package com.dathaze.pocketpad.hid

/**
 * Pending TV-menu keystrokes, with a bound on how long a refused one is chased.
 *
 * The keyboard path had the same unbounded retry the gamepad path was given a budget for: a
 * report the stack would never accept was re-offered every 8 ms for as long as the app stayed
 * connected. That is a timer running forever over a key nobody is waiting for, and it keeps
 * waking the send loop, which is the only way the keyboard can interfere with the gamepad
 * reports that share it.
 *
 * Presses and releases are not treated alike, for the reason they are not alike:
 *
 *  - **A lost press** costs one menu keystroke. Annoying; nothing is left behind.
 *  - **A lost release** leaves the host holding a key down — arrow keys repeating in a TV menu
 *    with nothing on the phone pressed, and nothing to make it stop.
 *
 * So a release gets a much larger budget, and if a press is ever abandoned while the host is
 * believed to be holding a key, a release is queued in its place rather than leaving that key
 * stranded. Giving up never leaves a key held.
 *
 * Not thread-safe; used from the main thread only.
 */
class KeyReportQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val maxReleaseAttempts: Int = DEFAULT_MAX_RELEASE_ATTEMPTS,
) {

    enum class Outcome { RETRY, DROPPED }

    private val keys = ArrayDeque<Byte>()
    private var attempts = 0

    /** The last key the stack accepted, so a repeat of it can be skipped. */
    private var lastAccepted: Byte = HidConstants.KEY_NONE

    /** Whether the host is believed to be holding a key down. */
    private val hostHoldsKey: Boolean get() = lastAccepted != HidConstants.KEY_NONE

    var droppedKeys = 0
        private set

    val size: Int get() = keys.size
    val isEmpty: Boolean get() = keys.isEmpty()

    fun offer(key: Byte) {
        // Same rule as the gamepad queue: compare against what is queued, and only fall back
        // to the last accepted value while nothing is pending.
        if (keys.isNotEmpty()) {
            if (keys.last() == key) return
        } else if (key == lastAccepted) {
            return
        }
        keys.addLast(key)
        while (keys.size > capacity) {
            keys.removeFirst()
            droppedKeys++
            attempts = 0
        }
    }

    fun peek(): Byte? = keys.firstOrNull()

    fun onSent() {
        val sent = keys.removeFirstOrNull() ?: return
        lastAccepted = sent
        attempts = 0
    }

    /**
     * The stack refused the head.
     *
     * Returns [Outcome.RETRY] while it is still worth offering. On [Outcome.DROPPED] the head
     * has been removed — and if dropping it would have left the host holding a key, a release
     * now sits at the front of the queue in its place.
     */
    fun onFailed(): Outcome {
        val head = keys.firstOrNull() ?: return Outcome.DROPPED
        attempts++
        val budget = if (head == HidConstants.KEY_NONE) maxReleaseAttempts else maxAttempts
        if (attempts < budget) return Outcome.RETRY

        keys.removeFirst()
        droppedKeys++
        attempts = 0

        // Never give up in a state where the host is left holding something. A press that was
        // abandoned while a key is believed held becomes a release instead.
        if (head != HidConstants.KEY_NONE && hostHoldsKey &&
            keys.firstOrNull() != HidConstants.KEY_NONE
        ) {
            keys.addFirst(HidConstants.KEY_NONE)
        }
        return Outcome.DROPPED
    }

    fun reset() {
        keys.clear()
        attempts = 0
        lastAccepted = HidConstants.KEY_NONE
    }

    companion object {
        /** Keystrokes are slow by nature; a deep backlog would only be stale. */
        const val DEFAULT_CAPACITY = 8

        /** A press is worth a few tries and no more. */
        const val DEFAULT_MAX_ATTEMPTS = 4

        /** A release decides whether a key is left stuck, so it is chased much harder. */
        const val DEFAULT_MAX_RELEASE_ATTEMPTS = 24
    }
}
