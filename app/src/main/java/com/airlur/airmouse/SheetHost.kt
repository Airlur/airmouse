package com.airlur.airmouse

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 底部抽屉。
 *
 * 不用 BottomSheetDialogFragment，因为项目零依赖（不引入 AndroidX / Material）。
 * 但行为必须和原型一致：
 *   - 左右下三边贴边，只圆上方两角
 *   - 顶部有抓手条，按住可下拉关闭
 *   - 下拉超过阈值或快速甩动 => 关闭；否则回弹
 *   - 内容可滚动，但不显示滚动条
 */
class SheetHost(context: Context) : FrameLayout(context) {

    var onDismissed: (() -> Unit)? = null

    private val scrim = View(context)
    private val panel = LinearLayout(context)
    private val scroll: MaxScrollView
    private val grabWrap = FrameLayout(context)
    private val grabBar = View(context)

    private var showing = false
    private var dragStartY = 0f
    private var dragLastY = 0f
    private var dragLastT = 0L
    private var dragVel = 0f
    private var dragging = false

    private var cornerPx = 0
    private var grabberColor = 0xFFE5E5EA.toInt()

    init {
        visibility = View.GONE
        scrim.setBackgroundColor(0x66000000)
        scrim.setOnClickListener { dismiss() }

        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        panel.orientation = LinearLayout.VERTICAL
        val panelLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        panelLp.gravity = Gravity.BOTTOM
        addView(panel, panelLp)

        grabWrap.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (26 * resources.displayMetrics.density).toInt()
        )
        grabBar.layoutParams = FrameLayout.LayoutParams(
            (36 * resources.displayMetrics.density).toInt(),
            (5 * resources.displayMetrics.density).toInt(),
            Gravity.CENTER
        )
        grabWrap.addView(grabBar)

        scroll = MaxScrollView(context)
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFillViewport = false
        panel.addView(grabWrap)
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setupDrag()
    }

    fun applyPalette(p: Palette, cornerDp: Int) {
        val d = resources.displayMetrics.density
        cornerPx = (cornerDp * d).toInt()
        grabberColor = p.sep

        val bg = GradientDrawable()
        bg.setColor(p.card)
        bg.cornerRadii = floatArrayOf(
            cornerPx.toFloat(), cornerPx.toFloat(),
            cornerPx.toFloat(), cornerPx.toFloat(),
            0f, 0f,
            0f, 0f
        )
        panel.background = bg
        grabBar.setBackgroundColor(grabberColor)
    }

    fun show(content: View, p: Palette) {
        applyPalette(p, 22)
        scroll.removeAllViews()
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val screenH = resources.displayMetrics.heightPixels
        scroll.maxHeight = (screenH * 0.86f).toInt()

        visibility = View.VISIBLE
        showing = true
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180L).start()

        panel.translationY = screenH.toFloat()
        panel.animate().translationY(0f).setDuration(220L).start()
    }

    fun dismiss() {
        if (!showing) return
        showing = false
        val screenH = resources.displayMetrics.heightPixels
        scrim.animate().alpha(0f).setDuration(160L).start()
        panel.animate().translationY(screenH.toFloat()).setDuration(200L).withEndAction {
            visibility = View.GONE
            panel.translationY = 0f
            val cb = onDismissed
            onDismissed = null
            if (cb != null) cb()
        }.start()
    }

    fun isShowing(): Boolean = showing

    // ------------------------------------------------------------ 下拉关闭

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setupDrag() {
        grabWrap.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    dragStartY = event.rawY
                    dragLastY = event.rawY
                    dragLastT = System.currentTimeMillis()
                    dragVel = 0f
                    panel.animate().cancel()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener true
                    val dy = (event.rawY - dragStartY).coerceAtLeast(0f)
                    panel.translationY = dy

                    val now = System.currentTimeMillis()
                    val dt = now - dragLastT
                    if (dt > 0L) {
                        dragVel = (event.rawY - dragLastY) / dt
                        dragLastY = event.rawY
                        dragLastT = now
                    }

                    val h = panel.height.toFloat()
                    if (h > 0f) {
                        val a = 1f - (dy / h) * 0.8f
                        scrim.alpha = a.coerceIn(0.2f, 1f)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return@setOnTouchListener true
                    dragging = false
                    val dy = panel.translationY
                    val fast = dragVel > 0.5f
                    if (dy > dp(90) || (fast && dy > dp(24))) {
                        dismiss()
                    } else {
                        panel.animate().translationY(0f).setDuration(180L).start()
                        scrim.animate().alpha(1f).setDuration(180L).start()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** 高度上限受控的 ScrollView；不显示滚动条，但可以滚 */
    private class MaxScrollView(context: Context) : ScrollView(context) {
        var maxHeight: Int = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (maxHeight > 0) {
                val capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, capped)
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }
}
