package com.dathaze.pocketpad.hid

import com.dathaze.pocketpad.ui.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **TV Keys must not have moved.** This is the regression guard for the one thing PC Keys was
 * not allowed to disturb.
 *
 * The mapping itself lives in `MainActivity.navKeyFor`, which is private to an `Activity` and
 * so cannot be called from a JVM test — hence the source check. That is the weaker kind of
 * test and is used here deliberately, the same way `SettingsSheetCompletenessTest` is: what is
 * being guarded is that a specific line of code still says a specific thing. If TV Keys is ever
 * meant to change, these assertions are where someone has to come and say so on purpose.
 *
 * The *behaviour* behind TV Keys — ordering, bounded retry, never leaving a key held — is
 * covered by `KeyReportQueueTest`, which this change left untouched, and that is the stronger
 * half of the guarantee: the class it tests was not edited at all.
 */
class TvKeysUnchangedTest {

    private val activity by lazy { TestSources.read("java/com/dathaze/pocketpad/MainActivity.kt") }
    private val manager by lazy {
        TestSources.read("java/com/dathaze/pocketpad/hid/HidGamepadManager.kt")
    }

    /** The TV mapping, exactly as it has always been. */
    private val tvMapping = listOf(
        "state.isPressed(HidConstants.BTN_CROSS) -> HidConstants.KEY_ENTER",
        "state.isPressed(HidConstants.BTN_CIRCLE) -> HidConstants.KEY_ESCAPE",
        "state.hat == 0 -> HidConstants.KEY_UP",
        "state.hat == 2 -> HidConstants.KEY_RIGHT",
        "state.hat == 4 -> HidConstants.KEY_DOWN",
        "state.hat == 6 -> HidConstants.KEY_LEFT",
    )

    @Test
    fun theTvMappingIsUnchanged() {
        val body = activity.substringAfter("private fun navKeyFor(").substringBefore("\n    }")
        tvMapping.forEach { line ->
            assertTrue("TV Keys no longer maps: $line", body.contains(line))
        }
    }

    @Test
    fun tvKeysStillSendsOneKeyAtATime() {
        // navKeyFor returns a single Byte. If it ever returns a report, TV Keys has changed
        // shape and every host that works today is back in play.
        assertTrue(
            "navKeyFor should still return a single Byte",
            activity.contains("private fun navKeyFor(state: GamepadState): Byte"),
        )
    }

    @Test
    fun tvModeStillUsesTheSingleKeyPath() {
        assertTrue(
            "TV mode must still call sendKey, not the multi-key path",
            activity.contains("KeyboardMode.TV -> hidManager.sendKey(navKeyFor(mergedState))"),
        )
    }

    @Test
    fun pcModeUsesTheOtherPath() {
        assertTrue(
            activity.contains("KeyboardMode.PC -> hidManager.sendKeyReport("),
        )
    }

    @Test
    fun theSingleKeyQueueIsStillWhatTvKeysUses() {
        // KeyReportQueue was left alone on purpose; PC Keys got its own queue instead.
        assertTrue(manager.contains("keyQueue.offer(key)"))
        assertTrue(manager.contains("private val keyQueue = KeyReportQueue()"))
    }

    // ------------------------------------------- the locked Bluetooth identity

    @Test
    fun theKeyboardReportIdIsUnchanged() {
        assertEquals(2, HidConstants.REPORT_ID_KEYBOARD)
    }

    @Test
    fun theGamepadReportIdIsUnchanged() {
        assertEquals(1, HidConstants.REPORT_ID)
    }

    @Test
    fun theReportSizesAreUnchanged() {
        assertEquals(9, HidConstants.GAMEPAD_REPORT_SIZE)
        assertEquals(8, HidConstants.KEYBOARD_REPORT_SIZE)
    }

    @Test
    fun theSdpSubclassIsUnchanged() {
        // Changing this is what would force a re-pair — and what broke the Samsung TV last time.
        assertTrue(
            "the SDP subclass must still be SUBCLASS1_COMBO",
            manager.contains("BluetoothHidDevice.SUBCLASS1_COMBO"),
        )
    }

    @Test
    fun theSdpIdentityStringsAreUnchanged() {
        listOf("\"PocketPad Controller\"", "\"Wireless Controller\"", "\"PocketPad\"").forEach {
            assertTrue("SDP identity changed: $it is gone", manager.contains(it))
        }
    }

    @Test
    fun bothKeyboardPathsUseTheSameReportId() {
        // PC Keys is the same collection on the same id. A new report id would need a descriptor
        // change, which would need a re-pair.
        val sends = Regex("""sendReport\([^)]*REPORT_ID_KEYBOARD""").findAll(manager).count()
        assertEquals("both keyboard paths should send on report id 2", 2, sends)
    }

    @Test
    fun theDescriptorDeclaresSixKeySlotsAlready() {
        // PC Keys uses slots the descriptor always had, which is why it needs no re-pair.
        val descriptor = HidConstants.REPORT_DESCRIPTOR
        assertTrue("the descriptor should be non-empty", descriptor.isNotEmpty())
        // Report Count (6) followed by Report Size (8) in the keyboard collection.
        val bytes = descriptor.toList()
        val pattern = listOf<Byte>(0x95, 0x06, 0x75, 0x08)
        assertTrue(
            "the keyboard collection should still declare six 8-bit key slots",
            (0..bytes.size - pattern.size).any { i -> bytes.subList(i, i + pattern.size) == pattern },
        )
    }
}
