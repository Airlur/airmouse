package com.airlur.airmouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 顶部圆形图标按钮。
 *
 * kind = KIND_SLIDERS 时画三条带滑块的横线（设置），
 * kind = KIND_HELP    时画一个问号。
 *
 * 自己画的原因：项目零依赖，不引入 AndroidX / 图标库；
 * 同时避免用 "⚙" 之类的 Unicode 字符在不同系统上字体不一致。
 */
class IconButton(context: Context, private val kind: Int) : View(context) {

    companion object {
        const val KIND_SLIDERS = 0
        const val KIND_HELP = 1
    }

    var circleColor: Int = 0xFFE5E5EA.toInt()
        set(v) {
            field = v
            invalidate()
        }

    var fgColor: Int = 0xFF8E8E93.toInt()
        set(v) {
            field = v
            fgPaint.color = v
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15f
        isFakeBoldText = true
    }

    init {
        textPaint.textSize = dp(15).toFloat()
        setWillNotDraw(false)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = dp(28)
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f

        bgPaint.color = circleColor
        canvas.drawCircle(cx, cy, r, bgPaint)

        fgPaint.color = fgColor
        fgPaint.strokeWidth = dp(2).toFloat() * 0.8f

        if (kind == KIND_HELP) {
            textPaint.color = fgColor
            val metrics = textPaint.fontMetrics
            val baseline = cy - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText("?", cx, baseline, textPaint)
            return
        }

        // 三条滑杆，长短与滑块位置错开，视觉上更像设置图标
        val half = r * 0.52f
        val gap = r * 0.44f
        val knobR = r * 0.13f
        val ys = floatArrayOf(cy - gap, cy, cy + gap)
        val knobPos = floatArrayOf(cx + half * 0.45f, cx - half * 0.35f, cx + half * 0.15f)

        for (i in 0 until 3) {
            val y = ys[i]
            canvas.drawLine(cx - half, y, cx + half, y, fgPaint)
            bgPaint.color = circleColor
            canvas.drawCircle(knobPos[i], y, knobR * 1.7f, bgPaint)
            fgPaint.style = Paint.Style.FILL
            canvas.drawCircle(knobPos[i], y, knobR, fgPaint)
            fgPaint.style = Paint.Style.STROKE
        }
    }
}
