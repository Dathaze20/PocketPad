package com.dathaze.pocketpad.update

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One file attached to a GitHub release. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** The parts of a GitHub release this app reads. */
data class Release(val tag: String, val notes: String, val assets: List<ReleaseAsset>) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}

/**
 * What a release's `version.json` turned out to be.
 *
 * A manifest is either complete and usable or it is refused outright — there is no partial
 * reading of it. Every field it carries is one the install depends on, so a manifest missing
 * any of them cannot be made safe by falling back to weaker checks.
 */
sealed interface ManifestResult {
    data class Ok(val published: PublishedVersion) : ManifestResult

    /** [reason] is shown to the user as-is; nothing is offered. */
    data class Invalid(val reason: String) : ManifestResult
}

/**
 * Reads GitHub's release JSON and this project's version manifest.
 *
 * Kept apart from [AppUpdater] so the awkward cases — a release with no assets, a manifest
 * missing its version code, a body that is not JSON at all — can be tested as the pure string
 * handling they are, rather than only being reached when a real server happens to misbehave.
 *
 * Nothing here throws. Every malformed input is turned into a refusal, because an updater that
 * crashes on a bad response is worse than one that says it could not read it.
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
     * Parse `version.json`.
     *
     * Every field is required, and a release that publishes a manifest at all must publish a
     * complete one.
     *
     * The checksum in particular **fails closed**. It used to be optional, falling back to
     * verifying the download by its length alone — but length is exactly what an attacker
     * controls for free, and a corrupted or substituted APK of the right size would then have
     * reached the installer. A manifest without a usable SHA-256 is now refused, so the only
     * way to be offered an update is for the release to say precisely which bytes it is.
     */
    fun parseVersionManifest(body: String?): ManifestResult {
        val root = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            ?: return ManifestResult.Invalid("The release's version information is unreadable.")

        val code = root.optInt("versionCode", -1)
        if (code <= 0) {
            return ManifestResult.Invalid("The release does not say which version it is.")
        }

        val apk = root.optString("apk").trim()
        if (apk.isEmpty()) {
            return ManifestResult.Invalid("The release does not say which file to install.")
        }

        val sha = root.optString("apkSha256").trim().lowercase(Locale.US)
        if (sha.length != SHA256_HEX_LENGTH || !sha.all(::isHex)) {
            return ManifestResult.Invalid(
                "The release does not publish a usable checksum for its APK, so the download " +
                    "cannot be verified. Nothing was offered."
            )
        }

        val name = root.optString("versionName").trim().ifEmpty { code.toString() }

        return ManifestResult.Ok(
            PublishedVersion(
                versionCode = code,
                versionName = name,
                apkName = apk,
                apkSha256 = sha,
            )
        )
    }

    private const val SHA256_HEX_LENGTH = 64

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f'
}
