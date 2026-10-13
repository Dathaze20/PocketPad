package com.dathaze.pocketpad.update

import java.util.Locale

/** Whether a downloaded file may be handed to Android's installer. */
sealed interface DownloadVerdict {
    data object Ok : DownloadVerdict

    /** Carries the sentence the user is shown; nothing is installed. */
    data class Rejected(val message: String) : DownloadVerdict
}

/**
 * The checks a downloaded APK must pass before the installer ever sees it.
 *
 * Pure functions on purpose. Verifying a download is exactly the code that only runs when
 * something has already gone wrong — a connection dropped halfway, a release republished
 * mid-download, the wrong file attached — so it is the code least likely to be exercised by
 * trying the happy path on a phone. Here it can be tested directly.
 */
object DownloadCheck {

    /**
     * Did the bytes that arrived match what the release promised?
     *
     * Length is checked first because it is what a cut-off download fails, and it gives a
     * message that says what actually happened. The checksum catches everything else: a
     * corrupted transfer, a cache serving a stale file, a different APK behind the same URL.
     */
    fun verifyBytes(
        expectedBytes: Long,
        actualBytes: Long,
        expectedSha256: String?,
        actualSha256: String,
    ): DownloadVerdict {
        if (actualBytes <= 0L) {
            return DownloadVerdict.Rejected("The download was empty.")
        }
        if (expectedBytes > 0L && actualBytes != expectedBytes) {
            return DownloadVerdict.Rejected(
                "The download arrived cut short — $actualBytes of $expectedBytes bytes. " +
                    "Check your connection and try again."
            )
        }
        val expected = expectedSha256?.lowercase(Locale.US)
        if (expected != null && expected != actualSha256.lowercase(Locale.US)) {
            return DownloadVerdict.Rejected(
                "The downloaded file does not match the release checksum. It was not installed."
            )
        }
        return DownloadVerdict.Ok
    }

    /**
     * Is the downloaded APK really this app, at the version that was offered?
     *
     * Android reads a package name and version code out of an APK without installing it, so
     * this can be checked before the installer is opened rather than discovered by the user
     * when the install fails. Three distinct things are being ruled out:
     *
     *  - a different app, which would install alongside PocketPad rather than update it;
     *  - a build older than the one running, which Android refuses and which would otherwise
     *    present as an unexplained installer error;
     *  - an APK whose version is not the one the check offered, which means the release moved
     *    underneath the download.
     *
     * [actualPackage] and [actualVersionCode] are null when Android could not read the file at
     * all, which is itself a reason not to install it.
     */
    fun verifyApk(
        actualPackage: String?,
        actualVersionCode: Int?,
        expectedPackage: String,
        offeredVersionCode: Int,
        installedVersionCode: Int,
    ): DownloadVerdict {
        if (actualPackage == null || actualVersionCode == null) {
            return DownloadVerdict.Rejected(
                "Android could not read the downloaded file as an app. It was not installed."
            )
        }
        if (actualPackage != expectedPackage) {
            return DownloadVerdict.Rejected(
                "That file is $actualPackage, not PocketPad. It was not installed."
            )
        }
        if (actualVersionCode <= installedVersionCode) {
            return DownloadVerdict.Rejected(
                "That download is version code $actualVersionCode, which is not newer than the " +
                    "installed $installedVersionCode. It was not installed."
            )
        }
        if (actualVersionCode != offeredVersionCode) {
            return DownloadVerdict.Rejected(
                "The release changed while downloading — expected version code " +
                    "$offeredVersionCode but got $actualVersionCode. Check for updates again."
            )
        }
        return DownloadVerdict.Ok
    }

    /** Lower-case hex, the form every checksum in this file is compared in. */
    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
