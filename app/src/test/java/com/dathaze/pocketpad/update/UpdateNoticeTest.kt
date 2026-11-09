package com.dathaze.pocketpad.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the automatic update strip: when to ask, when to show, when to stay quiet.
 *
 * All of it is pure, including the clock, so every window can be tested without waiting a day
 * and without a network.
 */
class UpdateNoticeTest {

    private val day = UpdateNotice.CHECK_EVERY_MS
    private val now = 1_800_000_000_000L   // an arbitrary fixed "now"

    private fun found(code: Int = 20, name: String = "2.6.0", bytes: Long = 9_000_000) =
        UpdateNotice.Remembered(code, name, bytes)

    private fun available(code: Int = 20, name: String = "2.6.0", bytes: Long = 9_000_000) =
        UpdateState.Available(
            AvailableUpdate(
                versionCode = code, versionName = name, notes = "",
                apkUrl = "https://github.com/Dathaze20/PocketPad/releases/download/latest/PocketPad.apk",
                apkName = "PocketPad.apk", apkBytes = bytes, apkSha256 = "a".repeat(64),
            )
        )

    // ------------------------------------------------------------- the window

    @Test
    fun theFirstLaunchEverChecks() {
        assertTrue(UpdateNotice.shouldCheckNetwork(lastCheckedAt = 0L, now = now))
    }

    @Test
    fun atMostOneAutomaticRequestADay() {
        assertFalse("just checked", UpdateNotice.shouldCheckNetwork(now, now))
        assertFalse("an hour ago", UpdateNotice.shouldCheckNetwork(now - 3_600_000, now))
        assertFalse("23 hours ago", UpdateNotice.shouldCheckNetwork(now - day + 60_000, now))
        assertTrue("a day later", UpdateNotice.shouldCheckNetwork(now - day, now))
        assertTrue("two days later", UpdateNotice.shouldCheckNetwork(now - 2 * day, now))
    }

    /** A clock that moved backwards must not lock checking out for a day. */
    @Test
    fun aTimestampInTheFutureIsTreatedAsUnknown() {
        assertTrue(UpdateNotice.shouldCheckNetwork(now + day, now))
    }

    @Test
    fun onlyASettledAnswerCountsAsHavingChecked() {
        assertTrue(UpdateNotice.isSettled(available()))
        assertTrue(UpdateNotice.isSettled(UpdateState.UpToDate))

        // Offline, GitHub erroring, a rate limit: not an answer, so it must not buy a day of
        // silence. Try again next launch instead.
        assertFalse(UpdateNotice.isSettled(UpdateState.Failed("no connection")))
        assertFalse(UpdateNotice.isSettled(UpdateState.Idle))
        assertFalse(UpdateNotice.isSettled(UpdateState.Checking))
    }

    // -------------------------------------------------------------- the strip

    @Test
    fun nothingIsShownWhenThereIsNothingToShow() {
        assertFalse(UpdateNotice.shouldShowBanner(null, 19, 0, 0L, now))
    }

    @Test
    fun anUpdateIsShown() {
        assertTrue(
            UpdateNotice.shouldShowBanner(found(code = 20), 19, 0, 0L, now)
        )
    }

    /**
     * A remembered result can outlive the update it describes — the user may have installed it
     * by hand. The installed version is therefore re-checked rather than taken on trust.
     */
    @Test
    fun aRememberedUpdateThatIsNowInstalledIsNotShown() {
        assertFalse(UpdateNotice.shouldShowBanner(found(code = 20), 20, 0, 0L, now))
        assertFalse(
            "nor an older one",
            UpdateNotice.shouldShowBanner(found(code = 18), 19, 0, 0L, now)
        )
    }

    // ------------------------------------------------------------- dismissing

    @Test
    fun dismissingHidesThatVersionForADay() {
        val f = found(code = 20)
        assertFalse(
            "just dismissed",
            UpdateNotice.shouldShowBanner(f, 19, 20, now, now)
        )
        assertFalse(
            "later the same day",
            UpdateNotice.shouldShowBanner(f, 19, 20, now - day / 2, now)
        )
        assertTrue(
            "a day later it may ask again",
            UpdateNotice.shouldShowBanner(f, 19, 20, now - day, now)
        )
    }

    /** "Later" means later, not never — and only for the version that was waved away. */
    @Test
    fun dismissingOneVersionDoesNotHideTheNext() {
        assertTrue(
            UpdateNotice.shouldShowBanner(found(code = 21), 19, 20, now, now)
        )
    }

    @Test
    fun reopeningTheAppDoesNotBringBackADismissedStrip() {
        // The snooze is stored against the version, not against the session, so coming back
        // consults the same window rather than starting fresh.
        val f = found(code = 20)
        repeat(5) {
            assertFalse(UpdateNotice.shouldShowBanner(f, 19, 20, now - 1000, now))
        }
    }

    @Test
    fun aSnoozeWithNoTimestampIsIgnored() {
        assertFalse(UpdateNotice.snoozeActive(20, 0L, 20, now))
    }

    @Test
    fun aSnoozeFromTheFutureIsIgnored() {
        assertFalse(UpdateNotice.snoozeActive(20, now + day, 20, now))
    }

    // --------------------------------------------------------- settings badge

    /**
     * Dismissing the strip means "stop interrupting me", not "forget about it". The menu is
     * where someone goes to look, so the mark there is deliberately not snoozed.
     */
    @Test
    fun theSettingsMarkSurvivesADismissal() {
        assertTrue(UpdateNotice.shouldMarkSettings(found(code = 20), 19))
    }

    @Test
    fun theSettingsMarkDisappearsOnceTheUpdateIsInstalled() {
        assertFalse(UpdateNotice.shouldMarkSettings(found(code = 20), 20))
        assertFalse(UpdateNotice.shouldMarkSettings(null, 19))
    }

    // ------------------------------------------------------------- caching

    @Test
    fun onlyAnAvailableUpdateIsWorthRemembering() {
        val remembered = UpdateNotice.remember(available(code = 20, name = "2.6.0", bytes = 123))
        assertEquals(20, remembered?.versionCode)
        assertEquals("2.6.0", remembered?.versionName)
        assertEquals(123L, remembered?.apkBytes)
    }

    @Test
    fun beingUpToDateClearsWhatWasRemembered() {
        // Otherwise a strip for a version the user already installed would come back on every
        // launch until something else overwrote it.
        assertNull(UpdateNotice.remember(UpdateState.UpToDate))
    }

    /**
     * This test used to assert the two helpers agreed and then *claim in a comment* that the
     * caller only wrote the cache after a settled answer. It did not — `onCheckFinished` wrote
     * `remember(state)` unconditionally, so a failed request erased a verified update. The
     * comment described an invariant nothing enforced, which is worse than no test, because it
     * read as coverage. [UpdateNotice.cacheAfter] is now that invariant, and the tests below
     * exercise it instead of describing it.
     */
    @Test
    fun rememberAloneCannotDistinguishFailureFromBeingUpToDate() {
        assertNull(UpdateNotice.remember(UpdateState.Failed("offline")))
        assertNull(UpdateNotice.remember(UpdateState.UpToDate))
        assertFalse(UpdateNotice.isSettled(UpdateState.Failed("offline")))
    }

    // ------------------------------------------- what storage keeps after a check

    /** The regression: being briefly offline must not lose a verified, still-newer update. */
    @Test
    fun aFailedCheckKeepsTheRememberedUpdate() {
        val cached = found(code = 20)
        val kept = UpdateNotice.cacheAfter(
            state = UpdateState.Failed("no connection"),
            existing = cached,
            installedVersionCode = 19,
        )
        assertEquals("the update must survive a failed request", cached, kept)
    }

    @Test
    fun everyKindOfFailureKeepsIt() {
        val cached = found(code = 20)
        listOf(
            UpdateState.Failed("no connection"),
            UpdateState.Failed("GitHub sent a release this app could not read."),
            UpdateState.Failed("That release has no installable APK attached."),
            UpdateState.Idle,
            UpdateState.Checking,
        ).forEach {
            assertEquals("$it must not clear the cache", cached, UpdateNotice.cacheAfter(it, cached, 19))
        }
    }

    @Test
    fun repeatedFailuresNeverWearTheCacheAway() {
        var cache: UpdateNotice.Remembered? = found(code = 20)
        repeat(50) {
            cache = UpdateNotice.cacheAfter(UpdateState.Failed("offline"), cache, 19)
        }
        assertEquals(20, cache?.versionCode)
    }

    /** The one case that should clear it: a check that succeeded and found nothing newer. */
    @Test
    fun aSuccessfulUpToDateCheckClearsIt() {
        assertNull(UpdateNotice.cacheAfter(UpdateState.UpToDate, found(code = 20), 19))
    }

    @Test
    fun aNewerFindReplacesAnOlderOne() {
        val updated = UpdateNotice.cacheAfter(available(code = 21, name = "2.7.0"), found(code = 20), 19)
        assertEquals(21, updated?.versionCode)
        assertEquals("2.7.0", updated?.versionName)
    }

    /** The other clearing case: the user installed it by hand since it was remembered. */
    @Test
    fun aRememberedUpdateThatIsNoLongerNewerIsDropped() {
        assertNull(
            "installed in the meantime",
            UpdateNotice.cacheAfter(UpdateState.Failed("offline"), found(code = 20), 20)
        )
        assertNull(
            "and an older one too",
            UpdateNotice.cacheAfter(UpdateState.Failed("offline"), found(code = 18), 19)
        )
    }

    @Test
    fun anEmptyCacheStaysEmptyThroughAFailure() {
        assertNull(UpdateNotice.cacheAfter(UpdateState.Failed("offline"), null, 19))
    }

    /**
     * End to end: find an update, go offline for a week of launches, and the strip must still
     * be offered the whole time.
     */
    @Test
    fun anUpdateFoundBeforeGoingOfflineIsStillOfferedAfterwards() {
        var cache = UpdateNotice.cacheAfter(available(code = 20), null, 19)
        repeat(7) { cache = UpdateNotice.cacheAfter(UpdateState.Failed(""), cache, 19) }

        assertTrue(
            "still offered",
            UpdateNotice.shouldShowBanner(cache, 19, 0, 0L, now)
        )
        assertTrue("and still marked in settings", UpdateNotice.shouldMarkSettings(cache, 19))
    }

    @Test
    fun aReleaseWithNoApkNeverBecomesAnOffer() {
        // AppUpdater returns Failed when the release has no installable APK attached, so the
        // notice has nothing to remember and nothing to show.
        val state = UpdateState.Failed("That release has no installable APK attached.")
        assertNull(UpdateNotice.remember(state))
        assertFalse(UpdateNotice.isSettled(state))
        assertFalse(UpdateNotice.shouldShowBanner(UpdateNotice.remember(state), 19, 0, 0L, now))
    }

    @Test
    fun invalidMetadataIsTreatedTheSameWay() {
        val state = UpdateState.Failed("GitHub sent a release this app could not read.")
        assertNull(UpdateNotice.remember(state))
        assertFalse(UpdateNotice.isSettled(state))
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    fun theCheckWaitsLongEnoughToStayOutOfTheWayOfStartup() {
        assertTrue(
            "must not fire during launch",
            UpdateNotice.START_DELAY_MS >= 3_000L
        )
        assertTrue(
            "but should not be so late it never runs in a short session",
            UpdateNotice.START_DELAY_MS <= 15_000L
        )
    }

    @Test
    fun theTwoWindowsAreBothADay() {
        assertEquals(24L * 60 * 60 * 1000, UpdateNotice.CHECK_EVERY_MS)
        assertEquals(24L * 60 * 60 * 1000, UpdateNotice.SNOOZE_MS)
    }

    /**
     * The offline path end to end: a cached find still shows, and a failed check neither
     * clears the cache nor buys a day of silence.
     */
    @Test
    fun offlineWithSomethingCachedStillShowsTheStrip() {
        val cached = found(code = 20)
        val failure = UpdateState.Failed("")

        assertTrue("the cached find still shows", UpdateNotice.shouldShowBanner(cached, 19, 0, 0L, now))
        assertFalse("and the failure is not recorded", UpdateNotice.isSettled(failure))
        assertTrue(
            "so the next launch tries again",
            UpdateNotice.shouldCheckNetwork(lastCheckedAt = 0L, now = now)
        )
    }
}
