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
        0x29, 0x05,                      //     Usage Maximum (5)
        0x15, 0x00,                      //     Logical Minimum (0)
        0x25, 0x01,                      //     Logical Maximum (1)
        0x95.toByte(), 0x05,             //     Report Count (5)
        0x75, 0x01,                      //     Report Size (1)
        0x81.toByte(), 0x02,             //     Input (Data,Var,Abs)  -> 5 个按键位
        0x95.toByte(), 0x01,             //     Report Count (1)
        0x75, 0x03,                      //     Report Size (3)
        0x81.toByte(), 0x01,             //     Input (Const,Array,Abs) -> 3 位补齐

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
