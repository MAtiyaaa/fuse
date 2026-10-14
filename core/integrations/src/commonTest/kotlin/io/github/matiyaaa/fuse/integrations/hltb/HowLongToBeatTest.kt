package io.github.matiyaaa.fuse.integrations.hltb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
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

class HowLongToBeatTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val answer = """{"count":2,"data":[
        {"game_id":4000,"game_name":"Golden Sun","game_alias":"Golden Sun: The Broken Seal","comp_main":79957,"comp_plus":95553,"comp_100":98445,"release_world":2001,"profile_platform":"Game Boy Advance","game_image":"4000_Golden_Sun.jpg"},
        {"game_id":4002,"game_name":"Golden Sun: The Lost Age","comp_main":114216,"comp_plus":138204,"comp_100":146304,"release_world":2002,"profile_platform":"Game Boy Advance"}
    ]}"""

    @Test
    fun `asks for a token, then searches with it`() = runTest {
        val seen = ArrayList<String>()
        val http = HttpClient(MockEngine { req ->
            seen += "${req.method.value} ${req.url.encodedPath}"
            when {
                req.url.encodedPath.endsWith("/init") -> respond("""{"token":"abc"}""", HttpStatusCode.OK, json)
                else -> {
                    assertEquals("abc", req.headers["x-auth-token"])
                    respond(answer, HttpStatusCode.OK, json)
                }
            }
        })
        val result = HowLongToBeat(http) { 1_000L }.search("Golden Sun")
        assertIs<HltbResult.Found>(result)
        assertEquals(listOf(4000L, 4002L), result.games.map { it.id })
        assertEquals(79957L, result.games.first().mainSeconds)
        assertEquals("https://howlongtobeat.com/games/4000_Golden_Sun.jpg", result.games.first().image)
        assertEquals(listOf("GET /api/search/site/init", "POST /api/search/site"), seen)
    }

    @Test
    fun `a refused token is renewed once, then it gives up`() = runTest {
        var inits = 0
        var searches = 0
        val http = HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/init")) {
                inits++
                respond("""{"token":"t$inits"}""", HttpStatusCode.OK, json)
            } else {
                searches++
                if (req.headers["x-auth-token"] == "t2") respond(answer, HttpStatusCode.OK, json) else respond("", HttpStatusCode.Forbidden)
            }
        })
        assertIs<HltbResult.Found>(HowLongToBeat(http) { 1L }.search("Golden Sun"))
        assertEquals(2, inits)
        assertEquals(2, searches)
    }

    @Test
    fun `too many searches waits instead of asking again`() = runTest {
        var searches = 0
        val http = HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/init")) respond("""{"token":"t"}""", HttpStatusCode.OK, json)
            else { searches++; respond("", HttpStatusCode.TooManyRequests) }
        })
        val hltb = HowLongToBeat(http) { 5L }
        assertIs<HltbResult.Unavailable>(hltb.search("Golden Sun"))
        assertIs<HltbResult.Unavailable>(hltb.search("Golden Sun"))
        assertEquals(1, searches)
    }

    @Test
    fun `a search that moved is found again in the site's scripts`() = runTest {
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path == "/" -> respond("""<script src="/_next/static/chunks/abc.js"></script>""", HttpStatusCode.OK)
                path == "/_next/static/chunks/abc.js" -> respond("""a=fetch("/api/error",{method:"POST"});r=await fetch("/api/find/v3",{method:"POST",headers:{}})""", HttpStatusCode.OK)
                path.endsWith("/init") -> respond("""{"token":"t"}""", HttpStatusCode.OK, json)
                path == "/api/find/v3" -> respond(answer, HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val result = HowLongToBeat(http) { 1L }.search("Golden Sun")
        assertIs<HltbResult.Found>(result)
        assertEquals(2, result.games.size)
    }

    @Test
    fun `the right game is picked, and never a guess`() {
        val games = HowLongToBeat.parse(answer)
        assertEquals(4000L, HowLongToBeat.pick(games, listOf("Golden Sun (USA)"), listOf("Game Boy Advance"), 2001)?.id)
        // Its other name works too.
        assertEquals(4000L, HowLongToBeat.pick(games, listOf("Golden Sun - The Broken Seal"), emptyList(), null)?.id)
        // Only a name that is another game's start: nothing.
        assertNull(HowLongToBeat.pick(games, listOf("Golden"), emptyList(), null))
        // Two with the very name and nothing to tell them apart: nothing.
        val twins = listOf(HltbGame(1, "Tomb Raider", year = 1996), HltbGame(2, "Tomb Raider", year = 2013))
        assertNull(HowLongToBeat.pick(twins, listOf("Tomb Raider"), emptyList(), null))
        assertEquals(1L, HowLongToBeat.pick(twins, listOf("Tomb Raider"), emptyList(), 1996)?.id)
        assertTrue(HowLongToBeat.searchPathIn("""fetch("/api/error",{method:"POST"})""") == null)
    }
}
