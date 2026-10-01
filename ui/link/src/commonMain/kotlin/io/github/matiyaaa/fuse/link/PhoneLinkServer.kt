package io.github.matiyaaa.fuse.link

import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkControl
import io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkState
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
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phone Link: a small web server (Ktor, CIO) for a phone on the same network, serving the phone web
 * app and its API (docs/PHONE_LINK.md). It runs while Phone Link is on in Settings.
 *
 * Every request must come from the local network and name the device by address; everything but
 * the app itself, the session check and sign-in needs a signed-in phone. There are no routes that
 * delete anything, show keys or passwords, or change settings beyond fixing a game's name, details
 * and art and starting art fills.
 */
class PhoneLinkServer(
    private val store: FuseStore,
    secrets: SecretStore,
    private val scope: CoroutineScope,
    private val deviceName: String,
    private val version: String,
    /** Reads art that isn't a plain file (Android content URIs); null where there is none. */
    private val readUri: (suspend (String) -> ByteArray?)? = null,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : PhoneLinkControl {
    internal val auth = LinkAuth(secrets, clock)
    internal val api = LinkApi(store, auth, deviceName, version, readUri)
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
                val s = withContext(Dispatchers.Default) { launchServer(candidate) }
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
        withContext(Dispatchers.Default) { runCatching { s.stop(200, 1_000) } }
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
        }
    }

    private fun securityHeaders(call: ApplicationCall) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        )
        if (call.request.path().startsWith("/api/") && !call.request.path().startsWith("/api/img/")) {
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

    /** Server-sent events: "now" and "fill" when they change, "library" when games change, a ping every 20 s. */
    private suspend fun ApplicationCall.events() {
        response.header(HttpHeaders.CacheControl, "no-cache")
        response.header("X-Accel-Buffering", "no")
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
    }
}
