package com.dathaze.pocketpad.hid

import kotlin.math.abs

/**
 * Keeps each attached external controller's input separate, then folds them
 * into one combined state.
 *
 * Holding the sources apart is what makes a disconnect clean. When a pad goes
 * away its contribution can be dropped on its own, without disturbing another
 * pad that is still connected and without touching the on-screen controls,
 * which are merged in afterwards by [GamepadState.merge]. Accumulating
 * everything into a single shared state cannot do that: once a button bit is
 * OR-ed in there is no way to tell whose it was, so a controller that vanishes
 * mid-press leaves that button transmitted as held for good.
 */
class ExternalInputTracker {

    /**
     * One controller's own view of its controls.
     *
     * A d-pad arrives either as discrete key events or as a hat axis, and some
     * controllers send both. The two are tracked separately and OR-ed, so a hat
     * returning to centre can clear its own flags without wiping a direction
     * that is genuinely held down as a key.
     */
    class Source {
        val state = GamepadState()

        var keyUp = false
        var keyDown = false
        var keyLeft = false
        var keyRight = false

        var axisUp = false
        var axisDown = false
        var axisLeft = false
        var axisRight = false

        /** Recompute the hat from both d-pad representations. */
        fun applyDpad() {
            state.hat = hatFor(
                up = keyUp || axisUp,
                right = keyRight || axisRight,
                down = keyDown || axisDown,
                left = keyLeft || axisLeft
            )
        }
    }

    private val sources = LinkedHashMap<Int, Source>()

    /**
     * Controllers that have gone away. Input events are delivered through a
     * queue, so one posted just before a disconnect can still arrive after it.
     * Without this, that late event would re-create the source through
     * [source] and strand its button as held forever — nothing else will
     * remove it, because the device is already gone.
     */
    private val detached = HashSet<Int>()

    val sourceCount: Int get() = sources.size

    fun sourceIds(): Set<Int> = sources.keys.toSet()

    fun has(id: Int): Boolean = sources.containsKey(id)

    /** The state belonging to [id], created on first use. */
    fun source(id: Int): Source = sources.getOrPut(id) { Source() }

    /**
     * The state for [id], or null if that controller has disconnected and has
     * not come back. Input handlers use this so a late event cannot revive a
     * controller that is gone.
     */
    fun sourceIfAttached(id: Int): Source? =
        if (id in detached) null else source(id)

    /** A controller with this id is present again, so accept its input. */
    fun markAttached(id: Int) {
        detached.remove(id)
    }

    fun isDetached(id: Int): Boolean = id in detached

    /** Forget [id]'s input entirely; true if it was being tracked. */
    fun remove(id: Int): Boolean {
        detached.add(id)
        return sources.remove(id) != null
    }

    fun clear() {
        sources.clear()
        detached.clear()
    }

    /**
     * Fold every tracked source into [out]: buttons are OR-ed, the hat takes
     * the first source actually pointing somewhere, each axis takes whichever
     * source is deflected furthest from centre, and each trigger takes the
     * largest value.
     *
     * With nothing tracked this leaves [out] neutral, which is precisely how
     * the last controller disconnecting releases whatever it was holding.
     */
    fun combineInto(out: GamepadState) {
        out.reset()
        for (source in sources.values) {
            val s = source.state
            out.buttons = out.buttons or s.buttons
            if (out.hat == HidConstants.HAT_NEUTRAL) out.hat = s.hat
            out.lx = moreDeflected(out.lx, s.lx)
            out.ly = moreDeflected(out.ly, s.ly)
            out.rx = moreDeflected(out.rx, s.rx)
            out.ry = moreDeflected(out.ry, s.ry)
            out.lt = maxOf(out.lt, s.lt)
            out.rt = maxOf(out.rt, s.rt)
        }
    }

    companion object {
        /**
         * Key for the DualShock 3 USB driver, which is not an Android input
         * device and so has no device id of its own. Int.MIN_VALUE cannot
         * collide with a real one — Android uses -1 for virtual devices.
         */
        const val SOURCE_DS3 = Int.MIN_VALUE

        /** Hat value for a direction, 0 = up and clockwise from there. */
        fun hatFor(up: Boolean, right: Boolean, down: Boolean, left: Boolean): Int = when {
            up && right -> 1
            right && down -> 3
            down && left -> 5
            left && up -> 7
            up -> 0
            right -> 2
            down -> 4
            left -> 6
            else -> HidConstants.HAT_NEUTRAL
        }

        private fun moreDeflected(current: Int, candidate: Int): Int =
            if (abs(candidate - 128) > abs(current - 128)) candidate else current
    }
}
