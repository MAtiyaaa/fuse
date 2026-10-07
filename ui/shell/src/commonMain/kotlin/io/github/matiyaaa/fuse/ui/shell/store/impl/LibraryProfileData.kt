package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.repo.CollectionRow
import io.github.matiyaaa.fuse.data.repo.SessionRow
import io.github.matiyaaa.fuse.data.repo.UserGameRow
import io.github.matiyaaa.fuse.data.repo.UserState
import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.ProfileSettings
import io.github.matiyaaa.fuse.sync.CollectionRecord
import io.github.matiyaaa.fuse.sync.GameKey
import io.github.matiyaaa.fuse.sync.GameRecord
import io.github.matiyaaa.fuse.sync.Hlc
import io.github.matiyaaa.fuse.sync.HlcClock
import io.github.matiyaaa.fuse.sync.Lww
import io.github.matiyaaa.fuse.sync.ProfileDataPort
import io.github.matiyaaa.fuse.sync.ProfileMeta
import io.github.matiyaaa.fuse.sync.SessionEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * The person's data in this library, as Fuse Sync reads and writes it: each game's favourite,
 * hidden, pinned, name, emulator, play time and sessions (by [GameKey], so the same game matches on
 * every device), the collections they made, and the settings that are theirs ([ProfileSettings]).
 *
 * Anything changed here between a read and the next write (a favourite tapped while the host
 * answered) is kept as it is, and goes up on the next round: a write never undoes the person.
 */
internal class LibraryProfileData(
    private val data: FuseData,
    /** The settings as they are now, with anything still on its way to the database included. */
    private val settings: suspend () -> AppSettings,
    /** Changes the settings and has the interface follow, at once. */
    private val applySettings: suspend ((AppSettings) -> AppSettings) -> Unit,
) : ProfileDataPort {
    private val lock = Mutex()
    private var lastState: UserState? = null
    private var lastSettings: Map<String, JsonElement> = emptyMap()

    /** The id the host gave each game, from the ids this library knows it by (so a serial here and a title on the Thor are one game). */
    @kotlin.concurrent.Volatile private var aliases: Map<String, String> = emptyMap()

    override fun useAliases(aliases: Map<String, String>) { this.aliases = aliases }

    /** The game here the household knows as [household], or null when this library doesn't have it. */
    suspend fun gameFor(household: String): Long? =
        data.profileState.read().games.firstOrNull { r -> keyOf(r).id == household || candidatesOf(r).any { it.id == household } }?.id

    /** Every library game's ids with the household, the one it is known by first. */
    suspend fun householdIds(): Map<Long, List<String>> =
        data.profileState.read().games.associate { r -> r.id to (listOf(keyOf(r).id) + candidatesOf(r).map { it.id }).distinct() }

    override suspend fun candidates(): List<List<GameKey>> =
        data.profileState.read().games.map { candidatesOf(it) }.distinct()

    /** The game's one id: the host's, when it has answered for any of the ids it is known by here. */
    private fun keyOf(r: UserGameRow): GameKey {
        val ids = candidatesOf(r)
        val a = aliases
        for (k in ids) a[k.id]?.let { found -> return GameKey.parse(found) ?: k }
        return ids.first()
    }

    override suspend fun read(device: String, clock: HlcClock): ProfileMeta = lock.withLock {
        val now = settings()
        val state = data.profileState.read()
        val mine = personal(now)
        lastState = state
        lastSettings = mine
        val sync = now.sync
        if (!sync.records && !sync.settings) return ProfileMeta()
        val keys = state.games.associate { it.id to keyOf(it) }
        val sessions = state.sessions.groupBy { it.gameId }
        val dismissed = now.home.continueDismissed
        val games = HashMap<String, GameRecord>()
        if (sync.records) {
            // Two copies of one game (a ROM on the card and one on the drive) are one record: the one played most speaks for it.
            for ((key, rows) in state.games.groupBy { keys.getValue(it.id) }) {
                val r = rows.maxBy { it.trackedSeconds }
                games[key.id] = GameRecord(
                    key = key,
                    playSeconds = if (r.trackedSeconds > 0) mapOf(device to r.trackedSeconds) else emptyMap(),
                    sessions = rows.flatMap { sessions[it.id].orEmpty() }.associate { s ->
                        SessionEntry.idOf(s.startedAt, s.endedAt).let { it to SessionEntry(it, device, s.startedAt, s.endedAt, s.emulator) }
                    },
                    lastPlayed = rows.mapNotNull { it.lastPlayed }.maxOrNull(),
                    favorite = Lww(rows.any { it.favorite }, Hlc.ZERO),
                    hidden = Lww(r.hidden, Hlc.ZERO),
                    pinned = Lww(rows.any { it.pinned }, Hlc.ZERO),
                    continueDismissed = Lww(rows.mapNotNull { dismissed[it.id.toString()] }.maxOrNull(), Hlc.ZERO),
                    title = Lww(r.customTitle, Hlc.ZERO),
                    emulator = Lww(r.emulator, Hlc.ZERO),
                    name = r.customTitle ?: r.title,
                )
            }
        }
        val collections = if (!sync.records) emptyMap() else state.collections.associate { c ->
            val id = collectionId(c.createdAt)
            id to CollectionRecord(
                id = id,
                name = Lww(c.name, Hlc.ZERO),
                members = c.games.mapNotNull { keys[it]?.id }.associateWith { Lww(true, Hlc.ZERO) },
                order = Lww(c.order.toInt(), Hlc.ZERO),
            )
        }
        val values = if (sync.settings) mine.mapValues { Lww(it.value, Hlc.ZERO) } else emptyMap()
        ProfileMeta(games, collections, values)
    }

    override suspend fun write(meta: ProfileMeta) = lock.withLock {
        val now = settings()
        val sync = now.sync
        val state = data.profileState.read()
        val before = lastState
        val keys = state.games.associate { it.id to keyOf(it) }
        var dismissed = now.home.continueDismissed
        if (sync.records) {
            val beforeRows = before?.games?.associateBy { it.id }.orEmpty()
            val rows = state.games.map { r ->
                // Changed here since it was read: stays, and is sent next time.
                val read = beforeRows[r.id]
                if (before != null && read != r) return@map r
                val g = meta.games[keys.getValue(r.id).id]
                dismissed = g?.continueDismissed?.value?.let { dismissed + (r.id.toString() to it) } ?: (dismissed - r.id.toString())
                if (g == null) {
                    r.copy(customTitle = null, favorite = false, hidden = false, pinned = false, lastPlayed = null, trackedSeconds = 0, sessionCount = 0, emulator = null)
                } else {
                    r.copy(
                        customTitle = g.title?.value,
                        favorite = g.favorite?.value ?: false,
                        hidden = g.hidden?.value ?: false,
                        pinned = g.pinned?.value ?: false,
                        lastPlayed = g.lastPlayed,
                        trackedSeconds = g.totalSeconds,
                        sessionCount = g.sessions.size.toLong(),
                        emulator = g.emulator?.value,
                    )
                }
            }
            // A game's sessions go on one copy of it; sessions that ended here since the read stay.
            val firstRow = HashMap<String, Long>()
            for (r in state.games) firstRow.getOrPut(keys.getValue(r.id).id) { r.id }
            val fresh = before?.let { b -> state.sessions.filter { it !in b.sessions } }.orEmpty()
            val sessions = meta.games.values.flatMap { g ->
                val row = firstRow[g.key.id] ?: return@flatMap emptyList()
                g.sessions.values.map { SessionRow(row, it.emulator, it.startedAt, it.endedAt) }
            } + fresh
            val collections = if (before != null && before.collections != state.collections) {
                state.collections
            } else {
                meta.collections.values.filter { it.deleted?.value != true }.map { c ->
                    CollectionRow(0, c.name.value, (c.order?.value ?: 0).toLong(), createdAtOf(c.id), c.games.mapNotNull { firstRow[it] })
                }
            }
            data.profileState.write(UserState(rows, sessions.distinct(), collections))
        }
        // Only what the profile has set comes in. What it never set stays as this device has it: a
        // new profile starts with this device's look, Home and settings, which then become its own
        // (sent up on the next round) and are never put back to Fuse's defaults.
        var incoming = if (sync.settings) meta.settings.mapValues { it.value.value }.filterKeys { p -> p in kept(now) && personal(now)[p] == lastSettings[p] } else emptyMap()
        // Home rejoining the profile's: the profile's Home comes back as it was (this device's was kept apart).
        val rejoin = sync.homeScope == HOME_REJOIN
        if (rejoin) meta.settings[HOME_LAYOUT]?.let { incoming = incoming + (HOME_LAYOUT to it.value) }
        if (incoming.isNotEmpty() || dismissed != now.home.continueDismissed || rejoin) {
            applySettings { s ->
                val next = if (incoming.isEmpty()) s else ProfileSettings.apply(s, incoming)
                next.copy(
                    home = next.home.copy(continueDismissed = dismissed),
                    sync = if (rejoin) next.sync.copy(homeScope = HOME_PROFILE) else next.sync,
                )
            }
        }
        lastState = data.profileState.read()
        lastSettings = personal(settings())
    }

    override suspend fun keyOf(gameId: Long): GameKey? = data.profileState.read().games.firstOrNull { it.id == gameId }?.let { keyOf(it) }

    /** The person's settings, without Home while this device keeps its own. */
    private fun personal(s: AppSettings): Map<String, JsonElement> = ProfileSettings.extract(s).filterKeys { it in kept(s) }

    private fun kept(s: AppSettings): Set<String> =
        if (s.sync.homeScope != HOME_PROFILE) ProfileSettings.paths.toSet() - HOME_LAYOUT else ProfileSettings.paths.toSet()

    companion object {
        const val HOME_PROFILE = "PROFILE"
        const val HOME_DEVICE = "DEVICE"

        /** Going back to the profile's Home: until it has come in, this device's isn't sent as the profile's. */
        const val HOME_REJOIN = "REJOIN"
        const val HOME_LAYOUT = "home.layout"

        fun keyOf(r: UserGameRow): GameKey = GameKey.of(r.platform, r.serial, null, r.title)

        /** Every id a game can be known by on some device: by serial when this library knows it, and always by title. */
        fun candidatesOf(r: UserGameRow): List<GameKey> {
            val byTitle = GameKey.of(r.platform, null, null, r.title)
            val first = keyOf(r)
            return if (first == byTitle) listOf(first) else listOf(first, byTitle)
        }

        /** A collection's id everywhere: when it was made, which every device keeps as it is. */
        fun collectionId(createdAt: Long) = "c$createdAt"

        fun createdAtOf(id: String): Long = id.removePrefix("c").toLongOrNull() ?: (id.hashCode().toLong() and 0x7fffffff)
    }
}
