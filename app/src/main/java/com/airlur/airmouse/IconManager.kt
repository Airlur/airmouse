package com.airlur.airmouse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 桌面图标切换（activity-alias 方案）。
 *
 * ---------------------------------------------------------------------------
 * 这个功能踩过两次坑，改之前务必先读完：
 *
 * 坑 1（崩溃）：禁用「当前正在运行的那个 alias」之后，系统会销毁当前 Activity。
 *   如果这之后还执行 toastTop()、rebuildUi() 之类的 UI 代码，就会崩溃
 *   （表现为「AirMouse 屡次停止运行」）。
 *   -> 所以 apply() 之后必须立刻重启，不能再碰界面。
 *
 * 坑 2（图标越换越多）：如果把禁用推迟到 onStop 之类的时机，而那个时机
 *   不一定触发，就会变成「每点一次只多启用一个 alias，旧的永远不清理」。
 *   -> 所以禁用必须是同步立即执行，不能依赖任何回调。
 *
 * 正确顺序：enable 目标 -> 立即 disable 其余 -> 主动重启进入新入口。
 * ---------------------------------------------------------------------------
 */
object IconManager {

    const val ICON_G = 0
    const val ICON_I = 1
    const val ICON_K = 2
    const val ICON_G2 = 3
    const val ICON_I2 = 4
    const val ICON_K2 = 5

    const val COUNT = 6

    /** alias 类名后缀，必须与 AndroidManifest 中声明的一致 */
    private val SUFFIX = arrayOf("IconG", "IconI", "IconK", "IconG2", "IconI2", "IconK2")

    val LABELS = arrayOf(
        "G . 白底蓝环",
        "I . 蓝底白环",
        "K . 黑底蓝环",
        "G2 . 白底全环",
        "I2 . 蓝底全环",
        "K2 . 黑底全环"
    )

    /** 界面上显示的外观描述 */
    private val SHORT = arrayOf("白底蓝环", "蓝底白环", "黑底蓝环", "白底全环", "蓝底全环", "黑底全环")

    fun shortName(index: Int): String = SHORT[index.coerceIn(0, COUNT - 1)]

    fun nameOf(index: Int): String = LABELS[index.coerceIn(0, COUNT - 1)]

    private fun component(context: Context, index: Int): ComponentName =
        ComponentName(
            context.packageName,
            context.packageName + "." + SUFFIX[index.coerceIn(0, COUNT - 1)]
        )

    /**
     * 启用目标并立即禁用其余。
     *
     * 返回 true 表示调用没抛异常；调用方随后必须立刻重启，
     * 因为当前 Activity 可能已经处于被销毁的状态。
     */
    fun apply(context: Context, index: Int): Boolean {
        val target = index.coerceIn(0, COUNT - 1)
        val pm = context.packageManager
        return try {
            // 先启用目标，保证任何瞬间都至少有一个入口存在
            pm.setComponentEnabledSetting(
                component(context, target),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            // 再同步禁用其余，绝不留到回调里做
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
            AppLog.add("ICON", "切换图标失败: " + t)
            false
        }
    }

    /**
     * 以指定 alias 作为入口重新拉起 App。
     *
     * 用显式 ComponentName 而不是 getLaunchIntentForPackage：
     * 后者在「多个 alias 同时启用」的瞬间可能返回旧的那个。
     */
    fun restartInto(context: Context, index: Int) {
        val target = index.coerceIn(0, COUNT - 1)
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = component(context, target)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        try {
            context.startActivity(intent)
            AppLog.add("ICON", "已请求以 " + SUFFIX[target] + " 重启")
        } catch (t: Throwable) {
            AppLog.add("ICON", "重启失败: " + t)
        }
    }

    /** 当前启用的 alias 下标；没有显式启用任何一个时返回默认值 */
    fun currentIndex(context: Context): Int {
        val pm = context.packageManager
        for (i in 0 until COUNT) {
            val state = try {
                pm.getComponentEnabledSetting(component(context, i))
            } catch (t: Throwable) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i
        }
        return Settings.DEF_ICON
    }
}