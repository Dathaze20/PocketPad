package com.dathaze.pocketpad.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks GitHub for a newer PocketPad and installs it, without leaving the app.
 *
 * Adapted from the updater already proven in Page Wallpaper: ask the releases API for the
 * newest release, compare it against the installed build, download the attached APK, and hand
 * the file to Android's own installer — which still shows its own confirmation, so nothing is
 * ever installed silently.
 *
 * Two things are deliberately different here. Page Wallpaper derives the version code from the
 * release tag by arithmetic; PocketPad's version code is assigned by hand and does not follow
 * from its version name, so the number is read out of a manifest the workflow generates from
 * the built APK instead (see [PublishedVersion]). And the download is checked against a
 * published SHA-256 and then re-read as a package before the installer is opened, rather than
 * against its length alone.
 *
 * Every method here blocks. Call them off the main thread — [UpdateFlow] does.
 */
class AppUpdater(private val context: Context) {

    /** The versionCode of the running build, which the published one is compared against. */
    val installedVersionCode: Int
        get() = runCatching {
            // minSdk is 28, so longVersionCode is always present and the deprecated
            // versionCode field is never read.
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(0)

    val installedVersionName: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifEmpty { "unknown" }

    /** True once the user has allowed this app to install apps; the switch lives in Settings. */
    fun canInstallPackages(): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * Opens the Android settings screen where installing from this app is allowed.
     *
     * Returns false when the phone has no such screen, so the caller can say so rather than
     * leaving the user looking at a button that did nothing.
     */
    fun openInstallPermissionSettings(): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    // ------------------------------------------------------------------ check

    /**
     * Asks GitHub for the newest release and works out whether it is newer than this build.
     *
     * Returns [UpdateState.Failed] rather than throwing, so the dialog has exactly one kind of
     * thing to render.
     */
    fun check(): UpdateState {
        val body = fetchText("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            .getOrElse { return UpdateState.Failed(describe(it)) }

        val release = ReleaseMetadata.parseRelease(body)
            ?: return UpdateState.Failed("GitHub sent a release this app could not read.")

        // The authoritative version. Without it there is no number that can honestly be
        // compared against the installed build — see PublishedVersion.
        val manifestAsset = release.asset(UpdateVersion.VERSION_MANIFEST_ASSET)
            ?: return UpdateState.Failed(
                "The published release does not say which version it is, so PocketPad cannot " +
                    "tell whether it is newer. It was built before in-app updates existed; the " +
                    "next build will publish one."
            )

        if (!UpdateAssets.isTrustedUrl(manifestAsset.url)) {
            return UpdateState.Failed("That release points somewhere PocketPad will not fetch from.")
        }

        val manifestBody = fetchText(manifestAsset.url)
            .getOrElse { return UpdateState.Failed(describe(it)) }
        val published = ReleaseMetadata.parseVersionManifest(manifestBody)
            ?: return UpdateState.Failed("The release's version information is unreadable.")

        if (!UpdateVersion.isNewer(published.versionCode, installedVersionCode)) {
            return UpdateState.UpToDate
        }

        val apkName = UpdateAssets.pickApk(release.assets.map { it.name }, published.apkName)
            ?: return UpdateState.Failed(
                "Version ${published.versionName} is published, but its APK " +
                    "(${published.apkName}) is not attached to the release."
            )
        val apk = release.asset(apkName)
            ?: return UpdateState.Failed("That release has no installable APK attached.")
        if (!UpdateAssets.isTrustedUrl(apk.url)) {
            return UpdateState.Failed("That release points somewhere PocketPad will not fetch from.")
        }

        return UpdateState.Available(
            AvailableUpdate(
                versionCode = published.versionCode,
                versionName = published.versionName,
                notes = release.notes.take(NOTES_LIMIT).trim(),
                apkUrl = apk.url,
                apkName = apk.name,
                apkBytes = apk.size,
                apkSha256 = published.apkSha256,
            )
        )
    }

    // --------------------------------------------------------------- download

    /**
     * Downloads [update], verifies it, and hands it to Android's installer.
     *
     * Nothing reaches the installer unverified: the bytes are checked against the length and
     * checksum the release published, and the file is then re-read as a package to confirm it
     * really is this app at the version that was offered. A truncated or substituted APK fails
     * at the installer with a message that looks like the app is broken rather than like the
     * download was, which is why it is stopped here instead.
     */
    fun downloadAndInstall(update: AvailableUpdate, onProgress: (Int) -> Unit): UpdateState {
        if (!UpdateAssets.isTrustedUrl(update.apkUrl)) {
            return UpdateState.Failed("An update may only be fetched from GitHub over https.")
        }
        // Asked again here, not just before offering: the user may have revoked it, and the
        // installer would otherwise open onto a dead end.
        if (!canInstallPackages()) return UpdateState.NeedsInstallPermission

        val dir = File(context.cacheDir, UPDATE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return UpdateState.Failed("Could not make room for the download.")
        }
        // Keep one update on disk, so a half-finished earlier attempt can never be mistaken
        // for a complete file.
        dir.listFiles()?.forEach { if (!it.delete()) Log.w(TAG, "stale update left: ${it.name}") }

        val target = File(dir, UpdateAssets.safeFileName(update.apkName))
        var connection: HttpURLConnection? = null
        try {
            connection = openFollowingRedirects(update.apkUrl)
            val reported = connection.contentLengthLong
            val expected = if (update.apkBytes > 0) update.apkBytes else reported
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPercent = -1

            connection.inputStream.buffered().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (expected > 0) {
                            val percent = ((done * 100) / expected).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }

            val bytes = DownloadCheck.verifyBytes(
                expectedBytes = expected,
                actualBytes = target.length(),
                expectedSha256 = update.apkSha256,
                actualSha256 = DownloadCheck.toHex(digest.digest()),
            )
            if (bytes is DownloadVerdict.Rejected) {
                target.delete()
                return UpdateState.Failed(bytes.message)
            }

            val identity = DownloadCheck.verifyApk(
                actualPackage = archivePackageName(target),
                actualVersionCode = archiveVersionCode(target),
                expectedPackage = context.packageName,
                offeredVersionCode = update.versionCode,
                installedVersionCode = installedVersionCode,
            )
            if (identity is DownloadVerdict.Rejected) {
                target.delete()
                return UpdateState.Failed(identity.message)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
            val install = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(install)
            return UpdateState.ReadyToInstall
        } catch (e: Exception) {
            Log.w(TAG, "Update download failed", e)
            target.delete()
            return UpdateState.Failed(describe(e))
        } finally {
            connection?.disconnect()
        }
    }

    /** What Android reads out of the downloaded file, without installing it. */
    private fun archivePackageName(apk: File): String? = runCatching {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
    }.getOrNull()

    private fun archiveVersionCode(apk: File): Int? = runCatching {
        context.packageManager
            .getPackageArchiveInfo(apk.absolutePath, 0)
            ?.longVersionCode
            ?.toInt()
    }.getOrNull()

    // ------------------------------------------------------------------- http

    private fun fetchText(url: String): Result<String> = runCatching {
        val connection = openFollowingRedirects(url, accept = "application/vnd.github+json")
        try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Opens [url], following redirects by hand.
     *
     * `HttpURLConnection` will not follow a redirect across hosts by itself, and GitHub answers
     * an asset request with exactly that: a redirect to its object store. Every hop is checked
     * against [UpdateAssets.isTrustedUrl] — following one down to plain http, or off to another
     * site, would hand an installable APK to whatever is there.
     */
    private fun openFollowingRedirects(
        url: String,
        accept: String = "application/octet-stream, */*",
    ): HttpURLConnection {
        var current = url
        var hops = 0
        while (true) {
            if (!UpdateAssets.isTrustedUrl(current)) {
                throw IOException("refusing to fetch an update from $current")
            }
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "PocketPad")
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308,
                -> {
                    val next = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (next == null) throw IOException("a redirect with nowhere to go")
                    if (++hops > MAX_REDIRECTS) throw IOException("too many redirects")
                    // Resolved against the current URL, because a Location header is allowed
                    // to be a path rather than a whole address.
                    current = URL(URL(current), next).toString()
                }

                HttpURLConnection.HTTP_OK -> return connection

                else -> {
                    connection.disconnect()
                    throw HttpStatusException(code)
                }
            }
        }
    }

    /** One sentence the user can act on, instead of a stack trace's first line. */
    private fun describe(failure: Throwable): String = when {
        failure is HttpStatusException && failure.code == 404 ->
            "No release has been published yet."
        failure is HttpStatusException && (failure.code == 403 || failure.code == 429) ->
            "GitHub is rate-limiting this check — try again in a few minutes."
        failure is HttpStatusException ->
            "GitHub answered ${failure.code}."
        else ->
            "Could not reach GitHub — check your connection."
    }

    /** Carries the status code, so a 404 and a 403 can be told apart in [describe]. */
    private class HttpStatusException(val code: Int) : IOException("the server answered $code")

    companion object {
        /** Where PocketPad releases are published; the update button looks nowhere else. */
        const val GITHUB_REPO = "Dathaze20/PocketPad"

        /** Matches the `cache-path` in res/xml/file_paths.xml. */
        const val UPDATE_DIR = "updates"

        private const val TAG = "AppUpdater"
        private const val MAX_REDIRECTS = 5
        private const val NOTES_LIMIT = 400
    }
}
