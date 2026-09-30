package io.github.matiyaaa.fuse.integrations.igdb

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.bodyText
import io.github.matiyaaa.fuse.integrations.json
import io.github.matiyaaa.fuse.model.PlatformId
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IgdbTest {

    private val gamesJson = """
        [{"id":1103,"cover":{"id":85210,"image_id":"co1tqi"},"first_release_date":764553600,
          "genres":[{"id":8,"name":"Platform"},{"id":31,"name":"Adventure"}],
          "involved_companies":[{"id":1,"company":{"id":70,"name":"Nintendo R&D1"},"developer":true,"publisher":false},
                                {"id":2,"company":{"id":71,"name":"Nintendo"},"developer":false,"publisher":true}],
          "name":"Super Metroid","platforms":[{"id":19,"abbreviation":"SNES","name":"Super Nintendo Entertainment System"}],
          "screenshots":[{"id":1,"image_id":"sc6j0c"}],"artworks":[{"id":2,"image_id":"ar5g3"}],
          "summary":"Samus returns to Zebes.","game_type":0}]
    """.trimIndent()

    private class Clock(var now: Long = 0)

    private fun harness(tokenStatus: () -> HttpStatusCode = { HttpStatusCode.OK }, gamesStatus: (Int) -> HttpStatusCode = { HttpStatusCode.OK }): Triple<TestHttp, IgdbClient, Clock> {
        var tokens = 0
        var gameCalls = 0
        val http = TestHttp { request ->
            if (request.url.host == "id.twitch.tv") {
                tokens++
                json("""{"access_token":"token$tokens","expires_in":5011271,"token_type":"bearer"}""", tokenStatus())
            } else {
                gameCalls++
                json(gamesJson, gamesStatus(gameCalls))
            }
        }
        val clock = Clock()
        val client = IgdbClient(http.client, IgdbCredentials("client-abc", "TWITCH_SECRET_XYZ"), RateLimiter.unlimited(), nowMillis = { clock.now })
        return Triple(http, client, clock)
    }

    @Test
    fun tokenIsFetchedOnceAndReused() = runTest {
        val (http, client, _) = harness()
        client.searchGames("Super Metroid")
        client.searchGames("Metroid Fusion")
        val tokenRequests = http.requests.filter { it.url.host == "id.twitch.tv" }
        assertEquals(1, tokenRequests.size)
        val t = tokenRequests.single()
        assertEquals(HttpMethod.Post, t.method)
        assertEquals("client-abc", t.url.parameters["client_id"])
        assertEquals("TWITCH_SECRET_XYZ", t.url.parameters["client_secret"])
        assertEquals("client_credentials", t.url.parameters["grant_type"])
        val g = http.last
        assertEquals("https://api.igdb.com/v4/games", g.url.toString())
        assertEquals("client-abc", g.headers["Client-ID"])
        assertEquals("Bearer token1", g.headers[HttpHeaders.Authorization])
    }

    @Test
    fun tokenRefreshesAfterExpiry() = runTest {
        val (http, client, clock) = harness()
        client.searchGames("a")
        clock.now = 5_011_271L * 1000
        client.searchGames("b")
        assertEquals(2, http.requests.count { it.url.host == "id.twitch.tv" })
        assertEquals("Bearer token2", http.last.headers[HttpHeaders.Authorization])
    }

    @Test
    fun unauthorizedRetriesOnceWithFreshToken() = runTest {
        val (http, client, _) = harness(gamesStatus = { call -> if (call == 1) HttpStatusCode.Unauthorized else HttpStatusCode.OK })
        val result = client.searchGames("Super Metroid")
        assertIs<ApiResult.Success<*>>(result)
        assertEquals(2, http.requests.count { it.url.host == "id.twitch.tv" })
        assertEquals("Bearer token2", http.last.headers[HttpHeaders.Authorization])
    }

    @Test
    fun badCredentialsAreAuthErrorsWithoutTheSecret() = runTest {
        val (_, client, _) = harness(tokenStatus = { HttpStatusCode.BadRequest })
        val result = client.verifyCredentials()
        assertIs<ApiResult.AuthError>(result)
        assertFalse("TWITCH_SECRET_XYZ" in result.message)
        assertFalse("TWITCH_SECRET_XYZ" in IgdbCredentials("id", "TWITCH_SECRET_XYZ").toString())
    }

    @Test
    fun queryBodyAndParsing() = runTest {
        val (http, client, _) = harness()
        val games = (client.searchGames("Super \"Metroid\"", listOf(19), limit = 5) as ApiResult.Success).value
        val body = http.last.bodyText
        assertTrue(body.startsWith("search \"Super \\\"Metroid\\\"\"; fields "), body)
        assertTrue("cover.image_id" in body && "involved_companies.developer" in body && "game_type" in body)
        assertTrue("where version_parent = null & platforms = (19); limit 5;" in body, body)
        val g = games.single()
        assertEquals("Super Metroid", g.name)
        assertEquals(listOf("Nintendo R&D1"), g.developers)
        assertEquals(listOf("Nintendo"), g.publishers)
        assertEquals("co1tqi", g.cover?.imageId)
        assertEquals(0L, g.gameType)
        assertEquals("fields ${IgdbQuery.GAME_FIELDS}; where id = 1103; limit 1;", IgdbQuery.byId(1103))
    }

    @Test
    fun imageUrls() {
        assertEquals("https://images.igdb.com/igdb/image/upload/t_cover_big/co1tqi.jpg", IgdbImages.url("co1tqi", IgdbImageSize.COVER_BIG))
        assertEquals("https://images.igdb.com/igdb/image/upload/t_1080p_2x/ar5g3.jpg", IgdbImages.url("ar5g3", IgdbImageSize.P1080, retina = true))
        assertEquals("https://images.igdb.com/igdb/image/upload/t_logo_med/x.jpg", IgdbImages.url("x", IgdbImageSize.LOGO_MED))
        assertEquals(19, IgdbPlatforms.idFor(PlatformId("snes")))
    }

    @Test
    fun missingCredentialsAreNotConfigured() = runTest {
        val http = TestHttp { json("[]") }
        val client = IgdbClient(http.client, "", "")
        assertIs<ApiResult.NotConfigured>(client.searchGames("x"))
        assertTrue(http.requests.isEmpty())
    }
}
