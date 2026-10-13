package com.dathaze.pocketpad.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the stuck-input defect: a physical controller that
 * disappears mid-press used to leave its buttons and sticks transmitted as
 * held, because every external source was accumulated into one shared state.
 */
class ExternalInputTrackerTest {

    private val padA = 11
    private val padB = 22

    private fun combined(t: ExternalInputTracker) =
        GamepadState().also { t.combineInto(it) }

    @Test
    fun noControllersMeansNeutral() {
        val out = combined(ExternalInputTracker())
        assertEquals(0, out.buttons)
        assertEquals(HidConstants.HAT_NEUTRAL, out.hat)
        assertEquals(128, out.lx)
        assertEquals(0, out.rt)
    }

    @Test
    fun oneControllerContributesItsInput() {
        val t = ExternalInputTracker()
        t.source(padA).state.apply {
            setButton(HidConstants.BTN_CROSS, true)
            lx = 0
            rt = 200
        }
        val out = combined(t)
        assertTrue(out.isPressed(HidConstants.BTN_CROSS))
        assertEquals(0, out.lx)
        assertEquals(200, out.rt)
    }

    /** The core defect: disconnecting must release what that pad was holding. */
    @Test
    fun removingTheOnlyControllerReleasesEverythingItHeld() {
        val t = ExternalInputTracker()
        t.source(padA).state.apply {
            setButton(HidConstants.BTN_R1, true)
            hat = 2
            lx = 255
            lt = 255
        }
        assertTrue(combined(t).isPressed(HidConstants.BTN_R1))

        assertTrue(t.remove(padA))

        val out = combined(t)
        assertEquals("buttons must clear", 0, out.buttons)
        assertEquals("hat must return to neutral", HidConstants.HAT_NEUTRAL, out.hat)
        assertEquals("stick must recentre", 128, out.lx)
        assertEquals("trigger must release", 0, out.lt)
    }

    @Test
    fun removingOneControllerLeavesTheOtherUntouched() {
        val t = ExternalInputTracker()
        t.source(padA).state.setButton(HidConstants.BTN_CROSS, true)
        t.source(padB).state.apply {
            setButton(HidConstants.BTN_CIRCLE, true)
            hat = 4
            ry = 20
            rt = 150
        }

        t.remove(padA)

        val out = combined(t)
        assertFalse("the disconnected pad's button must clear", out.isPressed(HidConstants.BTN_CROSS))
        assertTrue("the remaining pad keeps its button", out.isPressed(HidConstants.BTN_CIRCLE))
        assertEquals("the remaining pad keeps its hat", 4, out.hat)
        assertEquals("the remaining pad keeps its stick", 20, out.ry)
        assertEquals("the remaining pad keeps its trigger", 150, out.rt)
    }

    @Test
    fun theDs3CoexistsWithANativeControllerAndRemovesIndependently() {
        val t = ExternalInputTracker()
        t.source(ExternalInputTracker.SOURCE_DS3).state.apply {
            setButton(HidConstants.BTN_SQUARE, true)
            lt = 180
        }
        t.source(padA).state.setButton(HidConstants.BTN_TRIANGLE, true)

        var out = combined(t)
        assertTrue(out.isPressed(HidConstants.BTN_SQUARE))
        assertTrue(out.isPressed(HidConstants.BTN_TRIANGLE))

        t.remove(ExternalInputTracker.SOURCE_DS3)
        out = combined(t)
        assertFalse("the DS3's button must clear", out.isPressed(HidConstants.BTN_SQUARE))
        assertEquals("the DS3's trigger must release", 0, out.lt)
        assertTrue("the native pad is unaffected", out.isPressed(HidConstants.BTN_TRIANGLE))
    }

    @Test
    fun theDs3KeyCannotCollideWithARealDeviceId() {
        // Android device ids are non-negative; -1 marks virtual devices.
        assertTrue(ExternalInputTracker.SOURCE_DS3 < -1)
    }

    @Test
    fun removingAnUnknownControllerIsHarmless() {
        val t = ExternalInputTracker()
        t.source(padA).state.setButton(HidConstants.BTN_CROSS, true)

        assertFalse("nothing was tracked under that id", t.remove(9999))
        assertTrue("existing input is untouched", combined(t).isPressed(HidConstants.BTN_CROSS))

        assertTrue(t.remove(padA))
        assertFalse("a second removal reports nothing to do", t.remove(padA))
    }

    @Test
    fun sourcesAreOnlyCreatedDeliberately() {
        val t = ExternalInputTracker()
        assertEquals(0, t.sourceCount)
        assertFalse(t.has(padA))

        t.source(padA)
        assertTrue(t.has(padA))
        assertEquals(1, t.sourceCount)
        assertEquals(setOf(padA), t.sourceIds())

        t.clear()
        assertEquals(0, t.sourceCount)
    }

    @Test
    fun twoControllersCombineRatherThanOverwrite() {
        val t = ExternalInputTracker()
        t.source(padA).state.apply { lx = 0; lt = 100 }
        t.source(padB).state.apply { ly = 255; lt = 250 }

        val out = combined(t)
        assertEquals("most deflected wins on each axis", 0, out.lx)
        assertEquals(255, out.ly)
        assertEquals("hardest press wins on a trigger", 250, out.lt)
    }

    // ------------------------------------------------------------- d-pad

    @Test
    fun hatCoversAllEightDirectionsAndNeutral() {
        val f = ExternalInputTracker.Companion
        assertEquals(0, f.hatFor(up = true, right = false, down = false, left = false))
        assertEquals(1, f.hatFor(up = true, right = true, down = false, left = false))
        assertEquals(2, f.hatFor(up = false, right = true, down = false, left = false))
        assertEquals(3, f.hatFor(up = false, right = true, down = true, left = false))
        assertEquals(4, f.hatFor(up = false, right = false, down = true, left = false))
        assertEquals(5, f.hatFor(up = false, right = false, down = true, left = true))
        assertEquals(6, f.hatFor(up = false, right = false, down = false, left = true))
        assertEquals(7, f.hatFor(up = true, right = false, down = false, left = true))
        assertEquals(
            HidConstants.HAT_NEUTRAL,
            f.hatFor(up = false, right = false, down = false, left = false)
        )
    }

    @Test
    fun keyAndAxisDpadsCombineWithoutErasingEachOther() {
        val s = ExternalInputTracker.Source()

        s.keyUp = true
        s.applyDpad()
        assertEquals(0, s.state.hat)

        // A hat axis pushed right while up is held as a key reads as up-right.
        s.axisRight = true
        s.applyDpad()
        assertEquals(1, s.state.hat)

        // The axis returning to centre must not release the held key.
        s.axisRight = false
        s.applyDpad()
        assertEquals("key-held direction survives the axis recentring", 0, s.state.hat)

        s.keyUp = false
        s.applyDpad()
        assertEquals(HidConstants.HAT_NEUTRAL, s.state.hat)
    }

    /**
     * A hat axis returning to centre used to leave the direction latched,
     * because the flags were only rewritten while the axis was deflected.
     */
    @Test
    fun aHatAxisReturningToCentreReleasesTheDirection() {
        val s = ExternalInputTracker.Source()
        s.axisRight = true
        s.applyDpad()
        assertEquals(2, s.state.hat)

        s.axisRight = false
        s.applyDpad()
        assertEquals(HidConstants.HAT_NEUTRAL, s.state.hat)
    }

    @Test
    fun theFirstControllerPointingSomewhereSuppliesTheHat() {
        val t = ExternalInputTracker()
        t.source(padA).state.hat = HidConstants.HAT_NEUTRAL
        t.source(padB).state.hat = 6
        assertEquals(6, combined(t).hat)
    }
}
