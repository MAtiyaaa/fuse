package io.github.matiyaaa.fuse.sync

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondBytes
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
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
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

    /** The household's games: each device's list, requests between devices, games passing through. */
    val household = HouseholdHost(java.io.File(store.dir, "household"), clock)

    /** Tickets this host gave, by id, while they last (a ticket from before a restart is asked for again). */
    private val tickets = ConcurrentHashMap<String, PeerTicket>()

    /** For trying a device directly when passing a game through (the host is usually at home with it). */
    private val peerHttp by lazy {
        SyncHttp.client {
            install(io.ktor.client.plugins.HttpTimeout) { connectTimeoutMillis = 1_500; socketTimeoutMillis = 60_000; requestTimeoutMillis = 10 * 60_000 }
            expectSuccess = false
        }
    }

    /** Devices the host couldn't reach directly lately, until when: their pieces go the other way meanwhile. */
    private val unreachable = ConcurrentHashMap<String, Long>()

    private fun liveDevices(): Set<String> = store.devices().filter { !it.revoked }.map { it.id }.toSet()

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
            call.html(HubPage.render(status, status.profiles.mapNotNull { store.report(it.id) }, clock(), away = away))
        }
        // One game's versions and files, asked for when it is opened on the Hub (or as a page of
        // its own, without scripting). The same rule as the Hub: this computer, or signed in.
        get("/hub/game") {
            if (limited()) return@get
            if (!fromThisComputer() && !hubSignedIn()) return@get call.respondText("Sign in to the Hub first.", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            val profile = call.request.queryParameters["p"].orEmpty()
            val game = call.request.queryParameters["g"].orEmpty()
            val g = store.gameReport(profile, game) ?: return@get call.respondText("That game isn't on the host.", ContentType.Text.Plain, HttpStatusCode.NotFound)
            val names = store.status(port, fuseVersion).devices.associate { it.id to it.name }
            val deviceName = { id: String -> names[id] ?: id }
            call.html(
                if (call.request.queryParameters["page"] != null) HubPage.gamePage(store.name, g, deviceName)
                else HubPage.gameBody(g, deviceName),
            )
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
                // The device that made a profile may use it (with a carried PIN, it proved it elsewhere).
                if (req.pin.isNullOrBlank() && req.pinHash != null) store.openFor(d.id, made.id) else store.unlock(d.id, made.id, req.pin)
                bump()
                call.json(ProfileInfo.serializer(), made)
            }
            post("/profiles/order") {
                val d = device() ?: return@post
                val req = signedBody(d, ProfileOrder.serializer()) ?: return@post
                store.setOrder(req.ids.take(MAX_ORDER))
                bump()
                call.respondText("{}", ContentType.Application.Json)
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
            get("/profiles/{id}/convergence") {
                val (_, p) = profileCall() ?: return@get
                val game = call.request.queryParameters["game"].orEmpty()
                if (game.isEmpty()) return@get call.fail(HttpStatusCode.BadRequest, "Which game?", "bad-request")
                call.json(Convergence.serializer(), store.convergence(p, game))
            }
            // The household's RomM and Jellyfin: addresses, and sign-ins sealed for the asking device alone.
            get("/services") {
                val d = device() ?: return@get
                val services = store.servicesFor(d.id) ?: return@get call.fail(HttpStatusCode.Unauthorized, "This device was unlinked from the host. Connect it again.", "revoked")
                call.json(HouseholdServices.serializer(), services)
            }
            post("/services") {
                val d = device() ?: return@post
                val req = signedBody(d, ServicesShare.serializer()) ?: return@post
                if (!store.shareServices(d.id, req)) return@post call.fail(HttpStatusCode.BadRequest, "The sign-ins couldn't be opened.", "seal")
                call.respondText("{}", ContentType.Application.Json)
            }
            post("/applied") {
                val d = device() ?: return@post
                val req = signedBody(d, AppliedNotes.serializer()) ?: return@post
                // Only what this device may see: its own profiles' saves and the shared ones.
                store.noteApplied(d.id, req.notes.filter { it.profile == SHARED_SAVES || store.mayUse(d.id, it.profile) })
                store.touchDevice(d.id, route())
                call.respondText("{}", ContentType.Application.Json)
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
                val d = device() ?: return@get
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
                val wait = (call.request.queryParameters["wait"]?.toLongOrNull() ?: 0).coerceIn(0, 30)
                if (wait > 0 && store.seq() <= since && !household.hasWake(d.id)) {
                    withTimeoutOrNull(wait * 1000) {
                        kotlinx.coroutines.flow.merge(changed, household.ticks).first { store.seq() > since || household.hasWake(d.id) }
                    }
                }
                // Events carry only ids: what changed is fetched through the calls that check access.
                call.json(JournalPage.serializer(), store.events(since).copy(wake = household.takeWakes(d.id).toList()))
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
                    call.parameters["id"].orEmpty().let { store.revokeDevice(it); household.forget(it) }
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
                household.forget(id)
                bump()
                call.respondText("{}", ContentType.Application.Json)
            }
            householdApi()
        }
    }

    // ---------------------------------------------------------------- the household's games

    /**
     * The household's games (see [HouseholdHost]): each device's list, leave to fetch a game from
     * another device, the game passing through when the two can't reach each other, requests one
     * device makes of another, and every device's transfers. Every call is signed by a device.
     */
    private fun Route.householdApi() {
        post("/library") {
            val d = device() ?: return@post
            val req = signedBody(d, DeviceLibrary.serializer(), packed = call.request.headers[GZIP_BODY] == "1") ?: return@post
            household.putLibrary(req.copy(device = d.id, name = d.name, platform = d.platform, entries = req.entries.take(MAX_LIBRARY)))
            household.wake(HouseholdHost.WAKE_LIBRARY, except = d.id, all = ::liveDevices)
            call.respondText("{}", ContentType.Application.Json)
        }
        post("/libraries") {
            val d = device() ?: return@post
            val req = signedBody(d, LibrariesKnown.serializer()) ?: return@post
            call.json(LibrariesPage.serializer(), household.libraries(req.have, d.id, liveDevices()))
        }
        post("/tickets") {
            val d = device() ?: return@post
            val req = signedBody(d, TicketRequest.serializer()) ?: return@post
            val source = store.device(req.source)?.takeIf { !it.revoked && it.id != d.id }
                ?: return@post call.fail(HttpStatusCode.NotFound, "That device isn't in this household.", "no-device")
            val lib = household.library(source.id) ?: return@post call.fail(HttpStatusCode.NotFound, "That device hasn't shared its games.", "not-shared")
            val entry = lib.entries.firstOrNull { it.game == req.game } ?: return@post call.fail(HttpStatusCode.NotFound, "That device doesn't have this game any more.", "no-game")
            val listed = entry.files.map { it.path }.toSet()
            if (req.files.isEmpty() || req.files.size > MAX_TICKET_FILES || req.files.any { it !in listed }) return@post call.fail(HttpStatusCode.BadRequest, "Those aren't this game's files.", "bad-files")
            val now = clock()
            tickets.values.removeIf { it.until < now }
            val ticket = PeerTicket(
                id = "tkt-" + SyncCrypto.token(12), requester = d.id, source = source.id, game = req.game, files = req.files,
                until = now + TICKET_MS, endpoint = lib.endpoint, relay = true,
            )
            tickets[ticket.id] = ticket
            val salt = SyncCrypto.token(16)
            val sealed = SyncCrypto.seal(PeerSigning.key(source.secret, ticket).toByteArray(), d.secret, salt)
            call.json(PeerTicket.serializer(), ticket.copy(sealedKey = sealed, salt = salt))
        }
        // A piece of a game, passed through: straight from the device when the host reaches it (it
        // usually sits at home with it), else the device sends it here and it goes on as it comes.
        get("/relay") {
            val d = device() ?: return@get
            val q = call.request.queryParameters
            val ticket = tickets[q["ticket"].orEmpty()]?.takeIf { it.requester == d.id && it.until > clock() }
                ?: return@get call.fail(HttpStatusCode.Forbidden, "That leave ran out. Ask again.", "ticket")
            val file = q["file"].orEmpty()
            val offset = q["offset"]?.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
            val length = (q["length"]?.toLongOrNull() ?: SyncApi.RELAY_PIECE).coerceIn(1, SyncApi.RELAY_PIECE)
            if (file !in ticket.files) return@get call.fail(HttpStatusCode.BadRequest, "That file isn't part of the leave.", "bad-files")
            val source = store.device(ticket.source)?.takeIf { !it.revoked } ?: return@get call.fail(HttpStatusCode.NotFound, "That device left the household.", "no-device")
            if (passDirect(ticket, source, file, offset, length)) return@get
            val relay = household.openRelay(ticket, file, offset, length)
            household.wake(HouseholdHost.WAKE_RELAY, listOf(source.id), all = ::liveDevices)
            val body = withTimeoutOrNull(HouseholdHost.RELAY_WAIT_MS) { relay.body.await() }
            if (body == null) {
                household.closeRelay(relay.ask.id)
                return@get call.fail(HttpStatusCode.ServiceUnavailable, "${source.name} didn't answer. Fuse needs to be open there.", "relay-wait")
            }
            try {
                call.response.headers.append(PEER_OFFSET, offset.toString())
                call.respondOutputStream(ContentType.Application.OctetStream) {
                    withContext(Dispatchers.IO) { copyAtMost(body.toInputStream(), this@respondOutputStream, length) }
                }
            } finally {
                household.closeRelay(relay.ask.id)
            }
        }
        get("/relays") { val d = device() ?: return@get; call.json(ListSerializer(RelayAsk.serializer()), household.relayAsks(d.id)) }
        put("/relays/{id}") {
            val id = call.parameters["id"].orEmpty()
            val d = device(bodyHash = "relay:$id") ?: return@put
            val relay = household.relay(id)?.takeIf { it.source == d.id && !it.body.isCompleted }
                ?: return@put call.fail(HttpStatusCode.NotFound, "Nobody is waiting for that any more.", "no-relay")
            relay.body.complete(call.receiveChannel())
            // The call stays open while the bytes go on to the device that asked.
            withTimeoutOrNull(10 * 60_000L) { relay.passed.await() }
            call.respondText("{}", ContentType.Application.Json)
        }
        post("/commands") {
            val d = device() ?: return@post
            val req = signedBody(d, DeviceCommand.serializer()) ?: return@post
            val target = store.device(req.target)?.takeIf { !it.revoked } ?: return@post call.fail(HttpStatusCode.NotFound, "That device isn't in this household.", "no-device")
            if (req.type !in COMMAND_TYPES) return@post call.fail(HttpStatusCode.BadRequest, "That isn't something one device can ask of another.", "bad-command")
            val made = household.addCommand(req.copy(from = d.id, fromName = d.name, target = target.id, title = req.title.take(200)))
            household.wake(HouseholdHost.WAKE_COMMANDS, all = ::liveDevices)
            call.json(DeviceCommand.serializer(), made)
        }
        get("/commands") { device() ?: return@get; call.json(Commands.serializer(), Commands(household.allCommands())) }
        get("/commands/inbox") { val d = device() ?: return@get; call.json(Commands.serializer(), Commands(household.pendingFor(d.id))) }
        post("/commands/{id}/ack") {
            val d = device() ?: return@post
            val req = signedBody(d, CommandAck.serializer()) ?: return@post
            val c = household.ack(d.id, call.parameters["id"].orEmpty(), req) ?: return@post call.fail(HttpStatusCode.NotFound, "No such request for this device.", "no-command")
            household.wake(HouseholdHost.WAKE_COMMANDS, all = ::liveDevices)
            call.json(DeviceCommand.serializer(), c)
        }
        delete("/commands/{id}") {
            val d = device() ?: return@delete
            val c = household.cancel(d.id, call.parameters["id"].orEmpty()) ?: return@delete call.fail(HttpStatusCode.NotFound, "That request is done or isn't yours.", "no-command")
            household.wake(HouseholdHost.WAKE_COMMANDS, all = ::liveDevices)
            call.json(DeviceCommand.serializer(), c)
        }
        post("/transfers") {
            val d = device() ?: return@post
            val req = signedBody(d, TransferSnapshot.serializer()) ?: return@post
            household.putSnapshot(req.copy(device = d.id, name = d.name))
            call.respondText("{}", ContentType.Application.Json)
        }
        get("/transfers") {
            val d = device() ?: return@get
            call.json(TransferSnapshots.serializer(), TransferSnapshots(household.snapshots(liveDevices() - d.id)))
        }
    }

    /**
     * Passes [file] on from [source] directly, when the host reaches it on the home network: true
     * once answered. False (nothing sent yet) when the device can't be reached that way.
     */
    private suspend fun RoutingContext.passDirect(ticket: PeerTicket, source: DeviceRecord, file: String, offset: Long, length: Long): Boolean {
        val endpoint = ticket.endpoint?.takeIf { it.port > 0 } ?: return false
        if ((unreachable[source.id] ?: 0) > clock()) return false
        val key = PeerSigning.key(source.secret, ticket)
        for (address in endpoint.addresses.take(4)) {
            val path = SyncApi.PEER_BASE + "/file?" + PeerFiles.query(ticket.game, file, offset, length)
            val time = clock()
            val nonce = SyncCrypto.token(18)
            val ok = runCatching {
                peerHttp.prepareGet("http://" + PeerFiles.hostPort(address, endpoint.port) + path) {
                    header(PeerSigning.TICKET, PeerSigning.header(ticket, json))
                    header(RequestSigning.TIME, time.toString())
                    header(RequestSigning.NONCE, nonce)
                    header(RequestSigning.SIGNATURE, PeerSigning.sign(key, ticket.game, file, offset, length, time, nonce))
                }.execute { resp ->
                    if (resp.status != HttpStatusCode.OK) return@execute false
                    resp.headers[PEER_SIZE]?.let { call.response.headers.append(PEER_SIZE, it) }
                    call.response.headers.append(PEER_OFFSET, offset.toString())
                    val input = resp.bodyAsChannel()
                    call.respondOutputStream(ContentType.Application.OctetStream) {
                        withContext(Dispatchers.IO) { copyAtMost(input.toInputStream(), this@respondOutputStream, length) }
                    }
                    true
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        unreachable[source.id] = clock() + UNREACHABLE_MS
        return false
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
    private suspend fun <T> RoutingContext.signedBody(d: DeviceRecord, serializer: KSerializer<T>, packed: Boolean = false): T? {
        val raw = lastBodies.remove(call) ?: ByteArray(0)
        return runCatching {
            val bytes = if (packed) Gzip.unpack(raw, MAX_JSON) else raw
            json.decodeFromString(serializer, bytes.decodeToString())
        }.getOrElse {
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
        val h = call.request.headers
        val proxied = PROXY_HEADERS.any { h[it] != null }
        return loopback() && !proxied
    }

    /** The call's connection comes from this computer (Fuse itself, or a tunnel or proxy running here). */
    private fun RoutingContext.loopback(): Boolean =
        call.request.origin.remoteAddress.let { it == "127.0.0.1" || it == "::1" || it == "0:0:0:0:0:0:0:1" || it == "localhost" || it.startsWith("127.") }

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

    /**
     * Who is calling, for counting calls and wrong passwords: the caller a tunnel or proxy on this
     * computer names, else the address the call came from. A name in the headers of a call from
     * anywhere else is never believed (anyone could write one, to dodge a wait).
     */
    private fun RoutingContext.caller(): String {
        val from = call.request.origin.remoteAddress
        if (!loopback()) return from
        val h = call.request.headers
        return h["CF-Connecting-IP"] ?: h["True-Client-IP"] ?: h["X-Real-IP"] ?: h["X-Forwarded-For"]?.substringBefore(',')?.trim()?.ifEmpty { null } ?: from
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
        // Each caller a tunnel carries counts on its own, so devices from away don't share one allowance.
        val who = caller()
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

    /**
     * A Hub page: never cached as is, but a Refresh that would bring the very same page answers
     * 304, and a big one goes gzipped to a browser that takes it.
     */
    private suspend fun ApplicationCall.html(text: String) {
        val bytes = text.encodeToByteArray()
        val tag = "\"" + SyncCrypto.sha256(bytes.inputStream()).take(32) + "\""
        response.headers.append("Cache-Control", "no-cache")
        response.headers.append(io.ktor.http.HttpHeaders.ETag, tag)
        if (request.headers[io.ktor.http.HttpHeaders.IfNoneMatch]?.split(',')?.any { it.trim() == tag } == true) {
            return respondBytes(ByteArray(0), status = HttpStatusCode.NotModified)
        }
        val gzip = bytes.size >= GZIP_FROM && request.headers[io.ktor.http.HttpHeaders.AcceptEncoding]?.contains("gzip") == true
        if (!gzip) return respondBytes(bytes, ContentType.Text.Html.withCharset(Charsets.UTF_8))
        response.headers.append(io.ktor.http.HttpHeaders.ContentEncoding, "gzip")
        response.headers.append(io.ktor.http.HttpHeaders.Vary, io.ktor.http.HttpHeaders.AcceptEncoding)
        respondBytes(Gzip.pack(bytes), ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }

    private suspend fun <T> ApplicationCall.json(serializer: KSerializer<T>, value: T) {
        val text = json.encodeToString(serializer, value)
        // Gzipped when asked for and worth it: a profile's records for a big library are a lot of
        // text, and from outside home every byte goes the long way.
        val gzip = text.length >= GZIP_FROM && request.headers[io.ktor.http.HttpHeaders.AcceptEncoding]?.contains("gzip") == true
        if (!gzip) return respondText(text, ContentType.Application.Json)
        response.headers.append(io.ktor.http.HttpHeaders.ContentEncoding, "gzip")
        response.headers.append(io.ktor.http.HttpHeaders.Vary, io.ktor.http.HttpHeaders.AcceptEncoding)
        respondBytes(Gzip.pack(text.encodeToByteArray()), ContentType.Application.Json)
    }

    private suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String, code: String) =
        respondText(json.encodeToString(ApiError.serializer(), ApiError(message, code)), ContentType.Application.Json, status)

    companion object {
        /** Answers this long or longer are gzipped for a caller that takes it. */
        const val GZIP_FROM = 1_024

        /** The most profiles one reorder names. */
        const val MAX_ORDER = 200

        /** The largest JSON call: a profile's records for a big library fit easily. */
        const val MAX_JSON = 16L * 1024 * 1024

        /**
         * The largest single file. Only saves, save states and memory cards are kept (never games
         * themselves), and the biggest of those (a PS3 or Switch save, a state with its screenshot)
         * stays well under this.
         */
        const val MAX_BLOB = 1L * 1024 * 1024 * 1024

        const val CALLS_PER_MINUTE = 1_200

        /** A device's list says it was sent gzipped (signed as sent). */
        const val GZIP_BODY = "X-Fuse-Gzip"

        /** The answer to a piece of a game: where it starts, and the whole file's size. */
        const val PEER_OFFSET = "X-Fuse-Offset"
        const val PEER_SIZE = "X-Fuse-Size"
        const val PEER_SHA1 = "X-Fuse-Sha1"

        /** The most games one device lists. */
        const val MAX_LIBRARY = 50_000

        /** The most files one leave covers (a PS3 game's folder can hold thousands). */
        const val MAX_TICKET_FILES = 20_000

        /** How long leave to fetch a game lasts; a longer download asks again. */
        const val TICKET_MS = 60 * 60_000L

        /** How long a device the host couldn't reach directly is left to send its pieces itself. */
        const val UNREACHABLE_MS = 60_000L

        private val COMMAND_TYPES = setOf(DeviceCommand.FETCH, DeviceCommand.ROMM_UPLOAD, DeviceCommand.TRANSFER)

        /** Copies at most [max] bytes from [input] to [out]. */
        internal fun copyAtMost(input: java.io.InputStream, out: java.io.OutputStream, max: Long): Long {
            val buf = ByteArray(64 * 1024)
            var left = max
            var total = 0L
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
                total += n
            }
            return total
        }

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
