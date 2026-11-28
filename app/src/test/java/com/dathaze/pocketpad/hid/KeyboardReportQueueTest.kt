package com.dathaze.pocketpad.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC Keys' send queue: ordering, bounded retries, and the rule that matters most — **nothing
 * ever leaves the host holding a key nobody is pressing**.
 *
 * A stuck key on a PC is far worse than a dropped one. A dropped press costs an input; a stuck
 * `Z` types forever, and the user cannot clear it from the phone because the phone thinks it
 * already sent the release.
 */
class KeyboardReportQueueTest {

    private val none = HidConstants.neutralKeyboardReport()
    private fun report(vararg keys: Byte) = KeyboardReport.build(0, keys.toList())

    private val z = report(PcKeyMap.KEY_Z)
    private val a = report(PcKeyMap.KEY_A)
    private val za = report(PcKeyMap.KEY_Z, PcKeyMap.KEY_A)

    /** Stands in for the stack, draining the way `HidGamepadManager.drain()` does. */
    private class FakeLink {
        val delivered = mutableListOf<ByteArray>()
        var refuseEverything = false
        var refusalsLeft = 0

        fun send(report: ByteArray): Boolean {
            if (refuseEverything || refusalsLeft > 0) {
                if (refusalsLeft > 0) refusalsLeft--
                return false
            }
            delivered += report.copyOf()
            return true
        }
    }

    private fun drain(q: KeyboardReportQueue, link: FakeLink) {
        while (true) {
            val report = q.peek() ?: return
            if (link.send(report)) {
                q.onSent()
                continue
            }
            if (q.onFailed() == KeyboardReportQueue.Outcome.RETRY) return
        }
    }

    private fun keysDelivered(link: FakeLink) = link.delivered.map { KeyboardReport.keysOf(it) }

    // --------------------------------------------------------- ordinary use

    @Test
    fun aPressAndReleaseBothGoOut() {
        val q = KeyboardReportQueue(); val link = FakeLink()
        q.offer(z); drain(q, link)
        q.offer(none); drain(q, link)
        assertEquals(listOf(listOf(PcKeyMap.KEY_Z), emptyList<Byte>()), keysDelivered(link))
    }

    @Test
    fun repeatsAreSuppressed() {
        val q = KeyboardReportQueue(); val link = FakeLink()
        q.offer(z); drain(q, link)
        repeat(5) { q.offer(z); drain(q, link) }
        assertEquals(1, link.delivered.size)
    }

    @Test
    fun twoKeysHeldTogetherAreOneReport() {
        val q = KeyboardReportQueue(); val link = FakeLink()
        q.offer(z); drain(q, link)
        q.offer(za); drain(q, link)
        assertEquals(
            listOf(listOf(PcKeyMap.KEY_Z), listOf(PcKeyMap.KEY_Z, PcKeyMap.KEY_A)),
            keysDelivered(link),
        )
    }

    @Test
    fun releasingOneKeyLeavesTheOtherHeldOnTheWire() {
        val q = KeyboardReportQueue(); val link = FakeLink()
        q.offer(za); drain(q, link)
        q.offer(a); drain(q, link)
        assertEquals(
            "the second report must still carry A",
            listOf(listOf(PcKeyMap.KEY_Z, PcKeyMap.KEY_A), listOf(PcKeyMap.KEY_A)),
            keysDelivered(link),
        )
    }

    @Test
    fun sixKeysSurviveTheQueueIntact() {
        val six = KeyboardReport.build(
            0, listOf<Byte>(0x04, 0x05, 0x06, 0x07, 0x08, 0x09),
        )
        val q = KeyboardReportQueue(); val link = FakeLink()
        q.offer(six); drain(q, link)
        assertEquals(6, KeyboardReport.keysOf(link.delivered.single()).size)
    }

    @Test
    fun distinctTransitionsAreNotCoalesced() {
        // Unlike a stick position, no key state makes an earlier one irrelevant: a press and
        // its release inside one frame must both reach the host or the tap vanishes.
        val q = KeyboardReportQueue(); val link = FakeLink()
        q.offer(z)
        q.offer(none)
        assertEquals(2, q.size)
        drain(q, link)
        assertEquals(2, link.delivered.size)
    }

    @Test
    fun orderIsPreserved() {
        val q = KeyboardReportQueue(); val link = FakeLink()
        q.offer(z); q.offer(za); q.offer(a); q.offer(none)
        drain(q, link)
        assertEquals(
            listOf(
                listOf(PcKeyMap.KEY_Z),
                listOf(PcKeyMap.KEY_Z, PcKeyMap.KEY_A),
                listOf(PcKeyMap.KEY_A),
                emptyList(),
            ),
            keysDelivered(link),
        )
    }

    // ------------------------------------------------------------- refusals

    @Test
    fun aRefusedPressStillReachesTheHostAfterTheFingerLifts() {
        val q = KeyboardReportQueue(); val link = FakeLink()
        link.refusalsLeft = 1
        q.offer(z); drain(q, link)      // refused, still queued
        q.offer(none); drain(q, link)   // both go out now
        assertEquals(
            "a refused press must not be cancelled out by its own release",
            listOf(listOf(PcKeyMap.KEY_Z), emptyList<Byte>()),
            keysDelivered(link),
        )
    }

    @Test
    fun retriesAreBounded() {
        val q = KeyboardReportQueue(maxAttempts = 4)
        q.offer(z)
        repeat(3) { assertEquals(KeyboardReportQueue.Outcome.RETRY, q.onFailed()) }
        assertEquals(KeyboardReportQueue.Outcome.DROPPED, q.onFailed())
    }

    @Test
    fun aReleaseIsChasedHarderThanAPress() {
        assertTrue(
            KeyboardReportQueue.DEFAULT_MAX_RELEASE_ATTEMPTS >
                KeyboardReportQueue.DEFAULT_MAX_ATTEMPTS
        )
    }

    @Test
    fun givingUpOnAPressLeavesAReleaseBehindIt() {
        // The core anti-stuck rule: the host is holding Z, the next press is abandoned, and a
        // neutral report takes its place so the host does not keep holding Z for ever.
        val q = KeyboardReportQueue(maxAttempts = 2); val link = FakeLink()
        q.offer(z); drain(q, link)              // host now holds Z

        link.refuseEverything = true
        q.offer(za)
        assertEquals(KeyboardReportQueue.Outcome.RETRY, q.onFailed())
        assertEquals(KeyboardReportQueue.Outcome.DROPPED, q.onFailed())

        val head = q.peek()
        assertTrue("a release should have replaced the abandoned press", head != null)
        assertTrue("and it should be neutral", KeyboardReport.isNeutral(head!!))
    }

    @Test
    fun theReleaseLeftBehindActuallyGoesOut() {
        val q = KeyboardReportQueue(maxAttempts = 2); val link = FakeLink()
        q.offer(z); drain(q, link)

        link.refuseEverything = true
        q.offer(za)
        q.onFailed(); q.onFailed()

        link.refuseEverything = false
        drain(q, link)
        assertTrue(
            "the host must end up holding nothing",
            KeyboardReport.isNeutral(link.delivered.last()),
        )
    }

    @Test
    fun aDroppedReleaseIsNotReplacedByAnother() {
        // Avoid an endless chain of neutral reports: once the release itself is abandoned there
        // is nothing further to say.
        val q = KeyboardReportQueue(maxAttempts = 2, maxReleaseAttempts = 2)
        val link = FakeLink()
        q.offer(z); drain(q, link)

        link.refuseEverything = true
        q.offer(none)
        q.onFailed()
        assertEquals(KeyboardReportQueue.Outcome.DROPPED, q.onFailed())
        assertNull(q.peek())
    }

    @Test
    fun overflowDropsFromTheFrontAndKeepsTheNewest() {
        // Dropping from the front is what makes overflow safe: whatever the queue loses, the
        // last report still describes reality, so delivering it resynchronises the host.
        val q = KeyboardReportQueue(capacity = 3); val link = FakeLink()
        q.offer(z)
        q.offer(za)
        q.offer(a)
        q.offer(none)

        assertEquals("the queue must stay bounded", 3, q.size)
        assertEquals(1, q.droppedReports)

        drain(q, link)
        assertTrue(
            "the newest report survived, so the host ends up holding nothing",
            KeyboardReport.isNeutral(link.delivered.last()),
        )
    }

    @Test
    fun failingOnAnEmptyQueueIsHarmless() {
        assertEquals(KeyboardReportQueue.Outcome.DROPPED, KeyboardReportQueue().onFailed())
    }

    // ----------------------------------------------------------- disconnect

    @Test
    fun disconnectingWhileKeysAreHeldClearsEverythingPending() {
        // `HidGamepadManager.resetDelivery()` calls this when the link drops. Stale presses must
        // not outlive the connection and arrive at whatever connects next.
        val q = KeyboardReportQueue(); val link = FakeLink()
        q.offer(za); drain(q, link)
        q.offer(a)
        assertFalse(q.isEmpty)

        q.reset()

        assertTrue(q.isEmpty)
        assertNull(q.peek())
    }

    @Test
    fun afterAResetTheHostIsAssumedToHoldNothing() {
        // So the first report of the next session is sent even if it happens to match the last
        // one of the previous session.
        val q = KeyboardReportQueue(); val link = FakeLink()
        q.offer(z); drain(q, link)
        q.reset()
        q.offer(z)
        assertFalse("the press must be re-sent on the new link", q.isEmpty)
    }

    @Test
    fun reconnectingDeliversTheFirstReportAgain() {
        val q = KeyboardReportQueue(); val link = FakeLink()
        q.offer(z); drain(q, link)
        q.reset()
        q.offer(z); drain(q, link)
        assertEquals(2, link.delivered.size)
    }
}
