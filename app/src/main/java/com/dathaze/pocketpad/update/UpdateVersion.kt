package com.dathaze.pocketpad.update

import java.util.Locale

/**
 * What a published release says it is.
 *
 * PocketPad's `versionCode` is assigned by hand in `build.gradle` and is deliberately *not*
 * derived from the version name: 2.3.0 is versionCode 17, not 20300. So the tag-to-code
 * arithmetic Page Wallpaper uses cannot be reused here — applied to PocketPad it would compute
 * 20300 for "2.3.0", compare it against the installed 17, and offer an update to the build
 * already on the phone, over and over.
 *
 * The only number that may be compared against the installed build is the one the published APK
 * actually carries. The release workflow reads it back out of the APK it just built (`aapt2 dump
 * badging`) and publishes it as [UpdateVersion.VERSION_MANIFEST_ASSET] beside the APK, so the
 * two cannot drift: the manifest is derived from the artifact, not written alongside it by hand.
 */
data class PublishedVersion(
    /** Android's own upgrade number, read out of the published APK. */
    val versionCode: Int,
    /** Human-facing name, for the dialog. */
    val versionName: String,
    /** Which asset in the release is the APK this manifest describes. */
    val apkName: String,
    /**
     * Lower-case hex SHA-256 of that APK. Required: a manifest without one is refused by
     * [ReleaseMetadata.parseVersionManifest] rather than read with this left empty.
     */
    val apkSha256: String,
)

object UpdateVersion {

    /** The asset a release publishes to say, authoritatively, which version it is. */
    const val VERSION_MANIFEST_ASSET = "version.json"

    /**
     * True when [availableCode] names a build newer than [installedCode].
     *
     * Strictly greater, which is also exactly how Android decides whether an APK may be
     * installed over another. Equal is not an update — offering it would mean the button
     * proposes the build already running, every time it is tapped. Lower is a downgrade, which
     * Android refuses anyway; refusing it here means saying so plainly instead of letting the
     * installer throw up an error the user cannot act on.
     *
     * A null code means the release did not say what version it is. That is never an update:
     * guessing would be how a phone ends up downloading an APK it already has.
     */
    fun isNewer(availableCode: Int?, installedCode: Int): Boolean =
        availableCode != null && availableCode > installedCode
}

/** A release that has an installable APK attached, and says which version it is. */
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkBytes: Long,
    /** Always present; see [PublishedVersion.apkSha256]. */
    val apkSha256: String,
) {
    /** "14.4 MB", or empty when the release did not report a size. */
    val readableSize: String
        // Locale.US on purpose: the decimal separator must be a dot whatever the phone's
        // language is, or the test for this passes only on some machines.
        get() = if (apkBytes > 0) {
            String.format(Locale.US, "%.1f MB", apkBytes / 1048576.0)
        } else {
            ""
        }
}

/**
 * Where the update flow has got to, so the dialog never has to infer it from nulls.
 *
 * Every terminal state carries the text it should show. Nothing in this file reaches the network
 * or the installer — these are the states, and [AppUpdater] is what moves between them.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** The newest release is the one already installed. */
    data object UpToDate : UpdateState

    data class Available(val update: AvailableUpdate) : UpdateState

    /** Percent is -1 while the server has not said how big the file is. */
    data class Downloading(val percent: Int) : UpdateState

    /** The download finished, was verified, and Android's installer has been handed the file. */
    data object ReadyToInstall : UpdateState

    /** Installing is blocked until the user allows this app to install apps. */
    data object NeedsInstallPermission : UpdateState

    data class Failed(val message: String) : UpdateState
}
