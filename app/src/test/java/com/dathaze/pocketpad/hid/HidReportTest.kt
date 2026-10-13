package com.dathaze.pocketpad.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report-level regression tests.
 *
 * These assert the bytes that actually leave the device, and that the HID
 * descriptor advertising them agrees with what is sent. A descriptor that
 * disagrees with its reports makes a host misread every control, so it is
 * checked by walking the real descriptor rather than by restating its values.
 */
class HidReportTest {

    // ---------------------------------------------------------- descriptor

    /** One parsed HID short item. */
    private data class Item(val tag: Int, val data: Int)

    private fun parseDescriptor(): List<Item> {
        val d = HidConstants.REPORT_DESCRIPTOR
        val items = mutableListOf<Item>()
        var i = 0
        while (i < d.size) {
            val prefix = d[i].toInt() and 0xFF
            val length = prefix and 0x03
            var value = 0
            for (k in 0 until length) {
                value = value or ((d[i + 1 + k].toInt() and 0xFF) shl (8 * k))
            }
            items.add(Item(prefix and 0xFC, value))
            i += 1 + length
        }
        return items
    }

    /** Input bits declared per report id, by walking the descriptor. */
    private fun declaredBitsByReportId(): Map<Int, Int> {
        var reportId = 0
        var size = 0
        var count = 0
        val bits = mutableMapOf<Int, Int>()
        for (item in parseDescriptor()) {
            when (item.tag) {
                0x84 -> reportId = item.data          // Report ID
                0x74 -> size = item.data              // Report Size
                0x94 -> count = item.data             // Report Count
                0x80 -> bits[reportId] = (bits[reportId] ?: 0) + size * count  // Input
            }
        }
        return bits
    }

    @Test
    fun descriptorCollectionsAreBalanced() {
        var depth = 0
        for (item in parseDescriptor()) {
            when (item.tag) {
                0xA0 -> depth++   // Collection
                0xC0 -> depth--   // End Collection
            }
            assertTrue("collection depth went negative", depth >= 0)
        }
        assertEquals("unclosed collection in descriptor", 0, depth)
    }

    @Test
    fun descriptorDeclaresExactlyTheTwoReportsWeSend() {
        val bits = declaredBitsByReportId()
        assertEquals(
            "descriptor should declare exactly the gamepad and keyboard reports",
            setOf(HidConstants.REPORT_ID, HidConstants.REPORT_ID_KEYBOARD),
            bits.keys
        )
    }

    @Test
    fun gamepadReportSizeMatchesTheDescriptor() {
        val declared = declaredBitsByReportId()[HidConstants.REPORT_ID]!! / 8
        assertEquals(HidConstants.GAMEPAD_REPORT_SIZE, declared)
        assertEquals(declared, GamepadState().toReport().size)
    }

    @Test
    fun keyboardReportSizeMatchesTheDescriptor() {
        val declared = declaredBitsByReportId()[HidConstants.REPORT_ID_KEYBOARD]!! / 8
        assertEquals(HidConstants.KEYBOARD_REPORT_SIZE, declared)
        assertEquals(declared, HidConstants.keyboardReport(HidConstants.KEY_NONE).size)
    }

    // ------------------------------------------------------ button numbering

    private val allButtons = mapOf(
        "CROSS/A" to HidConstants.BTN_CROSS,
        "CIRCLE/B" to HidConstants.BTN_CIRCLE,
        "SQUARE/X" to HidConstants.BTN_SQUARE,
        "TRIANGLE/Y" to HidConstants.BTN_TRIANGLE,
        "L1/LB" to HidConstants.BTN_L1,
        "R1/RB" to HidConstants.BTN_R1,
        "L2/LT" to HidConstants.BTN_L2,
        "R2/RT" to HidConstants.BTN_R2,
        "SHARE/VIEW" to HidConstants.BTN_SHARE,
        "OPTIONS/MENU" to HidConstants.BTN_OPTIONS,
        "L3/LS" to HidConstants.BTN_L3,
        "R3/RS" to HidConstants.BTN_R3,
        "PS/GUIDE" to HidConstants.BTN_PS
    )

    @Test
    fun buttonIndicesAreUnique() {
        val byIndex = allButtons.entries.groupBy({ it.value }, { it.key })
        val clashes = byIndex.filterValues { it.size > 1 }
        assertTrue("two controls share a button index: $clashes", clashes.isEmpty())
    }

    @Test
    fun buttonIndicesFitTheDeclaredButtonField() {
        // The descriptor declares 16 one-bit buttons.
        allButtons.forEach { (name, index) ->
            assertTrue("$name index $index below 0", index >= 0)
            assertTrue("$name index $index exceeds the 16-button field", index < 16)
        }
    }

    @Test
    fun eachButtonSetsOnlyItsOwnBit() {
        allButtons.forEach { (name, index) ->
            val report = GamepadState().apply { setButton(index, true) }.toReport()
            val field = (report[0].toInt() and 0xFF) or ((report[1].toInt() and 0xFF) shl 8)
            assertEquals("$name should set exactly one bit", 1 shl index, field)
        }
    }

    // ----------------------------------------------------- neutral reports

    @Test
    fun neutralGamepadReportIsCentredAndUnpressed() {
        val r = HidConstants.neutralGamepadReport()
        assertEquals(HidConstants.GAMEPAD_REPORT_SIZE, r.size)
        assertEquals("no buttons", 0, r[0].toInt() and 0xFF)
        assertEquals("no buttons", 0, r[1].toInt() and 0xFF)
        assertEquals("hat neutral", HidConstants.HAT_NEUTRAL, r[2].toInt() and 0x0F)
        for (i in 3..6) assertEquals("axis $i centred", 128, r[i].toInt() and 0xFF)
        assertEquals("left trigger released", 0, r[7].toInt() and 0xFF)
        assertEquals("right trigger released", 0, r[8].toInt() and 0xFF)
    }

    @Test
    fun neutralKeyboardReportHoldsNothing() {
        val r = HidConstants.neutralKeyboardReport()
        assertEquals(HidConstants.KEYBOARD_REPORT_SIZE, r.size)
        assertArrayEquals(ByteArray(HidConstants.KEYBOARD_REPORT_SIZE), r)
    }

    // ------------------------------------------- Get_Report id dispatch

    @Test
    fun gamepadIdReturnsTheGamepadReport() {
        val r = HidConstants.neutralReportFor(HidConstants.REPORT_ID)!!
        assertEquals(HidConstants.GAMEPAD_REPORT_SIZE, r.size)
        assertArrayEquals(HidConstants.neutralGamepadReport(), r)
    }

    @Test
    fun keyboardIdReturnsTheKeyboardReport() {
        val r = HidConstants.neutralReportFor(HidConstants.REPORT_ID_KEYBOARD)!!
        assertEquals(HidConstants.KEYBOARD_REPORT_SIZE, r.size)
        assertArrayEquals(HidConstants.neutralKeyboardReport(), r)
    }

    @Test
    fun unknownReportIdsAreRefusedRatherThanGuessed() {
        for (id in listOf(3, 4, 15, 99, 255)) {
            assertNull("report id $id should be unsupported", HidConstants.neutralReportFor(id))
        }
    }

    @Test
    fun unspecifiedReportIdFallsBackToTheGamepad() {
        // Report id 0 means the host named no report. Answering with the
        // gamepad collection keeps hosts that already work working.
        assertArrayEquals(
            HidConstants.neutralGamepadReport(),
            HidConstants.neutralReportFor(0)
        )
    }

    @Test
    fun theTwoReportsCanNeverBeConfused() {
        val pad = HidConstants.neutralReportFor(HidConstants.REPORT_ID)!!
        val key = HidConstants.neutralReportFor(HidConstants.REPORT_ID_KEYBOARD)!!
        assertNotEquals("the two reports must differ in length", pad.size, key.size)
        assertTrue("reports must not be byte-identical", !pad.contentEquals(key))
    }

    /**
     * The defect this fix exists for. A gamepad report served for a keyboard
     * request is parsed as modifier, reserved, then keycodes — and the neutral
     * hat byte is 8, which is keyboard usage 0x08, the letter "e". The keyboard
     * reply must therefore carry no keycode in any slot.
     */
    @Test
    fun keyboardReplyCannotInjectAPhantomKeypress() {
        val padAsKeyboard = HidConstants.neutralGamepadReport()
        assertEquals(
            "precondition: the gamepad hat byte really is keyboard usage 0x08",
            0x08, padAsKeyboard[2].toInt() and 0xFF
        )

        val key = HidConstants.neutralReportFor(HidConstants.REPORT_ID_KEYBOARD)!!
        assertEquals("modifier byte must be clear", 0, key[0].toInt() and 0xFF)
        assertEquals("reserved byte must be clear", 0, key[1].toInt() and 0xFF)
        for (slot in 2 until key.size) {
            assertEquals("key slot $slot must be empty", 0, key[slot].toInt() and 0xFF)
        }
    }

    @Test
    fun keyboardReportPlacesTheKeyInTheFirstSlot() {
        val r = HidConstants.keyboardReport(HidConstants.KEY_ENTER)
        assertEquals(0, r[0].toInt())                       // modifiers
        assertEquals(0, r[1].toInt())                       // reserved
        assertEquals(HidConstants.KEY_ENTER, r[2])          // first key slot
        for (slot in 3 until r.size) assertEquals(0, r[slot].toInt())
    }

}
