package com.dathaze.pocketpad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PlayStation skin's shoulder buttons are bars, not circles — and only those four, and only
 * on that skin.
 *
 * The drawing itself is a `Canvas` call and cannot be asserted in a JVM test, so what is checked
 * here is the part that can go quietly wrong: the hit area, the spacing guarantee the bar must
 * not break, and the fact that no other skin picks it up.
 */
class ShoulderPillTest {

    private val view by lazy {
        TestSources.read("java/com/dathaze/pocketpad/ui/ControllerView.kt")
    }

    private val radius = 40f

    // ------------------------------------------------------------- hit area

    @Test
    fun theCentreIsInside() {
        assertTrue(ControlGeometry.pillContains(0f, 0f, radius))
    }

    @Test
    fun theBarReachesFurtherSidewaysThanUpAndDown() {
        // That is the whole point of the shape.
        assertTrue(ControlGeometry.pillContains(radius * 1.05f, 0f, radius))
        assertFalse(ControlGeometry.pillContains(0f, radius * 1.05f, radius))
    }

    @Test
    fun theEndsAreRounded() {
        val halfWidth = ControlGeometry.PILL_HALF_WIDTH * radius
        val halfHeight = ControlGeometry.PILL_HALF_HEIGHT * radius
        // Dead centre of the right-hand end cap.
        assertTrue(ControlGeometry.pillContains(halfWidth - halfHeight, halfHeight * 0.95f, radius))
        // The square corner the stadium cuts off.
        assertFalse(ControlGeometry.pillContains(halfWidth, halfHeight, radius))
    }

    @Test
    fun justPastTheEndIsOutside() {
        val halfWidth = ControlGeometry.PILL_HALF_WIDTH * radius
        assertTrue(ControlGeometry.pillContains(halfWidth * 0.99f, 0f, radius))
        assertFalse(ControlGeometry.pillContains(halfWidth * 1.02f, 0f, radius))
    }

    @Test
    fun theHitAreaScalesWithTheControl() {
        // Resizing a button still works: the bar is defined in multiples of the radius.
        val small = 20f
        val big = 80f
        val atSmallEdge = ControlGeometry.PILL_HALF_WIDTH * small * 0.95f
        assertTrue(ControlGeometry.pillContains(atSmallEdge, 0f, small))
        assertTrue(ControlGeometry.pillContains(atSmallEdge, 0f, big))
        assertFalse(
            ControlGeometry.pillContains(ControlGeometry.PILL_HALF_WIDTH * big * 1.05f, 0f, big)
        )
    }

    // -------------------------------------------------- the spacing promise

    /**
     * The bar must not undo the fix that stopped LT and LB from touching.
     *
     * [ControlGeometry.triggerStep] spaces neighbouring triggers by at least
     * `2 * HIT_SLOP * radius`. A half-width above [ControlGeometry.HIT_SLOP] would reach past
     * that into the next button.
     */
    @Test
    fun theBarStaysInsideTheGapBetweenTwoTriggers() {
        assertTrue(
            "PILL_HALF_WIDTH must not exceed HIT_SLOP",
            ControlGeometry.PILL_HALF_WIDTH <= ControlGeometry.HIT_SLOP,
        )
    }

    @Test
    fun twoAdjacentTriggersStillDoNotOverlap() {
        val density = 2.75f
        val step = ControlGeometry.triggerStep(radius, density)
        val barWidth = 2f * ControlGeometry.PILL_HALF_WIDTH * radius
        assertTrue(
            "two bars $barWidth wide, $step apart, would touch",
            step > barWidth,
        )
    }

    @Test
    fun theBarIsShorterThanItIsWideSoItNeverGrowsVertically() {
        assertTrue(ControlGeometry.PILL_HALF_HEIGHT < ControlGeometry.PILL_HALF_WIDTH)
        assertTrue(
            "the bar must not be taller than the circle it replaces",
            ControlGeometry.PILL_HALF_HEIGHT <= 1f,
        )
    }

    // ------------------------------------------------------ only PS, only four

    @Test
    fun everySkinClearsTheBarBeforeChoosing() {
        assertTrue(
            "applySkin must reset pill for all four shoulders",
            view.contains("btnL1.pill = false; btnL2.pill = false; " +
                "btnR1.pill = false; btnR2.pill = false"),
        )
    }

    @Test
    fun onlyThePlayStationSkinTurnsItOn() {
        val enabling = Regex("""btn(L1|L2|R1|R2)\.pill = true""").findAll(view).count()
        assertEquals("exactly the four shoulder buttons, once each", 4, enabling)

        val psBranch = view.substringAfter("Skin.PS -> {").substringBefore("Skin.XBOX")
        assertTrue("the PS branch should be the one enabling it", psBranch.contains(".pill = true"))
    }

    @Test
    fun noFaceOrSystemButtonBecomesABar() {
        listOf("faceTop", "faceRight", "faceBottom", "faceLeft", "btnL3", "btnR3",
            "btnShare", "btnOptions", "btnHome").forEach { name ->
            assertFalse("$name must stay a circle", view.contains("$name.pill = true"))
        }
    }

    @Test
    fun theHitTestIsAUnionAndNotAReplacement() {
        // Shrinking the hit area would break the 48dp minimum target.
        assertTrue(
            view.contains("if (super.contains(x, y)) return true"),
        )
    }
}
