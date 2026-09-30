package io.github.matiyaaa.fuse.integrations.screenscraper

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.json
import io.github.matiyaaa.fuse.integrations.text
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MetadataSource
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenScraperTest {

    private val dev = ScreenScraperDevCredentials("fusedev", "DEV_PASS_77", "Fuse")
    private val user = ScreenScraperUserCredentials("alice", "USER_PASS_88")

    private val jeuInfosJson = """
        {"header":{"APIversion":"2.0","dateTime":"2026-09-30 10:00:00","commandRequested":"...","success":"true","error":""},
         "response":{
           "serveurs":{"cpu1":"12"},
           "ssuser":{"id":"alice","numid":"123","niveau":"1","maxthreads":"4","maxrequestspermin":"120",
                     "requeststoday":"12","maxrequestsperday":"20000"},
           "jeu":{"id":"3","romid":"45678","notgame":"false",
             "noms":[{"region":"ss","text":"Sonic The Hedgehog"},{"region":"jp","text":"Sonic The Hedgehog (JP)"}],
             "systeme":{"id":"1","text":"Megadrive"},
             "editeur":{"id":"3","text":"SEGA"},"developpeur":{"id":"2","text":"Sonic Team"},
             "joueurs":{"text":"1"},"note":{"text":"17"},
             "synopsis":[{"langue":"fr","text":"Sonic en français"},{"langue":"en","text":"Sonic runs fast."}],
             "dates":[{"region":"us","text":"1991-06-23"},{"region":"jp","text":"1991-07-26"}],
             "genres":[{"id":"1","principale":"1","parentid":"0","noms":[{"langue":"en","text":"Platform"},{"langue":"fr","text":"Plateforme"}]}],
             "medias":[
               {"type":"box-2D","parent":"jeu","url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=fusedev&devpassword=DEV_PASS_77&softname=Fuse&ssid=alice&sspassword=USER_PASS_88&systemeid=1&jeuid=3&media=box-2D(us)","region":"us","format":"png"},
               {"type":"box-2D","parent":"jeu","url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=fusedev&devpassword=DEV_PASS_77&softname=Fuse&systemeid=1&jeuid=3&media=box-2D(jp)","region":"jp","format":"png"},
               {"type":"wheel","parent":"jeu","url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?devid=fusedev&devpassword=DEV_PASS_77&softname=Fuse&jeuid=3&media=wheel(wor)","region":"wor","format":"png"},
               {"type":"ss","parent":"jeu","url":"https://neoclone.screenscraper.fr/api2/mediaJeu.php?jeuid=3&media=ss","format":"png"},
               {"type":"box-3D","parent":"jeu","url":"https://neoclone.screenscraper.fr/x","format":"png"}],
             "rom":{"romfilename":"Sonic The Hedgehog (USA, Europe).md","romsize":"524288","romcrc":"F9394E97",
                    "rommd5":"1bc674be034e43c96b86487ac69d9293","romsha1":"6ddb7de1e17e7f6cdb88927bd906352030daa194"}}}}
    """.trimIndent()

    @Test
    fun withoutDeveloperCredentialsNothingIsSent() = runTest {
        val http = TestHttp { json("{}") }
        val client = ScreenScraperClient(http.client, devCredentials = null)
        val status = client.status()
        assertFalse(status.configured)
        assertEquals("needs ScreenScraper developer credentials", status.note)
        assertIs<ApiResult.NotConfigured>(client.gameInfo(SsRomQuery(md5 = "abc")))
        assertIs<ApiResult.NotConfigured>(client.search("Sonic"))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun jeuInfosRequestParsingAndQuota() = runTest {
        val http = TestHttp { json(jeuInfosJson) }
        val client = ScreenScraperClient(http.client, dev, user)
        val game = (client.gameInfo(
            SsRomQuery(crc32 = "f9394e97", md5 = "1BC674BE034E43C96B86487AC69D9293", sizeBytes = 524288, systemId = 1, fileName = "/roms/md/Sonic The Hedgehog (USA, Europe).md"),
        ) as ApiResult.Success).value!!
        val p = http.last.url.parameters
        assertEquals("/api2/jeuInfos.php", http.last.url.encodedPath)
        assertEquals("json", p["output"])
        assertEquals("F9394E97", p["crc"])
        assertEquals("1bc674be034e43c96b86487ac69d9293", p["md5"])
        assertEquals("524288", p["romtaille"])
        assertEquals("1", p["systemeid"])
        assertEquals("rom", p["romtype"])
        assertEquals("Sonic The Hedgehog (USA, Europe).md", p["romnom"])
        assertEquals("alice", p["ssid"])

        assertEquals("Sonic The Hedgehog", game.name())
        assertEquals("Sonic The Hedgehog (JP)", game.name(listOf("jp")))
        assertEquals(1991, game.year)
        assertTrue(game.matchesChecksum("1BC674BE034E43C96B86487AC69D9293", null))
        val meta = game.toMetadata("en")
        assertEquals("Sonic runs fast.", meta.description)
        assertEquals("Sonic Team", meta.developer)
        assertEquals("SEGA", meta.publisher)
        assertEquals(listOf("Platform"), meta.genres)
        assertEquals(85, meta.rating)
        assertEquals(MetadataSource.SCREENSCRAPER, meta.source)
        assertEquals("Sonic en français", game.toMetadata("fr").description)

        val art = game.artwork(listOf("jp"))
        assertEquals(listOf(MediaKind.BOXART, MediaKind.BOXART, MediaKind.LOGO, MediaKind.SCREENSHOT), art.map { it.kind })
        assertTrue(art.first().url.endsWith("media=box-2D(jp)"))
        // Credentials are stripped from stored media URLs and only added back for the download.
        art.forEach { option ->
            assertFalse("DEV_PASS_77" in option.url)
            assertFalse("USER_PASS_88" in option.url)
            assertFalse("devid=" in option.url)
        }
        val authorized = client.authorizeMediaUrl(art.first().url)
        assertTrue("devpassword=DEV_PASS_77" in authorized && "sspassword=USER_PASS_88" in authorized)

        assertEquals(4, client.maxThreads)
    }

    @Test
    fun knownErrorCodesMapToResults() = runTest {
        var status = HttpStatusCode.OK
        val http = TestHttp { text("Erreur : ${status.value}", status) }
        val client = ScreenScraperClient(http.client, dev, user)
        suspend fun codeResult(code: Int): ApiResult<SsGame?> {
            status = HttpStatusCode.fromValue(code)
            return client.gameInfo(SsRomQuery(md5 = "00"))
        }
        assertIs<ApiResult.AuthError>(codeResult(401))
        assertIs<ApiResult.AuthError>(codeResult(403))
        assertEquals(423, (codeResult(423) as ApiResult.HttpError).code)
        assertEquals(426, (codeResult(426) as ApiResult.HttpError).code)
        assertIs<ApiResult.RateLimited>(codeResult(429))
        assertIs<ApiResult.RateLimited>(codeResult(430))
        assertIs<ApiResult.RateLimited>(codeResult(431))
        assertNull((codeResult(404) as ApiResult.Success).value)
        for (code in listOf(401, 403, 423, 426, 429, 430, 431)) {
            val failure = codeResult(code) as ApiResult.Failure
            assertFalse("DEV_PASS_77" in failure.message)
            assertFalse("USER_PASS_88" in failure.message)
        }
    }

    @Test
    fun searchDropsEmptyPlaceholder() = runTest {
        val http = TestHttp {
            json("""{"response":{"jeux":[{}]}}""")
        }
        val client = ScreenScraperClient(http.client, dev)
        assertEquals(emptyList(), (client.search("nothing like this") as ApiResult.Success).value)
        assertEquals("nothing like this", http.last.url.parameters["recherche"])
        assertNull(http.last.url.parameters["ssid"])
    }

    @Test
    fun credentialsNeverPrint() {
        assertFalse("DEV_PASS_77" in dev.toString())
        assertFalse("fusedev" in dev.toString())
        assertFalse("USER_PASS_88" in user.toString())
        assertEquals(
            "https://x/mediaJeu.php?systemeid=1&jeuid=3",
            ScreenScraperClient.stripCredentials("https://x/mediaJeu.php?devid=a&devpassword=b&softname=c&systemeid=1&ssid=d&sspassword=e&jeuid=3"),
        )
    }
}
