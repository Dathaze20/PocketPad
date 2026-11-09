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

    // ------------------------------------------------------------- hit testing

    /**
     * How far outside its drawn edge a control still answers to a touch.
     *
     * Thumbs are imprecise and the drawn circle is smaller than the area a player aims at, so
     * a little generosity helps. It is also what makes overlap possible, which is why
     * [hitScore] exists rather than a plain "is it inside?" test.
     */
    const val HIT_SLOP = 1.15f

    /**
     * How close a touch is to a control, as a fraction of that control's reach.
     *
     * Zero at the centre, 1 at the edge of its slop, above 1 outside it. Dividing by the
     * control's own reach is the point: it lets a small button a thumb is sitting on beat a
     * large one whose outer edge happens to stretch over the same pixel.
     *
     * Selecting by this instead of by list order fixes a real failure. Controls used to be
     * matched with `firstOrNull`, and the sticks come before the face buttons, so a face button
     * dragged near a stick in the layout editor became impossible to press — drawn plainly on
     * screen, clearly being touched, and silently ignored.
     */
    fun hitScore(dx: Float, dy: Float, radius: Float): Float {
        if (radius <= 0f) return Float.MAX_VALUE
        return (hypot(dx.toDouble(), dy.toDouble()) / (radius * HIT_SLOP)).toFloat()
    }

    /** Whether a [hitScore] lands on the control at all. */
    fun isHit(score: Float): Boolean = score <= 1f

    /**
     * Whether two controls' touch areas intersect, so the layout editor can say so.
     *
     * Overlap is no longer fatal now that the nearest control wins, but it still means one of
     * the two is harder to hit than it looks, which is worth telling the user about.
     */
    fun overlaps(
        ax: Float, ay: Float, ar: Float,
        bx: Float, by: Float, br: Float,
    ): Boolean {
        if (ar <= 0f || br <= 0f) return false
        val gap = hypot((ax - bx).toDouble(), (ay - by).toDouble())
        return gap < (ar + br) * HIT_SLOP
    }

    // ------------------------------------------------------------------ sticks

    /** Fraction of a stick's radius the thumb can travel before the axis reads full scale. */
    const val STICK_TRAVEL = 0.82f

    /**
     * Fraction of that travel treated as centre.
     *
     * There was no dead zone at all, so a thumb resting on the stick produced a trickle of
     * small axis changes — drift in game, and a stream of reports the link did not need.
     * Output is rescaled across what remains, so leaving the dead zone starts from zero rather
     * than jumping.
     */
    const val STICK_DEAD_ZONE = 0.10f

    /**
     * Where a stick is pushed and what that should put in the report.
     *
     * [x] and [y] are 0..255 with 128 centred, matching the descriptor. [knobX] and [knobY] are
     * the drawn thumb position in pixels, clamped to the stick's travel — kept separate from
     * the axis values so the dead zone never makes the knob look stuck.
     */
    class StickReading(val x: Int, val y: Int, val knobX: Float, val knobY: Float)

    fun stickReading(dx: Float, dy: Float, radius: Float): StickReading {
        val travel = radius * STICK_TRAVEL
        if (travel <= 0f) return StickReading(CENTRE, CENTRE, 0f, 0f)

        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance <= 0f) return StickReading(CENTRE, CENTRE, 0f, 0f)

        val scale = if (distance > travel) travel / distance else 1f
        val knobX = dx * scale
        val knobY = dy * scale

        val dead = travel * STICK_DEAD_ZONE
        val magnitude = minOf(distance, travel)
        if (magnitude <= dead) return StickReading(CENTRE, CENTRE, knobX, knobY)

        val outward = ((magnitude - dead) / (travel - dead)).coerceIn(0f, 1f)
        val unitX = dx / distance
        val unitY = dy / distance
        return StickReading(
            x = axisValue(unitX * outward),
            y = axisValue(unitY * outward),
            knobX = knobX,
            knobY = knobY,
        )
    }

    private fun axisValue(fraction: Float): Int =
        (CENTRE + fraction * 127f).toDouble().let { Math.round(it).toInt() }.coerceIn(0, 255)

    // ---------------------------------------------------------------- triggers

    /** How far the finger slides, in multiples of the button's radius, to ease a trigger off. */
    const val TRIGGER_TRAVEL = 2.5f

    /**
     * The analog value for a trigger whose finger has slid [dragY] pixels from where it landed.
     *
     * Pressing gives full scale, so a tap or a hold behaves exactly as it always has — the
     * common case is untouched. Sliding *upward*, away from the button, eases the trigger off
     * toward zero, which is the only way to feather a throttle on a flat screen. Sliding back
     * down returns to full.
     */
    fun triggerValue(dragY: Float, radius: Float): Int {
        if (radius <= 0f) return 255
        val travel = radius * TRIGGER_TRAVEL
        if (travel <= 0f) return 255
        return Math.round(255f + (dragY / travel) * 255f).coerceIn(0, 255)
    }

    private const val CENTRE = 128
}
