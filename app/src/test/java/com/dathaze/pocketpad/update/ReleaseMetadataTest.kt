package com.dathaze.pocketpad.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading what GitHub and the release workflow actually send.
 *
 * These are the cases that only happen when something has gone wrong — a release with no
 * assets, a manifest missing its version code, a body that is not JSON — so they would
 * otherwise never be exercised until a user hit one.
 */
class ReleaseMetadataTest {

    /** The manifest's version, or a failure naming what came back instead. */
    private fun ok(body: String?): PublishedVersion =
        when (val r = ReleaseMetadata.parseVersionManifest(body)) {
            is ManifestResult.Ok -> r.published
            is ManifestResult.Invalid -> throw AssertionError("expected Ok, got: ${r.reason}")
        }

    /** The reason a manifest was refused, or a failure if it was accepted. */
    private fun refused(body: String?): String =
        when (val r = ReleaseMetadata.parseVersionManifest(body)) {
            is ManifestResult.Invalid -> r.reason
            is ManifestResult.Ok -> throw AssertionError("expected a refusal, got ${r.published}")
        }

    private val realRelease = """
        {
          "tag_name": "latest",
          "name": "PocketPad 2.4.0 — newest build",
          "body": "PocketPad 2.4.0 (versionCode 18).",
          "assets": [
            {
              "name": "PocketPad.apk",
              "size": 9123456,
              "browser_download_url":
                "https://github.com/Dathaze20/PocketPad/releases/download/latest/PocketPad.apk"
            },
            {
              "name": "version.json",
              "size": 112,
              "browser_download_url":
                "https://github.com/Dathaze20/PocketPad/releases/download/latest/version.json"
            }
          ]
        }
    """.trimIndent()

    private val realManifest = """
        {
          "versionCode": 18,
          "versionName": "2.4.0",
          "apk": "PocketPad.apk",
          "apkSha256": "3b1f2c4d5e6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e"
        }
    """.trimIndent()

    // ------------------------------------------------------------- a release

    @Test
    fun aRealReleaseIsReadWithBothItsAssets() {
        val release = ReleaseMetadata.parseRelease(realRelease)
        assertNotNull(release)
        requireNotNull(release)
        assertEquals("latest", release.tag)
        assertEquals("PocketPad 2.4.0 (versionCode 18).", release.notes)
        assertEquals(2, release.assets.size)

        val apk = release.asset("PocketPad.apk")
        assertNotNull(apk)
        assertEquals(9123456L, apk!!.size)
        assertEquals(
            "https://github.com/Dathaze20/PocketPad/releases/download/latest/PocketPad.apk",
            apk.url
        )
        assertNotNull(release.asset(UpdateVersion.VERSION_MANIFEST_ASSET))
        assertNull("an asset that is not there is not invented", release.asset("other.apk"))
    }

    @Test
    fun aReleaseWithNoAssetsIsStillReadableButOffersNothing() {
        val release = ReleaseMetadata.parseRelease("""{"tag_name":"latest","assets":[]}""")
        assertNotNull(release)
        assertEquals(0, release!!.assets.size)
        assertNull(release.asset("PocketPad.apk"))
    }

    @Test
    fun aReleaseWithNoAssetsFieldAtAllIsNotACrash() {
        val release = ReleaseMetadata.parseRelease("""{"tag_name":"latest"}""")
        assertNotNull(release)
        assertEquals(0, release!!.assets.size)
        assertEquals("", release.notes)
    }

    @Test
    fun anAssetMissingItsNameOrUrlIsDroppedRatherThanHalfUsed() {
        val release = ReleaseMetadata.parseRelease(
            """
            {"tag_name":"latest","assets":[
              {"name":"","browser_download_url":"https://github.com/a.apk","size":1},
              {"name":"no-url.apk","size":2},
              {"name":"PocketPad.apk","browser_download_url":"https://github.com/b.apk","size":3}
            ]}
            """.trimIndent()
        )
        assertNotNull(release)
        assertEquals(listOf("PocketPad.apk"), release!!.assets.map { it.name })
    }

    @Test
    fun invalidReleaseMetadataIsRefusedRatherThanGuessedAt() {
        assertNull("not JSON at all", ReleaseMetadata.parseRelease("<html>404</html>"))
        assertNull("empty body", ReleaseMetadata.parseRelease(""))
        assertNull("null body", ReleaseMetadata.parseRelease(null))
        assertNull("a JSON array, not an object", ReleaseMetadata.parseRelease("[]"))
        assertNull("truncated mid-object", ReleaseMetadata.parseRelease("""{"tag_name":"lat"""))
        assertNull("no tag", ReleaseMetadata.parseRelease("""{"assets":[]}"""))
        assertNull("blank tag", ReleaseMetadata.parseRelease("""{"tag_name":"  "}"""))
    }

    // ------------------------------------------------------------ a manifest

    @Test
    fun theVersionManifestIsReadInFull() {
        val published = ok(realManifest)
        assertEquals(18, published.versionCode)
        assertEquals("2.4.0", published.versionName)
        assertEquals("PocketPad.apk", published.apkName)
        assertEquals(
            "3b1f2c4d5e6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e",
            published.apkSha256
        )
    }

    private val sha = "3b1f2c4d5e6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e"

    @Test
    fun aManifestWithoutAUsableVersionCodeIsRefused() {
        // Reading a missing code as 0 would turn every check into "you are up to date" and
        // hide the real problem for good.
        val reason = refused("""{"versionName":"2.4.0","apk":"P.apk","apkSha256":"$sha"}""")
        assertTrue("says what is missing: $reason", reason.contains("which version"))

        refused("""{"versionCode":0,"apk":"P.apk","apkSha256":"$sha"}""")
        refused("""{"versionCode":-3,"apk":"P.apk","apkSha256":"$sha"}""")
        refused("""{"versionCode":"later","apk":"P.apk","apkSha256":"$sha"}""")
    }

    @Test
    fun aManifestThatNamesNoApkIsRefused() {
        val reason = refused("""{"versionCode":18,"apkSha256":"$sha"}""")
        assertTrue("says what is missing: $reason", reason.contains("which file"))

        refused("""{"versionCode":18,"apk":"","apkSha256":"$sha"}""")
        refused("""{"versionCode":18,"apk":"   ","apkSha256":"$sha"}""")
    }

    @Test
    fun aManifestThatIsNotJsonIsRefused() {
        refused("Not Found")
        refused("")
        refused(null)
        refused("[]")
    }

    @Test
    fun aMissingVersionNameFallsBackToTheCodeRatherThanBeingBlank() {
        val published = ok("""{"versionCode":18,"apk":"PocketPad.apk","apkSha256":"$sha"}""")
        assertEquals("18", published.versionName)
    }

    /**
     * The checksum fails closed.
     *
     * It used to be optional: a manifest without one was accepted and the download was then
     * verified by its length alone. Length is the one property of a substituted file that is
     * free to match, so that was not verification. A manifest that does not publish a usable
     * SHA-256 is now refused outright, and no update is offered from it.
     */
    @Test
    fun aManifestWithoutAUsableChecksumIsRefusedRatherThanVerifiedByLength() {
        val base = """"versionCode":18,"apk":"PocketPad.apk""""

        val reason = refused("{$base}")
        assertTrue("names the checksum: $reason", reason.contains("checksum"))
        assertTrue("says nothing was offered: $reason", reason.contains("Nothing was offered"))

        refused("""{$base,"apkSha256":""}""")
        refused("""{$base,"apkSha256":"   "}""")
        refused("""{$base,"apkSha256":"abc123"}""")
        refused("""{$base,"apkSha256":"${"a".repeat(63)}"}""")
        refused("""{$base,"apkSha256":"${"a".repeat(65)}"}""")
        refused("""{$base,"apkSha256":"${"z".repeat(64)}"}""")
        refused("""{$base,"apkSha256":123}""")
    }

    @Test
    fun anUpperCaseChecksumIsTheSameChecksum() {
        val base = """"versionCode":18,"apk":"PocketPad.apk""""
        val upper = "A".repeat(64)
        assertEquals("a".repeat(64), ok("""{$base,"apkSha256":"$upper"}""").apkSha256)
    }

    /**
     * The release published today, before any of this existed.
     *
     * It carries PocketPad.apk and nothing else, so the check must find no version manifest
     * and say so, rather than offering an install it cannot reason about.
     */
    @Test
    fun todaysReleaseHasNoVersionManifest() {
        val release = ReleaseMetadata.parseRelease(
            """
            {"tag_name":"latest","name":"PocketPad — newest build","assets":[
              {"name":"PocketPad.apk","size":9000000,
               "browser_download_url":
                 "https://github.com/Dathaze20/PocketPad/releases/download/latest/PocketPad.apk"}
            ]}
            """.trimIndent()
        )
        assertNotNull(release)
        assertNull(release!!.asset(UpdateVersion.VERSION_MANIFEST_ASSET))
    }
}
