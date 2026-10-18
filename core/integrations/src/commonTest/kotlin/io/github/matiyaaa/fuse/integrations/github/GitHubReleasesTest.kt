package io.github.matiyaaa.fuse.integrations.github

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol
import io.github.matiyaaa.fuse.integrations.json
import io.github.matiyaaa.fuse.integrations.text
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
    fun eachChannelTakesTheNewestOfWhatItFollows() = runTest {
        fun release(tag: String, name: String, pre: Boolean, draft: Boolean = false) =
            """{"tag_name":"$tag","name":"$name","draft":$draft,"prerelease":$pre,"html_url":"https://github.com/MAtiyaaa/fuse/releases/tag/$tag",
               "assets":[{"name":"Fuse-x-x86_64.AppImage","size":1,"browser_download_url":"https://github.com/MAtiyaaa/fuse/releases/download/$tag/Fuse-x-x86_64.AppImage"}]}"""
        // Newest first, as GitHub lists them: the rolling nightly, a draft, a beta, the published release.
        val list = "[" + listOf(
            release("nightly", "Fuse 0.4.5-nightly.20261019", pre = true),
            release("v0.4.6", "Fuse 0.4.6", pre = false, draft = true),
            release("v0.4.5-beta.1", "Fuse 0.4.5 beta 1", pre = true),
            release("v0.4.4", "Fuse 0.4.4 - The Overdrive Update", pre = false),
        ).joinToString(",") + "]"
        val http = TestHttp { json(list) }
        val releases = GitHubReleases(http.client, RateLimiter.unlimited())
        val beta = (releases.forChannel(GitHubReleases.FUSE_REPO, ReleaseChannel.BETA) as ApiResult.Success).value!!
        assertEquals("https://api.github.com/repos/MAtiyaaa/fuse/releases?per_page=30", http.last.url.toString())
        assertEquals("v0.4.5-beta.1", beta.tag)
        assertTrue(beta.prerelease)
        val nightly = (releases.forChannel(GitHubReleases.FUSE_REPO, ReleaseChannel.NIGHTLY) as ApiResult.Success).value!!
        assertEquals("0.4.5-nightly.20261019", nightly.tag, "the nightly's version, from its name")
        assertTrue(SemVer.isNewer(nightly.tag, "0.4.4"))

        // A draft is never offered, and the published release wins once it is the newest.
        val models = listOf(
            io.github.matiyaaa.fuse.model.ReleaseInfo("v0.4.5", "Fuse 0.4.5", "", null, emptyList(), ""),
            io.github.matiyaaa.fuse.model.ReleaseInfo("v0.4.5-beta.1", "", "", null, emptyList(), "", prerelease = true),
            io.github.matiyaaa.fuse.model.ReleaseInfo("nightly", "Fuse 0.4.5-nightly.20261019", "", null, emptyList(), "", prerelease = true),
        )
        assertEquals("v0.4.5", GitHubReleases.pick(ReleaseChannel.BETA, models)?.tag)
        assertEquals("v0.4.5", GitHubReleases.pick(ReleaseChannel.NIGHTLY, models)?.tag)
        assertEquals("v0.4.5", GitHubReleases.pick(ReleaseChannel.STABLE, models)?.tag)
        // A nightly whose name says no version is passed over, never guessed.
        assertNull(GitHubReleases.pick(ReleaseChannel.NIGHTLY, listOf(models[2].copy(name = "Nightly build"))))
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
    fun theStoresTokenIsSentWhenThereIsOne() = runTest {
        val http = TestHttp { json(releaseJson) }
        GitHubReleases(http.client, RateLimiter.unlimited(), token = { "ghp_example" }).latest(CartridgeProtocol.RELEASE_REPO)
        assertEquals("Bearer ghp_example", http.last.headers["Authorization"])
        GitHubReleases(http.client, RateLimiter.unlimited()).latest(CartridgeProtocol.RELEASE_REPO)
        assertNull(http.last.headers["Authorization"])
    }

    @Test
    fun aRateLimitedApiFallsBackToTheWebsite() = runTest {
        val sha = "a".repeat(64)
        val http = TestHttp { request ->
            val url = request.url.toString()
            when {
                url.startsWith("https://api.github.com/") ->
                    json("""{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden, "x-ratelimit-remaining" to "0")
                url.endsWith("/releases/latest") ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://github.com/MAtiyaaa/fuse/releases/tag/v9.9.9"))
                url.endsWith("/releases/tag/v9.9.9") -> text("<html></html>", HttpStatusCode.OK)
                url.endsWith("/releases/download/v9.9.9/SHA256SUMS.txt") ->
                    text("$sha  Fuse-9.9.9-x86_64.AppImage\n${"b".repeat(64)}  Fuse-9.9.9-android.apk\n", HttpStatusCode.OK)
                else -> text("", HttpStatusCode.NotFound)
            }
        }
        val release = (GitHubReleases(http.client, RateLimiter.unlimited()).latest(GitHubReleases.FUSE_REPO) as ApiResult.Success).value!!
        assertEquals("v9.9.9", release.tag)
        val appImage = GitHubReleases.pickAsset(release, ReleasePlatform.LINUX_X86_64)!!
        assertEquals("https://github.com/MAtiyaaa/fuse/releases/download/v9.9.9/Fuse-9.9.9-x86_64.AppImage", appImage.url)
        assertEquals("sha256:$sha", appImage.digest)
        assertEquals("Fuse-9.9.9-android.apk", GitHubReleases.pickAsset(release, ReleasePlatform.ANDROID)!!.name)
        // Said to come from the website, where a token would let the API answer again.
        assertTrue(release.fromWebsite)
    }

    @Test
    fun aFilesSizeIsAskedOfTheServerBeforeItsDownload() = runTest {
        val http = TestHttp { request ->
            if (request.method == io.ktor.http.HttpMethod.Head) respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "180369912"))
            else text("", HttpStatusCode.NotFound)
        }
        val releases = GitHubReleases(http.client, RateLimiter.unlimited())
        assertEquals(180369912L, releases.sizeOf("https://github.com/MAtiyaaa/fuse/releases/download/v9.9.9/Fuse-9.9.9-x86_64.AppImage"))
        assertEquals(io.ktor.http.HttpMethod.Head, http.last.method)
    }

    @Test
    fun theApisReleaseIsNotFromTheWebsite() = runTest {
        val release = (GitHubReleases(TestHttp { json(releaseJson) }.client, RateLimiter.unlimited()).latest(CartridgeProtocol.RELEASE_REPO) as ApiResult.Success).value!!
        assertFalse(release.fromWebsite)
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

    @Test
    fun pickAssetForWindowsAndBothMacs() {
        fun release(vararg names: String) = io.github.matiyaaa.fuse.model.ReleaseInfo(
            tag = "v0.1.0", name = "v0.1.0", notes = "", publishedAt = null, htmlUrl = "",
            assets = names.map { io.github.matiyaaa.fuse.model.ReleaseAsset(it, "u/$it", 1) },
        )
        val all = release(
            "Fuse-0.1.0-android.apk", "Fuse-0.1.0-x86_64.AppImage", "Fuse-0.1.0-windows-x64.zip", "Fuse-0.1.0-windows-x64.msi",
            "Fuse-0.1.0-macos-arm64.dmg", "Fuse-0.1.0-macos-x64.dmg",
        )
        assertEquals("Fuse-0.1.0-windows-x64.msi", GitHubReleases.pickAsset(all, ReleasePlatform.WINDOWS_X64)?.name)
        assertEquals("Fuse-0.1.0-macos-arm64.dmg", GitHubReleases.pickAsset(all, ReleasePlatform.MACOS_ARM64)?.name)
        assertEquals("Fuse-0.1.0-macos-x64.dmg", GitHubReleases.pickAsset(all, ReleasePlatform.MACOS_X64)?.name)
        // The portable zip stands in when the installer is missing; an APK-only release has nothing for a PC or Mac.
        assertEquals("Fuse-0.1.0-windows-x64.zip", GitHubReleases.pickAsset(release("Fuse-0.1.0-windows-x64.zip"), ReleasePlatform.WINDOWS_X64)?.name)
        val apkOnly = release("Fuse-0.1.0-android.apk")
        for (p in listOf(ReleasePlatform.WINDOWS_X64, ReleasePlatform.MACOS_ARM64, ReleasePlatform.MACOS_X64, ReleasePlatform.LINUX_X86_64)) {
            assertNull(GitHubReleases.pickAsset(apkOnly, p))
        }
    }
}
