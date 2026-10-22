package com.airlur.airmouse

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 内存日志环形缓冲。
 *
 * 为什么需要它：蓝牙 HID 的失败点全在系统回调里，界面上只能看到「未注册」三个字，
 * 完全无法判断是权限、注册、还是厂商蓝牙栈的问题。把关键路径记下来，
 * 用户复制粘贴出来就能定位。
 *
 * 只保留最近 800 条，不落盘、不上传。
 */
object AppLog {

    private const val MAX_LINES = 800
    private const val TAG = "AirMouse"

    private val buffer = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(tag: String, message: String) {
        val line = timeFormat.format(Date()) + "  [" + tag + "]  " + message
        if (buffer.size >= MAX_LINES) {
            buffer.removeFirst()
        }
        buffer.addLast(line)
        try {
            Log.d(TAG, line)
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun dump(): String {
        if (buffer.isEmpty()) return "(暂无日志)"
        val sb = StringBuilder()
        for (line in buffer) {
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        add("AppLog", "日志已清空")
    }

    @Synchronized
    fun size(): Int = buffer.size
}
