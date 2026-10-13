package com.dathaze.pocketpad.update

import java.util.Locale

/**
 * Which file in a release may be downloaded, and from where.
 *
 * An updater hands Android an APK and asks it to replace the running app. Everything about
 * *which* bytes those are is a security decision, so the rules live here, in one place, with no
 * network code around them — they can then be tested directly instead of inferred from a
 * download that happened to work.
 */
object UpdateAssets {

    /**
     * Hosts an update may be fetched from.
     *
     * GitHub answers an asset request with a redirect to its object store, so the download does
     * not finish on the host it started on. Following a redirect anywhere at all would mean the
     * release metadata — which is just text from a server — could point the installer at any
     * file on the internet. The redirect chain is therefore confined to GitHub's own hosts.
     */
    private val ALLOWED_HOSTS = listOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "raw.githubusercontent.com",
    )

    /**
     * True when [url] is an https URL on a host updates may legitimately come from.
     *
     * Checked on the first request and again on every redirect hop. Plain http is refused
     * outright: an APK fetched over http is an APK chosen by whatever sits on the network
     * between the phone and the server.
     */
    fun isTrustedUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val parsed = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
        if (!"https".equals(parsed.scheme, ignoreCase = true)) return false
        val host = parsed.host?.lowercase(Locale.US) ?: return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * The release asset named by the version manifest, or null when the release does not carry
     * it.
     *
     * Deliberately not "the first file ending in .apk". The manifest is generated from the APK
     * the workflow actually built and names it exactly; matching on that name is what ties the
     * version being compared to the bytes being installed. A release that gained some other APK
     * — a debug build, an experiment, an attachment added by hand — must not be able to
     * redirect the update onto it.
     */
    fun pickApk(assetNames: List<String>, wantedName: String): String? {
        val wanted = wantedName.trim()
        if (wanted.isEmpty() || !wanted.endsWith(".apk", ignoreCase = true)) return null
        return assetNames.firstOrNull { it == wanted }
    }

    /**
     * A download filename that cannot escape the cache directory.
     *
     * The name comes from the release, so it is untrusted text. Anything that is not a plain
     * filename character becomes an underscore, which rules out "../" and absolute paths
     * without having to reason about path traversal at the point of use.
     */
    fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')
        return if (cleaned.isEmpty()) "update.apk" else cleaned
    }
}
