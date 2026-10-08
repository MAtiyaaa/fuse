package io.github.matiyaaa.fuse.integrations.gametdb

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.text
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScrapeQuery
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameTdbSourceTest {
    private fun query(platform: String = "wii", serial: String? = "RMGE01", file: String? = null) =
        ScrapeQuery("Super Mario Galaxy", PlatformId(platform), "Wii", file, serial = serial)

    @Test fun onlyExplicitPlatformCompatibleIdsAreUsed() {
        assertEquals("RMGE01", GameTdbSource.discId(query()))
        assertEquals("GMSE01", GameTdbSource.discId(query("ngc", "GMSE01")))
        assertNull(GameTdbSource.discId(query("switch", "0100000000000000")))
        assertNull(GameTdbSource.discId(query("ngc", "RMGE01")))
        assertNull(GameTdbSource.discId(query(serial = "../../bad")))
        assertNull(GameTdbSource.discId(query(serial = null, file = "random title RMGE01.iso")))
        assertEquals("RMGE01", GameTdbSource.discId(query(serial = null, file = "Game [RMGE01].iso")))
        assertNull(GameTdbSource.discId(query(serial = null, file = "Game [RMGE01] [RZDE01].iso")))
    }

    @Test fun directIdProbeReturnsOnlyBoxArtAndPreservesProvenance() = runTest {
        val http = TestHttp { text("", HttpStatusCode.OK) }
        val source = GameTdbSource(http.client, RateLimiter.unlimited())
        val found = (source.search(query()) as ApiResult.Success).value.single()
        assertEquals("RMGE01", found.providerGameId)
        assertEquals("https://art.gametdb.com/wii/cover/US/RMGE01.png", found.previewUrl)
        assertEquals(HttpMethod.Head, http.requests.single().method)
        source.search(query().copy(title = "An alias for the same disc"))
        assertEquals(1, http.requests.size)
        assertTrue((source.artwork(found, query(), setOf(MediaKind.LOGO)) as ApiResult.Success).value.isEmpty())
    }

    @Test fun unsupportedPlatformsNeverContactProviderAndMissingCoverIsNegative() = runTest {
        val http = TestHttp { text("", HttpStatusCode.NotFound) }
        val source = GameTdbSource(http.client, RateLimiter.unlimited())
        assertTrue((source.search(query("ps2", "SLUS-12345")) as ApiResult.Success).value.isEmpty())
        assertTrue(http.requests.isEmpty())
        assertTrue((source.search(query()) as ApiResult.Success).value.isEmpty())
        source.search(query().copy(title = "Another name"))
        assertEquals(1, http.requests.size)
    }

    @Test fun aThrottledProviderRemainsAFailureRatherThanMissingArtwork() = runTest {
        val http = TestHttp { text("", HttpStatusCode.TooManyRequests) }
        val source = GameTdbSource(http.client, RateLimiter.unlimited())
        assertTrue(source.search(query()) is ApiResult.RateLimited)
    }
}
