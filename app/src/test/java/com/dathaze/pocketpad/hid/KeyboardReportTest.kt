package com.dathaze.pocketpad.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 8-byte keyboard report: six slots, a modifier byte, and the rules for filling them.
 *
 * The report *structure* is fixed by [HidConstants.REPORT_DESCRIPTOR] and is not being changed —
 * these tests are as much a statement of that as they are a check of [KeyboardReport].
 */
class KeyboardReportTest {

    @Test
    fun theReportIsTheSizeTheDescriptorDeclares() {
        assertEquals(8, HidConstants.KEYBOARD_REPORT_SIZE)
        assertEquals(
            HidConstants.KEYBOARD_REPORT_SIZE,
            KeyboardReport.build(0, emptyList()).size,
        )
    }

    @Test
    fun sixSlotsIsTheLimitTheDescriptorAllows() {
        // Report Count (6) with Report Size (8), after the modifier and reserved bytes.
        assertEquals(6, KeyboardReport.MAX_KEYS)
        assertEquals(
            HidConstants.KEYBOARD_REPORT_SIZE,
            KeyboardReport.FIRST_KEY_INDEX + KeyboardReport.MAX_KEYS,
        )
    }

    @Test
    fun anEmptyReportIsAllZeroAndMatchesTheExistingNeutral() {
        val built = KeyboardReport.build(0, emptyList())
        assertTrue(KeyboardReport.isNeutral(built))
        // The same bytes the single-key path has always sent for "nothing held".
        assertArrayEquals(HidConstants.neutralKeyboardReport(), built)
    }

    @Test
    fun keysLandInSlotsInTheOrderGiven() {
        val report = KeyboardReport.build(0, listOf(PcKeyMap.KEY_Z, PcKeyMap.KEY_A))
        assertEquals(PcKeyMap.KEY_Z, report[KeyboardReport.FIRST_KEY_INDEX])
        assertEquals(PcKeyMap.KEY_A, report[KeyboardReport.FIRST_KEY_INDEX + 1])
        assertEquals(0, report[KeyboardReport.FIRST_KEY_INDEX + 2].toInt())
    }

    @Test
    fun theReservedByteStaysZero() {
        val report = KeyboardReport.build(
            KeyboardReport.MOD_RIGHT_SHIFT,
            listOf(PcKeyMap.KEY_Z),
        )
        assertEquals(0, report[KeyboardReport.RESERVED_INDEX].toInt())
    }

    @Test
    fun modifiersLiveInByteZero() {
        val report = KeyboardReport.build(KeyboardReport.MOD_RIGHT_SHIFT, emptyList())
        assertEquals(
            KeyboardReport.MOD_RIGHT_SHIFT.toByte(),
            report[KeyboardReport.MODIFIER_INDEX],
        )
        assertEquals(KeyboardReport.MOD_RIGHT_SHIFT, KeyboardReport.modifiersOf(report))
    }

    @Test
    fun aModifierAloneIsNotNeutral() {
        // Right Shift held with no key is still something the host must be told about — and,
        // more importantly, something that must later be released.
        val report = KeyboardReport.build(KeyboardReport.MOD_RIGHT_SHIFT, emptyList())
        assertFalse(KeyboardReport.isNeutral(report))
    }

    @Test
    fun theRightShiftBitIsTheOneTheDescriptorDeclares() {
        // Modifier bits run Left Ctrl (0xE0) .. Right GUI (0xE7), one bit each from the low end.
        // Right Shift is 0xE5, the sixth, so bit 5.
        assertEquals(1 shl 5, KeyboardReport.MOD_RIGHT_SHIFT)
    }

    @Test
    fun sixKeysFillTheReport() {
        val six = listOf<Byte>(0x04, 0x05, 0x06, 0x07, 0x08, 0x09)
        val report = KeyboardReport.build(0, six)
        assertEquals(six, KeyboardReport.keysOf(report))
    }

    @Test
    fun aSeventhKeyIsDroppedRatherThanBlankingTheReport() {
        // The spec's answer is ErrorRollOver in every slot, which loses *all* input. For a game
        // pad that is the wrong trade: see the rollover note in KeyboardReport.
        val seven = listOf<Byte>(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A)
        val report = KeyboardReport.build(0, seven)
        assertEquals(seven.take(6), KeyboardReport.keysOf(report))
    }

    @Test
    fun repeatedKeysDoNotTakeTwoSlots() {
        val report = KeyboardReport.build(0, listOf(PcKeyMap.KEY_Z, PcKeyMap.KEY_Z))
        assertEquals(listOf(PcKeyMap.KEY_Z), KeyboardReport.keysOf(report))
    }

    @Test
    fun emptySlotsAreSkipped() {
        val report = KeyboardReport.build(0, listOf(HidConstants.KEY_NONE, PcKeyMap.KEY_Z))
        assertEquals(PcKeyMap.KEY_Z, report[KeyboardReport.FIRST_KEY_INDEX])
    }

    @Test
    fun aUsageAboveTheDeclaredMaximumIsRefusedASlot() {
        // 0xE5 is Right Shift. The slots declare Usage Maximum 101 (0x65), so a host is entitled
        // to misread anything larger — it belongs in the modifier byte and nowhere else.
        val report = KeyboardReport.build(0, listOf(0xE5.toByte(), PcKeyMap.KEY_Z))
        assertEquals(listOf(PcKeyMap.KEY_Z), KeyboardReport.keysOf(report))
    }

    @Test
    fun keysOfIgnoresTheModifierByte() {
        val report = KeyboardReport.build(KeyboardReport.MOD_RIGHT_SHIFT, listOf(PcKeyMap.KEY_Z))
        assertEquals(listOf(PcKeyMap.KEY_Z), KeyboardReport.keysOf(report))
    }
}
