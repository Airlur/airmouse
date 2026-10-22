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
 * 用的全是平台 API，没有任何第三方库：
 *   BluetoothAdapter.getProfileProxy(HID_DEVICE)  -> 拿到 BluetoothHidDevice
 *   registerApp(SDP 设置, 报告描述符)             -> 对外广播自己是鼠标
 *   sendReport(device, 0, byte[4])                -> 发一次相对位移
 *
 * 关键约束：
 *   1) sendReport 是同步 Binder 调用，绝不能在 UI 线程调 -> 独立 HandlerThread
 *   2) 传感器 50~100Hz，发送固定 60Hz，中间用「亚像素累加器」做量化，
 *      否则慢速移动会被取整成 0，表现为「转半天才动一下」
 *   3) 单次位移 HID 协议上限 ±127，快速甩动要拆成多帧
 */
class HidMouse(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatus(text: String)
    }

    companion object {
        const val BTN_LEFT = 1
        const val BTN_RIGHT = 2
        const val BTN_MIDDLE = 4

        private const val TAG = "HidMouse"
        private const val TICK_MS = 16L
        private const val MAX_CHUNK = 127
        private const val MAX_ACCUM = 600f
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

    // ------------------------------------------------------------------ 对外

    fun start() {
        val ad = adapter
        if (ad == null) {
            status("这台设备没有蓝牙适配器")
            return
        }
        if (proxy != null) return
        try {
            if (!ad.isEnabled) {
                status("请先在系统设置里打开蓝牙")
                return
            }
            status("正在获取 HID Device 服务…")
            val ok = ad.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
            if (!ok) status("无法获取 HID Device 服务：这台设备的蓝牙栈可能不支持（见 README 的 Plan B）")
        } catch (e: SecurityException) {
            status("缺少蓝牙权限")
        } catch (t: Throwable) {
            status("启动失败：${t.message}")
        }
    }

    fun stop() {
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
    }

    /** 传感器线程直接调用，内部转投到发送线程 */
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

    fun connectDevice(device: BluetoothDevice) {
        val hd = proxy
        if (hd == null || !registered) {
            status("还没注册成鼠标，先等注册完成")
            return
        }
        sendHandler.post {
            try {
                val name = safeName(device)
                val ok = hd.connect(device)
                status(if (ok) "正在连接 $name…" else "连接请求被拒绝（$name）")
            } catch (e: SecurityException) {
                status("缺少蓝牙权限")
            } catch (t: Throwable) {
                status("连接失败：${t.message}")
            }
        }
    }

    fun disconnectCurrent() {
        val hd = proxy ?: return
        val dev = host ?: return
        sendHandler.post {
            try {
                hd.disconnect(dev)
            } catch (_: Throwable) {
            }
        }
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

    private fun safeName(device: BluetoothDevice?): String = try {
        device?.name ?: "未知设备"
    } catch (e: SecurityException) {
        "未知设备"
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile?) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hd = p as? BluetoothHidDevice
            if (hd == null) {
                status("系统没有提供 HID Device 服务")
                return
            }
            proxy = hd
            registerNow(hd)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            proxy = null
            registered = false
            status("HID 服务断开")
        }
    }

    private fun registerNow(hd: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "AirMouse",
            "Phone as a Bluetooth mouse",
            "Airlur",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            HidDescriptors.MOUSE
        )
        status("正在注册为蓝牙鼠标…")
        try {
            val ok = hd.registerApp(sdp, null, null, exec, callback)
            if (!ok) status("registerApp 返回 false —— 这台设备可能禁用了 HID Device 角色")
        } catch (e: SecurityException) {
            status("缺少蓝牙权限")
        } catch (t: Throwable) {
            status("注册异常：${t.message}")
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (isRegistered) {
                status("已注册为蓝牙鼠标 ✓  现在去平板上配对「AirMouse」")
                // 如果系统已经有「已插线」的主机（之前配对过），直接连上去
                if (pluggedDevice != null) {
                    try {
                        proxy?.connect(pluggedDevice)
                    } catch (_: Throwable) {
                    }
                }
            } else {
                status("注册被撤销或失败（华为可能禁用了 HID Device 角色）")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    sendHandler.post {
                        accX = 0f
                        accY = 0f
                        accWheel = 0f
                        buttons = 0
                        dirty = true
                    }
                    status("已连接：${safeName(device)} ✓  可以开始用手机空鼠了")
                    sendHandler.removeCallbacks(ticker)
                    sendHandler.post(ticker)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    host = null
                    sendHandler.removeCallbacks(ticker)
                    status("已断开连接")
                }
            }
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
            // 只有按键状态变化或滚轮
            send(hd, dev, dx, dy, w)
            return
        }

        // 位移超过 ±127 时拆成多帧，最多 6 帧防止积压
        var remainingX = dx
        var remainingY = dy
        var guard = 0
        while ((remainingX != 0 || remainingY != 0) && guard < 6) {
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
        } catch (e: SecurityException) {
            Log.w(TAG, "sendReport 权限不足")
        } catch (t: Throwable) {
            Log.w(TAG, "sendReport 失败", t)
        }
    }
}
