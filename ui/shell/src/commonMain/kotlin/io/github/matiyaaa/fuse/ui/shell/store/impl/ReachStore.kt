package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.library.storage.UploadFiles
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.reach.REACH_SOURCE
import io.github.matiyaaa.fuse.reach.ReachFile
import io.github.matiyaaa.fuse.reach.ReachJob
import io.github.matiyaaa.fuse.reach.ReachSource
import io.github.matiyaaa.fuse.reach.ReachTransferHost
import io.github.matiyaaa.fuse.reach.reachHandlers
import io.github.matiyaaa.fuse.romm.RommClient
import io.github.matiyaaa.fuse.romm.RommPlacement
import io.github.matiyaaa.fuse.sync.CommandResult
import io.github.matiyaaa.fuse.sync.DeviceCommand
import io.github.matiyaaa.fuse.sync.DeviceInfo
import io.github.matiyaaa.fuse.sync.DeviceLibrary
import io.github.matiyaaa.fuse.sync.Household
import io.github.matiyaaa.fuse.sync.HouseholdLocal
import io.github.matiyaaa.fuse.sync.LibraryEntry
import io.github.matiyaaa.fuse.sync.ONLINE_MS
import io.github.matiyaaa.fuse.sync.PeerBytes
import io.github.matiyaaa.fuse.sync.PeerFile
import io.github.matiyaaa.fuse.sync.RemoteTransfer
import io.github.matiyaaa.fuse.sync.SharedGame
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.transfer.TransferArt
import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferPlaces
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.github.matiyaaa.fuse.transfer.Transfers
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.GameCopies
import io.github.matiyaaa.fuse.ui.shell.store.CopyCheck
import io.github.matiyaaa.fuse.ui.shell.store.CopyPlace
import io.github.matiyaaa.fuse.ui.shell.store.CopyView
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorChoice
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameDetail
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdDevice
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdGame
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdState
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdSystem
import io.github.matiyaaa.fuse.ui.shell.store.ReachOps
import io.github.matiyaaa.fuse.ui.shell.store.RemoteTransferRow
import io.github.matiyaaa.fuse.ui.shell.store.SendState
import io.github.matiyaaa.fuse.ui.shell.store.SendTarget
import io.github.matiyaaa.fuse.ui.shell.store.TransferAction
import io.github.matiyaaa.fuse.ui.shell.store.TransferRow
import io.github.matiyaaa.fuse.ui.shell.store.householdOnly
import io.github.matiyaaa.fuse.ui.shell.store.rommGameId
import io.github.matiyaaa.fuse.ui.shell.store.rommOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a game came to this device through Fuse, kept by its path so its copy says "Downloaded". */
@Serializable
internal data class Arrival(val from: String, val at: Long)

/**
 * The Remote Library in the app: the household's other devices' games (through Fuse Sync's
 * [Household]), each copy of a game wherever it is, bringing a game here or to another device
 * from wherever is best ([reachHandlers], through Fuse's transfers), sending a game to RomM from
 * the device that has it, and every device's transfers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultReachOps(
    private val ctx: StoreContext,
    private val engine: LibraryEngine,
    private val transfers: Transfers,
    private val service: SyncService?,
    private val romm: DefaultRommOps,
    /** Every library game's ids with the household, the one it is known by first. */
    private val householdIds: suspend () -> Map<Long, List<String>>,
    private val choiceFor: suspend (Game) -> EmulatorChoice,
    private val fillRemote: (List<GameId>) -> Unit,
    /** Downloads shows other devices' transfers through this. */
    private val showRemote: (List<TransferRow>, (TransferRow, TransferAction) -> Unit) -> Unit,
) : ReachOps {
    private val household: Household = service?.household ?: Household.None
    override val supported: Boolean = service != null
    override val self: String get() = household.self
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val games = HouseholdGames(ctx, { key -> bestEntry(key) }) { redraw.update { it + 1 } }
    private val redraw = MutableStateFlow(0)

    /** Library game id to its household ids, refreshed as the library changes. */
    private val localIds = MutableStateFlow<Map<Long, List<String>>>(emptyMap())
    private val localByKey = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The household's devices, drawn again whenever the household looks at who is around. */
    private val devices: StateFlow<List<DeviceInfo>> = combine(service?.devices ?: MutableStateFlow(emptyList()), household.seen) { list, _ -> list }
        .stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    /** Seen by the host lately: from the household's own look (every half minute), else the device list. */
    private fun online(device: String, list: List<DeviceInfo> = devices.value): Boolean {
        val seen = household.seen.value[device] ?: list.firstOrNull { it.id == device }?.lastSeen ?: return false
        return ctx.now() - seen < ONLINE_MS
    }

    private fun lastSeen(device: String, list: List<DeviceInfo> = devices.value): Long =
        household.seen.value[device] ?: list.firstOrNull { it.id == device }?.lastSeen ?: 0

    private val settings get() = ctx.settings.value.sync

    // ------------------------------------------------------------------ start

    fun start() {
        if (service == null) return
        ctx.addRemoteGames(games)
        val otherDetail = ctx.remoteDetail
        ctx.remoteDetail = { id -> if (id.householdOnly != null) detail(id) else otherDetail(id) }
        val otherOn = ctx.remoteGamesOn
        ctx.remoteGamesOn = { platform -> otherOn(platform) + index.value.groups.filter { it.platform == platform.value }.map { it.id } }
        val otherIds = ctx.remoteIds
        ctx.remoteIds = { platform -> otherIds(platform) + index.value.groups.filter { platform == null || it.platform == platform.value }.map { it.id } }
        val otherAdopt = ctx.adoptArt
        ctx.adoptArt = { id -> otherAdopt(id) || adoptArt(id) }
        reachHandlers(host).forEach(transfers::register)
        household.attach(local)
        ctx.scope.launch { refreshLocal() }
        // The library changed: this device's list goes up again, and what it has drops off the others'.
        ctx.scope.launch {
            engine.scan.map { it.phase }.distinctUntilChanged().collect { phase ->
                if (phase == ScanPhase.DONE) {
                    refreshLocal()
                    household.libraryChanged()
                }
            }
        }
        ctx.scope.launch {
            ctx.settings.map { Triple(it.sync.shareLibrary, it.sync.acceptSends, it.sync.relay) }.distinctUntilChanged().collect { household.libraryChanged() }
        }
        // Devices come and go: games waiting for one look again.
        ctx.scope.launch { devices.map { list -> list.filter { online(it.id, list) }.map { it.id }.toSet() }.distinctUntilChanged().collect { transfers.devicesChanged() } }
        // This device's transfers, for the others' Downloads.
        ctx.scope.launch { transfers.items.collect { household.transfersChanged() } }
        ctx.scope.launch {
            while (true) {
                delay(2_000)
                if (transfers.items.value.any { it.status == TransferStatus.ACTIVE }) household.transfersChanged()
            }
        }
        // A game another device asked for that couldn't come: that device hears why.
        ctx.scope.launch {
            val told = HashSet<String>()
            transfers.items.collect { list ->
                for (t in list) {
                    if (t.source != REACH_SOURCE || t.id in told) continue
                    if (t.status != TransferStatus.FAILED && t.status != TransferStatus.CANCELLED) continue
                    val cmd = runCatching { ReachJob.decode(t.payload).command }.getOrNull() ?: continue
                    told += t.id
                    settle(cmd, DeviceCommand.FAILED, if (t.status == TransferStatus.CANCELLED) "Cancelled on ${ctx.services.deviceName}" else t.error)
                }
            }
        }
        ctx.scope.launch {
            combine(remoteTransfers, requests, devices) { rows, asked, devs -> Triple(rows, asked, devs) }.collect { (rows, asked, devs) ->
                // What this device asked that hasn't started there yet (its device is away), or didn't work.
                val started = rows.map { it.device to it.item.title }.toSet()
                val waiting = asked.filter { c ->
                    (c.type == DeviceCommand.FETCH || c.type == DeviceCommand.ROMM_UPLOAD) &&
                        ((c.open && (c.target to c.title) !in started && !online(c.target, devs)) || (c.state == DeviceCommand.FAILED && ctx.now() - c.doneAt < 60 * 60_000L))
                }
                showRemote(rows.map(::rowOf) + waiting.map { requestRow(it, devs) }) { row, action ->
                    if (row.item.id.startsWith("request:")) ctx.scope.launch { cancelRequest(row.item.id.removePrefix("request:")) }
                    else rows.firstOrNull { it.id == row.item.id }?.let { actRemote(it, action) }
                }
            }
        }
    }

    private suspend fun refreshLocal() {
        val ids = runCatching { householdIds() }.getOrDefault(emptyMap())
        localIds.value = ids
        localByKey.value = buildMap { for ((game, keys) in ids) for (k in keys) put(k, game) }
    }

    private suspend fun settle(command: String, state: String, message: String?) = household.settle(command, state, message)

    // ------------------------------------------------------------------ the household's games, gathered

    /** One game across the other devices: every copy, by device. */
    internal data class Group(val key: String, val id: GameId, val platform: String, val copies: List<Pair<DeviceLibrary, LibraryEntry>>) {
        val best: LibraryEntry get() = copies.maxWith(compareBy<Pair<DeviceLibrary, LibraryEntry>>({ it.second.hashed }, { it.second.addedAt })).second
        val addedAt: Long get() = copies.maxOf { it.second.addedAt }
    }

    internal data class Index(val groups: List<Group> = emptyList(), val byKey: Map<String, Group> = emptyMap())

    /** The other devices' games this device doesn't have, one per game. */
    private val index: StateFlow<Index> = combine(household.libraries, localByKey) { libs, mine -> libs to mine }
        .mapLatest { (libs, mine) ->
            val self = household.self
            val copies = libs.filter { it.device != self }.flatMap { lib -> lib.entries.map { lib to it } }
            // One game is known by any of its ids: a serial on one device, a title on another.
            val keyOf = HashMap<String, String>()
            for ((_, e) in copies) {
                val k = (listOf(e.game) + e.ids).firstNotNullOfOrNull { keyOf[it] } ?: e.game
                for (id in listOf(e.game) + e.ids) keyOf.putIfAbsent(id, k)
            }
            val grouped = copies.groupBy { keyOf[it.second.game] ?: it.second.game }
                .filter { (_, list) -> list.none { (_, e) -> (listOf(e.game) + e.ids).any { it in mine } } }
            val ids = games.idsOf(grouped.keys)
            val groups = grouped.map { (k, list) -> Group(k, ids.getValue(k), list.first().second.platform, list) }
            Index(groups, groups.associateBy { it.key } + groups.flatMap { g -> g.copies.flatMap { (_, e) -> e.ids.map { it to g } } }.toMap())
        }
        .flowOn(Dispatchers.Default)
        .stateIn(ctx.scope, SharingStarted.Eagerly, Index())

    private fun bestEntry(key: String): LibraryEntry? = index.value.byKey[key]?.best

    /** Every copy on other devices of a game known here by [keys] (this device's own game). */
    private fun copiesOf(keys: Collection<String>): List<Pair<DeviceLibrary, LibraryEntry>> {
        val self = household.self
        return household.libraries.value.filter { it.device != self }.flatMap { lib ->
            lib.entries.filter { e -> (listOf(e.game) + e.ids).any { it in keys } }.map { lib to it }
        }
    }

    override val state: StateFlow<HouseholdState> = combine(household.supported, household.libraries, devices, index) { ok, libs, devs, idx ->
        val self = household.self
        HouseholdState(
            supported = ok,
            linked = service?.status?.value.let { it is io.github.matiyaaa.fuse.sync.SyncStatus.Online || it is io.github.matiyaaa.fuse.sync.SyncStatus.Offline || it is io.github.matiyaaa.fuse.sync.SyncStatus.Connecting },
            devices = libs.filter { it.device != self }.map { lib ->
                val d = devs.firstOrNull { it.id == lib.device }
                HouseholdDevice(lib.device, d?.name ?: lib.name, d?.platform ?: lib.platform, online(lib.device, devs), lastSeen(lib.device, devs), lib.accepts, lib.entries.size)
            },
            games = idx.groups.size,
        )
    }.stateIn(ctx.scope, SharingStarted.Eagerly, HouseholdState())

    private suspend fun toGames(groups: List<Group>): List<HouseholdGame> {
        if (groups.isEmpty()) return emptyList()
        val media = ctx.data.media.observeFor(groups.map { MediaOwner.OfGame(it.id) }).first()
        val records = games.allRecords()
        val running = transfers.items.value.filter { it.source == REACH_SOURCE && !it.status.finished }.associateBy { it.key }
        val devs = devices.value
        return groups.map { g ->
            val e = g.best
            val platform = ctx.platforms.byId(PlatformId(e.platform))
            val t = running["$REACH_SOURCE:${g.key}"]
            HouseholdGame(
                id = g.id,
                card = GameCard(
                    id = g.id,
                    platformId = platform?.id ?: PlatformId(e.platform),
                    title = games.title(e, records[g.key]),
                    platformShort = platform?.shortName ?: e.platform.uppercase(),
                    accent = platform?.accent ?: StoreContext.DEFAULT_ACCENT,
                    art = media[MediaOwner.OfGame(g.id)]?.let(Art::from) ?: Art.None,
                    addedAt = g.addedAt,
                    year = records[g.key]?.metadata?.releaseYear ?: e.releaseYear,
                ),
                key = g.key,
                holders = g.copies.map { it.first.name },
                onlineHolders = g.copies.count { online(it.first.device, devs) },
                sizeBytes = e.sizeBytes,
                addedAt = g.addedAt,
                transfer = t?.id,
                downloading = t?.status == TransferStatus.ACTIVE,
            )
        }
    }

    private val lists = combine(index, redraw, ctx.gameArtFound, transfers.items) { idx, _, _, _ -> idx }
        .mapLatest { idx ->
            val records = games.allRecords()
            val sorted = idx.groups.sortedBy { (records[it.key]?.titleCustom ?: it.best.title).lowercase() }
            // What shows first gets its art first, as RomM's games do.
            fillRemote(idx.groups.sortedByDescending { it.addedAt }.take(60).map { it.id })
            toGames(sorted)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    override val recent: StateFlow<List<HouseholdGame>> = lists.map { all -> all.sortedByDescending { it.addedAt }.take(RECENT) }
        .stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    override val systems: StateFlow<List<HouseholdSystem>> = lists.map { all ->
        all.groupBy { it.card.platformId }.map { (p, list) -> HouseholdSystem(p, ctx.platform(p)?.name ?: p.value, list.size, holders = list.flatMap { it.holders }.distinct()) }.sortedBy { it.name.lowercase() }
    }.mapLatest { list ->
        ctx.shownPlatforms.update { it + list.map { s -> s.platform } }
        val media = if (list.isEmpty()) emptyMap() else ctx.data.media.observeFor(list.map { MediaOwner.OfPlatform(it.platform) }).first()
        val colors = ctx.settings.value.library.systemColors
        list.map { s -> s.copy(art = media[MediaOwner.OfPlatform(s.platform)]?.let(Art::from) ?: Art.None, accent = colors[s.platform.value]) }
    }.flowOn(Dispatchers.Default).stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    override fun games(platform: PlatformId?): Flow<List<HouseholdGame>> = lists.map { all -> if (platform == null) all else all.filter { it.card.platformId == platform } }

    override val notOnRomm: StateFlow<List<HouseholdGame>> = combine(lists, romm.state) { all, s -> all to s }
        .mapLatest { (all, _) ->
            if (!romm.enabled || !settings.showInRomm) return@mapLatest emptyList()
            all.filter { g ->
                val e = bestEntry(g.key) ?: return@filter true
                romm.sameOnServer(e.rommRomId, e.files.mapNotNull { it.md5 }) == null
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    // ------------------------------------------------------------------ one game

    private fun detail(id: GameId): Flow<GameDetail?> {
        val owner = MediaOwner.OfGame(id)
        fillRemote(listOf(id))
        return combine(index, redraw, ctx.data.media.observe(owner)) { _, _, m -> m }.mapLatest { media ->
            val key = games.keyOf(id) ?: return@mapLatest null
            val entry = bestEntry(key) ?: return@mapLatest null
            val game = games.game(id, entry, games.record(key)) ?: return@mapLatest null
            val platform = ctx.platform(game.platformId) ?: return@mapLatest null
            GameDetail(
                game = game, platform = platform, media = media, art = Art.from(media),
                emulator = runCatching { choiceFor(game) }.getOrDefault(EmulatorChoice(null, emptyList(), "Automatic", null, false)),
                contentNotes = emptyList(), achievements = null, collections = emptyList(), secondsThisWeek = 0,
            )
        }.flowOn(Dispatchers.Default)
    }

    override fun libraryGame(id: GameId): Flow<GameId?> = combine(localByKey, flowOf(id)) { mine, gid ->
        val key = games.keyOf(gid) ?: return@combine null
        val entryIds = bestEntry(key)?.let { listOf(it.game) + it.ids } ?: listOf(key)
        entryIds.firstNotNullOfOrNull { mine[it] }?.let(::GameId)
    }.distinctUntilChanged()

    /** The household ids [id] is known by: a library game's own, another device's game's, or a RomM game's (by its link). */
    private suspend fun keysOf(id: GameId): List<String> = when {
        id.householdOnly != null -> listOfNotNull(games.keyOf(id))
        id.rommOnly != null -> {
            val romId = id.rommOnly!!
            copiesWithRom(romId).map { it.second.game }.distinct()
        }
        else -> localIds.value[id.value].orEmpty()
    }

    /** Other devices' copies of RomM's game [romId]: linked to it, or with its files. */
    private suspend fun copiesWithRom(romId: Long): List<Pair<DeviceLibrary, LibraryEntry>> {
        val rom = romm.romHere(romId) ?: return emptyList()
        val md5s = (rom.files.mapNotNull { it.md5 } + listOfNotNull(rom.md5)).map { it.lowercase() }.toSet()
        val self = household.self
        return household.libraries.value.filter { it.device != self }.flatMap { lib ->
            lib.entries.filter { e -> e.rommRomId == romId || e.files.any { f -> f.md5?.lowercase()?.let { it in md5s } == true } }.map { lib to it }
        }
    }

    override fun availability(id: GameId): Flow<GameCopies?> =
        combine(household.libraries, household.mine, devices, localIds, transfers.items) { _, _, _, _, _ -> }
            .mapLatest { availabilityNow(id) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    private suspend fun availabilityNow(id: GameId): GameCopies? {
        val keys = keysOf(id)
        val local: Game? = if (id.value > 0) ctx.data.games.get(id) else null
        val mineEntry = household.mine.value.firstOrNull { e -> (listOf(e.game) + e.ids).any { it in keys } }
        val others = if (keys.isEmpty()) emptyList() else copiesOf(keys)
        val romId = id.rommOnly ?: local?.let { romm.romOf(it.id) ?: it.links.rommRomId } ?: others.firstNotNullOfOrNull { it.second.rommRomId }
            ?: others.firstNotNullOfOrNull { (_, e) -> romm.sameOnServer(null, e.files.mapNotNull { it.md5 })?.id }
        val rom = romId?.takeIf { romm.enabled }?.let { romm.romHere(it) }
        if (local == null && others.isEmpty() && rom == null) return null
        val fingerprints = (others.map { it.second } + listOfNotNull(mineEntry)).mapNotNull { it.fingerprint }
        val rommHashes = rom?.let { r -> (r.files.mapNotNull { it.sha1 } + r.files.mapNotNull { it.md5 } + listOfNotNull(r.sha1, r.md5)).map { it.lowercase() }.toSet() }.orEmpty()
        fun checkOf(e: LibraryEntry?): CopyCheck = when {
            e == null -> CopyCheck.UNKNOWN
            !e.hashed -> CopyCheck.CHECKING
            fingerprints.count { it == e.fingerprint } > 1 -> CopyCheck.VERIFIED
            rommHashes.isNotEmpty() && e.files.any { f -> f.sha1?.lowercase() in rommHashes || f.md5?.lowercase() in rommHashes } -> CopyCheck.VERIFIED
            fingerprints.size > 1 || rommHashes.isNotEmpty() -> CopyCheck.DIFFERENT
            else -> CopyCheck.UNKNOWN
        }
        val devs = devices.value
        val copies = ArrayList<CopyView>()
        if (local != null) {
            val arrival = arrivalOf(local.location.path)
            copies += CopyView(
                CopyPlace.HERE, "here", ctx.services.deviceName, platform = "", online = true,
                sizeBytes = local.location.sizeBytes, check = if (!settings.shareLibrary) CopyCheck.UNKNOWN else checkOf(mineEntry),
                date = arrival?.at ?: ctx.data.games.summary(local.id)?.addedAt ?: 0, downloaded = arrival != null, from = arrival?.from,
            )
        }
        for ((lib, e) in others.distinctBy { it.first.device }) {
            val d = devs.firstOrNull { it.id == lib.device }
            copies += CopyView(
                CopyPlace.DEVICE, lib.device, d?.name ?: lib.name, d?.platform ?: lib.platform, online(lib.device, devs), lastSeen(lib.device, devs),
                e.sizeBytes, checkOf(e), e.addedAt, downloaded = e.arrivedFrom != null, from = e.arrivedFrom,
            )
        }
        if (rom != null) {
            val verified = (others.map { it.second } + listOfNotNull(mineEntry)).any { checkOf(it) == CopyCheck.VERIFIED && rommHashes.isNotEmpty() }
            copies += CopyView(
                CopyPlace.ROMM, romm.serverKey, "RomM", online = romm.state.value.link == io.github.matiyaaa.fuse.ui.shell.store.RommLink.ONLINE,
                sizeBytes = rom.sizeBytes, check = if (verified) CopyCheck.VERIFIED else CopyCheck.UNKNOWN, date = rom.createdAt,
            )
        }
        val running = transfers.items.value.firstOrNull { t -> t.source == REACH_SOURCE && !t.status.finished && keys.any { t.key == "$REACH_SOURCE:$it" } }
        return GameCopies(copies, running?.id)
    }

    private suspend fun arrivalOf(path: String): Arrival? =
        ctx.data.cache.entry(ARRIVED, FsPath.normalize(path))?.valueJson?.let { runCatching { json.decodeFromString(Arrival.serializer(), it) }.getOrNull() }

    // ------------------------------------------------------------------ bringing a game

    override suspend fun download(id: GameId): String? = withContext(Dispatchers.Default) { downloadFor(id, command = null) }

    /**
     * Queues [id] to come here from wherever is best: another device's copy (and every other copy
     * with the same files), with RomM's when it has exactly those files too. A RomM game no device
     * has comes from RomM as ever. Null when queued, else why not.
     */
    private suspend fun downloadFor(id: GameId, command: String?): String? {
        if (id.value > 0 && command == null) return "It's already here."
        val keys = keysOf(id)
        val copies = if (keys.isEmpty()) emptyList() else copiesOf(keys)
        if (id.rommOnly != null && copies.none { it.second.hashed }) {
            return romm.download(id.rommOnly!!)
        }
        if (localIds.value.any { (_, k) -> k.any { it in keys } } && command != null) return "It's already on ${ctx.services.deviceName}."
        if (copies.isEmpty()) return "No device has this game any more."
        val devs = devices.value
        // The copy the game is fixed by: one that has been read through (so every file can be checked), online first.
        val reference = copies.sortedWith(compareByDescending<Pair<DeviceLibrary, LibraryEntry>> { it.second.hashed }.thenByDescending { online(it.first.device, devs) }).first()
        val ref = reference.second
        val same = if (ref.hashed) copies.filter { it.second.fingerprint == ref.fingerprint } else listOf(reference)
        val platform = ctx.platforms.byId(PlatformId(ref.platform)) ?: ctx.platforms.resolveFolder(ref.platform)
            ?: return "Fuse doesn't know the system \"${ref.platform}\", so it can't tell where this goes."
        // RomM, when it has exactly these files.
        val rom = (id.rommOnly ?: ref.rommRomId ?: romm.sameOnServer(null, ref.files.mapNotNull { it.md5 })?.id)?.let { romm.romWithFiles(it) }
        val rommFiles = rom?.let { r ->
            ref.files.associate { f ->
                f.path to r.files.firstOrNull { rf -> rf.sizeBytes == f.size && ((f.sha1 != null && rf.sha1.equals(f.sha1, true)) || (f.md5 != null && rf.md5.equals(f.md5, true))) }
            }.takeIf { m -> m.values.all { it != null } }
        }
        val sources = same.map { (lib, _) -> ReachSource(ReachSource.PEER, lib.device, devs.firstOrNull { it.id == lib.device }?.name ?: lib.name) } +
            listOfNotNull(rom?.takeIf { rommFiles != null }?.let { ReachSource(ReachSource.ROMM, romm.serverKey, "RomM", romId = it.id) })
        val folderOf = folderFor(platform, ref.platform) ?: return "Choose where games go first: Settings, Addons, Remote Library."
        val safe = RommPlacement.safeName(ref.name.ifBlank { ref.title })
        val placePath = if (ref.folder) FsPath.join(folderOf, safe) else folderOf
        if (!ref.folder) {
            for (f in ref.files) if (fileExists(FsPath.join(folderOf, *f.path.split('/').filter { it.isNotEmpty() }.toTypedArray()))) {
                return "Something named ${f.path} is already in ${shortPath(folderOf)}, so it was left alone."
            }
        } else if (fileExists(placePath)) {
            return "A folder named $safe is already in ${shortPath(folderOf)}, so it was left alone."
        }
        val job = ReachJob(
            game = ref.game, title = ref.title, platform = platform.id.value, folder = ref.folder,
            files = ref.files.map { f -> ReachFile(f.path, f.size, f.sha1, f.md5, rommFiles?.get(f.path)) },
            sources = sources, command = command,
        )
        val drive = TransferPlaces.of(placePath, engine.drives.volumes.value, ctx.now())
        val holders = same.map { (lib, _) -> devs.firstOrNull { it.id == lib.device }?.name ?: lib.name }
        transfers.enqueue(
            TransferItem(
                id = "", key = "$REACH_SOURCE:${ref.game}", source = REACH_SOURCE, direction = TransferDirection.DOWNLOAD, kind = TransferKind.GAME,
                title = games.title(ref, games.record(ref.game)), detail = "From " + (holders + listOfNotNull("RomM".takeIf { rommFiles != null })).distinct().joinToString(" or "),
                platform = platform.id.value, art = TransferArt(), place = drive,
                target = "${drive.volume?.label ?: ctx.services.deviceName}  ·  ${shortPath(folderOf)}",
                totalBytes = ref.sizeBytes, payload = job.encode(),
            ),
        )
        return null
    }

    /** Where a game for [platform] goes here: the folder the person chose for games sent here, else RomM's choice. */
    private suspend fun folderFor(platform: io.github.matiyaaa.fuse.model.Platform, slug: String): String? {
        settings.receiveFolder.takeIf { it.isNotBlank() }?.let { base ->
            val root = FsPath.normalize(base)
            val children = runCatching { ctx.services.fs.list(root) }.getOrDefault(emptyList()).filter { it.isDirectory }.map { it.name }
            children.firstOrNull { ctx.platforms.resolveFolder(it)?.id == platform.id }?.let { return FsPath.join(root, it) }
            return FsPath.join(root, platform.id.value)
        }
        return romm.folderFor(platform, slug)?.first
    }

    private suspend fun fileExists(path: String) = runCatching { ctx.services.fs.stat(path) != null }.getOrDefault(false)

    private fun shortPath(path: String): String = FsPath.normalize(path).split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")

    // ------------------------------------------------------------------ sending a game elsewhere

    override suspend fun sendTargets(id: GameId): List<SendTarget> = withContext(Dispatchers.Default) {
        val keys = keysOf(id)
        val holders = copiesOf(keys).map { it.first.device }.toSet()
        val devs = devices.value
        val self = household.self
        val here = SendTarget(self, ctx.services.deviceName, "", if (id.value > 0) SendState.HAS_IT else SendState.THIS_DEVICE)
        val libs = household.libraries.value.associateBy { it.device }
        listOf(here) + devs.filter { it.id != self && !it.revoked }.map { d ->
            val state = when {
                d.id in holders -> SendState.HAS_IT
                libs[d.id]?.accepts == false -> SendState.REFUSES
                online(d.id, devs) -> SendState.READY
                else -> SendState.QUEUED
            }
            SendTarget(d.id, d.name, d.platform, state, lastSeen(d.id, devs))
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun sendTo(id: GameId, device: String): String? = withContext(Dispatchers.Default) {
        if (device == household.self) return@withContext downloadFor(id, null)
        val keys = keysOf(id)
        val key = keys.firstOrNull() ?: id.rommOnly?.let { "romm:$it" } ?: return@withContext "Fuse can't tell which game this is for the household yet. Try again once Fuse Sync has caught up."
        val title = ctx.remoteGames?.takeIf { it.owns(id) }?.get(id)?.displayTitle ?: ctx.data.games.get(id)?.displayTitle ?: ""
        val platform = (ctx.remoteGames?.takeIf { it.owns(id) }?.get(id) ?: ctx.data.games.get(id))?.platformId?.value
        household.ask(DeviceCommand(target = device, type = DeviceCommand.FETCH, game = key, title = title, platform = platform))
            .fold({ null }, { it.message ?: "The host didn't take the request." })
    }

    override suspend fun canUploadToRomm(id: GameId): Boolean = romm.enabled && romm.state.value.canUpload && id.rommOnly == null

    override suspend fun uploadToRomm(id: GameId): String? = withContext(Dispatchers.Default) {
        if (id.value > 0) {
            val plan = romm.uploadPlan(id) ?: return@withContext "That game isn't in the library any more."
            plan.problem?.let { return@withContext it }
            return@withContext romm.upload(id, plan)
        }
        val keys = keysOf(id)
        val copies = copiesOf(keys)
        val devs = devices.value
        val holder = copies.sortedByDescending { online(it.first.device, devs) }.firstOrNull() ?: return@withContext "No device has this game any more."
        household.ask(
            DeviceCommand(target = holder.first.device, type = DeviceCommand.ROMM_UPLOAD, game = holder.second.game, title = holder.second.title, platform = holder.second.platform),
        ).fold({ null }, { it.message ?: "The host didn't take the request." })
    }

    // ------------------------------------------------------------------ every device's transfers

    override val remoteTransfers: StateFlow<List<RemoteTransferRow>> = combine(household.transfers, devices) { snaps, devs ->
        val self = household.self
        snaps.filter { it.device != self }.flatMap { s ->
            val d = devs.firstOrNull { it.id == s.device }
            s.items.filter { it.status != "DONE" || ctx.now() - s.at < 60 * 60_000L }.map { RemoteTransferRow(s.device, d?.name ?: s.name, online(s.device, devs), lastSeen(s.device, devs).takeIf { it > 0 } ?: s.at, it) }
        }
    }.stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    override val requests: StateFlow<List<DeviceCommand>> = household.commands.map { list -> list.filter { it.from == household.self } }
        .stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    override fun watchTransfers(on: Boolean) = household.watchTransfers(on)

    override fun actRemote(row: RemoteTransferRow, action: TransferAction) {
        ctx.scope.launch {
            household.ask(DeviceCommand(target = row.device, type = DeviceCommand.TRANSFER, key = row.item.key, action = action.name, title = row.item.title))
        }
    }

    override suspend fun cancelRequest(id: String) {
        household.cancel(id)
    }

    /** Another device's transfer as a row of Downloads (acted on through [actRemote]). */
    private fun rowOf(r: RemoteTransferRow): TransferRow {
        val status = runCatching { TransferStatus.valueOf(r.item.status) }.getOrDefault(TransferStatus.QUEUED)
        val item = TransferItem(
            id = r.id, key = r.item.key, source = "remote", direction = if (r.item.upload) TransferDirection.UPLOAD else TransferDirection.DOWNLOAD,
            kind = runCatching { TransferKind.valueOf(r.item.kind) }.getOrDefault(TransferKind.GAME), title = r.item.title, detail = r.item.detail,
            platform = r.item.platform, target = r.item.target, totalBytes = r.item.total, doneBytes = r.item.done, status = status,
            phase = r.item.phase?.let { runCatching { io.github.matiyaaa.fuse.transfer.TransferPhase.valueOf(it) }.getOrNull() },
            waiting = r.item.waiting?.let { runCatching { io.github.matiyaaa.fuse.transfer.WaitReason.valueOf(it) }.getOrNull() },
            error = r.item.error, order = r.item.order,
        )
        val actions = when (status) {
            TransferStatus.ACTIVE, TransferStatus.WAITING -> listOf(TransferAction.PAUSE, TransferAction.CANCEL, TransferAction.MOVE_TO_TOP)
            TransferStatus.QUEUED -> listOf(TransferAction.PAUSE, TransferAction.MOVE_UP, TransferAction.MOVE_DOWN, TransferAction.MOVE_TO_TOP, TransferAction.CANCEL)
            TransferStatus.PAUSED -> listOf(TransferAction.RESUME, TransferAction.CANCEL)
            TransferStatus.FAILED -> listOf(TransferAction.RETRY, TransferAction.CANCEL)
            else -> emptyList()
        }
        return TransferRow(item, actions, device = r.device, deviceName = r.deviceName, deviceOnline = r.online, deviceSeen = r.lastSeen)
    }

    /**
     * A request this device made that is still waiting for its device (one that is away), or that
     * didn't work, as a row of Downloads: "Waiting for Thor", with Cancel.
     */
    private fun requestRow(c: DeviceCommand, devs: List<DeviceInfo>): TransferRow {
        val name = devs.firstOrNull { it.id == c.target }?.name ?: "the other device"
        val failed = c.state == DeviceCommand.FAILED
        val item = TransferItem(
            id = "request:${c.id}", key = "request:${c.id}", source = "request", direction = if (c.type == DeviceCommand.ROMM_UPLOAD) TransferDirection.UPLOAD else TransferDirection.DOWNLOAD,
            kind = TransferKind.GAME, title = c.title.ifBlank { "A game" }, detail = if (c.type == DeviceCommand.ROMM_UPLOAD) "To RomM" else "",
            platform = c.platform, target = "",
            status = if (failed) TransferStatus.FAILED else TransferStatus.WAITING, waiting = if (failed) null else io.github.matiyaaa.fuse.transfer.WaitReason.DEVICE,
            waitingFor = name, error = c.message, createdAt = c.at,
        )
        return TransferRow(item, if (failed) emptyList() else listOf(TransferAction.CANCEL), device = c.target, deviceName = name, deviceOnline = online(c.target, devs), deviceSeen = lastSeen(c.target, devs))
    }

    // ------------------------------------------------------------------ what this device does for the others

    private val local = object : HouseholdLocal {
        override suspend fun games(): List<SharedGame> = withContext(Dispatchers.Default) {
            refreshLocal()
            val ids = localIds.value
            val list = ctx.data.games.observeAll().first().filter { !it.isApp && !it.missing && !it.removed }
            list.mapNotNull { s ->
                val g = ctx.data.games.get(s.id) ?: return@mapNotNull null
                val keys = ids[s.id.value].orEmpty().ifEmpty { return@mapNotNull null }
                val files = runCatching { UploadFiles.collect(ctx.services.fs, g.location, g.discs) }.getOrDefault(emptyList())
                if (files.isEmpty()) return@mapNotNull null
                val folder = g.location.kind == LocationKind.FOLDER || (files.size > 1 && files.all { FsPath.isWithin(FsPath.normalize(it.path), FsPath.normalize(g.location.path)) } && g.location.kind != LocationKind.FILE)
                val rel = files.map { f -> (if (f.folder.isEmpty()) f.name else f.folder + "/" + f.name) to f }
                val arrival = arrivalOf(g.location.path)
                val entry = LibraryEntry(
                    game = keys.first(), ids = keys.drop(1), title = g.displayTitle, platform = g.platformId.value, sizeBytes = files.sumOf { it.sizeBytes },
                    addedAt = arrival?.at ?: s.addedAt, arrivedFrom = arrival?.from, folder = folder, name = FsPath.name(g.location.path),
                    files = rel.map { (p, f) -> PeerFile(p, f.sizeBytes) }, serial = g.tags.serial, rommRomId = romm.romOf(g.id) ?: g.links.rommRomId,
                    steamGridDbId = g.links.steamGridDbGameId, igdbId = g.links.igdbId, summary = g.metadata.description?.take(600),
                    releaseYear = g.metadata.releaseYear, developer = g.metadata.developer, genres = g.metadata.genres.take(6),
                )
                SharedGame(entry, rel.associate { (p, f) -> p to f.path })
            }
        }

        override suspend fun perform(command: DeviceCommand): CommandResult = when (command.type) {
            DeviceCommand.FETCH -> {
                if (!settings.acceptSends) CommandResult(DeviceCommand.FAILED, "${ctx.services.deviceName} doesn't take games from other devices (Settings, Addons, Remote Library).")
                else {
                    val key = command.game.orEmpty()
                    val romId = key.removePrefix("romm:").takeIf { key.startsWith("romm:") }?.toLongOrNull()
                    val target = romId?.let(::rommGameId) ?: index.value.byKey[key]?.id
                    val why = when {
                        localByKey.value.containsKey(key) -> null.also { return CommandResult(DeviceCommand.DONE, "It's already on ${ctx.services.deviceName}.") }
                        target == null -> "${ctx.services.deviceName} can't see this game on any device."
                        else -> downloadFor(target, command.id)
                    }
                    if (why == null) CommandResult(DeviceCommand.DELIVERED, "On its way to ${ctx.services.deviceName}") else CommandResult(DeviceCommand.FAILED, why)
                }
            }
            DeviceCommand.ROMM_UPLOAD -> {
                val game = localByKey.value[command.game.orEmpty()]?.let(::GameId)
                when {
                    game == null -> CommandResult(DeviceCommand.FAILED, "${ctx.services.deviceName} doesn't have this game any more.")
                    !romm.enabled -> CommandResult(DeviceCommand.FAILED, "Fuse RomM isn't on on ${ctx.services.deviceName}.")
                    else -> {
                        val plan = romm.uploadPlan(game)
                        val why = plan?.problem ?: if (plan == null) "That game isn't in the library any more." else romm.upload(game, plan)
                        if (why == null) CommandResult(DeviceCommand.DONE, "Sending to RomM from ${ctx.services.deviceName}") else CommandResult(DeviceCommand.FAILED, why)
                    }
                }
            }
            DeviceCommand.TRANSFER -> {
                val t = transfers.items.value.firstOrNull { it.key == command.key }
                val action = command.action?.let { runCatching { TransferAction.valueOf(it) }.getOrNull() }
                if (t == null || action == null) CommandResult(DeviceCommand.FAILED, "That transfer isn't on ${ctx.services.deviceName} any more.")
                else {
                    when (action) {
                        TransferAction.PAUSE -> transfers.pause(t.id)
                        TransferAction.RESUME -> transfers.resume(t.id)
                        TransferAction.RETRY -> transfers.retry(t.id)
                        TransferAction.CANCEL -> transfers.cancel(t.id)
                        TransferAction.MOVE_UP -> transfers.moveUp(t.id)
                        TransferAction.MOVE_DOWN -> transfers.moveDown(t.id)
                        TransferAction.MOVE_TO_TOP -> transfers.moveToTop(t.id)
                        TransferAction.REMOVE -> transfers.remove(t.id)
                        TransferAction.OPEN -> null
                    }
                    household.transfersChanged()
                    CommandResult(DeviceCommand.DONE)
                }
            }
            else -> CommandResult(DeviceCommand.FAILED, "${ctx.services.deviceName} doesn't know how to do that. Update Fuse there.")
        }

        override fun transfers(): List<RemoteTransfer> = transfers.items.value
            .filter { !it.status.finished || (it.finishedAt ?: 0) > ctx.now() - 60 * 60_000L }
            .map { t ->
                val live = transfers.live(t.id).value
                RemoteTransfer(
                    key = t.key, title = t.title, detail = t.detail, platform = t.platform, kind = t.kind.name, upload = t.upload,
                    status = t.status.name, phase = t.phase?.name, waiting = t.waiting?.name, done = if (t.running) live.doneBytes else t.doneBytes,
                    total = live.totalBytes ?: t.totalBytes, speed = live.speed, order = t.order, error = t.error, target = t.target,
                )
            }

        override fun sending(what: String?) {
            if (what == null) ctx.services.keepAliveForTransfers(false, "", null)
            else ctx.services.keepAliveForTransfers(true, "Sending a game to $what", null)
        }
    }

    // ------------------------------------------------------------------ what reach downloads need

    private val host = object : ReachTransferHost {
        override val peers: PeerBytes? get() = household as? PeerBytes
        override val http get() = ctx.services.http
        override fun romm(server: String): RommClient? = romm.clientFor(server)
        override fun online(device: String): Boolean = this@DefaultReachOps.online(device)
        override val relay: Boolean get() = settings.relay

        override suspend fun hashesFrom(source: String, game: String, path: String): Pair<String?, String?>? {
            runCatching { household.refresh() }
            val f = household.libraries.value.firstOrNull { it.device == source }?.entries?.firstOrNull { it.game == game }?.files?.firstOrNull { it.path == path } ?: return null
            return f.sha1 to f.md5
        }

        override suspend fun landed(job: ReachJob, item: TransferItem, paths: List<String>) {
            val gamePath = item.place?.let { p -> TransferPlaces.resolve(p, engine.drives.volumes.value) } ?: return
            val inLibrary = ctx.data.sources.all().any { it.enabled && FsPath.isWithin(FsPath.normalize(gamePath), FsPath.normalize(it.path)) }
            val systemDir = if (job.folder) FsPath.parent(gamePath) else gamePath
            if (!inLibrary) systemDir?.let { engine.add(it, LibrarySourceKind.PLATFORM_FOLDER) }
            val lead = if (job.folder) gamePath else paths.lastOrNull() ?: return
            val from = job.sources.firstOrNull { it.key == job.partFrom[job.files.firstOrNull()?.path] }?.name ?: job.sources.firstOrNull()?.name ?: "another device"
            ctx.data.cache.put(ARRIVED, FsPath.normalize(lead), json.encodeToString(Arrival.serializer(), Arrival(from, ctx.now())), ctx.now(), null)
            landedAt.update { it + (FsPath.normalize(lead) to job.game) }
            engine.rescan(ScanScope.PLATFORM, PlatformId(job.platform))
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(120_000) { engine.scan.first { it.phase == ScanPhase.DONE || it.phase == ScanPhase.FAILED } }
                refreshLocal()
                household.libraryChanged()
            }
            job.command?.let { settle(it, DeviceCommand.DONE, "On ${ctx.services.deviceName} now") }
        }
    }

    /** Where household games landed here (path to household id), so the library game takes the art found for it. */
    private val landedAt = MutableStateFlow<Map<String, String>>(emptyMap())

    private suspend fun adoptArt(id: GameId): Boolean {
        val path = ctx.data.games.get(id)?.location?.path?.let(FsPath::normalize) ?: return false
        val key = landedAt.value.entries.firstOrNull { (at, _) -> path == at || FsPath.isWithin(path, at) }?.value ?: return false
        val from = games.idOf(key)
        val n = ctx.data.media.copyMissing(MediaOwner.OfGame(from), MediaOwner.OfGame(id))
        if (n > 0) ctx.gameArtFound.update { it + 1 }
        return n > 0
    }

    companion object {
        const val ARRIVED = "reach.arrived"
        const val RECENT = 40
    }
}
