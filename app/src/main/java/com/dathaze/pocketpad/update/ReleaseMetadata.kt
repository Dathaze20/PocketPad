package com.dathaze.pocketpad.update

import org.json.JSONArray
import org.json.JSONObject

/** One file attached to a GitHub release. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** The parts of a GitHub release this app reads. */
data class Release(val tag: String, val notes: String, val assets: List<ReleaseAsset>) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}

/**
 * Reads GitHub's release JSON and this project's version manifest.
 *
 * Kept apart from [AppUpdater] so the awkward cases — a release with no assets, a manifest
 * missing its version code, a body that is not JSON at all — can be tested as the pure string
 * handling they are, rather than only being reached when a real server happens to misbehave.
 *
 * Nothing here throws. Every malformed input returns null, and the caller turns that into a
 * message, because an updater that crashes on a bad response is worse than one that says it
 * could not read it.
 */
object ReleaseMetadata {

    /** Parse the body of `GET /repos/{owner}/{repo}/releases/latest`, or null if unreadable. */
    fun parseRelease(body: String?): Release? {
        val root = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return null
        val tag = root.optString("tag_name").trim()
        if (tag.isEmpty()) return null

        val assets = root.optJSONArray("assets") ?: JSONArray()
        val parsed = (0 until assets.length()).mapNotNull { index ->
            val entry = assets.optJSONObject(index) ?: return@mapNotNull null
            val name = entry.optString("name").trim()
            val url = entry.optString("browser_download_url").trim()
            if (name.isEmpty() || url.isEmpty()) null
            else ReleaseAsset(name, url, entry.optLong("size", 0L))
        }
        return Release(tag = tag, notes = root.optString("body").trim(), assets = parsed)
    }

    /**
     * Parse `version.json`, or null when it does not say what it must.
     *
     * A manifest without a usable `versionCode` is not a manifest: the whole point of it is to
     * carry the one number the comparison is made on. Reading it as "0" and carrying on would
     * turn every check into "you are up to date" and hide the real problem for good.
     */
    fun parseVersionManifest(body: String?): PublishedVersion? {
        val root = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return null

        val code = root.optInt("versionCode", -1)
        if (code <= 0) return null

        val apk = root.optString("apk").trim()
        if (apk.isEmpty()) return null

        val name = root.optString("versionName").trim().ifEmpty { code.toString() }

        // Optional: releases published before the checksum was added still install, they are
        // just verified by length alone. A blank or malformed value is treated as absent rather
        // than as a checksum that will never match.
        val sha = root.optString("apkSha256").trim().lowercase()
        val usableSha = sha.takeIf { it.length == 64 && it.all(::isHex) }

        return PublishedVersion(
            versionCode = code,
            versionName = name,
            apkName = apk,
            apkSha256 = usableSha,
        )
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f'
}
