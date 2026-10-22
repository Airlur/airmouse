package com.airlur.airmouse

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro Filter（Casiez 2012）。
 *
 * 它的价值在于「截止频率随速度自适应」：
 *   - 手停住 / 慢慢转 -> 截止频率低 -> 强滤波 -> 指针不抖
 *   - 快速甩动         -> 截止频率高 -> 弱滤波 -> 不增加延迟
 *
 * 空鼠手感的一半来自这个滤波器。换成固定低通，你要么抖要么飘。
 */
class OneEuroFilter(
    private val minCutoff: Double = 1.2,
    private val beta: Double = 0.02,
    private val dCutoff: Double = 1.0
) {
    private var xPrev = 0.0
    private var dxPrev = 0.0
    private var tPrev = -1.0

    fun reset() {
        tPrev = -1.0
        xPrev = 0.0
        dxPrev = 0.0
    }

    fun filter(x: Double, timestampSec: Double): Double {
        if (tPrev < 0.0) {
            tPrev = timestampSec
            xPrev = x
            dxPrev = 0.0
            return x
        }
        var dt = timestampSec - tPrev
        if (dt <= 0.0) dt = 1e-3
        tPrev = timestampSec

        val dAlpha = alpha(dCutoff, dt)
        val dx = (x - xPrev) / dt
        val edx = dAlpha * dx + (1.0 - dAlpha) * dxPrev
        dxPrev = edx

        val cutoff = minCutoff + beta * abs(edx)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1.0 - a) * xPrev
        xPrev = xHat
        return xHat
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
