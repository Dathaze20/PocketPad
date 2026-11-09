package com.dathaze.pocketpad.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounded keyboard retries, and the rule that giving up never leaves a key held.
 *
 * The defect: a keyboard report the stack would not accept was re-offered every 8 ms for the
 * rest of the session, keeping a timer alive over a keystroke nobody was waiting for and
 * repeatedly waking the loop the gamepad reports share.
 */
class KeyReportQueueTest {

    private val up = HidConstants.KEY_UP
    private val down = HidConstants.KEY_DOWN
    private val none = HidConstants.KEY_NONE

    /** Stands in for the stack, draining the way `HidGamepadManager.drain()` does. */
    private class FakeLink {
        val delivered = mutableListOf<Byte>()
        var refuseEverything = false
        var refusalsLeft = 0

        fun send(key: Byte): Boolean {
            if (refuseEverything || refusalsLeft > 0) {
                if (refusalsLeft > 0) refusalsLeft--
                return false
            }
            delivered += key
            return true
        }
    }

    private fun drain(q: KeyReportQueue, link: FakeLink) {
        while (true) {
            val key = q.peek() ?: return
            if (link.send(key)) {
                q.onSent()
                continue
            }
            if (q.onFailed() == KeyReportQueue.Outcome.RETRY) return
        }
    }

    // --------------------------------------------------------- ordinary use

    @Test
    fun aKeyPressAndReleaseBothGoOut() {
        val q = KeyReportQueue(); val link = FakeLink()
        q.offer(up); drain(q, link)
        q.offer(none); drain(q, link)
        assertEquals(listOf(up, none), link.delivered)
    }

    @Test
    fun repeatsAreSuppressed() {
        val q = KeyReportQueue(); val link = FakeLink()
        q.offer(up); drain(q, link)
        repeat(5) { q.offer(up); drain(q, link) }
        assertEquals(listOf(up), link.delivered)
    }

    @Test
    fun aRefusedPressStillReachesTheHostAfterTheFingerLifts() {
        val q = KeyReportQueue(); val link = FakeLink()
        link.refusalsLeft = 1
        q.offer(up); drain(q, link)
        q.offer(none); drain(q, link)
        assertEquals("the press must not be cancelled by its own release", listOf(up, none), link.delivered)
    }

    // ------------------------------------------------------ persistent refusal

    /** The bug: this used to schedule a retry every 8 ms indefinitely. */
    @Test
    fun aPermanentlyRefusedPressIsGivenUpOn() {
        val q = KeyReportQueue(maxAttempts = 4)
        val link = FakeLink()
        link.refuseEverything = true

        q.offer(up)
        repeat(50) { drain(q, link) }

        assertTrue("the queue must stop asking", q.isEmpty)
        assertTrue(q.droppedKeys > 0)
        assertEquals(0, link.delivered.size)
    }

    @Test
    fun givingUpStopsTheRetryLoopRatherThanLoopingForever() {
        val q = KeyReportQueue(maxAttempts = 3, maxReleaseAttempts = 3)
        val link = FakeLink()
        link.refuseEverything = true

        q.offer(up)
        q.offer(none)
        var retriesRequested = 0
        repeat(100) {
            val before = q.isEmpty
            drain(q, link)
            if (!before && !q.isEmpty) retriesRequested++
        }
        assertTrue("everything must eventually be abandoned", q.isEmpty)
        assertTrue("and it must not have asked 100 times", retriesRequested < 100)
    }

    /**
     * The property that makes giving up safe. A lost press costs a keystroke; a lost release
     * leaves a TV menu scrolling on its own with nothing pressed on the phone.
     */
    @Test
    fun abandoningAPressWhileAKeyIsHeldQueuesAReleaseInstead() {
        val q = KeyReportQueue(maxAttempts = 2)
        val link = FakeLink()

        q.offer(up)
        drain(q, link)
        assertEquals("the host is now holding UP", listOf(up), link.delivered)

        // A second press is offered and permanently refused.
        link.refuseEverything = true
        q.offer(down)
        repeat(10) { drain(q, link) }

        assertEquals(
            "a release must have taken the abandoned press's place",
            none, q.peek()
        )

        link.refuseEverything = false
        drain(q, link)
        assertEquals("and it must actually be delivered", none, link.delivered.last())
        assertTrue(q.isEmpty)
    }

    @Test
    fun aReleaseIsChasedHarderThanAPress() {
        val pressQ = KeyReportQueue(maxAttempts = 3, maxReleaseAttempts = 20)
        val releaseQ = KeyReportQueue(maxAttempts = 3, maxReleaseAttempts = 20)
        val working = FakeLink()
        val refusing = FakeLink().apply { refuseEverything = true }

        // A release is only meaningful once a key is actually held, so the release queue has
        // to get one down first. (Offering a release with nothing held is a no-op — see
        // aReleaseIsPointlessWhenNothingIsHeld.)
        releaseQ.offer(up)
        drain(releaseQ, working)
        releaseQ.offer(none)

        pressQ.offer(up)
        repeat(5) { drain(pressQ, refusing); drain(releaseQ, refusing) }

        assertTrue("the press was abandoned quickly", pressQ.isEmpty)
        assertTrue("the release is still being chased", !releaseQ.isEmpty)
        assertEquals(none, releaseQ.peek())
    }

    /** Releasing when the host holds nothing is not worth a report. */
    @Test
    fun aReleaseIsPointlessWhenNothingIsHeld() {
        val q = KeyReportQueue()
        q.offer(none)
        assertTrue("nothing to release", q.isEmpty)
    }

    @Test
    fun nothingIsLeftHeldOnceEverythingIsAbandoned() {
        val q = KeyReportQueue(maxAttempts = 2, maxReleaseAttempts = 2)
        val link = FakeLink()

        q.offer(up); drain(q, link)          // host holds UP
        link.refuseEverything = true
        q.offer(down)
        repeat(40) { drain(q, link) }

        // Everything is gone, and the last thing the queue tried to send was a release.
        assertTrue(q.isEmpty)
        assertNull(q.peek())
    }

    // ------------------------------------------------------------- recovery

    @Test
    fun theQueueRecoversOnceTheStackTakesReportsAgain() {
        val q = KeyReportQueue(); val link = FakeLink()
        link.refusalsLeft = 2

        q.offer(up)
        drain(q, link)
        drain(q, link)
        drain(q, link)

        assertEquals(listOf(up), link.delivered)
        assertTrue(q.isEmpty)
        assertEquals("nothing was abandoned", 0, q.droppedKeys)
    }

    @Test
    fun keysQueuedDuringARefusalAreDeliveredInOrderAfterwards() {
        val q = KeyReportQueue(); val link = FakeLink()
        link.refuseEverything = true
        q.offer(up)
        drain(q, link)
        q.offer(none)
        q.offer(down)
        link.refuseEverything = false
        drain(q, link)

        assertEquals(listOf(up, none, down), link.delivered)
    }

    @Test
    fun aResetDropsEverythingAndForgetsWhatTheHostHeld() {
        val q = KeyReportQueue(); val link = FakeLink()
        q.offer(up); drain(q, link)
        q.reset()

        // After a reconnect the same key must be sendable again, because the new host is not
        // holding anything.
        q.offer(up)
        assertEquals(up, q.peek())
        drain(q, link)
        assertEquals(listOf(up, up), link.delivered)
    }

    @Test
    fun theQueueIsBounded() {
        val q = KeyReportQueue(capacity = 4)
        for (i in 0 until 50) q.offer(if (i % 2 == 0) up else down)
        assertTrue("got ${q.size}", q.size <= 4)
        assertTrue(q.droppedKeys > 0)
    }

    @Test
    fun anEmptyQueueHandlesFailureWithoutBlowingUp() {
        val q = KeyReportQueue()
        assertEquals(KeyReportQueue.Outcome.DROPPED, q.onFailed())
        q.onSent()
        assertNull(q.peek())
    }

    @Test
    fun thePressAndReleaseBudgetsDiffer() {
        assertNotEquals(
            KeyReportQueue.DEFAULT_MAX_ATTEMPTS,
            KeyReportQueue.DEFAULT_MAX_RELEASE_ATTEMPTS
        )
        assertTrue(
            "a release must be chased harder than a press",
            KeyReportQueue.DEFAULT_MAX_RELEASE_ATTEMPTS > KeyReportQueue.DEFAULT_MAX_ATTEMPTS
        )
    }
}
