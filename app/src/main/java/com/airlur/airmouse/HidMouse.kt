package com.airlur.airmouse

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 把本机注册成一台「蓝牙鼠标」（HID Device 角色）。
 *
 * 连接状态说明（这是最容易混淆的地方）：
 *   配对(bonded)  ≠  连接(connected)
 *   配对只是双方交换过密钥、彼此记住；连接才是 HID 会话真正建立。
 *   蓝牙列表里出现设备只代表配对过，不代表鼠标能用。
 *
 * connect() 的返回值只表示「请求已提交」，真正结果在 onConnectionStateChanged 回调里。
 */
class HidMouse(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        /** 一行状态文字，给连接面板显示 */
        fun onStatus(text: String)

        /** 任何连接状态变化，通知界面刷新 */
        fun onChanged()
    }

    companion object {
        const val BTN_LEFT = 1
        const val BTN_RIGHT = 2
        const val BTN_MIDDLE = 4

        /** 连接阶段，用于界面显示三态 */
        const val PHASE_IDLE = 0
        const val PHASE_CONNECTING = 1
        const val PHASE_CONNECTED = 2
        const val PHASE_FAILED = 3

        private const val TAG = "HidMouse"
        private const val TICK_MS = 16L
        private const val MAX_CHUNK = 127
        /** 累积上限。放大容易让指针「甩飞」，这里压到 240 像素 */
        private const val MAX_ACCUM = 240f

        /** 点了连接之后多久还没连上就判定失败 */
        private const val CONNECT_TIMEOUT_MS = 12000L

        /** 断线后自动重连：最多尝试次数与退避间隔 */
        private val RETRY_DELAYS = longArrayOf(2000L, 4000L, 8000L, 15000L, 30000L)
    }

    private val main = Handler(Looper.getMainLooper())
    private val sendThread = HandlerThread("hid-send").also { it.start() }
    private val sendHandler = Handler(sendThread.looper)
    private val exec: Executor = Executors.newSingleThreadExecutor()

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var proxy: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var registered = false

    /** 正在连接中的设备（UI 用来显示「连接中…」） */
    @Volatile var connectingDevice: BluetoothDevice? = null
        private set

    /** 当前连接阶段，界面用它区分 可连接 / 连接中 / 已连接 / 失败 */
    @Volatile var phase: Int = PHASE_IDLE
        private set

    /** 阶段对应的设备地址（用地址比对更可靠，名字可能取不到） */
    @Volatile var phaseAddress: String? = null
        private set

    /** 失败原因，界面直接显示 */
    @Volatile var phaseError: String? = null
        private set

    /** 最近一次连上的设备，断线后用它重连 */
    @Volatile private var lastDevice: BluetoothDevice? = null

    @Volatile private var retryIndex = 0
    @Volatile private var registerRetry = 0
    @Volatile private var stopped = false

    private var connectTimeout: Runnable = Runnable { }

    /** 是否允许断线自动重连 */
    @Volatile var autoReconnect: Boolean = true

    /** 已成功发出的 HID 报告数（诊断用） */
    @Volatile var sentReports: Long = 0L
        private set

    val isConnected: Boolean get() = host != null && registered
    val isRegistered: Boolean get() = registered
    val connectedName: String get() = safeName(host)
    val isConnecting: Boolean get() = connectingDevice != null

    /** 本机蓝牙名称——平板蓝牙列表里找的就是这个名字，不是 AirMouse */
    val localName: String
        get() = try {
            adapter?.name ?: "本机"
        } catch (e: SecurityException) {
            "本机"
        }

    // 以下状态只在 sendHandler 线程访问
    private var accX = 0f
    private var accY = 0f
    private var accWheel = 0f
    private var buttons = 0
    private var dirty = false

    private val ticker = object : Runnable {
        override fun run() {
            flush()
            sendHandler.postDelayed(this, TICK_MS)
        }
    }

    private val retryRunnable = object : Runnable {
        override fun run() {
            if (stopped || !autoReconnect) return
            val dev = lastDevice ?: return
            if (host != null) return
            val hd = proxy ?: return
            if (!registered) return
            try {
                connectingDevice = dev
                notifyChanged()
                val ok = hd.connect(dev)
                if (!ok) {
                    connectingDevice = null
                    status("自动重连请求被拒绝")
                    notifyChanged()
                }
            } catch (e: SecurityException) {
                connectingDevice = null
                status("缺少蓝牙权限")
                notifyChanged()
            } catch (t: Throwable) {
                connectingDevice = null
                status("自动重连失败：${t.message}")
                notifyChanged()
            }
        }
    }

    // ------------------------------------------------------------------ 对外

    fun start() {
        stopped = false
        val ad = adapter
        if (ad == null) {
            AppLog.add("HID", "start: 没有蓝牙适配器")
            status("这台设备没有蓝牙适配器")
            notifyChanged()
            return
        }
        if (proxy != null) {
            AppLog.add("HID", "start: 已持有 proxy，跳过")
            return
        }
        try {
            AppLog.add("HID", "start: 蓝牙已开启=" + ad.isEnabled)
            if (!ad.isEnabled) {
                status("请先在系统设置里打开蓝牙")
                notifyChanged()
                return
            }
            status("正在注册为蓝牙鼠标…")
            notifyChanged()
            val ok = ad.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
            AppLog.add("HID", "getProfileProxy(HID_DEVICE) 返回 " + ok)
            if (!ok) {
                status("无法获取 HID Device 服务：这台设备的蓝牙栈可能不支持")
                notifyChanged()
            }
        } catch (e: SecurityException) {
            AppLog.add("HID", "start: SecurityException " + e.message)
            status("缺少蓝牙权限")
            notifyChanged()
        } catch (t: Throwable) {
            AppLog.add("HID", "start: 异常 " + t)
            status("启动失败：${t.message}")
            notifyChanged()
        }
    }

    fun stop() {
        stopped = true
        main.removeCallbacks(retryRunnable)
        main.removeCallbacks(reRegisterRunnable)
        sendHandler.removeCallbacks(ticker)
        val ad = adapter
        val hd = proxy
        // 不管 registered 是什么值都尝试注销一次。
        // 系统只在 registerApp 之后才可能回调 registered=true，万一回调丢了，
        // 只按 registered 判断就会漏掉注销，留下「HID 被占用」的坑，
        // 下次 registerApp 直接返回 false，只能靠关蓝牙才能清掉。
        try {
            hd?.unregisterApp()
        } catch (_: Throwable) {
        }
        registered = false
        try {
            if (ad != null && hd != null) {
                ad.closeProfileProxy(BluetoothProfile.HID_DEVICE, hd)
            }
        } catch (_: Throwable) {
        }
        proxy = null
        host = null
        connectingDevice = null
        phase = PHASE_IDLE
        phaseAddress = null
        phaseError = null
    }

    fun moveBy(dx: Float, dy: Float) {
        if (host == null || proxy == null) return
        sendHandler.post {
            accX = (accX + dx).coerceIn(-MAX_ACCUM, MAX_ACCUM)
            accY = (accY + dy).coerceIn(-MAX_ACCUM, MAX_ACCUM)
        }
    }

    fun wheel(steps: Int) {
        if (host == null || proxy == null) return
        sendHandler.post {
            accWheel = (accWheel + steps).coerceIn(-MAX_ACCUM, MAX_ACCUM)
        }
    }

    fun setButton(mask: Int, down: Boolean) {
        if (host == null || proxy == null) return
        sendHandler.post {
            buttons = if (down) (buttons or mask) else (buttons and mask.inv())
            dirty = true
        }
    }

    fun click(mask: Int, holdMs: Long) {
        if (host == null || proxy == null) return
        setButton(mask, true)
        sendHandler.postDelayed({
            if (proxy != null && host != null) {
                buttons = buttons and mask.inv()
                dirty = true
            }
        }, holdMs)
    }

    fun connectDevice(device: BluetoothDevice) {
        val name = safeName(device)
        AppLog.add("HID", "点击连接: name=" + name + " addr=" + device.address)
        AppLog.add("HID", "  当前状态: proxy=" + (proxy != null) + " registered=" + registered)

        val hd = proxy
        if (hd == null || !registered) {
            // 不再静默返回：界面必须看到失败原因
            fail(device, if (hd == null) "HID 服务未就绪" else "未注册为蓝牙鼠标")
            status("还没注册成鼠标，请稍等一秒再试")
            notifyChanged()
            return
        }
        if (host != null && host?.address == device.address) {
            phase = PHASE_CONNECTED
            phaseAddress = device.address
            phaseError = null
            status("这台设备已经连接了，可以直接使用")
            notifyChanged()
            return
        }

        // 立刻进入「连接中」，让界面有机会马上变化
        phase = PHASE_CONNECTING
        phaseAddress = device.address
        phaseError = null
        connectingDevice = device
        lastDevice = device
        retryIndex = 0
        notifyChanged()
        startConnectTimeout(device)

        sendHandler.post {
            try {
                val ok = hd.connect(device)
                AppLog.add("HID", "  connect() 返回 " + ok)
                if (ok) {
                    status("正在连接 $name …")
                } else {
                    fail(device, "连接请求被拒绝")
                    status("连接请求被拒绝（$name）")
                }
                notifyChanged()
            } catch (e: SecurityException) {
                AppLog.add("HID", "  connect 权限异常: " + e.message)
                fail(device, "缺少蓝牙权限")
                status("缺少蓝牙权限")
                notifyChanged()
            } catch (t: Throwable) {
                AppLog.add("HID", "  connect 异常: " + t)
                fail(device, "连接失败：" + t.message)
                status("连接失败：${t.message}")
                notifyChanged()
            }
        }
    }

    /** 把某个设备标成失败，并记下原因 */
    private fun fail(device: BluetoothDevice?, reason: String) {
        phase = PHASE_FAILED
        phaseAddress = device?.address
        phaseError = reason
        connectingDevice = null
        main.removeCallbacks(connectTimeout)
        AppLog.add("HID", "失败: " + reason + " addr=" + (device?.address ?: "null"))
    }

    /** 发起连接后长时间没连上，判定失败，避免界面一直转圈 */
    private fun startConnectTimeout(device: BluetoothDevice) {
        main.removeCallbacks(connectTimeout)
        connectTimeout = Runnable {
            if (phase == PHASE_CONNECTING && phaseAddress == device.address) {
                fail(device, "连接超时")
                notifyChanged()
            }
        }
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    /**
     * 断开当前连接。
     *
     * 这里刻意不静默返回：没连上时也要给出可读的提示，
     * 否则用户点了没反应，会以为按钮坏了。
     */
    fun disconnectCurrent() {
        val hd = proxy
        if (hd == null) {
            status("HID 服务还没就绪，稍后再试")
            notifyChanged()
            return
        }
        autoReconnect = false
        main.removeCallbacks(retryRunnable)
        val dev = host
        if (dev == null) {
            val list = try {
                hd.connectedDevices
            } catch (e: SecurityException) {
                emptyList<BluetoothDevice>()
            }
            if (list.isNullOrEmpty()) {
                status("当前没有已连接的设备。若平板还显示连着，请在平板上「忽略此设备」")
                notifyChanged()
                return
            }
            sendHandler.post {
                for (d in list) {
                    try {
                        hd.disconnect(d)
                    } catch (_: Throwable) {
                    }
                }
                status("正在断开 ${list.size} 个设备…")
                notifyChanged()
            }
            return
        }
        sendHandler.post {
            try {
                hd.disconnect(dev)
                status("正在断开 ${safeName(dev)} …")
            } catch (t: Throwable) {
                status("断开失败：${t.message}")
            }
            notifyChanged()
        }
    }

    fun resumeAutoReconnect() {
        autoReconnect = true
        retryIndex = 0
    }

    fun bondedDevices(): List<BluetoothDevice> = try {
        val list = adapter?.bondedDevices?.toList().orEmpty()
        AppLog.add("HID", "bondedDevices: " + list.size + " 个")
        for (d in list) {
            AppLog.add("HID", "  - " + safeName(d) + " " + d.address +
                " bondState=" + d.bondState)
        }
        list
    } catch (e: SecurityException) {
        AppLog.add("HID", "bondedDevices: 缺少权限 " + e.message)
        emptyList()
    }

    fun isAdapterEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    // ------------------------------------------------------------- 内部实现

    private fun status(text: String) {
        main.post { listener.onStatus(text) }
    }

    private fun notifyChanged() {
        main.post { listener.onChanged() }
    }

    private fun safeName(device: BluetoothDevice?): String = try {
        device?.name ?: device?.address ?: "未知设备"
    } catch (e: SecurityException) {
        try {
            device?.address ?: "未知设备"
        } catch (e2: SecurityException) {
            "未知设备"
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile?) {
            AppLog.add("HID", "onServiceConnected: profile=" + profile +
                " proxy=" + (p != null) + " type=" + (p?.javaClass?.simpleName ?: "null"))
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hd = p as? BluetoothHidDevice
            if (hd == null) {
                AppLog.add("HID", "  拿到的不是 BluetoothHidDevice -> 系统未提供该服务")
                status("系统没有提供 HID Device 服务")
                notifyChanged()
                return
            }
            proxy = hd
            registerNow(hd)
        }

        override fun onServiceDisconnected(profile: Int) {
            AppLog.add("HID", "onServiceDisconnected: profile=" + profile)
            if (profile != BluetoothProfile.HID_DEVICE) return
            proxy = null
            registered = false
            host = null
            status("HID 服务断开")
            notifyChanged()
        }
    }

    private fun registerNow(hd: BluetoothHidDevice) {
        // SDP 里的名字只用于「浏览服务」，平板蓝牙列表显示的是本机蓝牙名。
        // 这里刻意用固定常量而不是 localName：某些机型上读名字可能抛异常，
        // 一旦抛在 registerApp 之前，注册就永远发不出去。
        val sdpName = "AirMouse"

        // 先清一次可能残留的注册。
        //
        // 系统里 Bluetooth 服务保存着一个 mUserUid，只有注册它的进程或系统自身
        // 才能清掉。断开 HID 连接后有些机型会主动撤销注册并留下这个占用，
        // 此时直接 registerApp 会立刻返回 false（日志里能看到只有 2ms 的间隔）。
        // 先 unregisterApp() 一次，能清掉「自己上一次」留下的占用。
        try {
            hd.unregisterApp()
        } catch (t: Throwable) {
            AppLog.add("HID", "  预清理 unregisterApp: " + t)
        }

        AppLog.add("HID", "registerNow: sdpName=" + sdpName)
        val sdp = BluetoothHidDeviceAppSdpSettings(
            sdpName,
            "Phone as a Bluetooth mouse",
            "Airlur",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            HidDescriptors.MOUSE
        )
        try {
            val ok = hd.registerApp(sdp, null, null, exec, callback)
            AppLog.add("HID", "  registerApp() 返回 " + ok +
                "  描述符=" + HidDescriptors.MOUSE.size + "字节")
            if (!ok) {
                status("registerApp 返回 false —— 这台设备可能禁用了 HID Device 角色")
                notifyChanged()
            }
        } catch (e: SecurityException) {
            AppLog.add("HID", "  registerApp SecurityException: " + e.message)
            status("缺少蓝牙权限")
            notifyChanged()
        } catch (t: Throwable) {
            AppLog.add("HID", "  registerApp 异常: " + t)
            status("注册异常：${t.message}")
            notifyChanged()
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            AppLog.add("HID", "onAppStatusChanged: registered=" + isRegistered +
                " plugged=" + safeName(pluggedDevice))
            registered = isRegistered
            if (isRegistered) {
                status("已注册为蓝牙鼠标，等待平板连接")
                registerRetry = 0
                if (pluggedDevice != null) {
                    lastDevice = pluggedDevice
                    try {
                        connectingDevice = pluggedDevice
                        proxy?.connect(pluggedDevice)
                    } catch (_: Throwable) {
                    }
                }
            } else {
                // 部分厂商在连接断开后会主动注销 HID 应用。
                // 这里不能干等，必须自己重新注册，否则界面会一直显示「未注册」。
                status("HID 注册被系统撤销，正在重新注册…")
                scheduleReRegister()
            }
            notifyChanged()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val stateName = when (state) {
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN(" + state + ")"
            }
            AppLog.add("HID", "onConnectionStateChanged: " + stateName + " dev=" + safeName(device))

            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    connectingDevice = device
                    phase = PHASE_CONNECTING
                    phaseAddress = device?.address
                    status("正在连接 ${safeName(device)} …")
                }

                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    connectingDevice = null
                    lastDevice = device
                    retryIndex = 0
                    autoReconnect = true
                    phase = PHASE_CONNECTED
                    phaseAddress = device?.address
                    phaseError = null
                    main.removeCallbacks(connectTimeout)
                    main.removeCallbacks(retryRunnable)
                    sendHandler.post {
                        accX = 0f
                        accY = 0f
                        accWheel = 0f
                        buttons = 0
                        dirty = true
                    }
                    status("已连接：${safeName(device)}")
                    sendHandler.removeCallbacks(ticker)
                    sendHandler.post(ticker)
                }

                BluetoothProfile.STATE_DISCONNECTING -> {
                    status("正在断开 ${safeName(device)} …")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasHost = host != null
                    host = null
                    connectingDevice = null
                    sendHandler.removeCallbacks(ticker)
                    main.removeCallbacks(connectTimeout)
                    if (phase == PHASE_CONNECTING) {
                        fail(device, "连接失败：设备未响应")
                    } else if (wasHost) {
                        phase = PHASE_IDLE
                        phaseAddress = null
                        phaseError = null
                    }
                    status(if (wasHost) "连接已断开" else "设备未连接")
                    scheduleRetry()
                }
            }
            notifyChanged()
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            try {
                proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            } catch (_: Throwable) {
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            try {
                proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (_: Throwable) {
            }
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) = Unit

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            host = null
            connectingDevice = null
            sendHandler.removeCallbacks(ticker)
            status("主机断开了虚拟线缆")
            scheduleRetry()
            notifyChanged()
        }
    }

    /** 断线后按退避策略自动重连；不会无限重试 */
    private fun scheduleRetry() {
        if (stopped || !autoReconnect) return
        val dev = lastDevice ?: return
        if (retryIndex >= RETRY_DELAYS.size) {
            status("多次重连失败，请到连接面板手动连接")
            return
        }
        val delay = RETRY_DELAYS[retryIndex]
        retryIndex++
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, delay)
        status("${delay / 1000} 秒后尝试重连…")
    }

    /** HID 应用被系统注销后重新注册，最多 3 次，间隔递增 */
    private fun scheduleReRegister() {
        if (stopped) return
        val hd = proxy
        if (hd == null) return
        if (registerRetry >= 3) {
            status("重新注册失败，请退出重进 App")
            return
        }
        val delay = 800L * (registerRetry + 1)
        registerRetry++
        main.removeCallbacks(reRegisterRunnable)
        main.postDelayed(reRegisterRunnable, delay)
    }

    private val reRegisterRunnable = object : Runnable {
        override fun run() {
            if (stopped || registered) return
            val hd = proxy ?: return
            try {
                registerNow(hd)
            } catch (_: Throwable) {
            }
        }
    }

    /** 只在 sendHandler 线程执行 */
    private fun flush() {
        val hd = proxy ?: return
        val dev = host ?: return
        if (!registered) return

        var dx = accX.toInt().coerceIn(-MAX_ACCUM.toInt(), MAX_ACCUM.toInt())
        var dy = accY.toInt().coerceIn(-MAX_ACCUM.toInt(), MAX_ACCUM.toInt())
        accX -= dx
        accY -= dy

        val w = accWheel.toInt().coerceIn(-MAX_CHUNK, MAX_CHUNK)
        accWheel -= w

        if (dx == 0 && dy == 0 && w == 0 && !dirty) return
        dirty = false

        if (dx == 0 && dy == 0) {
            send(hd, dev, dx, dy, w)
            return
        }

        var remainingX = dx
        var remainingY = dy
        var guard = 0
        while ((remainingX != 0 || remainingY != 0) && guard < 2) {
            val cx = remainingX.coerceIn(-MAX_CHUNK, MAX_CHUNK)
            val cy = remainingY.coerceIn(-MAX_CHUNK, MAX_CHUNK)
            send(hd, dev, cx, cy, 0)
            remainingX -= cx
            remainingY -= cy
            guard++
        }
        if (w != 0) send(hd, dev, 0, 0, w)
    }

    private fun send(hd: BluetoothHidDevice, dev: BluetoothDevice, dx: Int, dy: Int, wheel: Int) {
        val report = byteArrayOf(
            buttons.toByte(),
            dx.coerceIn(-MAX_CHUNK, MAX_CHUNK).toByte(),
            dy.coerceIn(-MAX_CHUNK, MAX_CHUNK).toByte(),
            wheel.coerceIn(-MAX_CHUNK, MAX_CHUNK).toByte()
        )
        try {
            val ok = hd.sendReport(dev, 0, report)
            if (ok) {
                sentReports += 1
                logSend(dx, dy, wheel)
            } else {
                failReports += 1
                logSendFail()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "sendReport 权限不足")
            AppLog.add("SEND", "sendReport 权限不足")
        } catch (t: Throwable) {
            Log.w(TAG, "sendReport 失败", t)
            AppLog.add("SEND", "sendReport 异常: " + t)
        }
    }

    /** sendReport 返回 false 的次数：报告没送出去，界面不会有任何反应 */
    @Volatile private var failReports = 0L
    private var lastFailLogAt = 0L

    private fun logSendFail() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailLogAt < 1000L) return
        lastFailLogAt = now
        AppLog.add("SEND", "sendReport 返回 false，累计失败=" + failReports)
    }

    /**
     * 发送日志做了限流：指针移动时每帧都会发一次报告，
     * 不加限制会瞬间把日志冲爆，把真正的错误挤出缓冲区。
     */
    private var lastSendLogAt = 0L
    private var sendLogSkipped = 0

    private fun logSend(dx: Int, dy: Int, wheel: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSendLogAt < 400L) {
            sendLogSkipped++
            return
        }
        lastSendLogAt = now
        val skipped = if (sendLogSkipped > 0) " (+" + sendLogSkipped + " 条省略)" else ""
        sendLogSkipped = 0
        AppLog.add(
            "SEND",
            "dx=" + dx + " dy=" + dy + " wheel=" + wheel +
                " btn=" + buttons + " 累计=" + sentReports + skipped
        )
    }
}
