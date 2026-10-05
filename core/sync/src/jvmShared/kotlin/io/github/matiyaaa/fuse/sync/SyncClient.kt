package io.github.matiyaaa.fuse.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** What a device keeps to talk to its host: the host's id and addresses, and its own secret. */
@kotlinx.serialization.Serializable
data class HostLink(
    val hostId: String,
    val hostName: String,
    val deviceId: String,
    val deviceSecret: String,
    /** The home address (found on the network, or typed): preferred whenever it answers. */
    val localAddress: String? = null,
    /** The address from outside (HTTPS behind a reverse proxy, or a VPN address). */
    val remoteAddress: String? = null,
)

/**
 * How Fuse Sync makes its HTTP clients. CIO by default; Android sets one over OkHttp, so the host
 * is reached with the system's own TLS (an https tunnel such as Cloudflare's) as the rest of Fuse is.
 */
object SyncHttp {
    @Volatile var factory: (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient = { block -> HttpClient(CIO, block) }

    fun client(block: io.ktor.client.HttpClientConfig<*>.() -> Unit = {}): HttpClient = factory(block)
}

/** A request to join on its way: where it went, and what both sides need to finish it. */
class JoinSession internal constructor(
    internal val base: String,
    internal val deviceId: String,
    val ticket: JoinTicket,
    internal val secret: String,
    /** The six digits this device shows, and whoever lets it in sees. */
    val match: String,
)

/** A host's answer that wasn't a success: what it said, its code, and the HTTP status. */
class SyncException(message: String, val code: String, val status: Int) : IOException(message)

/**
 * Something other than a Fuse host answered (a reverse proxy or tunnel whose host is off, a Wi-Fi
 * sign-in page, another program on the address): to Fuse Sync, the host simply wasn't reached.
 */
internal class NotTheHost(status: Int) : IOException("Something other than the host answered ($status).")

/** A failed answer as Fuse Sync takes it: the host's own error, or [NotTheHost] when it wasn't the host's. */
internal fun hostError(status: Int, text: String, json: Json): IOException {
    val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull() ?: return NotTheHost(status)
    return SyncException(err.error, err.code, status)
}

/**
 * A device's side of Fuse Sync: every call signed with the device's secret, sent to the home
 * address while it answers and to the address from outside otherwise ([route] says which is in
 * use; a call that fails at home tries outside before giving up, and the next call tries home
 * again first). Files go up and come down checked by their hash.
 */
class SyncClient(
    @Volatile var link: HostLink,
    private val http: HttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var route: Route? = null
        private set

    private fun bases(): List<Pair<Route, String>> = buildList {
        link.localAddress?.let { add(Route.LOCAL to normalise(it)) }
        link.remoteAddress?.let { add(Route.REMOTE to normalise(it)) }
    }

    private suspend fun <T> call(
        method: HttpMethod,
        path: String,
        body: ByteArray?,
        out: KSerializer<T>?,
        bodyHash: String? = null,
        content: OutgoingContent? = null,
        raw: (suspend (io.ktor.client.statement.HttpResponse) -> T)? = null,
    ): T {
        val targets = bases()
        if (targets.isEmpty()) throw SyncException("This device has no address for its host.", "no-address", 0)
        var last: Exception? = null
        for ((r, base) in targets) {
            try {
                val time = clock()
                val nonce = SyncCrypto.token(18)
                val full = SyncApi.BASE + path
                val sig = if (bodyHash != null) {
                    SyncCrypto.hmac(SyncCrypto.decode(link.deviceSecret), RequestSigning.message(method.value, full, time, nonce, bodyHash))
                } else {
                    RequestSigning.sign(link.deviceSecret, method.value, full, time, nonce, body ?: ByteArray(0))
                }
                val build: HttpRequestBuilder.() -> Unit = {
                    this.method = method
                    url(base + full)
                    header(RequestSigning.DEVICE, link.deviceId)
                    header(RequestSigning.TIME, time.toString())
                    header(RequestSigning.NONCE, nonce)
                    header(RequestSigning.SIGNATURE, sig)
                    header("X-Fuse-Route", r.name)
                    when {
                        content != null -> setBody(content)
                        body != null -> {
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    }
                }
                if (raw != null) {
                    return http.prepareRequest(build).execute { resp ->
                        if (!resp.status.isSuccess()) throw hostError(resp.status.value, resp.bodyAsText(), json)
                        route = r
                        raw(resp)
                    }
                }
                val resp = http.request(build)
                val text = resp.bodyAsText()
                if (!resp.status.isSuccess()) throw hostError(resp.status.value, text, json)
                route = r
                @Suppress("UNCHECKED_CAST")
                return if (out == null) Unit as T else json.decodeFromString(out, text)
            } catch (e: SyncException) {
                // The host answered: no point asking it again by another way.
                throw e
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        route = null
        throw SyncException("The host didn't answer. ${last?.message ?: ""}".trim(), "offline", 0)
    }

    private suspend fun <T> get(path: String, out: KSerializer<T>): T = call(HttpMethod.Get, path, null, out)
    private suspend fun <B, T> send(method: HttpMethod, path: String, body: B, inS: KSerializer<B>, out: KSerializer<T>): T =
        call(method, path, json.encodeToString(inS, body).toByteArray(), out)

    // ---------------------------------------------------------------- calls

    suspend fun status(): HostStatus = get("/status", HostStatus.serializer())
    suspend fun profiles(): List<ProfileInfo> = get("/profiles", ListSerializer(ProfileInfo.serializer()))
    suspend fun createProfile(p: NewProfile): ProfileInfo = send(HttpMethod.Post, "/profiles", p, NewProfile.serializer(), ProfileInfo.serializer())
    suspend fun changeProfile(id: String, c: ProfileChange): ProfileInfo = send(HttpMethod.Patch, "/profiles/$id", c, ProfileChange.serializer(), ProfileInfo.serializer())
    suspend fun deleteProfile(id: String) = call<Unit>(HttpMethod.Delete, "/profiles/$id", ByteArray(0), null)
    suspend fun openProfile(id: String, pin: String?): ProfileTicket = send(HttpMethod.Post, "/profiles/$id/open", UnlockRequest(pin), UnlockRequest.serializer(), ProfileTicket.serializer())
    suspend fun meta(profile: String): MetaState = get("/profiles/$profile/meta", MetaState.serializer())
    suspend fun report(profile: String): ProfileReport = get("/profiles/$profile/report", ProfileReport.serializer())
    suspend fun pushMeta(profile: String, meta: ProfileMeta): MetaState = send(HttpMethod.Post, "/profiles/$profile/meta", MetaPush(meta), MetaPush.serializer(), MetaState.serializer())
    suspend fun heads(profile: String): Heads = get("/profiles/$profile/heads", Heads.serializer())
    suspend fun revisions(profile: String, game: String? = null, kind: SaveKind? = null): List<SaveRevision> {
        val q = listOfNotNull(game?.let { "game=" + java.net.URLEncoder.encode(it, "UTF-8") }, kind?.let { "kind=${it.name}" }).joinToString("&")
        return get("/profiles/$profile/revisions" + if (q.isEmpty()) "" else "?$q", ListSerializer(SaveRevision.serializer()))
    }
    suspend fun pin(profile: String, revision: String, pinned: Boolean) =
        send(HttpMethod.Post, "/profiles/$profile/revisions/$revision/pin", pinned, Boolean.serializer(), kotlinx.serialization.json.JsonObject.serializer())
    suspend fun events(since: Long, waitSeconds: Int = 0): JournalPage = get("/events?since=$since&wait=$waitSeconds", JournalPage.serializer())
    suspend fun devices(): List<DeviceInfo> = get("/devices", ListSerializer(DeviceInfo.serializer()))
    suspend fun resolveGames(games: List<List<String>>): List<String> =
        send(HttpMethod.Post, "/games/resolve", GameClaims(games), GameClaims.serializer(), ResolvedGames.serializer()).ids
    suspend fun presence(): List<Presence> = get("/presence", ListSerializer(Presence.serializer()))
    suspend fun notePresence(note: PresenceNote) = send(HttpMethod.Post, "/presence", note, PresenceNote.serializer(), kotlinx.serialization.json.JsonObject.serializer())
    suspend fun joins(): List<JoinAsk> = get("/joins", ListSerializer(JoinAsk.serializer()))
    suspend fun answerJoin(id: String, allow: Boolean) = send(HttpMethod.Post, "/joins/$id", JoinAnswer(allow), JoinAnswer.serializer(), kotlinx.serialization.json.JsonObject.serializer())
    /** A code for adding another device, from this one (any device already in may make one). */
    suspend fun pairingCode(): String = call(HttpMethod.Post, "/pairing", ByteArray(0), String.serializer())
    suspend fun sharedGames(): SharedGames = get("/shared-games", SharedGames.serializer())
    suspend fun setShared(game: String, shared: Boolean, from: String?): SharedGames =
        send(HttpMethod.Post, "/shared-games", SharedChange(game, shared, from), SharedChange.serializer(), SharedGames.serializer())
    suspend fun renameSelf(name: String): DeviceInfo = send(HttpMethod.Patch, "/devices/${link.deviceId}", DeviceChange(name = name), DeviceChange.serializer(), DeviceInfo.serializer())
    suspend fun unlinkSelf() = call<Unit>(HttpMethod.Delete, "/devices/${link.deviceId}", ByteArray(0), null)

    suspend fun missing(hashes: List<String>): List<String> =
        send(HttpMethod.Post, "/blobs/missing", MissingBlobs(hashes), MissingBlobs.serializer(), MissingBlobs.serializer()).hashes

    /** Sends [file] as [hash] (its SHA-256); the host checks it as it arrives. */
    suspend fun upload(hash: String, file: File) {
        val content = object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Application.OctetStream
            override val contentLength = file.length()
            override suspend fun writeTo(channel: ByteWriteChannel) {
                withContext(Dispatchers.IO) {
                    file.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            channel.writeFully(buf, 0, n)
                        }
                    }
                }
            }
        }
        call<Unit>(HttpMethod.Put, "/blobs/$hash", null, null, bodyHash = hash, content = content)
    }

    /**
     * Fetches [hash] into [store] (checked against its hash on the way in): nothing is kept when
     * the bytes don't match, and the next try starts again.
     */
    suspend fun download(hash: String, store: ContentStore) {
        if (store.has(hash)) return
        call<String>(HttpMethod.Get, "/blobs/$hash", null, null, raw = { resp ->
            withContext(Dispatchers.IO) { store.put(resp.bodyAsChannel().toInputStream(), expected = hash) }
        })
    }

    /**
     * Offers [revision] to the host: first every file the host lacks, then the revision itself.
     * Files come from [store] (where the device keeps what it captured).
     */
    suspend fun push(profile: String, revision: SaveRevision, store: ContentStore): RevisionResult {
        val need = missing(revision.manifest.files.map { it.hash })
        for (h in need) upload(h, store.fileOf(h))
        return send(HttpMethod.Post, "/profiles/$profile/revisions", RevisionPush(revision), RevisionPush.serializer(), RevisionResult.serializer())
    }

    companion object {
        fun defaultClient(): HttpClient = SyncHttp.client {
            install(HttpTimeout) {
                connectTimeoutMillis = 4_000
                requestTimeoutMillis = 10 * 60_000
                socketTimeoutMillis = 60_000
            }
            expectSuccess = false
        }

        /**
         * `host:port` or a URL, as a base URL without a trailing slash. A plain address at home
         * (an IP, a `.local` or `.lan` name, a name without dots) gets `http://`; a name on the
         * internet (`sync.example.com`, as a tunnel gives) gets `https://`.
         */
        fun normalise(address: String): String {
            val a = address.trim().trimEnd('/')
            if (a.startsWith("http://") || a.startsWith("https://")) return a
            val host = a.substringBefore('/').substringBeforeLast(':').removePrefix("[").removeSuffix("]").lowercase()
            val home = host.all { it.isDigit() || it == '.' } || ':' in host || '.' !in host ||
                listOf(".local", ".lan", ".home", ".internal", ".localdomain").any { host.endsWith(it) }
            return if (home) "http://$a" else "https://$a"
        }

        /**
         * Pairs this device with the host at [address] using [code] from the host's screen.
         * Returns the link to keep (its secret opened with the code), or throws why not.
         */
        suspend fun pair(
            address: String,
            code: String,
            deviceId: String,
            deviceName: String,
            platform: String,
            http: HttpClient = defaultClient(),
        ): HostLink {
            val json = Json { ignoreUnknownKeys = true }
            val base = normalise(address)
            val resp = http.request {
                method = HttpMethod.Post
                url(base + SyncApi.BASE + "/pair")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PairRequest.serializer(), PairRequest(code.trim().uppercase(), deviceId, deviceName, platform)))
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) throw hostError(resp.status.value, text, json)
            val r = json.decodeFromString(PairResponse.serializer(), text)
            val secret = SyncCrypto.open(r.sealedSecret, code.trim().uppercase(), r.salt) ?: throw SyncException("The host's answer couldn't be opened with that code.", "seal", 0)
            val home = !base.startsWith("https://")
            return HostLink(r.hostId, r.hostName, deviceId, secret.decodeToString(), localAddress = if (home) base else null, remoteAddress = if (home) null else base)
        }

        /**
         * Asks the host at [address] to let this device in without a code: sends this device's
         * half of a key exchange and returns the request, with the number both screens show.
         */
        suspend fun askToJoin(address: String, deviceId: String, deviceName: String, platform: String, http: HttpClient = defaultClient()): JoinSession {
            val json = Json { ignoreUnknownKeys = true }
            val base = normalise(address)
            val keys = SyncCrypto.joinKeys()
            val mine = SyncCrypto.publicKeyText(keys)
            val resp = http.request {
                method = HttpMethod.Post
                url(base + SyncApi.BASE + "/join")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JoinRequest.serializer(), JoinRequest(deviceId, deviceName, platform, mine)))
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) throw hostError(resp.status.value, text, json)
            val ticket = json.decodeFromString(JoinTicket.serializer(), text)
            val secret = SyncCrypto.joinSecret(keys, ticket.publicKey) ?: throw SyncException("The host's answer wasn't a key.", "bad-key", 0)
            return JoinSession(base, deviceId, ticket, secret, SyncCrypto.joinMatch(mine, ticket.publicKey))
        }

        /**
         * Where [session] stands: null while it waits, the link once someone let this device in.
         * Throws when it was turned away or ran out.
         */
        suspend fun joinResult(session: JoinSession, http: HttpClient = defaultClient()): HostLink? {
            val json = Json { ignoreUnknownKeys = true }
            val resp = http.request {
                method = HttpMethod.Get
                url(session.base + SyncApi.BASE + "/join/" + session.ticket.id)
            }
            if (!resp.status.isSuccess()) {
                // A moment's trouble on the way (too many calls, a proxy between) isn't an answer: ask again.
                val e = hostError(resp.status.value, resp.bodyAsText(), json)
                if (e is SyncException && e.code != "rate") throw e
                return null
            }
            val state = runCatching { json.decodeFromString(JoinState.serializer(), resp.bodyAsText()) }.getOrNull() ?: return null
            return linkFrom(session, state)
        }

        private fun linkFrom(session: JoinSession, state: JoinState): HostLink? = when (state.state) {
            JoinState.WAITING -> null
            JoinState.DENIED -> throw SyncException("${session.ticket.hostName} didn't let this device in.", "denied", 403)
            JoinState.ALLOWED -> {
                val secret = SyncCrypto.open(state.sealedSecret.orEmpty(), session.secret, state.salt.orEmpty())
                    ?: throw SyncException("The host's answer couldn't be opened.", "seal", 0)
                val home = !session.base.startsWith("https://")
                HostLink(session.ticket.hostId, session.ticket.hostName, session.deviceId, secret.decodeToString(),
                    localAddress = if (home) session.base else null, remoteAddress = if (home) null else session.base)
            }
            else -> throw SyncException("Nobody let this device in in time. Ask again.", "gone", 410)
        }

        /**
         * Joins with the host's account, for when nobody is at a screen to let this device in. The
         * password is stretched here and proves itself over the secret only this request shares
         * with the host: it never travels.
         */
        suspend fun joinWithAccount(session: JoinSession, username: String, password: String, http: HttpClient = defaultClient()): HostLink {
            val json = Json { ignoreUnknownKeys = true }
            val salt = session.ticket.accountSalt ?: throw SyncException("${session.ticket.hostName} has no account. Ask someone there to let this device in.", "no-account", 403)
            val key = SyncCrypto.stretch(password, salt, session.ticket.accountIterations)
            val proof = SyncCrypto.hmac(key, session.secret)
            val resp = http.request {
                method = HttpMethod.Post
                url(session.base + SyncApi.BASE + "/join/" + session.ticket.id + "/account")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JoinWithAccount.serializer(), JoinWithAccount(username.trim(), proof)))
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                val e = hostError(resp.status.value, text, json)
                throw e as? SyncException ?: SyncException("The host didn't answer. Try again in a moment.", "offline", resp.status.value)
            }
            return linkFrom(session, json.decodeFromString(JoinState.serializer(), text)) ?: throw SyncException("The host didn't let this device in.", "denied", 403)
        }

        /** Says hello to the host at [address] without signing: who it is, or null when nothing answers. */
        suspend fun hello(address: String, http: HttpClient = defaultClient()): HostHello? = runCatching {
            val resp = http.request {
                method = HttpMethod.Get
                url(normalise(address) + SyncApi.BASE + "/hello")
            }
            if (!resp.status.isSuccess()) null else Json { ignoreUnknownKeys = true }.decodeFromString(HostHello.serializer(), resp.bodyAsText())
        }.getOrNull()
    }
}

private fun HttpRequestBuilder.url(full: String) {
    url.takeFrom(full)
}
