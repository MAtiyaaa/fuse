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

/** A host's answer that wasn't a success: what it said, its code, and the HTTP status. */
class SyncException(message: String, val code: String, val status: Int) : IOException(message)

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
                        if (!resp.status.isSuccess()) throw failure(resp.status.value, resp.bodyAsText())
                        route = r
                        raw(resp)
                    }
                }
                val resp = http.request(build)
                val text = resp.bodyAsText()
                if (!resp.status.isSuccess()) throw failure(resp.status.value, text)
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

    private fun failure(status: Int, text: String): SyncException {
        val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
        return SyncException(err?.error ?: "The host said no ($status).", err?.code ?: "", status)
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
        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 4_000
                requestTimeoutMillis = 10 * 60_000
                socketTimeoutMillis = 60_000
            }
            expectSuccess = false
        }

        /** `host:port` or a URL, as a base URL without a trailing slash; plain addresses get `http://`. */
        fun normalise(address: String): String {
            val a = address.trim().trimEnd('/')
            return if (a.startsWith("http://") || a.startsWith("https://")) a else "http://$a"
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
            if (!resp.status.isSuccess()) {
                val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
                throw SyncException(err?.error ?: "Pairing failed (${resp.status.value}).", err?.code ?: "", resp.status.value)
            }
            val r = json.decodeFromString(PairResponse.serializer(), text)
            val secret = SyncCrypto.open(r.sealedSecret, code.trim().uppercase(), r.salt) ?: throw SyncException("The host's answer couldn't be opened with that code.", "seal", 0)
            val home = !base.startsWith("https://")
            return HostLink(r.hostId, r.hostName, deviceId, secret.decodeToString(), localAddress = if (home) base else null, remoteAddress = if (home) null else base)
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
