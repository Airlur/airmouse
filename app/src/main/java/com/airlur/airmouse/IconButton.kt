package com.airlur.airmouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 顶部圆形图标按钮。
 *
 * kind = KIND_SLIDERS 时画三条带滑块的横线，
 * kind = KIND_HELP    时画一个问号，
 * kind = KIND_ICON    时使用 drawable 里的标准矢量图标（传 resId）。
 *
 * 自己画的原因：项目零依赖，不引入 AndroidX / 图标库；
 * 同时避免用 "⚙" 之类的 Unicode 字符在不同系统上字体不一致。
 * bug 图标用矢量 drawable，是因为手画的虫子比例不对、看着粗糙。
 */
class IconButton(
    context: Context,
    private val kind: Int,
    iconRes: Int = 0
) : View(context) {

    companion object {
        const val KIND_SLIDERS = 0
        const val KIND_HELP = 1
        const val KIND_ICON = 2
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
            icon?.setTint(v)
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

    /** 矢量图标（仅 KIND_ICON 使用） */
    private var icon: Drawable? = if (iconRes != 0) context.getDrawable(iconRes) else null

    init {
        textPaint.textSize = dp(15).toFloat()
        icon?.setTint(fgColor)
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

        if (kind == KIND_ICON) {
            val d = icon
            if (d != null) {
                // 图标占圆内的 58%，比圆形按钮略小，视觉上不顶边
                val half = (r * 0.58f).toInt()
                d.setBounds(
                    (cx - half).toInt(), (cy - half).toInt(),
                    (cx + half).toInt(), (cy + half).toInt()
                )
                d.setTint(fgColor)
                d.draw(canvas)
            } else {
                drawBug(canvas, cx, cy, r)
            }
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

    /**
     * 虫子图标（调试用，类似 IDE 里的 debug 断点图标）
     * 由「椭圆身体 + 头部 + 六条腿 + 两根触角」组成
     */
    private fun drawBug(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val bodyR = r * 0.20f
        val bodyTop = cy - r * 0.34f
        val bodyBottom = cy + r * 0.44f
        val headR = r * 0.155f
        val headY = cy - r * 0.52f

        // 身体（椭圆）
        fgPaint.style = Paint.Style.STROKE
        fgPaint.strokeWidth = dp(2).toFloat() * 0.75f
        canvas.drawOval(
            cx - bodyR, bodyTop,
            cx + bodyR, bodyBottom,
            fgPaint
        )

        // 头
        canvas.drawCircle(cx, headY, headR, fgPaint)

        // 触角
        canvas.drawLine(cx - headR * 0.6f, headY - headR * 0.7f,
            cx - headR * 1.7f, headY - headR * 2.1f, fgPaint)
        canvas.drawLine(cx + headR * 0.6f, headY - headR * 0.7f,
            cx + headR * 1.7f, headY - headR * 2.1f, fgPaint)

        // 六条腿，左右各三
        val legYs = floatArrayOf(
            bodyTop + (bodyBottom - bodyTop) * 0.22f,
            bodyTop + (bodyBottom - bodyTop) * 0.50f,
            bodyTop + (bodyBottom - bodyTop) * 0.78f
        )
        val spread = bodyR * 2.05f
        for (y in legYs) {
            canvas.drawLine(cx - bodyR * 0.85f, y, cx - spread, y - r * 0.06f, fgPaint)
            canvas.drawLine(cx + bodyR * 0.85f, y, cx + spread, y - r * 0.06f, fgPaint)
        }

        // 身体中缝，让它更像虫子
        canvas.drawLine(cx, bodyTop + (bodyBottom - bodyTop) * 0.15f,
            cx, bodyBottom - (bodyBottom - bodyTop) * 0.12f, fgPaint)
    }
}
