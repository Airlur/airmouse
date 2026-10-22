package com.airlur.airmouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 触控板：手指在面板上滑动来移动指针，像笔记本触摸板。
 *
 * 手势：
 *   单指滑动             移动指针
 *   轻点                 左键单击（立即触发，不等待双击判定）
 *   轻点后短时间内再按住   拖拽（左键保持按下）—— 对应笔记本的「双击拖拽」
 *   长按不动             右键单击
 *   双指滑动             滚轮
 *   双指轻点             右键单击
 *
 * 所有时间阈值都从 Settings 读取，可在 App 里实时调整。
 */
class TouchpadView(context: Context) : View(context) {

    var onMove: ((Float, Float) -> Unit)? = null
    var onLeftClick: (() -> Unit)? = null
    var onLeftDown: (() -> Unit)? = null
    var onLeftUp: (() -> Unit)? = null
    var onRightClick: (() -> Unit)? = null
    var onWheel: ((Int) -> Unit)? = null
    var onDragStateChanged: ((Boolean) -> Unit)? = null

    var gain: Float = 1.8f
    var doubleTapDragEnabled: Boolean = true
    var dblWindowMs: Int = 260
    var longPressMs: Int = 520
    var tapMaxMs: Int = 300
    var wheelStepPx: Float = 42f
    var wheelInvert: Boolean = false
    var hapticsEnabled: Boolean = true

    private val handler = Handler(Looper.getMainLooper())
    private val slop = 12f

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF007AFF.toInt() }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33007AFF }

    var accentColor: Int = 0xFF007AFF.toInt()
        set(v) {
            field = v
            dotPaint.color = v
        }

    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var moved = 0f

    private var dragging = false
    private var longFired = false
    private var twoFinger = false
    private var scrollAcc = 0f
    private var twoFingerMoved = 0f
    private var lastTapUpTime = 0L

    private var touching = false
    private var touchX = 0f
    private var touchY = 0f

    private val longPressRunnable = Runnable {
        if (!touching || dragging || moved > slop) return@Runnable
        longFired = true
        haptic(HapticFeedbackConstants.LONG_PRESS)
        onRightClick?.invoke()
    }

    fun bindFrom(s: Settings) {
        doubleTapDragEnabled = s.doubleTapDrag
        dblWindowMs = s.dblWindowMs
        longPressMs = s.longPressMs
        tapMaxMs = s.tapMaxMs
        wheelInvert = s.wheelInvert
        gain = s.padGain()
        // 手感换算：设置里的 px 是「滚轮一格需要手指移动多少像素」
        wheelStepPx = (s.wheelStepPx * resources.displayMetrics.density).coerceAtLeast(8f)
        hapticsEnabled = s.haptics
    }

    private fun haptic(kind: Int) {
        if (!hapticsEnabled) return
        try {
            performHapticFeedback(kind)
        } catch (_: Throwable) {
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    twoFinger = true
                    twoFingerMoved = 0f
                    scrollAcc = 0f
                    lastY = avgY(event)
                    lastX = avgX(event)
                    handler.removeCallbacks(longPressRunnable)
                }
            }

            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_POINTER_UP -> handler.removeCallbacks(longPressRunnable)
            MotionEvent.ACTION_UP -> handleUp()
            MotionEvent.ACTION_CANCEL -> handleCancel()
        }
        return true
    }

    private fun handleDown(event: MotionEvent) {
        downTime = System.currentTimeMillis()
        lastX = event.x
        lastY = event.y
        moved = 0f
        longFired = false
        twoFinger = false
        scrollAcc = 0f
        twoFingerMoved = 0f
        touching = true
        touchX = event.x
        touchY = event.y

        val now = downTime
        val inDoubleTap = doubleTapDragEnabled && lastTapUpTime > 0L &&
            (now - lastTapUpTime) <= dblWindowMs

        if (inDoubleTap) {
            dragging = true
            lastTapUpTime = 0L
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            onDragStateChanged?.invoke(true)
            onLeftDown?.invoke()
        } else {
            dragging = false
            handler.postDelayed(longPressRunnable, longPressMs.toLong())
        }
        invalidate()
    }

    private fun handleMove(event: MotionEvent) {
        if (event.pointerCount >= 2) {
            val y = avgY(event)
            val dy = y - lastY
            lastY = y
            lastX = avgX(event)
            twoFingerMoved += abs(dy)
            scrollAcc += dy // 手指上滑为负
            val step = wheelStepPx
            if (step > 0f) {
                val notches = (scrollAcc / step).toInt()
                if (notches != 0) {
                    scrollAcc -= notches * step
                    // 手指上滑（dy<0）=> notches<0 => 取反后为正向，即「内容向上滚」
                    var w = -notches
                    if (wheelInvert) w = -w
                    if (w != 0) onWheel?.invoke(w)
                }
            }
            return
        }

        val dx = event.x - lastX
        val dy = event.y - lastY
        lastX = event.x
        lastY = event.y
        touchX = event.x
        touchY = event.y
        moved += hypot(dx, dy)

        if (moved > slop) handler.removeCallbacks(longPressRunnable)

        if (dragging || moved > slop) {
            onMove?.invoke(dx * gain, dy * gain)
        }
        invalidate()
    }

    private fun handleUp() {
        handler.removeCallbacks(longPressRunnable)
        val dur = System.currentTimeMillis() - downTime
        touching = false
        invalidate()

        if (dragging) {
            dragging = false
            lastTapUpTime = 0L
            onDragStateChanged?.invoke(false)
            onLeftUp?.invoke()
            return
        }

        if (twoFinger) {
            if (twoFingerMoved < slop) {
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                onRightClick?.invoke()
            }
            twoFinger = false
            lastTapUpTime = 0L
            return
        }

        if (!longFired && moved < slop && dur <= tapMaxMs) {
            onLeftClick?.invoke()
            lastTapUpTime = System.currentTimeMillis()
        } else {
            lastTapUpTime = 0L
        }
    }

    private fun handleCancel() {
        handler.removeCallbacks(longPressRunnable)
        touching = false
        if (dragging) {
            dragging = false
            onDragStateChanged?.invoke(false)
            onLeftUp?.invoke()
        }
        twoFinger = false
        lastTapUpTime = 0L
        invalidate()
    }

    private fun avgX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!touching) return
        val r = if (dragging) 30f else 22f
        glowPaint.color = accentColor and 0x00FFFFFF or 0x33000000
        canvas.drawCircle(touchX, touchY, r * 1.5f, glowPaint)
        canvas.drawCircle(touchX, touchY, r, dotPaint)
    }
}
