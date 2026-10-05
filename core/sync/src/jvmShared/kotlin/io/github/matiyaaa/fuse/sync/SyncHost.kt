package io.github.matiyaaa.fuse.sync

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * A Fuse Sync Host: [store] served over HTTP on [port] for the devices that sync with it. Every
 * call but saying hello and pairing is signed by a paired device ([RequestSigning]); profile data
 * is only served to devices that opened that profile (with its PIN when it has one); files are
 * only ever named by their SHA-256, checked as they arrive, so no call can reach outside the host's
 * own store. Calls are limited per address, bodies are limited in size, and every answer to a
 * mistake is a short JSON error, never a stack trace.
 *
 * Remote access belongs behind HTTPS (a reverse proxy, or a VPN): the host itself speaks HTTP for
 * the home network, where signatures keep secrets off the wire and stop replays.
 */
class SyncHost(
    val store: HostStore,
    val port: Int = SyncApi.DEFAULT_PORT,
    private val fuseVersion: String = "",
    private val clock: () -> Long = System::currentTimeMillis,
    private val bind: String = "0.0.0.0",
    /** Calls one address may make in a minute before being asked to wait. */
    private val callsPerMinute: Int = CALLS_PER_MINUTE,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var server: EmbeddedServer<*, *>? = null
    private val nonces = ConcurrentHashMap<String, Long>()
    private val calls = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val changed = MutableStateFlow(store.seq())

    /** The journal's newest entry, for watching what changes (the Hub's live view). */
    val changes: StateFlow<Long> = changed

    @Volatile private var pairing: Pairing? = null
    private class Pairing(val code: String, val until: Long, var wrong: Int = 0)

    /**
     * A code for adding a device, shown on the host for [ttlMs]: the device types it (or reads it
     * from the Hub) once. A new code replaces the last; five wrong tries end it.
     */
    fun newPairingCode(ttlMs: Long = 10 * 60_000L): String {
        val code = SyncCrypto.pairingCode()
        pairing = Pairing(code, clock() + ttlMs)
        return code
    }

    fun pairingCode(): String? = pairing?.takeIf { it.until > clock() }?.code

    fun cancelPairing() {
        pairing = null
    }

    // ---------------------------------------------------------------- who is playing what

    private val presence = ConcurrentHashMap<String, Presence>()

    /** What each device last said it was doing, without what has long gone quiet. */
    fun presence(): List<Presence> {
        val now = clock()
        presence.values.removeIf { now - it.at > PRESENCE_TTL_MS }
        return presence.values.sortedByDescending { it.at }
    }

    private fun notePresence(d: DeviceRecord, note: PresenceNote) {
        val state = note.state
        if (state == null) {
            presence.remove(d.id)
            return
        }
        val now = clock()
        val was = presence[d.id]?.takeIf { it.game == note.game }
        presence[d.id] = Presence(d.id, d.name, note.profile, note.game.take(200), note.title.take(200), state, now, was?.since ?: note.since.takeIf { it in 1..now } ?: now)
    }

    // ---------------------------------------------------------------- joining without a code

    private class Join(val id: String, val request: JoinRequest, val keys: java.security.KeyPair, val secret: String, val match: String, val at: Long) {
        @Volatile var state: String = JoinState.WAITING
        @Volatile var sealed: String? = null
        @Volatile var salt: String? = null
    }

    private val joins = ConcurrentHashMap<String, Join>()

    /** Its secret, sealed with the key only the device and the host have. */
    private fun letIn(j: Join) {
        val device = store.addDevice(j.request.deviceId, j.request.deviceName, j.request.platform)
        val salt = SyncCrypto.token(16)
        j.sealed = SyncCrypto.seal(device.secret.toByteArray(), j.secret, salt)
        j.salt = salt
        j.state = JoinState.ALLOWED
    }

    // ---------------------------------------------------------------- the account, from away

    /** Wrong passwords lately, by who tried (the caller a tunnel carries, else the address). */
    private val misses = ConcurrentHashMap<String, MutableList<Long>>()

    /** True (after answering) when [who] got the password wrong too often lately. */
    private fun lockedOut(who: String): Boolean {
        val now = clock()
        val list = misses[who] ?: return false
        synchronized(list) { list.removeIf { now - it > MISS_WINDOW_MS } }
        return list.size >= MAX_MISSES
    }

    private fun missed(who: String) {
        val list = misses.getOrPut(who) { mutableListOf() }
        synchronized(list) { list += clock() }
        if (misses.size > 10_000) misses.clear()
    }

    /** Signed-in Hub sessions from away: token to when it ends. */
    private val hubSessions = ConcurrentHashMap<String, Long>()

    /** Requests to join still waiting for someone to let them in, newest first. */
    fun joinRequests(): List<JoinAsk> {
        val now = clock()
        joins.values.removeIf { now - it.at > JOIN_TTL_MS }
        return joins.values.filter { it.state == JoinState.WAITING }.sortedByDescending { it.at }
            .map { JoinAsk(it.id, it.request.deviceName, it.request.platform, it.match, it.at) }
    }

    /**
     * Lets the device that asked in (its secret sealed with the key only it and the host have),
     * or turns it away. False when there is no such request any more.
     */
    fun answerJoin(id: String, allow: Boolean): Boolean {
        val j = joins[id]?.takeIf { it.state == JoinState.WAITING && clock() - it.at <= JOIN_TTL_MS } ?: return false
        if (allow) {
            letIn(j)
        } else {
            j.state = JoinState.DENIED
        }
        // Every device showing the request learns it was answered.
        store.noteJoin()
        bump()
        return true
    }

    fun start(): SyncHost {
        server = embeddedServer(CIO, port = port, host = bind) {
            routing { api() }
        }.also { it.start(wait = false) }
        return this
    }

    /** The port the server actually listens on (for a [port] of 0, chosen by the system). */
    suspend fun boundPort(): Int = server?.engine?.resolvedConnectors()?.firstOrNull()?.port ?: port

    fun stop() {
        server?.stop(500, 2_000)
        server = null
    }

    private fun bump() {
        changed.value = store.seq()
    }

    // ---------------------------------------------------------------- the API

    private fun Route.api() {
        // The Hub as a web page, to this computer only (it names people and devices).
        get("/hub") {
            if (limited()) return@get
            val local = fromThisComputer()
            val away = !local && hubSignedIn()
            if (!local && !away) {
                // From away: the host's account first; without one, the Hub is this computer's alone.
                return@get if (store.accountName() != null) {
                    call.respondText(HubPage.login(store.name, null), ContentType.Text.Html)
                } else {
                    call.respondText("The Hub opens on the host computer. To open it from away, set up a host account in Fuse on the host.", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                }
            }
            val status = store.status(port, fuseVersion)
            call.response.headers.append("Cache-Control", "no-store")
            call.respondText(HubPage.render(status, status.profiles.mapNotNull { store.report(it.id) }, clock(), away = away), ContentType.Text.Html)
        }
        post("/hub/login") {
            if (limited(weight = 20)) return@post
            val who = caller()
            if (lockedOut(who)) return@post call.respondText(HubPage.login(store.name, "Too many wrong passwords. Try again in a few minutes."), ContentType.Text.Html, HttpStatusCode.TooManyRequests)
            val form = runCatching { io.ktor.http.parseQueryString(call.receiveChannel().readRemaining(8_192).readByteArray().decodeToString()) }.getOrNull()
            val user = form?.get("username").orEmpty()
            val pass = form?.get("password").orEmpty()
            if (!store.checkAccount(user, pass)) {
                missed(who)
                return@post call.respondText(HubPage.login(store.name, "That username or password isn't the host's."), ContentType.Text.Html, HttpStatusCode.Forbidden)
            }
            val token = SyncCrypto.token(32)
            val now = clock()
            hubSessions.entries.removeIf { it.value < now }
            hubSessions[token] = now + HUB_SESSION_MS
            val secure = call.request.headers["X-Forwarded-Proto"] == "https" || call.request.headers["CF-Visitor"]?.contains("https") == true
            call.response.headers.append(
                "Set-Cookie",
                "$HUB_COOKIE=$token; Path=/hub; Max-Age=${HUB_SESSION_MS / 1000}; HttpOnly; SameSite=Strict" + if (secure) "; Secure" else "",
            )
            call.response.headers.append("Location", "/hub")
            call.respondText("", ContentType.Text.Plain, HttpStatusCode.SeeOther)
        }
        post("/hub/logout") {
            if (limited()) return@post
            hubToken()?.let { hubSessions.remove(it) }
            call.response.headers.append("Set-Cookie", "$HUB_COOKIE=; Path=/hub; Max-Age=0; HttpOnly; SameSite=Strict")
            call.response.headers.append("Location", "/hub")
            call.respondText("", ContentType.Text.Plain, HttpStatusCode.SeeOther)
        }
        route(SyncApi.BASE) {
            get("/hello") { if (limited()) return@get; call.json(HostHello.serializer(), store.hello(port, fuseVersion)) }
            // Asking to join without a code: someone already in (or the host) lets the device in.
            post("/join") {
                if (limited(weight = 20)) return@post
                val req = body(JoinRequest.serializer()) ?: return@post
                if (req.deviceId.length !in 8..64 || req.deviceName.isBlank() || req.publicKey.length > 400) return@post call.fail(HttpStatusCode.BadRequest, "Bad device", "bad-device")
                joinRequests()
                if (joins.values.count { it.state == JoinState.WAITING } >= MAX_JOINS) return@post call.fail(HttpStatusCode.TooManyRequests, "Too many devices are asking at once. Try again in a few minutes.", "busy")
                val keys = SyncCrypto.joinKeys()
                val mine = SyncCrypto.publicKeyText(keys)
                val secret = SyncCrypto.joinSecret(keys, req.publicKey) ?: return@post call.fail(HttpStatusCode.BadRequest, "Bad key", "bad-key")
                val join = Join(SyncCrypto.token(12), req.copy(deviceName = req.deviceName.take(64), platform = req.platform.take(32)), keys, secret, SyncCrypto.joinMatch(req.publicKey, mine), clock())
                joins[join.id] = join
                store.noteJoin()
                bump()
                val stretch = store.accountStretch()
                call.json(JoinTicket.serializer(), JoinTicket(join.id, store.identity.hostId, store.name, mine, stretch?.first, stretch?.second ?: 0))
            }
            get("/join/{id}") {
                if (limited(weight = 2)) return@get
                val j = joins[call.parameters["id"].orEmpty()]
                val state = when {
                    j == null || (j.state == JoinState.WAITING && clock() - j.at > JOIN_TTL_MS) -> JoinState(JoinState.GONE)
                    j.state == JoinState.ALLOWED -> JoinState(JoinState.ALLOWED, j.sealed, j.salt).also { joins.remove(j.id) }
                    else -> JoinState(j.state)
                }
                call.json(JoinState.serializer(), state)
            }
            // Nobody at a screen: the host's account lets the device in (the password never travels).
            post("/join/{id}/account") {
                if (limited(weight = 20)) return@post
                val who = caller()
                if (lockedOut(who)) return@post call.fail(HttpStatusCode.TooManyRequests, "Too many wrong passwords. Try again in a few minutes.", "locked-out")
                val req = body(JoinWithAccount.serializer()) ?: return@post
                val j = joins[call.parameters["id"].orEmpty()]?.takeIf { it.state == JoinState.WAITING && clock() - it.at <= JOIN_TTL_MS }
                    ?: return@post call.fail(HttpStatusCode.Gone, "That request ran out. Ask again.", "gone")
                if (store.accountName() == null) return@post call.fail(HttpStatusCode.Forbidden, "The host has no account. Ask someone at a screen to let this device in.", "no-account")
                if (!store.accountProofOk(req.username, j.secret, req.proof)) {
                    missed(who)
                    return@post call.fail(HttpStatusCode.Forbidden, "That username or password isn't the host's.", "wrong-account")
                }
                letIn(j)
                bump()
                call.json(JoinState.serializer(), JoinState(JoinState.ALLOWED, j.sealed, j.salt).also { joins.remove(j.id) })
            }
            post("/pair") {
                if (limited(weight = 20)) return@post
                val req = body(PairRequest.serializer()) ?: return@post
                val p = pairing
                if (p == null || p.until < clock()) return@post call.fail(HttpStatusCode.Forbidden, "No device is being added on the host right now. Add a device there to get a code.", "no-pairing")
                if (!SyncCrypto.constantEquals(p.code, req.code.trim().uppercase())) {
                    p.wrong++
                    if (p.wrong >= 5) pairing = null
                    return@post call.fail(HttpStatusCode.Forbidden, "That code isn't the one on the host.", "wrong-code")
                }
                if (req.deviceId.length !in 8..64 || req.deviceName.isBlank()) return@post call.fail(HttpStatusCode.BadRequest, "Bad device", "bad-device")
                pairing = null
                val device = store.addDevice(req.deviceId, req.deviceName, req.platform)
                val salt = SyncCrypto.token(16)
                bump()
                call.json(PairResponse.serializer(), PairResponse(store.identity.hostId, store.name, SyncCrypto.seal(device.secret.toByteArray(), p.code, salt), salt))
            }
            get("/status") { val d = device() ?: return@get; call.json(HostStatus.serializer(), store.status(port, fuseVersion)).also { store.touchDevice(d.id, route()) } }
            get("/profiles") { val d = device() ?: return@get; call.json(ListSerializer(ProfileInfo.serializer()), store.profiles(d.id)) }
            post("/profiles") {
                val d = device() ?: return@post
                val req = signedBody(d, NewProfile.serializer()) ?: return@post
                val made = runCatching { store.createProfile(req) }.getOrElse { return@post call.fail(HttpStatusCode.BadRequest, it.message ?: "Couldn't make the profile", "bad-profile") }
                // The device that made a profile may use it.
                store.unlock(d.id, made.id, req.pin)
                bump()
                call.json(ProfileInfo.serializer(), made)
            }
            patch("/profiles/{id}") {
                val d = device() ?: return@patch
                val id = call.parameters["id"].orEmpty()
                val req = signedBody(d, ProfileChange.serializer()) ?: return@patch
                if (!store.mayUse(d.id, id)) return@patch call.fail(HttpStatusCode.Forbidden, "Open this profile first.", "locked")
                val changedProfile = runCatching { store.changeProfile(id, req) }.getOrElse {
                    return@patch call.fail(if (it is SecurityException) HttpStatusCode.Forbidden else HttpStatusCode.BadRequest, it.message ?: "Couldn't change it", "bad-change")
                }
                // Whoever changed the PIN keeps the profile open.
                if (req.pin != null || req.removePin) store.unlock(d.id, id, req.pin)
                bump()
                call.json(ProfileInfo.serializer(), changedProfile)
            }
            delete("/profiles/{id}") {
                val d = device() ?: return@delete
                val id = call.parameters["id"].orEmpty()
                if (!store.mayUse(d.id, id)) return@delete call.fail(HttpStatusCode.Forbidden, "Open this profile first.", "locked")
                store.deleteProfile(id)
                bump()
                call.respondText("{}", ContentType.Application.Json)
            }
            post("/profiles/{id}/open") {
                val d = device() ?: return@post
                if (limited(weight = 5)) return@post
                val id = call.parameters["id"].orEmpty()
                val req = signedBody(d, UnlockRequest.serializer()) ?: return@post
                when (val r = store.unlock(d.id, id, req.pin)) {
                    UnlockResult.Opened -> call.json(ProfileTicket.serializer(), ProfileTicket(id, "open"))
                    UnlockResult.WrongPin -> call.fail(HttpStatusCode.Forbidden, "That PIN isn't right.", "wrong-pin")
                    UnlockResult.NoProfile -> call.fail(HttpStatusCode.NotFound, "No such profile.", "no-profile")
                    is UnlockResult.Wait -> call.fail(HttpStatusCode.TooManyRequests, "Too many tries. Wait ${(r.millis + 999) / 1000} s.", "wait")
                }
            }
            get("/profiles/{id}/meta") { val (_, p) = profileCall() ?: return@get; call.json(MetaState.serializer(), store.meta(p)) }
            get("/profiles/{id}/report") {
                val (_, p) = profileCall() ?: return@get
                val report = store.report(p) ?: return@get call.fail(HttpStatusCode.NotFound, "No such profile.", "no-profile")
                call.json(ProfileReport.serializer(), report)
            }
            post("/profiles/{id}/meta") {
                val (d, p) = profileCall() ?: return@post
                val req = signedBody(d, MetaPush.serializer()) ?: return@post
                val state = store.mergeMeta(p, d.id, req.meta)
                store.touchDevice(d.id, route(), synced = true)
                bump()
                call.json(MetaState.serializer(), state)
            }
            get("/profiles/{id}/heads") { val (_, p) = profileCall() ?: return@get; call.json(Heads.serializer(), store.heads(p)) }
            get("/profiles/{id}/revisions") {
                val (_, p) = profileCall() ?: return@get
                val game = call.request.queryParameters["game"]
                val kind = call.request.queryParameters["kind"]?.let { k -> SaveKind.entries.firstOrNull { it.name == k } }
                call.json(ListSerializer(SaveRevision.serializer()), store.revisions(p, game, kind))
            }
            post("/profiles/{id}/revisions") {
                val (d, p) = profileCall() ?: return@post
                val req = signedBody(d, RevisionPush.serializer()) ?: return@post
                val result = runCatching { store.push(p, d.id, req.revision) }.getOrElse { return@post call.fail(HttpStatusCode.BadRequest, it.message ?: "Bad revision", "bad-revision") }
                store.touchDevice(d.id, route(), synced = true)
                bump()
                call.json(RevisionResult.serializer(), result)
            }
            post("/profiles/{id}/revisions/{rev}/pin") {
                val (d, p) = profileCall() ?: return@post
                val pinned = signedBody(d, Boolean.serializer()) ?: return@post
                if (!store.pin(p, call.parameters["rev"].orEmpty(), pinned)) return@post call.fail(HttpStatusCode.NotFound, "No such revision.", "no-revision")
                call.respondText("{}", ContentType.Application.Json)
            }
            post("/blobs/missing") {
                val d = device() ?: return@post
                val req = signedBody(d, MissingBlobs.serializer()) ?: return@post
                if (req.hashes.size > 10_000) return@post call.fail(HttpStatusCode.BadRequest, "Too many at once.", "too-many")
                call.json(MissingBlobs.serializer(), MissingBlobs(store.missing(req.hashes)))
            }
            put("/blobs/{hash}") {
                val hash = call.parameters["hash"].orEmpty()
                if (!SavePath.isHash(hash)) return@put call.fail(HttpStatusCode.BadRequest, "Not a content hash.", "bad-hash")
                // Signed over the hash itself (the body is checked against it as it is stored).
                val d = device(bodyHash = hash) ?: return@put
                val stored = withContext(Dispatchers.IO) {
                    runCatching { store.content.put(call.receiveChannel().toInputStream(), expected = hash, maxBytes = MAX_BLOB) }
                }
                stored.onFailure { return@put call.fail(HttpStatusCode.UnprocessableEntity, "The file arrived damaged. It will be sent again.", "integrity") }
                store.touchDevice(d.id, route())
                call.respondText("{}", ContentType.Application.Json)
            }
            get("/blobs/{hash}") {
                device() ?: return@get
                val hash = call.parameters["hash"].orEmpty()
                if (!SavePath.isHash(hash) || !store.content.has(hash)) return@get call.fail(HttpStatusCode.NotFound, "Not on the host.", "no-blob")
                call.respondOutputStream(ContentType.Application.OctetStream) { withContext(Dispatchers.IO) { store.content.copyTo(hash, this@respondOutputStream) } }
            }
            get("/events") {
                device() ?: return@get
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
                val wait = (call.request.queryParameters["wait"]?.toLongOrNull() ?: 0).coerceIn(0, 30)
                if (wait > 0 && store.seq() <= since) withTimeoutOrNull(wait * 1000) { changed.first { it > since } }
                // Events carry only ids: what changed is fetched through the calls that check access.
                call.json(JournalPage.serializer(), store.events(since))
            }
            // Management from this computer only, with the token in the host's own folder.
            route("/admin") {
                post("/pairing") { if (!admin()) return@post; call.json(String.serializer(), newPairingCode()) }
                get("/joins") { if (!admin()) return@get; call.json(ListSerializer(JoinAsk.serializer()), joinRequests()) }
                post("/joins/{id}") {
                    if (!admin()) return@post
                    val req = body(JoinAnswer.serializer()) ?: return@post
                    if (!answerJoin(call.parameters["id"].orEmpty(), req.allow)) return@post call.fail(HttpStatusCode.NotFound, "That request ran out or was answered.", "no-join")
                    call.respondText("{}", ContentType.Application.Json)
                }
                post("/account") {
                    if (!admin()) return@post
                    val req = body(HostAccountChange.serializer()) ?: return@post
                    runCatching { store.setAccount(req.username, req.password) }
                        .onFailure { return@post call.fail(HttpStatusCode.BadRequest, it.message ?: "Bad account", "bad-account") }
                    bump()
                    call.respondText("{}", ContentType.Application.Json)
                }
                delete("/account") {
                    if (!admin()) return@delete
                    store.clearAccount()
                    hubSessions.clear()
                    bump()
                    call.respondText("{}", ContentType.Application.Json)
                }
                post("/outside") {
                    if (!admin()) return@post
                    val req = body(OutsideChange.serializer()) ?: return@post
                    store.setOutsideAddress(req.address)
                    bump()
                    call.respondText("{}", ContentType.Application.Json)
                }
                // The Fuse on this computer, and the host's own profile for it.
                post("/owner/{id}") {
                    if (!admin()) return@post
                    val id = call.parameters["id"].orEmpty()
                    if (store.device(id) == null) return@post call.fail(HttpStatusCode.NotFound, "No such device.", "no-device")
                    val admin = store.adoptOwner(id)
                    bump()
                    call.json(ProfileInfo.serializer(), admin)
                }
                get("/status") { if (!admin()) return@get; call.json(HostStatus.serializer(), store.status(port, fuseVersion)) }
                patch("/devices/{id}") {
                    if (!admin()) return@patch
                    val req = body(DeviceChange.serializer()) ?: return@patch
                    val info = store.changeDevice(call.parameters["id"].orEmpty(), req) ?: return@patch call.fail(HttpStatusCode.NotFound, "No such device.", "no-device")
                    bump()
                    call.json(DeviceInfo.serializer(), info)
                }
                delete("/devices/{id}") {
                    if (!admin()) return@delete
                    store.revokeDevice(call.parameters["id"].orEmpty())
                    bump()
                    call.respondText("{}", ContentType.Application.Json)
                }
            }
            get("/devices") { device() ?: return@get; call.json(ListSerializer(DeviceInfo.serializer()), store.devices()) }
            get("/presence") { device() ?: return@get; call.json(ListSerializer(Presence.serializer()), presence()) }
            post("/presence") {
                val d = device() ?: return@post
                val req = signedBody(d, PresenceNote.serializer()) ?: return@post
                notePresence(d, req)
                call.respondText("{}", ContentType.Application.Json)
            }
            post("/games/resolve") {
                val d = device() ?: return@post
                val req = signedBody(d, GameClaims.serializer()) ?: return@post
                if (req.games.size > 5_000) return@post call.fail(HttpStatusCode.BadRequest, "Too many games at once.", "too-many")
                call.json(ResolvedGames.serializer(), ResolvedGames(store.resolveGames(req.games)))
            }
            // Any device already in can let another in, or show a code for it.
            get("/joins") { device() ?: return@get; call.json(ListSerializer(JoinAsk.serializer()), joinRequests()) }
            post("/joins/{id}") {
                val d = device() ?: return@post
                val req = signedBody(d, JoinAnswer.serializer()) ?: return@post
                if (!answerJoin(call.parameters["id"].orEmpty(), req.allow)) return@post call.fail(HttpStatusCode.NotFound, "That request ran out or was answered.", "no-join")
                call.respondText("{}", ContentType.Application.Json)
            }
            post("/pairing") { device() ?: return@post; call.json(String.serializer(), newPairingCode()) }
            get("/shared-games") { device() ?: return@get; call.json(SharedGames.serializer(), store.sharedGames()) }
            post("/shared-games") {
                val d = device() ?: return@post
                val req = signedBody(d, SharedChange.serializer()) ?: return@post
                // Starting from someone's save needs that person's say-so on this device (their PIN, if they have one).
                if (req.from != null && !store.mayUse(d.id, req.from)) return@post call.fail(HttpStatusCode.Forbidden, "Open that profile on this device first.", "locked")
                val result = runCatching { store.setShared(req.game, req.shared, req.from, d.id) }.getOrElse { return@post call.fail(HttpStatusCode.BadRequest, it.message ?: "Bad request", "bad-request") }
                call.json(SharedGames.serializer(), result)
            }
            patch("/devices/{id}") {
                val d = device() ?: return@patch
                val req = signedBody(d, DeviceChange.serializer()) ?: return@patch
                val id = call.parameters["id"].orEmpty()
                // A device renames itself; others are renamed from the Hub on the host.
                if (id != d.id) return@patch call.fail(HttpStatusCode.Forbidden, "Only this device's own name can be changed from it.", "not-yours")
                val info = store.changeDevice(id, req.copy(profile = null)) ?: return@patch call.fail(HttpStatusCode.NotFound, "No such device.", "no-device")
                bump()
                call.json(DeviceInfo.serializer(), info)
            }
            delete("/devices/{id}") {
                val d = device() ?: return@delete
                val id = call.parameters["id"].orEmpty()
                // A device can always unlink itself.
                if (id != d.id) return@delete call.fail(HttpStatusCode.Forbidden, "Unlink other devices from the host's Hub.", "not-yours")
                store.revokeDevice(id)
                bump()
                call.respondText("{}", ContentType.Application.Json)
            }
        }
    }

    // ---------------------------------------------------------------- checks

    /**
     * The signed device making this call, or null after answering why not. [bodyHash] stands in
     * for the body's hash when the body is streamed (a file, signed over its content hash).
     */
    private suspend fun RoutingContext.device(bodyHash: String? = null): DeviceRecord? {
        if (limited()) return null
        val h = call.request.headers
        val id = h[RequestSigning.DEVICE]
        val time = h[RequestSigning.TIME]?.toLongOrNull()
        val nonce = h[RequestSigning.NONCE]
        val sig = h[RequestSigning.SIGNATURE]
        if (id == null || time == null || nonce == null || sig == null || nonce.length !in 16..64) {
            call.fail(HttpStatusCode.Unauthorized, "This device isn't paired with this host.", "unsigned")
            return null
        }
        val d = store.device(id)
        if (d == null || d.revoked) {
            call.fail(HttpStatusCode.Unauthorized, "This device was unlinked from the host. Connect it again.", "revoked")
            return null
        }
        val now = clock()
        if (kotlin.math.abs(now - time) > RequestSigning.WINDOW_MS) {
            call.fail(HttpStatusCode.Unauthorized, "This device's clock is too far from the host's.", "clock")
            return null
        }
        val body = if (bodyHash != null) null else call.receiveChannel().readRemaining(MAX_JSON + 1).readByteArray()
        if (body != null && body.size > MAX_JSON) {
            call.fail(HttpStatusCode.PayloadTooLarge, "Too large.", "too-large")
            return null
        }
        lastBodies[call] = body ?: ByteArray(0)
        val path = call.request.uri
        val message = RequestSigning.message(call.request.httpMethod.value, path, time, nonce, bodyHash ?: SyncCrypto.sha256(body!!))
        val expected = SyncCrypto.hmac(SyncCrypto.decode(d.secret), message)
        if (!SyncCrypto.constantEquals(expected, sig)) {
            call.fail(HttpStatusCode.Unauthorized, "The call's signature doesn't match.", "bad-signature")
            return null
        }
        // Each nonce once: a recorded call played back is refused.
        if (nonces.putIfAbsent("$id/$nonce", now) != null) {
            call.fail(HttpStatusCode.Unauthorized, "This call was already made.", "replay")
            return null
        }
        if (nonces.size > 50_000) nonces.entries.removeIf { now - it.value > RequestSigning.WINDOW_MS * 2 }
        store.touchDevice(d.id, route())
        learnOutside()
        return d
    }

    private val lastBodies = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ApplicationCall, ByteArray>())

    /** The body [device] already read and checked, decoded. */
    private suspend fun <T> RoutingContext.signedBody(d: DeviceRecord, serializer: KSerializer<T>): T? {
        val bytes = lastBodies.remove(call) ?: ByteArray(0)
        return runCatching { json.decodeFromString(serializer, bytes.decodeToString()) }.getOrElse {
            call.fail(HttpStatusCode.BadRequest, "That isn't a call this host understands.", "bad-body")
            null
        }
    }

    /** An unsigned body (pairing), read with a size limit. */
    private suspend fun <T> RoutingContext.body(serializer: KSerializer<T>): T? {
        val bytes = call.receiveChannel().readRemaining(MAX_JSON + 1).readByteArray()
        if (bytes.size > MAX_JSON) {
            call.fail(HttpStatusCode.PayloadTooLarge, "Too large.", "too-large")
            return null
        }
        return runCatching { json.decodeFromString(serializer, bytes.decodeToString()) }.getOrElse {
            call.fail(HttpStatusCode.BadRequest, "That isn't a call this host understands.", "bad-body")
            null
        }
    }

    /** The signed device and the profile in the path, when the device may use it. */
    private suspend fun RoutingContext.profileCall(): Pair<DeviceRecord, String>? {
        val d = device() ?: return null
        val p = call.parameters["id"].orEmpty()
        if (!store.mayUse(d.id, p)) {
            call.fail(HttpStatusCode.Forbidden, "Open this profile on this device first.", "locked")
            return null
        }
        return d to p
    }

    /** True for a management call from this computer with the host's token; else answered and false. */
    private suspend fun RoutingContext.admin(): Boolean {
        val local = fromThisComputer()
        val token = call.request.headers["X-Fuse-Admin"]
        if (!local || token == null || !SyncCrypto.constantEquals(token, store.adminToken)) {
            call.fail(HttpStatusCode.Forbidden, "Only Fuse on the host can do that.", "not-admin")
            return false
        }
        return true
    }

    /**
     * True when the call comes from this computer itself. A tunnel or proxy on this computer
     * (cloudflared, a reverse proxy) also connects from here, but says who it carries in its
     * headers: such a call is from outside, whatever address it arrives from.
     */
    private fun RoutingContext.fromThisComputer(): Boolean {
        val loopback = call.request.origin.remoteHost.let { it == "127.0.0.1" || it == "::1" || it == "0:0:0:0:0:0:0:1" || it == "localhost" }
        val h = call.request.headers
        val proxied = PROXY_HEADERS.any { h[it] != null }
        return loopback && !proxied
    }

    /**
     * A paired device reached the host through a tunnel or proxy by name: that name (with https)
     * is the host's address from outside, for every device, unless the person typed one.
     */
    private fun RoutingContext.learnOutside() {
        if (fromThisComputer()) return
        val h = call.request.headers
        if (PROXY_HEADERS.none { h[it] != null }) return
        val name = (h["X-Forwarded-Host"] ?: h["Host"])?.substringBefore(',')?.trim()?.lowercase() ?: return
        val bare = name.substringBefore(':')
        if (bare.isEmpty() || bare == "localhost" || bare.all { it.isDigit() || it == '.' } || ':' in bare || !bare.contains('.') || bare.endsWith(".local") || bare.endsWith(".lan")) return
        val https = h["X-Forwarded-Proto"] == "https" || h["CF-Visitor"]?.contains("https") == true || h["CF-Ray"] != null
        if (store.setOutsideAddress((if (https) "https://" else "http://") + name, learned = true)) bump()
    }

    /** Who is calling, for counting wrong passwords: the caller a tunnel names, else the address. */
    private fun RoutingContext.caller(): String {
        val h = call.request.headers
        return h["CF-Connecting-IP"] ?: h["X-Real-IP"] ?: h["X-Forwarded-For"]?.substringBefore(',')?.trim() ?: call.request.origin.remoteAddress
    }

    private fun RoutingContext.hubToken(): String? =
        call.request.headers["Cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$HUB_COOKIE=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    private fun RoutingContext.hubSignedIn(): Boolean {
        val token = hubToken() ?: return false
        val until = hubSessions[token] ?: return false
        if (until < clock()) {
            hubSessions.remove(token)
            return false
        }
        return store.accountName() != null
    }

    private fun RoutingContext.route(): String = call.request.headers["X-Fuse-Route"]?.takeIf { it == "LOCAL" || it == "REMOTE" } ?: ""

    /** True (after answering) when this address has made too many calls this minute. */
    private suspend fun RoutingContext.limited(weight: Int = 1): Boolean {
        val who = call.request.origin.remoteAddress
        val minute = clock() / 60_000
        val (m, count) = calls[who] ?: (minute to 0)
        val now = if (m == minute) count + weight else weight
        calls[who] = minute to now
        if (calls.size > 10_000) calls.entries.removeIf { it.value.first < minute }
        if (now > callsPerMinute) {
            call.fail(HttpStatusCode.TooManyRequests, "Too many calls. Try again in a minute.", "rate")
            return true
        }
        return false
    }

    private suspend fun <T> ApplicationCall.json(serializer: KSerializer<T>, value: T) =
        respondText(json.encodeToString(serializer, value), ContentType.Application.Json)

    private suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String, code: String) =
        respondText(json.encodeToString(ApiError.serializer(), ApiError(message, code)), ContentType.Application.Json, status)

    companion object {
        /** The largest JSON call: a profile's records for a big library fit easily. */
        const val MAX_JSON = 16L * 1024 * 1024

        /**
         * The largest single file. Only saves, save states and memory cards are kept (never games
         * themselves), and the biggest of those (a PS3 or Switch save, a state with its screenshot)
         * stays well under this.
         */
        const val MAX_BLOB = 1L * 1024 * 1024 * 1024

        const val CALLS_PER_MINUTE = 1_200

        /** How long a request to join waits for someone to let the device in. */
        const val JOIN_TTL_MS = 5 * 60_000L

        /** A device quiet this long is forgotten as playing anything. */
        const val PRESENCE_TTL_MS = 12 * 60 * 60_000L
        private const val MAX_JOINS = 8

        /** Wrong passwords allowed in [MISS_WINDOW_MS] before that caller waits. */
        private const val MAX_MISSES = 5
        private const val MISS_WINDOW_MS = 10 * 60_000L
        private const val HUB_SESSION_MS = 12 * 60 * 60_000L
        private const val HUB_COOKIE = "fuse_hub"

        /** Headers a tunnel or reverse proxy adds for the caller it carries. */
        private val PROXY_HEADERS = listOf("CF-Connecting-IP", "CF-Ray", "X-Forwarded-For", "X-Forwarded-Host", "X-Real-IP", "Forwarded", "True-Client-IP")
    }
}
