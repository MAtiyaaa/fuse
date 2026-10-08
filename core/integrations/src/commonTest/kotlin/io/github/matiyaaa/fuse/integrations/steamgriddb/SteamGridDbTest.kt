package io.github.matiyaaa.fuse.integrations.steamgriddb

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.KeyCheck
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.json
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async

class SteamGridDbTest {

    @Test
    fun repeatedAndConcurrentRequestsUseOneProviderCall() = runTest {
        val http = TestHttp { kotlinx.coroutines.delay(20); json("""{"success":true,"data":[{"id":42,"name":"Doom"}]}""") }
        val client = SteamGridDbClient(http.client, "key", RateLimiter.unlimited(), now = { testScheduler.currentTime })
        kotlinx.coroutines.coroutineScope {
            val calls = List(5) { async { client.searchAutocomplete("Doom") } }
            calls.forEach { assertIs<ApiResult.Success<List<SgdbGame>>>(it.await()) }
        }
        client.searchAutocomplete("Doom")
        assertEquals(1, http.requests.size)
    }

    @Test
    fun quotaCooldownAppliesAcrossDifferentDirectRequests() = runTest {
        var now = 0L
        val http = TestHttp { json("{}", HttpStatusCode.TooManyRequests, HttpHeaders.RetryAfter to "120") }
        val client = SteamGridDbClient(http.client, "key", RateLimiter.unlimited(), now = { now })
        assertIs<ApiResult.RateLimited>(client.searchAutocomplete("Doom"))
        assertIs<ApiResult.RateLimited>(client.gameById(42))
        assertEquals(1, http.requests.size)
        now = 120_001
        assertIs<ApiResult.RateLimited>(client.gameById(42))
        assertEquals(2, http.requests.size)
    }

    private val gridsJson = """
        {"success":true,"page":0,"total":3,"limit":50,"data":[
          {"id":80451,"score":12,"style":"alternate","width":600,"height":900,"nsfw":false,"humor":false,
           "notes":null,"mime":"image/png","language":"en","url":"https://cdn2.steamgriddb.com/grid/abc.png",
           "thumb":"https://cdn2.steamgriddb.com/thumb/abc.jpg","lock":false,"epilepsy":false,"upvotes":12,"downvotes":0,
           "author":{"name":"artist","steam64":"76561197960287930","avatar":"https://avatars.example/a.jpg"}},
          {"id":80452,"score":"3","style":"blurred","width":"920","height":430,"mime":"image/jpeg",
           "url":"https://cdn2.steamgriddb.com/grid/def.jpg","thumb":"https://cdn2.steamgriddb.com/thumb/def.jpg",
           "author":{"name":"other"}},
          {"id":80453,"score":0,"style":"material","width":342,"height":482,"mime":"image/png",
           "url":"https://cdn2.steamgriddb.com/grid/ghi.png","thumb":null}
        ]}
    """.trimIndent()

    @Test
    fun filtersBecomeQueryParameters() {
        val filters = SgdbFilters(
            styles = setOf(SgdbStyle.ALTERNATE, SgdbStyle.OFFICIAL, SgdbStyle.NO_LOGO),
            dimensions = setOf(SgdbDimension.GRID_600x900, SgdbDimension.GRID_342x482),
            mimes = setOf(SgdbMime.PNG, SgdbMime.WEBP),
            types = setOf(SgdbAnimation.STATIC),
            limit = 500,
            page = 2,
        )
        val grid = filters.toParameters(SgdbAssetType.GRID).toMap()
        assertEquals("alternate,no_logo", grid["styles"])
        assertEquals("600x900,342x482", grid["dimensions"])
        assertEquals("image/png,image/webp", grid["mimes"])
        assertEquals("static", grid["types"])
        assertEquals("false", grid["nsfw"])
        assertEquals("false", grid["humor"])
        assertEquals("false", grid["epilepsy"])
        assertEquals("50", grid["limit"])
        assertEquals("2", grid["page"])
        val logo = filters.toParameters(SgdbAssetType.LOGO).toMap()
        assertEquals("official", logo["styles"])
        assertNull(logo["dimensions"])
        assertEquals("any", SgdbFilters(nsfw = SgdbContentFilter.ANY).toParameters(SgdbAssetType.HERO).toMap()["nsfw"])
    }

    @Test
    fun gridsRequestAndMapping() = runTest {
        val http = TestHttp { json(gridsJson) }
        val client = SteamGridDbClient(http.client, "SGDB_SECRET", RateLimiter.unlimited())
        val page = client.grids(5247, SgdbFilters(dimensions = SgdbDimension.PORTRAIT, types = setOf(SgdbAnimation.STATIC)))
        val req = http.last
        assertEquals("/api/v2/grids/game/5247", req.url.encodedPath)
        assertEquals("Bearer SGDB_SECRET", req.headers[HttpHeaders.Authorization])
        assertEquals("static", req.url.parameters["types"])
        assertEquals("false", req.url.parameters["nsfw"])
        val assets = (page as ApiResult.Success).value
        assertEquals(3, assets.total)
        val options = assets.data.map { it.toArtworkOption(SgdbAssetType.GRID) }
        assertEquals(listOf(MediaKind.BOXART, MediaKind.GRID, MediaKind.BOXART), options.map { it.kind })
        assertEquals(ScrapeProviderId.STEAMGRIDDB, options[0].provider)
        assertEquals("artist", options[0].author)
        assertEquals(12, options[0].score)
        assertEquals(920, options[1].width)
        assertEquals("alternate", options[0].style)
    }

    @Test
    fun heroesLogosIconsMapToKinds() {
        assertEquals(MediaKind.HERO, SgdbAssetType.HERO.mediaKind(1920, 620))
        assertEquals(MediaKind.LOGO, SgdbAssetType.LOGO.mediaKind(800, 300))
        assertEquals(MediaKind.ICON, SgdbAssetType.ICON.mediaKind(256, 256))
        assertEquals(MediaKind.BOXART, SgdbAssetType.GRID.mediaKind(660, 930))
        assertEquals(MediaKind.GRID, SgdbAssetType.GRID.mediaKind(460, 215))
        assertEquals(MediaKind.SQUARE, SgdbAssetType.GRID.mediaKind(512, 512))
        assertEquals(MediaKind.SQUARE, SgdbAssetType.GRID.mediaKind(1024, 1024))
        assertEquals(MediaKind.ICON, SgdbAssetType.ICON.mediaKind(512, 512))
    }

    @Test
    fun searchAndLookup() = runTest {
        val http = TestHttp { request ->
            when {
                "autocomplete" in request.url.encodedPath -> json(
                    """{"success":true,"data":[{"id":5247,"name":"Super Metroid","types":["steam"],"verified":true,
                        "release_date":764553600},{"id":999,"name":"Metroid Fusion","types":[],"verified":false}]}""",
                )
                "/games/steam/" in request.url.encodedPath -> json("""{"success":false,"errors":["Game not found"]}""", HttpStatusCode.NotFound)
                else -> json("""{"success":true,"data":{"id":5247,"name":"Super Metroid","types":["steam"],"verified":true}}""")
            }
        }
        val client = SteamGridDbClient(http.client, "SGDB_SECRET", RateLimiter.unlimited())
        val games = (client.searchAutocomplete("Super Metroid: X") as ApiResult.Success).value
        assertEquals(listOf(5247L, 999L), games.map { it.id })
        assertEquals(764553600L, games[0].releaseDate)
        assertTrue(http.last.url.encodedPath.endsWith("/search/autocomplete/Super%20Metroid%3A%20X"), http.last.url.encodedPath)
        assertEquals("Super Metroid", (client.gameById(5247) as ApiResult.Success).value?.name)
        assertNull((client.gameBySteamAppId(1) as ApiResult.Success).value)
    }

    @Test
    fun rejectedKeyNeverLeaks() = runTest {
        val http = TestHttp { json("""{"success":false,"errors":["Unauthorized"]}""", HttpStatusCode.Unauthorized) }
        val client = SteamGridDbClient(http.client, "SGDB_SECRET_123", RateLimiter.unlimited())
        val result = client.verifyKey()
        assertIs<KeyCheck.Rejected>(result)
        assertFalse("SGDB_SECRET_123" in result.reason)
        assertFalse("SGDB_SECRET_123" in client.toString())
    }
}
