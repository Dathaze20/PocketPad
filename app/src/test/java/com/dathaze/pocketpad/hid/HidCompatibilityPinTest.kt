package com.dathaze.pocketpad.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Pins the two things a paired host remembers about PocketPad.
 *
 * A Bluetooth host caches a HID device's capabilities when it pairs. Change the report
 * descriptor or the advertised SDP subclass and every device that already works — the
 * Samsung TV above all — has to be un-paired and paired again before it behaves. That is a
 * cost no refactor should be able to impose by accident, so both are nailed down here.
 *
 * **If one of these tests fails, that is the question being asked, not a broken test:** is
 * the change worth making every existing user re-pair? If yes, update the pin in the same
 * commit and say so in the release notes. If you did not mean to change it, you have just
 * been told that you did.
 */
class HidCompatibilityPinTest {

    @Test
    fun theReportDescriptorIsUnchanged() {
        val descriptor = HidConstants.REPORT_DESCRIPTOR
        assertEquals("descriptor length changed", 137, descriptor.size)
        assertEquals(
            "The HID report descriptor changed. Every paired host cached the old one and " +
                "will need re-pairing — see this test's class comment.",
            "dbf3d39b9ffb775cf32833baa4d402e286c1d9be84fd62f16b192f7efbbaccfe",
            sha256(descriptor)
        )
    }

    /**
     * The SDP subclass lives in `HidGamepadManager`, which cannot be loaded in a JVM test
     * because it touches `android.bluetooth`, so it is read out of the source instead — the
     * same approach `ControllerStyleMappingTest` takes to `ControllerView`.
     *
     * `SUBCLASS1_COMBO` is not cosmetic: a bare gamepad subclass was what kept the Samsung TV
     * from listing PocketPad at all before v1.5. Hosts filter their "input device" menus on
     * this byte.
     */
    @Test
    fun theSdpSubclassIsStillCombo() {
        val source = readSource("hid/HidGamepadManager.kt")
        assertTrue(
            "The SDP subclass is no longer SUBCLASS1_COMBO. A bare gamepad subclass is what " +
                "hid PocketPad from the Samsung TV's input-device list before v1.5.",
            Regex("""SUBCLASS\s*:\s*Byte\s*=\s*BluetoothHidDevice\.SUBCLASS1_COMBO""")
                .containsMatchIn(source)
        )
    }

    /**
     * The custom L2CAP flow spec is off. It is the only non-default thing PocketPad asks of
     * the Bluetooth stack, and asking for it is what a stricter host can refuse the channel
     * over, so turning it back on should be a deliberate, visible decision.
     */
    @Test
    fun theCustomQosRequestIsOffByDefault() {
        val source = readSource("hid/HidGamepadManager.kt")
        assertTrue(
            "REQUEST_CUSTOM_QOS is enabled again — this asks the host for a non-default " +
                "L2CAP flow spec, which a strict host may refuse outright.",
            Regex("""REQUEST_CUSTOM_QOS\s*=\s*false""").containsMatchIn(source)
        )
    }

    /** Both input reports must still be the sizes the pinned descriptor declares. */
    @Test
    fun theReportSizesMatchThePinnedDescriptor() {
        assertEquals(9, HidConstants.GAMEPAD_REPORT_SIZE)
        assertEquals(8, HidConstants.KEYBOARD_REPORT_SIZE)
        assertEquals(HidConstants.GAMEPAD_REPORT_SIZE, HidConstants.neutralGamepadReport().size)
        assertEquals(HidConstants.KEYBOARD_REPORT_SIZE, HidConstants.neutralKeyboardReport().size)
    }

    // ----------------------------------------------------------------- helpers

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun readSource(relative: String): String {
        val candidates = listOf(
            "src/main/java/com/dathaze/pocketpad/$relative",
            "app/src/main/java/com/dathaze/pocketpad/$relative",
            "../app/src/main/java/com/dathaze/pocketpad/$relative"
        )
        val found = candidates.map(::File).firstOrNull { it.isFile }
        requireNotNull(found) {
            "$relative not found from ${File(".").absolutePath}; tried $candidates"
        }
        return found.readText()
    }
}
