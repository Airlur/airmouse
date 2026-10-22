package com.airlur.airmouse

/**
 * 标准「相对位移鼠标」HID 报告描述符。
 *
 * 报告格式（4 字节）：
 *   byte0 = 按键位图  bit0 左键 / bit1 右键 / bit2 中键，高 5 位补齐
 *   byte1 = X 相对位移（-127..127）
 *   byte2 = Y 相对位移（-127..127，向下为正）
 *   byte3 = 滚轮（-127..127，向上为正）
 *
 * 用相对位移而不是绝对坐标，是因为 HID 鼠标本来就是相对设备，
 * 主机侧完全不需要知道「指针现在在哪」。
 *
 * ---------------------------------------------------------------------------
 * 2026-10 修改说明（触控板移动无效）：
 *
 * 之前是「5 键 + 3 位填充」，且填充项写的是 0x81 0x01（Const, Array）。
 * Array 与 Constant 是互斥语义，严格的 HID 解析器会在这里出错，
 * 导致后面的 X/Y/Wheel 定义没被正确加载 —— 表现就是按键有效、移动无效。
 *
 * 现在改成微软标准鼠标描述符的写法：「3 键 + 5 位填充」，
 * 填充项用 0x81 0x03（Const, Variable）。报告长度不变，仍是 4 字节。
 * 只用左/右/中三个键，功能不受影响。
 * ---------------------------------------------------------------------------
 */
object HidDescriptors {

    val MOUSE: ByteArray = byteArrayOf(
        0x05, 0x01,                      // Usage Page (Generic Desktop)
        0x09, 0x02,                      // Usage (Mouse)
        0xA1.toByte(), 0x01,             // Collection (Application)
        0x09, 0x01,                      //   Usage (Pointer)
        0xA1.toByte(), 0x00,             //   Collection (Physical)

        0x05, 0x09,                      //     Usage Page (Button)
        0x19, 0x01,                      //     Usage Minimum (1)
        0x29, 0x03,                      //     Usage Maximum (3)   -> 3 个按键
        0x15, 0x00,                      //     Logical Minimum (0)
        0x25, 0x01,                      //     Logical Maximum (1)
        0x95.toByte(), 0x03,             //     Report Count (3)
        0x75, 0x01,                      //     Report Size (1)
        0x81.toByte(), 0x02,             //     Input (Data,Var,Abs)  -> 3 个按键位
        0x95.toByte(), 0x01,             //     Report Count (1)
        0x75, 0x05,                      //     Report Size (5)
        0x81.toByte(), 0x03,             //     Input (Const,Var,Abs) -> 5 位补齐

        0x05, 0x01,                      //     Usage Page (Generic Desktop)
        0x09, 0x30,                      //     Usage (X)
        0x09, 0x31,                      //     Usage (Y)
        0x09, 0x38,                      //     Usage (Wheel)
        0x15, 0x81.toByte(),             //     Logical Minimum (-127)
        0x25, 0x7F,                      //     Logical Maximum (127)
        0x75, 0x08,                      //     Report Size (8)
        0x95.toByte(), 0x03,             //     Report Count (3)
        0x81.toByte(), 0x06,             //     Input (Data,Var,Rel)

        0xC0.toByte(),                   //   End Collection
        0xC0.toByte()                    // End Collection
    )
}
