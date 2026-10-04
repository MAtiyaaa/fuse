package io.github.matiyaaa.fuse.jellyfin

import io.github.matiyaaa.fuse.data.settings.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How Fuse reaches the server. */
enum class ConnectionMode { AUTO, LOCAL, REMOTE }

/** Which way Fuse is reaching the server right now. */
enum class Route { LOCAL, REMOTE }

/** Where things stand with the server, for the settings page, the banner and the screens. */
data class JellyfinState(
    val enabled: Boolean = false,
    /** Signed in: the user and server. */
    val account: Account? = null,
    /** The way in use; null when offline or not set up. */
    val route: Route? = null,
    val base: String? = null,
    /** True when the server can't be reached and kept pages are shown. */
    val offline: Boolean = false,
    /** True when the server signed this device out: sign in again. */
    val authRequired: Boolean = false,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val checking: Boolean = false,
)

/** The addresses and mode from Settings, Addons, Jellyfin. */
data class JellyfinConnection(
    val mode: ConnectionMode = ConnectionMode.AUTO,
    val localAddress: String = "",
    val remoteAddress: String = "",
)

/** A server that answered on the local network. */
data class DiscoveredServer(val name: String, val address: String, val id: String?)

/**
 * Jellyfin for the rest of Fuse: signing in (the token in the secret store, the password never),
 * reaching the server the right way (on the home network when it can, else from outside, and back
 * home when home returns, never mid-stream), and the media calls the screens make, with answers
 * kept for working offline. When Jellyfin is off in Settings nothing here runs or asks anything.
 */
class JellyfinService(
    val client: JellyfinClient,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
    disk: JellyfinDiskCache? = null,
    private val discovery: ServerDiscovery? = null,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val stateFlow = MutableStateFlow(JellyfinState())
    val state: StateFlow<JellyfinState> = stateFlow

    private val cache = ResponseCache(disk, clock)
    private val lock = Mutex()
    private var connection = JellyfinConnection()
    private var watcher: Job? = null
    private var lastCheck = 0L

    init {
        client.cache = cache
        client.freshForMs = FRESH_MS
        client.onReachable = { ok -> if (stateFlow.value.offline == ok) stateFlow.update { it.copy(offline = !ok) } }
    }

    /** Settings changed (on or off, the mode, the addresses): reconnects as needed. */
    fun configure(enabled: Boolean, connection: JellyfinConnection) {
        val changed = this.connection != connection || stateFlow.value.enabled != enabled
        this.connection = connection
        stateFlow.update { it.copy(enabled = enabled) }
        if (!enabled) {
            watcher?.cancel()
            watcher = null
            stateFlow.update { JellyfinState(enabled = false, account = it.account) }
            return
        }
        if (changed || watcher == null) {
            watcher?.cancel()
            watcher = scope.launch {
                loadAccount()
                while (true) {
                    runCatching { reconnect() }
                    delay(HEALTH_EVERY_MS)
                }
            }
        }
    }

    /** Looks for servers on the local network (two seconds). */
    suspend fun discover(): List<DiscoveredServer> = discovery?.discover(DISCOVERY_MS).orEmpty()

    /** Checks one address: the server's name and version, or why not. */
    suspend fun test(address: String): Result<Pair<String, ServerInfo>> {
        var last: Throwable? = null
        for (base in JellyfinClient.candidates(address)) {
            try {
                return Result.success(base to client.publicInfo(base))
            } catch (e: JellyfinException) {
                last = e
            }
        }
        return Result.failure(last ?: JellyfinException("Enter an address.", JellyfinException.Kind.NETWORK))
    }

    /** Signs in on whichever address answers (local first), and keeps the token. */
    suspend fun signIn(username: String, password: String): Result<Account> = runCatching {
        val bases = addressesInOrder()
        if (bases.isEmpty()) throw JellyfinException("Enter the server's address first.", JellyfinException.Kind.NETWORK)
        var last: Throwable? = null
        for ((route, address) in bases) {
            for (base in JellyfinClient.candidates(address)) {
                try {
                    val info = client.publicInfo(base)
                    val signed = client.signIn(base, username, password)
                    val account = signed.copy(serverName = info.name, serverId = signed.serverId ?: info.id)
                    saveAccount(account)
                    stateFlow.update { it.copy(account = account, route = route, base = base, offline = false, authRequired = false, serverName = info.name, serverVersion = info.version) }
                    cache.clear()
                    return@runCatching account
                } catch (e: JellyfinException) {
                    last = e
                    // The right server said no: no point trying it another way.
                    if (e.kind == JellyfinException.Kind.AUTH) throw e
                }
            }
        }
        throw last ?: JellyfinException("The server can't be reached.", JellyfinException.Kind.NETWORK)
    }

    suspend fun signOut() {
        val s = stateFlow.value
        val a = s.account
        val b = s.base
        if (a != null && b != null) client.signOut(b, a)
        for (k in listOf(TOKEN, USER_ID, USER_NAME, SERVER_ID, SERVER_NAME)) secrets.remove(k)
        cache.clear()
        stateFlow.update { JellyfinState(enabled = it.enabled) }
    }

    /** The route to use now and the account, or an error the screens can show. */
    suspend fun session(): Pair<String, Account> {
        val s = stateFlow.value
        val a = s.account ?: throw JellyfinException("Sign in to Jellyfin in Settings, Addons, Jellyfin.", JellyfinException.Kind.AUTH)
        val base = s.base ?: run {
            reconnect()
            stateFlow.value.base
        }
        // Offline, kept answers are still used: any address will do for the cache key.
        return (base ?: lastBase ?: connection.remoteAddress.ifBlank { connection.localAddress }) to a
    }

    private var lastBase: String? = null

    /** Picks the way in: home first in Auto, and back home as soon as home answers again. */
    suspend fun reconnect(force: Boolean = false): Unit = lock.withLock {
        if (!stateFlow.value.enabled) return
        if (!force && clock() - lastCheck < MIN_CHECK_MS && stateFlow.value.base != null) return
        lastCheck = clock()
        stateFlow.update { it.copy(checking = true) }
        var found: Triple<Route, String, ServerInfo>? = null
        for ((route, address) in addressesInOrder()) {
            for (base in JellyfinClient.candidates(address)) {
                val info = runCatching { client.publicInfo(base, if (route == Route.LOCAL) LOCAL_TIMEOUT_MS else REMOTE_TIMEOUT_MS) }.getOrNull() ?: continue
                found = Triple(route, base, info)
                break
            }
            if (found != null) break
        }
        stateFlow.update {
            if (found == null) {
                it.copy(route = null, base = null, offline = it.account != null, checking = false)
            } else {
                lastBase = found.second
                it.copy(route = found.first, base = found.second, offline = false, checking = false, serverName = found.third.name ?: it.serverName, serverVersion = found.third.version)
            }
        }
        val a = stateFlow.value.account
        val b = stateFlow.value.base
        // A quiet check that the token still works.
        if (a != null && b != null) {
            runCatching { client.views(b, a) }.onFailure { e ->
                if (e is JellyfinException && e.kind == JellyfinException.Kind.AUTH) stateFlow.update { it.copy(authRequired = true) }
            }
        }
    }

    /** After a stream failed: the other route if there is one that answers. */
    suspend fun switchRoute(): Boolean {
        val current = stateFlow.value.route ?: return false
        val other = addressesInOrder().firstOrNull { it.first != current } ?: return false
        for (base in JellyfinClient.candidates(other.second)) {
            val info = runCatching { client.publicInfo(base, REMOTE_TIMEOUT_MS) }.getOrNull() ?: continue
            stateFlow.update { it.copy(route = other.first, base = base, offline = false, serverName = info.name ?: it.serverName) }
            lastBase = base
            return true
        }
        return false
    }

    private fun addressesInOrder(): List<Pair<Route, String>> {
        val local = connection.localAddress.trim().takeIf { it.isNotEmpty() }?.let { Route.LOCAL to it }
        val remote = connection.remoteAddress.trim().takeIf { it.isNotEmpty() }?.let { Route.REMOTE to it }
        return when (connection.mode) {
            ConnectionMode.AUTO -> listOfNotNull(local, remote)
            ConnectionMode.LOCAL -> listOfNotNull(local)
            ConnectionMode.REMOTE -> listOfNotNull(remote)
        }
    }

    private suspend fun loadAccount() {
        val token = secrets.get(TOKEN) ?: return
        val userId = secrets.get(USER_ID) ?: return
        val a = Account(secrets.get(SERVER_ID), secrets.get(SERVER_NAME), userId, secrets.get(USER_NAME), token)
        stateFlow.update { it.copy(account = a, serverName = a.serverName ?: it.serverName) }
    }

    private suspend fun saveAccount(a: Account) {
        secrets.put(TOKEN, a.token)
        secrets.put(USER_ID, a.userId)
        a.userName?.let { secrets.put(USER_NAME, it) }
        a.serverId?.let { secrets.put(SERVER_ID, it) }
        a.serverName?.let { secrets.put(SERVER_NAME, it) }
    }

    // Media -----------------------------------------------------------------------------------------

    /** Runs a call on the route in use; marks the account as signed out when the server says so. */
    suspend fun <T> call(block: suspend (base: String, account: Account) -> T): T {
        val (base, account) = session()
        try {
            return block(base, account)
        } catch (e: JellyfinException) {
            if (e.kind == JellyfinException.Kind.AUTH) stateFlow.update { it.copy(authRequired = true) }
            throw e
        }
    }

    /** The Jellyfin home: what to continue, what's next, what's new, then each library. */
    suspend fun home(): List<Shelf> = call { base, a ->
        val views = client.views(base, a)
        buildList {
            runCatching { client.resume(base, a) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { add(Shelf("continue", "Continue watching", ShelfKind.CONTINUE, it)) }
            runCatching { client.nextUp(base, a) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { add(Shelf("nextup", "Next up", ShelfKind.NEXT_UP, it)) }
            for (v in views) {
                val kind = v.library
                val title = when (kind) {
                    LibraryKind.MOVIES -> "New in ${v.name}"
                    LibraryKind.SHOWS -> "New in ${v.name}"
                    LibraryKind.MUSIC -> "New in ${v.name}"
                    else -> "New in ${v.name}"
                }
                if (kind == LibraryKind.COLLECTIONS) continue
                val items = runCatching { client.latest(base, a, v.id, limit = 20) }.getOrNull().orEmpty()
                if (items.isNotEmpty()) add(Shelf("latest.${v.id}", title, if (kind == LibraryKind.MUSIC) ShelfKind.MUSIC else ShelfKind.LATEST, items, v.id))
            }
            runCatching { client.query(base, a, types = "Movie,Series,Episode,MusicAlbum", filter = MediaFilter.FAVORITES, sort = MediaSort.NAME, limit = 30) }
                .getOrNull()?.items?.takeIf { it.isNotEmpty() }?.let { add(Shelf("favorites", "Favourites", ShelfKind.FAVORITES, it)) }
            views.firstOrNull { it.library == LibraryKind.COLLECTIONS }?.let { c ->
                runCatching { client.query(base, a, parentId = c.id, limit = 30) }.getOrNull()?.items?.takeIf { it.isNotEmpty() }
                    ?.let { add(Shelf("collections", "Collections", ShelfKind.COLLECTIONS, it, c.id)) }
            }
        }
    }

    suspend fun libraries(): List<MediaItem> = call { b, a -> client.views(b, a) }

    suspend fun page(
        parentId: String?,
        types: String?,
        sort: MediaSort,
        filter: MediaFilter,
        start: Int,
        limit: Int = PAGE,
    ): MediaPage = call { b, a -> client.query(b, a, parentId = parentId, types = types, sort = sort, filter = filter, start = start, limit = limit) }

    suspend fun item(id: String): MediaItem = call { b, a -> client.item(b, a, id) }

    suspend fun seasons(seriesId: String): List<MediaItem> = call { b, a -> client.seasons(b, a, seriesId) }

    suspend fun episodes(seriesId: String, seasonId: String?): List<MediaItem> = call { b, a -> client.episodes(b, a, seriesId, seasonId) }

    suspend fun nextUpFor(seriesId: String): MediaItem? = call { b, a -> client.nextUp(b, a, 1, seriesId).firstOrNull() }

    suspend fun children(parentId: String, start: Int = 0, limit: Int = PAGE): MediaPage = call { b, a -> client.query(b, a, parentId = parentId, start = start, limit = limit, recursive = false) }

    suspend fun albumTracks(albumId: String): List<MediaItem> = call { b, a -> client.albumTracks(b, a, albumId) }

    suspend fun artists(parentId: String?, start: Int = 0): MediaPage = call { b, a -> client.artists(b, a, parentId, start) }

    suspend fun albumsBy(artistId: String): List<MediaItem> = call { b, a ->
        client.query(b, a, types = "MusicAlbum", sort = MediaSort.RELEASED, extra = mapOf("albumArtistIds" to artistId), limit = 100).items
    }

    /** Jellyfin-only search, grouped the way the search page shows it. */
    suspend fun search(term: String): Map<MediaType, List<MediaItem>> = call { b, a ->
        client.query(b, a, types = "Movie,Series,Episode,BoxSet,MusicAlbum,MusicArtist,Audio,Person", search = term, limit = 60, sort = MediaSort.NAME)
            .items.groupBy { it.type }
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = call { b, a ->
        client.setFavorite(b, a, id, favorite)
        cache.invalidate { true }
    }

    suspend fun setPlayed(id: String, played: Boolean) = call { b, a ->
        client.setPlayed(b, a, id, played)
        cache.invalidate { true }
    }

    /** A picture's URL on the route in use (or the last one, offline: the image cache answers). */
    fun imageUrl(art: JellyfinArt): String? {
        val base = stateFlow.value.base ?: lastBase ?: return null
        return client.imageUrl(base, art)
    }

    /** After playing: what was watched has changed, so pages ask again. */
    suspend fun changed() = cache.invalidate { true }

    companion object {
        const val TOKEN = "jellyfin.token"
        const val USER_ID = "jellyfin.userId"
        const val USER_NAME = "jellyfin.userName"
        const val SERVER_ID = "jellyfin.serverId"
        const val SERVER_NAME = "jellyfin.serverName"
        const val DEVICE_ID = "jellyfin.deviceId"

        const val PAGE = 60
        private const val FRESH_MS = 60_000L
        private const val HEALTH_EVERY_MS = 30_000L
        private const val MIN_CHECK_MS = 5_000L
        private const val LOCAL_TIMEOUT_MS = 2_500L
        private const val REMOTE_TIMEOUT_MS = 6_000L
        private const val DISCOVERY_MS = 2_000L
    }
}

/** Finds Jellyfin servers on the local network (UDP broadcast on 7359). */
interface ServerDiscovery {
    suspend fun discover(timeoutMs: Long): List<DiscoveredServer>
}
