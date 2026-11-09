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

    @Test
    fun aFailedCheckDoesNotOverwriteWhatWasRemembered() {
        // remember() returns null, but the caller only writes it after a settled answer, so a
        // failure leaves the cache alone. Pinned here because the two rules have to agree.
        assertNull(UpdateNotice.remember(UpdateState.Failed("offline")))
        assertFalse(UpdateNotice.isSettled(UpdateState.Failed("offline")))
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
