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
import kotlin.test.assertTrue

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
    fun sinceThePackIsOneFileTheDualScreenEditionFollowsIt() = runTest {
        // Version 8 dropped the dual-screen file, from its releases and its main branch alike.
        val single = "https://github.com/RJNY/Obtainium-Emulation-Pack/releases/download/v8.0.1/obtainium-emulation-pack-v8.0.1.json"
        val http = TestHttp { request ->
            when (request.url.toString()) {
                latest -> respond("", HttpStatusCode.Found, headersOf("Location", "https://github.com/RJNY/Obtainium-Emulation-Pack/releases/tag/v8.0.1"))
                single -> text(fixture("pack-v8.json"), HttpStatusCode.OK)
                else -> text("Not Found", HttpStatusCode.NotFound)
            }
        }
        val fetched = assertIs<ApiResult.Success<FetchedPack>>(PackFetcher(http.client).fetch(StoreVariant.DUAL_SCREEN)).value
        assertEquals("v8.0.1", fetched.version)
        assertEquals(single, fetched.sourceUrl)
        // The real pack of version 8, with its dual-screen builds in it.
        val names = fetched.pack.apps.map { it.name }
        assertEquals(69, names.size)
        for (n in listOf("WatermelonDS", "Cemu (DS)", "Mjolnir", "Jarngreipr", "ES-DE Companion")) assertTrue(n in names, n)
        assertEquals(
            listOf(latest, "https://github.com/RJNY/Obtainium-Emulation-Pack/releases/download/v8.0.1/obtainium-emulation-pack-dual-screen-v8.0.1.json", single),
            http.requests.map { it.url.toString() },
        )
    }

    @Test
    fun withTheReleaseAwayTheMainBranchsOnePackIsFollowed() = runTest {
        val http = TestHttp { request ->
            when (request.url.toString()) {
                main -> text("404: Not Found", HttpStatusCode.NotFound)
                mainStandard -> text(fixture("pack-v8.json"), HttpStatusCode.OK)
                else -> text("busy", HttpStatusCode.ServiceUnavailable)
            }
        }
        val fetched = assertIs<ApiResult.Success<FetchedPack>>(PackFetcher(http.client).fetch(StoreVariant.DUAL_SCREEN)).value
        assertNull(fetched.version)
        assertEquals(mainStandard, fetched.sourceUrl)
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
