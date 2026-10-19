package io.github.matiyaaa.fuse.romm

import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.integrations.net.RoutePicker
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The person's changes to RomM, as RomM 5.4 takes them (backend/endpoints/roms/__init__.py
 * `update_rom_user`, collections.py `add_collection` and `add_roms_to_collection`,
 * play_sessions.py `ingest_play_sessions`, tasks.py `start_scan` and `get_task_by_id`), its
 * Cloudflare Access token, and the queue they wait in.
 */
class RommWritesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest fun cleanUp() { scope.cancel() }

    private data class Seen(val method: HttpMethod, val url: String, val body: String, val headers: Map<String, String?>)

    private val seen = CopyOnWriteArrayList<Seen>()

    /** The person's collections as RomM lists them; one is made by `POST /api/collections`. */
    private val collections = CopyOnWriteArrayList<String>()

    private val http = HttpClient(MockEngine { req ->
        val body = when (val c = req.body) {
            is OutgoingContent.ByteArrayContent -> String(c.bytes())
            is OutgoingContent.WriteChannelContent -> "(multipart)"
            else -> ""
        }
        val headers = listOf(RommAccess.ID_HEADER, RommAccess.SECRET_HEADER, HttpHeaders.Authorization).associateWith { req.headers[it] }
        seen += Seen(req.method, req.url.toString(), body, headers)
        val path = req.url.encodedPath
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        when {
            path == "/api/heartbeat" -> json("""{"SYSTEM":{"VERSION":"5.4.0"}}""")
            path == "/openapi.json" -> json("""{"paths":{"/api/roms/{id}/props":{},"/api/collections/{id}/roms":{},"/api/play-sessions":{},"/api/tasks/{task_id}":{},"/api/tasks/scan":{}}}""")
            path == "/api/collections" && req.method == HttpMethod.Get -> json(collections.joinToString(",", "[", "]"))
            path == "/api/collections" && req.method == HttpMethod.Post -> {
                collections += """{"id":41,"name":"Favourites","rom_ids":[],"is_favorite":true}"""
                json("""{"id":41,"name":"Favourites","rom_ids":[],"is_favorite":true}""")
            }
            path == "/api/play-sessions" -> json("""{"results":[],"created_count":2,"skipped_count":0}""", HttpStatusCode.Created)
            path == "/api/roms/7/notes" && req.method == HttpMethod.Get -> json(
                """[{"id":5,"title":"Fuse trophies","content":"text","is_public":false,"tags":["fuse"],"user_id":1,"username":"pat","created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z"},""" +
                    """{"id":6,"title":"Tips","content":null,"is_public":true,"tags":null,"user_id":2,"username":"sam","created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z"},{"title":"no id"}]""",
            )
            path == "/api/roms/7/notes" -> json("""{"id":9,"title":"Fuse trophies","content":"made","is_public":false,"tags":["fuse"],"user_id":1,"username":"pat","created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z"}""")
            path == "/api/roms/7/notes/9" -> json("""{"id":9,"title":"Fuse trophies","content":"changed","is_public":false,"tags":["fuse"],"user_id":1,"username":"pat","created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z"}""")
            path == "/api/tasks/scan" -> json("""{"task_key":null,"task_name":"scan_library","task_id":"job-9","status":"queued","created_at":null,"enqueued_at":null}""", HttpStatusCode.Accepted)
            path == "/api/tasks/job-9" -> json(
                """{"task_id":"job-9","task_name":"scan_library","status":"started","task_type":"scan","meta":{"scan_stats":{"total_platforms":3,"scanned_platforms":1,"total_roms":120,"scanned_roms":45,"new_roms":2,"identified_roms":40}}}""",
            )
            else -> json("{}")
        }
    })

    private fun client(local: String? = "http://192.168.1.5:8080", remote: String? = null, mode: RouteMode = RouteMode.LOCAL, access: RommAccess? = null) = RommClient(
        http,
        RoutePicker(local, remote, mode, scope, System::currentTimeMillis, probe = { true }, lookEveryMs = 0),
        RommCredential.Token("tok", RommScopes.READ + RommScopes.CHANGES),
        access = access,
    )

    private fun body(s: Seen) = Json.parseToJsonElement(s.body).jsonObject

    @Test
    fun hidingSendsOnlyHiddenToTheGamesProps(): Unit = runBlocking {
        client().setHidden(7, hidden = true)
        val s = seen.single()
        assertEquals(HttpMethod.Put, s.method)
        assertTrue(s.url.endsWith("/api/roms/7/props"), s.url)
        // Only what changed: RomM keeps the rest (`exclude_unset`).
        assertEquals(setOf("hidden"), body(s).keys)
        assertEquals("true", body(s)["hidden"]!!.jsonPrimitive.content)
    }

    @Test
    fun theFavouritesCollectionIsMadeOnceThenFound(): Unit = runBlocking {
        val c = client()
        assertEquals(41L, c.favourites())
        val made = seen.single { it.method == HttpMethod.Post }
        // As RomM's own pages make it: is_favorite and is_public as query, the name as a form field.
        assertTrue("is_favorite=true" in made.url && "is_public=false" in made.url, made.url)
        assertEquals("(multipart)", made.body)
        seen.clear()
        assertEquals(41L, c.favourites())
        assertTrue(seen.none { it.method == HttpMethod.Post }, "found, not made again")
    }

    @Test
    fun gamesGoIntoAndOutOfACollectionByTheirIdsAlone(): Unit = runBlocking {
        val c = client()
        c.collectionRoms(41, listOf(7, 8), into = true)
        c.collectionRoms(41, listOf(8), into = false)
        c.collectionRoms(41, emptyList(), into = true)
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete), seen.map { it.method })
        assertEquals(listOf(7L, 8L), body(seen[0])["rom_ids"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(listOf(8L), body(seen[1])["rom_ids"]!!.jsonArray.map { it.jsonPrimitive.long })
    }

    @Test
    fun playSessionsGoAsRommReadsThem(): Unit = runBlocking {
        val taken = client().playSessions(listOf(RommPlayed(7, 1_760_000_000_000, 1_760_000_600_000), RommPlayed(8, 1_760_001_000_000, 1_760_001_090_500)))
        assertEquals(2, taken)
        val sessions = body(seen.single())["sessions"]!!.jsonArray.map { it.jsonObject }
        assertEquals("2025-10-09T08:53:20Z", sessions[0]["start_time"]!!.jsonPrimitive.content)
        assertEquals("2025-10-09T09:03:20Z", sessions[0]["end_time"]!!.jsonPrimitive.content)
        assertEquals(600_000L, sessions[0]["duration_ms"]!!.jsonPrimitive.long)
        assertEquals(8L, sessions[1]["rom_id"]!!.jsonPrimitive.long)
        // RomM takes at most a hundred at a time.
        assertFailsWith<IllegalArgumentException> { client().playSessions(List(101) { RommPlayed(1, it * 10_000L, it * 10_000L + 5_000) }) }
    }

    @Test
    fun aGamesNotesAreReadMadeKeptToThePersonAndChangedByTheirTextAlone(): Unit = runBlocking {
        // As RomM 5.4 answers them (backend/endpoints/roms/notes.py, UserNoteSchema in responses/rom.py).
        val c = client()
        val notes = c.notes(7)
        assertEquals(listOf(5L, 6L), notes.map { it.id }, "one without an id is passed over")
        assertEquals(RommNote(5, "Fuse trophies", "text", isPublic = false, username = "pat"), notes[0])
        assertEquals("", notes[1].content)
        assertTrue(notes[1].isPublic)
        seen.clear()
        assertEquals(9L, c.addNote(7, "Fuse trophies", "made", listOf("fuse")).id)
        val made = body(seen.single())
        assertEquals(setOf("title", "content", "is_public", "tags"), made.keys)
        assertEquals("false", made["is_public"]!!.jsonPrimitive.content)
        assertEquals(listOf("fuse"), made["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        seen.clear()
        c.updateNote(7, 9, "changed")
        assertEquals(HttpMethod.Put, seen.single().method)
        assertTrue(seen.single().url.endsWith("/api/roms/7/notes/9"), seen.single().url)
        // Only its text: RomM keeps the title, who may see it and the tags as they were.
        assertEquals(setOf("content"), body(seen.single()).keys)
    }

    @Test
    fun aScanIsFollowedByItsTask(): Unit = runBlocking {
        val c = client()
        c.detect()
        assertTrue(c.capabilities.taskStatus && c.capabilities.userProps && c.capabilities.collectionRoms && c.capabilities.playSessions)
        val id = c.scan()
        assertEquals("job-9", id)
        val task = c.task(id!!)
        assertEquals("started", task.status)
        assertFalse(task.done)
        assertEquals(RommScanStats(totalPlatforms = 3, scannedPlatforms = 1, totalRoms = 120, scannedRoms = 45, newRoms = 2, identifiedRoms = 40), task.scan)
    }

    @Test
    fun theAccessTokenGoesOnlyToItsOwnHostOverHttps(): Unit = runBlocking {
        val access = RommAccess("romm.example.com", "id-123.access", "secret-456")
        // The remote address behind Access: every call carries it, unsigned ones too.
        client(local = null, remote = "https://romm.example.com", mode = RouteMode.REMOTE, access = access).heartbeat()
        assertEquals("id-123.access", seen.single().headers[RommAccess.ID_HEADER])
        assertEquals("secret-456", seen.single().headers[RommAccess.SECRET_HEADER])
        seen.clear()
        // Home, over http: never.
        client(access = access).heartbeat()
        assertNull(seen.single().headers[RommAccess.ID_HEADER])
        assertNull(seen.single().headers[RommAccess.SECRET_HEADER])
        seen.clear()
        // A download made outside the client, to the same host by http, or to another host: never.
        val c = client(local = null, remote = "https://romm.example.com", mode = RouteMode.REMOTE, access = access)
        http.get("https://romm.example.com/api/roms/7/content/a.gba") { c.authorize(this) }
        http.get("http://romm.example.com/api/roms/7/content/a.gba") { c.authorize(this) }
        http.get("https://elsewhere.example.com/x") { c.authorize(this) }
        assertEquals(listOf("secret-456", null, null), seen.map { it.headers[RommAccess.SECRET_HEADER] })
        // Printed anywhere, it never shows the token.
        assertFalse("secret-456" in access.toString() || "id-123" in access.toString())
        assertEquals("romm.example.com", RommAccess.hostOf("https://ROMM.example.com:443/romm/"))
    }

    @Test
    fun theQueueKeepsWhereThePersonLeftEachGameAndEverySession() {
        var q = emptyList<RommWrite>()
        q = RommWrites.add(q, RommWrite.Favourite(7, on = true))
        q = RommWrites.add(q, RommWrite.Played(7, 0, 600_000))
        q = RommWrites.add(q, RommWrite.Hidden(8, on = true))
        q = RommWrites.add(q, RommWrite.Favourite(7, on = false))
        // A session too short for RomM, and the same session twice: neither added.
        q = RommWrites.add(q, RommWrite.Played(7, 1_000_000, 1_000_400))
        q = RommWrites.add(q, RommWrite.Played(7, 0, 600_000))
        assertEquals(listOf(RommWrite.Played(7, 0, 600_000), RommWrite.Hidden(8, true), RommWrite.Favourite(7, false)), q)
        // Sessions in a row go together, at most a hundred; anything else alone.
        val many = (0 until 150).fold(emptyList<RommWrite>()) { acc, i -> RommWrites.add(acc, RommWrite.Played(1, i * 10_000L, i * 10_000L + 5_000)) } + RommWrite.Hidden(1, true)
        assertEquals(100, RommWrites.next(many).size)
        assertEquals(listOf(RommWrite.Hidden(8, true)), RommWrites.next(RommWrites.without(q, RommWrites.next(q))))
        assertTrue(RommWrites.next(emptyList()).isEmpty())
        // A game's trophies wait once: they are read from its history when they go.
        val t = RommWrites.add(RommWrites.add(RommWrites.add(emptyList(), RommWrite.Trophies(7, 70)), RommWrite.Hidden(7, true)), RommWrite.Trophies(7, 70))
        assertEquals(listOf(RommWrite.Hidden(7, true), RommWrite.Trophies(7, 70)), t)
        // Taken off once each, as it was sent: a change made meanwhile stays.
        val sent = RommWrites.next(q)
        val meanwhile = RommWrites.add(q, RommWrite.Hidden(9, true))
        assertEquals(listOf(RommWrite.Hidden(8, true), RommWrite.Favourite(7, false), RommWrite.Hidden(9, true)), RommWrites.without(meanwhile, sent))
    }
}
