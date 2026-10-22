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

        private const val TAG = "HidMouse"
        private const val TICK_MS = 16L
        private const val MAX_CHUNK = 127
        /** 累积上限。放大容易让指针「甩飞」，这里压到 240 像素 */
        private const val MAX_ACCUM = 240f

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

    /** 最近一次连上的设备，断线后用它重连 */
    @Volatile private var lastDevice: BluetoothDevice? = null

    @Volatile private var retryIndex = 0
    @Volatile private var registerRetry = 0
    @Volatile private var stopped = false

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
            status("这台设备没有蓝牙适配器")
            notifyChanged()
            return
        }
        if (proxy != null) return
        try {
            if (!ad.isEnabled) {
                status("请先在系统设置里打开蓝牙")
                notifyChanged()
                return
            }
            status("正在注册为蓝牙鼠标…")
            notifyChanged()
            val ok = ad.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
            if (!ok) {
                status("无法获取 HID Device 服务：这台设备的蓝牙栈可能不支持")
                notifyChanged()
            }
        } catch (e: SecurityException) {
            status("缺少蓝牙权限")
            notifyChanged()
        } catch (t: Throwable) {
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
        try {
            if (hd != null && registered) hd.unregisterApp()
        } catch (_: Throwable) {
        }
        try {
            if (ad != null && hd != null) {
                ad.closeProfileProxy(BluetoothProfile.HID_DEVICE, hd)
            }
        } catch (_: Throwable) {
        }
        proxy = null
        host = null
        registered = false
        connectingDevice = null
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
        val hd = proxy
        if (hd == null || !registered) {
            status("还没注册成鼠标，请稍等一秒再试")
            notifyChanged()
            return
        }
        if (host != null && host?.address == device.address) {
            status("这台设备已经连接了，可以直接使用")
            notifyChanged()
            return
        }
        sendHandler.post {
            try {
                val name = safeName(device)
                connectingDevice = device
                lastDevice = device
                retryIndex = 0
                notifyChanged()
                val ok = hd.connect(device)
                if (ok) {
                    status("正在连接 $name …")
                } else {
                    connectingDevice = null
                    status("连接请求被拒绝（$name），请先在平板上忽略此设备再重新配对")
                }
                notifyChanged()
            } catch (e: SecurityException) {
                connectingDevice = null
                status("缺少蓝牙权限")
                notifyChanged()
            } catch (t: Throwable) {
                connectingDevice = null
                status("连接失败：${t.message}")
                notifyChanged()
            }
        }
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
        adapter?.bondedDevices?.toList().orEmpty()
    } catch (e: SecurityException) {
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
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hd = p as? BluetoothHidDevice
            if (hd == null) {
                status("系统没有提供 HID Device 服务")
                notifyChanged()
                return
            }
            proxy = hd
            registerNow(hd)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            proxy = null
            registered = false
            host = null
            status("HID 服务断开")
            notifyChanged()
        }
    }

    private fun registerNow(hd: BluetoothHidDevice) {
        // SDP 里的名字只用于「浏览服务」，平板蓝牙列表显示的是本机蓝牙名
        val sdp = BluetoothHidDeviceAppSdpSettings(
            localName,
            "Phone as a Bluetooth mouse",
            "Airlur",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            HidDescriptors.MOUSE
        )
        try {
            val ok = hd.registerApp(sdp, null, null, exec, callback)
            if (!ok) {
                status("registerApp 返回 false —— 这台设备可能禁用了 HID Device 角色")
                notifyChanged()
            }
        } catch (e: SecurityException) {
            status("缺少蓝牙权限")
            notifyChanged()
        } catch (t: Throwable) {
            status("注册异常：${t.message}")
            notifyChanged()
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
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
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    connectingDevice = device
                    status("正在连接 ${safeName(device)} …")
                }

                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    connectingDevice = null
                    lastDevice = device
                    retryIndex = 0
                    autoReconnect = true
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
            hd.sendReport(dev, 0, report)
            sentReports += 1
        } catch (e: SecurityException) {
            Log.w(TAG, "sendReport 权限不足")
        } catch (t: Throwable) {
            Log.w(TAG, "sendReport 失败", t)
        }
    }
}
