package com.dathaze.pocketpad.hid

import com.dathaze.pocketpad.ui.ControlGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end verification of what each control puts on the wire.
 *
 * `ControllerStyleMappingTest` already checks which button constant each on-screen control is
 * bound to, but it does that by reading `ControllerView.kt` as text — it cannot run anything.
 * This picks the path up from the constant onwards and actually executes it: constant → bit
 * position → merged state → serialized report bytes.
 *
 * Between the two, every link from a finger to a byte is covered by something.
 */
class ControlSignalPathTest {

    private fun GamepadState.report() = toReport()
    private fun buttonsOf(r: ByteArray) = (r[0].toInt() and 0xFF) or ((r[1].toInt() and 0xFF) shl 8)
    private fun hatOf(r: ByteArray) = r[2].toInt() and 0x0F
    private fun bit(i: Int) = 1 shl i

    // --------------------------------------------------- face buttons, both skins

    /**
     * The audit's central finding, now executable: Xbox and PlayStation are the same signals.
     *
     * Positionally they are one-for-one — A and Cross are both the bottom face button — so the
     * mapping is correct for both. What a host *calls* button 1 is the host's decision, and a
     * generic HID gamepad has no way to influence it.
     */
    @Test
    fun theFourFaceButtonsOccupyButtonsOneToFour() {
        val expected = listOf(
            "bottom (A / Cross)" to HidConstants.BTN_CROSS,
            "right (B / Circle)" to HidConstants.BTN_CIRCLE,
            "left (X / Square)" to HidConstants.BTN_SQUARE,
            "top (Y / Triangle)" to HidConstants.BTN_TRIANGLE,
        )
        expected.forEachIndexed { index, (name, constant) ->
            assertEquals("$name must be HID button ${index + 1}", index, constant)
            val state = GamepadState().apply { setButton(constant, true) }
            assertEquals(
                "$name sets exactly one bit",
                bit(index),
                buttonsOf(state.report())
            )
        }
    }

    @Test
    fun everyNamedControlLandsOnItsOwnDistinctBit() {
        val controls = mapOf(
            "Cross/A" to HidConstants.BTN_CROSS,
            "Circle/B" to HidConstants.BTN_CIRCLE,
            "Square/X" to HidConstants.BTN_SQUARE,
            "Triangle/Y" to HidConstants.BTN_TRIANGLE,
            "L1/LB" to HidConstants.BTN_L1,
            "R1/RB" to HidConstants.BTN_R1,
            "L2/LT" to HidConstants.BTN_L2,
            "R2/RT" to HidConstants.BTN_R2,
            "Share/View/Select" to HidConstants.BTN_SHARE,
            "Options/Menu/Start" to HidConstants.BTN_OPTIONS,
            "L3/LS" to HidConstants.BTN_L3,
            "R3/RS" to HidConstants.BTN_R3,
            "Home/PS/Guide" to HidConstants.BTN_PS,
        )
        assertEquals("no two controls share a bit", controls.size, controls.values.toSet().size)
        controls.forEach { (name, index) ->
            assertTrue("$name is inside the 16 declared buttons", index in 0..15)
            val r = GamepadState().apply { setButton(index, true) }.report()
            assertEquals("$name sets one bit and nothing else", bit(index), buttonsOf(r))
        }
    }

    @Test
    fun releasingAControlClearsOnlyItsOwnBit() {
        val state = GamepadState()
        state.setButton(HidConstants.BTN_L1, true)
        state.setButton(HidConstants.BTN_R1, true)
        state.setButton(HidConstants.BTN_L1, false)

        assertFalse(state.isPressed(HidConstants.BTN_L1))
        assertTrue("the other shoulder is untouched", state.isPressed(HidConstants.BTN_R1))
        assertEquals(bit(HidConstants.BTN_R1), buttonsOf(state.report()))
    }

    // ----------------------------------------------------------- shoulders/triggers

    @Test
    fun triggersDriveBothAButtonBitAndAnAnalogAxis() {
        // A digital bit alone is unusable for anything reading a throttle; an axis alone is
        // invisible to anything reading a button. Both are needed.
        val state = GamepadState().apply {
            setButton(HidConstants.BTN_R2, true)
            rt = 255
        }
        val r = state.report()
        assertTrue("button bit", buttonsOf(r) and bit(HidConstants.BTN_R2) != 0)
        assertEquals("accelerator axis", 255, r[8].toInt() and 0xFF)
    }

    @Test
    fun theTwoTriggerAxesDoNotSwap() {
        val r = GamepadState().apply { lt = 64; rt = 192 }.report()
        assertEquals("brake is byte 7", 64, r[7].toInt() and 0xFF)
        assertEquals("accelerator is byte 8", 192, r[8].toInt() and 0xFF)
    }

    // ------------------------------------------------------------------- the sticks

    @Test
    fun stickAxesSerialiseToTheirOwnBytesInDescriptorOrder() {
        val r = GamepadState().apply { lx = 10; ly = 20; rx = 30; ry = 40 }.report()
        assertEquals("X  (left stick horizontal)", 10, r[3].toInt() and 0xFF)
        assertEquals("Y  (left stick vertical)", 20, r[4].toInt() and 0xFF)
        assertEquals("Z  (right stick horizontal)", 30, r[5].toInt() and 0xFF)
        assertEquals("Rz (right stick vertical)", 40, r[6].toInt() and 0xFF)
    }

    @Test
    fun aCentredStickReadsAsTheMidpoint() {
        val r = GamepadState().report()
        listOf(3, 4, 5, 6).forEach { assertEquals(128, r[it].toInt() and 0xFF) }
    }

    @Test
    fun stickClicksAreSeparateFromStickMovement() {
        val state = GamepadState().apply {
            lx = 0; ly = 0
            setButton(HidConstants.BTN_L3, true)
        }
        val r = state.report()
        assertEquals(bit(HidConstants.BTN_L3), buttonsOf(r))
        assertEquals("pushing the stick does not press it", 0, r[3].toInt() and 0xFF)
    }

    // ----------------------------------------------------------------- the d-pad

    @Test
    fun theFourCardinalDirectionsMapToTheRightHatValues() {
        val r = 100f
        assertEquals("up", 0, ControlGeometry.hatFor(0f, -r, r))
        assertEquals("right", 2, ControlGeometry.hatFor(r, 0f, r))
        assertEquals("down", 4, ControlGeometry.hatFor(0f, r, r))
        assertEquals("left", 6, ControlGeometry.hatFor(-r, 0f, r))
    }

    @Test
    fun theFourDiagonalsMapToTheOddHatValues() {
        val d = 70f   // ~99 px from centre on a radius-100 pad
        assertEquals("up-right", 1, ControlGeometry.hatFor(d, -d, 100f))
        assertEquals("down-right", 3, ControlGeometry.hatFor(d, d, 100f))
        assertEquals("down-left", 5, ControlGeometry.hatFor(-d, d, 100f))
        assertEquals("up-left", 7, ControlGeometry.hatFor(-d, -d, 100f))
        listOf(1, 3, 5, 7).forEach { assertTrue(ControlGeometry.isDiagonal(it)) }
        listOf(0, 2, 4, 6).forEach { assertFalse(ControlGeometry.isDiagonal(it)) }
    }

    @Test
    fun theCentreOfTheDpadIsNeutral() {
        assertEquals(HidConstants.HAT_NEUTRAL, ControlGeometry.hatFor(0f, 0f, 100f))
        assertEquals(
            "just inside the dead zone",
            HidConstants.HAT_NEUTRAL,
            ControlGeometry.hatFor(10f, 0f, 100f)
        )
        assertNotEquals(
            "just outside it",
            HidConstants.HAT_NEUTRAL,
            ControlGeometry.hatFor(25f, 0f, 100f)
        )
    }

    @Test
    fun directionBoundariesSitHalfwayBetweenNeighbours() {
        // 22.5 degrees either side of straight up is the up/up-right and up-left/up border.
        assertEquals("just before the boundary is still up", 0, ControlGeometry.hatFor(30f, -100f, 100f))
        assertEquals("just after it is up-right", 1, ControlGeometry.hatFor(50f, -100f, 100f))
        assertEquals("wrapping past 337.5 returns to up", 0, ControlGeometry.hatFor(-30f, -100f, 100f))
    }

    @Test
    fun everyDirectionAroundTheCircleIsReachableAndInOrder() {
        // Sweep clockwise from up and confirm the hat advances 0,1,2,…,7 with no gaps or jumps.
        val seen = mutableListOf<Int>()
        var angle = 0.0
        while (angle < 360.0) {
            val rad = Math.toRadians(angle)
            val hat = ControlGeometry.hatFor(
                (Math.sin(rad) * 100).toFloat(),
                (-Math.cos(rad) * 100).toFloat(),
                100f
            )
            if (seen.lastOrNull() != hat) seen += hat
            angle += 1.0
        }
        // The sweep ends back inside "up"'s own 45-degree wedge, so the run closes with a
        // second 0. That wrap is correct — the directions must come round in order and return
        // to where they started.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 0), seen)
    }

    @Test
    fun theHatNibbleNeverOverflowsItsFourBits() {
        for (hat in 0..8) {
            val r = GamepadState().apply { this.hat = hat }.report()
            assertEquals(hat, hatOf(r))
            assertEquals("upper nibble stays clear for padding", 0, r[2].toInt() and 0xF0)
        }
    }

    // -------------------------------------------- the Batmobile / simultaneous case

    /**
     * The scenario reported on Xbox Cloud Gaming: a shoulder and a trigger held together while
     * a stick is live. Every part has to survive into one report.
     *
     * This verifies the *state and serialization* half. Whether the host then acts on it is a
     * separate question that needs the real TV — see the handoff notes.
     */
    @Test
    fun aShoulderATriggerAndBothSticksCoexistInOneReport() {
        val state = GamepadState().apply {
            setButton(HidConstants.BTN_L1, true)
            setButton(HidConstants.BTN_R2, true)
            rt = 255
            lx = 20; ly = 200
            rx = 240; ry = 15
        }
        val r = state.report()

        assertTrue("L1 held", state.isPressed(HidConstants.BTN_L1))
        assertTrue("R2 held", state.isPressed(HidConstants.BTN_R2))
        assertEquals(
            "both bits present together",
            bit(HidConstants.BTN_L1) or bit(HidConstants.BTN_R2),
            buttonsOf(r)
        )
        assertEquals(255, r[8].toInt() and 0xFF)
        assertEquals(20, r[3].toInt() and 0xFF)
        assertEquals(200, r[4].toInt() and 0xFF)
        assertEquals(240, r[5].toInt() and 0xFF)
        assertEquals(15, r[6].toInt() and 0xFF)
    }

    @Test
    fun allThirteenControlsCanBeHeldAtOnce() {
        val all = listOf(
            HidConstants.BTN_CROSS, HidConstants.BTN_CIRCLE, HidConstants.BTN_SQUARE,
            HidConstants.BTN_TRIANGLE, HidConstants.BTN_L1, HidConstants.BTN_R1,
            HidConstants.BTN_L2, HidConstants.BTN_R2, HidConstants.BTN_SHARE,
            HidConstants.BTN_OPTIONS, HidConstants.BTN_L3, HidConstants.BTN_R3,
            HidConstants.BTN_PS,
        )
        val state = GamepadState()
        all.forEach { state.setButton(it, true) }
        val bits = buttonsOf(state.report())
        all.forEach { assertTrue("button $it survived", bits and bit(it) != 0) }
        assertEquals("13 bits set, no more", 13, Integer.bitCount(bits))
    }

    @Test
    fun aDpadDirectionAndFaceButtonsAreIndependent() {
        val state = GamepadState().apply {
            hat = 3
            setButton(HidConstants.BTN_CROSS, true)
        }
        val r = state.report()
        assertEquals(3, hatOf(r))
        assertEquals(bit(HidConstants.BTN_CROSS), buttonsOf(r))
    }

    // ---------------------------------------------- touch merged with a USB pad

    @Test
    fun touchAndAnExternalPadCombineWithoutLosingEitherOne() {
        val touch = GamepadState().apply { setButton(HidConstants.BTN_CROSS, true) }
        val external = GamepadState().apply { setButton(HidConstants.BTN_CIRCLE, true) }
        val out = GamepadState()
        GamepadState.merge(touch, external, out)

        assertTrue(out.isPressed(HidConstants.BTN_CROSS))
        assertTrue(out.isPressed(HidConstants.BTN_CIRCLE))
    }

    @Test
    fun aDeflectedExternalStickWinsOverACentredTouchStickAndViceVersa() {
        val touch = GamepadState().apply { lx = 128 }
        val external = GamepadState().apply { lx = 10 }
        val out = GamepadState()
        GamepadState.merge(touch, external, out)
        assertEquals("the one actually being pushed wins", 10, out.lx)

        val touch2 = GamepadState().apply { rx = 250 }
        val external2 = GamepadState()
        GamepadState.merge(touch2, external2, out)
        assertEquals(250, out.rx)
    }

    @Test
    fun theHarderPressedTriggerWins() {
        val touch = GamepadState().apply { rt = 255 }
        val external = GamepadState().apply { rt = 40 }
        val out = GamepadState()
        GamepadState.merge(touch, external, out)
        assertEquals(255, out.rt)
    }

    // ------------------------------------------------------------- report shape

    @Test
    fun theReportIsAlwaysNineBytesWhateverIsHeld() {
        assertEquals(HidConstants.GAMEPAD_REPORT_SIZE, GamepadState().report().size)
        val busy = GamepadState().apply {
            (0..12).forEach { setButton(it, true) }
            hat = 5; lx = 0; ly = 255; rx = 0; ry = 255; lt = 255; rt = 255
        }
        assertEquals(HidConstants.GAMEPAD_REPORT_SIZE, busy.report().size)
    }

    @Test
    fun resetReturnsEveryControlToItsRestingValue() {
        val state = GamepadState().apply {
            (0..12).forEach { setButton(it, true) }
            hat = 2; lx = 0; ly = 0; rx = 255; ry = 255; lt = 255; rt = 255
        }
        state.reset()
        val r = state.report()
        assertEquals(0, buttonsOf(r))
        assertEquals(HidConstants.HAT_NEUTRAL, hatOf(r))
        listOf(3, 4, 5, 6).forEach { assertEquals(128, r[it].toInt() and 0xFF) }
        listOf(7, 8).forEach { assertEquals(0, r[it].toInt() and 0xFF) }
    }
}
