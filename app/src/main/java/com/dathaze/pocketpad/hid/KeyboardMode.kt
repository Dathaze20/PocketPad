package com.dathaze.pocketpad.hid

/**
 * Which keyboard mapping, if any, rides alongside the gamepad reports.
 *
 * All three send the gamepad report (id 1) exactly as before — this only decides what goes out
 * on the keyboard collection (id 2) as well.
 *
 *  - [OFF] — nothing. Pure gamepad; the right choice on a console or a TV that reads the pad.
 *  - [TV] — the original TV menu keys: d-pad to arrows, ✕ to Enter, ○ to Escape, one key at a
 *    time. **Unchanged**, down to the queue it uses.
 *  - [PC] — [PcKeyMap]: every control to a distinct key, up to six at once, diagonals included.
 *
 * ### Migration
 *
 * The mode used to be a single boolean under [LEGACY_TV_PREF]. A build that has only ever seen
 * that key must keep doing what the user last chose, so [fromPrefs] falls back to it. Nobody is
 * silently moved into PC Keys — it is opt-in, and reading a missing [PREF] can never produce it.
 */
enum class KeyboardMode {
    OFF,
    TV,
    PC;

    /** The value stored in preferences. Stable — do not renumber or rename. */
    val storedValue: String get() = name

    companion object {
        /** Preference key holding [storedValue]. */
        const val PREF = "keyboard_mode"

        /** The pre-PC-Keys boolean, kept so an upgrade does not lose the user's choice. */
        const val LEGACY_TV_PREF = "tv_navigation"

        /**
         * Resolve the mode from what preferences hold.
         *
         * [stored] is [PREF] if it has ever been written; [legacyTvOn] is [LEGACY_TV_PREF]. An
         * unrecognised value falls back the same way a missing one does, so a downgrade that
         * wrote something newer cannot wedge the app.
         */
        fun fromPrefs(stored: String?, legacyTvOn: Boolean): KeyboardMode {
            val match = entries.firstOrNull { it.storedValue == stored }
            if (match != null) return match
            return if (legacyTvOn) TV else OFF
        }
    }
}
