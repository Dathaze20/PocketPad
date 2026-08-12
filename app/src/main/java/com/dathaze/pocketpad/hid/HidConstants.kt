package com.dathaze.pocketpad.hid

/**
 * HID report descriptor for a standard gamepad:
 *  - 16 buttons (1 bit each)
 *  - 1 hat switch (d-pad, 4 bits + 4 bits padding)
 *  - 4 analog axes: X, Y (left stick) and Z, Rz (right stick), 0..255
 *
 * Total input report: 7 bytes (+ report id on the wire).
 * This is a plain, spec-standard descriptor so Tizen TVs (and anything else
 * that accepts Bluetooth HID gamepads) can parse it without a driver.
 */
object HidConstants {

    const val REPORT_ID: Int = 1

    // Bit indices into the 16-bit button field (PS-style layout).
    const val BTN_CROSS = 0
    const val BTN_CIRCLE = 1
    const val BTN_SQUARE = 2
    const val BTN_TRIANGLE = 3
    const val BTN_L1 = 4
    const val BTN_R1 = 5
    const val BTN_L2 = 6
    const val BTN_R2 = 7
    const val BTN_SHARE = 8
    const val BTN_OPTIONS = 9
    const val BTN_PS = 10
    const val BTN_L3 = 11
    const val BTN_R3 = 12

    // Hat switch values (0 = up, clockwise), 8 = released/neutral.
    const val HAT_NEUTRAL = 8

    val REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),                 // Usage Page (Generic Desktop)
        0x09.toByte(), 0x05.toByte(),                 // Usage (Game Pad)
        0xA1.toByte(), 0x01.toByte(),                 // Collection (Application)
        0x85.toByte(), REPORT_ID.toByte(),            //   Report ID (1)
        // 16 buttons
        0x05.toByte(), 0x09.toByte(),                 //   Usage Page (Button)
        0x19.toByte(), 0x01.toByte(),                 //   Usage Minimum (Button 1)
        0x29.toByte(), 0x10.toByte(),                 //   Usage Maximum (Button 16)
        0x15.toByte(), 0x00.toByte(),                 //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),                 //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),                 //   Report Size (1)
        0x95.toByte(), 0x10.toByte(),                 //   Report Count (16)
        0x81.toByte(), 0x02.toByte(),                 //   Input (Data, Variable, Absolute)
        // Hat switch (d-pad)
        0x05.toByte(), 0x01.toByte(),                 //   Usage Page (Generic Desktop)
        0x09.toByte(), 0x39.toByte(),                 //   Usage (Hat switch)
        0x15.toByte(), 0x00.toByte(),                 //   Logical Minimum (0)
        0x25.toByte(), 0x07.toByte(),                 //   Logical Maximum (7)
        0x35.toByte(), 0x00.toByte(),                 //   Physical Minimum (0)
        0x46.toByte(), 0x3B.toByte(), 0x01.toByte(),  //   Physical Maximum (315)
        0x65.toByte(), 0x14.toByte(),                 //   Unit (Degrees)
        0x75.toByte(), 0x04.toByte(),                 //   Report Size (4)
        0x95.toByte(), 0x01.toByte(),                 //   Report Count (1)
        0x81.toByte(), 0x42.toByte(),                 //   Input (Data, Variable, Absolute, Null State)
        0x75.toByte(), 0x04.toByte(),                 //   Report Size (4) — padding
        0x95.toByte(), 0x01.toByte(),                 //   Report Count (1)
        0x81.toByte(), 0x03.toByte(),                 //   Input (Constant)
        // Axes: X, Y, Z, Rz
        0x05.toByte(), 0x01.toByte(),                 //   Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),                 //   Usage (X)
        0x09.toByte(), 0x31.toByte(),                 //   Usage (Y)
        0x09.toByte(), 0x32.toByte(),                 //   Usage (Z)
        0x09.toByte(), 0x35.toByte(),                 //   Usage (Rz)
        0x15.toByte(), 0x00.toByte(),                 //   Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x00.toByte(),  //   Logical Maximum (255)
        0x75.toByte(), 0x08.toByte(),                 //   Report Size (8)
        0x95.toByte(), 0x04.toByte(),                 //   Report Count (4)
        0x81.toByte(), 0x02.toByte(),                 //   Input (Data, Variable, Absolute)
        0xC0.toByte()                                 // End Collection
    )
}
