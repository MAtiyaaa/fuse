package io.github.matiyaaa.fuse.link

import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.ui.shell.app.RemoteInput
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkControl
import io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkState
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Phone Link: a small web server (Ktor, CIO) for a phone on the same network, serving the phone web
 * app and its API (docs/PHONE_LINK.md). It runs while Phone Link is on in Settings.
 *
 * Every request must come from the local network and name the device by address; everything but
 * the app itself, the session check, sign-in and a download link a signed-in phone asked for needs
 * a signed-in phone. There are no routes that delete anything, show keys or passwords, or change
 * settings beyond fixing a game's name, details and art and starting art fills. Screenshots and
 * recordings can be looked at and downloaded, never changed.
 */
class PhoneLinkServer(
    private val store: FuseStore,
    secrets: SecretStore,
    private val scope: CoroutineScope,
    private val deviceName: String,
    private val version: String,
    /** Reads art that isn't a plain file (Android content URIs); null where there is none. */
    private val readUri: (suspend (String) -> ByteArray?)? = null,
    /** The device's screenshots and recordings; null where Fuse takes none (the desktop for now). */
    captures: LinkCaptures? = null,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : PhoneLinkControl {
    internal val auth = LinkAuth(secrets, clock)
    internal val api = LinkApi(store, auth, deviceName, version, readUri, capturesAvailable = captures != null)
    private val share = captures?.let { CaptureShare(it, clock) }
    private val stateFlow = MutableStateFlow(PhoneLinkState())
    override val state: StateFlow<PhoneLinkState> = stateFlow
    private val lifecycle = Mutex()
    private var server: EmbeddedServer<*, *>? = null
    private var port: Int? = null
    private var watcher: Job? = null

    /** Follows the Phone Link switch in Settings for as long as [scope] lives. */
    fun start() {
        watcher?.cancel()
        watcher = scope.launch {
            // Addresses change when the device joins another network.
            launch {
                while (true) {
                    delay(15_000)
                    if (server != null) refreshState()
                }
            }
            refreshState()
            store.prefs.map { it.phoneLinkEnabled }.distinctUntilChanged().collect { on ->
                if (on) startServer() else stopServer()
            }
        }
    }

    /** Stops following the switch and closes the server (the app is quitting). */
    fun close() {
        watcher?.cancel()
        watcher = null
        val s = server ?: return
        server = null
        port = null
        runCatching { s.stop(100, 500) }
    }

    override suspend fun setAccount(username: String, password: String): Result<Unit> = runCatching {
        auth.setAccount(username, password)
        refreshState()
    }

    override suspend fun signOutAll() {
        auth.signOutAll()
        refreshState()
    }

    override fun qr(text: String): List<BooleanArray>? = qrModules(text)

    override suspend fun pairingLink(address: String?): String? {
        if (server == null) return null
        val base = address ?: stateFlow.value.addresses.firstOrNull() ?: return null
        return base.trimEnd('/') + "/pair/" + auth.issuePairing()
    }

    /** The port the server listens on while running. */
    fun boundPort(): Int? = port

    private suspend fun refreshState(error: String? = stateFlow.value.error) {
        val p = port
        stateFlow.value = PhoneLinkState(
            running = server != null,
            addresses = if (p == null) emptyList() else lanAddresses().map { "http://$it:$p/" },
            username = auth.username(),
            sessions = auth.sessionCount(),
            error = error,
        )
    }

    private suspend fun startServer() = lifecycle.withLock {
        if (server != null) return@withLock
        var lastError: Throwable? = null
        // The usual port, or the next free one (Cartridge's own remote uses 47280 and 47281).
        for (candidate in PORT until PORT + 10) {
            try {
                // Starting blocks its thread until the server is bound, and stopping can block for a
                // second: both on the threads made for blocking, never the shared computation ones
                // the server's own start-up (and everything else in Fuse) runs on.
                val s = withContext(ioDispatcher) { launchServer(candidate) }
                server = s
                port = candidate
                refreshState(error = null)
                return@withLock
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        refreshState(error = "Phone Link couldn't start: no free port near $PORT (${lastError?.message ?: "unknown"})")
    }

    /**
     * Binds and starts the server. Kept out of any coroutine scope on purpose: inside one,
     * `embeddedServer` would make the server a child job that the caller then waits on forever.
     */
    private fun launchServer(port: Int): EmbeddedServer<*, *> =
        embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.also { it.start(wait = false) }

    private suspend fun stopServer() = lifecycle.withLock {
        val s = server ?: return@withLock
        server = null
        port = null
        withContext(ioDispatcher) { runCatching { s.stop(200, 1_000) } }
        refreshState(error = null)
    }

    private fun Application.module() {
        intercept(ApplicationCallPipeline.Plugins) {
            // Local network only, and only by address.
            val remote = call.request.origin.remoteAddress
            if (!LinkNetwork.isLocal(remote) || !LinkNetwork.isAddressHost(call.request.headers[HttpHeaders.Host])) {
                call.respondText("{\"error\":\"Phone Link only answers phones on the same network.\"}", ContentType.Application.Json, HttpStatusCode.Forbidden)
                finish()
                return@intercept
            }
            // Changes only from the app's own pages.
            val method = call.request.httpMethod
            if (method != HttpMethod.Get && method != HttpMethod.Head) {
                val origin = call.request.headers[HttpHeaders.Origin]
                val host = call.request.headers[HttpHeaders.Host]
                if (origin != null && origin.substringAfter("://").trimEnd('/') != host) {
                    call.respondText("{\"error\":\"Not allowed from another page.\"}", ContentType.Application.Json, HttpStatusCode.Forbidden)
                    finish()
                    return@intercept
                }
                if ((call.request.contentLength() ?: 0) > MAX_BODY) {
                    call.respondText("{\"error\":\"Too large.\"}", ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
                    finish()
                    return@intercept
                }
            }
            securityHeaders(call)
        }
        routing {
            for (file in WebAssets.files.keys) {
                get(file) { call.serveAsset(file) }
            }
            get("/") { call.serveAsset("/index.html") }

            get("/api/session") { call.json(api.session(call.token())) }
            // The code beside the on-screen keyboard: signs the phone in and opens its Remote.
            get("/pair/{code}") {
                val token = auth.redeemPairing(call.parameters["code"])
                if (token != null) {
                    call.response.header(HttpHeaders.SetCookie, "$COOKIE=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=${60 * 60 * 24 * 180}")
                    scope.launch { refreshState() }
                }
                call.response.header(HttpHeaders.Location, if (token != null) "/#remote" else "/?pair=expired")
                call.respondText("", ContentType.Text.Plain, HttpStatusCode.SeeOther)
            }
            post("/api/login") {
                val result = api.login(call.body())
                when (result) {
                    is LinkApi.Login.Ok -> {
                        call.response.header(HttpHeaders.SetCookie, "$COOKIE=${result.token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${60 * 60 * 24 * 180}")
                        call.json(buildJsonObject { put("ok", true) })
                        scope.launch { refreshState() }
                    }
                    is LinkApi.Login.Failed -> {
                        result.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
                        call.json(result.body, result.status)
                    }
                }
            }
            post("/api/logout") {
                auth.logout(call.token())
                call.response.header(HttpHeaders.SetCookie, "$COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
                call.json(buildJsonObject { put("ok", true) })
                scope.launch { refreshState() }
            }

            get("/api/now") { call.signedIn { call.json(api.now()) } }
            get("/api/systems") { call.signedIn { call.json(api.systems()) } }
            get("/api/games") {
                call.signedIn {
                    val q = call.request.queryParameters
                    call.json(api.games(q["query"].orEmpty(), q["system"].orEmpty(), q["sort"].orEmpty(), q["offset"]?.toIntOrNull() ?: 0, q["limit"]?.toIntOrNull() ?: 60))
                }
            }
            get("/api/games/{id}") { call.signedIn { call.reply(api.game(call.gameId())) } }
            put("/api/games/{id}/search-as") { call.signedIn { call.reply(api.searchAs(call.gameId(), call.body())) } }
            get("/api/games/{id}/identify") { call.signedIn { call.reply(api.identify(call.gameId())) } }
            post("/api/games/{id}/identify") { call.signedIn { call.reply(api.accept(call.gameId(), call.body())) } }
            get("/api/games/{id}/art/{kind}") { call.signedIn { call.reply(api.artOptions(call.gameId(), call.parameters["kind"].orEmpty())) } }
            post("/api/games/{id}/art/{kind}") { call.signedIn { call.reply(api.applyArt(call.gameId(), call.parameters["kind"].orEmpty(), call.body())) } }
            post("/api/games/{id}/fill") { call.signedIn { call.reply(api.fillGame(call.gameId())) } }
            post("/api/fill") { call.signedIn { call.reply(api.fill(call.body())) } }
            post("/api/fill/cancel") { call.signedIn { call.reply(api.cancelFill()) } }
            post("/api/system-art") { call.signedIn { call.reply(api.systemArt()) } }
            get("/api/img/{token}") {
                call.signedIn {
                    val image = api.image(call.parameters["token"].orEmpty())
                    if (image == null) {
                        call.json(buildJsonObject { put("error", "No such image.") }, HttpStatusCode.NotFound)
                    } else {
                        call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
                        call.respondBytes(image.second, image.first)
                    }
                }
            }
            get("/api/events") { call.signedIn { call.events() } }

            // Typing from the phone into the field open on the device, and the phone as a controller.
            get("/api/input") { call.signedIn { call.json(RemoteApi.field(RemoteInput.field.value)) } }
            put("/api/input/text") { call.signedIn { call.reply(RemoteApi.setText(call.body())) } }
            post("/api/input/done") { call.signedIn { call.reply(RemoteApi.submit(call.body())) } }
            post("/api/input/cancel") { call.signedIn { call.reply(RemoteApi.cancel(call.body())) } }
            post("/api/pad") { call.signedIn { call.reply(RemoteApi.pad(call.body(), store.prefs.value.phoneLinkController)) } }

            // Screenshots and recordings: look and download, never change or delete.
            get("/api/captures") {
                call.signedIn {
                    val s = share
                    if (s == null) {
                        call.json(buildJsonObject { put("available", false); putJsonArray("items") {} })
                    } else {
                        call.json(s.list())
                    }
                }
            }
            get("/api/captures/{id}/thumb") {
                call.signedIn {
                    val bytes = share?.thumbnail(call.parameters["id"].orEmpty())
                    if (bytes == null) {
                        call.json(buildJsonObject { put("error", "No such picture.") }, HttpStatusCode.NotFound)
                    } else {
                        call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
                        call.respondBytes(bytes, ContentType.Image.JPEG)
                    }
                }
            }
            get("/api/captures/{id}") {
                call.signedIn {
                    val s = share
                    val capture = s?.capture(call.parameters["id"].orEmpty())
                    if (s == null || capture == null) {
                        call.json(buildJsonObject { put("error", "This capture is no longer on the device.") }, HttpStatusCode.NotFound)
                    } else {
                        call.sendCapture(s, capture, attachment = false)
                    }
                }
            }
            post("/api/captures/download") {
                call.signedIn {
                    val s = share ?: return@signedIn call.json(buildJsonObject { put("error", "There are no captures on this device.") }, HttpStatusCode.NotFound)
                    val ids = ((call.body() as? JsonObject)?.get("ids") as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        .orEmpty()
                    when {
                        ids.isEmpty() -> call.json(buildJsonObject { put("error", "Choose something to download.") }, HttpStatusCode.BadRequest)
                        ids.size > CaptureShare.MAX_DOWNLOAD -> call.json(buildJsonObject { put("error", "Download at most ${CaptureShare.MAX_DOWNLOAD} at a time.") }, HttpStatusCode.BadRequest)
                        else -> {
                            val minted = s.mintDownload(ids)
                            if (minted == null) {
                                call.json(buildJsonObject { put("error", "Some of these are no longer on the device. Refresh and try again.") }, HttpStatusCode.NotFound)
                            } else {
                                call.json(buildJsonObject {
                                    put("url", minted.url)
                                    put("name", minted.name)
                                    put("size", minted.size)
                                    put("count", minted.count)
                                })
                            }
                        }
                    }
                }
            }
            // A link a signed-in phone just asked for: the browser may hand it to a download manager
            // that has no cookie, so the link itself is the permission (random, short-lived).
            get("/api/download/{token}") {
                val s = share
                val items = s?.download(call.parameters["token"].orEmpty())
                when {
                    s == null || items == null -> call.json(buildJsonObject { put("error", "This download link has expired. Start the download again.") }, HttpStatusCode.NotFound)
                    items.size == 1 -> call.sendCapture(s, items.single(), attachment = true)
                    else -> call.sendZip(s, items)
                }
            }
        }
    }

    /** One capture, whole or the byte range asked for, so videos can seek and downloads resume. */
    private suspend fun ApplicationCall.sendCapture(share: CaptureShare, capture: LinkCapture, attachment: Boolean) = share.sending {
        val reader = share.open(capture)
        if (reader == null) {
            json(buildJsonObject { put("error", "This capture is no longer on the device.") }, HttpStatusCode.NotFound)
            return@sending
        }
        try {
            // A descriptor that can't say its size falls back on the size Fuse listed.
            val length = reader.length.takeIf { it >= 0 } ?: capture.size
            val disposition = if (attachment) ContentDisposition.Attachment else ContentDisposition.Inline
            response.header(HttpHeaders.ContentDisposition, disposition.withParameter(ContentDisposition.Parameters.FileName, capture.name).toString())
            response.header(HttpHeaders.AcceptRanges, "bytes")
            val type = ContentType.parse(capture.mime)
            val (status, first, count) = when (val range = ByteRange.parse(request.headers[HttpHeaders.Range], length)) {
                ByteRange.Unsatisfiable -> {
                    response.header(HttpHeaders.ContentRange, "bytes */$length")
                    respondText("", ContentType.Text.Plain, HttpStatusCode.RequestedRangeNotSatisfiable)
                    return@sending
                }
                ByteRange.Whole -> Triple(HttpStatusCode.OK, 0L, length)
                is ByteRange.Part -> {
                    response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$length")
                    Triple(HttpStatusCode.PartialContent, range.first, range.count)
                }
            }
            if (first > 0) withContext(ioDispatcher) { reader.seek(first) }
            respondBytesWriter(type, status, count) {
                val buffer = ByteArray(COPY_BUFFER)
                var left = count
                while (left > 0) {
                    val n = withContext(ioDispatcher) { reader.read(buffer, minOf(left, buffer.size.toLong()).toInt()) }
                    if (n < 0) break
                    writeFully(buffer, 0, n)
                    left -= n
                }
            }
        } finally {
            runCatching { reader.close() }
        }
    }

    /** Several captures as one zip, written as the files are read. */
    private suspend fun ApplicationCall.sendZip(share: CaptureShare, items: List<LinkCapture>) = share.sending {
        val name = share.zipName()
        response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString())
        val entries = share.zipEntryNames(items).zip(items) { entryName, capture -> ZipItem(entryName, capture.takenAt) { share.open(capture) } }
        respondBytesWriter(ContentType.Application.Zip) { writeZip(this, entries) }
    }

    private fun securityHeaders(call: ApplicationCall) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        )
        // Pictures may be kept by the phone's browser; everything else is asked for fresh.
        val path = call.request.path()
        val picture = path.startsWith("/api/img/") || (path.startsWith("/api/captures/") && path.endsWith("/thumb"))
        if (path.startsWith("/api/") && !picture) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
        }
    }

    private suspend fun ApplicationCall.serveAsset(path: String) {
        val chunks = WebAssets.files[path] ?: return json(buildJsonObject { put("error", "Not found.") }, HttpStatusCode.NotFound)
        val gz = unbase64(chunks.joinToString(""))
        val type = when (path.substringAfterLast('.')) {
            "html" -> ContentType.Text.Html.withParameter("charset", "utf-8")
            "css" -> ContentType.Text.CSS.withParameter("charset", "utf-8")
            "js" -> ContentType("text", "javascript").withParameter("charset", "utf-8")
            "webmanifest" -> ContentType("application", "manifest+json")
            else -> ContentType.Application.OctetStream
        }
        response.header(HttpHeaders.CacheControl, "no-cache")
        response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
        if (request.headers[HttpHeaders.AcceptEncoding].orEmpty().contains("gzip")) {
            response.header(HttpHeaders.ContentEncoding, "gzip")
            respondBytes(gz, type)
        } else {
            respondBytes(gunzip(gz), type)
        }
    }

    private fun ApplicationCall.token(): String? = request.cookies[COOKIE]

    private fun ApplicationCall.gameId(): Long = parameters["id"]?.toLongOrNull() ?: -1

    private suspend fun ApplicationCall.body(): JsonElement? = runCatching {
        val text = receiveText()
        if (text.length > MAX_BODY) null else if (text.isBlank()) null else LinkJson.parseToJsonElement(text)
    }.getOrNull()

    private suspend fun ApplicationCall.signedIn(block: suspend () -> Unit) {
        if (!auth.isSignedIn(token())) {
            json(buildJsonObject { put("error", "Sign in") }, HttpStatusCode.Unauthorized)
            return
        }
        block()
    }

    private suspend fun ApplicationCall.json(body: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) =
        respondText(LinkJson.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json.withParameter("charset", "utf-8"), status)

    private suspend fun ApplicationCall.reply(result: LinkApi.Reply) = json(result.body, result.status)

    /**
     * Server-sent events: "now" and "fill" when they change, "library" when games change, "keyboard"
     * when a text field opens or closes on the device (and once on connecting), a ping every 20 s.
     * While a stream is open the phone counts as following the device.
     */
    private suspend fun ApplicationCall.events() {
        response.header(HttpHeaders.CacheControl, "no-cache")
        response.header("X-Accel-Buffering", "no")
        RemoteInput.phoneAttached()
        try {
            streamEvents()
        } finally {
            RemoteInput.phoneDetached()
        }
    }

    private suspend fun ApplicationCall.streamEvents() {
        respondBytesWriter(contentType = ContentType.Text.EventStream) {
            val lock = Mutex()
            suspend fun send(event: String?, data: String) = lock.withLock {
                if (event == null) writeStringUtf8(": $data\n\n") else writeStringUtf8("event: $event\ndata: $data\n\n")
                flush()
            }
            kotlinx.coroutines.coroutineScope {
                launch { api.nowUpdates().collect { send("now", LinkJson.encodeToString(JsonObject.serializer(), it)) } }
                launch { api.fillUpdates().collect { send("fill", it?.let { f -> LinkJson.encodeToString(JsonObject.serializer(), f) } ?: "null") } }
                launch { api.libraryUpdates().collect { send("library", "{}") } }
                // The field open on the device: a phone following along opens its keyboard for it.
                launch {
                    RemoteInput.field.collect { send("keyboard", LinkJson.encodeToString(JsonElement.serializer(), RemoteApi.field(it))) }
                }
                // A different controller picked up on the device: the Remote relabels its buttons.
                launch {
                    RemoteInput.padFamily.collect { send("pad", LinkJson.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(api.padGlyphs()))) }
                }
                share?.let { s -> launch { s.changes.collect { send("captures", "{}") } } }
                launch {
                    while (true) {
                        delay(20_000)
                        send(null, "ping")
                    }
                }
            }
        }
    }

    companion object {
        const val PORT = 47300
        private const val COOKIE = "fuse_session"
        private const val MAX_BODY = 64 * 1024L
        private const val COPY_BUFFER = 256 * 1024
    }
}
