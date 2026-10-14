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
     * This is the **redirect-hop** rule, not the rule for where a download may start. GitHub
     * answers an asset request with a redirect to its object store, under a signed path nobody
     * can predict, so a hop can only be checked by host. Plain http is refused outright: an APK
     * fetched over http is an APK chosen by whatever sits on the network in between.
     *
     * Where a download may *start* is a stricter question — see [isOfficialReleaseAsset].
     */
    fun isTrustedUrl(url: String?): Boolean {
        val parsed = httpsUri(url) ?: return false
        val host = parsed.host?.lowercase(Locale.US) ?: return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * True when [url] is an asset of a release of [repo] itself.
     *
     * [isTrustedUrl] alone is not enough for the URL the update *starts* from. Every download
     * link in a release comes out of a JSON document fetched over the network, and "somewhere
     * on github.com" includes every other repository on the site — a gist, a fork, anybody's
     * release. Pinning the start of the chain to this project's own release-download path is
     * what makes the APK that gets installed this project's APK.
     *
     * Only the first request can be pinned this way: after that GitHub redirects to its object
     * store, where the path is a signed blob reference belonging to no repository, and the hops
     * fall back to [isTrustedUrl].
     */
    fun isOfficialReleaseAsset(url: String?, repo: String): Boolean {
        val parsed = httpsUri(url) ?: return false
        // Exactly github.com. A subdomain is fine for a redirect target but is never where a
        // release asset of this repository lives.
        if (!"github.com".equals(parsed.host, ignoreCase = true)) return false
        val path = parsed.path?.lowercase(Locale.US) ?: return false
        // GitHub treats owner and repo case-insensitively in URLs, so the comparison is too.
        val wanted = "/${repo.trim('/').lowercase(Locale.US)}/releases/download/"
        return path.startsWith(wanted) && path.length > wanted.length
    }

    /** [url] as an https URI, or null if it is blank, malformed, or some other scheme. */
    private fun httpsUri(url: String?): java.net.URI? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parsed = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        if (!"https".equals(parsed.scheme, ignoreCase = true)) return null
        return parsed
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
