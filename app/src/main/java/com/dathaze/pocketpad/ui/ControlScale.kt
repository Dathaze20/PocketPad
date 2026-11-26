package com.dathaze.pocketpad.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How much bigger or smaller the user has made one control, and the arithmetic that connects
 * that to the slider in the layout editor.
 *
 * Every default size in this app is derived from the screen, which is right for a default and
 * wrong as a final answer: thumbs differ, and so do the games people play. This lets one control
 * be scaled on its own, between [MIN] and [MAX], without disturbing any other.
 *
 * The bounds are deliberately modest. Below about 70% a button stops being a reliable target —
 * 70% of a trigger's new default is almost exactly Android's 48dp minimum — and above 150% a
 * control starts swallowing its neighbours, which the editor would then warn about on save
 * anyway. Anything outside is clamped rather than refused, so a stored value from a future
 * version with wider bounds still loads as something sensible instead of being dropped.
 */
object ControlScale {

    const val MIN = 0.70f
    const val MAX = 1.50f
    const val DEFAULT = 1.00f

    /**
     * Slider positions between [MIN] and [MAX].
     *
     * 80 steps over a range of 0.80 is exactly one percentage point per step, so the readout
     * never shows a number the slider cannot actually land on.
     */
    const val SLIDER_STEPS = 80

    fun clamp(scale: Float): Float = scale.coerceIn(MIN, MAX)

    /** True when this control is still at its layout's own size. */
    fun isDefault(scale: Float): Boolean = abs(scale - DEFAULT) < 0.005f

    /** The scale a slider at [progress] means. */
    fun fromSlider(progress: Int): Float =
        clamp(MIN + (MAX - MIN) * progress.coerceIn(0, SLIDER_STEPS) / SLIDER_STEPS)

    /** Where the slider sits for a given [scale]. */
    fun toSlider(scale: Float): Int =
        (((clamp(scale) - MIN) / (MAX - MIN)) * SLIDER_STEPS).roundToInt().coerceIn(0, SLIDER_STEPS)

    /** The number shown beside the slider, as a whole percentage. */
    fun percent(scale: Float): Int = (clamp(scale) * 100f).roundToInt()
}
