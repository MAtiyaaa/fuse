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
import io.ktor.client.statement.bodyAsBytes
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
    /** A Cloudflare Access service token in front of the server, sent only to its own host ([RommAccess.covers]). */
    @kotlin.concurrent.Volatile var access: RommAccess? = null,
) {
    @kotlin.concurrent.Volatile var capabilities: RommCapabilities = RommCapabilities()
        private set

    /** The address calls go to first right now (for downloads, which carry on by range if it changes). */
    fun base(): String = routes.preferred()?.second ?: throw RommException("RomM has no address for the server. Add one in Settings, Addons, RomM.", code = "no-address")

    /** Adds the sign-in to a request, and the Cloudflare Access token where it goes (downloads made outside this client use it too). */
    fun authorize(b: HttpRequestBuilder) {
        when (val c = credential) {
            is RommCredential.Token -> b.header(HttpHeaders.Authorization, "Bearer ${c.token.trim()}")
            is RommCredential.Password -> b.header(HttpHeaders.Authorization, "Basic " + base64("${c.username}:${c.password}"))
            null -> Unit
        }
        gate(access, b)
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
        if (targets.isEmpty()) throw RommException("RomM has no address for the server. Add one in Settings, Addons, RomM.", code = "no-address")
        var last: Exception? = null
        for ((route, base) in targets) {
            val resp = try {
                http.request(base + path) {
                    this.method = method
                    header(HttpHeaders.Accept, "application/json")
                    if (signed) authorize(this) else gate(access, this)
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
            s == 401 -> "RomM didn't accept Fuse's sign-in. Sign in again in Settings, Addons, RomM."
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
                tokenScans = "/api/tasks/scan" in paths || "/api/tasks/run/{task_name}" in paths,
                scanTask = "/api/tasks/scan" !in paths && "/api/tasks/run/{task_name}" in paths,
                incremental = true,
                identifiers = "/api/roms/identifiers" in paths,
                saveSync = "/api/devices" in paths && "/api/saves/{id}/downloaded" in paths,
                userProps = "/api/roms/{id}/props" in paths,
                collectionRoms = "/api/collections/{id}/roms" in paths,
                playSessions = "/api/play-sessions" in paths,
                taskStatus = "/api/tasks/{task_id}" in paths,
                notes = "/api/roms/{id}/notes" in paths && "/api/roms/{id}/notes/{note_id}" in paths,
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
        // A file RomM didn't list (id 0): the whole game, as RomM serves it by its own name.
        if (file.id <= 0) "$base/api/roms/$rom/content/${file.name.encodeURLPathPart()}"
        else "$base/api/roms/$rom/content/${file.name.encodeURLPathPart()}?file_ids=${file.id}"

    fun firmwareUrl(base: String, firmware: RommFirmware): String =
        "$base/api/firmware/${firmware.id}/content/${firmware.fileName.encodeURLPathPart()}"

    /** A picture RomM keeps ([path] as RomM gives it), at the address in use. */
    fun assetUrl(base: String, path: String): String =
        (if (path.startsWith("http")) path else base + (if (path.startsWith("/")) path else "/$path")).let(::escapeUrl)

    // ------------------------------------------------------------------ saves

    /**
     * This device, registered with RomM for its save sync (RomM shows which device has which save).
     * Registering again with the same name and kind gives the same device back.
     */
    suspend fun registerDevice(name: String, platform: String, clientVersion: String): String {
        val o = json(HttpMethod.Post, "/api/devices") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", name)
                put("platform", platform)
                put("client", "Fuse")
                put("client_version", clientVersion)
                put("allow_existing", true)
            }.toString())
        }.jsonObject
        return (o["device_id"] as? JsonPrimitive)?.contentOrNull ?: throw RommException("RomM didn't give this device an id.", code = "unreadable")
    }

    /**
     * The signed-in person's saves of [romId] on RomM, newest first, each with the name of the device
     * that made it (from RomM's list of devices, when its own answer doesn't say).
     */
    suspend fun saves(romId: Long): List<RommSave> {
        val saves = RommParse.saves(json(HttpMethod.Get, "/api/saves") { parameter("rom_id", romId) }).sortedByDescending { it.updatedAt }
        if (saves.none { it.originDevice != null && it.originDeviceName == null }) return saves
        val names = runCatching { RommParse.deviceNames(json(HttpMethod.Get, "/api/devices")) }.getOrDefault(emptyMap())
        return saves.map { s -> if (s.originDeviceName == null) s.copy(originDeviceName = s.originDevice?.let(names::get)) else s }
    }

    /** Every save of the signed-in person on RomM, newest first, with the device each came from. */
    suspend fun allSaves(): List<RommSave> {
        val saves = RommParse.saves(json(HttpMethod.Get, "/api/saves")).sortedByDescending { it.updatedAt }
        if (saves.none { it.originDevice != null && it.originDeviceName == null }) return saves
        val names = runCatching { RommParse.deviceNames(json(HttpMethod.Get, "/api/devices")) }.getOrDefault(emptyMap())
        return saves.map { s -> if (s.originDeviceName == null) s.copy(originDeviceName = s.originDevice?.let(names::get)) else s }
    }

    /** [save]'s file. */
    suspend fun saveContent(save: RommSave, deviceId: String?): ByteArray =
        call(HttpMethod.Get, "/api/saves/${save.id}/content", timeoutMs = SAVE_TIMEOUT_MS, extra = {
            if (deviceId != null) parameter("device_id", deviceId)
            parameter("optimistic", false)
        }) { it.bodyAsBytes() }

    /** Tells RomM [save] is now on this device (so it shows as current here). */
    suspend fun confirmSave(save: RommSave, deviceId: String) {
        call(HttpMethod.Post, "/api/saves/${save.id}/downloaded", extra = {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("device_id", deviceId) }.toString())
        }) { }
    }

    /**
     * Sends [bytes] as a new save of [romId] in [slot] (RomM keeps the earlier ones, up to
     * [keep], as history), named [fileName], made by [emulator] on [deviceId].
     */
    suspend fun uploadSave(romId: Long, slot: String, emulator: String?, deviceId: String?, fileName: String, bytes: ByteArray, keep: Int = 10): RommSave {
        val e = call(HttpMethod.Post, "/api/saves", timeoutMs = SAVE_TIMEOUT_MS, extra = {
            parameter("rom_id", romId)
            parameter("slot", slot)
            if (emulator != null) parameter("emulator", emulator)
            if (deviceId != null) parameter("device_id", deviceId)
            parameter("autocleanup", true)
            parameter("autocleanup_limit", keep)
            setBody(io.ktor.client.request.forms.MultiPartFormDataContent(io.ktor.client.request.forms.formData {
                append("saveFile", bytes, io.ktor.http.Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                })
            }))
        }) { RommParse.element(it.bodyAsText()) }
        return RommParse.save(e) ?: throw RommException("RomM didn't say what it kept.", code = "unreadable")
    }

    // ------------------------------------------------------------------ save states

    /** The signed-in person's save states of [romId] on RomM, newest first. */
    suspend fun states(romId: Long): List<RommState> =
        RommParse.states(json(HttpMethod.Get, "/api/states") { parameter("rom_id", romId) }).sortedWith(compareByDescending<RommState> { it.updatedAt }.thenByDescending { it.id })

    /** [state]'s file. */
    suspend fun stateContent(state: RommState): ByteArray =
        call(HttpMethod.Get, "/api/states/${state.id}/content", timeoutMs = SAVE_TIMEOUT_MS) { it.bodyAsBytes() }

    /** Sends [bytes] as a save state of [romId], named [fileName], made by [emulator]. */
    suspend fun uploadState(romId: Long, emulator: String?, fileName: String, bytes: ByteArray): RommState {
        val e = call(HttpMethod.Post, "/api/states", timeoutMs = SAVE_TIMEOUT_MS, extra = {
            parameter("rom_id", romId)
            if (emulator != null) parameter("emulator", emulator)
            setBody(io.ktor.client.request.forms.MultiPartFormDataContent(io.ktor.client.request.forms.formData {
                append("stateFile", bytes, io.ktor.http.Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                })
            }))
        }) { RommParse.element(it.bodyAsText()) }
        return RommParse.state(e) ?: throw RommException("RomM didn't say what it kept.", code = "unreadable")
    }

    /** Takes the save states [ids] off RomM (a slot's older copy, once a newer one is there). */
    suspend fun deleteStates(ids: List<Long>) {
        if (ids.isEmpty()) return
        call(HttpMethod.Post, "/api/states/delete", extra = {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("states", kotlinx.serialization.json.JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) })) }.toString())
        }) { }
    }

    // ------------------------------------------------------------------ uploads

    /**
     * Opens an upload of one file to [platformId]. With [romId] and [folder] it goes inside that game
     * (an update or DLC beside it). Returns RomM's id for the upload.
     */
    suspend fun startUpload(platformId: Long, fileName: String, size: Long, chunks: Int, romId: Long? = null, folder: String = ""): String {
        val o = json(HttpMethod.Post, "/api/roms/upload/start", timeoutMs = START_TIMEOUT_MS) {
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

    /** Sends one piece of an upload, given time for its size on a slow connection (a home's upload speed from outside). */
    suspend fun uploadChunk(uploadId: String, index: Int, bytes: ByteArray) {
        call(HttpMethod.Put, "/api/roms/upload/$uploadId", timeoutMs = chunkTimeout(bytes.size), extra = {
            header("x-chunk-index", index.toString())
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }) { }
    }

    /**
     * Finishes an upload: RomM puts the pieces together into the game's file before it answers,
     * which for a large game takes minutes, so this waits that long.
     */
    suspend fun completeUpload(uploadId: String) {
        call(HttpMethod.Post, "/api/roms/upload/$uploadId/complete", timeoutMs = COMPLETE_TIMEOUT_MS) { }
    }

    suspend fun cancelUpload(uploadId: String) {
        runCatching { call(HttpMethod.Post, "/api/roms/upload/$uploadId/cancel") { } }
    }

    /**
     * Asks RomM to look at its folders for new games (needs the `tasks.run` scope). Returns the
     * scan's task, to follow with [task], when RomM names it.
     */
    suspend fun scan(platformIds: List<Long> = emptyList()): String? {
        // RomM 5.3 runs its library scan as a task: a quick scan of every system, which only takes
        // in files it hasn't got yet (the task takes no arguments, so the systems can't be named).
        val answer = if (capabilities.scanTask) {
            json(HttpMethod.Post, "/api/tasks/run/scan_library")
        } else {
            json(HttpMethod.Post, "/api/tasks/scan") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    putJsonArray("platforms") { platformIds.forEach { add(JsonPrimitive(it)) } }
                    put("type", "quick")
                }.toString())
            }
        }
        return RommParse.task(answer)?.id
    }

    /** Where task [id] is (a scan's counts so far among it), from `GET /api/tasks/{task_id}`. */
    suspend fun task(id: String): RommTask =
        RommParse.task(json(HttpMethod.Get, "/api/tasks/${id.encodeURLPathPart()}")) ?: throw RommException("RomM didn't say where the scan is.", code = "unreadable")

    // ------------------------------------------------------------------ the person's changes

    /** [romId] hidden from the signed-in person's library, or shown again (`PUT /api/roms/{id}/props`, only this changed). */
    suspend fun setHidden(romId: Long, hidden: Boolean) {
        call(HttpMethod.Put, "/api/roms/$romId/props", extra = {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("hidden", hidden) }.toString())
        }) { }
    }

    /**
     * [romId]'s status and rating as [marks] say, for the signed-in person (`PUT /api/roms/{id}/props`,
     * only these changed): RomM's status with its backlog and now-playing flags, and the rating.
     */
    suspend fun setMarks(romId: Long, marks: RommMarks) {
        call(HttpMethod.Put, "/api/roms/$romId/props", extra = {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("status", marks.status)
                put("backlogged", marks.backlogged)
                put("now_playing", marks.nowPlaying)
                put("rating", marks.rating)
            }.toString())
        }) { }
    }

    /**
     * The signed-in person's favourites collection on RomM (`is_favorite`, the heart in RomM's own
     * pages), made the first time it is needed when they have none.
     */
    suspend fun favourites(): Long {
        suspend fun own() = RommParse.collections(json(HttpMethod.Get, "/api/collections"), smart = false).firstOrNull { it.favorite }?.id?.removePrefix("user-")?.toLongOrNull()
        own()?.let { return it }
        val made = runCatching {
            json(HttpMethod.Post, "/api/collections") {
                parameter("is_favorite", true)
                parameter("is_public", false)
                setBody(io.ktor.client.request.forms.MultiPartFormDataContent(io.ktor.client.request.forms.formData { append("name", FAVOURITES_NAME) }))
            }
        }
        // Made meanwhile (another device, or RomM's own pages), or a collection of that name already there.
        made.getOrNull()?.let { (it as? JsonObject)?.get("id") as? JsonPrimitive }?.contentOrNull?.toLongOrNull()?.let { return it }
        return own() ?: throw (made.exceptionOrNull() ?: RommException("RomM didn't make a favourites collection.", code = "unreadable"))
    }

    /** [romIds] put in the person's collection [collectionId] ([into]), or taken out of it, leaving the rest as it is. */
    suspend fun collectionRoms(collectionId: Long, romIds: List<Long>, into: Boolean) {
        if (romIds.isEmpty()) return
        call(if (into) HttpMethod.Post else HttpMethod.Delete, "/api/collections/$collectionId/roms", extra = {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { putJsonArray("rom_ids") { romIds.forEach { add(JsonPrimitive(it)) } } }.toString())
        }) { }
    }

    /**
     * Play sessions for the signed-in person (`POST /api/play-sessions`, at most [MAX_SESSIONS] at a
     * time): each game's id, when it started and ended. RomM keeps one of each (one sent twice is
     * skipped). Returns how many it took.
     */
    suspend fun playSessions(sessions: List<RommPlayed>, deviceId: String? = null): Int {
        if (sessions.isEmpty()) return 0
        require(sessions.size <= MAX_SESSIONS) { "At most $MAX_SESSIONS sessions at a time." }
        val e = json(HttpMethod.Post, "/api/play-sessions") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                if (deviceId != null) put("device_id", deviceId)
                putJsonArray("sessions") {
                    for (s in sessions) add(buildJsonObject {
                        put("rom_id", s.romId)
                        put("start_time", RommParse.iso(s.startedAt))
                        put("end_time", RommParse.iso(s.endedAt))
                        put("duration_ms", s.endedAt - s.startedAt)
                    })
                }
            }.toString())
        }
        return RommParse.sessionsCreated(e)
    }

    /** [romId]'s notes: the signed-in person's own and others' public ones (`GET /api/roms/{id}/notes`). */
    suspend fun notes(romId: Long): List<RommNote> = RommParse.notes(json(HttpMethod.Get, "/api/roms/$romId/notes"))

    /**
     * A note of the signed-in person's own on [romId] (`POST /api/roms/{id}/notes`), kept to them
     * (`is_public` false). RomM keeps one note of each title per person and game.
     */
    suspend fun addNote(romId: Long, title: String, content: String, tags: List<String> = emptyList()): RommNote {
        val e = json(HttpMethod.Post, "/api/roms/$romId/notes") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("title", title)
                put("content", content)
                put("is_public", false)
                putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
            }.toString())
        }
        return RommParse.note(e) ?: throw RommException("RomM didn't say which note it made.", code = "unreadable")
    }

    /** The text of the person's note [noteId] on [romId], nothing else of it changed (`PUT /api/roms/{id}/notes/{note_id}`). */
    suspend fun updateNote(romId: Long, noteId: Long, content: String) {
        call(HttpMethod.Put, "/api/roms/$romId/notes/$noteId", extra = {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("content", content) }.toString())
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

        /** How long opening an upload may take. */
        const val START_TIMEOUT_MS = 120_000L

        /** How long RomM may take to put a finished upload together (a large game's file is written whole). */
        const val COMPLETE_TIMEOUT_MS = 30 * 60_000L

        /** The slowest connection a piece of an upload is given time for: 16 KB a second. */
        private const val SLOWEST_UPLOAD_BYTES_PER_SECOND = 16 * 1024L

        /** Time for sending [bytes] at the slowest upload speed Fuse allows for, never under two minutes. */
        /** How long a save's upload or download may take: saves are small. */
        const val SAVE_TIMEOUT_MS = 60_000L

        /** The most play sessions RomM takes in one request (`MAX_BATCH_SIZE` in its play_sessions.py). */
        const val MAX_SESSIONS = 100

        /** What Fuse names the person's favourites collection when it makes one (RomM finds it by `is_favorite`, not its name). */
        const val FAVOURITES_NAME = "Favourites"

        /** [access]'s token on [b], when [b] goes to the host it is for ([RommAccess.covers]); nothing otherwise. */
        fun gate(access: RommAccess?, b: HttpRequestBuilder) {
            val a = access ?: return
            if (!a.covers(b.url.buildString())) return
            b.header(RommAccess.ID_HEADER, a.clientId)
            b.header(RommAccess.SECRET_HEADER, a.clientSecret)
        }

        fun chunkTimeout(bytes: Int): Long = maxOf(120_000L, bytes * 1000L / SLOWEST_UPLOAD_BYTES_PER_SECOND)

        /** The paths in an OpenAPI document, without holding the rest of it. */
        /**
         * [url] with the characters a request line can't carry percent-encoded. RomM gives its
         * pictures' paths with a time in them (`big.png?ts=2026-10-04 08:45:01`); sent as they are,
         * the space makes the request malformed and the server answers 400.
         */
        internal fun escapeUrl(url: String): String = buildString(url.length) {
            for (ch in url) {
                when {
                    ch == ' ' -> append("%20")
                    ch == '"' || ch == '<' || ch == '>' || ch == '`' || ch == '{' || ch == '}' || ch == '|' || ch == '\\' || ch == '^' || ch.code < 0x20 ->
                        append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
                    else -> append(ch)
                }
            }
        }

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
