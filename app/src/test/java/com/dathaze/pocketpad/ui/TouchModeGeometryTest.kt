package com.dathaze.pocketpad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The floating movement stick and the camera surface.
 *
 * The camera tests pin an *approximation* on purpose. Turning the camera by dragging cannot be
 * reproduced faithfully through a gamepad — a drag is a displacement and a stick is a rate —
 * so what is tested is that the approximation behaves sensibly and safely, not that it matches
 * a phone. See `TouchModeGeometry.CameraDrag` for why no mapping could.
 */
class TouchModeGeometryTest {

    private val stick = TouchModeGeometry.FloatingStick
    private val camera = TouchModeGeometry.CameraDrag

    // ------------------------------------------------------- floating stick

    @Test
    fun theStickStaysPutWhileTheThumbIsWithinReach() {
        val o = stick.follow(originX = 100f, originY = 100f, touchX = 140f, touchY = 100f, maxTravel = 80f)
        assertEquals(100f, o.x, 1e-3f)
        assertEquals(100f, o.y, 1e-3f)
    }

    @Test
    fun theStickFollowsOnceTheThumbOutrunsIt() {
        // Thumb 200 right of an origin that can only reach 80: the origin should end up
        // exactly 80 behind it, i.e. at 120.
        val o = stick.follow(100f, 100f, 300f, 100f, 80f)
        assertEquals(220f, o.x, 1e-3f)
        assertEquals(100f, o.y, 1e-3f)
    }

    @Test
    fun theDraggedOriginStaysExactlyFullTravelBehindTheThumb() {
        val o = stick.follow(0f, 0f, 300f, 400f, 100f)   // thumb 500 away on a 3-4-5 triangle
        val gap = hypot((300f - o.x).toDouble(), (400f - o.y).toDouble())
        assertEquals("still at full deflection, not beyond it", 100.0, gap, 1e-3)
    }

    /**
     * The reason the origin follows at all: without it, sliding a thumb across the screen pins
     * the stick at the edge, and turning around means lifting off and putting it down again.
     */
    @Test
    fun walkingAcrossTheScreenKeepsFullDeflectionRatherThanSticking() {
        var ox = 100f
        var oy = 100f
        for (x in 100..600 step 25) {
            val o = stick.follow(ox, oy, x.toFloat(), 100f, 60f)
            ox = o.x; oy = o.y
        }
        val reading = ControlGeometry.stickReading(600f - ox, 100f - oy, 60f / ControlGeometry.STICK_TRAVEL)
        assertTrue("should still read hard right, got ${reading.x}", reading.x > 240)
    }

    @Test
    fun aStickWithNoTravelSnapsToTheThumb() {
        val o = stick.follow(0f, 0f, 50f, 60f, 0f)
        assertEquals(50f, o.x, 1e-3f)
        assertEquals(60f, o.y, 1e-3f)
    }

    @Test
    fun theOriginIsUnchangedWhenTheThumbHasNotMoved() {
        val o = stick.follow(42f, 99f, 42f, 99f, 70f)
        assertEquals(42f, o.x, 1e-3f)
        assertEquals(99f, o.y, 1e-3f)
    }

    // -------------------------------------------------------- camera surface

    @Test
    fun aStationaryFingerLeavesTheCameraCentred() {
        assertEquals(128, camera.axisFor(0f))
    }

    @Test
    fun draggingRightAndDownPushesTheAxesAboveCentre() {
        assertTrue(camera.axisFor(20f) > 128)
        assertTrue("and left/up below", camera.axisFor(-20f) < 128)
    }

    @Test
    fun aFasterDragPushesTheStickFurther() {
        assertTrue(camera.axisFor(40f) > camera.axisFor(10f))
    }

    @Test
    fun theCameraAxisNeverLeavesTheDeclaredRange() {
        for (delta in -5000..5000 step 37) {
            val v = camera.axisFor(delta.toFloat())
            assertTrue("delta=$delta gave $v", v in 0..255)
        }
    }

    @Test
    fun aViolentFlickSaturatesRatherThanWrappingAround() {
        assertEquals(255, camera.axisFor(100_000f))
        assertEquals(1, camera.axisFor(-100_000f))
    }

    @Test
    fun sensitivityScalesTheResult() {
        val slow = camera.axisFor(20f, sensitivity = 0.2f)
        val fast = camera.axisFor(20f, sensitivity = 1.2f)
        assertTrue(fast > slow)
        assertTrue(slow > 128)
    }

    @Test
    fun aTapIsNotMistakenForADrag() {
        assertFalse("a few pixels of wobble is a tap", camera.isDrag(3f, 4f, 20f))
        assertTrue("a real swipe is a drag", camera.isDrag(60f, 10f, 20f))
    }

    @Test
    fun theRecentreDelayIsShortEnoughToFeelLikeStopping() {
        // A finger held still sends no move events, so without this the camera would keep
        // turning at the last speed indefinitely.
        assertTrue(camera.IDLE_RECENTRE_MS in 30..200)
    }

    @Test
    fun theDefaultSensitivityIsUsable() {
        // A typical fast swipe step is tens of pixels per event; that should produce real
        // deflection but not instantly saturate.
        val typical = camera.axisFor(30f)
        assertTrue("got $typical", typical in 140..230)
    }
}
