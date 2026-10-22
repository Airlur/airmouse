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
 * 触控板：手指在屏幕上滑动 -> 指针移动，像笔记本的触摸板。
 *
 * 手势表：
 *   单指滑动            指针移动
 *   轻点（<200ms 且没动） 左键单击（延迟 200ms 触发，给「点住拖」留判定窗口）
 *   长按 550ms 不动      右键单击（带震动）
 *   点一下再按住并移动    拖拽（左键保持按下）
 *   双指滑动            滚轮
 *   双指轻点            右键单击
 *
 * 延迟 200ms 再发左键，是为了区分「单击」和「点住拖」——这是触摸板的通行做法。
 */
class TouchpadView(context: Context) : View(context) {

    var onMove: ((Float, Float) -> Unit)? = null
    var onLeftDown: (() -> Unit)? = null
    var onLeftUp: (() -> Unit)? = null
    var onRightClick: (() -> Unit)? = null
    var onWheel: ((Int) -> Unit)? = null
    var onDragStateChanged: ((Boolean) -> Unit)? = null

    /** 指针速度倍率 */
    @Volatile var gain: Float = 1.8f

    private val handler = Handler(Looper.getMainLooper())

    private val slop = 12f
    private val longPressMs = 550L
    private val tapMaxMs = 200L
    private val doubleTapWindowMs = 260L
    private val scrollStepPx = 42f

    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var moved = 0f

    private var dragging = false
    private var longPressFired = false
    private var clickPending = false

    private var lastTapUpTime = 0L

    private var twoFinger = false
    private var scrollAcc = 0f
    private var twoFingerMoved = 0f

    // 绘制反馈
    private var touchX = 0f
    private var touchY = 0f
    private var touching = false

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1F007AFF }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x33007AFF
    }

    private val longPressRunnable = Runnable {
        if (!touching || dragging || moved > slop) return@Runnable
        longPressFired = true
        clickPending = false
        handler.removeCallbacks(clickRunnable)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onRightClick?.invoke()
    }

    private val clickRunnable = Runnable {
        if (!clickPending) return@Runnable
        clickPending = false
        onLeftDown?.invoke()
        onLeftUp?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                touchX = event.x
                touchY = event.y
                touching = true
                twoFinger = false
                scrollAcc = 0f
                twoFingerMoved = 0f
                longPressFired = false

                // 上一次轻点后 260ms 内又按下 -> 进入拖拽
                if (now - lastTapUpTime < doubleTapWindowMs && !dragging) {
                    clickPending = false
                    handler.removeCallbacks(clickRunnable)
                    dragging = true
                    onDragStateChanged?.invoke(true)
                    onLeftDown?.invoke()
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }

                downTime = now
                lastX = event.x
                lastY = event.y
                moved = 0f

                if (!dragging) {
                    handler.postDelayed(longPressRunnable, longPressMs)
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    twoFinger = true
                    twoFingerMoved = 0f
                    scrollAcc = 0f
                    lastY = avgY(event)
                    lastX = avgX(event)
                    handler.removeCallbacks(longPressRunnable)
                    clickPending = false
                    handler.removeCallbacks(clickRunnable)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && twoFinger) {
                    val y = avgY(event)
                    val dy = y - lastY
                    lastY = y
                    twoFingerMoved += abs(dy)
                    scrollAcc += dy
                    val steps = (scrollAcc / scrollStepPx).toInt()
                    if (steps != 0) {
                        scrollAcc -= steps * scrollStepPx
                        onWheel?.invoke(steps) // 手指上滑 -> 内容上滚
                    }
                } else if (event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    touchX = event.x
                    touchY = event.y
                    moved += hypot(dx, dy)

                    if (moved > slop) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (moved > slop || dragging) {
                        onMove?.invoke(dx * gain, dy * gain)
                    }
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                touching = false
                invalidate()

                if (twoFinger) {
                    // 双指轻点 -> 右键
                    if (twoFingerMoved < slop) onRightClick?.invoke()
                    twoFinger = false
                } else if (dragging) {
                    dragging = false
                    onDragStateChanged?.invoke(false)
                    onLeftUp?.invoke()
                } else if (!longPressFired && moved < slop) {
                    val dur = System.currentTimeMillis() - downTime
                    if (dur < tapMaxMs) {
                        // 延迟触发单击，给「点住拖」留窗口
                        clickPending = true
                        lastTapUpTime = System.currentTimeMillis()
                        handler.postDelayed(clickRunnable, doubleTapWindowMs)
                    }
                } else if (longPressFired) {
                    longPressFired = false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                touching = false
                twoFinger = false
                if (dragging) {
                    dragging = false
                    onDragStateChanged?.invoke(false)
                    onLeftUp?.invoke()
                }
                invalidate()
            }
        }
        return true
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
        canvas.drawCircle(touchX, touchY, r, dotPaint)
        canvas.drawCircle(touchX, touchY, r, ringPaint)
    }
}
