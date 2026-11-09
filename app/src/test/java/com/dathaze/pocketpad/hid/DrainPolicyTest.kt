package com.dathaze.pocketpad.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry-scheduling decisions, exercised directly.
 *
 * `ReportQueueTest` reproduces the drain loop to test what the *queue* decides. That is the
 * wrong shape for this: the bug here was in the scheduling itself, so a test that re-stated
 * the scheduling would only confirm its own copy. These call [DrainPolicy] as the manager
 * calls it and assert what it answers.
 *
 * The defect being guarded: the retry flag was cleared at the top of the drain loop, which a
 * new touch enters directly, so the flag went false while the delayed callback was still
 * queued. Callbacks stacked up, and — the damaging part — every touch event reached the stack
 * and spent one of the queue's bounded retry attempts. At stick-sample rates the three-attempt
 * budget was gone in milliseconds and the frame was dropped, losing exactly the input the
 * queue exists to protect.
 */
class DrainPolicyTest {

    /** Counts posts and cancellations the way the Handler would. */
    private class Scheduler {
        var posted = 0
        var pending = 0

        fun post() {
            posted++
            pending++
        }

        fun fire(policy: DrainPolicy) {
            check(pending > 0) { "nothing was scheduled" }
            pending--
            policy.onRetryFired()
        }
    }

    @Test
    fun aQuietPolicyLetsAReportGoStraightOut() {
        val p = DrainPolicy()
        assertTrue(p.mayDrainNow())
        assertFalse(p.isBackingOff)
    }

    @Test
    fun aFailureSchedulesExactlyOneRetry() {
        val p = DrainPolicy()
        val s = Scheduler()

        if (p.onRetryNeeded()) s.post()

        assertEquals(1, s.posted)
        assertTrue(p.isBackingOff)
    }

    /** The heart of it: touches arriving while a retry is pending must not post more. */
    @Test
    fun touchesArrivingDuringABackoffScheduleNothingExtra() {
        val p = DrainPolicy()
        val s = Scheduler()

        if (p.onRetryNeeded()) s.post()          // first failure
        repeat(200) {                            // a stick being moved
            if (p.mayDrainNow()) {
                // Would have reached the stack and spent an attempt.
                if (p.onRetryNeeded()) s.post()
            }
        }

        assertEquals("one callback outstanding, not 201", 1, s.posted)
        assertEquals(1, s.pending)
    }

    /**
     * The consequence that actually broke input: attempts are spent per retry window, not per
     * touch. Three attempts must survive a burst of samples.
     */
    @Test
    fun aBurstOfTouchesDoesNotExhaustTheQueuesRetryBudget() {
        val policy = DrainPolicy()
        val queue = ReportQueue(maxAttempts = 3, maxFinalAttempts = 3)
        val scheduler = Scheduler()

        // A press, then a flood of stick samples, with the stack refusing throughout.
        queue.offer(reportWith(buttons = 1))
        if (policy.mayDrainNow() && queue.peek() != null) {
            if (queue.onFailed() == ReportQueue.Outcome.RETRY && policy.onRetryNeeded()) {
                scheduler.post()
            }
        }
        repeat(300) { i ->
            queue.offer(reportWith(buttons = 1, lx = 128 + (i % 100)))
            if (policy.mayDrainNow()) {
                if (queue.onFailed() == ReportQueue.Outcome.RETRY && policy.onRetryNeeded()) {
                    scheduler.post()
                }
            }
        }

        assertEquals("the press must still be queued", 0, queue.droppedFrames)
        assertEquals("one attempt spent, not 301", 1, scheduler.posted)
        assertTrue(queue.peek() != null)
    }

    @Test
    fun onlyTheFiredCallbackClearsTheBackoff() {
        val p = DrainPolicy()
        val s = Scheduler()

        if (p.onRetryNeeded()) s.post()
        assertFalse("a plain drain must not clear it", p.mayDrainNow())

        s.fire(p)
        assertTrue("once the callback has run, sending resumes", p.mayDrainNow())
        assertFalse(p.isBackingOff)
    }

    @Test
    fun afterARetryFiresTheNextFailureMaySchedulAgain() {
        val p = DrainPolicy()
        val s = Scheduler()

        if (p.onRetryNeeded()) s.post()
        s.fire(p)
        if (p.onRetryNeeded()) s.post()

        assertEquals("two windows, two callbacks, never overlapping", 2, s.posted)
        assertEquals(1, s.pending)
    }

    @Test
    fun repeatedFailuresInsideOneWindowDoNotStack() {
        val p = DrainPolicy()
        val s = Scheduler()

        repeat(50) { if (p.onRetryNeeded()) s.post() }

        assertEquals(1, s.posted)
    }

    @Test
    fun aResetClearsTheBackoffSoTheNextLinkStartsClean() {
        val p = DrainPolicy()
        val s = Scheduler()

        if (p.onRetryNeeded()) s.post()
        p.reset()   // disconnect

        assertTrue(p.mayDrainNow())
        assertFalse(p.isBackingOff)
        if (p.onRetryNeeded()) s.post()
        assertEquals("a fresh link may schedule its own retry", 2, s.posted)
    }

    /**
     * Responsiveness is the other half of the requirement: when nothing is outstanding, a
     * report goes immediately rather than waiting out a tick.
     */
    @Test
    fun anIdleLinkSendsEveryReportWithoutDelay() {
        val p = DrainPolicy()
        var immediate = 0
        repeat(100) { if (p.mayDrainNow()) immediate++ }
        assertEquals(100, immediate)
    }

    @Test
    fun aSuccessfulWindowLeavesNothingPending() {
        val p = DrainPolicy()
        val s = Scheduler()
        if (p.onRetryNeeded()) s.post()
        s.fire(p)
        assertEquals("no callback left outstanding", 0, s.pending)
        assertTrue(p.mayDrainNow())
    }

    private fun reportWith(buttons: Int = 0, lx: Int = 128): ByteArray =
        GamepadState().apply {
            this.buttons = buttons
            this.lx = lx
        }.toReport()
}
