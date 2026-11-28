package com.dathaze.pocketpad.hid

/**
 * The send queue for multi-key keyboard reports, used by **PC Keys**.
 *
 * ### Why this is not [KeyReportQueue]
 *
 * [KeyReportQueue] carries a single `Byte` — one key, or [HidConstants.KEY_NONE] for "released".
 * That is exactly what TV Keys needs and it is proven in the field, so it is left alone: TV Keys
 * keeps a byte-for-byte identical path to the one it has always had, and its whole test suite
 * goes on asserting against it untouched.
 *
 * PC Keys has to express "Z and Left are both down, now Z is up and Left is still down", which a
 * single byte cannot say at all. So this queue carries whole 8-byte reports.
 *
 * ### What it inherits
 *
 * The semantics are deliberately the same as [KeyReportQueue], because the hazards are the same:
 *
 *  - A report the stack refuses is **offered again**, not dropped, so a refused key-down cannot
 *    be cancelled out by its own key-up and leave the host holding a key nobody is pressing.
 *  - Retries are **bounded**, so a report the stack will never take cannot keep a timer alive
 *    for the rest of the session.
 *  - A **release is chased far harder than a press** ([DEFAULT_MAX_RELEASE_ATTEMPTS] against
 *    [DEFAULT_MAX_ATTEMPTS]), because a lost press costs a dropped input and a lost release
 *    costs a stuck key.
 *  - Giving up never leaves the host holding keys: if a press is abandoned while the host is
 *    believed to be holding something, a neutral report takes its place at the front.
 *
 * `sendReport` returning true means the local stack took the bytes, not that the host acted on
 * them — there is no acknowledgement on the HID interrupt channel. Nothing here pretends
 * otherwise; the guarantee is ordering and bounded retry, no more.
 *
 * Not thread-safe. [HidGamepadManager] drives it from the main thread only.
 */
class KeyboardReportQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val maxReleaseAttempts: Int = DEFAULT_MAX_RELEASE_ATTEMPTS,
) {

    /** What the caller should do after a refused send. */
    enum class Outcome { RETRY, DROPPED }

    private val reports = ArrayDeque<ByteArray>()
    private var attempts = 0

    /** The last report the stack accepted — what the host is believed to be holding. */
    private var lastAccepted: ByteArray = HidConstants.neutralKeyboardReport()

    private val hostHoldsKeys: Boolean get() = !KeyboardReport.isNeutral(lastAccepted)

    /** Reports abandoned because the stack would not take them. Diagnostics only. */
    var droppedReports = 0
        private set

    /** Reports identical to what is already queued or already delivered. Diagnostics only. */
    var suppressedDuplicates = 0
        private set

    val size: Int get() = reports.size
    val isEmpty: Boolean get() = reports.isEmpty()

    /**
     * Queue [report].
     *
     * Every distinct report is a transition and is kept in order — nothing is coalesced, because
     * unlike a stick position there is no such thing as a stale key state that a newer one makes
     * irrelevant. A repeat of what is already pending, or of what the host already has while
     * nothing is pending, is dropped.
     *
     * The array is copied, so the caller may keep reusing its own buffer.
     */
    fun offer(report: ByteArray) {
        require(report.size == HidConstants.KEYBOARD_REPORT_SIZE) {
            "expected a ${HidConstants.KEYBOARD_REPORT_SIZE}-byte keyboard report, got ${report.size}"
        }
        val tail = reports.lastOrNull()
        if (tail != null) {
            if (tail.contentEquals(report)) {
                suppressedDuplicates++
                return
            }
        } else if (lastAccepted.contentEquals(report)) {
            // Nothing pending, so everything queued was delivered, and the host already has this.
            suppressedDuplicates++
            return
        }
        reports.addLast(report.copyOf())
        while (reports.size > capacity) {
            reports.removeFirst()
            droppedReports++
            attempts = 0
            // Whatever was dropped never arrived, so nothing can be assumed about the host.
            lastAccepted = HidConstants.neutralKeyboardReport()
        }
    }

    /** The report to send next, or null when there is nothing pending. */
    fun peek(): ByteArray? = reports.firstOrNull()

    /** The stack accepted the head. */
    fun onSent() {
        val sent = reports.removeFirstOrNull() ?: return
        lastAccepted = sent
        attempts = 0
    }

    /**
     * The stack refused the head.
     *
     * Returns [Outcome.RETRY] while it is still worth offering. On [Outcome.DROPPED] the head has
     * been removed — and if dropping it would have left the host holding keys, a neutral report
     * now sits at the front in its place.
     */
    fun onFailed(): Outcome {
        val head = reports.firstOrNull() ?: return Outcome.DROPPED
        attempts++
        val isRelease = KeyboardReport.isNeutral(head)
        val budget = if (isRelease) maxReleaseAttempts else maxAttempts
        if (attempts < budget) return Outcome.RETRY

        reports.removeFirst()
        droppedReports++
        attempts = 0

        // Never give up in a state where the host is left holding something.
        if (!isRelease && hostHoldsKeys && !nextIsRelease()) {
            reports.addFirst(HidConstants.neutralKeyboardReport())
        }
        return Outcome.DROPPED
    }

    private fun nextIsRelease(): Boolean =
        reports.firstOrNull()?.let { KeyboardReport.isNeutral(it) } == true

    /** Throw away everything pending — the link is gone and stale reports must not outlive it. */
    fun reset() {
        reports.clear()
        attempts = 0
        lastAccepted = HidConstants.neutralKeyboardReport()
    }

    companion object {
        /** Keystrokes are slow by nature; a deeper backlog would only be stale. */
        const val DEFAULT_CAPACITY = 12

        /** A press is worth a few tries and no more. */
        const val DEFAULT_MAX_ATTEMPTS = 4

        /** A release decides whether keys are left stuck, so it is chased much harder. */
        const val DEFAULT_MAX_RELEASE_ATTEMPTS = 24
    }
}
