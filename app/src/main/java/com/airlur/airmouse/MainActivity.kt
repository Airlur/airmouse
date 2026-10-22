package com.airlur.airmouse

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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
        private const val MODE_AIR = 0
        private const val MODE_PAD = 1
    }

    private lateinit var settings: Settings
    private lateinit var pal: Palette
    private lateinit var engine: AirMouseEngine
    private lateinit var hid: HidMouse
    private lateinit var touchpad: TouchpadView
    private lateinit var sheetHost: SheetHost

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
    private lateinit var invXToggle: ToggleSwitch
    private lateinit var invYToggle: ToggleSwitch

    private var mode = MODE_AIR
    private var leftDown = false
    private var rightDown = false
    private var lastStatus = ""

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 200L)
        }
    }

    // ================================================================ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        pal = Palette.of(resolveDark())
        engine = AirMouseEngine(this)
        hid = HidMouse(this, this)

        buildUi()
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

    // ================================================================ 主界面

    private fun buildUi() {
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

        setContentView(root)
        setMode(MODE_AIR)
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val title = txt("AirMouse", 26f, pal.label, true)
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        pill = txt("未注册", 11f, pal.sec, true)
        pill.setPadding(dp(9), dp(5), dp(9), dp(5))
        pill.background = rounded(0x228E8E93, 20)
        pill.setOnClickListener { showConnectSheet() }
        row.addView(pill)
        row.addView(space(6))

        val gear = IconButton(this, IconButton.KIND_SLIDERS)
        gear.circleColor = pal.fillBtn
        gear.fgColor = pal.sec
        gear.setOnClickListener { showSettingsSheet() }
        row.addView(gear)
        row.addView(space(6))

        val help = IconButton(this, IconButton.KIND_HELP)
        help.circleColor = pal.fillBtn
        help.fgColor = pal.sec
        help.setOnClickListener { showHelpSheet() }
        row.addView(help)

        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(row)
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

        segAir = segLabel("空鼠")
        segPad = segLabel("触控板")
        seg.addView(segAir, LinearLayout.LayoutParams(0, dp(32), 1f))
        seg.addView(segPad, LinearLayout.LayoutParams(0, dp(32), 1f))

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
        paneAir.visibility = if (air) View.VISIBLE else View.GONE
        panePad.visibility = if (air) View.GONE else View.VISIBLE
        segAir.background = if (air) rounded(pal.card, 7) else null
        segPad.background = if (air) null else rounded(pal.card, 7)
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

        // 灵敏度
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

        pane.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        pane.addView(spacer(dp(9)))

        // 反向开关（同一行两个）
        pane.addView(buildInvertRow())
        pane.addView(spacer(dp(9)))

        // 按键区
        pane.addView(buildDeck(true))

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
        row.layoutParams = fullWrap()

        val left = invertCell("水平反向", settings.invertX) { v ->
            settings.invertX = v
            engine.invertX = v
        }
        invXToggle = left.second

        val right = invertCell("垂直反向", settings.invertY) { v ->
            settings.invertY = v
            engine.invertY = v
        }
        invYToggle = right.second

        row.addView(left.first, LinearLayout.LayoutParams(0, dp(48), 1f))

        val div = View(this)
        div.setBackgroundColor(pal.sep)
        val divLp = LinearLayout.LayoutParams(dp(1), dp(30))
        divLp.gravity = Gravity.CENTER_VERTICAL
        row.addView(div, divLp)

        row.addView(right.first, LinearLayout.LayoutParams(0, dp(48), 1f))
        return row
    }

    private fun invertCell(
        label: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): Pair<LinearLayout, ToggleSwitch> {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.HORIZONTAL
        cell.gravity = Gravity.CENTER_VERTICAL
        cell.setPadding(dp(14), 0, dp(14), 0)
        cell.isClickable = true

        val t = txt(label, 14f, pal.label, false)
        cell.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

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
        return Pair(cell, sw)
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
        frame.addView(padHintView("单指滑动 = 移动指针", Gravity.CENTER))
        frame.addView(padHintView("轻点 = 左键 · 连点两下按住 = 拖拽", Gravity.BOTTOM or Gravity.START))
        frame.addView(padHintView("长按 = 右键", Gravity.BOTTOM or Gravity.END))

        pane.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pane.addView(spacer(dp(10)))

        // 指针速度
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

        pane.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        pane.addView(spacer(dp(10)))
        pane.addView(buildDeck(false))

        val legend = txt("左键锁定后，单指滑动面板即可框选", 10.5f, pal.sub, false)
        legend.gravity = Gravity.CENTER
        pane.addView(legend, fullWrap())

        return pane
    }

    private fun padHintView(text: String, gravity: Int): View {
        val t = txt(text, 11f, pal.sub, false)
        if (text.startsWith("单指")) padHint = t
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
        deck.layoutParams = fullWrap()

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
            val lp = LinearLayout.LayoutParams(dp(9), 1)
            deck.addView(gap, lp)
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
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    engine.suppress(120)
                    if (latched) {
                        latched = false
                        paint(false)
                        hid.setButton(mask, false)
                        if (isLeft) leftDown = false else rightDown = false
                        toast(if (isLeft) "左键 已释放" else "右键 已释放")
                        return@setOnTouchListener true
                    }
                    hid.setButton(mask, true)
                    if (isLeft) leftDown = true else rightDown = true
                    paint(true)

                    if (settings.latchEnabled) {
                        val r = Runnable {
                            latched = true
                            engine.suppress(200)
                            toast(if (isLeft) "左键 已锁定 · 滑动即可拖拽" else "右键 已锁定")
                        }
                        timer = r
                        box.postDelayed(r, settings.latchMs.toLong())
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val t = timer
                    if (t != null) box.removeCallbacks(t)
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
        val knobLp = FrameLayout.LayoutParams(dp(30), dp(34), Gravity.CENTER)
        box.addView(knob, knobLp)

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
                    var notches = (acc / step).toInt()
                    if (notches != 0) {
                        acc -= notches * step
                        if (settings.wheelInvert) notches = -notches
                        hid.wheel(-notches)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.background = rounded(pal.keyBg, 12)
                    knob.background = rounded(pal.track, 40)
                    if (!moved && settings.midClick) {
                        hid.click(HidMouse.BTN_MIDDLE, 30L)
                        toast("中键")
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
            override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                onChange(p)
            }

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
        box.layoutParams = fullWrap()

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(
            txt(label, 13f, pal.label, false),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val valueText = txt(fmt(value), 13f, pal.sec, false)
        row.addView(valueText)
        box.addView(row)

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
        wrap.layoutParams = fullWrap().apply { topMargin = dp(8) }
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
        v.layoutParams = fullWrap()
        return v
    }

    private fun sheetSub(t: String): View {
        val v = txt(t, 12f, pal.sec, false)
        v.setPadding(0, 0, 0, dp(12))
        v.layoutParams = fullWrap()
        return v
    }

    private fun sheetRoot(): LinearLayout {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.setPadding(dp(16), dp(2), dp(16), dp(18))
        return v
    }

    // ================================================================ 抽屉

    private fun showSheet(content: View) {
        sheetHost.show(content, pal)
    }

    private fun showHelpSheet() {
        val root = sheetRoot()
        root.addView(sheetTitle("使用说明"))
        root.addView(sheetSub("可上下滑动 · 拖顶部横条或点空白处关闭"))

        root.addView(sheetPara("两种模式", "空鼠：握着手机转手腕控制指针，适合靠坐、远距离。\n触控板：手机放桌上，手指在白色区域滑动，像笔记本触摸板。"))
        root.addView(sheetPara("顶部状态胶囊", "灰＝未注册，橙＝待配对，绿＝已连接。点它打开连接面板。"))
        root.addView(sheetPara("框选 / 拖拽（两种都支持）", "双击拖拽：轻点一下 → 在设定时间内再按住并滑动 → 松手结束。适合手机放桌上。\n按钮锁定：按住左键到设定时长变蓝锁定 → 滑动手腕或面板 → 再点一下释放。适合单手握机。\n两者可在设置里分别开关。"))
        root.addView(sheetPara("单击会不会变慢", "不会。轻点立即发出左键，不等待双击判定。"))
        root.addView(sheetPara("触控板手势", "单指滑＝移动指针；轻点＝左键；双击按住＝拖拽；长按＝右键；双指滑＝滚轮。"))
        root.addView(sheetPara("灵敏度 / 指针速度", "空鼠的「灵敏度」是转手腕的放大倍数；触控板的「指针速度」是手指滑动的放大倍数。"))
        root.addView(sheetPara("水平 / 垂直反向", "只影响空鼠模式。方向反了就打开，不用改代码。"))
        root.addView(sheetPara("滚轮区", "上下滑动＝页面滚动；轻点＝中键单击。"))
        root.addView(sheetPara("设置按钮", "齿轮图标里可以改主题、手势时长、拖拽方式开关、震动、常亮等，改完立即生效并记住。"))

        val close = bigButton("知道了", false)
        close.setOnClickListener { sheetHost.dismiss() }
        root.addView(close)

        showSheet(root)
    }

    private fun sheetPara(title: String, body: String): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(6), 0, dp(6))
        box.layoutParams = fullWrap()
        val t = txt(title, 13.5f, pal.label, true)
        box.addView(t)
        val b = txt(body, 12.5f, pal.sec, false)
        b.setPadding(0, dp(3), 0, 0)
        b.setLineSpacing(0f, 1.25f)
        box.addView(b)
        return box
    }

    private fun showConnectSheet() {
        val root = sheetRoot()
        root.addView(sheetTitle("连接平板"))
        val state = txt(connectStateText(), 12f, pal.sec, false)
        state.setPadding(0, 0, 0, dp(12))
        root.addView(state)

        val discover = bigButton("让平板发现我（5 分钟）", true)
        discover.setOnClickListener {
            makeDiscoverable()
            state.text = "已开启可被发现（5 分钟），请在平板上搜索 AirMouse"
        }
        root.addView(discover)

        val tip = txt(
            "点这个之后，在平板上打开 设置 → 蓝牙 → 搜索新设备 → 选 AirMouse → 配对。只需一次，以后自动连。",
            11.5f, pal.sec, false
        )
        tip.setPadding(dp(2), dp(8), dp(2), dp(12))
        tip.setLineSpacing(0f, 1.25f)
        root.addView(tip)

        val devices = hid.bondedDevices()
        val rows = ArrayList<View>()
        if (devices.isEmpty()) {
            rows.add(sheetItem("没有已配对设备", "先在平板上搜索 AirMouse 并配对", null))
        } else {
            for (d in devices) {
                val name = try {
                    d.name ?: d.address
                } catch (e: SecurityException) {
                    d.address
                }
                val item = sheetItem(name, "点击连接", txt("连接", 13f, pal.sec, false))
                item.setOnClickListener {
                    hid.connectDevice(d)
                    state.text = "正在连接 $name …"
                }
                rows.add(item)
            }
        }
        root.addView(group(null, *rows.toTypedArray()))

        val disconnect = bigButton("断开当前连接", false)
        disconnect.setOnClickListener {
            hid.disconnectCurrent()
            state.text = "已断开，等待平板重新连接"
        }
        root.addView(disconnect)

        val close = txt("关闭", 15f, pal.accent, true)
        close.gravity = Gravity.CENTER
        close.setPadding(0, dp(11), 0, dp(4))
        close.setOnClickListener { sheetHost.dismiss() }
        root.addView(close)

        showSheet(root)
    }

    private fun connectStateText(): String = when {
        hid.isConnected -> "已连接，可以直接使用"
        hid.isRegistered -> "已注册为蓝牙鼠标，等待平板连接"
        else -> "正在注册为蓝牙鼠标…"
    }

    private fun showSettingsSheet() {
        val root = sheetRoot()
        root.addView(sheetTitle("设置"))
        root.addView(sheetSub("改动立即生效"))

        // 外观
        val themeRow = LinearLayout(this)
        themeRow.orientation = LinearLayout.HORIZONTAL
        themeRow.gravity = Gravity.CENTER_VERTICAL
        themeRow.setPadding(0, dp(4), 0, dp(6))
        themeRow.layoutParams = fullWrap()
        themeRow.addView(
            txt("外观", 12f, pal.sec, false),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        themeRow.addView(themeSelector())
        root.addView(themeRow)

        root.addView(
            group(
                null,
                sheetItem("深色模式", "切换后全部界面同步", sheetSwitch(resolveDark()) { v ->
                    settings.theme = if (v) Settings.THEME_DARK else Settings.THEME_LIGHT
                    recreate()
                })
            )
        )

        // 手势开关
        root.addView(
            group(
                "手势",
                sheetItem("双击拖拽", "轻点后短时间内再按住＝拖拽", sheetSwitch(settings.doubleTapDrag) { v ->
                    settings.doubleTapDrag = v
                    touchpad.doubleTapDragEnabled = v
                }),
                sheetItem("按钮锁定", "按住左键变蓝，持续按下", sheetSwitch(settings.latchEnabled) { v ->
                    settings.latchEnabled = v
                })
            )
        )

        // 时间参数
        val sliders = LinearLayout(this)
        sliders.orientation = LinearLayout.VERTICAL
        sliders.background = rounded(pal.grp, 11)
        sliders.addView(sheetSeek("双击判定窗口", 120, 500, settings.dblWindowMs, { "$it ms" }) { v ->
            settings.dblWindowMs = v
            touchpad.dblWindowMs = v
        })
        sliders.addView(divider())
        sliders.addView(sheetSeek("长按右键时长", 300, 900, settings.longPressMs, { "$it ms" }) { v ->
            settings.longPressMs = v
            touchpad.longPressMs = v
        })
        sliders.addView(divider())
        sliders.addView(sheetSeek("左键锁定按住时长", 200, 800, settings.latchMs, { "$it ms" }) { v ->
            settings.latchMs = v
        })
        sliders.addView(divider())
        sliders.addView(sheetSeek("轻点判定上限", 150, 500, settings.tapMaxMs, { "$it ms" }) { v ->
            settings.tapMaxMs = v
            touchpad.tapMaxMs = v
        })
        sliders.addView(divider())
        sliders.addView(sheetSeek("滚轮一格距离", 12, 60, settings.wheelStepPx, { "$it px" }) { v ->
            settings.wheelStepPx = v
            touchpad.bindFrom(settings)
        })
        root.addView(group("时间参数", sliders))

        // 其他
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
            toast("已恢复默认")
            recreate()
        }
        root.addView(reset)

        val done = txt("完成", 15f, pal.accent, true)
        done.gravity = Gravity.CENTER
        done.setPadding(0, dp(11), 0, dp(4))
        done.setOnClickListener { sheetHost.dismiss() }
        root.addView(done)

        showSheet(root)
    }

    private fun themeSelector(): View {
        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        seg.background = rounded(pal.track, 7)
        seg.setPadding(dp(2), dp(2), dp(2), dp(2))
        seg.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val labels = arrayOf("浅色", "深色", "跟随")
        val values = intArrayOf(Settings.THEME_LIGHT, Settings.THEME_DARK, Settings.THEME_SYSTEM)
        val current = settings.theme

        for (i in labels.indices) {
            val t = txt(labels[i], 12f, pal.label, true)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(9), dp(4), dp(9), dp(4))
            if (values[i] == current) t.background = rounded(pal.card, 5)
            val v = values[i]
            t.setOnClickListener {
                settings.theme = v
                recreate()
            }
            seg.addView(t)
        }
        return seg
    }

    // ================================================================ 状态刷新

    private fun refreshStatus() {
        val connected = hid.isConnected
        val registered = hid.isRegistered
        val text: String
        val color: Int
        val bgColor: Int
        when {
            connected -> {
                text = "已连接"
                color = pal.green
                bgColor = if (pal.isDark) 0x3330D158 else 0x2234C759
            }

            registered -> {
                text = "待配对"
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

        if (settings.showDiag) {
            val sb = StringBuilder()
            sb.append("陀螺仪 ").append(if (engine.hasGyro) "正常" else "缺失")
                .append(" · 姿态 ").append(if (engine.hasRotation) "3D" else "2D")
                .append('\n')
            sb.append(
                String.format(
                    java.util.Locale.US,
                    "ω %+.2f %+.2f %+.2f",
                    engine.telRawX, engine.telRawY, engine.telRawZ
                )
            ).append('\n')
            sb.append(
                String.format(
                    java.util.Locale.US,
                    "yaw %+.2f pitch %+.2f → dx %+.0f dy %+.0f",
                    engine.telYaw, engine.telPitch, engine.telDx, engine.telDy
                )
            ).append('\n')
            sb.append("报告 ").append(hid.sentReports)
            if (lastStatus.isNotEmpty()) sb.append('\n').append(lastStatus)
            diag.text = sb.toString()
        }
    }

    override fun onStatus(text: String) {
        runOnUiThread { lastStatus = text }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ================================================================ 蓝牙流程

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            hid.start()
            return
        }
        val need = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (need.isEmpty()) {
            hid.start()
        } else {
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
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            hid.start()
        } else {
            toast("缺少蓝牙权限，请到 设置 → 应用 → AirMouse → 权限 全部允许")
        }
    }

    private fun makeDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        try {
            startActivityForResult(intent, REQ_DISCOVERABLE)
        } catch (t: Throwable) {
            toast("无法开启「可被发现」：${t.message}")
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
