package com.dathaze.pocketpad.hid

/**
 * Reading and writing the 8-byte keyboard input report that the keyboard collection of
 * [HidConstants.REPORT_DESCRIPTOR] (report id 2) declares.
 *
 * The layout is the standard boot-keyboard one, and the descriptor is **not** changed by any of
 * this — the six key slots were always there, PocketPad simply never used more than the first:
 *
 * ```
 *   byte 0   modifier bits (Left Ctrl .. Right GUI)
 *   byte 1   reserved, always zero
 *   byte 2   key slot 1
 *   ...
 *   byte 7   key slot 6
 * ```
 *
 * ### Why modifiers cannot go in a key slot
 *
 * The descriptor declares the six slots with `Usage Maximum (101)`, i.e. `0x65`. Right Shift is
 * usage `0xE5`, far above that, so a host is entitled to ignore it — or to read it as something
 * else entirely — if it arrives in a slot. Modifiers have their own bits in byte 0 and that is
 * the only place they are valid. [MOD_RIGHT_SHIFT] is the bit PocketPad actually uses.
 *
 * ### Rollover
 *
 * Six slots is the hard limit of this report. The HID spec's answer to a seventh key is to fill
 * every slot with `ErrorRollOver`, which tells the host "too many" and in practice drops *all*
 * input. That is the wrong trade for a game pad, where losing the direction you are holding is
 * worse than losing the seventh button. [build] therefore keeps the first six keys it is given
 * and discards the rest, so the caller's ordering decides what survives — see [PcKeyMap.keysFor],
 * which puts directions first on purpose.
 */
object KeyboardReport {

    /** Key slots in one report. Fixed by the descriptor. */
    const val MAX_KEYS = 6

    const val MODIFIER_INDEX = 0
    const val RESERVED_INDEX = 1
    const val FIRST_KEY_INDEX = 2

    /** Modifier bits in byte 0, in descriptor order (Usage Minimum 0xE0 .. Maximum 0xE7). */
    const val MOD_NONE = 0x00
    const val MOD_LEFT_CTRL = 0x01
    const val MOD_LEFT_SHIFT = 0x02
    const val MOD_LEFT_ALT = 0x04
    const val MOD_LEFT_GUI = 0x08
    const val MOD_RIGHT_CTRL = 0x10
    const val MOD_RIGHT_SHIFT = 0x20
    const val MOD_RIGHT_ALT = 0x40
    const val MOD_RIGHT_GUI = 0x80

    /** The highest usage the descriptor's key slots declare. Anything above it is not valid there. */
    const val MAX_KEYCODE = 0x65

    /** One past the last key slot. */
    private const val END_KEY_INDEX = FIRST_KEY_INDEX + MAX_KEYS

    /**
     * Build a report holding [modifiers] and up to [MAX_KEYS] of [keys], in the order given.
     *
     * Zero bytes (no key), anything above [MAX_KEYCODE] and repeats are skipped rather than
     * taking up a slot: a duplicate keycode in two slots is not meaningful, and a key the
     * descriptor does not declare is better dropped here than misread by the host.
     */
    fun build(modifiers: Int, keys: List<Byte>): ByteArray {
        val report = ByteArray(HidConstants.KEYBOARD_REPORT_SIZE)
        report[MODIFIER_INDEX] = (modifiers and 0xFF).toByte()
        var slot = FIRST_KEY_INDEX
        for (key in keys) {
            if (slot >= END_KEY_INDEX) break
            val code = key.toInt() and 0xFF
            if (code == 0 || code > MAX_KEYCODE) continue
            var alreadyHeld = false
            for (i in FIRST_KEY_INDEX until slot) {
                if (report[i] == key) {
                    alreadyHeld = true
                    break
                }
            }
            if (alreadyHeld) continue
            report[slot] = key
            slot++
        }
        return report
    }

    /** The keys held in [report], in slot order, without the empty slots. */
    fun keysOf(report: ByteArray): List<Byte> {
        val last = minOf(report.size, END_KEY_INDEX)
        val held = ArrayList<Byte>(MAX_KEYS)
        for (i in FIRST_KEY_INDEX until last) {
            if (report[i] != HidConstants.KEY_NONE) held += report[i]
        }
        return held
    }

    /** The modifier bits of [report]. */
    fun modifiersOf(report: ByteArray): Int = report[MODIFIER_INDEX].toInt() and 0xFF

    /**
     * True when [report] holds nothing at all — no modifier, no key.
     *
     * This is what "everything released" looks like on the wire, and the queue uses it to decide
     * which reports it must chase hardest: a release that never lands is a stuck key.
     */
    fun isNeutral(report: ByteArray): Boolean {
        for (b in report) if (b != HidConstants.KEY_NONE) return false
        return true
    }
}
