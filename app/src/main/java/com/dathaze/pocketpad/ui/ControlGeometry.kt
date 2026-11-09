package com.dathaze.pocketpad.ui

import com.dathaze.pocketpad.hid.HidConstants
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The arithmetic behind the touch controls, kept out of [ControllerView] so it can be tested.
 *
 * These calculations decide what a host actually receives — which d-pad direction a thumb is
 * pointing, how far a stick is pushed — and they lived inside a `View` subclass, which cannot be
 * loaded in a plain JVM test. The suite could only check them by reading the file as text and
 * matching regexes against it, which verifies that the source *says* something rather than that
 * it *computes* something. Moving them here costs nothing and makes them ordinary functions with
 * ordinary tests.
 */
object ControlGeometry {

    /**
     * Fraction of the d-pad's radius treated as the centre rest position.
     *
     * A thumb sitting in the middle of the cross should mean "no direction", not whichever way
     * it happens to lean by a pixel.
     */
    const val DPAD_DEAD_ZONE = 0.18f

    /**
     * Which of the eight hat directions a touch at ([dx], [dy]) from the centre points at, or
     * [HidConstants.HAT_NEUTRAL] when it is inside the dead zone.
     *
     * [dx] is positive to the right and [dy] positive *downwards*, matching Android's screen
     * coordinates. Hat values run clockwise from 0 = up, which is what the report descriptor
     * declares.
     */
    fun hatFor(dx: Float, dy: Float, radius: Float): Int {
        val distance = hypot(dx.toDouble(), dy.toDouble())
        if (radius <= 0f || distance < radius * DPAD_DEAD_ZONE) return HidConstants.HAT_NEUTRAL
        // Measured from straight up, turning clockwise: atan2's first argument is the sideways
        // component and its second is the upward one, which is why dy is negated.
        val degrees = (Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360.0) % 360.0
        // Each direction owns 45 degrees, centred on itself, so the boundary sits at 22.5.
        return ((degrees + 22.5) / 45.0).toInt() % 8
    }

    /** True when [hat] is one of the four diagonals rather than a cardinal direction. */
    fun isDiagonal(hat: Int): Boolean = hat == 1 || hat == 3 || hat == 5 || hat == 7
}
