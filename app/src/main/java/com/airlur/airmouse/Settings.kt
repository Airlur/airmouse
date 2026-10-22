package com.airlur.airmouse

import android.content.Context

/**
 * 全部可调参数集中在这里，用 SharedPreferences 持久化。
 *
 * 设计原则：界面上能调的，就不要写死在代码里。改一个毫秒数不需要重新打包。
 */
class Settings(context: Context) {

    companion object {
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_SYSTEM = 2

        const val DEF_THEME = THEME_SYSTEM
        const val DEF_AIR_SENS = 40        // 0..100
        const val DEF_PAD_SENS = 45        // 0..100
        const val DEF_DBL_MS = 260
        const val DEF_LONG_MS = 520
        const val DEF_LATCH_MS = 400
        const val DEF_TAP_MS = 300
        const val DEF_WHEEL_PX = 24

        /** 触控板指针倍率上限，防止高灵敏度下指针甩飞 */
        const val PAD_GAIN_MAX = 2.0f


        private const val KEY_THEME = "theme"
        private const val KEY_AIR_SENS = "air_sens"
        private const val KEY_PAD_SENS = "pad_sens"
        private const val KEY_INV_X = "inv_x"
        private const val KEY_INV_Y = "inv_y"
        private const val KEY_DBL_DRAG = "dbl_drag"
        private const val KEY_LATCH = "latch"
        private const val KEY_DBL_MS = "dbl_ms"
        private const val KEY_LONG_MS = "long_ms"
        private const val KEY_LATCH_MS = "latch_ms"
        private const val KEY_TAP_MS = "tap_ms"
        private const val KEY_WHEEL_PX = "wheel_px"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_MID = "mid_click"
        private const val KEY_DIAG = "show_diag"
        private const val KEY_WHEEL_INV = "wheel_invert"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
    }

    private val sp = context.getSharedPreferences("airmouse_prefs", Context.MODE_PRIVATE)

    var theme: Int
        get() = sp.getInt(KEY_THEME, DEF_THEME)
        set(v) = sp.edit().putInt(KEY_THEME, v).apply()

    var airSens: Int
        get() = sp.getInt(KEY_AIR_SENS, DEF_AIR_SENS)
        set(v) = sp.edit().putInt(KEY_AIR_SENS, v).apply()

    var padSens: Int
        get() = sp.getInt(KEY_PAD_SENS, DEF_PAD_SENS)
        set(v) = sp.edit().putInt(KEY_PAD_SENS, v).apply()

    var invertX: Boolean
        get() = sp.getBoolean(KEY_INV_X, false)
        set(v) = sp.edit().putBoolean(KEY_INV_X, v).apply()

    var invertY: Boolean
        get() = sp.getBoolean(KEY_INV_Y, false)
        set(v) = sp.edit().putBoolean(KEY_INV_Y, v).apply()

    var doubleTapDrag: Boolean
        get() = sp.getBoolean(KEY_DBL_DRAG, true)
        set(v) = sp.edit().putBoolean(KEY_DBL_DRAG, v).apply()

    var latchEnabled: Boolean
        get() = sp.getBoolean(KEY_LATCH, true)
        set(v) = sp.edit().putBoolean(KEY_LATCH, v).apply()

    var dblWindowMs: Int
        get() = sp.getInt(KEY_DBL_MS, DEF_DBL_MS)
        set(v) = sp.edit().putInt(KEY_DBL_MS, v).apply()

    var longPressMs: Int
        get() = sp.getInt(KEY_LONG_MS, DEF_LONG_MS)
        set(v) = sp.edit().putInt(KEY_LONG_MS, v).apply()

    var latchMs: Int
        get() = sp.getInt(KEY_LATCH_MS, DEF_LATCH_MS)
        set(v) = sp.edit().putInt(KEY_LATCH_MS, v).apply()

    var tapMaxMs: Int
        get() = sp.getInt(KEY_TAP_MS, DEF_TAP_MS)
        set(v) = sp.edit().putInt(KEY_TAP_MS, v).apply()

    var wheelStepPx: Int
        get() = sp.getInt(KEY_WHEEL_PX, DEF_WHEEL_PX)
        set(v) = sp.edit().putInt(KEY_WHEEL_PX, v).apply()

    var haptics: Boolean
        get() = sp.getBoolean(KEY_HAPTIC, true)
        set(v) = sp.edit().putBoolean(KEY_HAPTIC, v).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean(KEY_KEEP_AWAKE, true)
        set(v) = sp.edit().putBoolean(KEY_KEEP_AWAKE, v).apply()

    var midClick: Boolean
        get() = sp.getBoolean(KEY_MID, true)
        set(v) = sp.edit().putBoolean(KEY_MID, v).apply()

    var showDiag: Boolean
        get() = sp.getBoolean(KEY_DIAG, false)
        set(v) = sp.edit().putBoolean(KEY_DIAG, v).apply()

    /** 滚轮方向反转（默认自然滚动：手指上滑 = 内容向上滚） */
    var wheelInvert: Boolean
        get() = sp.getBoolean(KEY_WHEEL_INV, false)
        set(v) = sp.edit().putBoolean(KEY_WHEEL_INV, v).apply()

    /** 断线后是否自动重连 */
    var autoReconnect: Boolean
        get() = sp.getBoolean(KEY_AUTO_RECONNECT, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_RECONNECT, v).apply()


    /** 空鼠灵敏度：0..100 -> 300..3000 */
    fun airSensitivity(): Double = 300.0 + airSens * 27.0

    /** 触控板指针倍率：0..100 -> 0.5..2.0（封顶，避免指针甩飞） */
    fun padGain(): Float {
        val g = 0.5f + padSens * 0.015f
        return if (g > PAD_GAIN_MAX) PAD_GAIN_MAX else g
    }

    fun resetAll() {
        sp.edit().clear().apply()
    }
}
