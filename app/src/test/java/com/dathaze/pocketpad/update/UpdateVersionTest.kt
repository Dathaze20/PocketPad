package com.dathaze.pocketpad.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which published build counts as an update.
 *
 * The rule is deliberately the same one Android itself applies — a strictly higher versionCode
 * — because anything else would mean the app offers installs that Android then refuses.
 */
class UpdateVersionTest {

    /**
     * The versionCode PocketPad 2.3.0 shipped as.
     *
     * The pairing matters: 2.3.0 is 17, 2.4.0 is 18, 2.5.0 is 19. None of those numbers
     * follows from its version name, which is the whole reason the published code is read
     * out of the APK rather than computed from a tag. See app/build.gradle.
     */
    private val installed = 17

    @Test
    fun aHigherVersionCodeIsAnUpdate() {
        // 18 is 2.4.0, the build that first carries the updater.
        assertTrue(UpdateVersion.isNewer(18, installed))
        assertTrue(UpdateVersion.isNewer(99, installed))
        assertTrue(UpdateVersion.isNewer(1, installedCode = 0))
    }

    @Test
    fun theInstalledVersionIsNeverOfferedAgain() {
        assertFalse("equal is not an update", UpdateVersion.isNewer(installed, installed))
        // Tapping the button twice must not start offering it the second time.
        repeat(3) { assertFalse(UpdateVersion.isNewer(installed, installed)) }
    }

    @Test
    fun anOlderPublishedBuildIsNotADowngradeOffer() {
        assertFalse(UpdateVersion.isNewer(16, installed))
        assertFalse(UpdateVersion.isNewer(1, installed))
        assertFalse(UpdateVersion.isNewer(0, installed))
    }

    /**
     * The failure this whole design exists to avoid.
     *
     * Page Wallpaper turns its tag into a version code by arithmetic: "2.3.0" becomes 20300.
     * PocketPad's version code is assigned by hand and is 17 at version name 2.3.0, so reusing
     * that rule here would compare 20300 against 17 and offer the running build as an update,
     * every single time the button was tapped.
     */
    @Test
    fun theVersionNameIsNeverUsedAsAVersionCode() {
        val pretendTagArithmetic = 2 * 10000 + 3 * 100 + 0
        assertEquals(20300, pretendTagArithmetic)
        assertTrue(
            "this is what the wrong rule would conclude",
            pretendTagArithmetic > installed
        )
        // The real rule is given the number the APK actually carries, which is equal, so no
        // update is offered.
        assertFalse(UpdateVersion.isNewer(installed, installed))
    }

    @Test
    fun aReleaseThatDoesNotSayItsVersionIsNeverAnUpdate() {
        // Null is what an unreadable or absent version manifest produces. Guessing here is how
        // a phone ends up downloading an APK it already has.
        assertFalse(UpdateVersion.isNewer(null, installed))
        assertFalse(UpdateVersion.isNewer(null, 0))
    }

    @Test
    fun sizeIsReportedInMegabytesOrNotAtAll() {
        val update = AvailableUpdate(
            versionCode = 18,
            versionName = "2.4.0",
            notes = "",
            apkUrl =
                "https://github.com/Dathaze20/PocketPad/releases/download/latest/PocketPad.apk",
            apkName = "PocketPad.apk",
            apkBytes = 14_451_221,
            apkSha256 = "a".repeat(64),
        )
        assertEquals("13.8 MB", update.readableSize)
        assertEquals("", update.copy(apkBytes = 0).readableSize)
        assertEquals("", update.copy(apkBytes = -1).readableSize)
    }

    @Test
    fun theVersionManifestAssetNameIsTheOneTheWorkflowPublishes() {
        // Changing either side alone silently turns every check into "no version information".
        assertEquals("version.json", UpdateVersion.VERSION_MANIFEST_ASSET)
    }
}
