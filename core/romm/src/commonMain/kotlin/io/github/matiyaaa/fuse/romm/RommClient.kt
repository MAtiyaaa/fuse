package io.github.matiyaaa.fuse.romm

import io.github.matiyaaa.fuse.integrations.net.NetRoute
import io.github.matiyaaa.fuse.integrations.net.RoutePicker
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Fuse's client for a RomM server. Every call goes through [routes] (Local, Remote or Auto: home
 * while it answers, outside otherwise, never waiting on a dead address), signs in with [credential],
 * and turns RomM's refusals into words for the person. Nothing here ever logs an address with a
 * token, a token or a password.
 */
class RommClient(
    private val http: HttpClient,
    val routes: RoutePicker,
    @kotlin.concurrent.Volatile var credential: RommCredential?,
    private val clientVersion: String = "",
) {
    @kotlin.concurrent.Volatile var capabilities: RommCapabilities = RommCapabilities()
        private set

    /** The address calls go to first right now (for downloads, which carry on by range if it changes). */
    fun base(): String = routes.preferred()?.second ?: throw RommException("Fuse RomM has no address for the server. Add one in Settings, Addons, Fuse RomM.", code = "no-address")

    /** Adds the sign-in to a request (downloads made outside this client use it too). */
    fun authorize(b: HttpRequestBuilder) {
        when (val c = credential) {
            is RommCredential.Token -> b.header(HttpHeaders.Authorization, "Bearer ${c.token.trim()}")
            is RommCredential.Password -> b.header(HttpHeaders.Authorization, "Basic " + base64("${c.username}:${c.password}"))
            null -> Unit
        }
    }

    /** Whether the signed-in token may [scope]; a password sign-in may do whatever its user may. */
    fun may(scope: String): Boolean = when (val c = credential) {
        is RommCredential.Token -> c.scopes.isEmpty() || scope in c.scopes
        is RommCredential.Password -> true
        null -> false
    }

    // ------------------------------------------------------------------ calls

    private suspend fun <T> call(
        method: HttpMethod,
        path: String,
        signed: Boolean = true,
        timeoutMs: Long? = null,
        extra: HttpRequestBuilder.() -> Unit = {},
        read: suspend (HttpResponse) -> T,
    ): T {
        val targets = routes.bases()
        if (targets.isEmpty()) throw RommException("Fuse RomM has no address for the server. Add one in Settings, Addons, Fuse RomM.", code = "no-address")
        var last: Exception? = null
        for ((route, base) in targets) {
            val resp = try {
                http.request(base + path) {
                    this.method = method
                    header(HttpHeaders.Accept, "application/json")
                    if (signed) authorize(this)
                    if (timeoutMs != null) timeout { requestTimeoutMillis = timeoutMs; socketTimeoutMillis = timeoutMs }
                    // Not `build`: that name is HttpRequestBuilder's own and would win.
                    extra()
                }
            } catch (e: Exception) {
                // A request that timed out once connected reached a server that is there but slow
                // (asking it another way would only wait as long again); only one that couldn't
                // connect at all means this way in is down.
                if (isSlow(e)) {
                    routes.answered(route)
                    throw RommException("RomM is taking too long to answer.", code = SLOW, cause = e)
                }
                if (e is CancellationException) throw e
                last = e
                continue
            }
            if (resp.status.value in 300..399) {
                throw RommException("The server sent Fuse to a sign-in page. If it is behind Cloudflare Access or a similar gate, let Fuse through it.", resp.status.value, "redirect")
            }
            routes.answered(route)
            if (!resp.status.isSuccess()) throw refusal(resp, path)
            // The server answered: whatever goes wrong reading the answer is never "not answering".
            return try {
                read(resp)
            } catch (e: Exception) {
                if (isSlow(e)) throw RommException("RomM took too long to send everything. Fuse tries again with less at a time.", code = SLOW, cause = e)
                if (e is CancellationException || e is RommException) throw e
                throw RommException("RomM sent an answer Fuse couldn't read.", code = "unreadable", cause = e)
            }
        }
        routes.lost()
        throw RommException("The RomM server didn't answer.", code = "offline", cause = last)
    }

    /** A request or read that ran out of time once connected (not one that couldn't connect). */
    private fun isSlow(e: Throwable): Boolean {
        // A caller's own withTimeout is a cancellation, never the server's doing.
        if (e is kotlinx.coroutines.TimeoutCancellationException) return false
        val name = e::class.simpleName.orEmpty()
        // Some engines report a connection that never opened as a plain socket timeout ("connect timed out").
        val connecting = "Connect" in name || e.message.orEmpty().contains("connect", ignoreCase = true)
        return "Timeout" in name && !connecting
    }

    private suspend fun json(method: HttpMethod, path: String, signed: Boolean = true, timeoutMs: Long? = null, extra: HttpRequestBuilder.() -> Unit = {}): JsonElement =
        call(method, path, signed, timeoutMs, extra) { RommParse.element(it.bodyAsText()) }

    private suspend fun refusal(resp: HttpResponse, path: String): RommException {
        val detail = runCatching { (RommParse.element(resp.bodyAsText()) as? JsonObject)?.get("detail")?.let { (it as? JsonPrimitive)?.contentOrNull } }.getOrNull()
        val s = resp.status.value
        val words = when {
            s == 401 -> "RomM didn't accept Fuse's sign-in. Sign in again in Settings, Addons, Fuse RomM."
            s == 403 -> "Your RomM sign-in isn't allowed to do this."
            s == 404 -> detail?.takeIf { it != "Not Found" } ?: "RomM doesn't have that."
            s == 409 -> detail ?: "RomM is already doing that."
            s == 429 -> "RomM asked Fuse to slow down. It tries again in a moment."
            s >= 500 -> "The RomM server had a problem ($s)."
            else -> detail ?: "RomM said no ($s)."
        }
        return RommException(words, s, path)
    }

    // ------------------------------------------------------------------ the server

    /** RomM's version, from `/api/heartbeat` (answers without signing in). */
    suspend fun heartbeat(): String = RommParse.version(json(HttpMethod.Get, "/api/heartbeat", signed = false))

    /**
     * What this server can do: its version, then the routes its OpenAPI document lists. A server
     * whose document can't be read falls back to what its version is known to have.
     */
    suspend fun detect(): RommCapabilities {
        val version = heartbeat()
        val paths = runCatching {
            call(HttpMethod.Get, "/openapi.json", signed = false) { resp -> openApiPaths(resp.bodyAsText()) }
        }.getOrNull()
        val major = RommCapabilities(version).major
        val caps = if (paths != null && paths.isNotEmpty()) {
            RommCapabilities(
                version = version,
                deviceAuth = "/api/auth/device/init" in paths,
                pairCodes = "/api/client-tokens/exchange" in paths,
                chunkedUploads = "/api/roms/upload/start" in paths,
                tokenScans = "/api/tasks/scan" in paths,
                incremental = true,
                identifiers = "/api/roms/identifiers" in paths,
            )
        } else {
            RommCapabilities(version = version, pairCodes = major >= 4, chunkedUploads = major >= 4, incremental = major >= 4, identifiers = major >= 4)
        }
        capabilities = caps
        return caps
    }

    /** Who Fuse is signed in as (also proves the sign-in works). */
    suspend fun me(): String {
        val o = json(HttpMethod.Get, "/api/users/me").jsonObject
        return (o["username"] as? JsonPrimitive)?.contentOrNull ?: ""
    }

    // ------------------------------------------------------------------ signing in

    /**
     * Starts the device pairing: RomM gives a short code for the person to approve in its web
     * interface (or by scanning the QR), with only [scopes].
     */
    suspend fun startPairing(deviceId: String, deviceName: String, platform: String, scopes: List<String>): RommDeviceCode {
        val o = json(HttpMethod.Post, "/api/auth/device/init", signed = false) {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("client_device_identifier", deviceId)
                    put("name", deviceName.take(255))
                    put("client", "Fuse")
                    put("platform", platform.take(50))
                    put("client_version", clientVersion.take(50))
                    putJsonArray("requested_scopes") { scopes.forEach { add(JsonPrimitive(it)) } }
                }.toString(),
            )
        }.jsonObject
        val user = (o["user_code"] as? JsonPrimitive)?.contentOrNull ?: throw RommException("RomM didn't start the pairing.")
        val path = (o["verification_path_complete"] as? JsonPrimitive)?.contentOrNull ?: "/pair/device?user_code=$user"
        return RommDeviceCode(
            deviceCode = (o["device_code"] as? JsonPrimitive)?.contentOrNull ?: throw RommException("RomM didn't start the pairing."),
            userCode = user,
            verificationUrl = base() + path,
            expiresInSeconds = (o["expires_in"] as? JsonPrimitive)?.intOrNull ?: 600,
            intervalSeconds = (o["interval"] as? JsonPrimitive)?.intOrNull ?: 5,
        )
    }

    /** Asks once whether the pairing was approved. */
    suspend fun pollPairing(code: RommDeviceCode): PairingState = try {
        val o = json(HttpMethod.Post, "/api/auth/device/token", signed = false) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("device_code", code.deviceCode) }.toString())
        }.jsonObject
        val token = (o["access_token"] as? JsonPrimitive)?.contentOrNull ?: throw RommException("RomM approved the pairing but sent no token.")
        PairingState.Approved(
            RommCredential.Token(
                token = token,
                scopes = (o["scopes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                deviceId = (o["device_id"] as? JsonPrimitive)?.contentOrNull,
            ),
        )
    } catch (e: RommException) {
        when {
            e.status == 400 && "authorization_pending" in e.message.orEmpty() -> PairingState.Waiting
            e.status == 400 && "slow_down" in e.message.orEmpty() -> PairingState.SlowDown
            e.status == 400 && "access_denied" in e.message.orEmpty() -> PairingState.Denied
            e.status == 400 && "expired_token" in e.message.orEmpty() -> PairingState.Expired
            else -> throw e
        }
    }

    /** Exchanges a pairing code made in RomM's web interface (API Tokens, Pair) for that token. */
    suspend fun exchangeCode(code: String): RommCredential.Token {
        val o = json(HttpMethod.Post, "/api/client-tokens/exchange", signed = false) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("code", code.filter { it.isLetterOrDigit() }.uppercase()) }.toString())
        }.jsonObject
        val raw = (o["raw_token"] as? JsonPrimitive)?.contentOrNull ?: throw RommException("RomM didn't send the token for that code.")
        return RommCredential.Token(raw, (o["scopes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty())
    }

    // ------------------------------------------------------------------ the library

    suspend fun platforms(): List<RommPlatform> = RommParse.platforms(json(HttpMethod.Get, "/api/platforms"))

    /**
     * One page of games, [limit] at a time from [offset]. [updatedAfter] (epoch millis) brings only
     * what changed since, where the server can. [withFiles] adds every game's file list: a PS4 or PS5
     * game kept as a folder can list tens of thousands, so the library is read without them and a
     * game's files are brought when it is opened or downloaded ([rom]).
     */
    suspend fun roms(offset: Int, limit: Int, platformId: Long? = null, updatedAfter: Long? = null, withFiles: Boolean = false): RommPage =
        RommParse.page(
            json(HttpMethod.Get, "/api/roms", timeoutMs = PAGE_TIMEOUT_MS) {
                parameter("offset", offset)
                parameter("limit", limit)
                parameter("order_by", "id")
                parameter("order_dir", "asc")
                parameter("with_files", withFiles)
                parameter("with_char_index", false)
                parameter("with_filter_values", false)
                parameter("with_rom_id_index", false)
                if (platformId != null) {
                    parameter("platform_ids", platformId)
                    parameter("platform_id", platformId)
                }
                if (updatedAfter != null && capabilities.incremental) parameter("updated_after", RommParse.iso(updatedAfter))
            },
        )

    /** The game on [platformId] whose file is [fileName] exactly, if RomM has one. */
    suspend fun findByFileName(platformId: Long, fileName: String): RommRom? {
        val page = RommParse.page(
            json(HttpMethod.Get, "/api/roms") {
                parameter("platform_ids", platformId)
                parameter("platform_id", platformId)
                parameter("search_term", fileName.substringBeforeLast('.'))
                parameter("limit", 50)
                parameter("with_char_index", false)
                parameter("with_filter_values", false)
                parameter("with_rom_id_index", false)
            },
        )
        return page.items.firstOrNull { it.fsName == fileName || it.files.any { f -> f.name == fileName && f.path.isEmpty() } }
    }

    /** One game with every file it has. */
    suspend fun rom(id: Long): RommRom? = RommParse.rom(json(HttpMethod.Get, "/api/roms/$id", timeoutMs = PAGE_TIMEOUT_MS))

    /** Every game's id on the server, for noticing ones that went. */
    suspend fun romIds(): List<Long> = RommParse.identifiers(json(HttpMethod.Get, "/api/roms/identifiers"))

    suspend fun firmware(): List<RommFirmware> = RommParse.firmware(json(HttpMethod.Get, "/api/firmware"))

    suspend fun collections(): List<RommCollection> {
        val own = runCatching { RommParse.collections(json(HttpMethod.Get, "/api/collections"), smart = false) }.getOrDefault(emptyList())
        val smart = runCatching { RommParse.collections(json(HttpMethod.Get, "/api/collections/smart"), smart = true) }.getOrDefault(emptyList())
        return own + smart
    }

    /** Where a game's file downloads from: one file at a time, so each can carry on by range. */
    fun fileUrl(base: String, rom: Long, file: RommFile): String =
        "$base/api/roms/$rom/content/${file.name.encodeURLPathPart()}?file_ids=${file.id}"

    fun firmwareUrl(base: String, firmware: RommFirmware): String =
        "$base/api/firmware/${firmware.id}/content/${firmware.fileName.encodeURLPathPart()}"

    /** A picture RomM keeps ([path] as RomM gives it), at the address in use. */
    fun assetUrl(base: String, path: String): String = if (path.startsWith("http")) path else base + (if (path.startsWith("/")) path else "/$path")

    // ------------------------------------------------------------------ uploads

    /**
     * Opens an upload of one file to [platformId]. With [romId] and [folder] it goes inside that game
     * (an update or DLC beside it). Returns RomM's id for the upload.
     */
    suspend fun startUpload(platformId: Long, fileName: String, size: Long, chunks: Int, romId: Long? = null, folder: String = ""): String {
        val o = json(HttpMethod.Post, "/api/roms/upload/start") {
            header("x-upload-platform", platformId.toString())
            // A header carries only Latin-1: the real name goes in the body, which RomM prefers.
            header("x-upload-filename", fileName.map { if (it.code in 32..255) it else '_' }.joinToString(""))
            header("x-upload-total-size", size.toString())
            header("x-upload-total-chunks", chunks.toString())
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                if (romId != null) put("rom_id", romId)
                put("folder", folder)
                put("filename", fileName)
            }.toString())
        }.jsonObject
        return (o["upload_id"] as? JsonPrimitive)?.contentOrNull ?: throw RommException("RomM didn't open the upload.")
    }

    suspend fun uploadChunk(uploadId: String, index: Int, bytes: ByteArray) {
        call(HttpMethod.Put, "/api/roms/upload/$uploadId", extra = {
            header("x-chunk-index", index.toString())
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }) { }
    }

    suspend fun completeUpload(uploadId: String) {
        call(HttpMethod.Post, "/api/roms/upload/$uploadId/complete") { }
    }

    suspend fun cancelUpload(uploadId: String) {
        runCatching { call(HttpMethod.Post, "/api/roms/upload/$uploadId/cancel") { } }
    }

    /** Asks RomM to look at its folders for new games (needs the `tasks.run` scope). */
    suspend fun scan(platformIds: List<Long> = emptyList()) {
        call(HttpMethod.Post, "/api/tasks/scan", extra = {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("platforms") { platformIds.forEach { add(JsonPrimitive(it)) } }
                put("type", "quick")
            }.toString())
        }) { }
    }

    /** Which route the last call used. */
    val route: NetRoute? get() = routes.route.value

    companion object {
        /** A call that reached the server but ran out of time: the server is there, just slow. */
        const val SLOW = "slow"

        /** How long a page of the library (or one large game) may take: big libraries answer slowly. */
        const val PAGE_TIMEOUT_MS = 120_000L

        /** RomM's chunk size for uploads: well under its 64 MB limit, small enough to retry cheaply. */
        const val CHUNK_BYTES = 8 * 1024 * 1024

        /** The paths in an OpenAPI document, without holding the rest of it. */
        internal fun openApiPaths(text: String): Set<String> {
            val root = RommParse.element(text) as? JsonObject ?: return emptySet()
            return (root["paths"] as? JsonObject)?.keys.orEmpty()
        }

        private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        internal fun base64(text: String): String {
            val b = text.encodeToByteArray()
            val out = StringBuilder()
            var i = 0
            while (i < b.size) {
                val n = (b[i].toInt() and 0xFF shl 16) or ((if (i + 1 < b.size) b[i + 1].toInt() and 0xFF else 0) shl 8) or (if (i + 2 < b.size) b[i + 2].toInt() and 0xFF else 0)
                out.append(B64[n shr 18 and 63]).append(B64[n shr 12 and 63])
                out.append(if (i + 1 < b.size) B64[n shr 6 and 63] else '=')
                out.append(if (i + 2 < b.size) B64[n and 63] else '=')
                i += 3
            }
            return out.toString()
        }
    }
}
