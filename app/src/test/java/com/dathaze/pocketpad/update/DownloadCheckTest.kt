package com.dathaze.pocketpad.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What must be true of a downloaded file before Android's installer is opened on it.
 *
 * This is the code that only runs once something has already gone wrong, so trying the happy
 * path on a phone proves nothing about it.
 */
class DownloadCheckTest {

    private val sha = "a".repeat(64)
    private val otherSha = "b".repeat(64)

    private fun message(verdict: DownloadVerdict): String = when (verdict) {
        is DownloadVerdict.Rejected -> verdict.message
        DownloadVerdict.Ok -> throw AssertionError("expected a rejection, got Ok")
    }

    // ------------------------------------------------------------ the bytes

    @Test
    fun aCompleteMatchingDownloadIsAccepted() {
        assertSame(
            DownloadVerdict.Ok,
            DownloadCheck.verifyBytes(1000, 1000, sha, sha)
        )
    }

    @Test
    fun anInterruptedDownloadIsRejectedAndSaysSo() {
        val verdict = DownloadCheck.verifyBytes(9_000_000, 4_100_000, sha, sha)
        val text = message(verdict)
        assertTrue("says how far it got: $text", text.contains("4100000"))
        assertTrue("says how far it should have got: $text", text.contains("9000000"))
        assertTrue("tells the user what to do: $text", text.contains("try again"))
    }

    @Test
    fun anEmptyDownloadIsRejected() {
        assertTrue(DownloadCheck.verifyBytes(9000, 0, null, sha) is DownloadVerdict.Rejected)
        assertTrue(DownloadCheck.verifyBytes(0, 0, null, sha) is DownloadVerdict.Rejected)
    }

    @Test
    fun aFileLongerThanPromisedIsAlsoRejected() {
        // Not just "cut short": any disagreement with the published length means the bytes
        // are not the ones that were checked.
        assertTrue(
            DownloadCheck.verifyBytes(1000, 1001, sha, sha) is DownloadVerdict.Rejected
        )
    }

    @Test
    fun theRightLengthWithTheWrongContentIsStillRejected() {
        val text = message(DownloadCheck.verifyBytes(1000, 1000, sha, otherSha))
        assertTrue("names the checksum: $text", text.contains("checksum"))
        assertTrue("says nothing was installed: $text", text.contains("not installed"))
    }

    @Test
    fun checksumComparisonIgnoresCase() {
        assertSame(
            DownloadVerdict.Ok,
            DownloadCheck.verifyBytes(10, 10, sha.uppercase(), sha)
        )
    }

    @Test
    fun aReleaseWithoutAChecksumIsStillVerifiedByLength() {
        // Older releases published no checksum; they must still be installable, and a
        // truncated one must still be caught.
        assertSame(DownloadVerdict.Ok, DownloadCheck.verifyBytes(1000, 1000, null, sha))
        assertTrue(
            DownloadCheck.verifyBytes(1000, 400, null, sha) is DownloadVerdict.Rejected
        )
    }

    @Test
    fun anUnknownLengthFallsBackToTheChecksum() {
        // GitHub did not report a size, so length proves nothing — but the checksum still does.
        assertSame(DownloadVerdict.Ok, DownloadCheck.verifyBytes(0, 1234, sha, sha))
        assertTrue(
            DownloadCheck.verifyBytes(0, 1234, sha, otherSha) is DownloadVerdict.Rejected
        )
    }

    // --------------------------------------------------------- the identity

    private val pkg = "com.dathaze.pocketpad"

    @Test
    fun theRightAppAtTheOfferedVersionIsAccepted() {
        assertSame(
            DownloadVerdict.Ok,
            DownloadCheck.verifyApk(pkg, 18, pkg, offeredVersionCode = 18, installedVersionCode = 17)
        )
    }

    @Test
    fun aDifferentAppIsNeverInstalled() {
        val text = message(
            DownloadCheck.verifyApk("com.example.other", 18, pkg, 18, 17)
        )
        assertTrue("names what it actually was: $text", text.contains("com.example.other"))
        assertTrue(text.contains("not installed"))
    }

    @Test
    fun aDowngradeIsRefusedBeforeTheInstallerSeesIt() {
        // Android refuses this anyway, but it does so with an error the user cannot act on.
        assertTrue(
            DownloadCheck.verifyApk(pkg, 16, pkg, 16, 17) is DownloadVerdict.Rejected
        )
        assertTrue(
            "the installed version is not an upgrade either",
            DownloadCheck.verifyApk(pkg, 17, pkg, 17, 17) is DownloadVerdict.Rejected
        )
    }

    @Test
    fun aReleaseThatChangedMidDownloadIsRefused() {
        // Offered 18, got 19: the rolling release was republished while downloading. 19 is
        // newer, so it would install — but it is not what the user was shown and not what was
        // checksummed, so it is refused and the check is run again.
        val text = message(DownloadCheck.verifyApk(pkg, 19, pkg, 18, 17))
        assertTrue("explains the mismatch: $text", text.contains("changed while downloading"))
        assertTrue(text.contains("18"))
        assertTrue(text.contains("19"))
    }

    @Test
    fun aFileAndroidCannotReadAsAnAppIsRefused() {
        assertTrue(
            DownloadCheck.verifyApk(null, null, pkg, 18, 17) is DownloadVerdict.Rejected
        )
        assertTrue(
            DownloadCheck.verifyApk(pkg, null, pkg, 18, 17) is DownloadVerdict.Rejected
        )
        assertTrue(
            DownloadCheck.verifyApk(null, 18, pkg, 18, 17) is DownloadVerdict.Rejected
        )
    }

    @Test
    fun theWrongAppIsCaughtEvenWhenItsVersionLooksRight() {
        // Package identity is checked before the numbers, so a different app carrying a
        // plausible version code cannot slip through.
        assertTrue(
            DownloadCheck.verifyApk("com.dathaze.pocketpad.debug", 18, pkg, 18, 17)
                is DownloadVerdict.Rejected
        )
    }

    // -------------------------------------------------------------- hex

    @Test
    fun checksumsAreRenderedAsLowerCaseHex() {
        assertEquals("", DownloadCheck.toHex(byteArrayOf()))
        assertEquals("00", DownloadCheck.toHex(byteArrayOf(0)))
        assertEquals("0f", DownloadCheck.toHex(byteArrayOf(15)))
        // Every byte is two characters, including the ones above 0x7F that are negative as
        // signed bytes — the bug that turns a checksum into "ffffffff...".
        assertEquals("ff", DownloadCheck.toHex(byteArrayOf(-1)))
        assertEquals("80", DownloadCheck.toHex(byteArrayOf(-128)))
        assertEquals("0a1bff", DownloadCheck.toHex(byteArrayOf(10, 27, -1)))
    }

    @Test
    fun aRealDigestRendersAsSixtyFourCharacters() {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
        val hex = DownloadCheck.toHex(digest)
        assertEquals(64, hex.length)
        // The published SHA-256 of an empty input, so this pins the encoding, not just its
        // length.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex
        )
    }
}
