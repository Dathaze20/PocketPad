package com.dathaze.pocketpad.ui

import kotlin.math.hypot

/**
 * The two controls that make the touch style feel like a phone game rather than a pad.
 *
 * Both are deliberately confined to this file and to [ControllerView.Skin.TOUCH]. The Xbox,
 * PlayStation, SNES, NES and Game Boy styles keep their fixed-origin sticks and have no camera
 * surface, so nothing here can change how they behave.
 */
object TouchModeGeometry {

    /**
     * A movement stick whose centre is wherever the thumb landed.
     *
     * This is the single biggest difference from the established styles, whose sticks measure
     * from a fixed drawn centre — put your thumb down off-centre there and the stick is already
     * deflected. On a phone you do not look at your thumb, so the stick has to come to it.
     *
     * Once the thumb travels past the stick's reach the origin follows it, trailing at exactly
     * full deflection. Without that, sliding a thumb across the screen pins the stick at the
     * edge and walking in a new direction means lifting off first.
     */
    object FloatingStick {

        /** An origin after the thumb has possibly dragged it. */
        class Origin(val x: Float, val y: Float)

        /**
         * Where the stick's centre should now be, given a thumb at ([touchX], [touchY]).
         *
         * Returns the original centre while the thumb is inside [maxTravel], and a centre
         * dragged along behind it once it is not.
         */
        fun follow(
            originX: Float,
            originY: Float,
            touchX: Float,
            touchY: Float,
            maxTravel: Float,
        ): Origin {
            if (maxTravel <= 0f) return Origin(touchX, touchY)
            val dx = touchX - originX
            val dy = touchY - originY
            val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (distance <= maxTravel) return Origin(originX, originY)
            // Keep the origin exactly maxTravel behind the thumb, along the same line.
            val pull = (distance - maxTravel) / distance
            return Origin(originX + dx * pull, originY + dy * pull)
        }
    }

    /**
     * Turning the camera by dragging, translated into right-stick deflection.
     *
     * **This cannot be an exact reproduction, and the reason is the protocol, not the code.**
     * A touch drag is a *displacement* control: the game is told the finger moved so many
     * pixels and turns by a proportional angle, so a flick of a given length always turns you
     * the same amount. A gamepad stick is a *rate* control: it reports how far it is pushed and
     * the game integrates that over time, so how far you turn depends on how long you hold it.
     * There is no HID gamepad input that says "rotate this much, now", so no mapping between
     * the two can be faithful.
     *
     * What this does instead is turn how fast the finger is moving into how far the stick is
     * pushed, which is the closest usable equivalent: flick quickly and the camera whips round,
     * move slowly and it eases. It is good enough to play with and it is not the same thing.
     * Small precise adjustments in particular feel softer than they do on the phone.
     */
    object CameraDrag {

        /** Axis units per pixel of finger movement between samples, at the default setting. */
        const val DEFAULT_SENSITIVITY = 0.65f

        /** Full-scale deflection either side of centre. */
        const val MAX_DEFLECTION = 127

        /**
         * How long the stick holds its last deflection before easing back to centre.
         *
         * A finger held still mid-drag sends no move events at all, which would otherwise read
         * as "still turning at the last speed" for ever. Recentring after a short gap makes a
         * stationary finger mean a stationary camera.
         */
        const val IDLE_RECENTRE_MS = 70L

        /**
         * The axis value for a finger that moved [deltaPx] since the previous sample.
         *
         * Positive [deltaPx] pushes the axis above centre, which for both camera axes means
         * right and down — matching the screen's own coordinates and the descriptor's.
         */
        fun axisFor(deltaPx: Float, sensitivity: Float = DEFAULT_SENSITIVITY): Int {
            val scaled = deltaPx * sensitivity
            val clamped = scaled.coerceIn(-MAX_DEFLECTION.toFloat(), MAX_DEFLECTION.toFloat())
            return (CENTRE + Math.round(clamped)).coerceIn(0, 255)
        }

        /** True when a touch has moved far enough to be a drag rather than a tap. */
        fun isDrag(totalDx: Float, totalDy: Float, slopPx: Float): Boolean =
            hypot(totalDx.toDouble(), totalDy.toDouble()) > slopPx

        private const val CENTRE = 128
    }
}
