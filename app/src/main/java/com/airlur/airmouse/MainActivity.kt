package com.airlur.airmouse

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : Activity(), HidMouse.Listener {

    companion object {
        private const val REQ_PERMS = 100
        private const val REQ_DISCOVERABLE = 200
    }

    private lateinit var status: TextView
    private lateinit var dot: DotView
    private lateinit var sensLabel: TextView
    private lateinit var engine: AirMouseEngine
    private lateinit var hid: HidMouse

    private var leftDown = false
    private var rightDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = AirMouseEngine(this)
        hid = HidMouse(this, this)
        buildUi()

        engine.onDelta = { dx, dy ->
            hid.moveBy(dx, dy)
            dot.push(dx, dy)
        }

        requestBluetoothPermissions()
    }

    override fun onResume() {
        super.onResume()
        engine.start()
    }

    override fun onPause() {
        super.onPause()
        engine.stop()
        // 离开界面时不要留下按住的键，否则平板上的左键会一直卡住
        if (leftDown) {
            hid.setButton(HidMouse.BTN_LEFT, false)
            leftDown = false
        }
        if (rightDown) {
            hid.setButton(HidMouse.BTN_RIGHT, false)
            rightDown = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hid.stop()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weight(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    )

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF10141C.toInt())
            setPadding(dp(16), dp(28), dp(16), dp(18))
        }

        // ---- 状态 ----
        status = TextView(this).apply {
            text = "正在初始化…"
            setTextColor(0xFF9AA7B8.toInt())
            textSize = 13f
            setLineSpacing(0f, 1.15f)
        }
        root.addView(status, matchWrap())

        // ---- 顶部按钮 ----
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val connectBtn = mkButton("连接已配对设备")
        connectBtn.setOnClickListener { showBondedDevices() }
        val discoverBtn = mkButton("让平板发现我")
        discoverBtn.setOnClickListener { makeDiscoverable() }
        topRow.addView(connectBtn, weight())
        topRow.addView(discoverBtn, weight())
        root.addView(topRow, matchWrap())

        // ---- 中间：动感反馈圆盘 ----
        dot = DotView(this)
        root.addView(
            dot,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // ---- 灵敏度 ----
        sensLabel = TextView(this).apply {
            setTextColor(0xFF9AA7B8.toInt())
            textSize = 12f
            text = "灵敏度"
        }
        root.addView(sensLabel, matchWrap())

        val sensBar = SeekBar(this).apply { max = 100; progress = 33 }
        sensBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                applySensitivity(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        root.addView(sensBar, matchWrap())
        applySensitivity(sensBar.progress)

        // ---- 方向修正 ----
        val invRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val invX = mkButton("水平反向")
        invX.setOnClickListener {
            engine.invertX = !engine.invertX
            invX.text = if (engine.invertX) "水平反向 ✓" else "水平反向"
        }
        val invY = mkButton("垂直反向")
        invY.setOnClickListener {
            engine.invertY = !engine.invertY
            invY.text = if (engine.invertY) "垂直反向 ✓" else "垂直反向"
        }
        invRow.addView(invX, weight())
        invRow.addView(invY, weight())
        root.addView(invRow, matchWrap())

        // ---- 底部：左键 / 滚轮 / 右键 ----
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val leftBtn = mkPad("左键")
        bindMouseButton(leftBtn, HidMouse.BTN_LEFT, isLeft = true)

        val wheelStrip = WheelStrip(this).apply {
            onScroll = { steps -> hid.wheel(steps) }
        }

        val rightBtn = mkPad("右键")
        bindMouseButton(rightBtn, HidMouse.BTN_RIGHT, isLeft = false)

        controls.addView(leftBtn, LinearLayout.LayoutParams(0, dp(104), 3f))
        controls.addView(
            wheelStrip,
            LinearLayout.LayoutParams(dp(92), dp(104)).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
        )
        controls.addView(rightBtn, LinearLayout.LayoutParams(0, dp(104), 3f))

        root.addView(
            controls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        setContentView(root)
    }

    private fun applySensitivity(progress: Int) {
        val s = 150.0 + progress * 13.5
        engine.sensitivity = s
        sensLabel.text = "灵敏度  ${s.toInt()}   （慢速精准 ← → 快速跨屏）"
    }

    private fun mkButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(0xFFE6EDF7.toInt())
        setBackgroundColor(0xFF1E2633.toInt())
    }

    private fun mkPad(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 19f
        gravity = Gravity.CENTER
        setTextColor(0xFFE6EDF7.toInt())
        setBackgroundColor(0xFF22304A.toInt())
        isClickable = true
    }

    private fun bindMouseButton(view: View, mask: Int, isLeft: Boolean) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 手指压下会带动手机机身轻微位移，冻住 120ms 免得指针乱窜
                    engine.suppress(120)
                    hid.setButton(mask, true)
                    if (isLeft) leftDown = true else rightDown = true
                    v.setBackgroundColor(0xFF3A5480.toInt())
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hid.setButton(mask, false)
                    if (isLeft) leftDown = false else rightDown = false
                    engine.suppress(160)
                    v.setBackgroundColor(0xFF22304A.toInt())
                }
            }
            true
        }
    }

    // -------------------------------------------------------------- 蓝牙流程

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Android 11 及以下：BLUETOOTH / BLUETOOTH_ADMIN 是安装即授予的普通权限
            hid.start()
            return
        }
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) {
            hid.start()
        } else {
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            hid.start()
        } else {
            status.text = "缺少蓝牙权限，无法工作。请到 \n设置 → 应用 → AirMouse → 权限 里全部允许。"
        }
    }

    private fun makeDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        try {
            startActivityForResult(intent, REQ_DISCOVERABLE)
        } catch (t: Throwable) {
            toast("无法开启「可被发现」：${t.message}")
        }
    }

    private fun showBondedDevices() {
        if (!hid.isAdapterEnabled()) {
            toast("请先打开蓝牙")
            return
        }
        val list = hid.bondedDevices()
        if (list.isEmpty()) {
            toast("没有已配对设备。请先点「让平板发现我」，在平板上搜索并配对「AirMouse」")
            return
        }
        val names = Array<CharSequence>(list.size) { index ->
            val device = list[index]
            try {
                "${device.name ?: "未知"}    ${device.address}"
            } catch (e: SecurityException) {
                "未知设备"
            }
        }

        AlertDialog.Builder(this)
            .setTitle("选择要连接的设备")
            .setItems(names) { _, which -> hid.connectDevice(list[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onStatus(text: String) {
        runOnUiThread { status.text = text }
    }

    // ------------------------------------------------------------- 自定义视图

    /** 中间那块圆盘：哪边在动、动得多快，一眼可见，便于调灵敏度 */
    private class DotView(context: android.content.Context) : View(context) {

        private var ox = 0f
        private var oy = 0f
        private var maxOff = 100f

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x22FFFFFF
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = 0x18FFFFFF
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4CC2FF.toInt()
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x334CC2FF
        }

        fun push(dx: Float, dy: Float) {
            ox += dx * 0.6f
            oy += dy * 0.6f
            val m = hypot(ox, oy)
            if (m > maxOff && m > 0f) {
                ox = ox / m * maxOff
                oy = oy / m * maxOff
            }
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            maxOff = minOf(w, h) * 0.36f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f

            canvas.drawCircle(cx, cy, maxOff, ringPaint)
            canvas.drawLine(cx - maxOff, cy, cx + maxOff, cy, crossPaint)
            canvas.drawLine(cx, cy - maxOff, cx, cy + maxOff, crossPaint)

            val px = cx + ox
            val py = cy + oy
            canvas.drawCircle(px, py, 26f, glowPaint)
            canvas.drawCircle(px, py, 14f, dotPaint)

            ox *= 0.94f
            oy *= 0.94f
            if (abs(ox) > 0.4f || abs(oy) > 0.4f) postInvalidateOnAnimation()
        }
    }

    /** 竖条 = 滚轮，手指上下拖 */
    private class WheelStrip(context: android.content.Context) : View(context) {

        var onScroll: ((Int) -> Unit)? = null

        private var lastY = 0f
        private var acc = 0f
        private var highlight = false

        private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            color = 0xFF6E82A3.toInt()
        }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2E3F5C.toInt()
        }

        init {
            setBackgroundColor(0xFF161C27.toInt())
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = event.y
                    highlight = true
                    invalidate()
                }

                MotionEvent.ACTION_MOVE -> {
                    acc += lastY - event.y // 手指上滑 = 滚轮向上
                    lastY = event.y
                    val step = (acc / 55f).toInt()
                    if (step != 0) {
                        acc -= step * 55f
                        onScroll?.invoke(step)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    acc = 0f
                    highlight = false
                    invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val s = minOf(width, height) * 0.16f

            // 中间滑块
            knob.alpha = if (highlight) 0xCC else 0x66
            canvas.drawRoundRect(
                cx - width * 0.22f, cy - s * 2.2f,
                cx + width * 0.22f, cy + s * 2.2f,
                width * 0.22f, width * 0.22f, knob
            )

            // 上下箭头
            canvas.drawLine(cx - s, cy - s * 2.6f + s, cx, cy - s * 2.6f, chevron)
            canvas.drawLine(cx + s, cy - s * 2.6f + s, cx, cy - s * 2.6f, chevron)
            canvas.drawLine(cx - s, cy + s * 2.6f - s, cx, cy + s * 2.6f, chevron)
            canvas.drawLine(cx + s, cy + s * 2.6f - s, cx, cy + s * 2.6f, chevron)
        }
    }
}
