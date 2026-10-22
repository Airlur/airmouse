package com.airlur.airmouse

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : Activity(), HidMouse.Listener {

    companion object {
        private const val REQ_PERMS = 100
        private const val REQ_DISCOVERABLE = 200
        private const val MODE_AIR = 0
        private const val MODE_PAD = 1

        private const val SHEET_NONE = 0
        private const val SHEET_HELP = 1
        private const val SHEET_CONNECT = 2
        private const val SHEET_SETTINGS = 3
    }

    private lateinit var settings: Settings
    private lateinit var pal: Palette
    private lateinit var engine: AirMouseEngine
    private lateinit var hid: HidMouse
    private lateinit var touchpad: TouchpadView

    private lateinit var rootView: FrameLayout
    private lateinit var sheetHost: SheetHost
    private var toastView: TextView? = null
    private var debugOverlay: View? = null
    private var debugText: TextView? = null


    private lateinit var pill: TextView
    private lateinit var segAir: TextView
    private lateinit var segPad: TextView
    private lateinit var paneAir: View
    private lateinit var panePad: View
    private lateinit var disc: DiscView
    private lateinit var diag: TextView
    private lateinit var padHint: TextView
    private lateinit var sensLabel: TextView
    private lateinit var padSensLabel: TextView

    // 连接面板的实时引用（面板不重建也能刷新）
    private var connStateText: TextView? = null
    private var connDiscoverBtn: TextView? = null
    private var connDisconnectBtn: TextView? = null
    private var connDeviceStatus: MutableList<DeviceRow> = mutableListOf()

    /** 一行设备：地址用于精确匹配，圆点和文字用于显示状态 */
    private class DeviceRow(
        val address: String,
        val dot: View,
        val label: TextView,
        val name: String
    )

    private var mode = MODE_PAD
    private var leftDown = false
    private var rightDown = false
    private var lastStatus = ""
    private var openSheet = SHEET_NONE

    private var discoverUntil = 0L

    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 200L)
        }
    }

    private val discoverTick = object : Runnable {
        override fun run() {
            refreshConnectSheet()
            if (discoverUntil > SystemClock.elapsedRealtime()) {
                ui.postDelayed(this, 1000L)
            }
        }
    }

    // ================================================================ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        pal = Palette.of(resolveDark())
        engine = AirMouseEngine(this)
        hid = HidMouse(this, this)

        rebuildUi(SHEET_NONE)
        applySettings()

        engine.onDelta = { dx, dy ->
            hid.moveBy(dx, dy)
            disc.push(dx, dy)
        }

        requestBluetoothPermissions()
    }


    private fun resolveDark(): Boolean {
        return when (settings.theme) {
            Settings.THEME_DARK -> true
            Settings.THEME_LIGHT -> false
            else -> {
                val m = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                m == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        engine.start()
        hid.resumeAutoReconnect()
        ui.removeCallbacks(tick)
        ui.post(tick)
        setMode(mode)
    }

    override fun onPause() {
        super.onPause()
        engine.stop()
        ui.removeCallbacks(tick)
        releaseAllButtons()
    }


    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(tick)
        ui.removeCallbacks(discoverTick)
        hid.stop()
    }

    private fun applySettings() {
        engine.bindFrom(settings)
        if (::touchpad.isInitialized) touchpad.bindFrom(settings)
        if (settings.keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun releaseAllButtons() {
        if (leftDown) {
            hid.setButton(HidMouse.BTN_LEFT, false)
            leftDown = false
        }
        if (rightDown) {
            hid.setButton(HidMouse.BTN_RIGHT, false)
            rightDown = false
        }
    }

    // ================================================================ 基础工具

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun txt(s: CharSequence, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        return t
    }

    private fun fullWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    // ================================================================ 界面构建

    /**
     * 重建整个界面。
     *
     * 刻意不用 recreate()：那会销毁 Activity，进而 unregisterApp() 掉 HID 注册，
     * 蓝牙鼠标直接断线。所以只重建 View 树，hid / engine 保持存活。
     */
    private fun rebuildUi(reopenSheet: Int) {
        val root = FrameLayout(this)
        root.setBackgroundColor(pal.bg)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(10), dp(16), dp(10))

        content.addView(buildHeader())
        content.addView(buildSeg())

        val holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        paneAir = buildAirPane()
        panePad = buildPadPane()
        holder.addView(paneAir, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        holder.addView(panePad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        sheetHost = SheetHost(this)
        root.addView(
            sheetHost,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 顶部提示条：必须加在抽屉之后，否则会被抽屉盖住
        val tv = toastView ?: txt("", 13f, 0xFFFFFFFF.toInt(), false).also { toastView = it }
        tv.setBackgroundColor(0xE6000000.toInt())
        tv.setPadding(dp(16), dp(10), dp(16), dp(10))
        tv.gravity = Gravity.CENTER_HORIZONTAL
        tv.visibility = View.GONE
        val tvLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        tvLp.topMargin = dp(56)
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        root.addView(tv, tvLp)

        // 日志全屏页（初始隐藏，也在最上层）
        debugOverlay = buildDebugOverlay()
        root.addView(
            debugOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        rootView = root
        setContentView(root)
        setMode(mode)

        openSheet = SHEET_NONE
        when (reopenSheet) {
            SHEET_HELP -> showHelpSheet()
            SHEET_CONNECT -> showConnectSheet()
            SHEET_SETTINGS -> showSettingsSheet()
        }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        row.addView(
            txt("AirMouse", 22f, pal.label, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        pill = txt("未注册", 11f, pal.sec, true)
        pill.setPadding(dp(9), dp(5), dp(9), dp(5))
        pill.background = rounded(0x228E8E93, 20)
        pill.setOnClickListener { showConnectSheet() }
        row.addView(pill)
        row.addView(space(5))

        val gear = IconButton(this, IconButton.KIND_ICON, R.drawable.ic_gear)
        gear.circleColor = pal.fillBtn
        gear.fgColor = pal.sec
        gear.setOnClickListener { showSettingsSheet() }
        row.addView(gear)
        row.addView(space(5))

        val help = IconButton(this, IconButton.KIND_HELP)
        help.circleColor = pal.fillBtn
        help.fgColor = pal.sec
        help.setOnClickListener { showHelpSheet() }
        row.addView(help)
        row.addView(space(5))

        val bug = IconButton(this, IconButton.KIND_ICON, R.drawable.ic_bug)
        bug.circleColor = pal.fillBtn
        bug.fgColor = pal.sec
        bug.setOnClickListener { openDebug() }
        row.addView(bug)

        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(row, fullWrap())
        wrap.setPadding(0, dp(2), 0, dp(8))
        return wrap
    }

    private fun space(w: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(dp(w), 1)
        return v
    }

    private fun buildSeg(): View {
        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        seg.background = rounded(pal.fillSeg, 9)
        seg.setPadding(dp(2), dp(2), dp(2), dp(2))

        segPad = segLabel("触控板")
        segAir = segLabel("空鼠")
        seg.addView(segPad, LinearLayout.LayoutParams(0, dp(32), 1f))
        seg.addView(segAir, LinearLayout.LayoutParams(0, dp(32), 1f))

        segAir.setOnClickListener { setMode(MODE_AIR) }
        segPad.setOnClickListener { setMode(MODE_PAD) }

        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(seg, fullWrap())
        wrap.setPadding(0, 0, 0, dp(10))
        return wrap
    }

    private fun segLabel(s: String): TextView {
        val t = txt(s, 13f, pal.label, true)
        t.gravity = Gravity.CENTER
        t.isClickable = true
        return t
    }

    private fun setMode(m: Int) {
        mode = m
        val air = m == MODE_AIR
        engine.enabled = air
        if (::paneAir.isInitialized) {
            paneAir.visibility = if (air) View.VISIBLE else View.GONE
            panePad.visibility = if (air) View.GONE else View.VISIBLE
            segAir.background = if (air) rounded(pal.card, 7) else null
            segPad.background = if (air) null else rounded(pal.card, 7)
        }
    }

    // ---------------------------------------------------------------- 空鼠页

    private fun buildAirPane(): View {
        val pane = LinearLayout(this)
        pane.orientation = LinearLayout.VERTICAL

        disc = DiscView(this)
        disc.ringColor = pal.sep
        disc.accentColor = pal.accent
        pane.addView(disc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val hint = txt("转动手腕 · 实时反馈", 11f, pal.sub, false)
        hint.gravity = Gravity.CENTER
        val hintWrap = LinearLayout(this)
        hintWrap.addView(hint, fullWrap())
        hintWrap.setPadding(0, 0, 0, dp(4))
        pane.addView(hintWrap, fullWrap())

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(pal.card, 12)
        card.setPadding(dp(16), dp(4), dp(16), dp(6))

        val sensRow = LinearLayout(this)
        sensRow.orientation = LinearLayout.HORIZONTAL
        sensRow.gravity = Gravity.CENTER_VERTICAL
        sensRow.setPadding(0, dp(8), 0, 0)
        sensRow.addView(
            txt("灵敏度", 15.5f, pal.label, false),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        sensLabel = txt("", 15f, pal.sec, false)
        sensRow.addView(sensLabel)
        card.addView(sensRow)

        val seek = styledSeekBar(settings.airSens) { p ->
            settings.airSens = p
            engine.sensitivity = settings.airSensitivity()
            sensLabel.text = settings.airSensitivity().toInt().toString()
        }
        card.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        sensLabel.text = settings.airSensitivity().toInt().toString()

        pane.addView(card, fullWrap())
        pane.addView(spacer(dp(9)))
        pane.addView(buildInvertRow(), fullWrap())
        pane.addView(spacer(dp(9)))
        pane.addView(buildDeck(true), fullWrap())

        val legend = txt("中间滚轮：上下滑 = 滚动　·　轻点 = 中键", 10.5f, pal.sub, false)
        legend.gravity = Gravity.CENTER
        pane.addView(legend, fullWrap())

        diag = txt("", 10f, pal.sec, false)
        diag.typeface = Typeface.MONOSPACE
        diag.setPadding(0, dp(4), 0, 0)
        diag.visibility = if (settings.showDiag) View.VISIBLE else View.GONE
        pane.addView(diag, fullWrap())

        return pane
    }

    private fun buildInvertRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = rounded(pal.card, 12)

        val left = invertCell("水平反向", settings.invertX) { v ->
            settings.invertX = v
            engine.invertX = v
        }
        val right = invertCell("垂直反向", settings.invertY) { v ->
            settings.invertY = v
            engine.invertY = v
        }

        row.addView(left, LinearLayout.LayoutParams(0, dp(48), 1f))

        val div = View(this)
        div.setBackgroundColor(pal.sep)
        val divLp = LinearLayout.LayoutParams(dp(1), dp(30))
        divLp.gravity = Gravity.CENTER_VERTICAL
        row.addView(div, divLp)

        row.addView(right, LinearLayout.LayoutParams(0, dp(48), 1f))
        return row
    }

    private fun invertCell(
        label: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.HORIZONTAL
        cell.gravity = Gravity.CENTER_VERTICAL
        cell.setPadding(dp(14), 0, dp(14), 0)
        cell.isClickable = true

        cell.addView(
            txt(label, 14f, pal.label, false),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val sw = ToggleSwitch(this)
        sw.big = false
        sw.trackOff = pal.track
        sw.trackOn = pal.green
        sw.knobColor = 0xFFFFFFFF.toInt()
        sw.setChecked(initial, false)
        cell.addView(sw)

        var state = initial
        cell.setOnClickListener {
            state = !state
            sw.setChecked(state, true)
            onChange(state)
        }
        return cell
    }

    // -------------------------------------------------------------- 触控板页

    private fun buildPadPane(): View {
        val pane = LinearLayout(this)
        pane.orientation = LinearLayout.VERTICAL

        val frame = FrameLayout(this)
        frame.background = rounded(pal.card, 16)

        touchpad = TouchpadView(this)
        touchpad.accentColor = pal.accent
        touchpad.bindFrom(settings)
        touchpad.onMove = { dx, dy -> hid.moveBy(dx, dy) }
        touchpad.onLeftClick = { hid.click(HidMouse.BTN_LEFT, 30L) }
        touchpad.onLeftDown = {
            hid.setButton(HidMouse.BTN_LEFT, true)
            leftDown = true
        }
        touchpad.onLeftUp = {
            hid.setButton(HidMouse.BTN_LEFT, false)
            leftDown = false
        }
        touchpad.onRightClick = { hid.click(HidMouse.BTN_RIGHT, 30L) }
        touchpad.onWheel = { steps -> hid.wheel(steps) }
        touchpad.onDragStateChanged = { dragging ->
            padHint.text = if (dragging) "正在拖拽（双击并按住）" else "单指滑动 = 移动指针"
            padHint.setTextColor(if (dragging) pal.accent else pal.sub)
        }
        frame.addView(
            touchpad,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        frame.addView(padHintView("双指滑动 = 滚轮", Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        padHint = padHintView("单指滑动 = 移动指针", Gravity.CENTER)
        frame.addView(padHint)
        frame.addView(padHintView("轻点 = 左键 · 连点两下按住 = 拖拽", Gravity.BOTTOM or Gravity.START))
        frame.addView(padHintView("长按 = 右键", Gravity.BOTTOM or Gravity.END))

        pane.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pane.addView(spacer(dp(10)))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(pal.card, 12)
        card.setPadding(dp(16), dp(4), dp(16), dp(6))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, 0)
        row.addView(
            txt("指针速度", 15.5f, pal.label, false),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        padSensLabel = txt("", 15f, pal.sec, false)
        row.addView(padSensLabel)
        card.addView(row)

        val seek = styledSeekBar(settings.padSens) { p ->
            settings.padSens = p
            touchpad.gain = settings.padGain()
            padSensLabel.text = String.format(java.util.Locale.US, "%.1f×", settings.padGain())
        }
        card.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        padSensLabel.text = String.format(java.util.Locale.US, "%.1f×", settings.padGain())

        pane.addView(card, fullWrap())
        pane.addView(spacer(dp(10)))
        pane.addView(buildDeck(false), fullWrap())

        val legend = txt("左键锁定后，单指滑动面板即可框选", 10.5f, pal.sub, false)
        legend.gravity = Gravity.CENTER
        pane.addView(legend, fullWrap())

        return pane
    }

    private fun padHintView(text: String, gravity: Int): TextView {
        val t = txt(text, 11f, pal.sub, false)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            gravity
        )
        when (gravity) {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL -> lp.topMargin = dp(12)
            Gravity.BOTTOM or Gravity.START -> {
                lp.leftMargin = dp(14)
                lp.bottomMargin = dp(12)
            }

            Gravity.BOTTOM or Gravity.END -> {
                lp.rightMargin = dp(14)
                lp.bottomMargin = dp(12)
            }
        }
        return t.apply { layoutParams = lp }
    }

    // ------------------------------------------------------------------ 按键区

    private fun buildDeck(withWheel: Boolean): View {
        val deck = LinearLayout(this)
        deck.orientation = LinearLayout.HORIZONTAL
        deck.background = rounded(pal.deck, 16)
        deck.setPadding(dp(9), dp(9), dp(9), dp(9))

        val left = keyView("左键")
        bindKey(left, HidMouse.BTN_LEFT, true)
        deck.addView(left, LinearLayout.LayoutParams(0, dp(100), 3f))

        if (withWheel) {
            val wheel = buildWheelKey()
            val lp = LinearLayout.LayoutParams(dp(92), dp(100))
            lp.leftMargin = dp(9)
            lp.rightMargin = dp(9)
            deck.addView(wheel, lp)
        } else {
            val gap = View(this)
            deck.addView(gap, LinearLayout.LayoutParams(dp(9), 1))
        }

        val right = keyView("右键")
        bindKey(right, HidMouse.BTN_RIGHT, false)
        deck.addView(right, LinearLayout.LayoutParams(0, dp(100), 3f))

        return deck
    }

    private fun keyView(label: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.background = rounded(pal.keyBg, 12)
        box.isClickable = true

        val main = txt(label, 18f, pal.label, true)
        main.gravity = Gravity.CENTER
        box.addView(main, fullWrap())

        val sub = txt("轻点＝单击\n按住＝锁定", 9.5f, pal.sub, false)
        sub.gravity = Gravity.CENTER
        box.addView(sub, fullWrap())
        return box
    }

    private fun bindKey(box: LinearLayout, mask: Int, isLeft: Boolean) {
        var latched = false
        var timer: Runnable? = null
        val main = box.getChildAt(0) as TextView
        val sub = box.getChildAt(1) as TextView

        fun paint(on: Boolean) {
            box.background = rounded(if (on) pal.accent else pal.keyBg, 12)
            main.setTextColor(if (on) 0xFFFFFFFF.toInt() else pal.label)
            sub.setTextColor(if (on) 0xCCFFFFFF.toInt() else pal.sub)
        }

        box.setOnTouchListener { _, event ->
            val label = if (isLeft) "左键" else "右键"
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    engine.suppress(120)
                    if (latched) {
                        latched = false
                        paint(false)
                        hid.setButton(mask, false)
                        if (isLeft) leftDown = false else rightDown = false
                        toastTop("$label 已释放")
                        return@setOnTouchListener true
                    }
                    hid.setButton(mask, true)
                    if (isLeft) leftDown = true else rightDown = true
                    paint(true)

                    if (settings.latchEnabled) {
                        val r = Runnable {
                            latched = true
                            engine.suppress(200)
                            toastTop("$label 已锁定 · 滑动即可拖拽")
                        }
                        timer = r
                        box.postDelayed(r, settings.latchMs.toLong())
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    timer?.let { box.removeCallbacks(it) }
                    timer = null
                    if (!latched) {
                        hid.setButton(mask, false)
                        if (isLeft) leftDown = false else rightDown = false
                        paint(false)
                    }
                    engine.suppress(160)
                    true
                }

                else -> false
            }
        }
    }

    private fun buildWheelKey(): View {
        val box = FrameLayout(this)
        box.background = rounded(pal.keyBg, 12)

        val knob = View(this)
        knob.background = rounded(pal.track, 40)
        box.addView(knob, FrameLayout.LayoutParams(dp(30), dp(34), Gravity.CENTER))

        var lastY = 0f
        var acc = 0f
        var moved = false

        box.setOnTouchListener { v, event ->
            val step = (settings.wheelStepPx * resources.displayMetrics.density).coerceAtLeast(8f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = event.y
                    acc = 0f
                    moved = false
                    v.background = rounded(pal.accent, 12)
                    knob.background = rounded(0xCCFFFFFF.toInt(), 40)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lastY
                    lastY = event.y
                    if (abs(dy) > 1f) moved = true
                    acc += dy
                    val notches = (acc / step).toInt()
                    if (notches != 0) {
                        acc -= notches * step
                        hid.wheel(notches)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.background = rounded(pal.keyBg, 12)
                    knob.background = rounded(pal.track, 40)
                    if (!moved && settings.midClick) {
                        hid.click(HidMouse.BTN_MIDDLE, 30L)
                        toastTop("中键")
                    }
                    true
                }

                else -> false
            }
        }
        return box
    }

    // ------------------------------------------------------------------ 控件库

    private fun styledSeekBar(progress0: Int, onChange: (Int) -> Unit): SeekBar {
        val sb = SeekBar(this)
        sb.max = 100
        sb.progress = progress0
        sb.progressTintList = ColorStateList.valueOf(pal.accent)
        sb.thumbTintList = ColorStateList.valueOf(pal.accent)
        sb.progressBackgroundTintList = ColorStateList.valueOf(pal.track)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) = onChange(p)
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        return sb
    }

    private fun sheetSeek(
        label: String,
        min: Int,
        max: Int,
        value: Int,
        fmt: (Int) -> String,
        onChange: (Int) -> Unit
    ): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(6), dp(14), dp(8))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(
            txt(label, 13f, pal.label, false),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val valueText = txt(fmt(value), 13f, pal.sec, false)
        row.addView(valueText)
        box.addView(row, fullWrap())

        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = (value - min).coerceAtLeast(0)
        sb.progressTintList = ColorStateList.valueOf(pal.accent)
        sb.thumbTintList = ColorStateList.valueOf(pal.accent)
        sb.progressBackgroundTintList = ColorStateList.valueOf(pal.track)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                val real = min + p
                valueText.text = fmt(real)
                onChange(real)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        return box
    }

    private fun spacer(h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(1, h)
        return v
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(pal.sep)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        return v
    }

    private fun group(title: String?, vararg children: View): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL

        if (title != null) {
            val t = txt(title, 12f, pal.sec, false)
            t.setPadding(dp(4), dp(6), 0, dp(5))
            wrap.addView(t)
        }
        val g = LinearLayout(this)
        g.orientation = LinearLayout.VERTICAL
        g.background = rounded(pal.grp, 11)
        for (i in children.indices) {
            g.addView(children[i], fullWrap())
            if (i != children.lastIndex) {
                val d = View(this)
                d.setBackgroundColor(pal.sep)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                lp.leftMargin = dp(14)
                g.addView(d, lp)
            }
        }
        wrap.addView(g, fullWrap())
        return wrap
    }

    private fun sheetItem(label: String, sub: String?, right: View?): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(10), dp(14), dp(10))
        row.minimumHeight = dp(46)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(txt(label, 14.5f, pal.label, false))
        if (sub != null) {
            val s = txt(sub, 11f, pal.sec, false)
            s.setPadding(0, dp(2), 0, 0)
            col.addView(s)
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) row.addView(right)
        return row
    }

    private fun sheetSwitch(initial: Boolean, onChange: (Boolean) -> Unit): ToggleSwitch {
        val sw = ToggleSwitch(this)
        sw.trackOff = pal.track
        sw.trackOn = pal.green
        sw.knobColor = 0xFFFFFFFF.toInt()
        sw.setChecked(initial, false)
        sw.setOnClickListener {
            val next = !sw.isChecked
            sw.setChecked(next, true)
            onChange(next)
        }
        return sw
    }

    private fun bigButton(text: String, primary: Boolean): TextView {
        val t = txt(text, 15f, if (primary) 0xFFFFFFFF.toInt() else pal.accent, true)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(12), 0, dp(12))
        t.background = rounded(if (primary) pal.accent else pal.grp, 12)
        t.isClickable = true
        t.layoutParams = fullWrap().apply { topMargin = dp(9) }
        return t
    }

    private fun sheetTitle(t: String): View {
        val v = txt(t, 19f, pal.label, true)
        v.setPadding(0, dp(4), 0, dp(2))
        return v
    }

    private fun sheetSub(t: String): View {
        val v = txt(t, 12f, pal.sec, false)
        v.setPadding(0, 0, 0, dp(12))
        return v
    }

    private fun sheetRoot(): LinearLayout {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.setPadding(dp(16), dp(2), dp(16), dp(18))
        return v
    }

    // ================================================================ 顶部提示

    /**
     * 顶部提示条。
     *
     * 不用系统 Toast：Android 11 起自定义 gravity 会被忽略，永远弹在底部。
     * 这里是带受控动画的顶部浮层，风格与界面一致。
     */
    private fun toastTop(msg: String) {
        val tv = toastView ?: return
        tv.text = msg
        tv.background = rounded(0xE6000000.toInt(), 20)
        // 抽屉、日志页都可能盖在提示之上，每次弹之前先提到最前
        try {
            if (::rootView.isInitialized) rootView.bringChildToFront(tv)
        } catch (_: Throwable) {
        }
        tv.visibility = View.VISIBLE
        tv.alpha = 0f
        tv.translationY = -dp(12).toFloat()
        tv.animate().alpha(1f).translationY(0f).setDuration(160L).start()
        ui.removeCallbacks(hideToast)
        ui.postDelayed(hideToast, 2200L)
    }

    private val hideToast = Runnable {
        val tv = toastView ?: return@Runnable
        tv.animate().alpha(0f).translationY(-dp(12).toFloat()).setDuration(180L)
            .withEndAction { if (toastView != null) toastView!!.visibility = View.GONE }
            .start()
    }

    // ================================================================ 日志页

    /** 全屏日志页：等宽字体、可滚动、一键复制 */
    private fun buildDebugOverlay(): View {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(pal.bg)
        panel.setPadding(dp(16), dp(14), dp(16), dp(14))
        panel.visibility = View.GONE

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(
            txt("运行日志", 20f, pal.label, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(
            txt("关闭", 14f, pal.accent, true).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                setOnClickListener { closeDebug() }
            }
        )
        panel.addView(head, fullWrap())

        val hint = txt("出错时点「复制」把内容发给我", 11f, pal.sec, false)
        hint.setPadding(0, dp(4), 0, dp(8))
        panel.addView(hint, fullWrap())

        val logView = TextView(this)
        logView.typeface = Typeface.MONOSPACE
        logView.textSize = 10f
        logView.setTextColor(pal.label)
        logView.setBackgroundColor(pal.card)
        logView.setPadding(dp(10), dp(10), dp(10), dp(10))
        logView.setTextIsSelectable(true)
        logView.setLineSpacing(0f, 1.25f)
        debugText = logView

        val scroll = android.widget.ScrollView(this)
        scroll.addView(
            logView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL

        val copy = bigButton("复制全部", true)
        copy.setOnClickListener { copyLog() }
        btnRow.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val clear = bigButton("清空", false)
        clear.setOnClickListener {
            AppLog.clear()
            refreshDebug()
            toastTop("日志已清空")
        }
        val clearLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        clearLp.leftMargin = dp(10)
        btnRow.addView(clear, clearLp)

        panel.addView(btnRow, fullWrap())
        return panel
    }

    private fun openDebug() {
        if (sheetHost.isShowing()) sheetHost.dismiss()
        refreshDebug()
        debugOverlay?.visibility = View.VISIBLE
        debugOverlay?.bringToFront()
    }

    private fun closeDebug() {
        debugOverlay?.visibility = View.GONE
    }

    private fun refreshDebug() {
        debugText?.text = AppLog.dump()
    }

    private fun copyLog() {
        val content = AppLog.dump()
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AirMouse 日志", content))
            toastTop("日志已复制到剪贴板")
        } catch (t: Throwable) {
            toastTop("复制失败：" + t.message)
        }
    }

    // ================================================================ 抽屉

    private fun showSheet(content: View, which: Int) {
        openSheet = which
        sheetHost.show(content, pal)
    }

    private fun showHelpSheet() {
        val root = sheetRoot()
        root.addView(sheetTitle("使用说明"))
        root.addView(sheetSub("可上下滑动 · 拖顶部横条或点空白处关闭"))

        root.addView(sheetPara("两种模式", "空鼠：握着手机转手腕控制指针，适合靠坐、远距离。\n触控板：手机放桌上，手指在白色区域滑动，像笔记本触摸板。"))
        root.addView(sheetPara("顶部状态胶囊", "灰＝未注册，橙＝待连接，绿＝已连接。点它打开连接面板。"))
        root.addView(sheetPara("配对 ≠ 连接", "「已配对」只表示双方认识、记住了地址；「已连接」才是鼠标真正能用。\n在连接面板里点设备才会真正建立连接。"))
        root.addView(sheetPara("平板里该找哪个名字", "找你的手机蓝牙名称（本机名称见连接面板顶部），不是「AirMouse」。\nAirMouse 只是 HID 服务名，普通蓝牙列表里不显示。"))
        root.addView(sheetPara("框选 / 拖拽（两种都支持）", "双击拖拽：轻点一下 → 在设定时间内再按住并滑动 → 松手结束。适合手机放桌上。\n按钮锁定：按住左键到设定时长变蓝锁定 → 滑动手腕或面板 → 再点一下释放。适合单手握机。\n两者可在设置里分别开关。"))
        root.addView(sheetPara("触控板手势", "单指滑＝移动指针；轻点＝左键；双击按住＝拖拽；长按＝右键；双指滑＝滚轮。"))
        root.addView(sheetPara("灵敏度 / 指针速度", "空鼠的「灵敏度」是转手腕的放大倍数；触控板的「指针速度」是手指滑动的放大倍数。"))
        root.addView(sheetPara("滚轮区", "上下滑动＝页面滚动；轻点＝中键单击。"))
        root.addView(sheetPara("断线重连", "连接意外断开后会自动尝试重连（间隔逐次拉长）。\n手动点「断开当前连接」会暂停自动重连，避免你刚断开又被连回去。"))

        val close = bigButton("知道了", false)
        close.setOnClickListener { sheetHost.dismiss() }
        root.addView(close)

        showSheet(root, SHEET_HELP)
    }

    private fun sheetPara(title: String, body: String): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(6), 0, dp(6))
        val t = txt(title, 13.5f, pal.label, true)
        box.addView(t)
        val b = txt(body, 12.5f, pal.sec, false)
        b.setPadding(0, dp(3), 0, 0)
        b.setLineSpacing(0f, 1.25f)
        box.addView(b)
        return box
    }

    // ------------------------------------------------------------ 连接面板

    private fun showConnectSheet() {
        connDeviceStatus = mutableListOf()
        connStateText = null
        connDiscoverBtn = null
        connDisconnectBtn = null

        val root = sheetRoot()
        root.addView(sheetTitle("连接平板"))

        val state = txt("", 12f, pal.sec, false)
        state.setLineSpacing(0f, 1.3f)
        state.setPadding(0, 0, 0, dp(10))
        connStateText = state
        root.addView(state)

        val discover = bigButton("", true)
        connDiscoverBtn = discover
        discover.setOnClickListener { onDiscoverButton() }
        root.addView(discover, fullWrap())

        // 按钮和下面列表之间要留呼吸空间，否则贴在一起很难看
        val gap = View(this)
        gap.layoutParams = LinearLayout.LayoutParams(1, dp(12))
        root.addView(gap)

        val devices = hid.bondedDevices()
        val rows = ArrayList<View>()
        if (devices.isEmpty()) {
            rows.add(sheetItem("没有已配对设备", "先在平板上搜索并配对手机", null))
        } else {
            for (d in devices) {
                val name = try {
                    d.name ?: d.address
                } catch (e: SecurityException) {
                    d.address
                }
                val chipRow = LinearLayout(this)
                chipRow.orientation = LinearLayout.HORIZONTAL
                chipRow.gravity = Gravity.CENTER_VERTICAL
                chipRow.setPadding(dp(10), dp(6), dp(10), dp(6))
                chipRow.background = rounded(pal.grp, 10)

                val dot = View(this)
                val dotLp = LinearLayout.LayoutParams(dp(8), dp(8))
                dotLp.rightMargin = dp(6)
                dot.background = rounded(pal.sec, 4)
                chipRow.addView(dot, dotLp)

                val statusText = txt("连接", 13f, pal.accent, true)
                chipRow.addView(statusText)

                val item = sheetItem(name, d.address, chipRow)
                item.isClickable = true
                item.setOnClickListener {
                    if (hid.isConnected && hid.connectedName == name) {
                        toastTop("「$name」已连接")
                        return@setOnClickListener
                    }
                    hid.connectDevice(d)
                    toastTop("正在连接「$name」…")
                    // 立刻刷一次，不依赖回调时序
                    refreshConnectSheet()
                }
                connDeviceStatus.add(DeviceRow(d.address, dot, statusText, name))
                rows.add(item)
            }
        }
        root.addView(group(null, *rows.toTypedArray()))

        val disconnect = bigButton("断开当前连接", false)
        connDisconnectBtn = disconnect
        disconnect.setOnClickListener { onDisconnectButton() }
        root.addView(disconnect)

        val close = txt("关闭", 15f, pal.accent, true)
        close.gravity = Gravity.CENTER
        close.setPadding(0, dp(11), 0, dp(4))
        close.setOnClickListener { sheetHost.dismiss() }
        root.addView(close)

        showSheet(root, SHEET_CONNECT)
        refreshConnectSheet()
    }

    private fun onDisconnectButton() {
        if (!hid.isConnected) {
            toastTop("当前没有已连接的设备")
            return
        }
        hid.disconnectCurrent()
        toastTop("正在断开「" + hid.connectedName + "」…")
    }

    private fun onDiscoverButton() {
        if (discoverUntil > SystemClock.elapsedRealtime()) {
            // 正在可被发现中 -> 尝试关闭
            val stopped = tryStopDiscoverable()
            discoverUntil = 0
            ui.removeCallbacks(discoverTick)
            refreshConnectSheet()
            toastTop(
                if (stopped) "已关闭可被发现"
                else "系统不允许应用主动关闭，会在倒计时结束后自动关闭"
            )
            return
        }
        makeDiscoverable()
    }

    private fun refreshConnectSheet() {
        val leftMs = discoverUntil - SystemClock.elapsedRealtime()
        val discovering = leftMs > 0

        connDiscoverBtn?.let { btn ->
            if (discovering) {
                val sec = (leftMs / 1000).toInt()
                btn.text = "可被发现中 · ${sec / 60}:${String.format(java.util.Locale.US, "%02d", sec % 60)} · 点击关闭"
                btn.background = rounded(pal.orange, 12)
            } else {
                btn.text = "让平板发现我（5 分钟）"
                btn.background = rounded(pal.accent, 12)
            }
        }

        // 状态行只留必要信息
        connStateText?.let { t ->
            val primary = when {
                hid.isConnected -> "已连接：" + hid.connectedName
                hid.phase == HidMouse.PHASE_CONNECTING -> "正在连接…"
                hid.phase == HidMouse.PHASE_FAILED -> "连接失败，请查看运行日志"
                hid.isRegistered -> "未连接。点下面的设备发起连接"
                else -> "HID 未注册，请关闭蓝牙再打开后重试"
            }
            // lastStatus 常常和上面这行内容一样（比如都是「已连接：xxx」），
            // 重复显示会出现两行一模一样的文字，所以相同就丢掉。
            val extra = lastStatus.trim()
            val showExtra = extra.isNotEmpty() && !primary.contains(extra) && !extra.contains(primary)
            t.text = if (showExtra) primary + "\n" + extra else primary
            t.setTextColor(
                when {
                    hid.isConnected -> pal.green
                    hid.phase == HidMouse.PHASE_FAILED -> pal.red
                    else -> pal.sec
                }
            )
        }

        // 断开按钮带设备名
        connDisconnectBtn?.let { btn ->
            if (hid.isConnected) {
                btn.text = "断开「" + hid.connectedName + "」"
                btn.setTextColor(pal.red)
            } else {
                btn.text = "当前未连接"
                btn.setTextColor(pal.sec)
            }
        }

        // 每个设备行按连接阶段显示：可连接 / 连接中 / 已连接 / 失败
        for (row in connDeviceStatus) {
            val isThis = hid.phaseAddress == row.address
            when {
                hid.isConnected && hid.phaseAddress == row.address -> {
                    row.label.text = "已连接"
                    row.label.setTextColor(pal.green)
                    row.dot.background = rounded(pal.green, 4)
                }

                isThis && hid.phase == HidMouse.PHASE_CONNECTING -> {
                    row.label.text = "连接中…"
                    row.label.setTextColor(pal.orange)
                    row.dot.background = rounded(pal.orange, 4)
                }

                isThis && hid.phase == HidMouse.PHASE_FAILED -> {
                    row.label.text = "失败"
                    row.label.setTextColor(pal.red)
                    row.dot.background = rounded(pal.red, 4)
                }

                else -> {
                    row.label.text = "连接"
                    row.label.setTextColor(pal.accent)
                    row.dot.background = rounded(pal.sec, 4)
                }
            }
        }
    }

    // -------------------------------------------------------------- 设置面板

    private fun showSettingsSheet() {
        val root = sheetRoot()
        root.addView(sheetTitle("设置"))
        root.addView(sheetSub("改动立即生效"))

        val themeRow = LinearLayout(this)
        themeRow.orientation = LinearLayout.HORIZONTAL
        themeRow.gravity = Gravity.CENTER_VERTICAL
        themeRow.setPadding(0, dp(4), 0, dp(6))
        themeRow.addView(
            txt("外观", 12f, pal.sec, false),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        themeRow.addView(themeSelector())
        root.addView(themeRow, fullWrap())

        // 深色模式 + 桌面图标：同一张卡片，中间自动分割线
        root.addView(
            group(
                null,
                sheetItem("深色模式", "切换后全部界面同步", sheetSwitch(resolveDark()) { v ->
                    applyTheme(if (v) Settings.THEME_DARK else Settings.THEME_LIGHT)
                }),
                iconSelector()
            )
        )

        root.addView(
            group(
                "手势",
                sheetItem("双击拖拽", "轻点两下按住＝拖拽；点一下再滑不会误触", sheetSwitch(settings.doubleTapDrag) { v ->
                    settings.doubleTapDrag = v
                    touchpad.doubleTapDragEnabled = v
                }),
                sheetItem("按钮锁定", "按住左键变蓝，持续按下", sheetSwitch(settings.latchEnabled) { v ->
                    settings.latchEnabled = v
                }),
                sheetItem("断线自动重连", "连接意外断开时自动重试", sheetSwitch(settings.autoReconnect) { v ->
                    settings.autoReconnect = v
                    hid.autoReconnect = v
                })
            )
        )

        val sliders = LinearLayout(this)
        sliders.orientation = LinearLayout.VERTICAL
        sliders.background = rounded(pal.grp, 11)
        sliders.addView(sheetSeek("双击判定窗口", 120, 500, settings.dblWindowMs, { "$it ms" }) { v ->
            settings.dblWindowMs = v
            touchpad.dblWindowMs = v
        })
        sliders.addView(divider(), fullWrap())
        sliders.addView(sheetSeek("长按右键时长", 300, 900, settings.longPressMs, { "$it ms" }) { v ->
            settings.longPressMs = v
            touchpad.longPressMs = v
        })
        sliders.addView(divider(), fullWrap())
        sliders.addView(sheetSeek("左键锁定按住时长", 200, 800, settings.latchMs, { "$it ms" }) { v ->
            settings.latchMs = v
        })
        sliders.addView(divider(), fullWrap())
        sliders.addView(sheetSeek("轻点判定上限", 150, 500, settings.tapMaxMs, { "$it ms" }) { v ->
            settings.tapMaxMs = v
            touchpad.tapMaxMs = v
        })
        sliders.addView(divider(), fullWrap())
        sliders.addView(sheetSeek("滚轮一格距离", 12, 60, settings.wheelStepPx, { "$it px" }) { v ->
            settings.wheelStepPx = v
            touchpad.bindFrom(settings)
        })
        root.addView(group("时间参数", sliders))

        root.addView(
            group(
                "其他",
                sheetItem("震动反馈", "长按、锁定、右键时轻震", sheetSwitch(settings.haptics) { v ->
                    settings.haptics = v
                    touchpad.hapticsEnabled = v
                }),
                sheetItem("保持屏幕常亮", "使用期间不自动熄屏", sheetSwitch(settings.keepAwake) { v ->
                    settings.keepAwake = v
                    applySettings()
                }),
                sheetItem("滚轮区轻点＝中键", null, sheetSwitch(settings.midClick) { v ->
                    settings.midClick = v
                }),
                sheetItem("滚轮方向反转", "默认手指上滑＝内容向上滚", sheetSwitch(settings.wheelInvert) { v ->
                    settings.wheelInvert = v
                    touchpad.wheelInvert = v
                }),
                sheetItem("诊断信息", "显示角速度、发送计数", sheetSwitch(settings.showDiag) { v ->
                    settings.showDiag = v
                    diag.visibility = if (v) View.VISIBLE else View.GONE
                })
            )
        )

        val reset = bigButton("恢复默认设置", false)
        reset.setOnClickListener {
            settings.resetAll()
            toastTop("已恢复默认设置")
            applyTheme(settings.theme)
            hid.autoReconnect = settings.autoReconnect
        }
        root.addView(reset)

        val done = txt("完成", 15f, pal.accent, true)
        done.gravity = Gravity.CENTER
        done.setPadding(0, dp(11), 0, dp(4))
        done.setOnClickListener { sheetHost.dismiss() }
        root.addView(done)

        showSheet(root, SHEET_SETTINGS)
    }

    /**
     * 切换主题。
     *
     * 不调用 recreate()：那会销毁 Activity → onDestroy → unregisterApp()，
     * 蓝牙鼠标会被断开。这里只换配色并重建 View 树，hid 保持不变，
     * 并且把当前打开的抽屉重新打开，所以设置界面不会关掉。
     */
    private fun applyTheme(theme: Int) {
        settings.theme = theme
        val wasSheet = openSheet
        pal = Palette.of(resolveDark())
        rebuildUi(wasSheet)
        applySettings()
        refreshStatus()
    }

    /**
     * 桌面图标选择器。
     *
     * 切换方式：启用目标 alias + 立即禁用其余 + 主动重启。
     * 重启是必须的 —— 禁用当前入口后 Activity 已经在销毁，
     * 这时任何 UI 操作都会崩溃。所以点击后除了重启，什么都不做。
     */
    private fun iconSelector(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL

        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.setPadding(dp(14), dp(11), dp(14), dp(4))
        titleRow.addView(txt("桌面图标", 14.5f, pal.label, false), fullWrap())
        wrap.addView(titleRow, fullWrap())

        val current = settings.appIcon
        val size = dp(44)

        wrap.addView(iconRow(intArrayOf(
            IconManager.ICON_K, IconManager.ICON_G, IconManager.ICON_I
        ), current, size), fullWrap())
        wrap.addView(divider(), fullWrap())
        wrap.addView(iconRow(intArrayOf(
            IconManager.ICON_K2, IconManager.ICON_G2, IconManager.ICON_I2
        ), current, size), fullWrap())

        return wrap
    }

    private fun iconRow(ids: IntArray, current: Int, size: Int): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(6), dp(10), dp(6), dp(10))

        for (i in ids) {
            val selected = (i == current)

            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER
            cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            val img = ImageView(this)
            img.setImageResource(iconFgRes(i))
            img.background = iconThumbBg(i, selected)
            img.layoutParams = LinearLayout.LayoutParams(size, size)
            cell.addView(img)

            val label = txt(IconManager.shortName(i), 10.5f,
                if (selected) pal.accent else pal.sec, selected)
            label.gravity = Gravity.CENTER
            label.setPadding(0, dp(6), 0, 0)
            cell.addView(label)

            cell.setOnClickListener {
                if (settings.appIcon == i) return@setOnClickListener
                switchIcon(i)
            }
            row.addView(cell)
        }
        return row
    }

    /** 缩略图底：用图标自身底色 + 描边，白底图标才不会糊在卡片上 */
    private fun iconThumbBg(index: Int, selected: Boolean): GradientDrawable {
        val fill = when (index) {
            IconManager.ICON_I, IconManager.ICON_I2 -> 0xFF007AFF.toInt()
            IconManager.ICON_K, IconManager.ICON_K2 -> 0xFF000000.toInt()
            else -> 0xFFF2F2F7.toInt()
        }
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(11).toFloat()
        d.setStroke(dp(if (selected) 2 else 1), if (selected) pal.accent else pal.sep)
        return d
    }

    /** 只取前景线条，底色由 iconThumbBg 负责 */
    private fun iconFgRes(index: Int): Int = when (index) {
        IconManager.ICON_I -> R.drawable.ic_i_fg
        IconManager.ICON_K -> R.drawable.ic_k_fg
        IconManager.ICON_G2 -> R.drawable.ic_g2_fg
        IconManager.ICON_I2 -> R.drawable.ic_i2_fg
        IconManager.ICON_K2 -> R.drawable.ic_k2_fg
        else -> R.drawable.ic_g_fg
    }

    /**
     * 执行一次图标切换。
     *
     * 顺序固定为：记录选择 -> enable 目标 + 立即 disable 其余 -> 以新入口重新启动。
     *
     * 两个绝对不能做错的地方：
     *
     * 1. 调完 apply() 之后不能再碰 UI。
     *    禁用当前 alias 会让系统销毁本 Activity，此时再 toast/rebuild 就是崩溃。
     *
     * 2. 绝对不能 killProcess。
     *    重新拉起的新实例与旧实例在**同一个进程**里，杀进程会把新实例一起杀掉，
     *    表现为「切换后 App 起来就闪退」。用 CLEAR_TASK 清掉旧任务即可，
     *    旧 Activity 会随旧任务一起被系统回收。
     */
    private fun switchIcon(index: Int) {
        settings.appIcon = index
        if (!IconManager.apply(this, index)) {
            toastTop("切换失败，当前桌面可能不支持动态图标")
            return
        }
        IconManager.restartInto(applicationContext, index)
    }

    private fun themeSelector(): View {
        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        seg.background = rounded(pal.track, 7)
        seg.setPadding(dp(2), dp(2), dp(2), dp(2))

        val labels = arrayOf("浅色", "深色", "跟随")
        val values = intArrayOf(Settings.THEME_LIGHT, Settings.THEME_DARK, Settings.THEME_SYSTEM)
        val current = settings.theme

        for (i in labels.indices) {
            val t = txt(labels[i], 12f, pal.label, true)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(9), dp(4), dp(9), dp(4))
            if (values[i] == current) t.background = rounded(pal.card, 5)
            val v = values[i]
            t.setOnClickListener { applyTheme(v) }
            seg.addView(t)
        }
        return seg
    }

    // ================================================================ 状态刷新

    private fun refreshStatus() {
        if (!::pill.isInitialized) return
        val connected = hid.isConnected
        val registered = hid.isRegistered
        val connecting = hid.isConnecting

        val text: String
        val color: Int
        val bgColor: Int
        when {
            connected -> {
                text = "已连接"
                color = pal.green
                bgColor = if (pal.isDark) 0x3330D158 else 0x2234C759
            }

            connecting -> {
                text = "连接中"
                color = pal.orange
                bgColor = if (pal.isDark) 0x33FF9F0A else 0x22FF9500
            }

            registered -> {
                text = "待连接"
                color = pal.orange
                bgColor = if (pal.isDark) 0x33FF9F0A else 0x22FF9500
            }

            else -> {
                text = "未注册"
                color = pal.sec
                bgColor = 0x228E8E93
            }
        }
        pill.text = text
        pill.setTextColor(color)
        pill.background = rounded(bgColor, 20)

        if (openSheet == SHEET_CONNECT) refreshConnectSheet()

        if (settings.showDiag && ::diag.isInitialized) {
            val sb = StringBuilder()
            sb.append("陀螺仪 ").append(if (engine.hasGyro) "正常" else "缺失")
                .append(" · 姿态 ").append(if (engine.hasRotation) "3D" else "2D")
                .append('\n')
            sb.append(
                String.format(
                    java.util.Locale.US, "ω %+.2f %+.2f %+.2f",
                    engine.telRawX, engine.telRawY, engine.telRawZ
                )
            ).append('\n')
            sb.append(
                String.format(
                    java.util.Locale.US, "yaw %+.2f pitch %+.2f → dx %+.0f dy %+.0f",
                    engine.telYaw, engine.telPitch, engine.telDx, engine.telDy
                )
            ).append('\n')
            sb.append("报告 ").append(hid.sentReports)
            if (lastStatus.isNotEmpty()) sb.append('\n').append(lastStatus)
            diag.text = sb.toString()
        }
    }

    override fun onStatus(text: String) {
        runOnUiThread {
            lastStatus = text
            if (openSheet == SHEET_CONNECT) refreshConnectSheet()
        }
    }

    override fun onChanged() {
        runOnUiThread {
            refreshStatus()
            if (openSheet == SHEET_CONNECT) refreshConnectSheet()
        }
    }

    // ================================================================ 蓝牙流程

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            AppLog.add("PERM", "Android < 12，蓝牙为安装即授予的普通权限")
            hid.start()
            return
        }
        val all = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        for (p in all) {
            val granted = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            AppLog.add("PERM", p.substringAfterLast('.') + " = " + granted)
        }
        val need = all.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (need.isEmpty()) {
            AppLog.add("PERM", "权限齐全，开始注册")
            hid.start()
        } else {
            AppLog.add("PERM", "申请 " + need.size + " 项权限")
            requestPermissions(need.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        for (i in permissions.indices) {
            val r = if (i < grantResults.size) grantResults[i] else -1
            AppLog.add(
                "PERM",
                "结果 " + permissions[i].substringAfterLast('.') + " = " +
                    (if (r == PackageManager.PERMISSION_GRANTED) "允许" else "拒绝(" + r + ")")
            )
        }
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            hid.start()
        } else {
            toastTop("缺少蓝牙权限，请到 设置 → 应用 → AirMouse → 权限 全部允许")
        }
    }

    private fun makeDiscoverable() {
        AppLog.add("BT", "请求「可被发现」300 秒")
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        try {
            startActivityForResult(intent, REQ_DISCOVERABLE)
        } catch (t: Throwable) {
            AppLog.add("BT", "可被发现失败: " + t)
            toastTop("无法开启可被发现：${t.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_DISCOVERABLE) return
        // ACTION_REQUEST_DISCOVERABLE 的 resultCode 就是可被发现的秒数，取消时为 RESULT_CANCELED(0)
        if (resultCode > 0) {
            discoverUntil = SystemClock.elapsedRealtime() + resultCode * 1000L
            ui.removeCallbacks(discoverTick)
            ui.post(discoverTick)
            toastTop("已开启可被发现 $resultCode 秒，请在平板上搜索「${hid.localName}」")
        } else {
            discoverUntil = 0
            refreshConnectSheet()
            toastTop("你取消了「可被发现」")
        }
    }

    /** 尝试主动关闭可被发现（隐藏 API，失败时返回 false） */
    private fun tryStopDiscoverable(): Boolean {
        return try {
            val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val ad = mgr?.adapter ?: return false
            val method = BluetoothAdapter::class.java.getMethod(
                "setScanMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(ad, BluetoothAdapter.SCAN_MODE_CONNECTABLE, -1)
            true
        } catch (t: Throwable) {
            false
        }
    }

    // ================================================================ 反馈盘

    private class DiscView(context: android.content.Context) : View(context) {

        var ringColor: Int = 0x14000000
        var accentColor: Int = 0xFF007AFF.toInt()

        private var ox = 0f
        private var oy = 0f
        private var maxOff = 100f

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
            maxOff = minOf(w, h) * 0.34f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f

            ringPaint.color = ringColor
            canvas.drawCircle(cx, cy, maxOff, ringPaint)
            canvas.drawLine(cx - maxOff, cy, cx + maxOff, cy, ringPaint)
            canvas.drawLine(cx, cy - maxOff, cx, cy + maxOff, ringPaint)

            val px = cx + ox
            val py = cy + oy

            glowPaint.color = (accentColor and 0x00FFFFFF) or 0x2E000000
            canvas.drawCircle(px, py, 30f, glowPaint)

            dotPaint.color = accentColor
            canvas.drawCircle(px, py, 15f, dotPaint)

            ox *= 0.94f
            oy *= 0.94f
            if (abs(ox) > 0.4f || abs(oy) > 0.4f) postInvalidateOnAnimation()
        }
    }
}
