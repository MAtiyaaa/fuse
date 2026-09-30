package io.github.matiyaaa.fuse.integrations.thegamesdb

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.json
import io.github.matiyaaa.fuse.model.MediaKind
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class TheGamesDbTest {

    private val searchJson = """
        {"code":200,"status":"Success",
         "data":{"count":1,"games":[{"id":136,"game_title":"Super Metroid","release_date":"1994-03-19","platform":6,
            "region_id":0,"country_id":0,"developers":[6255],"overview":"Samus returns.","players":1,
            "publishers":[4],"genres":[1,15],"rating":"E - Everyone"}]},
         "include":{
           "boxart":{"base_url":{"original":"https://cdn.thegamesdb.net/images/original/","small":"https://cdn.thegamesdb.net/images/small/",
              "thumb":"https://cdn.thegamesdb.net/images/thumb/","cropped_center_thumb":"https://cdn.thegamesdb.net/images/cropped_center_thumb/",
              "medium":"https://cdn.thegamesdb.net/images/medium/","large":"https://cdn.thegamesdb.net/images/large/"},
              "data":{"136":[{"id":1234,"type":"boxart","side":"front","filename":"boxart/front/136-1.jpg","resolution":"1000x1400"},
                             {"id":1235,"type":"boxart","side":"back","filename":"boxart/back/136-1.jpg","resolution":"1000x1400"}]}},
           "platform":{"data":{"6":{"id":6,"name":"Super Nintendo (SNES)","alias":"super-nintendo-snes"}}}},
         "pages":{"previous":null,"current":"https://api.thegamesdb.net/v1.1/Games/ByGameName?name=Super%20Metroid&page=1","next":null},
         "remaining_monthly_allowance":2987,"extra_allowance":0,"allowance_refresh_timer":2102163}
    """.trimIndent()

    private val imagesJson = """
        {"code":200,"status":"Success",
         "data":{"count":3,"base_url":{"original":"https://cdn2.example.net/art/original/","thumb":"https://cdn2.example.net/art/thumb/"},
           "images":{"136":[{"id":1,"type":"fanart","side":null,"filename":"fanart/136-1.jpg","resolution":"1920x1080"},
                            {"id":2,"type":"clearlogo","side":null,"filename":"clearlogo/136.png","resolution":"400x155"},
                            {"id":3,"type":"titlescreen","side":null,"filename":"titlescreen/136-1.jpg","resolution":null}]}},
         "remaining_monthly_allowance":"2986","extra_allowance":0,"allowance_refresh_timer":2102100}
    """.trimIndent()

    @Test
    fun searchParsesGamesBoxartAndAllowance() = runTest {
        val http = TestHttp { json(searchJson) }
        val client = TheGamesDbClient(http.client, "TGDB_KEY_1234", RateLimiter.unlimited())
        val result = (client.searchByName("Super Metroid", listOf(6)) as ApiResult.Success).value
        val req = http.last
        assertEquals("/v1.1/Games/ByGameName", req.url.encodedPath)
        assertEquals("TGDB_KEY_1234", req.url.parameters["apikey"])
        assertEquals("Super Metroid", req.url.parameters["name"])
        assertEquals("6", req.url.parameters["filter[platform]"])
        assertEquals("boxart,platform", req.url.parameters["include"])
        val game = result.games.single()
        assertEquals("Super Metroid", game.gameTitle)
        assertEquals(6, game.platform)
        assertEquals("1", game.players)
        assertEquals("Super Nintendo (SNES)", result.platformNames[6])
        assertEquals(2987, result.allowance.remainingMonthly)
        val boxart = result.boxartFor(136)
        assertEquals(1, boxart.size) // the back cover is not offered
        assertEquals("https://cdn.thegamesdb.net/images/original/boxart/front/136-1.jpg", boxart[0].url)
        assertEquals("https://cdn.thegamesdb.net/images/thumb/boxart/front/136-1.jpg", boxart[0].thumbUrl)
        assertEquals(1000, boxart[0].width)
        assertEquals(MediaKind.BOXART, boxart[0].kind)
    }

    @Test
    fun imagesUseResponseBaseUrl() = runTest {
        val http = TestHttp { json(imagesJson) }
        val client = TheGamesDbClient(http.client, "TGDB_KEY_1234", RateLimiter.unlimited())
        val result = (client.images(listOf(136), setOf(TgdbImageType.FANART, TgdbImageType.CLEARLOGO, TgdbImageType.TITLESCREEN)) as ApiResult.Success).value
        assertEquals("fanart,clearlogo,titlescreen", http.last.url.parameters["filter[type]"])
        assertEquals("136", http.last.url.parameters["games_id"])
        val art = result.artworkFor(136)
        assertEquals(listOf(MediaKind.HERO, MediaKind.LOGO, MediaKind.SCREENSHOT), art.map { it.kind })
        assertEquals("https://cdn2.example.net/art/original/fanart/136-1.jpg", art[0].url)
        assertEquals("https://cdn2.example.net/art/thumb/clearlogo/136.png", art[1].thumbUrl)
        assertEquals("titlescreen", art[2].style)
        assertEquals(2986, result.allowance.remainingMonthly)
    }

    @Test
    fun errorsAreTypedAndRedacted() = runTest {
        var body = """{"code":403,"status":"This API Key has reached its allowance limit."}"""
        var status = HttpStatusCode.Forbidden
        val http = TestHttp { json(body, status) }
        val client = TheGamesDbClient(http.client, "TGDB_KEY_1234", RateLimiter.unlimited())
        assertIs<ApiResult.RateLimited>(client.searchByName("x"))
        body = """{"code":401,"status":"Invalid API key"}"""
        status = HttpStatusCode.Unauthorized
        val auth = client.searchByName("x")
        assertIs<ApiResult.AuthError>(auth)
        assertFalse("TGDB_KEY_1234" in auth.message)
    }
}
