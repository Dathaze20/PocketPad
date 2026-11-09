package com.dathaze.pocketpad.update

/**
 * When PocketPad is allowed to look for an update on its own, and when it may say so.
 *
 * Ported from the behaviour My Music settled on, with the same rules and the same windows:
 * one quiet check a day, a small strip when something is actually installable, and a dismissal
 * that means "not today" rather than "never".
 *
 * None of the *finding* happens here. [AppUpdater] still does that, with its existing checks
 * intact — official repository URL, trusted redirects only, published SHA-256, size, package
 * identity and version. This object decides only whether to go to the network at all and
 * whether to put a strip on screen, so those decisions can be tested without one.
 *
 * Everything is a pure function of values the caller passes in, including the clock. Nothing
 * here reads storage, the network or `System.currentTimeMillis`.
 */
object UpdateNotice {

    /** At most one automatic request a day. The manual button ignores this entirely. */
    const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

    /** Dismissing the strip hides that version for a day, not for good. */
    const val SNOOZE_MS = 24L * 60 * 60 * 1000

    /**
     * How long after launch the check runs.
     *
     * Long enough to be clear of startup and of the user getting into a game; the point is
     * that this never competes with the Bluetooth connection coming up.
     */
    const val START_DELAY_MS = 6_000L

    /** What was found last time, kept so the strip survives a relaunch without a request. */
    data class Remembered(
        val versionCode: Int,
        val versionName: String,
        val apkBytes: Long,
    )

    /**
     * Whether an automatic check may go to the network.
     *
     * [lastCheckedAt] is 0 when nothing has ever been recorded.
     */
    fun shouldCheckNetwork(lastCheckedAt: Long, now: Long): Boolean {
        if (lastCheckedAt <= 0L) return true
        // A clock that has gone backwards (timezone change, manual set) would otherwise lock
        // checking out for a day. Treat anything in the future as "no idea" and check.
        if (lastCheckedAt > now) return true
        return now - lastCheckedAt >= CHECK_EVERY_MS
    }

    /**
     * Whether the result of a check is worth remembering as "asked today".
     *
     * A failure is not. No connection, GitHub having a bad day, a rate limit — none of that is
     * an answer, and recording it would mean a day of not looking because of one bad moment.
     */
    fun isSettled(state: UpdateState): Boolean = when (state) {
        is UpdateState.Available, UpdateState.UpToDate -> true
        else -> false
    }

    /** Whether this version was dismissed recently enough that it should stay hidden. */
    fun snoozeActive(
        snoozedVersionCode: Int,
        snoozedAt: Long,
        versionCode: Int,
        now: Long,
    ): Boolean {
        if (snoozedVersionCode != versionCode) return false
        if (snoozedAt <= 0L) return false
        if (snoozedAt > now) return false
        return now - snoozedAt < SNOOZE_MS
    }

    /**
     * Whether the strip should be on screen.
     *
     * [installedVersionCode] is checked again here and not taken on trust: a remembered result
     * can outlive the update it describes, because the user may have installed it by hand in
     * between.
     */
    fun shouldShowBanner(
        found: Remembered?,
        installedVersionCode: Int,
        snoozedVersionCode: Int,
        snoozedAt: Long,
        now: Long,
    ): Boolean {
        if (found == null) return false
        if (!UpdateVersion.isNewer(found.versionCode, installedVersionCode)) return false
        return !snoozeActive(snoozedVersionCode, snoozedAt, found.versionCode, now)
    }

    /**
     * Whether the settings row should carry its "new version" badge.
     *
     * Deliberately not subject to the snooze. Dismissing the strip means "stop interrupting
     * me", not "forget about it" — the menu is where someone goes to look, so the mark stays.
     */
    fun shouldMarkSettings(found: Remembered?, installedVersionCode: Int): Boolean {
        if (found == null) return false
        return UpdateVersion.isNewer(found.versionCode, installedVersionCode)
    }

    /** What an [UpdateState] describes, on its own, with no regard for what came before. */
    fun remember(state: UpdateState): Remembered? = when (state) {
        is UpdateState.Available -> Remembered(
            versionCode = state.update.versionCode,
            versionName = state.update.versionName,
            apkBytes = state.update.apkBytes,
        )
        else -> null
    }

    /**
     * What storage should hold after a check, given what it held before.
     *
     * The distinction [remember] does not make, and the one that matters: a check that did not
     * happen is not the same as a check that found nothing.
     *
     *  - **Available** — remember it.
     *  - **Up to date** — a successful check proved the app is current, so forget what was
     *    remembered. Without this a strip for an already-installed version would reappear.
     *  - **Failed, or still running** — nothing was learned. Keep what was there. Losing a
     *    verified, still-newer update because the phone was briefly offline is exactly the
     *    failure this prevents; the user would then see nothing until the next successful
     *    check, with no way to tell why.
     *
     * In every case a remembered update that is no longer newer than [installedVersionCode] is
     * dropped — the user may have installed it by hand since.
     */
    fun cacheAfter(
        state: UpdateState,
        existing: Remembered?,
        installedVersionCode: Int,
    ): Remembered? {
        val next = when (state) {
            is UpdateState.Available -> remember(state)
            UpdateState.UpToDate -> null
            else -> existing
        }
        if (next == null) return null
        return if (UpdateVersion.isNewer(next.versionCode, installedVersionCode)) next else null
    }
}
