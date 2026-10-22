package com.airlur.airmouse

/**
 * 浅色 / 深色两套配色。
 *
 * 注意：这些颜色必须和 prototype/index.html 里的 CSS 变量一一对应，
 * 否则原型和 App 会长得不一样。
 */
class Palette(
    val isDark: Boolean,
    val bg: Int,
    val card: Int,
    val label: Int,
    val sec: Int,
    val sep: Int,
    val deck: Int,
    val keyBg: Int,
    val sub: Int,
    val track: Int,
    val fillSeg: Int,
    val fillBtn: Int,
    val grp: Int,
    val accent: Int,
    val green: Int,
    val orange: Int,
    val red: Int
) {
    companion object {
        fun light(): Palette = Palette(
            isDark = false,
            bg = 0xFFF2F2F7.toInt(),
            card = 0xFFFFFFFF.toInt(),
            label = 0xFF000000.toInt(),
            sec = 0xFF8E8E93.toInt(),
            sep = 0xFFE5E5EA.toInt(),
            deck = 0xFFE8E8EE.toInt(),
            keyBg = 0xFFFFFFFF.toInt(),
            sub = 0xFFA8A8B0.toInt(),
            track = 0xFFE5E5EA.toInt(),
            fillSeg = 0x1F767680,
            fillBtn = 0xFFE5E5EA.toInt(),
            grp = 0xFFF2F2F7.toInt(),
            accent = 0xFF007AFF.toInt(),
            green = 0xFF34C759.toInt(),
            orange = 0xFFFF9500.toInt(),
            red = 0xFFFF3B30.toInt()
        )

        fun dark(): Palette = Palette(
            isDark = true,
            bg = 0xFF000000.toInt(),
            card = 0xFF1C1C1E.toInt(),
            label = 0xFFFFFFFF.toInt(),
            sec = 0xFF98989D.toInt(),
            sep = 0xFF38383A.toInt(),
            deck = 0xFF2C2C2E.toInt(),
            keyBg = 0xFF2C2C2E.toInt(),
            sub = 0xFF8E8E93.toInt(),
            track = 0xFF39393D.toInt(),
            fillSeg = 0x52767680,
            fillBtn = 0xFF2C2C2E.toInt(),
            grp = 0xFF2C2C2E.toInt(),
            accent = 0xFF0A84FF.toInt(),
            green = 0xFF30D158.toInt(),
            orange = 0xFFFF9F0A.toInt(),
            red = 0xFFFF453A.toInt()
        )

        fun of(dark: Boolean): Palette = if (dark) dark() else light()
    }
}
