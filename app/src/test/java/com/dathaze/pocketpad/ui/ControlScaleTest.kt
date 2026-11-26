package com.dathaze.pocketpad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size slider: its bounds, and the arithmetic that keeps the readout honest.
 *
 * A slider whose number disagrees with what it actually applied is worse than no number at all,
 * so the round trip between slider position and scale is what most of this checks.
 */
class ControlScaleTest {

    private val tolerance = 0.0001f

    @Test
    fun theRangeIsTheOneThatWasAskedFor() {
        assertEquals(0.70f, ControlScale.MIN, tolerance)
        assertEquals(1.50f, ControlScale.MAX, tolerance)
        assertEquals(1.00f, ControlScale.DEFAULT, tolerance)
        assertTrue("the default has to be inside the range", ControlScale.DEFAULT > ControlScale.MIN)
        assertTrue(ControlScale.DEFAULT < ControlScale.MAX)
    }

    @Test
    fun anythingOutsideTheRangeIsPulledBackIn() {
        assertEquals(ControlScale.MAX, ControlScale.clamp(4f), tolerance)
        assertEquals(ControlScale.MIN, ControlScale.clamp(0f), tolerance)
        assertEquals(ControlScale.MIN, ControlScale.clamp(-1f), tolerance)
        assertEquals(1.25f, ControlScale.clamp(1.25f), tolerance)
    }

    @Test
    fun theSliderEndsAreTheRangeEnds() {
        assertEquals(ControlScale.MIN, ControlScale.fromSlider(0), tolerance)
        assertEquals(ControlScale.MAX, ControlScale.fromSlider(ControlScale.SLIDER_STEPS), tolerance)
    }

    @Test
    fun theSliderCannotBeDraggedOutOfRange() {
        assertEquals(ControlScale.MIN, ControlScale.fromSlider(-5), tolerance)
        assertEquals(ControlScale.MAX, ControlScale.fromSlider(9999), tolerance)
        assertEquals(0, ControlScale.toSlider(0.1f))
        assertEquals(ControlScale.SLIDER_STEPS, ControlScale.toSlider(99f))
    }

    /** Every position the slider can take must survive a trip to a scale and back. */
    @Test
    fun everySliderPositionRoundTrips() {
        for (progress in 0..ControlScale.SLIDER_STEPS) {
            val scale = ControlScale.fromSlider(progress)
            assertEquals(
                "slider position $progress came back as a different one",
                progress, ControlScale.toSlider(scale)
            )
        }
    }

    /**
     * One step is one percentage point, so the readout can never show a number the slider
     * cannot actually land on.
     */
    @Test
    fun eachStepIsOnePercent() {
        for (progress in 0 until ControlScale.SLIDER_STEPS) {
            val here = ControlScale.percent(ControlScale.fromSlider(progress))
            val next = ControlScale.percent(ControlScale.fromSlider(progress + 1))
            assertEquals("step $progress should move the readout by one", here + 1, next)
        }
        assertEquals(70, ControlScale.percent(ControlScale.fromSlider(0)))
        assertEquals(150, ControlScale.percent(ControlScale.fromSlider(ControlScale.SLIDER_STEPS)))
    }

    @Test
    fun oneHundredPercentIsReachable() {
        val slider = ControlScale.toSlider(ControlScale.DEFAULT)
        assertEquals(
            "the default must be exactly reachable, or a reset could not be matched",
            100, ControlScale.percent(ControlScale.fromSlider(slider))
        )
    }

    @Test
    fun theDefaultIsRecognisedDespiteFloatingPointDrift() {
        assertTrue(ControlScale.isDefault(1f))
        assertTrue(ControlScale.isDefault(1.0001f))
        assertFalse(ControlScale.isDefault(1.05f))
        assertFalse(ControlScale.isDefault(0.95f))
    }

    /**
     * 70% of a trigger's default is almost exactly Android's 48dp minimum touch target — the
     * bottom of this range is a floor with a reason, not a round number.
     */
    @Test
    fun theSmallestSizeStillLeavesATriggerRoughlyAtTheMinimumTarget() {
        val density = 3f
        val radius = ControlGeometry.triggerRadius(360f * density, density)
        val shrunkDp = (radius * 2f * ControlScale.MIN) / density
        assertTrue(
            "a trigger at the smallest size is only ${shrunkDp}dp across",
            shrunkDp >= ControlGeometry.MIN_TOUCH_TARGET_DP - 1f
        )
    }
}
