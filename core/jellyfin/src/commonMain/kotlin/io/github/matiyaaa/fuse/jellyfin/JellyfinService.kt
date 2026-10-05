package io.github.matiyaaa.fuse.jellyfin

import io.github.matiyaaa.fuse.data.settings.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    private val revisionFlow = MutableStateFlow(0)

    /** Goes up whenever what the server would answer has changed (played, marked, signed in or out). */
    val revision: StateFlow<Int> = revisionFlow

    private val cache = ResponseCache(disk, clock)
    private val lock = Mutex()
    private var connection = JellyfinConnection()
    private var watcher: Job? = null
    private var lastCheck = 0L

    /** Keys being fetched behind a shown answer, so each is asked once at a time. */
    private val behind = HashSet<String>()
    private val behindLock = Mutex()
    private var refreshedJob: Job? = null

    init {
        client.cache = cache
        client.freshForMs = FRESH_MS
        // An answer clears "can't reach"; a failed call only asks whether another way works, and
        // the server counts as unreachable only when no way in answers (see reconnect).
        client.onReachable = { ok ->
            if (ok && stateFlow.value.offline) stateFlow.update { it.copy(offline = false) }
            if (!ok) scope.launch { reconnect(force = true, quietly = true) }
        }
        client.refreshBehind = { key, fetch ->
            scope.launch {
                if (!behindLock.withLock { behind.add(key) }) return@launch
                try {
                    val base = stateFlow.value.base ?: run { reconnect(); stateFlow.value.base }
                    if (base != null) {
                        runCatching { fetch(base) }.onFailure { e ->
                            if (e is JellyfinException && e.kind == JellyfinException.Kind.NETWORK) reconnect(force = true, quietly = true)
                        }
                    }
                } finally {
                    behindLock.withLock { behind.remove(key) }
                }
            }
        }
        // Pages shown from what was kept ask again once the fresh answers are in (a moment later, together).
        client.onRefreshed = {
            refreshedJob?.cancel()
            refreshedJob = scope.launch {
                delay(REFRESHED_SETTLE_MS)
                revisionFlow.update { it + 1 }
            }
        }
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
                    lastBase = base
                    secrets.put(LAST_BASE, base)
                    stateFlow.update { it.copy(account = account, route = route, base = base, offline = false, authRequired = false, serverName = info.name, serverVersion = info.version) }
                    cache.clear()
                    revisionFlow.update { it + 1 }
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
        for (k in listOf(TOKEN, USER_ID, USER_NAME, SERVER_ID, SERVER_NAME, LAST_BASE)) secrets.remove(k)
        lastBase = null
        cache.clear()
        stateFlow.update { JellyfinState(enabled = it.enabled) }
        revisionFlow.update { it + 1 }
    }

    /**
     * The address to use now and the account, or an error the screens can show. Before the way in
     * is known, the last one that worked is used: kept answers show at once from it, and a call
     * that fails there finds the right way and asks again (see [call]).
     */
    suspend fun session(): Pair<String, Account> {
        val s = stateFlow.value
        val a = s.account ?: throw JellyfinException("Sign in to Jellyfin in Settings, Addons, Jellyfin.", JellyfinException.Kind.AUTH)
        val base = s.base ?: lastBase ?: run {
            reconnect()
            stateFlow.value.base
        } ?: throw JellyfinException("The server can't be reached.", JellyfinException.Kind.NETWORK)
        return base to a
    }

    /** The last address that answered, kept between runs so pages and pictures show before it answers again. */
    @kotlin.concurrent.Volatile private var lastBase: String? = null

    /**
     * Picks the way in. Every address is asked at once: home wins whenever it answers (in Auto),
     * else the first outside address that does, so a home address that can't be reached away from
     * home costs a couple of seconds at most, never one timeout after another. The server counts as
     * unreachable only when no way answers.
     */
    suspend fun reconnect(force: Boolean = false, quietly: Boolean = false): Unit = lock.withLock {
        if (!stateFlow.value.enabled) return
        val interval = if (quietly) QUIET_CHECK_MS else MIN_CHECK_MS
        if (clock() - lastCheck < interval && stateFlow.value.base != null && (!force || quietly)) return
        lastCheck = clock()
        val before = stateFlow.value
        if (!quietly) stateFlow.update { it.copy(checking = true) }
        val found = probe(before)
        stateFlow.update {
            if (found == null) {
                it.copy(route = null, base = null, offline = it.account != null, checking = false)
            } else {
                it.copy(route = found.first, base = found.second, offline = false, checking = false, serverName = found.third.name ?: it.serverName, serverVersion = found.third.version)
            }
        }
        if (found != null && found.second != lastBase) {
            lastBase = found.second
            if (stateFlow.value.account != null) runCatching { secrets.put(LAST_BASE, found.second) }
        }
        val a = stateFlow.value.account
        val b = stateFlow.value.base
        // A quiet check that the token still works, when the way in changed.
        if (a != null && b != null && b != before.base) {
            scope.launch {
                runCatching { client.views(b, a) }.onFailure { e ->
                    if (e is JellyfinException && e.kind == JellyfinException.Kind.AUTH) stateFlow.update { it.copy(authRequired = true) }
                }
            }
        }
    }

    /** Asks every way in at once and picks by preference: the first that answers, in [addressesInOrder]. */
    private suspend fun probe(current: JellyfinState): Triple<Route, String, ServerInfo>? {
        val order = addressesInOrder().flatMap { (route, address) -> JellyfinClient.candidates(address).map { route to it } }
        if (order.isEmpty()) return null
        // At home and answering: one quick look is enough.
        val base = current.base
        if (base != null && (current.route == Route.LOCAL || connection.mode != ConnectionMode.AUTO) && order.any { it.second == base }) {
            runCatching { client.publicInfo(base, if (current.route == Route.LOCAL) LOCAL_TIMEOUT_MS else REMOTE_TIMEOUT_MS) }.getOrNull()
                ?.let { return Triple(current.route ?: Route.REMOTE, base, it) }
        }
        return coroutineScope {
            val asks = order.map { (route, b) ->
                async { runCatching { client.publicInfo(b, if (route == Route.LOCAL) LOCAL_TIMEOUT_MS else REMOTE_TIMEOUT_MS) }.getOrNull() }
            }
            var pick: Triple<Route, String, ServerInfo>? = null
            for ((i, ask) in asks.withIndex()) {
                val info = ask.await() ?: continue
                pick = Triple(order[i].first, order[i].second, info)
                break
            }
            asks.forEach { it.cancel() }
            pick
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
        lastBase = lastBase ?: secrets.get(LAST_BASE)
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

    /**
     * Runs a call on the route in use. When it can't get through, the way in is looked for again
     * and the call asked once more on whichever answers; the account is marked signed out only
     * when the server says so.
     */
    suspend fun <T> call(block: suspend (base: String, account: Account) -> T): T {
        val (base, account) = session()
        try {
            return block(base, account)
        } catch (e: JellyfinException) {
            if (e.kind == JellyfinException.Kind.AUTH) stateFlow.update { it.copy(authRequired = true) }
            if (e.kind != JellyfinException.Kind.NETWORK) throw e
            reconnect(force = true)
            val again = stateFlow.value.base ?: throw e
            return block(again, account)
        }
    }

    /** The Jellyfin home: what to continue, what's next, what's new, then each library. */
    suspend fun home(): List<Shelf> = call { base, a ->
        coroutineScope {
            // Everything is asked for at once: the page waits for the slowest answer, not their sum.
            val views = client.views(base, a)
            val resume = async { runCatching { client.resume(base, a) }.getOrNull().orEmpty() }
            val next = async { runCatching { client.nextUp(base, a) }.getOrNull().orEmpty() }
            val latest = views.filter { it.library != LibraryKind.COLLECTIONS }.map { v ->
                v to async { runCatching { client.latest(base, a, v.id, limit = 20) }.getOrNull().orEmpty() }
            }
            val favorites = async {
                runCatching { client.query(base, a, types = "Movie,Series,Episode,MusicAlbum", filter = MediaFilter.FAVORITES, sort = MediaSort.NAME, limit = 30) }.getOrNull()?.items.orEmpty()
            }
            val collectionsView = views.firstOrNull { it.library == LibraryKind.COLLECTIONS }
            val collections = async { collectionsView?.let { c -> runCatching { client.query(base, a, parentId = c.id, limit = 30) }.getOrNull()?.items }.orEmpty() }
            buildList {
                resume.await().takeIf { it.isNotEmpty() }?.let { add(Shelf("continue", "Continue watching", ShelfKind.CONTINUE, it)) }
                next.await().takeIf { it.isNotEmpty() }?.let { add(Shelf("nextup", "Next up", ShelfKind.NEXT_UP, it)) }
                for ((v, items) in latest) {
                    val got = items.await()
                    if (got.isNotEmpty()) add(Shelf("latest.${v.id}", "New in ${v.name}", if (v.library == LibraryKind.MUSIC) ShelfKind.MUSIC else ShelfKind.LATEST, got, v.id))
                }
                favorites.await().takeIf { it.isNotEmpty() }?.let { add(Shelf("favorites", "Favourites", ShelfKind.FAVORITES, it)) }
                collections.await().takeIf { it.isNotEmpty() }?.let { add(Shelf("collections", "Collections", ShelfKind.COLLECTIONS, it, collectionsView?.id)) }
            }
        }
    }

    /** For Home's widgets: what to continue, what's next, and the newest films and episodes. */
    suspend fun widgetFeed(): MediaFeed = call { b, a ->
        // One shelf failing leaves it empty, but the server not answering at all is thrown, so the
        // call reconnects (the other address) and asks again instead of showing empty widgets.
        suspend fun <T> shelf(get: suspend () -> List<T>): List<T> = try {
            get()
        } catch (e: JellyfinException) {
            if (e.kind == JellyfinException.Kind.NETWORK || e.kind == JellyfinException.Kind.AUTH) throw e
            emptyList()
        }
        coroutineScope {
            val resume = async { shelf { client.resume(b, a) } }
            val next = async { shelf { client.nextUp(b, a) } }
            val latest = async { shelf { client.latest(b, a, null, limit = 16, types = "Movie,Series,Episode") } }
            val favorites = async { shelf { client.query(b, a, types = "Movie,Series", filter = MediaFilter.FAVORITES, sort = MediaSort.NAME, limit = 16).items } }
            val movies = async { shelf { client.latest(b, a, null, limit = 16, types = "Movie") } }
            val music = async { shelf { client.latest(b, a, null, limit = 16, types = "MusicAlbum") } }
            MediaFeed(resume.await(), next.await(), latest.await(), favorites.await(), movies.await(), music.await())
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
        coroutineScope {
            val items = async { client.query(b, a, types = "Movie,Series,Episode,BoxSet,MusicAlbum,Audio", search = term, limit = 80, sort = MediaSort.NAME).items }
            // People and artists aren't items to the server: each has its own search.
            val people = async { runCatching { client.persons(b, a, term) }.getOrDefault(emptyList()) }
            val artists = async { runCatching { client.artists(b, a, null, limit = 30, search = term).items }.getOrDefault(emptyList()) }
            (items.await() + artists.await() + people.await()).groupBy { it.type }
        }
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = call { b, a ->
        client.setFavorite(b, a, id, favorite)
        cache.invalidate { true }
        revisionFlow.update { it + 1 }
    }

    suspend fun setPlayed(id: String, played: Boolean) = call { b, a ->
        client.setPlayed(b, a, id, played)
        cache.invalidate { true }
        revisionFlow.update { it + 1 }
    }

    /** A picture's URL on the route in use (or the last one, offline: the image cache answers). */
    fun imageUrl(art: JellyfinArt): String? {
        val base = stateFlow.value.base ?: lastBase ?: return null
        return client.imageUrl(base, art)
    }

    /** After playing: what was watched has changed, so pages ask again. */
    suspend fun changed() {
        cache.invalidate { true }
        revisionFlow.update { it + 1 }
    }

    companion object {
        const val TOKEN = "jellyfin.token"
        const val USER_ID = "jellyfin.userId"
        const val USER_NAME = "jellyfin.userName"
        const val SERVER_ID = "jellyfin.serverId"
        const val SERVER_NAME = "jellyfin.serverName"
        const val DEVICE_ID = "jellyfin.deviceId"

        /** The last address that answered (not a secret; kept beside the account so it goes with it). */
        const val LAST_BASE = "jellyfin.lastBase"

        const val PAGE = 60
        private const val FRESH_MS = 60_000L
        private const val HEALTH_EVERY_MS = 30_000L
        private const val MIN_CHECK_MS = 5_000L
        private const val QUIET_CHECK_MS = 3_000L
        private const val REFRESHED_SETTLE_MS = 400L
        private const val LOCAL_TIMEOUT_MS = 2_500L
        private const val REMOTE_TIMEOUT_MS = 6_000L
        private const val DISCOVERY_MS = 2_000L
    }
}

/** Finds Jellyfin servers on the local network (UDP broadcast on 7359). */
interface ServerDiscovery {
    suspend fun discover(timeoutMs: Long): List<DiscoveredServer>
}
