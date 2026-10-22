package com.airlur.airmouse

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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

        // iOS 浅色调色板
        private val C_BG = 0xFFF2F2F7.toInt()
        private val C_CARD = 0xFFFFFFFF.toInt()
        private val C_LABEL = 0xFF000000.toInt()
        private val C_SEC = 0xFF8E8E93.toInt()
        private val C_SEP = 0xFFE5E5EA.toInt()
        private val C_ACCENT = 0xFF007AFF.toInt()
        private val C_GREEN = 0xFF34C759.toInt()
        private val C_FILL = 0x1F767680
        private val C_SOFT = 0xFFEFEFF4.toInt()

        private const val MODE_AIR = 0
        private const val MODE_PAD = 1
    }

    private lateinit var engine: AirMouseEngine
    private lateinit var hid: HidMouse
    private lateinit var touchpad: TouchpadView

    private lateinit var pill: TextView
    private lateinit var segAir: TextView
    private lateinit var segPad: TextView
    private lateinit var paneAir: LinearLayout
    private lateinit var panePad: LinearLayout
    private lateinit var disc: DiscView
    private lateinit var diag: TextView

    private lateinit var sensLabel: TextView
    private lateinit var sensBar: SeekBar
    private lateinit var padSensLabel: TextView
    private lateinit var padSensBar: SeekBar

    private var mode = MODE_AIR
    private var leftDown = false
    private var rightDown = false
    private var lastStatus = ""

    private val ui = Handler(Looper.getMainLooper())
    private val telemetryTick = object : Runnable {
        override fun run() {
            refreshDiagnostics()
            ui.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = AirMouseEngine(this)
        hid = HidMouse(this, this)
        buildUi()

        engine.onDelta = { dx, dy ->
            hid.moveBy(dx, dy)
            disc.push(dx, dy)
        }

        requestBluetoothPermissions()
    }

    override fun onResume() {
        super.onResume()
        engine.start()
        ui.removeCallbacks(telemetryTick)
        ui.post(telemetryTick)
        setMode(mode)
    }

    override fun onPause() {
        super.onPause()
        engine.stop()
        ui.removeCallbacks(telemetryTick)
        releaseButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(telemetryTick)
        hid.stop()
    }

    private fun releaseButtons() {
        if (leftDown) {
            hid.setButton(HidMouse.BTN_LEFT, false)
            leftDown = false
        }
        if (rightDown) {
            hid.setButton(HidMouse.BTN_RIGHT, false)
            rightDown = false
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight)

    private fun vp(h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, weight)

    private fun txt(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    // -------------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }

        // ---- 标题 + 状态 ----
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(txt("AirMouse", 30f, C_LABEL, true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        pill = TextView(this).apply {
            text = "未注册"
            textSize = 12f
            setTextColor(C_SEC)
            background = bg(0x1A8E8E93, 20)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        head.addView(pill)
        root.addView(head, vp(ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- 分段控制 ----
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(C_FILL, 9)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        segAir = segButton("空鼠")
        segPad = segButton("触控板")
        seg.addView(segAir, lp(0, dp(30), 1f))
        seg.addView(segPad, lp(0, dp(30), 1f))
        root.addView(seg, vp(dp(34)).apply { topMargin = dp(12); bottomMargin = dp(12) })

        segAir.setOnClickListener { setMode(MODE_AIR) }
        segPad.setOnClickListener { setMode(MODE_PAD) }

        // ---- 内容区 ----
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        paneAir = buildAirPane()
        panePad = buildPadPane()
        content.addView(paneAir, vp(0, 1f))
        content.addView(panePad, vp(0, 1f))
        root.addView(content, vp(0, 1f))

        // ---- 诊断卡 ----
        val diagCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(C_CARD, 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        diag = txt("", 11f, C_SEC).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.3f)
        }
        diagCard.addView(diag, vp(ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(diagCard, vp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        setContentView(root)
        setMode(MODE_AIR)
    }

    private fun segButton(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(C_LABEL)
    }

    private fun buildAirPane(): LinearLayout {
        val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        disc = DiscView(this)
        pane.addView(disc, vp(0, 1f))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(C_CARD, 12)
            setPadding(dp(16), dp(4), dp(16), dp(10))
        }

        val sensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        sensRow.addView(txt("灵敏度", 16f, C_LABEL), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sensLabel = txt("", 15f, C_SEC)
        sensRow.addView(sensLabel)
        card.addView(sensRow, vp(ViewGroup.LayoutParams.WRAP_CONTENT))

        sensBar = SeekBar(this).apply { max = 100; progress = 40 }
        sensBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                applySensitivity(p)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        card.addView(sensBar, vp(dp(36)))
        applySensitivity(sensBar.progress)

        card.addView(divider(), vp(1))
        card.addView(toggleRow("水平反向") { v -> engine.invertX = v }, vp(dp(46)))
        card.addView(divider(), vp(1))
        card.addView(toggleRow("垂直反向") { v -> engine.invertY = v }, vp(dp(46)))

        pane.addView(card, vp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val leftBtn = bigButton("左键")
        bindButton(leftBtn, HidMouse.BTN_LEFT, true)
        val wheelBtn = bigButton("滚轮").apply {
            textSize = 13f
            setTextColor(C_SEC)
            background = bg(C_SOFT, 12)
        }
        bindWheel(wheelBtn)
        val rightBtn = bigButton("右键")
        bindButton(rightBtn, HidMouse.BTN_RIGHT, false)

        row.addView(leftBtn, lp(0, dp(76), 1f))
        row.addView(wheelBtn, lp(dp(82), dp(76)).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        row.addView(rightBtn, lp(0, dp(76), 1f))
        pane.addView(row, vp(ViewGroup.LayoutParams.WRAP_CONTENT))

        return pane
    }

    private fun buildPadPane(): LinearLayout {
        val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        touchpad = TouchpadView(this).apply {
            background = bg(C_CARD, 14)
            onMove = { dx, dy -> hid.moveBy(dx, dy) }
            onLeftDown = {
                hid.setButton(HidMouse.BTN_LEFT, true)
                leftDown = true
            }
            onLeftUp = {
                hid.setButton(HidMouse.BTN_LEFT, false)
                leftDown = false
            }
            onRightClick = {
                hid.setButton(HidMouse.BTN_RIGHT, true)
                hid.setButton(HidMouse.BTN_RIGHT, false)
            }
            onWheel = { steps -> hid.wheel(steps) }
        }
        pane.addView(touchpad, vp(0, 1f))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(C_CARD, 12)
            setPadding(dp(16), dp(4), dp(16), dp(10))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(txt("指针速度", 16f, C_LABEL), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        padSensLabel = txt("", 15f, C_SEC)
        row.addView(padSensLabel)
        card.addView(row, vp(ViewGroup.LayoutParams.WRAP_CONTENT))

        padSensBar = SeekBar(this).apply { max = 100; progress = 45 }
        padSensBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                applyPadSpeed(p)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        card.addView(padSensBar, vp(dp(36)))
        applyPadSpeed(padSensBar.progress)

        pane.addView(card, vp(ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val leftBtn = bigButton("左键")
        bindButton(leftBtn, HidMouse.BTN_LEFT, true)
        val rightBtn = bigButton("右键")
        bindButton(rightBtn, HidMouse.BTN_RIGHT, false)
        btnRow.addView(leftBtn, lp(0, dp(76), 1f))
        btnRow.addView(rightBtn, lp(0, dp(76), 1f).apply { marginStart = dp(10) })
        pane.addView(btnRow, vp(ViewGroup.LayoutParams.WRAP_CONTENT))

        return pane
    }

    private fun divider(): View = View(this).apply { setBackgroundColor(C_SEP) }

    private fun toggleRow(label: String, onChange: (Boolean) -> Unit): LinearLayout {
        var on = false
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }
        row.addView(txt(label, 16f, C_LABEL), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = txt("关", 15f, C_SEC)
        row.addView(value)
        row.setOnClickListener {
            on = !on
            value.text = if (on) "开" else "关"
            value.setTextColor(if (on) C_ACCENT else C_SEC)
            onChange(on)
        }
        return row
    }

    private fun bigButton(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 17f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(C_LABEL)
        background = bg(C_CARD, 12)
        isClickable = true
    }

    private fun bindButton(v: View, mask: Int, isLeft: Boolean) {
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    engine.suppress(120)
                    hid.setButton(mask, true)
                    if (isLeft) leftDown = true else rightDown = true
                    view.background = bg(C_ACCENT, 12)
                    (view as TextView).setTextColor(0xFFFFFFFF.toInt())
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hid.setButton(mask, false)
                    if (isLeft) leftDown = false else rightDown = false
                    engine.suppress(160)
                    view.background = bg(C_CARD, 12)
                    (view as TextView).setTextColor(C_LABEL)
                }
            }
            true
        }
    }

    private fun bindWheel(v: View) {
        var lastY = 0f
        var acc = 0f
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = event.y
                    acc = 0f
                    view.background = bg(C_ACCENT, 12)
                    (view as TextView).setTextColor(0xFFFFFFFF.toInt())
                }

                MotionEvent.ACTION_MOVE -> {
                    acc += lastY - event.y
                    lastY = event.y
                    val step = dp(28).toFloat()
                    val steps = (acc / step).toInt()
                    if (steps != 0) {
                        acc -= steps * step
                        hid.wheel(steps)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.background = bg(C_SOFT, 12)
                    (view as TextView).setTextColor(C_SEC)
                }
            }
            true
        }
    }

    private fun applySensitivity(p: Int) {
        val s = 300.0 + p * 27.0
        engine.sensitivity = s
        sensLabel.text = s.toInt().toString()
    }

    private fun applyPadSpeed(p: Int) {
        val g = 0.6f + p * 0.028f
        touchpad.gain = g
        padSensLabel.text = String.format(java.util.Locale.US, "%.1f×", g)
    }

    private fun setMode(m: Int) {
        mode = m
        val air = (m == MODE_AIR)
        engine.enabled = air
        paneAir.visibility = if (air) View.VISIBLE else View.GONE
        panePad.visibility = if (air) View.GONE else View.VISIBLE
        segAir.background = if (air) bg(C_CARD, 7) else null
        segPad.background = if (air) null else bg(C_CARD, 7)
    }

    private fun refreshDiagnostics() {
        val gyro = if (engine.hasGyro) "正常" else "缺失"
        val rot = if (engine.hasRotation) "3D" else "2D"
        val conn = when {
            hid.isConnected -> "已连接"
            hid.isRegistered -> "待配对"
            else -> "未注册"
        }

        val sb = StringBuilder()
        sb.append("陀螺仪 ").append(gyro).append(" · 姿态 ").append(rot)
            .append(" · HID ").append(conn).append('\n')
        sb.append(String.format(
            java.util.Locale.US, "ω %+.2f %+.2f %+.2f  (X Y Z)",
            engine.telRawX, engine.telRawY, engine.telRawZ
        )).append('\n')
        sb.append(String.format(
            java.util.Locale.US, "yaw %+.2f pitch %+.2f -> dx %+.0f dy %+.0f  ",
            engine.telYaw, engine.telPitch, engine.telDx, engine.telDy
        ))
        sb.append("报告 ").append(hid.sentReports)
        if (lastStatus.isNotEmpty()) sb.append('\n').append(lastStatus)
        diag.text = sb.toString()

        pill.text = conn
        pill.setTextColor(if (hid.isConnected) C_GREEN else C_SEC)
        pill.background = bg(if (hid.isConnected) 0x1A34C759 else 0x1A8E8E93, 20)
    }

    // -------------------------------------------------------------- 蓝牙流程

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            hid.start()
            return
        }
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) hid.start() else requestPermissions(needed.toTypedArray(), REQ_PERMS)
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
            toast("缺少蓝牙权限，请到 设置 → 应用 → AirMouse → 权限 全部允许")
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
            toast("没有已配对设备，请先在平板上搜索并配对 AirMouse")
            return
        }
        val names = Array<CharSequence>(list.size) { i ->
            val d = list[i]
            try {
                "${d.name ?: "未知"}    ${d.address}"
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
        runOnUiThread { lastStatus = text }
    }

    // ------------------------------------------------------------- 摇杆反馈盘

    private class DiscView(context: android.content.Context) : View(context) {

        private var ox = 0f
        private var oy = 0f
        private var maxOff = 100f

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x14000000
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = 0x0A000000
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1A007AFF }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF007AFF.toInt() }

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
            maxOff = minOf(w, h) * 0.33f
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
            canvas.drawCircle(px, py, 30f, glowPaint)
            canvas.drawCircle(px, py, 15f, dotPaint)

            ox *= 0.94f
            oy *= 0.94f
            if (abs(ox) > 0.4f || abs(oy) > 0.4f) postInvalidateOnAnimation()
        }
    }
}
