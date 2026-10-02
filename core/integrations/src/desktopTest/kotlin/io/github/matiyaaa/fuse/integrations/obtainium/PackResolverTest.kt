package io.github.matiyaaa.fuse.integrations.obtainium

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.json
import io.github.matiyaaa.fuse.integrations.text
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pack's HTML apps resolved against their real download pages (saved on 2 October 2026), and
 * its GitHub rules against GitHub's release format. Only the transport is fake.
 */
class PackResolverTest {
    private val pack = PackDocument.parse(fixture("pack-standard.json")).getOrThrow()
    private val abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private fun app(name: String) = pack.apps.first { it.name == name }

    private val pages = mapOf(
        "https://dolphin-emu.org/download/?ref=btn" to "dolphin-obtainium.json",
        "https://duckstation-mirror.rmacias.workers.dev" to "duckstation.html",
        "https://stable.eden-emu.dev/latest/release.json" to "eden-release.json",
        "https://purei.org/downloads/play/stable/" to "play-stable.html",
        "https://purei.org/downloads/play/stable/0.72/" to "play-0.72.html",
        "https://www.ppsspp.org/download/" to "ppsspp-download.html",
        "https://buildbot.libretro.com/stable" to "retroarch-stable.html",
        "https://buildbot.libretro.com/stable/1.22.2/" to "retroarch-1.22.2.html",
        "https://buildbot.libretro.com/stable/1.22.2/android/" to "retroarch-1.22.2-android.html",
        "https://www.scummvm.org/downloads/" to "scummvm-downloads.html",
    )
    private val web = TestHttp { request ->
        val file = pages[request.url.toString()]
        if (file != null) text(fixture(file), HttpStatusCode.OK) else text("Not here", HttpStatusCode.NotFound)
    }
    private val resolver = PackResolver(web.client)

    private suspend fun release(name: String): UpstreamRelease = when (val r = resolver.resolve(app(name), abis)) {
        is ApiResult.Success -> r.value
        is ApiResult.Failure -> error("$name: ${r.message}")
    }

    private fun apkUrl(r: UpstreamRelease) = assertIs<ApkChoice.Install>(r.choice).apk.url

    @Test
    fun dolphin() = runTest {
        val r = release("Dolphin Emulator")
        assertEquals("2609", r.version)
        assertEquals("https://dl.dolphin-emu.org/releases/2609/dolphin-2609.apk", apkUrl(r))
        // The pack's own request header is what Dolphin's site answers to.
        assertEquals(listOf("Obtainium/1.0"), web.requests.first().headers.getAll(HttpHeaders.UserAgent))
    }

    @Test
    fun duckStationReadsItsVersionFromThePage() = runTest {
        val r = release("DuckStation")
        assertEquals("0.1-8969-g611bb8fb4", r.version)
        assertEquals("https://duckstation-mirror.rmacias.workers.dev/duckstation-android.apk", apkUrl(r))
    }

    @Test
    fun edenFindsLinksInJson() = runTest {
        val r = release("Eden")
        assertEquals("0.2.1", r.version)
        val url = apkUrl(r)
        assertTrue(url.startsWith("https://") && url.endsWith("-standard.apk"), url)
    }

    @Test
    fun playFollowsTheNewestVersionFolder() = runTest {
        val r = release("Play!")
        assertEquals("0.72", r.version)
        assertEquals("https://purei.org/downloads/play/stable/0.72/Play-release.apk", apkUrl(r))
    }

    @Test
    fun ppssppTakesTheNewestRelease() = runTest {
        val r = release("PPSSPP")
        assertEquals("1.20.4", r.version)
        assertEquals("https://www.ppsspp.org/files/1_20_4/ppsspp.apk", apkUrl(r))
    }

    @Test
    fun retroArchWalksTwoFolders() = runTest {
        val r = release("RetroArch (AArch64)")
        assertEquals("1.22.2", r.version)
        assertEquals("https://buildbot.libretro.com/stable/1.22.2/android/RetroArch_aarch64.apk", apkUrl(r))
    }

    @Test
    fun scummVm() = runTest {
        val r = release("ScummVM")
        assertEquals("2026.3.0", r.version)
        assertEquals("https://downloads.scummvm.org/frs/scummvm/2026.3.0/scummvm-2026.3.0-android-arm64-v8a.apk", apkUrl(r))
    }

    @Test
    fun aDownloadThatIsNotHttpsIsNeverOffered() = runTest {
        val http = TestHttp { text("""<a href="http://example.org/files/app-1.0.apk">app</a>""", HttpStatusCode.OK) }
        val app = PackApp("com.example.app", "https://example.org/download", "App", "x", emptyList(), PackSourceKind.HTML, false, null, PackRules())
        val r = assertIs<ApiResult.Success<UpstreamRelease>>(PackResolver(http.client).resolve(app, abis)).value
        assertIs<ApkChoice.Manual>(r.choice)
    }

    @Test
    fun aPageThatIsNotHttpsIsNotRead() = runTest {
        val http = TestHttp { text("", HttpStatusCode.OK) }
        val app = PackApp("com.example.app", "http://example.org/download", "App", "x", emptyList(), PackSourceKind.HTML, false, null, PackRules())
        assertIs<ApiResult.NotConfigured>(PackResolver(http.client).resolve(app, abis))
        assertTrue(http.requests.isEmpty())
    }

    // GitHub

    private fun ghRelease(tag: String, date: String, vararg assets: String, prerelease: Boolean = false, name: String? = null) = """
        {"tag_name":"$tag","name":${name?.let { "\"$it\"" } ?: "null"},"published_at":"$date","html_url":"https://github.com/o/r/releases/tag/$tag",
         "draft":false,"prerelease":$prerelease,"body":"Notes for $tag",
         "assets":[${assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://github.com/o/r/releases/download/$tag/$it","size":1000,"digest":"sha256:ab"}""" }}]}
    """.trimIndent()

    private fun gitHubApp(rules: PackRules) =
        PackApp("com.example.emu", "https://github.com/o/r", "Emu", "o", listOf("Emulator"), PackSourceKind.GITHUB, false, 0, rules)

    @Test
    fun gitHubSkipsPrereleasesAndPicksTheDevicesApk() = runTest {
        val body = "[" + listOf(
            ghRelease("v2.0-rc1", "2026-09-20T00:00:00Z", "emu-arm64-v8a.apk", prerelease = true),
            ghRelease("v1.9", "2026-09-01T00:00:00Z", "emu-arm64-v8a.apk", "emu-x86_64.apk"),
        ).joinToString(",") + "]"
        val http = TestHttp { json(body) }
        val rules = PackRules(fallbackToOlderReleases = true, sortMethod = "date", filterByArch = true, versionPattern = "v?(.+)", versionGroups = "\$1")
        val r = assertIs<ApiResult.Success<UpstreamRelease>>(PackResolver(http.client).resolve(gitHubApp(rules), abis)).value
        assertEquals("1.9", r.version)
        val apk = assertIs<ApkChoice.Install>(r.choice).apk
        assertEquals("emu-arm64-v8a.apk", apk.name)
        assertEquals("sha256:ab", apk.digest)
        assertEquals("https://api.github.com/repos/o/r/releases?per_page=50", http.last.url.toString())
    }

    @Test
    fun withoutFallbackOnlyTheNewestEligibleReleaseCounts() = runTest {
        val body = "[" + listOf(
            ghRelease("v2.0", "2026-09-20T00:00:00Z", "emu.zip"),
            ghRelease("v1.9", "2026-09-01T00:00:00Z", "emu.apk"),
        ).joinToString(",") + "]"
        val http = TestHttp { json(body) }
        val result = PackResolver(http.client).resolve(gitHubApp(PackRules(sortMethod = "date")), abis)
        assertIs<ApiResult.HttpError>(result)
        val withFallback = PackResolver(http.client).resolve(gitHubApp(PackRules(sortMethod = "date", fallbackToOlderReleases = true)), abis)
        assertEquals("v1.9", assertIs<ApiResult.Success<UpstreamRelease>>(withFallback).value.version)
    }

    @Test
    fun releaseTitleAsVersion() = runTest {
        val http = TestHttp { json("[" + ghRelease("build-88", "2026-09-01T00:00:00Z", "Cemu.apk", name = "Cemu 2.6") + "]") }
        val r = PackResolver(http.client).resolve(gitHubApp(PackRules(releaseTitleAsVersion = true)), abis)
        assertEquals("Cemu 2.6", assertIs<ApiResult.Success<UpstreamRelease>>(r).value.version)
    }

    @Test
    fun trackOnlyAppsAreFollowedWithoutAFile() = runTest {
        val http = TestHttp { json("[" + ghRelease("v3", "2026-09-01T00:00:00Z") + "]") }
        val r = PackResolver(http.client).resolve(gitHubApp(PackRules(trackOnly = true)), abis)
        val value = assertIs<ApiResult.Success<UpstreamRelease>>(r).value
        assertEquals("v3", value.version)
        assertNull(value.choice)
    }

    @Test
    fun anExhaustedGitHubLimitIsSaidSo() = runTest {
        val http = TestHttp {
            respond("""{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden, headersOf("x-ratelimit-remaining" to listOf("0"), "x-ratelimit-reset" to listOf("1790000000")))
        }
        val r = PackResolver(http.client).resolve(gitHubApp(PackRules()), abis)
        assertEquals(1790000000L, assertIs<ApiResult.RateLimited>(r).retryAfterSeconds)
    }

    @Test
    fun aTokenIsSentWhenTheUserAddedOne() = runTest {
        val http = TestHttp { json("[" + ghRelease("v1", "2026-09-01T00:00:00Z", "a.apk") + "]") }
        PackResolver(http.client, githubToken = { "ghp_secret" }).resolve(gitHubApp(PackRules()), abis)
        assertEquals("Bearer ghp_secret", http.last.headers[HttpHeaders.Authorization])
    }

    @Test
    fun verifyLatestTagPutsGitHubsLatestFirst() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/latest")) json(ghRelease("v1.5", "2026-08-01T00:00:00Z", "emu.apk"))
            else json("[" + ghRelease("v9-test", "2026-09-30T00:00:00Z", "emu.apk") + "," + ghRelease("v1.5", "2026-08-01T00:00:00Z", "emu.apk") + "]")
        }
        val r = PackResolver(http.client).resolve(gitHubApp(PackRules(verifyLatestTag = true, sortMethod = "date")), abis)
        assertEquals("v1.5", assertIs<ApiResult.Success<UpstreamRelease>>(r).value.version)
    }

    @Test
    fun noLatestReleaseLeavesTheListToDecide() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/latest")) text("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
            else json("[" + ghRelease("v0.9-beta", "2026-09-30T00:00:00Z", "emu.apk", prerelease = true) + "]")
        }
        val r = PackResolver(http.client).resolve(gitHubApp(PackRules(verifyLatestTag = true, includePrereleases = true)), abis)
        assertEquals("v0.9-beta", assertIs<ApiResult.Success<UpstreamRelease>>(r).value.version)
    }
}
