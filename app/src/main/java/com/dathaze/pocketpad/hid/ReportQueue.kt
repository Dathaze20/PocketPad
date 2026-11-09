package com.dathaze.pocketpad.hid

/**
 * The send queue that sits between touch input and the Bluetooth stack.
 *
 * ### What this exists to prevent
 *
 * Reports used to be sent straight from the touch handler, with a plain
 * "is this the same as the last one?" check in front. Two things went wrong.
 *
 * A report the stack refuses was never retried. If a press was refused and the finger lifted
 * before anything else happened, the release matched the last report that *had* been accepted,
 * the duplicate check swallowed it, and the host never learned the button was pressed at all.
 *
 * And every stick sample produced its own report. Two thumbs on two sticks offered the link two
 * full reports per touch frame, which is what made the stack start refusing them in the first
 * place. The two defects fed each other.
 *
 * ### Why this is not just "retry the latest state"
 *
 * Remembering only the newest state is the obvious fix and it is wrong. A press and its release
 * can both land inside one frame, and collapsing them leaves a single neutral report — the tap
 * disappears exactly as before, only now by design. The same thing happens if a refused press is
 * "retried" with whatever the state has become by the time the retry runs.
 *
 * So frames are split by what they carry:
 *
 *  - **Button and hat bits are transitions.** Each distinct one is queued in its own frame and
 *    delivered in order. Nothing merges them, nothing reorders them, nothing skips one because a
 *    newer frame exists.
 *  - **Stick and trigger positions are a level.** Only the newest matters, so a new sample
 *    overwrites the pending one *in place* as long as the buttons around it have not changed.
 *
 * A press, three stick samples and a release therefore leave three frames, not five and not one.
 *
 * ### What the Bluetooth API can and cannot promise
 *
 * `BluetoothHidDevice.sendReport` returning true means the local stack accepted the bytes for
 * transmission. It is **not** an acknowledgement from the host: HID input reports travel on the
 * L2CAP interrupt channel and no reply comes back up to the application. There is no delivery
 * receipt available to this layer at all.
 *
 * So the guarantee here is bounded on purpose: frames are handed over **in order**, a frame the
 * stack refuses is **offered again** rather than discarded, and the queue never grows without
 * limit. Whether the host then acted on a report is not knowable from this side, and nothing in
 * this class pretends otherwise.
 *
 * ### What happens when it cannot keep up
 *
 * Retries are capped, because replaying a stale press forever is its own bug — a button the user
 * let go of a second ago must never arrive late. When a frame is given up on, it is dropped and
 * counted.
 *
 * The invariant that makes dropping safe: **the newest frame always holds the true current
 * state.** Coalescing only ever rewrites the tail, and frames are only ever dropped from the
 * front, so whatever the queue loses, the last frame still describes reality. Delivering it
 * resynchronises the host. A dropped press costs a transient tap; it cannot strand a button in
 * the held position.
 *
 * The final frame is given a longer budget than the ones behind it for that reason: intermediate
 * frames are superseded and can be abandoned cheaply, but the last one is the state the host will
 * be left believing.
 *
 * Not thread-safe. Call it from one thread — [HidGamepadManager] uses the main thread.
 */
class ReportQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val maxFinalAttempts: Int = DEFAULT_MAX_FINAL_ATTEMPTS,
) {

    /** What the caller should do after a refused send. */
    enum class Outcome {
        /** Offer the same frame again shortly. */
        RETRY,

        /** The frame was given up on and removed. Anything behind it is still queued. */
        DROPPED,
    }

    private val frames = ArrayDeque<ByteArray>()
    private var attempts = 0

    /**
     * The last frame the stack actually took, or null when that is no longer known.
     *
     * Used to suppress a repeat of a state already on the host — but **only while the queue is
     * empty**, because only then is everything queued known to have been delivered. That
     * distinction is the whole difference from the bug this class replaced, which compared
     * against the last accepted report even when a send had failed and so let a lost press
     * cancel its own release. Anything dropped clears this back to null, so a frame that never
     * arrived can never be mistaken for one that did.
     */
    private var lastAccepted: ByteArray? = null

    /** Frames abandoned because the stack would not take them. Diagnostics only. */
    var droppedFrames = 0
        private set

    /** Analog samples folded into an already-queued frame instead of adding one. */
    var coalescedSamples = 0
        private set

    /** Identical reports discarded before they ever reached the queue. */
    var suppressedDuplicates = 0
        private set

    val size: Int get() = frames.size
    val isEmpty: Boolean get() = frames.isEmpty()

    /**
     * Queue [report] (a 9-byte gamepad payload), or fold it into the pending tail.
     *
     * The array is copied, so the caller may keep reusing its own buffer.
     */
    fun offer(report: ByteArray) {
        require(report.size == HidConstants.GAMEPAD_REPORT_SIZE) {
            "expected a ${HidConstants.GAMEPAD_REPORT_SIZE}-byte gamepad report, got ${report.size}"
        }
        val tail = frames.lastOrNull()
        if (tail == null && lastAccepted?.contentEquals(report) == true) {
            // Nothing pending and the host already has exactly this. Safe to skip only because
            // an empty queue means every frame was accepted, and any drop nulls this out.
            suppressedDuplicates++
            return
        }
        if (tail != null && carriesSameTransition(tail, report)) {
            if (tail.contentEquals(report)) {
                // Nothing moved. This is the duplicate suppression that used to live in send(),
                // except it now compares against what is *queued* rather than what was last
                // accepted — which is the reason a lost press can no longer swallow its release.
                suppressedDuplicates++
                return
            }
            // Same buttons, different stick or trigger position: the newer sample replaces the
            // older one. The frame has not been sent yet, so nothing is lost by rewriting it.
            frames.removeLast()
            frames.addLast(report.copyOf())
            coalescedSamples++
            return
        }
        frames.addLast(report.copyOf())
        trimToCapacity()
    }

    /** The frame to send next, or null when there is nothing pending. */
    fun peek(): ByteArray? = frames.firstOrNull()

    /**
     * The newest pending frame — the one that describes the controller as it is right now.
     *
     * Exposed because it is the queue's safety property: coalescing only rewrites the tail and
     * drops only take from the front, so this frame survives everything and delivering it
     * resynchronises the host.
     */
    fun peekNewest(): ByteArray? = frames.lastOrNull()

    /** The stack accepted the head. */
    fun onSent() {
        val sent = frames.removeFirstOrNull()
        if (sent != null) lastAccepted = sent
        attempts = 0
    }

    /**
     * The stack refused the head.
     *
     * Returns [Outcome.RETRY] while the frame is still worth offering, or [Outcome.DROPPED] once
     * its budget is spent — at which point it has been removed.
     */
    fun onFailed(): Outcome {
        if (frames.isEmpty()) return Outcome.DROPPED
        attempts++
        // The last frame is the one that decides what the host ends up believing, so it gets a
        // longer budget than frames that something newer has already superseded.
        val budget = if (frames.size == 1) maxFinalAttempts else maxAttempts
        if (attempts < budget) return Outcome.RETRY
        frames.removeFirst()
        droppedFrames++
        attempts = 0
        // The host never received that frame, so nothing can be assumed about what it holds.
        lastAccepted = null
        return Outcome.DROPPED
    }

    /** Throw away everything pending — the link is gone and stale frames must not outlive it. */
    fun reset() {
        frames.clear()
        attempts = 0
        lastAccepted = null
    }

    /**
     * Drop the oldest frames until the queue fits.
     *
     * Only reachable when the stack has been refusing reports for long enough that this many
     * distinct transitions have piled up behind it. Dropping from the front keeps the newest
     * frame, so the host still converges on the right state once the link recovers.
     */
    private fun trimToCapacity() {
        while (frames.size > capacity) {
            frames.removeFirst()
            droppedFrames++
            attempts = 0
            lastAccepted = null
        }
    }

    companion object {
        /** Byte indices that make a frame a distinct transition rather than a new sample. */
        const val BUTTONS_LOW = 0
        const val BUTTONS_HIGH = 1
        const val HAT = 2

        /** Enough to absorb a long stall; past this the link is not coming back in time. */
        const val DEFAULT_CAPACITY = 24

        /** Retries for a frame something newer has already replaced. */
        const val DEFAULT_MAX_ATTEMPTS = 3

        /** Retries for the frame that will be the host's last word on the controller. */
        const val DEFAULT_MAX_FINAL_ATTEMPTS = 12

        /**
         * True when two reports describe the same buttons and the same hat direction, and so
         * differ only in how far a stick or trigger has moved.
         */
        fun carriesSameTransition(a: ByteArray, b: ByteArray): Boolean =
            a[BUTTONS_LOW] == b[BUTTONS_LOW] &&
                a[BUTTONS_HIGH] == b[BUTTONS_HIGH] &&
                a[HAT] == b[HAT]
    }
}
