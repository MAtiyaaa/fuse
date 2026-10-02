package io.github.matiyaaa.fuse.integrations.obtainium

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.text
import io.github.matiyaaa.fuse.model.StoreVariant
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PackFetcherTest {
    private val latest = "https://github.com/RJNY/Obtainium-Emulation-Pack/releases/latest"
    private val tagged = "https://github.com/RJNY/Obtainium-Emulation-Pack/releases/download/v7.18.0/obtainium-emulation-pack-dual-screen-v7.18.0.json"
    private val main = "https://raw.githubusercontent.com/RJNY/Obtainium-Emulation-Pack/main/obtainium-emulation-pack-dual-screen-latest.json"
    private val mainStandard = "https://raw.githubusercontent.com/RJNY/Obtainium-Emulation-Pack/main/obtainium-emulation-pack-latest.json"

    @Test
    fun takesTheNewestReleasesFile() = runTest {
        val http = TestHttp { request ->
            when (request.url.toString()) {
                latest -> respond("", HttpStatusCode.Found, headersOf("Location", "https://github.com/RJNY/Obtainium-Emulation-Pack/releases/tag/v7.18.0"))
                tagged -> text(fixture("pack-dual-screen.json"), HttpStatusCode.OK)
                else -> text("", HttpStatusCode.NotFound)
            }
        }
        val fetched = assertIs<ApiResult.Success<FetchedPack>>(PackFetcher(http.client).fetch(StoreVariant.DUAL_SCREEN)).value
        assertEquals("v7.18.0", fetched.version)
        assertEquals(68, fetched.pack.apps.size)
        assertEquals(tagged, fetched.sourceUrl)
    }

    @Test
    fun fallsBackToTheMainBranch() = runTest {
        val http = TestHttp { request ->
            when (request.url.toString()) {
                main -> text(fixture("pack-dual-screen.json"), HttpStatusCode.OK)
                else -> text("busy", HttpStatusCode.ServiceUnavailable)
            }
        }
        val fetched = assertIs<ApiResult.Success<FetchedPack>>(PackFetcher(http.client).fetch(StoreVariant.DUAL_SCREEN)).value
        assertNull(fetched.version)
        assertEquals(main, fetched.sourceUrl)
    }

    @Test
    fun anOddTagIsNotTrustedIntoAnAddress() = runTest {
        val http = TestHttp { request ->
            when (request.url.toString()) {
                latest -> respond("", HttpStatusCode.Found, headersOf("Location", "https://github.com/RJNY/Obtainium-Emulation-Pack/releases/tag/..%2Fevil"))
                mainStandard -> text(fixture("pack-standard.json"), HttpStatusCode.OK)
                else -> text("", HttpStatusCode.NotFound)
            }
        }
        val fetched = assertIs<ApiResult.Success<FetchedPack>>(PackFetcher(http.client).fetch(StoreVariant.STANDARD))
        assertNull(fetched.value.version)
        assertEquals(listOf(latest, mainStandard), http.requests.map { it.url.toString() })
    }

    @Test
    fun aBrokenDownloadIsAFailureNotACatalogue() = runTest {
        val http = TestHttp { text("<html>Something went wrong</html>", HttpStatusCode.OK) }
        assertIs<ApiResult.Failure>(PackFetcher(http.client).fetch(StoreVariant.STANDARD))
    }
}
