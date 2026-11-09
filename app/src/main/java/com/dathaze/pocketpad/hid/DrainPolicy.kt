package com.dathaze.pocketpad.hid

/**
 * Decides when the send loop may try the Bluetooth stack again.
 *
 * ### The bug this exists to stop
 *
 * The retry flag used to be cleared at the top of the drain loop, on the assumption that
 * reaching the loop meant the scheduled callback had fired. It did not: a new touch calls the
 * loop directly, so the flag was cleared while the delayed callback was still queued. Two
 * things followed.
 *
 * Overlapping callbacks piled up — each failed drain posted another one while the earlier ones
 * were still pending.
 *
 * Far worse, every touch event reached the stack and so consumed one of the queue's bounded
 * retry attempts. The 8 ms spacing was supposed to make that budget last; instead the budget
 * was spent at touch-sample rate. A stick being moved produces samples in the low hundreds per
 * second, so three attempts were gone in around 25 ms and the frame was dropped — exactly the
 * loss of input the queue was built to prevent, arriving by a different route.
 *
 * ### The rule
 *
 * There is at most one scheduled retry, and while one is outstanding nothing else touches the
 * stack. Reports that arrive during that window are still queued — [ReportQueue] folds analog
 * samples into the pending frame — they just do not get their own attempt. When nothing is
 * outstanding, a report is sent immediately, so the common case keeps its latency.
 *
 * Not thread-safe; used from the main thread only.
 */
class DrainPolicy {

    /** True while a delayed retry is outstanding. */
    var isBackingOff: Boolean = false
        private set

    /**
     * A report was just queued. Returns whether to try the stack now.
     *
     * False while backing off: the report is already queued, and spending an attempt on it
     * early is what used to exhaust the budget.
     */
    fun mayDrainNow(): Boolean = !isBackingOff

    /**
     * The queue asked for a retry after a refusal. Returns whether a *new* delayed callback
     * has to be posted — false when one is already outstanding, which is what stops them
     * stacking up.
     */
    fun onRetryNeeded(): Boolean {
        if (isBackingOff) return false
        isBackingOff = true
        return true
    }

    /** The delayed callback fired. Only this clears the flag. */
    fun onRetryFired() {
        isBackingOff = false
    }

    /** Link gone, or shutting down. */
    fun reset() {
        isBackingOff = false
    }
}
