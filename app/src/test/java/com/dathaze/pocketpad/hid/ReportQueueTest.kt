package com.dathaze.pocketpad.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable coverage for the send path — the half of the app that had none.
 *
 * The audit found that quick taps could vanish entirely under link congestion, and that the
 * suite could not have caught it: the only tests touching `HidGamepadManager` read it as source
 * text and matched regexes against it. These drive the real [ReportQueue] through a stand-in for
 * the Bluetooth stack, including the cases where it refuses reports.
 *
 * [drain] is deliberately a line-for-line copy of the loop in `HidGamepadManager.drain()`, so
 * what is exercised here is the decision logic the app actually runs.
 */
class ReportQueueTest {

    // ----------------------------------------------------------------- harness

    /** Stands in for the Bluetooth stack: records what it took, and can refuse on demand. */
    private class FakeLink {
        val delivered = mutableListOf<ByteArray>()
        var refusalsLeft = 0
        var refuseEverything = false
        var sendAttempts = 0

        fun send(report: ByteArray): Boolean {
            sendAttempts++
            if (refuseEverything || refusalsLeft > 0) {
                if (refusalsLeft > 0) refusalsLeft--
                return false
            }
            delivered += report.copyOf()
            return true
        }
    }

    /** The same loop `HidGamepadManager.drain()` runs, minus the Android scheduling. */
    private fun drain(queue: ReportQueue, link: FakeLink) {
        while (true) {
            val frame = queue.peek() ?: return
            if (link.send(frame)) {
                queue.onSent()
                continue
            }
            if (queue.onFailed() == ReportQueue.Outcome.RETRY) return
        }
    }

    private fun report(
        buttons: Int = 0,
        hat: Int = HidConstants.HAT_NEUTRAL,
        lx: Int = 128, ly: Int = 128,
        rx: Int = 128, ry: Int = 128,
        lt: Int = 0, rt: Int = 0,
    ): ByteArray = GamepadState().apply {
        this.buttons = buttons
        this.hat = hat
        this.lx = lx; this.ly = ly; this.rx = rx; this.ry = ry
        this.lt = lt; this.rt = rt
    }.toReport()

    private fun bit(index: Int) = 1 shl index

    /** Button bits of a delivered frame, so assertions read in terms of controls. */
    private fun buttonsOf(report: ByteArray): Int =
        (report[0].toInt() and 0xFF) or ((report[1].toInt() and 0xFF) shl 8)

    private fun hatOf(report: ByteArray): Int = report[2].toInt() and 0x0F

    private fun pressed(report: ByteArray, index: Int) = buttonsOf(report) and bit(index) != 0

    // ------------------------------------------------- 1. ordinary press/release

    @Test
    fun aNormalPressAndReleaseBothReachTheHost() {
        val q = ReportQueue(); val link = FakeLink()

        q.offer(report(buttons = bit(HidConstants.BTN_CROSS))); drain(q, link)
        q.offer(report()); drain(q, link)

        assertEquals(2, link.delivered.size)
        assertTrue("press", pressed(link.delivered[0], HidConstants.BTN_CROSS))
        assertFalse("release", pressed(link.delivered[1], HidConstants.BTN_CROSS))
        assertTrue(q.isEmpty)
    }

    // ------------------------------------------------------------ 2. rapid taps

    @Test
    fun veryRapidTappingDeliversEveryTransitionInOrder() {
        val q = ReportQueue(); val link = FakeLink()
        val down = report(buttons = bit(HidConstants.BTN_CROSS))
        val up = report()

        repeat(10) {
            q.offer(down); drain(q, link)
            q.offer(up); drain(q, link)
        }

        assertEquals("every edge of 10 taps", 20, link.delivered.size)
        link.delivered.forEachIndexed { i, frame ->
            assertEquals(
                "frame $i should be ${if (i % 2 == 0) "down" else "up"}",
                i % 2 == 0,
                pressed(frame, HidConstants.BTN_CROSS)
            )
        }
    }

    // ------------------------------------------- 3. the bug this was written for

    /**
     * The exact sequence from the audit. The press is refused, the finger lifts before the
     * retry, and the old code compared the release against the last *accepted* report, found
     * them equal, and sent nothing at all — the host never saw the button.
     */
    @Test
    fun aRefusedPressStillReachesTheHostWhenTheFingerHasAlreadyLifted() {
        val q = ReportQueue(); val link = FakeLink()

        link.refusalsLeft = 1
        q.offer(report(buttons = bit(HidConstants.BTN_CROSS)))
        drain(q, link)
        assertEquals("the stack refused it", 0, link.delivered.size)

        // Finger lifts before the retry runs.
        q.offer(report())
        drain(q, link)

        assertEquals("press then release, not nothing", 2, link.delivered.size)
        assertTrue(pressed(link.delivered[0], HidConstants.BTN_CROSS))
        assertFalse(pressed(link.delivered[1], HidConstants.BTN_CROSS))
    }

    // ------------------------------------------- 4. press and release in a frame

    /**
     * Both edges are offered before anything drains — the case that breaks any design which
     * keeps only "the latest state", because the latest state is neutral.
     */
    @Test
    fun aPressAndReleaseInsideOneFrameAreNotCollapsed() {
        val q = ReportQueue(); val link = FakeLink()

        q.offer(report(buttons = bit(HidConstants.BTN_CIRCLE)))
        q.offer(report())
        assertEquals("two distinct transitions queued", 2, q.size)

        drain(q, link)

        assertEquals(2, link.delivered.size)
        assertTrue(pressed(link.delivered[0], HidConstants.BTN_CIRCLE))
        assertFalse(pressed(link.delivered[1], HidConstants.BTN_CIRCLE))
    }

    // -------------------------------------------------- 5. simultaneous presses

    @Test
    fun twoButtonsHeldTogetherAreBothPresentInTheReport() {
        val q = ReportQueue(); val link = FakeLink()
        val both = bit(HidConstants.BTN_CROSS) or bit(HidConstants.BTN_SQUARE)

        q.offer(report(buttons = bit(HidConstants.BTN_CROSS)))
        q.offer(report(buttons = both))
        drain(q, link)

        val last = link.delivered.last()
        assertTrue(pressed(last, HidConstants.BTN_CROSS))
        assertTrue(pressed(last, HidConstants.BTN_SQUARE))
    }

    // --------------------------------------------------- 6. both sticks at once

    @Test
    fun bothSticksMovingTogetherCoalesceIntoOneFrame() {
        val q = ReportQueue(); val link = FakeLink()

        // What one touch frame used to produce: left stick sample, then right stick sample.
        q.offer(report(lx = 200, ly = 100))
        q.offer(report(lx = 200, ly = 100, rx = 60, ry = 190))

        assertEquals("one frame, not two", 1, q.size)
        assertEquals(1, q.coalescedSamples)

        drain(q, link)
        assertEquals(1, link.delivered.size)
        val f = link.delivered[0]
        assertEquals(200, f[3].toInt() and 0xFF)
        assertEquals(100, f[4].toInt() and 0xFF)
        assertEquals(60, f[5].toInt() and 0xFF)
        assertEquals(190, f[6].toInt() and 0xFF)
    }

    @Test
    fun aLongStreamOfStickSamplesCollapsesToItsNewestValue() {
        val q = ReportQueue(); val link = FakeLink()

        for (x in 129..228) q.offer(report(lx = x))
        assertEquals("100 samples, one pending frame", 1, q.size)

        drain(q, link)
        assertEquals(1, link.delivered.size)
        assertEquals(228, link.delivered[0][3].toInt() and 0xFF)
    }

    // ------------------------------------------- 7. held button while stick moves

    @Test
    fun aHeldButtonSurvivesEveryStickSampleAndItsRelease() {
        val q = ReportQueue(); val link = FakeLink()
        val held = bit(HidConstants.BTN_R2)

        q.offer(report(buttons = held)); drain(q, link)
        for (x in 130..160) { q.offer(report(buttons = held, lx = x)); drain(q, link) }
        q.offer(report()); drain(q, link)

        assertTrue("more than just the edges got through", link.delivered.size >= 3)
        link.delivered.dropLast(1).forEach {
            assertTrue("button must stay held while the stick moves", pressed(it, HidConstants.BTN_R2))
        }
        assertFalse("released at the end", pressed(link.delivered.last(), HidConstants.BTN_R2))
    }

    // ------------------------------------------- 8. triggers + shoulders together

    /** The Batmobile case: shoulder and trigger held at once, with a stick live underneath. */
    @Test
    fun shouldersAndTriggersHeldTogetherWithAStickMovingStayHeld() {
        val q = ReportQueue(); val link = FakeLink()
        val combo = bit(HidConstants.BTN_L1) or bit(HidConstants.BTN_R1) or
            bit(HidConstants.BTN_L2) or bit(HidConstants.BTN_R2)

        q.offer(report(buttons = bit(HidConstants.BTN_L1)))
        q.offer(report(buttons = bit(HidConstants.BTN_L1) or bit(HidConstants.BTN_R1)))
        q.offer(report(buttons = combo, lt = 255, rt = 255))
        for (x in 140..170) q.offer(report(buttons = combo, lt = 255, rt = 255, lx = x))
        drain(q, link)

        val last = link.delivered.last()
        listOf(
            HidConstants.BTN_L1, HidConstants.BTN_R1, HidConstants.BTN_L2, HidConstants.BTN_R2
        ).forEach { assertTrue("button $it held", pressed(last, it)) }
        assertEquals("left trigger analog", 255, last[7].toInt() and 0xFF)
        assertEquals("right trigger analog", 255, last[8].toInt() and 0xFF)
    }

    // --------------------------------------- 9. fingers arriving and leaving

    @Test
    fun fingersArrivingAndLeavingProduceOneTransitionEach() {
        val q = ReportQueue(); val link = FakeLink()
        val a = bit(HidConstants.BTN_CROSS)
        val b = bit(HidConstants.BTN_CIRCLE)
        val c = bit(HidConstants.BTN_SQUARE)

        listOf(a, a or b, a or b or c, b or c, c, 0).forEach { q.offer(report(buttons = it)) }
        drain(q, link)

        assertEquals(6, link.delivered.size)
        assertEquals(listOf(a, a or b, a or b or c, b or c, c, 0), link.delivered.map(::buttonsOf))
    }

    @Test
    fun theHatIsTreatedAsATransitionNotALevel() {
        val q = ReportQueue(); val link = FakeLink()
        // Rolling a thumb around the d-pad must not lose intermediate directions the way a
        // stick sample would be folded away.
        listOf(0, 1, 2, 3, HidConstants.HAT_NEUTRAL).forEach { q.offer(report(hat = it)) }
        drain(q, link)

        assertEquals(5, link.delivered.size)
        assertEquals(listOf(0, 1, 2, 3, HidConstants.HAT_NEUTRAL), link.delivered.map(::hatOf))
    }

    // ------------------------------------------------- 10/11. link lifecycle

    @Test
    fun aDisconnectThrowsAwayWhateverWasStillQueued() {
        val q = ReportQueue(); val link = FakeLink()

        link.refuseEverything = true
        q.offer(report(buttons = bit(HidConstants.BTN_CROSS)))
        q.offer(report(buttons = bit(HidConstants.BTN_CROSS) or bit(HidConstants.BTN_CIRCLE)))
        drain(q, link)
        assertFalse("stuck behind a dead link", q.isEmpty)

        q.reset()   // what onConnectionStateChanged(DISCONNECTED) does

        assertTrue(q.isEmpty)
        assertEquals("nothing was delivered to a host that went away", 0, link.delivered.size)
    }

    @Test
    fun inputAfterAReconnectIsNotPollutedByTheOldSession() {
        val q = ReportQueue(); val link = FakeLink()

        link.refuseEverything = true
        q.offer(report(buttons = bit(HidConstants.BTN_PS)))   // held when the link dropped
        drain(q, link)
        q.reset()

        link.refuseEverything = false
        q.offer(report(buttons = bit(HidConstants.BTN_CROSS)))
        drain(q, link)

        assertEquals(1, link.delivered.size)
        assertFalse(
            "a press from the previous connection must not replay",
            pressed(link.delivered[0], HidConstants.BTN_PS)
        )
        assertTrue(pressed(link.delivered[0], HidConstants.BTN_CROSS))
    }

    @Test
    fun focusLossReleasesEverythingAndThatReleaseIsDelivered() {
        val q = ReportQueue(); val link = FakeLink()
        val held = bit(HidConstants.BTN_CROSS) or bit(HidConstants.BTN_L2)

        q.offer(report(buttons = held, lt = 255)); drain(q, link)
        // releaseAllPointers() resets the state and announces it.
        q.offer(report()); drain(q, link)

        assertEquals(0, buttonsOf(link.delivered.last()))
        assertEquals("trigger axis released too", 0, link.delivered.last()[7].toInt() and 0xFF)
    }

    // --------------------------------------- 12. failure, backpressure, recovery

    @Test
    fun aBurstOfRefusalsIsRiddenOutAndEverythingArrivesInOrder() {
        val q = ReportQueue(); val link = FakeLink()
        val a = bit(HidConstants.BTN_CROSS)
        val b = bit(HidConstants.BTN_CIRCLE)

        link.refusalsLeft = 2
        q.offer(report(buttons = a))
        q.offer(report(buttons = a or b))
        q.offer(report(buttons = b))
        q.offer(report())

        drain(q, link)                      // refused, one attempt consumed
        drain(q, link)                      // refused again
        drain(q, link)                      // link recovers, everything flushes

        assertEquals(listOf(a, a or b, b, 0), link.delivered.map(::buttonsOf))
        assertEquals("nothing was abandoned", 0, q.droppedFrames)
    }

    @Test
    fun retriesAreBoundedSoAStalePressIsNeverReplayedForever() {
        val q = ReportQueue(maxAttempts = 3, maxFinalAttempts = 5)
        val link = FakeLink()
        link.refuseEverything = true

        q.offer(report(buttons = bit(HidConstants.BTN_CROSS)))
        q.offer(report())                                  // the release, i.e. current state

        repeat(40) { drain(q, link) }

        assertTrue("the queue gives up rather than retrying for ever", q.isEmpty)
        assertTrue(q.droppedFrames > 0)
        assertEquals("and it stopped trying", 0, link.delivered.size)
    }

    @Test
    fun theFinalFrameIsRetriedHarderThanTheOnesItSuperseded() {
        val q = ReportQueue(maxAttempts = 2, maxFinalAttempts = 9)
        val link = FakeLink()
        link.refuseEverything = true

        q.offer(report(buttons = bit(HidConstants.BTN_CROSS)))
        q.offer(report())
        repeat(2) { drain(q, link) }

        assertEquals("the superseded press was dropped quickly", 1, q.size)
        val attemptsSoFar = link.sendAttempts
        repeat(3) { drain(q, link) }
        assertTrue("the surviving frame keeps being offered", link.sendAttempts > attemptsSoFar)
        assertEquals("and it is the current state", 0, buttonsOf(q.peek()!!))
    }

    /**
     * The safety property that makes dropping acceptable: whatever the queue loses, the newest
     * frame still describes reality, so a recovered link resynchronises the host. A dropped
     * press costs a tap. It cannot leave a button stuck down.
     */
    @Test
    fun theNewestFrameAlwaysHoldsTheTrueCurrentState() {
        val q = ReportQueue(capacity = 4)
        val link = FakeLink()
        link.refuseEverything = true

        for (i in 0 until 40) {
            q.offer(report(buttons = if (i % 2 == 0) bit(HidConstants.BTN_CROSS) else 0))
            drain(q, link)
        }
        q.offer(report())   // the finger is up; this is the truth

        assertTrue(q.size <= 4)
        assertEquals("nothing is left held", 0, buttonsOf(q.peekNewest()!!))
    }

    @Test
    fun theQueueNeverGrowsWithoutLimit() {
        val q = ReportQueue(capacity = 6)
        for (i in 0 until 200) {
            q.offer(report(buttons = if (i % 2 == 0) bit(HidConstants.BTN_CROSS) else 0))
        }
        assertTrue("capacity respected, got ${q.size}", q.size <= 6)
        assertTrue(q.droppedFrames > 0)
    }

    // ------------------------------- 13. duplicate suppression without data loss

    @Test
    fun identicalReportsAreSuppressed() {
        val q = ReportQueue(); val link = FakeLink()
        val held = report(buttons = bit(HidConstants.BTN_CROSS))

        q.offer(held); drain(q, link)
        repeat(5) { q.offer(held); drain(q, link) }

        assertEquals("the radio carries changes, not repeats", 1, link.delivered.size)
        assertEquals(5, q.suppressedDuplicates)
    }

    @Test
    fun suppressionComparesAgainstWhatIsQueuedNotWhatWasAccepted() {
        // The old code compared against the last accepted report, which is what let a refused
        // press cancel its own release. Suppression must only ever fire against a frame that is
        // still pending, never against one the stack turned down.
        val q = ReportQueue(); val link = FakeLink()

        link.refusalsLeft = 1
        q.offer(report(buttons = bit(HidConstants.BTN_TRIANGLE)))
        drain(q, link)
        q.offer(report())
        drain(q, link)

        assertEquals(2, link.delivered.size)
        assertEquals(0, q.suppressedDuplicates)
    }

    /**
     * Suppression is allowed to consult the last accepted frame only while nothing is pending.
     * If a frame was abandoned, the host's state is unknown, and the next offer must go out
     * even if it happens to match — otherwise giving up on a release could strand a button.
     */
    @Test
    fun aDroppedFrameInvalidatesTheSuppressionBaseline() {
        val q = ReportQueue(maxAttempts = 1, maxFinalAttempts = 1)
        val link = FakeLink()
        val neutral = report()

        q.offer(neutral); drain(q, link)
        assertEquals(1, link.delivered.size)

        // A press is offered, refused, and given up on.
        link.refuseEverything = true
        q.offer(report(buttons = bit(HidConstants.BTN_CROSS)))
        drain(q, link)
        assertTrue("the press was abandoned", q.droppedFrames > 0)

        // Back to neutral — identical to what was last accepted, but it must still be sent,
        // because the host may have taken the press before the stack reported failure.
        link.refuseEverything = false
        q.offer(neutral)
        drain(q, link)

        assertEquals("a resync frame must not be suppressed", 2, link.delivered.size)
        assertEquals(0, buttonsOf(link.delivered.last()))
    }

    @Test
    fun aStickReturningToCentreIsNotMistakenForADuplicate() {
        val q = ReportQueue(); val link = FakeLink()

        q.offer(report()); drain(q, link)
        q.offer(report(lx = 255)); drain(q, link)
        q.offer(report()); drain(q, link)

        assertEquals(3, link.delivered.size)
        assertEquals(128, link.delivered.last()[3].toInt() and 0xFF)
    }

    // ------------------------------------------------------------- housekeeping

    @Test
    fun onlyNineByteGamepadReportsAreAccepted() {
        val q = ReportQueue()
        val tooShort = ByteArray(HidConstants.KEYBOARD_REPORT_SIZE)
        try {
            q.offer(tooShort)
            throw AssertionError("an 8-byte keyboard report must not enter the gamepad queue")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("9-byte"))
        }
    }

    @Test
    fun offeredArraysAreCopiedSoCallersCanReuseTheirBuffer() {
        val q = ReportQueue(); val link = FakeLink()
        val buffer = report(buttons = bit(HidConstants.BTN_CROSS))

        q.offer(buffer)
        buffer[0] = 0   // the caller reuses its scratch array
        drain(q, link)

        assertTrue(
            "the queue must not alias the caller's array",
            pressed(link.delivered[0], HidConstants.BTN_CROSS)
        )
    }

    @Test
    fun transitionComparisonLooksOnlyAtButtonsAndHat() {
        val stickMoved = report(lx = 10)
        val stillCentred = report()
        val buttonPressed = report(buttons = bit(HidConstants.BTN_CROSS))
        val hatMoved = report(hat = 2)

        assertTrue(ReportQueue.carriesSameTransition(stickMoved, stillCentred))
        assertFalse(ReportQueue.carriesSameTransition(stillCentred, buttonPressed))
        assertFalse(ReportQueue.carriesSameTransition(stillCentred, hatMoved))
    }

    @Test
    fun anEmptyQueueHandlesFailureWithoutBlowingUp() {
        val q = ReportQueue()
        assertEquals(ReportQueue.Outcome.DROPPED, q.onFailed())
        q.onSent()
        assertTrue(q.isEmpty)
        assertArrayEquals(null, q.peek())
    }
}
