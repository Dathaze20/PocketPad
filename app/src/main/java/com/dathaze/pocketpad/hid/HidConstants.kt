package com.dathaze.pocketpad.hid

/**
 * HID report descriptor for a standard gamepad:
 *  - 16 buttons (1 bit each)
 *  - 1 hat switch (d-pad, 4 bits + 4 bits padding)
 *  - 4 analog axes: X, Y (left stick) and Z, Rz (right stick), 0..255
 *  - 2 analog triggers: Brake (L2/LT) and Accelerator (R2/RT), 0..255
 *
 * Total input report: 9 bytes (+ report id on the wire).
 * This is a plain, spec-standard descriptor so Tizen TVs (and anything else
 * that accepts Bluetooth HID gamepads) can parse it without a driver.
 */
object HidConstants {

    const val REPORT_ID: Int = 1
    const val REPORT_ID_KEYBOARD: Int = 2

    // USB HID keyboard usage codes, used for TV menu navigation.
    const val KEY_NONE: Byte = 0x00
    const val KEY_ENTER: Byte = 0x28
    const val KEY_ESCAPE: Byte = 0x29
    const val KEY_BACKSPACE: Byte = 0x2A
    const val KEY_RIGHT: Byte = 0x4F
    const val KEY_LEFT: Byte = 0x50
    const val KEY_DOWN: Byte = 0x51
    const val KEY_UP: Byte = 0x52

    // Bit indices into the 16-bit button field. The order is the standard
    // gamepad numbering every host expects — A/B/X/Y, bumpers, triggers,
    // View, Menu, stick clicks, then Guide. Getting this order wrong makes a
    // host read the wrong control entirely.
    const val BTN_CROSS = 0      // button 1  — A / Cross
    const val BTN_CIRCLE = 1     // button 2  — B / Circle
    const val BTN_SQUARE = 2     // button 3  — X / Square
    const val BTN_TRIANGLE = 3   // button 4  — Y / Triangle
    const val BTN_L1 = 4         // button 5  — LB / L1
    const val BTN_R1 = 5         // button 6  — RB / R1
    const val BTN_L2 = 6         // button 7  — LT / L2
    const val BTN_R2 = 7         // button 8  — RT / R2
    const val BTN_SHARE = 8      // button 9  — View / Back / Select
    const val BTN_OPTIONS = 9    // button 10 — Menu / Start
    const val BTN_L3 = 10        // button 11 — left stick click
    const val BTN_R3 = 11        // button 12 — right stick click
    const val BTN_PS = 12        // button 13 — Guide / PS / Xbox

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
        // Analog triggers. A digital button is not enough for anything that
        // reads a throttle — driving games take gas and brake as axes.
        0x05.toByte(), 0x02.toByte(),                 //   Usage Page (Simulation Controls)
        0x09.toByte(), 0xC5.toByte(),                 //   Usage (Brake)
        0x09.toByte(), 0xC4.toByte(),                 //   Usage (Accelerator)
        0x15.toByte(), 0x00.toByte(),                 //   Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x00.toByte(),  //   Logical Maximum (255)
        0x75.toByte(), 0x08.toByte(),                 //   Report Size (8)
        0x95.toByte(), 0x02.toByte(),                 //   Report Count (2)
        0x81.toByte(), 0x02.toByte(),                 //   Input (Data, Variable, Absolute)
        0xC0.toByte(),                                // End Collection

        // ---- Keyboard collection (report id 2) -------------------------
        // Hosts that ignore a bare gamepad still always accept a keyboard,
        // so this both gets PocketPad listed in TV "input device" menus and
        // lets the d-pad drive TV menus as arrow keys.
        0x05.toByte(), 0x01.toByte(),                 // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),                 // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(),                 // Collection (Application)
        0x85.toByte(), REPORT_ID_KEYBOARD.toByte(),   //   Report ID (2)
        0x05.toByte(), 0x07.toByte(),                 //   Usage Page (Keyboard)
        0x19.toByte(), 0xE0.toByte(),                 //   Usage Minimum (Left Control)
        0x29.toByte(), 0xE7.toByte(),                 //   Usage Maximum (Right GUI)
        0x15.toByte(), 0x00.toByte(),                 //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),                 //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),                 //   Report Size (1)
        0x95.toByte(), 0x08.toByte(),                 //   Report Count (8)
        0x81.toByte(), 0x02.toByte(),                 //   Input (modifier bits)
        0x95.toByte(), 0x01.toByte(),                 //   Report Count (1)
        0x75.toByte(), 0x08.toByte(),                 //   Report Size (8)
        0x81.toByte(), 0x03.toByte(),                 //   Input (Constant — reserved)
        0x95.toByte(), 0x06.toByte(),                 //   Report Count (6)
        0x75.toByte(), 0x08.toByte(),                 //   Report Size (8)
        0x15.toByte(), 0x00.toByte(),                 //   Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(),                 //   Logical Maximum (101)
        0x05.toByte(), 0x07.toByte(),                 //   Usage Page (Keyboard)
        0x19.toByte(), 0x00.toByte(),                 //   Usage Minimum (0)
        0x29.toByte(), 0x65.toByte(),                 //   Usage Maximum (101)
        0x81.toByte(), 0x00.toByte(),                 //   Input (Data, Array)
        0xC0.toByte()                                 // End Collection
    )

    /** Build the 8-byte keyboard report for a single (or no) key. */
    fun keyboardReport(key: Byte): ByteArray =
        byteArrayOf(0, 0, key, 0, 0, 0, 0, 0)
}
