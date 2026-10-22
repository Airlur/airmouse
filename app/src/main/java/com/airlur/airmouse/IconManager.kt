package com.airlur.airmouse

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标动态切换。
 *
 * 原理：Manifest 里为每个图标方案声明一个 activity-alias，
 * 同一时刻只启用其中一个，其它禁用。系统桌面读到的就是被启用那个的 icon。
 *
 * 注意事项（这些是真实限制，不是没实现）：
 *   1. 切换后桌面图标可能不会立刻刷新，部分启动器要等几秒，或需要回到桌面一次。
 *   2. 少数启动器会把被禁用的 alias 对应的快捷方式一起移除，切换后可能需要重新把图标拖到桌面。
 *   3. 不能用 PackageManager.COMPONENT_ENABLED_STATE_DISABLED 立刻禁用「当前正在运行」的
 *      那个组件并杀掉进程，所以这里统一用 DONT_KILL_APP。
 */
object IconManager {

    const val ICON_G = 0
    const val ICON_I = 1
    const val ICON_K = 2
    const val ICON_G2 = 3
    const val ICON_I2 = 4
    const val ICON_K2 = 5

    const val COUNT = 6

    private val ALIASES = arrayOf(
        ".IconG",
        ".IconI",
        ".IconK",
        ".IconG2",
        ".IconI2",
        ".IconK2"
    )

    val LABELS = arrayOf(
        "G · 白底蓝环",
        "I · 蓝底白环",
        "K · 黑底蓝环",
        "G2 · 白底全环",
        "I2 · 蓝底全环",
        "K2 · 黑底全环"
    )

    /** 界面上直接显示外观描述，不用代号 */
    private val SHORT = arrayOf("白底蓝环", "蓝底白环", "黑底蓝环", "白底全环", "蓝底全环", "黑底全环")

    fun shortName(index: Int): String = SHORT[index.coerceIn(0, COUNT - 1)]

    fun nameOf(index: Int): String = LABELS[index.coerceIn(0, COUNT - 1)]

    private fun component(context: Context, index: Int): ComponentName =
        ComponentName(context.packageName, context.packageName + ALIASES[index])

    /**
     * 立刻传 true 会让图标闪烁，这里返回当前启用的是哪几个。
     * 正常情况应当只有一个。
     */
    fun currentIndex(context: Context): Int {
        val pm = context.packageManager
        for (i in 0 until COUNT) {
            val state = try {
                pm.getComponentEnabledSetting(component(context, i))
            } catch (e: Throwable) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i
        }
        // 没有显式启用任何一个 -> 用默认声明的那个
        return Settings.DEF_ICON
    }

    /**
     * 只启用目标 alias，不动其它。
     *
     * 刻意不在同一时刻禁用其它图标：用户当前这个界面就是从「正在启用的那个 alias」
     * 启动的，一旦把它禁用，系统会认为任务入口没了而杀掉进程 —— 表现就是
     * 「AirMouse 屡次停止运行」。所以禁用动作推迟到 Activity 不可见之后。
     */
    fun enable(context: Context, index: Int): Boolean {
        val target = index.coerceIn(0, COUNT - 1)
        return try {
            context.packageManager.setComponentEnabledSetting(
                component(context, target),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 禁用除 keep 之外的所有 alias。应在 Activity 已经不可见时调用。 */
    fun disableOthers(context: Context, keep: Int): Boolean {
        val target = keep.coerceIn(0, COUNT - 1)
        val pm = context.packageManager
        return try {
            for (i in 0 until COUNT) {
                if (i == target) continue
                pm.setComponentEnabledSetting(
                    component(context, i),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 一步到位地切换（启用目标 + 禁用其它）。
     *
     * 仅用于「启动时纠正状态」这种不在前台交互的场景；
     * 用户主动切换图标请走 [enable]，然后在 onStop 里调 [disableOthers]。
     */
    fun apply(context: Context, index: Int): Boolean {
        val target = index.coerceIn(0, COUNT - 1)
        val pm = context.packageManager
        return try {
            pm.setComponentEnabledSetting(
                component(context, target),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            for (i in 0 until COUNT) {
                if (i == target) continue
                pm.setComponentEnabledSetting(
                    component(context, i),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            true
        } catch (e: Throwable) {
            false
        }
    }
}
