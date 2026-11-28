package com.dathaze.pocketpad.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC Keys mapping, control by control.
 *
 * Every row of the table in [PcKeyMap]'s documentation is asserted here, because the whole point
 * of the mode is that each control sends something *distinct* — a mapping with two controls on
 * one key would look fine in use right up until the emulator bound them both.
 */
class PcKeyMapTest {

    private fun state(block: GamepadState.() -> Unit = {}) = GamepadState().apply(block)

    private fun keys(block: GamepadState.() -> Unit) = PcKeyMap.keysFor(state(block))

    private fun held(block: GamepadState.() -> Unit) =
        KeyboardReport.keysOf(PcKeyMap.reportFor(state(block)))

    private fun modifiers(block: GamepadState.() -> Unit) =
        KeyboardReport.modifiersOf(PcKeyMap.reportFor(state(block)))

    // ------------------------------------------------------- one button each

    /** The mapping as the user was told it, one control at a time. */
    private val buttonKeys = listOf(
        HidConstants.BTN_CROSS to PcKeyMap.KEY_Z,
        HidConstants.BTN_CIRCLE to PcKeyMap.KEY_X,
        HidConstants.BTN_SQUARE to PcKeyMap.KEY_A,
        HidConstants.BTN_TRIANGLE to PcKeyMap.KEY_S,
        HidConstants.BTN_L1 to PcKeyMap.KEY_Q,
        HidConstants.BTN_R1 to PcKeyMap.KEY_W,
        HidConstants.BTN_L2 to PcKeyMap.KEY_E,
        HidConstants.BTN_R2 to PcKeyMap.KEY_R,
        HidConstants.BTN_OPTIONS to HidConstants.KEY_ENTER,
        HidConstants.BTN_L3 to PcKeyMap.KEY_C,
        HidConstants.BTN_R3 to PcKeyMap.KEY_V,
        HidConstants.BTN_PS to HidConstants.KEY_ESCAPE,
    )

    @Test
    fun everyButtonSendsItsOwnKey() {
        buttonKeys.forEach { (button, key) ->
            assertEquals(
                "button $button should send key $key",
                listOf(key),
                keys { setButton(button, true) },
            )
        }
    }

    @Test
    fun noTwoControlsShareAKey() {
        val sent = buttonKeys.map { it.second }
        assertEquals("two controls map to the same key", sent.size, sent.distinct().size)
    }

    @Test
    fun everyKeyFitsAKeySlot() {
        // The descriptor declares the six slots with Usage Maximum 101. Anything above that is
        // not valid there, which is exactly why Right Shift is a modifier instead.
        buttonKeys.forEach { (button, key) ->
            val code = key.toInt() and 0xFF
            assertTrue(
                "button $button sends 0x${code.toString(16)}, above the declared maximum",
                code in 1..KeyboardReport.MAX_KEYCODE,
            )
        }
    }

    // ------------------------------------------------------------ Right Shift

    @Test
    fun shareIsRightShiftAndUsesNoKeySlot() {
        val s = state { setButton(HidConstants.BTN_SHARE, true) }
        assertEquals(KeyboardReport.MOD_RIGHT_SHIFT, PcKeyMap.modifiersFor(s))
        assertTrue("Right Shift must not occupy a key slot", PcKeyMap.keysFor(s).isEmpty())
    }

    @Test
    fun shareCombinesWithAKeyRatherThanReplacingIt() {
        val report = PcKeyMap.reportFor(state {
            setButton(HidConstants.BTN_SHARE, true)
            setButton(HidConstants.BTN_CROSS, true)
        })
        assertEquals(KeyboardReport.MOD_RIGHT_SHIFT, KeyboardReport.modifiersOf(report))
        assertEquals(listOf(PcKeyMap.KEY_Z), KeyboardReport.keysOf(report))
    }

    @Test
    fun nothingHeldIsANeutralReport() {
        assertTrue(KeyboardReport.isNeutral(PcKeyMap.reportFor(state())))
    }

    // ------------------------------------------------------ all eight d-pad directions

    /** Hat value to the arrows it is made of, clockwise from up. Eight, not four. */
    private val hatDirections = mapOf(
        0 to listOf(HidConstants.KEY_UP),
        1 to listOf(HidConstants.KEY_UP, HidConstants.KEY_RIGHT),
        2 to listOf(HidConstants.KEY_RIGHT),
        3 to listOf(HidConstants.KEY_DOWN, HidConstants.KEY_RIGHT),
        4 to listOf(HidConstants.KEY_DOWN),
        5 to listOf(HidConstants.KEY_DOWN, HidConstants.KEY_LEFT),
        6 to listOf(HidConstants.KEY_LEFT),
        7 to listOf(HidConstants.KEY_UP, HidConstants.KEY_LEFT),
    )

    @Test
    fun allEightDpadDirectionsProduceArrows() {
        hatDirections.forEach { (hat, expected) ->
            assertEquals(
                "hat $hat",
                expected.sorted(),
                keys { this.hat = hat }.sorted(),
            )
        }
    }

    /**
     * The defect PC Keys exists to fix, stated as a test.
     *
     * TV Keys only answers hat 0, 2, 4 and 6; the four diagonals fall through to `KEY_NONE`, so
     * rolling a thumb round the d-pad drops input entirely at each corner.
     */
    @Test
    fun noDpadDirectionEverProducesNothing() {
        (0..7).forEach { hat ->
            assertTrue("hat $hat sent no key at all", keys { this.hat = hat }.isNotEmpty())
        }
    }

    @Test
    fun aCentredDpadSendsNothing() {
        assertTrue(keys { hat = HidConstants.HAT_NEUTRAL }.isEmpty())
    }

    // ------------------------------------------------------------- left stick

    @Test
    fun theLeftStickDrivesTheArrows() {
        assertEquals(listOf(HidConstants.KEY_UP), keys { ly = 0 })
        assertEquals(listOf(HidConstants.KEY_DOWN), keys { ly = 255 })
        assertEquals(listOf(HidConstants.KEY_LEFT), keys { lx = 0 })
        assertEquals(listOf(HidConstants.KEY_RIGHT), keys { lx = 255 })
    }

    @Test
    fun theStickSupportsDiagonals() {
        assertEquals(
            listOf(HidConstants.KEY_UP, HidConstants.KEY_RIGHT).sorted(),
            keys { lx = 255; ly = 0 }.sorted(),
        )
        assertEquals(
            listOf(HidConstants.KEY_DOWN, HidConstants.KEY_LEFT).sorted(),
            keys { lx = 0; ly = 255 }.sorted(),
        )
    }

    @Test
    fun aRestingStickSendsNothing() {
        assertTrue(keys { lx = 128; ly = 128 }.isEmpty())
    }

    @Test
    fun aStickInsideTheDeadZoneSendsNothing() {
        val justInside = 128 + PcKeyMap.STICK_DEADZONE
        assertTrue("$justInside should still be centre", keys { lx = justInside }.isEmpty())
        assertTrue(keys { ly = 128 - PcKeyMap.STICK_DEADZONE }.isEmpty())
    }

    @Test
    fun aStickJustOutsideTheDeadZoneRegisters() {
        assertEquals(
            listOf(HidConstants.KEY_RIGHT),
            keys { lx = 128 + PcKeyMap.STICK_DEADZONE + 1 },
        )
    }

    @Test
    fun theRightStickIsNotMappedToArrows() {
        // Only the left stick stands in for the d-pad; the right one has no keyboard meaning.
        assertTrue(keys { rx = 255; ry = 0 }.isEmpty())
    }

    @Test
    fun theDpadAndTheStickDoNotDoubleUpOnAKey() {
        // Both pushed the same way is still one Up: a duplicate keycode in two slots is not a
        // thing a host can read.
        assertEquals(listOf(HidConstants.KEY_UP), held { hat = 0; ly = 0 })
    }

    // ------------------------------------------------------ analog triggers

    @Test
    fun anAnalogTriggerCountsWithoutItsButtonBit() {
        // A USB controller may report only the axis.
        assertEquals(listOf(PcKeyMap.KEY_E), keys { lt = 255 })
        assertEquals(listOf(PcKeyMap.KEY_R), keys { rt = 255 })
    }

    @Test
    fun aLightlyRestingTriggerDoesNotCount() {
        assertTrue(keys { lt = PcKeyMap.TRIGGER_THRESHOLD - 1 }.isEmpty())
    }

    // ------------------------------------------------- several keys at once

    @Test
    fun twoKeysAtOnce() {
        val report = PcKeyMap.reportFor(state {
            hat = 6
            setButton(HidConstants.BTN_CROSS, true)
        })
        assertEquals(
            listOf(HidConstants.KEY_LEFT, PcKeyMap.KEY_Z).sorted(),
            KeyboardReport.keysOf(report).sorted(),
        )
    }

    @Test
    fun runningAndJumpingTogetherIsExpressible() {
        // The thing a single-key report physically cannot say, and the reason this mode exists.
        val report = PcKeyMap.reportFor(state {
            hat = 2
            setButton(HidConstants.BTN_CROSS, true)
            setButton(HidConstants.BTN_SQUARE, true)
        })
        val keys = KeyboardReport.keysOf(report)
        assertTrue(keys.contains(HidConstants.KEY_RIGHT))
        assertTrue(keys.contains(PcKeyMap.KEY_Z))
        assertTrue(keys.contains(PcKeyMap.KEY_A))
    }

    @Test
    fun sixKeysAtOnce() {
        val report = PcKeyMap.reportFor(state {
            hat = 1 // up + right, two of the six
            setButton(HidConstants.BTN_CROSS, true)
            setButton(HidConstants.BTN_CIRCLE, true)
            setButton(HidConstants.BTN_SQUARE, true)
            setButton(HidConstants.BTN_TRIANGLE, true)
        })
        assertEquals(
            listOf(
                HidConstants.KEY_UP,
                HidConstants.KEY_RIGHT,
                PcKeyMap.KEY_Z,
                PcKeyMap.KEY_X,
                PcKeyMap.KEY_A,
                PcKeyMap.KEY_S,
            ).sorted(),
            KeyboardReport.keysOf(report).sorted(),
        )
    }

    @Test
    fun aSeventhKeyIsDroppedAndDirectionsSurvive() {
        // Rollover keeps the first six in priority order rather than blanking the report, so
        // what is lost is the lowest-priority button and never the direction being held.
        val report = PcKeyMap.reportFor(state {
            hat = 4
            setButton(HidConstants.BTN_CROSS, true)
            setButton(HidConstants.BTN_CIRCLE, true)
            setButton(HidConstants.BTN_SQUARE, true)
            setButton(HidConstants.BTN_TRIANGLE, true)
            setButton(HidConstants.BTN_L1, true)
            setButton(HidConstants.BTN_PS, true)
        })
        val keys = KeyboardReport.keysOf(report)
        assertEquals(KeyboardReport.MAX_KEYS, keys.size)
        assertTrue("the direction must survive rollover", keys.contains(HidConstants.KEY_DOWN))
        assertFalse("Escape is lowest priority and should be the one dropped",
            keys.contains(HidConstants.KEY_ESCAPE))
    }

    @Test
    fun everyControlAtOnceStillProducesAValidReport() {
        val report = PcKeyMap.reportFor(state {
            hat = 3
            lx = 0; ly = 0
            lt = 255; rt = 255
            buttons = 0xFFFF
        })
        assertEquals(HidConstants.KEYBOARD_REPORT_SIZE, report.size)
        assertEquals(KeyboardReport.MAX_KEYS, KeyboardReport.keysOf(report).size)
        assertEquals(KeyboardReport.MOD_RIGHT_SHIFT, KeyboardReport.modifiersOf(report))
    }

    // ----------------------------------------------------- releasing one key

    @Test
    fun releasingOneButtonLeavesTheOtherHeld() {
        val s = state {
            setButton(HidConstants.BTN_CROSS, true)
            setButton(HidConstants.BTN_SQUARE, true)
        }
        assertEquals(2, PcKeyMap.keysFor(s).size)

        s.setButton(HidConstants.BTN_CROSS, false)

        assertEquals(
            "releasing ✕ must not release □",
            listOf(PcKeyMap.KEY_A),
            PcKeyMap.keysFor(s),
        )
    }

    @Test
    fun releasingADirectionLeavesAHeldButton() {
        val s = state {
            hat = 2
            setButton(HidConstants.BTN_CROSS, true)
        }
        s.hat = HidConstants.HAT_NEUTRAL
        assertEquals(listOf(PcKeyMap.KEY_Z), PcKeyMap.keysFor(s))
    }

    @Test
    fun releasingEverythingProducesTheNeutralReport() {
        val s = state {
            hat = 1
            buttons = 0xFFFF
            lt = 255
        }
        assertFalse(KeyboardReport.isNeutral(PcKeyMap.reportFor(s)))
        s.reset()
        assertTrue(KeyboardReport.isNeutral(PcKeyMap.reportFor(s)))
    }
}
