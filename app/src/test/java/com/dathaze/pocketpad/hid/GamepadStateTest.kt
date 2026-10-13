package com.dathaze.pocketpad.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The input model itself: holding and releasing controls, encoding them, and
 * merging the on-screen pad with physical controllers.
 */
class GamepadStateTest {

    @Test
    fun aFreshStateIsNeutral() {
        val s = GamepadState()
        assertEquals(0, s.buttons)
        assertEquals(HidConstants.HAT_NEUTRAL, s.hat)
        assertEquals(128, s.lx); assertEquals(128, s.ly)
        assertEquals(128, s.rx); assertEquals(128, s.ry)
        assertEquals(0, s.lt); assertEquals(0, s.rt)
    }

    @Test
    fun resetReturnsEverythingToNeutral() {
        val s = GamepadState().apply {
            setButton(HidConstants.BTN_CROSS, true)
            hat = 3; lx = 0; ly = 255; rx = 10; ry = 240; lt = 200; rt = 90
        }
        s.reset()
        assertEquals(0, s.buttons)
        assertEquals(HidConstants.HAT_NEUTRAL, s.hat)
        assertEquals(128, s.lx); assertEquals(0, s.rt)
    }

    @Test
    fun buttonsHoldAndReleaseIndependently() {
        val s = GamepadState()
        s.setButton(HidConstants.BTN_CROSS, true)
        s.setButton(HidConstants.BTN_R1, true)
        assertTrue(s.isPressed(HidConstants.BTN_CROSS))
        assertTrue(s.isPressed(HidConstants.BTN_R1))

        s.setButton(HidConstants.BTN_CROSS, false)
        assertFalse("releasing one must not release the other", s.isPressed(HidConstants.BTN_CROSS))
        assertTrue(s.isPressed(HidConstants.BTN_R1))
    }

    @Test
    fun manyButtonsCanBeHeldAtOnce() {
        val held = listOf(
            HidConstants.BTN_CROSS, HidConstants.BTN_CIRCLE, HidConstants.BTN_SQUARE,
            HidConstants.BTN_TRIANGLE, HidConstants.BTN_L1, HidConstants.BTN_R1,
            HidConstants.BTN_L2, HidConstants.BTN_R2, HidConstants.BTN_SHARE,
            HidConstants.BTN_OPTIONS, HidConstants.BTN_L3, HidConstants.BTN_R3,
            HidConstants.BTN_PS
        )
        val s = GamepadState()
        held.forEach { s.setButton(it, true) }
        held.forEach { assertTrue("button $it should be held", s.isPressed(it)) }

        val report = s.toReport()
        val field = (report[0].toInt() and 0xFF) or ((report[1].toInt() and 0xFF) shl 8)
        assertEquals(held.fold(0) { acc, i -> acc or (1 shl i) }, field)
    }

    @Test
    fun hatEncodesEveryDirectionIncludingDiagonals() {
        for (value in 0..7) {
            val report = GamepadState().apply { hat = value }.toReport()
            assertEquals(value, report[2].toInt() and 0x0F)
        }
        val neutral = GamepadState().toReport()
        assertEquals(HidConstants.HAT_NEUTRAL, neutral[2].toInt() and 0x0F)
    }

    @Test
    fun axesAndTriggersSerialiseInOrder() {
        val report = GamepadState().apply {
            lx = 1; ly = 2; rx = 3; ry = 4; lt = 5; rt = 6
        }.toReport()
        assertEquals(1, report[3].toInt() and 0xFF)
        assertEquals(2, report[4].toInt() and 0xFF)
        assertEquals(3, report[5].toInt() and 0xFF)
        assertEquals(4, report[6].toInt() and 0xFF)
        assertEquals(5, report[7].toInt() and 0xFF)
        assertEquals(6, report[8].toInt() and 0xFF)
    }

    @Test
    fun copyFromCarriesEveryField() {
        val src = GamepadState().apply {
            setButton(HidConstants.BTN_TRIANGLE, true)
            hat = 5; lx = 11; ly = 22; rx = 33; ry = 44; lt = 55; rt = 66
        }
        val dst = GamepadState().apply { copyFrom(src) }
        assertEquals(src.buttons, dst.buttons)
        assertEquals(src.hat, dst.hat)
        assertEquals(src.lx, dst.lx); assertEquals(src.ly, dst.ly)
        assertEquals(src.rx, dst.rx); assertEquals(src.ry, dst.ry)
        assertEquals(src.lt, dst.lt); assertEquals(src.rt, dst.rt)
    }

    // ------------------------------------------------------------- merging

    @Test
    fun mergingTwoNeutralSourcesStaysNeutral() {
        val out = GamepadState()
        GamepadState.merge(GamepadState(), GamepadState(), out)
        assertEquals(0, out.buttons)
        assertEquals(HidConstants.HAT_NEUTRAL, out.hat)
        assertEquals(128, out.lx)
        assertEquals(0, out.lt)
    }

    @Test
    fun touchAndControllerButtonsCombine() {
        val touch = GamepadState().apply { setButton(HidConstants.BTN_CROSS, true) }
        val external = GamepadState().apply { setButton(HidConstants.BTN_CIRCLE, true) }
        val out = GamepadState()
        GamepadState.merge(touch, external, out)
        assertTrue(out.isPressed(HidConstants.BTN_CROSS))
        assertTrue(out.isPressed(HidConstants.BTN_CIRCLE))
    }

    @Test
    fun aNeutralControllerLeavesHeldTouchButtonsAlone() {
        // This is the property that keeps a finger on the glass working when a
        // physical controller disconnects and its state goes neutral.
        val touch = GamepadState().apply {
            setButton(HidConstants.BTN_SQUARE, true)
            hat = 2
            lx = 0
        }
        val out = GamepadState()
        GamepadState.merge(touch, GamepadState(), out)
        assertTrue(out.isPressed(HidConstants.BTN_SQUARE))
        assertEquals(2, out.hat)
        assertEquals(0, out.lx)
    }

    @Test
    fun aDeflectedControllerStickWinsOverACentredTouchStick() {
        val touch = GamepadState()
        val external = GamepadState().apply { lx = 10 }
        val out = GamepadState()
        GamepadState.merge(touch, external, out)
        assertEquals(10, out.lx)
    }

    @Test
    fun aDeflectedTouchStickWinsOverACentredController() {
        val touch = GamepadState().apply { ry = 250 }
        val out = GamepadState()
        GamepadState.merge(touch, GamepadState(), out)
        assertEquals(250, out.ry)
    }

    @Test
    fun theHarderPressedTriggerWins() {
        val touch = GamepadState().apply { lt = 255; rt = 0 }
        val external = GamepadState().apply { lt = 40; rt = 180 }
        val out = GamepadState()
        GamepadState.merge(touch, external, out)
        assertEquals(255, out.lt)
        assertEquals(180, out.rt)
    }

    @Test
    fun aControllerHatOverridesTheTouchDpadButNeutralDoesNot() {
        val touch = GamepadState().apply { hat = 4 }
        val out = GamepadState()

        GamepadState.merge(touch, GamepadState().apply { hat = 0 }, out)
        assertEquals("a live controller hat should win", 0, out.hat)

        GamepadState.merge(touch, GamepadState(), out)
        assertEquals("a neutral controller must not erase the touch d-pad", 4, out.hat)
    }
}
