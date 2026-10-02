package io.github.matiyaaa.fuse.integrations.obtainium

import io.github.matiyaaa.fuse.integrations.TestHttp
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Store app's own icon, from its repository's metadata or its website; never a guess. */
class AppIconFinderTest {
    private val png = headersOf(HttpHeaders.ContentType, "image/png")

    private val web = TestHttp { request ->
        val url = request.url.toString()
        when {
            // A project with F-Droid metadata, and one with only a Play Store icon.
            url == "https://raw.githubusercontent.com/dolphin-emu/dolphin/HEAD/fastlane/metadata/android/en-US/images/icon.png" ->
                respond(ByteArray(0), HttpStatusCode.OK, png)
            url == "https://raw.githubusercontent.com/someone/emu/HEAD/app/src/main/ic_launcher-playstore.png" ->
                respond(ByteArray(0), HttpStatusCode.OK, png)
            // A page that names a touch icon (and an older favicon).
            url == "https://www.ppsspp.org/download/" -> respond(
                """<html><head><link rel="icon" href="/favicon.ico"><link rel="apple-touch-icon" sizes="180x180" href="/static/img/touch.png"></head></html>""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"),
            )
            url == "https://www.ppsspp.org/static/img/touch.png" && request.method == HttpMethod.Head -> respond(ByteArray(0), HttpStatusCode.OK, png)
            // A path that answers, but not with a picture.
            url.startsWith("https://raw.githubusercontent.com/nobody/") -> respond("<html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            else -> respond("", HttpStatusCode.NotFound)
        }
    }
    private val finder = AppIconFinder(web.client)

    @Test
    fun githubProjectsGiveTheirMetadataIcon() = runTest {
        assertEquals(
            "https://raw.githubusercontent.com/dolphin-emu/dolphin/HEAD/fastlane/metadata/android/en-US/images/icon.png",
            finder.find("https://github.com/dolphin-emu/dolphin"),
        )
        assertEquals("https://raw.githubusercontent.com/someone/emu/HEAD/app/src/main/ic_launcher-playstore.png", finder.find("https://github.com/someone/emu/"))
    }

    @Test
    fun websitesGiveTheirTouchIcon() = runTest {
        assertEquals("https://www.ppsspp.org/static/img/touch.png", finder.find("https://www.ppsspp.org/download/"))
        // Pictures are only checked, never downloaded.
        assertTrue(web.requests.filter { it.url.encodedPath.endsWith(".png") }.all { it.method == HttpMethod.Head })
    }

    @Test
    fun nothingFoundMeansNoIcon() = runTest {
        assertNull(finder.find("https://github.com/nobody/thing"))
        assertNull(finder.find("http://insecure.example/download"))
        assertNull(finder.find("https://example.com/missing"))
    }
}
