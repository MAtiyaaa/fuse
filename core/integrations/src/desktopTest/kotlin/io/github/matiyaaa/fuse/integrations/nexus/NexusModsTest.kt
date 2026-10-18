package io.github.matiyaaa.fuse.integrations.nexus

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.Secret
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Nexus Mods' API through a stand-in answering as its documentation and its own client's types
 * describe (a made-up game and mod): the person's key in its header and nowhere else, files read
 * with what Fuse would add, and download links as each kind of account gets them.
 */
class NexusModsTest {
    private val key = "abcDEF123-personal-key"
    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(premium: Boolean) = NexusMods(
        HttpClient(MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                request.headers["apikey"] != key -> respond("""{"message":"Please provide a valid API Key"}""", HttpStatusCode.Unauthorized, json)
                path == "/v1/users/validate.json" ->
                    respond("""{"user_id":7,"key":"$key","name":"Mo","is_premium":$premium,"is_supporter":false,"email":"mo@example.com","profile_url":"https://x"}""", HttpStatusCode.OK, json)
                path == "/v1/games.json" -> respond(
                    """[{"id":1,"domain_name":"lanternquest","name":"Lantern Quest","mods":120},{"id":2,"domain_name":"lanternquest2","name":"Lantern Quest II","mods":40},
                       {"id":3,"domain_name":"otherthing","name":"Other Thing","mods":5}]""",
                    HttpStatusCode.OK, json,
                )
                path == "/v1/games/lanternquest/mods/trending.json" -> respond(
                    """[{"mod_id":55,"game_id":1,"domain_name":"lanternquest","name":"Brighter Lanterns","summary":"More light","version":"1.2","author":"Ana",
                        "picture_url":"https://staticdelivery.nexusmods.com/x.jpg","updated_timestamp":1790000000,"endorsement_count":31,"contains_adult_content":false,
                        "available":true,"status":"published","category_id":3,"user":{"member_id":1,"member_group_id":1,"name":"Ana"}}]""",
                    HttpStatusCode.OK, json,
                )
                path == "/v1/games/lanternquest/mods/55/files.json" -> respond(
                    """{"files":[
                        {"file_id":900,"category_id":1,"category_name":"MAIN","name":"Brighter Lanterns","file_name":"Brighter Lanterns-55-1-2-1790000000.zip","version":"1.2","size":2048,"size_kb":2048,"uploaded_timestamp":1790000000,"is_primary":true},
                        {"file_id":800,"category_id":4,"category_name":"OLD_VERSION","name":"Brighter Lanterns","file_name":"Brighter Lanterns-55-1-1.zip","version":"1.1","size":2000,"size_kb":2000,"uploaded_timestamp":1780000000,"is_primary":false},
                        {"file_id":901,"category_id":3,"category_name":"OPTION","name":"Installer","file_name":"Setup-55.exe","version":"1.2","size":10,"size_kb":10,"uploaded_timestamp":1790000001,"is_primary":false}
                      ],"file_updates":[]}""",
                    HttpStatusCode.OK, json,
                )
                path == "/v1/games/lanternquest/mods/55/files/900/download_link.json" -> when {
                    premium -> respond("""[{"name":"Nexus CDN","short_name":"Nexus CDN","URI":"https://cf-files.nexusmods.com/cdn/1/55/x.zip?md5=1"},{"name":"Old","short_name":"o","URI":"http://insecure/x.zip"}]""", HttpStatusCode.OK, json)
                    request.url.parameters["key"] == "onetime" && request.url.parameters["expires"] == "1790003600" ->
                        respond("""[{"name":"Nexus CDN","short_name":"Nexus CDN","URI":"https://cf-files.nexusmods.com/cdn/1/55/x.zip?md5=2"}]""", HttpStatusCode.OK, json)
                    request.url.parameters["key"] != null -> respond("""{"message":"expired"}""", HttpStatusCode.Gone, json)
                    else -> respond("""{"message":"premium only"}""", HttpStatusCode.Forbidden, json)
                }
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }),
        Secret(key), "0.4.4",
    )

    @Test
    fun theKeyGoesInItsHeaderAndNowhereElse(): Unit = runBlocking {
        val api = client(premium = false)
        val me = assertIs<ApiResult.Success<NexusUser>>(api.validate()).value
        assertEquals("Mo", me.name)
        assertFalse(me.premium)
        val r = requests.single()
        assertEquals("Fuse", r.headers["Application-Name"])
        assertEquals("0.4.4", r.headers["Application-Version"])
        assertFalse(key in r.url.toString())
        // A failure's words never carry it.
        val wrong = NexusMods(HttpClient(MockEngine { respond("""{"message":"bad key $key"}""", HttpStatusCode.Unauthorized, json) }), Secret(key), "0.4.4")
        val failure = assertIs<ApiResult.Failure>(wrong.validate())
        assertFalse(key in failure.message, failure.message)
        assertEquals("***", Secret(key).toString())
    }

    @Test
    fun aGamesModsAndTheFilesFuseWouldAdd(): Unit = runBlocking {
        val api = client(premium = false)
        val games = assertIs<ApiResult.Success<List<NexusGame>>>(api.games()).value
        assertEquals(listOf("lanternquest", "lanternquest2"), NexusMods.matching(games, "Lantern Quest").map { it.domain })
        val trending = assertIs<ApiResult.Success<List<NexusMod>>>(api.list("lanternquest", NexusList.TRENDING)).value
        assertEquals("Brighter Lanterns", trending.single().name)
        assertEquals("https://www.nexusmods.com/lanternquest/mods/55", trending.single().url)
        val files = assertIs<ApiResult.Success<List<NexusFile>>>(api.files("lanternquest", 55)).value
        assertEquals(listOf(901L, 900L, 800L), files.map { it.id }, "newest first")
        val main = files.single { it.id == 900L }
        assertTrue(main.fetchable && main.primary)
        assertFalse(files.single { it.id == 800L }.fetchable, "an old version stays on the page")
        assertTrue("isn't a .zip" in files.single { it.id == 901L }.notFetchable.orEmpty())
    }

    @Test
    fun premiumDownloadsStraightAwayOthersWithTheWebsitesLink(): Unit = runBlocking {
        val links = assertIs<ApiResult.Success<List<NexusLink>>>(client(premium = true).downloadLinks("lanternquest", 55, 900)).value
        assertEquals(listOf("https://cf-files.nexusmods.com/cdn/1/55/x.zip?md5=1"), links.map { it.uri }, "only secure addresses")

        val free = client(premium = false)
        val refused = assertIs<ApiResult.AuthError>(free.downloadLinks("lanternquest", 55, 900))
        assertTrue("Mod Manager Download" in refused.message)
        val nxm = assertNotNull(NexusMods.nxmOf("nxm://lanternquest/mods/55/files/900?key=onetime&expires=1790003600&user_id=7"))
        assertEquals(Triple("lanternquest", 55L, 900L), Triple(nxm.game, nxm.mod, nxm.file))
        assertEquals("***", nxm.key.toString())
        val got = assertIs<ApiResult.Success<List<NexusLink>>>(free.downloadLinks("lanternquest", 55, 900, nxm)).value
        assertEquals("https://cf-files.nexusmods.com/cdn/1/55/x.zip?md5=2", got.single().uri)
        val stale = NexusMods.nxmOf("nxm://lanternquest/mods/55/files/900?key=old&expires=1")!!
        assertTrue("run out" in (free.downloadLinks("lanternquest", 55, 900, stale) as ApiResult.Failure).message)
    }

    @Test
    fun linksAreReadOnlyWhenTheyAreWhatTheySeem() {
        assertEquals("lanternquest" to 55L, NexusMods.modOf("https://www.nexusmods.com/lanternquest/mods/55?tab=files"))
        assertEquals("lanternquest" to 55L, NexusMods.modOf("nexusmods.com/LanternQuest/mods/55"))
        assertNull(NexusMods.modOf("https://example.com/lanternquest/mods/55"))
        assertNull(NexusMods.nxmOf("https://www.nexusmods.com/lanternquest/mods/55"))
        assertNull(NexusMods.nxmOf("nxm://lanternquest/mods/55/files/900?expires=1"), "no key")
        assertNull(NexusMods.nxmOf("nxm://lanternquest/mods/55/files/900?key=a%20b&expires=1"), "a key Nexus Mods never makes")
    }
}
