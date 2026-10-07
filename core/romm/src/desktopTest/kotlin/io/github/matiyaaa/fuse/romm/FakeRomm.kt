package io.github.matiyaaa.fuse.romm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A RomM server in memory, close enough to RomM 4 for Fuse's client: heartbeat, the OpenAPI paths,
 * platforms, paged games with files, identifiers, firmware, collections, file downloads with ranges,
 * chunked uploads, scans and the device pairing flow. Each address can be made to stop answering.
 */
class FakeRomm(var version: String = "4.4.0") {
    data class Game(
        val id: Long,
        val platformId: Long,
        val slug: String,
        val name: String,
        val fsName: String,
        val files: List<Triple<Long, String, ByteArray>>,
        var updated: String = "2026-01-01T00:00:00+00:00",
        val created: String = "2026-01-01T00:00:00+00:00",
        val titleId: String? = null,
        val folderPaths: Map<Long, String> = emptyMap(),
    )

    val games = CopyOnWriteArrayList<Game>()
    val firmware = CopyOnWriteArrayList<Triple<Long, String, ByteArray>>()
    val calls = CopyOnWriteArrayList<String>()
    val down = ConcurrentHashMap.newKeySet<String>()
    @Volatile var openApi = true
    /** Pages of the library bigger than this take too long to put together (as a big PS5 page does). */
    @Volatile var slowAbove: Int = Int.MAX_VALUE
    /** The library can't be read from this many games on (the connection drops part way). */
    @Volatile var dropFrom: Int = Int.MAX_VALUE
    @Volatile var acceptedTokens = setOf("good", "readonly")
    /** Scopes per token. */
    val scopes = mapOf("good" to RommScopes.UPLOAD_AND_SCAN, "readonly" to RommScopes.READ)
    val uploads = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    val uploadMeta = ConcurrentHashMap<String, Triple<String, Long, String>>()
    val received = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    @Volatile var scans = 0
    /** Putting an upload together takes longer than Fuse waits for an answer (a big game on a slow disk): the file still lands. */
    @Volatile var slowComplete = false
    /** This many pieces of uploads take too long to arrive (a slow connection) and are lost. */
    @Volatile var slowChunks = 0
    @Volatile var pairingApproved = false

    val client = HttpClient(MockEngine { req -> handle(req) })

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun romJson(g: Game, withFiles: Boolean = true): String {
        val files = if (!withFiles) "" else g.files.joinToString(",") { (id, name, bytes) ->
            val folder = g.folderPaths[id]?.let { "/$it" } ?: ""
            """{"id":$id,"file_name":"$name","file_path":"${g.slug}/${g.fsName}$folder","file_size_bytes":${bytes.size},"md5_hash":"${md5(bytes)}","category":null}"""
        }
        val md5 = if (g.files.size == 1) "\"${md5(g.files[0].third)}\"" else "null"
        return """{"id":${g.id},"platform_id":${g.platformId},"platform_slug":"${g.slug}","name":"${g.name}","fs_name":"${g.fsName}","fs_path":"${g.slug}",
            "fs_size_bytes":${g.files.sumOf { it.third.size }},"md5_hash":$md5,"title_id":${g.titleId?.let { "\"$it\"" } ?: "null"},"regions":["USA"],
            "metadatum":{"genres":["RPG"],"first_release_date":946684800000},"path_cover_large":"/assets/romm/resources/roms/${g.id}/cover/big.png",
            "has_multiple_files":${g.files.size > 1},"created_at":"${g.created}","updated_at":"${g.updated}","files":[$files]}"""
    }

    private fun authorised(req: HttpRequestData): String? {
        val h = req.headers[HttpHeaders.Authorization] ?: return null
        return h.removePrefix("Bearer ").takeIf { it in acceptedTokens }
    }

    private fun MockRequestHandleScope.handle(req: HttpRequestData): HttpResponseData {
        val host = req.url.host
        calls += "${req.method.value} ${req.url.encodedPath}" + req.url.encodedQuery.let { if (it.isEmpty()) "" else "?$it" }
        if (host in down) throw java.net.ConnectException("$host is down")
        val path = req.url.encodedPath
        when {
            path == "/api/heartbeat" -> return json("""{"SYSTEM":{"VERSION":"$version"}}""")
            path == "/openapi.json" -> return if (openApi) {
                json("""{"paths":{"/api/auth/device/init":{},"/api/client-tokens/exchange":{},"/api/roms/upload/start":{},"/api/tasks/scan":{},"/api/roms/identifiers":{}}}""")
            } else {
                respond("", HttpStatusCode.NotFound)
            }
            path == "/api/auth/device/init" -> return json("""{"device_code":"dc1","user_code":"ABCD2345","verification_path":"/pair/device","verification_path_complete":"/pair/device?user_code=ABCD2345","expires_in":600,"interval":5}""", HttpStatusCode.Created)
            path == "/api/auth/device/token" -> return if (pairingApproved) {
                json("""{"access_token":"readonly","device_id":"d1","scopes":${RommScopes.READ.joinToString(",", "[", "]") { "\"$it\"" }},"expires_at":null}""")
            } else {
                json("""{"detail":"authorization_pending"}""", HttpStatusCode.BadRequest)
            }
            path == "/api/client-tokens/exchange" -> return json("""{"id":1,"name":"Fuse","scopes":["roms.read"],"raw_token":"good","expires_at":null,"last_used_at":null,"created_at":"2026-01-01T00:00:00Z","user_id":1}""")
        }
        val token = authorised(req) ?: return json("""{"detail":"Not authenticated"}""", HttpStatusCode.Unauthorized)
        val mine = scopes[token].orEmpty()
        when {
            path == "/api/users/me" -> return json("""{"username":"tester"}""")
            path == "/api/platforms" -> {
                val bySlug = games.groupBy { it.platformId to it.slug }
                return json(bySlug.entries.joinToString(",", "[", "]") { (k, v) -> """{"id":${k.first},"slug":"${k.second}","fs_slug":"${k.second}","name":"${k.second.uppercase()}","rom_count":${v.size}}""" })
            }
            path == "/api/roms/identifiers" -> return json(games.joinToString(",", "[", "]") { it.id.toString() })
            path == "/api/roms" && req.method == HttpMethod.Get -> {
                val offset = req.url.parameters["offset"]?.toInt() ?: 0
                val limit = req.url.parameters["limit"]?.toInt() ?: 50
                val after = req.url.parameters["updated_after"]
                val search = req.url.parameters["search_term"]
                val platform = req.url.parameters["platform_ids"]?.toLong()
                var list = games.sortedBy { it.id }
                if (after != null) list = list.filter { RommParse.millis(it.updated) > RommParse.millis(after) }
                if (search != null) list = list.filter { it.fsName.contains(search, ignoreCase = true) }
                if (platform != null) list = list.filter { it.platformId == platform }
                if (search == null && limit > slowAbove) throw io.ktor.client.plugins.HttpRequestTimeoutException(req.url.toString(), 1_000)
                if (search == null && offset >= dropFrom) throw java.io.IOException("Connection reset")
                // As RomM: a page of the library lists each game's files only when asked to.
                val withFiles = req.url.parameters["with_files"] == "true"
                val page = list.drop(offset).take(limit)
                return json("""{"items":[${page.joinToString(",") { romJson(it, withFiles) }}],"total":${list.size}}""")
            }
            path.startsWith("/api/roms/") && path.contains("/content/") -> {
                val id = path.removePrefix("/api/roms/").substringBefore('/').toLong()
                val fileId = req.url.parameters["file_ids"]?.toLong()
                val game = games.firstOrNull { it.id == id } ?: return respond("", HttpStatusCode.NotFound)
                val bytes = game.files.first { it.first == fileId }.third
                val range = req.headers[HttpHeaders.Range]
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                val body = bytes.copyOfRange(from, bytes.size)
                return respond(body, if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
            }
            path.matches(Regex("/api/roms/\\d+")) && req.method == HttpMethod.Get -> {
                val game = games.firstOrNull { it.id == path.removePrefix("/api/roms/").toLong() } ?: return respond("", HttpStatusCode.NotFound)
                return json(romJson(game))
            }
            path == "/api/firmware" -> return json(firmware.joinToString(",", "[", "]") { (id, name, b) -> """{"id":$id,"platform_id":1,"file_name":"$name","file_size_bytes":${b.size},"md5_hash":"${md5(b)}","sha1_hash":"","crc_hash":"","is_verified":true,"missing_from_fs":false}""" })
            path.startsWith("/api/firmware/") -> {
                val id = path.removePrefix("/api/firmware/").substringBefore('/').toLong()
                return respond(firmware.first { it.first == id }.third, HttpStatusCode.OK)
            }
            path == "/api/collections" -> return json("""[{"id":5,"name":"Favourites","rom_ids":[${games.firstOrNull()?.id ?: ""}]}]""")
            path == "/api/collections/smart" -> return json("[]")
            path == "/api/roms/upload/start" -> {
                if (RommScopes.ROMS_WRITE !in mine) return json("""{"detail":"Forbidden"}""", HttpStatusCode.Forbidden)
                val id = "up" + (uploads.size + 1)
                uploads[id] = ConcurrentHashMap()
                val body = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                val name = Regex("\"filename\":\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: req.headers["x-upload-filename"].orEmpty()
                val folder = Regex("\"folder\":\"([^\"]*)\"").find(body)?.groupValues?.get(1).orEmpty()
                uploadMeta[id] = Triple(name, req.headers["x-upload-platform"]!!.toLong(), folder)
                return json("""{"upload_id":"$id"}""", HttpStatusCode.Created)
            }
            path.startsWith("/api/roms/upload/") && path.endsWith("/complete") -> {
                val id = path.removePrefix("/api/roms/upload/").removeSuffix("/complete")
                // As RomM: the upload is claimed by the first answer, so asking again finds nothing.
                val parts = uploads.remove(id) ?: return json("""{"detail":"Upload session not found or expired"}""", HttpStatusCode.NotFound)
                val all = parts.toSortedMap().values.fold(ByteArray(0)) { a, b -> a + b }
                val (name, platform, folder) = uploadMeta[id]!!
                received += (if (folder.isEmpty()) name else "$folder/$name") to all
                if (folder.isEmpty()) {
                    val newId = (games.maxOfOrNull { it.id } ?: 0) + 1
                    games += Game(newId, platform, games.firstOrNull { it.platformId == platform }?.slug ?: "gba", name.substringBeforeLast('.'), name, listOf(Triple(newId * 100, name, all)))
                }
                if (slowComplete) throw io.ktor.client.plugins.HttpRequestTimeoutException(req.url.toString(), 1_000)
                return respond("", HttpStatusCode.Created)
            }
            path.startsWith("/api/roms/upload/") -> {
                if (slowChunks > 0) { slowChunks--; throw io.ktor.client.plugins.HttpRequestTimeoutException(req.url.toString(), 1_000) }
                val id = path.removePrefix("/api/roms/upload/")
                val index = req.headers["x-chunk-index"]!!.toInt()
                uploads[id]!![index] = (req.body as OutgoingContent.ByteArrayContent).bytes()
                return respond("", HttpStatusCode.OK)
            }
            path == "/api/tasks/scan" -> {
                if (RommScopes.TASKS_RUN !in mine) return json("""{"detail":"Forbidden"}""", HttpStatusCode.Forbidden)
                scans++
                return json("""{"task_name":"scan"}""", HttpStatusCode.Accepted)
            }
        }
        return json("""{"detail":"Not Found"}""", HttpStatusCode.NotFound)
    }
}
