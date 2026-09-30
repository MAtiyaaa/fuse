package io.github.matiyaaa.fuse.integrations.systemart

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.text
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemArtPackClientTest {
    private val base = "https://pack.test/systems"

    /** A pack that has every file in [existing] (paths relative to the systems folder). */
    private fun pack(existing: Set<String>) = TestHttp { request ->
        val path = request.url.encodedPath.removePrefix("/systems/")
        when {
            path !in existing -> text("404: Not Found", HttpStatusCode.NotFound)
            path.endsWith(".xml") -> text(SNES_XML, HttpStatusCode.OK)
            else -> text("", HttpStatusCode.OK)
        }
    }

    private fun client(http: TestHttp) = SystemArtPackClient(http.client, RateLimiter.unlimited(), base)

    @Test
    fun fetchesLogoArtworkAndMetadata() = runTest {
        val http = pack(setOf("logos/snes.svg", "artwork/snes.png", "artwork-noir/snes.png", "_metadata-global/snes.xml"))
        val art = (client(http).fetch("snes", SystemArtStyle.NOIR) as ApiResult.Success).value
        assertEquals("$base/logos/snes.svg", art.logoUrl)
        assertEquals("$base/artwork-noir/snes.png", art.artworkUrl)
        assertEquals(SystemArtStyle.NOIR, art.artworkStyle)
        assertEquals("Super Nintendo", art.meta?.name)
        assertEquals(0xFFDF5142, art.meta?.color)
        assertEquals(listOf(HttpMethod.Head, HttpMethod.Head, HttpMethod.Get), http.requests.map { it.method })
    }

    @Test
    fun aMissingStyleFallsBackToClassic() = runTest {
        val http = pack(setOf("logos/ps4.svg", "artwork/ps4.png"))
        val art = (client(http).fetch("ps4", SystemArtStyle.NOIR) as ApiResult.Success).value
        assertEquals("$base/artwork/ps4.png", art.artworkUrl)
        assertEquals(SystemArtStyle.CLASSIC, art.artworkStyle)
        assertNull(art.meta, "a missing metadata file is not a failure")
        assertEquals(
            listOf("/systems/logos/ps4.svg", "/systems/artwork-noir/ps4.png", "/systems/artwork/ps4.png", "/systems/_metadata-global/ps4.xml"),
            http.requests.map { it.url.encodedPath },
        )
    }

    @Test
    fun noArtworkAtAllStillReturnsTheLogo() = runTest {
        val http = pack(setOf("logos/kodi.svg"))
        val art = (client(http).fetch("kodi") as ApiResult.Success).value
        assertNull(art.artworkUrl)
        assertNull(art.artworkStyle)
        assertEquals(2, http.requests.count { it.method == HttpMethod.Head }, "Classic is only probed once")
    }

    @Test
    fun aMissingLogoFailsTheFetch() = runTest {
        val http = pack(emptySet())
        val result = client(http).fetch("ps5")
        assertIs<ApiResult.HttpError>(result)
        assertEquals(404, result.code)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun serverAndNetworkProblemsAreFailures() = runTest {
        val broken = TestHttp { respondError(HttpStatusCode.ServiceUnavailable) }
        val failure = client(broken).fetch("snes")
        assertIs<ApiResult.HttpError>(failure)
        assertEquals(503, failure.code)

        val offline = TestHttp { throw IllegalStateException("no route to host") }
        assertIs<ApiResult.NetworkError>(client(offline).fetch("snes"))
    }

    @Test
    fun availableStylesProbesEveryFolder() = runTest {
        val http = pack(setOf("artwork/msx.png", "artwork-outline/msx.png", "artwork-noir/msx.png", "artwork-screenshots/msx.png"))
        val styles = (client(http).availableStyles("msx") as ApiResult.Success).value
        assertEquals(listOf(SystemArtStyle.CLASSIC, SystemArtStyle.OUTLINE, SystemArtStyle.NOIR, SystemArtStyle.SCREENSHOTS), styles)
        assertTrue(http.requests.all { it.method == HttpMethod.Head })
    }

    @Test
    fun attributionNamesEveryCredit() {
        val text = SystemArtPack.ATTRIBUTION
        for (credit in listOf("Anthony Caccese", "Dan Patrick", "tenlevels", "f8less", "Joppa Fallston", "theUnBurn", "CC BY-NC-SA 2.0")) {
            assertTrue(credit in text, "attribution is missing $credit")
        }
    }
}
