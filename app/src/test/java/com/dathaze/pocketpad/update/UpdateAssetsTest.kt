package com.dathaze.pocketpad.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which bytes the updater is allowed to fetch, and from where. */
class UpdateAssetsTest {

    // ------------------------------------------------------------------ urls

    @Test
    fun githubReleaseUrlsAreAccepted() {
        assertTrue(
            UpdateAssets.isTrustedUrl(
                "https://github.com/Dathaze20/PocketPad/releases/download/latest/PocketPad.apk"
            )
        )
        assertTrue(
            UpdateAssets.isTrustedUrl(
                "https://api.github.com/repos/Dathaze20/PocketPad/releases/latest"
            )
        )
        // The host a release asset actually redirects to.
        assertTrue(UpdateAssets.isTrustedUrl("https://objects.githubusercontent.com/abc?token=1"))
        assertTrue(
            UpdateAssets.isTrustedUrl("https://release-assets.githubusercontent.com/x/y.apk")
        )
    }

    @Test
    fun plainHttpIsRefusedEvenOnGithub() {
        // An APK fetched over http is an APK chosen by whatever is on the network in between.
        assertFalse(
            UpdateAssets.isTrustedUrl(
                "http://github.com/Dathaze20/PocketPad/releases/download/latest/PocketPad.apk"
            )
        )
        assertFalse(UpdateAssets.isTrustedUrl("http://objects.githubusercontent.com/a.apk"))
    }

    @Test
    fun anyOtherHostIsRefused() {
        // Release metadata is just text from a server; without this, it could point the
        // installer at any file on the internet.
        assertFalse(UpdateAssets.isTrustedUrl("https://example.com/PocketPad.apk"))
        assertFalse(UpdateAssets.isTrustedUrl("https://github.evil.com/PocketPad.apk"))
        assertFalse(UpdateAssets.isTrustedUrl("https://notgithub.com/PocketPad.apk"))
        // A lookalike that ends in the right text but is a different host.
        assertFalse(UpdateAssets.isTrustedUrl("https://mygithub.com/a.apk"))
        // Userinfo cannot be used to make another host read as github.com.
        assertFalse(UpdateAssets.isTrustedUrl("https://github.com@evil.com/a.apk"))
    }

    @Test
    fun aSubdomainOfAnAllowedHostIsAccepted() {
        assertTrue(UpdateAssets.isTrustedUrl("https://cdn.objects.githubusercontent.com/a.apk"))
    }

    @Test
    fun unsupportedAndMalformedUrlsAreRefused() {
        assertFalse(UpdateAssets.isTrustedUrl(null))
        assertFalse(UpdateAssets.isTrustedUrl(""))
        assertFalse(UpdateAssets.isTrustedUrl("   "))
        assertFalse(UpdateAssets.isTrustedUrl("PocketPad.apk"))
        assertFalse(UpdateAssets.isTrustedUrl("ftp://github.com/PocketPad.apk"))
        assertFalse(UpdateAssets.isTrustedUrl("file:///sdcard/PocketPad.apk"))
        assertFalse(UpdateAssets.isTrustedUrl("javascript:alert(1)"))
        assertFalse(UpdateAssets.isTrustedUrl("https://"))
        assertFalse(UpdateAssets.isTrustedUrl("not a url at all"))
    }

    @Test
    fun theSchemeIsMatchedWithoutRegardToCase() {
        assertTrue(UpdateAssets.isTrustedUrl("HTTPS://GitHub.com/a/b/releases/download/l/P.apk"))
    }

    // -------------------------------------------- the official release only

    private val repo = "Dathaze20/PocketPad"

    @Test
    fun thisProjectsOwnReleaseAssetIsAccepted() {
        assertTrue(
            UpdateAssets.isOfficialReleaseAsset(
                "https://github.com/Dathaze20/PocketPad/releases/download/latest/PocketPad.apk",
                repo
            )
        )
        assertTrue(
            "a future tag is still this project's release",
            UpdateAssets.isOfficialReleaseAsset(
                "https://github.com/Dathaze20/PocketPad/releases/download/v9.9.9/version.json",
                repo
            )
        )
        // GitHub treats owner and repo case-insensitively in URLs.
        assertTrue(
            UpdateAssets.isOfficialReleaseAsset(
                "https://github.com/dathaze20/pocketpad/releases/download/latest/PocketPad.apk",
                repo
            )
        )
    }

    /**
     * The reason this exists. Every download link comes out of a JSON document fetched over
     * the network, and "somewhere on github.com" is every repository on the site.
     */
    @Test
    fun anotherRepositorysReleaseIsRefusedEvenThoughItIsOnGithub() {
        for (url in listOf(
            "https://github.com/someone/else/releases/download/latest/PocketPad.apk",
            "https://github.com/Dathaze20/OtherApp/releases/download/latest/PocketPad.apk",
            "https://github.com/Dathaze20/PocketPadEvil/releases/download/latest/PocketPad.apk",
            "https://gist.github.com/Dathaze20/abc/raw/PocketPad.apk",
            "https://raw.githubusercontent.com/Dathaze20/PocketPad/main/PocketPad.apk",
        )) {
            assertFalse(url, UpdateAssets.isOfficialReleaseAsset(url, repo))
            }
    }

    @Test
    fun somethingThatIsNotAReleaseDownloadIsRefused() {
        for (url in listOf(
            "https://github.com/Dathaze20/PocketPad",
            "https://github.com/Dathaze20/PocketPad/releases/latest",
            "https://github.com/Dathaze20/PocketPad/releases/download/",
            "https://github.com/Dathaze20/PocketPad/archive/main.zip",
            "https://github.com/Dathaze20/PocketPad/blob/main/app/debug.keystore",
        )) {
            assertFalse(url, UpdateAssets.isOfficialReleaseAsset(url, repo))
        }
    }

    @Test
    fun theStartOfTheChainIsStricterThanTheRedirectHops() {
        // objects.githubusercontent.com is where an asset request lands, so it has to be
        // allowed as a hop — but a download may never *start* there, because its path is a
        // signed blob reference belonging to no repository.
        val objectStore = "https://objects.githubusercontent.com/abc?token=1"
        assertTrue("allowed as a hop", UpdateAssets.isTrustedUrl(objectStore))
        assertFalse("not allowed as a start", UpdateAssets.isOfficialReleaseAsset(objectStore, repo))
    }

    @Test
    fun anOfficialPathOverPlainHttpOrNonsenseIsStillRefused() {
        assertFalse(
            UpdateAssets.isOfficialReleaseAsset(
                "http://github.com/Dathaze20/PocketPad/releases/download/latest/PocketPad.apk",
                repo
            )
        )
        assertFalse(UpdateAssets.isOfficialReleaseAsset(null, repo))
        assertFalse(UpdateAssets.isOfficialReleaseAsset("", repo))
        assertFalse(UpdateAssets.isOfficialReleaseAsset("not a url", repo))
        // Userinfo cannot be used to make another host read as github.com.
        assertFalse(
            UpdateAssets.isOfficialReleaseAsset(
                "https://github.com@evil.com/Dathaze20/PocketPad/releases/download/l/P.apk",
                repo
            )
        )
    }

    // ---------------------------------------------------------------- assets

    @Test
    fun theApkNamedByTheManifestIsTheOneChosen() {
        val names = listOf("version.json", "PocketPad.apk")
        assertEquals("PocketPad.apk", UpdateAssets.pickApk(names, "PocketPad.apk"))
    }

    /**
     * Deliberately not "the first file ending in .apk". The manifest names the APK its version
     * code was read out of; matching on that name is what ties the version being compared to
     * the bytes being installed.
     */
    @Test
    fun someOtherApkInTheReleaseCannotHijackTheUpdate() {
        val names = listOf("experiment.apk", "PocketPad.apk", "version.json")
        assertEquals("PocketPad.apk", UpdateAssets.pickApk(names, "PocketPad.apk"))
        // And if the manifest names something that is not attached, nothing is substituted.
        assertNull(UpdateAssets.pickApk(names, "PocketPad-2.4.0.apk"))
    }

    @Test
    fun aReleaseWithNoApkOffersNothing() {
        assertNull(UpdateAssets.pickApk(emptyList(), "PocketPad.apk"))
        assertNull(UpdateAssets.pickApk(listOf("version.json"), "PocketPad.apk"))
        assertNull(UpdateAssets.pickApk(listOf("PocketPad.aab"), "PocketPad.apk"))
    }

    @Test
    fun aManifestNamingSomethingThatIsNotAnApkIsRefused() {
        val names = listOf("PocketPad.apk", "notes.txt")
        assertNull(UpdateAssets.pickApk(names, "notes.txt"))
        assertNull(UpdateAssets.pickApk(names, ""))
        assertNull(UpdateAssets.pickApk(names, "   "))
    }

    @Test
    fun theAssetNameIsMatchedExactly() {
        // Not case-insensitively and not by prefix: GitHub asset names are exact, and a loose
        // match is a way for a second attachment to be picked up instead.
        val names = listOf("PocketPad.apk")
        assertNull(UpdateAssets.pickApk(names, "pocketpad.apk"))
        assertNull(UpdateAssets.pickApk(names, "Pocket.apk"))
    }

    // ------------------------------------------------------------- file name

    @Test
    fun aDownloadCannotBeWrittenOutsideTheCacheDirectory() {
        // The name comes from the release, so it is untrusted text.
        assertEquals("PocketPad.apk", UpdateAssets.safeFileName("PocketPad.apk"))
        assertEquals("_etc_passwd", UpdateAssets.safeFileName("../etc/passwd"))
        assertEquals("_data_local_tmp_x.apk", UpdateAssets.safeFileName("/data/local/tmp/x.apk"))
        assertEquals("a_b.apk", UpdateAssets.safeFileName("a b.apk"))
        assertEquals("update.apk", UpdateAssets.safeFileName(""))
        assertEquals("update.apk", UpdateAssets.safeFileName("..."))
        assertTrue(UpdateAssets.safeFileName("../../x").none { it == '/' })
    }
}
