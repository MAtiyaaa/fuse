package io.github.matiyaaa.fuse.integrations.github

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol
import io.github.matiyaaa.fuse.integrations.json
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubReleasesTest {

    private val releaseJson = """
        {"url":"https://api.github.com/repos/MAtiyaaa/cartridge/releases/1","html_url":"https://github.com/MAtiyaaa/cartridge/releases/tag/v0.9.10",
         "id":1,"tag_name":"v0.9.10","name":"Cartridge 0.9.10","draft":false,"prerelease":false,
         "created_at":"2026-09-20T10:00:00Z","published_at":"2026-09-21T12:00:00Z","body":"Bridge for Fuse.",
         "assets":[
           {"id":10,"name":"Cartridge-android.apk","content_type":"application/vnd.android.package-archive","size":23456789,
            "digest":"sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            "browser_download_url":"https://github.com/MAtiyaaa/cartridge/releases/download/v0.9.10/Cartridge-android.apk"},
           {"id":11,"name":"Cartridge-x86_64.AppImage","size":"98765432","digest":null,
            "browser_download_url":"https://github.com/MAtiyaaa/cartridge/releases/download/v0.9.10/Cartridge-x86_64.AppImage"},
           {"id":12,"name":"SHA256SUMS.txt","size":200,"browser_download_url":"https://github.com/x/SHA256SUMS.txt"}]}
    """.trimIndent()

    @Test
    fun latestReleaseParses() = runTest {
        val http = TestHttp { json(releaseJson) }
        val releases = GitHubReleases(http.client, RateLimiter.unlimited())
        val release = (releases.latest(CartridgeProtocol.RELEASE_REPO) as ApiResult.Success).value!!
        assertEquals("https://api.github.com/repos/MAtiyaaa/cartridge/releases/latest", http.last.url.toString())
        assertEquals("application/vnd.github+json", http.last.headers["Accept"])
        assertEquals("v0.9.10", release.tag)
        assertEquals("Cartridge 0.9.10", release.name)
        assertEquals("Bridge for Fuse.", release.notes)
        assertEquals("2026-09-21T12:00:00Z", release.publishedAt)
        assertEquals(3, release.assets.size)
        assertEquals("sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", release.assets[0].digest)
        assertNull(release.assets[1].digest)
        assertEquals(98765432L, release.assets[1].sizeBytes)

        val apk = GitHubReleases.pickAsset(release, ReleasePlatform.ANDROID)!!
        assertEquals(CartridgeProtocol.ANDROID_ASSET_NAME, apk.name)
        assertTrue(apk.url.endsWith("/Cartridge-android.apk"))
        assertEquals(CartridgeProtocol.LINUX_ASSET_NAME, GitHubReleases.pickAsset(release, ReleasePlatform.LINUX_X86_64)!!.name)
    }

    @Test
    fun missingReleaseAndRateLimit() = runTest {
        var status = HttpStatusCode.NotFound
        var remaining = "59"
        val http = TestHttp { json("""{"message":"Not Found"}""", status, "x-ratelimit-remaining" to remaining, "x-ratelimit-reset" to "1790000000") }
        val releases = GitHubReleases(http.client, RateLimiter.unlimited())
        assertNull((releases.latest("MAtiyaaa", "nothing") as ApiResult.Success).value)
        status = HttpStatusCode.Forbidden
        remaining = "0"
        assertIs<ApiResult.RateLimited>(releases.latest("MAtiyaaa", "fuse"))
    }

    @Test
    fun semanticVersionCompare() {
        assertTrue(SemVer.isNewer("v0.9.10", "v0.9.9"))
        assertTrue(SemVer.isNewer("0.10.0", "v0.9.10"))
        assertFalse(SemVer.isNewer("v0.9.9", "0.9.10"))
        assertEquals(0, SemVer.compare("1.0", "v1.0.0"))
        assertTrue(SemVer.compare("1.0.0-beta.2", "1.0.0") < 0)
        assertTrue(SemVer.compare("1.0.0-beta.10", "1.0.0-beta.2") > 0)
        assertTrue(SemVer.compare("1.0.0-alpha", "1.0.0-beta") < 0)
        assertEquals(0, SemVer.compare("1.2.3+build.5", "1.2.3"))
        assertTrue(SemVer.atLeast("0.9.10", CartridgeProtocol.MIN_BRIDGE_VERSION))
        assertFalse(SemVer.atLeast("0.9.9", CartridgeProtocol.MIN_BRIDGE_VERSION))
        assertTrue(SemVer.compare("garbage", "0.0.1") < 0)
    }

    @Test
    fun pickAssetPrefersUniversalApkAndNeedsAppImage() {
        val release = io.github.matiyaaa.fuse.model.ReleaseInfo(
            tag = "v1", name = "v1", notes = "", publishedAt = null, htmlUrl = "",
            assets = listOf(
                io.github.matiyaaa.fuse.model.ReleaseAsset("Fuse-arm64-v8a.apk", "u1", 1),
                io.github.matiyaaa.fuse.model.ReleaseAsset("Fuse-universal.apk", "u2", 1),
                io.github.matiyaaa.fuse.model.ReleaseAsset("Fuse-aarch64.AppImage", "u3", 1),
            ),
        )
        assertEquals("Fuse-universal.apk", GitHubReleases.pickAsset(release, ReleasePlatform.ANDROID)?.name)
        assertNull(GitHubReleases.pickAsset(release, ReleasePlatform.LINUX_X86_64))
    }
}
