package com.airlur.airmouse

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * iOS 风格开关。自己画，避免依赖 AndroidX 的 SwitchCompat。
 *
 * 只负责显示状态，点击事件交给外层可点击的整行处理。
 */
class ToggleSwitch(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var progress = 0f
    private var animator: ValueAnimator? = null

    /** 当前是否打开（供点击时取反用） */
    var isChecked: Boolean = false
        private set

    var trackOff: Int = 0xFFE5E5EA.toInt()
        set(v) {
            field = v
            invalidate()
        }

    var trackOn: Int = 0xFF34C759.toInt()
        set(v) {
            field = v
            invalidate()
        }

    var knobColor: Int = 0xFFFFFFFF.toInt()

    /** 大号 44x27（设置页），小号 40x24（主界面反向开关） */
    var big: Boolean = true
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    init {
        knobPaint.color = knobColor
        isClickable = false
        setWillNotDraw(false)
    }

    fun setChecked(checked: Boolean, animated: Boolean) {
        isChecked = checked
        val target = if (checked) 1f else 0f
        if (animated) {
            animator?.cancel()
            val a = ValueAnimator.ofFloat(progress, target)
            a.duration = 160L
            a.addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                progress = v
                invalidate()
            }
            animator = a
            a.start()
        } else {
            animator?.cancel()
            progress = target
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (big) dp(44) else dp(40)
        val h = if (big) dp(27) else dp(24)
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val r = h / 2f
        val offR = android.graphics.Color.red(trackOff)
        val offG = android.graphics.Color.green(trackOff)
        val offB = android.graphics.Color.blue(trackOff)
        val onR = android.graphics.Color.red(trackOn)
        val onG = android.graphics.Color.green(trackOn)
        val onB = android.graphics.Color.blue(trackOn)

        trackPaint.color = android.graphics.Color.rgb(
            (offR + (onR - offR) * progress).toInt(),
            (offG + (onG - offG) * progress).toInt(),
            (offB + (onB - offB) * progress).toInt()
        )

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        knobPaint.color = knobColor
        val pad = dp(2).toFloat()
        val knobR = (h - pad * 2f) / 2f
        val travel = w - h
        val cx = pad + knobR + travel * progress
        canvas.drawCircle(cx, h / 2f, knobR, knobPaint)
    }
}
