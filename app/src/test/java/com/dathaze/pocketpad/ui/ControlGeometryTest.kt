package com.dathaze.pocketpad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hit testing, stick dead zone and analog triggers.
 *
 * All three were defects the audit found and none of them could be tested before, because the
 * arithmetic lived inside a `View` subclass.
 */
class ControlGeometryTest {

    // ----------------------------------------------------------- hit testing

    /**
     * The failure this replaced: controls were matched with `firstOrNull`, and the sticks come
     * before the face buttons in the list. A face button dragged near a stick in the layout
     * editor was drawn normally, was clearly being touched, and could never be pressed.
     */
    @Test
    fun theNearerControlWinsWhenTwoOverlap() {
        // A big stick centred at 0, a small face button at 150 — their slop areas overlap.
        val stickScore = ControlGeometry.hitScore(140f - 0f, 0f, 160f)
        val buttonScore = ControlGeometry.hitScore(140f - 150f, 0f, 60f)

        assertTrue("the touch is inside the stick's reach", ControlGeometry.isHit(stickScore))
        assertTrue("and inside the button's", ControlGeometry.isHit(buttonScore))
        assertTrue(
            "but it is nearer the button, so the button must win",
            buttonScore < stickScore
        )
    }

    @Test
    fun scoreIsZeroAtTheCentreAndOneAtTheEdgeOfTheSlop() {
        assertEquals(0f, ControlGeometry.hitScore(0f, 0f, 50f), 1e-4f)
        assertEquals(1f, ControlGeometry.hitScore(50f * ControlGeometry.HIT_SLOP, 0f, 50f), 1e-4f)
    }

    @Test
    fun aTouchOutsideTheSlopIsNotAHit() {
        val justOutside = ControlGeometry.hitScore(50f * ControlGeometry.HIT_SLOP + 1f, 0f, 50f)
        assertFalse(ControlGeometry.isHit(justOutside))
    }

    @Test
    fun theScoreIsRelativeSoASmallButtonCanBeatABigOneFromFurtherAway() {
        // 30 px from the centre of a 40 px button beats 30 px from the centre of a 200 px one,
        // even though the absolute distance is identical.
        val small = ControlGeometry.hitScore(30f, 0f, 40f)
        val large = ControlGeometry.hitScore(30f, 0f, 200f)
        assertTrue(small > large)
        // …and at the same *fraction* of reach they tie, which is what makes it fair.
        assertEquals(
            ControlGeometry.hitScore(20f, 0f, 40f),
            ControlGeometry.hitScore(100f, 0f, 200f),
            1e-4f
        )
    }

    @Test
    fun aControlWithNoSizeIsNeverHit() {
        assertFalse(ControlGeometry.isHit(ControlGeometry.hitScore(0f, 0f, 0f)))
    }

    @Test
    fun overlapIsDetectedOnlyWhenTheTouchAreasActuallyMeet() {
        assertTrue(
            "touching circles",
            ControlGeometry.overlaps(0f, 0f, 60f, 100f, 0f, 60f)
        )
        assertFalse(
            "well apart",
            ControlGeometry.overlaps(0f, 0f, 60f, 400f, 0f, 60f)
        )
        assertFalse("a zero-size control cannot overlap", ControlGeometry.overlaps(0f, 0f, 0f, 1f, 0f, 60f))
    }

    @Test
    fun theStockLayoutSpacingIsNotReportedAsOverlapping() {
        // The PS landscape face cluster, as laid out today: four buttons of radius ~97 at
        // ±177 from the cluster centre. These must not trip the warning.
        val r = 96.8f
        val off = 177.5f
        assertFalse(
            "adjacent face buttons",
            ControlGeometry.overlaps(0f, -off, r, off, 0f, r)
        )
    }

    // --------------------------------------------------------- stick dead zone

    @Test
    fun aCentredThumbReadsAsCentred() {
        val reading = ControlGeometry.stickReading(0f, 0f, 100f)
        assertEquals(128, reading.x)
        assertEquals(128, reading.y)
    }

    /** The drift this fixes: any touch off dead centre used to deflect the axis immediately. */
    @Test
    fun smallMovementInsideTheDeadZoneDoesNotMoveTheAxis() {
        val travel = 100f * ControlGeometry.STICK_TRAVEL
        val inside = travel * ControlGeometry.STICK_DEAD_ZONE * 0.5f
        val reading = ControlGeometry.stickReading(inside, 0f, 100f)
        assertEquals(128, reading.x)
        assertEquals(128, reading.y)
    }

    @Test
    fun theKnobStillFollowsTheThumbInsideTheDeadZone() {
        // The axis holds at centre but the drawn stick must not look stuck.
        val reading = ControlGeometry.stickReading(4f, 0f, 100f)
        assertEquals(128, reading.x)
        assertEquals(4f, reading.knobX, 1e-4f)
    }

    @Test
    fun leavingTheDeadZoneStartsFromZeroRatherThanJumping() {
        val travel = 100f * ControlGeometry.STICK_TRAVEL
        val dead = travel * ControlGeometry.STICK_DEAD_ZONE
        val justOutside = ControlGeometry.stickReading(dead + 0.5f, 0f, 100f)
        assertTrue(
            "output should ease in, not snap: got ${justOutside.x}",
            justOutside.x in 128..132
        )
    }

    /**
     * Centre is 128 and travel is ±127, so one end lands on 255 and the other on 1.
     *
     * That asymmetry is deliberate and unchanged from before: deflecting the same distance
     * either side of centre matters more than touching the very end of the range, and a host
     * reads these as offsets from centre. Stretching left to 0 would make it travel 128 while
     * right travelled 127.
     */
    @Test
    fun fullTravelReachesBothEndsSymmetricallyAboutCentre() {
        val far = 1000f
        assertEquals("pushed fully right", 255, ControlGeometry.stickReading(far, 0f, 100f).x)
        assertEquals("pushed fully left", 1, ControlGeometry.stickReading(-far, 0f, 100f).x)
        assertEquals("pushed fully down", 255, ControlGeometry.stickReading(0f, far, 100f).y)
        assertEquals("pushed fully up", 1, ControlGeometry.stickReading(0f, -far, 100f).y)

        val right = ControlGeometry.stickReading(far, 0f, 100f).x - 128
        val left = 128 - ControlGeometry.stickReading(-far, 0f, 100f).x
        assertEquals("equal deflection either side", right, left)
    }

    /** HID declares Y increasing downward, so pushing up must read below centre. */
    @Test
    fun pushingUpReadsBelowCentre() {
        assertTrue(ControlGeometry.stickReading(0f, -80f, 100f).y < 128)
        assertTrue(ControlGeometry.stickReading(0f, 80f, 100f).y > 128)
    }

    @Test
    fun theKnobNeverLeavesTheSticksTravel() {
        val travel = 100f * ControlGeometry.STICK_TRAVEL
        val reading = ControlGeometry.stickReading(500f, 500f, 100f)
        val distance = Math.hypot(reading.knobX.toDouble(), reading.knobY.toDouble())
        assertTrue("knob at $distance, travel $travel", distance <= travel + 1e-3)
    }

    @Test
    fun diagonalPushIsNotClippedToASquare() {
        // Both axes should move together and neither should saturate before the other.
        val reading = ControlGeometry.stickReading(500f, 500f, 100f)
        assertEquals(reading.x, reading.y)
        assertTrue(reading.x > 128)
    }

    @Test
    fun aStickWithNoSizeIsSafe() {
        val reading = ControlGeometry.stickReading(10f, 10f, 0f)
        assertEquals(128, reading.x)
        assertEquals(128, reading.y)
    }

    @Test
    fun axesStayInsideTheDeclaredRange() {
        for (dx in -400..400 step 7) {
            for (dy in -400..400 step 37) {
                val r = ControlGeometry.stickReading(dx.toFloat(), dy.toFloat(), 100f)
                assertTrue("x=${r.x}", r.x in 0..255)
                assertTrue("y=${r.y}", r.y in 0..255)
            }
        }
    }

    // ---------------------------------------------------------- analog trigger

    /** The guarantee that matters: an ordinary tap or hold is exactly what it always was. */
    @Test
    fun aTriggerThatIsSimplyPressedIsFullyPulled() {
        assertEquals(255, ControlGeometry.triggerValue(0f, 40f))
    }

    @Test
    fun slidingUpEasesTheTriggerOff() {
        val radius = 40f
        val travel = radius * ControlGeometry.TRIGGER_TRAVEL
        val half = ControlGeometry.triggerValue(-travel / 2f, radius)
        assertTrue("about halfway, got $half", half in 120..136)
        assertEquals("fully released at full travel", 0, ControlGeometry.triggerValue(-travel, radius))
    }

    @Test
    fun slidingBackDownReturnsToFull() {
        val radius = 40f
        assertEquals(255, ControlGeometry.triggerValue(0f, radius))
        assertEquals("pushing past the start stays at full", 255, ControlGeometry.triggerValue(80f, radius))
    }

    @Test
    fun triggerValueIsAlwaysInRange() {
        for (dy in -1000..1000 step 13) {
            val v = ControlGeometry.triggerValue(dy.toFloat(), 40f)
            assertTrue("dy=$dy gave $v", v in 0..255)
        }
    }

    @Test
    fun theTriggerIsMonotonic() {
        var previous = -1
        for (dy in -100..0) {
            val v = ControlGeometry.triggerValue(dy.toFloat(), 40f)
            assertTrue("value must not go backwards as the finger returns", v >= previous)
            previous = v
        }
    }

    @Test
    fun aTriggerWithNoSizeStillWorksAsAPlainButton() {
        assertEquals(255, ControlGeometry.triggerValue(-50f, 0f))
    }

    @Test
    fun theDeadZoneAndTriggerTravelAreSaneValues() {
        assertTrue(ControlGeometry.STICK_DEAD_ZONE > 0f && ControlGeometry.STICK_DEAD_ZONE < 0.3f)
        assertTrue(ControlGeometry.STICK_TRAVEL > 0.5f && ControlGeometry.STICK_TRAVEL <= 1f)
        assertTrue(ControlGeometry.HIT_SLOP >= 1f)
        assertNotEquals(0f, ControlGeometry.TRIGGER_TRAVEL)
    }
}
