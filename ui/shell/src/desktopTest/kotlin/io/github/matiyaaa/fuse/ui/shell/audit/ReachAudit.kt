package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.sync.DeviceCommand
import io.github.matiyaaa.fuse.sync.RemoteTransfer
import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferLive
import io.github.matiyaaa.fuse.transfer.TransferPhase
import io.github.matiyaaa.fuse.transfer.TransferSettings
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.github.matiyaaa.fuse.transfer.TransferSummary
import io.github.matiyaaa.fuse.transfer.WaitReason
import io.github.matiyaaa.fuse.ui.shell.app.CompanionPage
import io.github.matiyaaa.fuse.ui.shell.app.Spotlight
import io.github.matiyaaa.fuse.ui.shell.store.CopyCheck
import io.github.matiyaaa.fuse.ui.shell.store.CopyPlace
import io.github.matiyaaa.fuse.ui.shell.store.CopyView
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCopies
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdDevice
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdGame
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdState
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdSystem
import io.github.matiyaaa.fuse.ui.shell.store.ReachOps
import io.github.matiyaaa.fuse.ui.shell.store.RemoteTransferRow
import io.github.matiyaaa.fuse.ui.shell.store.RommOps
import io.github.matiyaaa.fuse.ui.shell.store.SendState
import io.github.matiyaaa.fuse.ui.shell.store.SendTarget
import io.github.matiyaaa.fuse.ui.shell.store.TransferAction
import io.github.matiyaaa.fuse.ui.shell.store.TransferRow
import io.github.matiyaaa.fuse.ui.shell.store.TransfersOps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

private const val HOUR = 60 * 60_000L
private const val DAY = 24 * HOUR

/**
 * A household with two other devices (a Gaming PC that is around, a Thor that went away an hour
 * ago) and their games from the sample library, every copy of a game, and the Thor's transfers.
 */
internal class AuditReach(base: FuseStore) : ReachOps by ReachOps.None {
    private val now = System.currentTimeMillis()
    private val cards = runBlocking { base.library.games(GameQuery()).first() }
    private val theirs = cards.drop(30).take(14).ifEmpty { cards.take(14) }
    private val games = theirs.mapIndexed { i, c ->
        HouseholdGame(
            id = c.id, card = c, key = "k$i",
            holders = if (i % 3 == 0) listOf("Gaming PC", "Thor") else if (i % 3 == 1) listOf("Gaming PC") else listOf("Thor"),
            onlineHolders = if (i % 3 == 2) 0 else 1,
            sizeBytes = (i + 2) * 420_000_000L, addedAt = now - i * 9 * HOUR,
            downloading = i == 1, transfer = "t1".takeIf { i == 1 },
        )
    }
    override val supported = true
    override val self = "deck"
    override val state: StateFlow<HouseholdState> = MutableStateFlow(
        HouseholdState(
            supported = true, linked = true, games = games.size,
            devices = listOf(
                HouseholdDevice("pc", "Gaming PC", "LINUX", online = true, lastSeen = now - 20_000, accepts = true, games = 212),
                HouseholdDevice("thor", "Thor", "ANDROID", online = false, lastSeen = now - HOUR, accepts = true, games = 96),
            ),
        ),
    )
    override val recent: StateFlow<List<HouseholdGame>> = MutableStateFlow(games.sortedByDescending { it.addedAt })
    override val systems: StateFlow<List<HouseholdSystem>> = MutableStateFlow(
        games.groupBy { it.card.platformId }.map { (p, l) -> HouseholdSystem(p, p.value.uppercase(), l.size) },
    )
    override fun games(platform: PlatformId?): Flow<List<HouseholdGame>> = flowOf(if (platform == null) games else games.filter { it.card.platformId == platform })
    override val notOnRomm: StateFlow<List<HouseholdGame>> = MutableStateFlow(games.take(8))

    override fun availability(id: GameId): Flow<GameCopies?> = flowOf(
        GameCopies(
            listOf(
                CopyView(CopyPlace.HERE, "here", "Steam Deck", sizeBytes = 1_840_000_000, check = CopyCheck.VERIFIED, date = now - 2 * DAY, downloaded = true, from = "Gaming PC"),
                CopyView(CopyPlace.DEVICE, "pc", "Gaming PC", "LINUX", online = true, lastSeen = now - 20_000, sizeBytes = 1_840_000_000, check = CopyCheck.VERIFIED, date = now - 40 * DAY),
                CopyView(CopyPlace.DEVICE, "thor", "Thor", "ANDROID", online = false, lastSeen = now - HOUR, sizeBytes = 1_790_000_000, check = CopyCheck.DIFFERENT, date = now - 6 * DAY, downloaded = true, from = "RomM"),
                CopyView(CopyPlace.ROMM, "main", "RomM", online = true, sizeBytes = 1_840_000_000, check = CopyCheck.VERIFIED, date = now - 90 * DAY),
            ),
        ),
    )

    override suspend fun sendTargets(id: GameId): List<SendTarget> = listOf(
        SendTarget("deck", "Steam Deck", "LINUX", SendState.HAS_IT),
        SendTarget("pc", "Gaming PC", "LINUX", SendState.HAS_IT, now - 20_000),
        SendTarget("thor", "Thor", "ANDROID", SendState.QUEUED, now - HOUR),
        SendTarget("phone", "Retroid Pocket 5", "ANDROID", SendState.READY, now - 30_000),
        SendTarget("tv", "Living Room", "LINUX", SendState.REFUSES, now - 5 * DAY),
    )

    override suspend fun canUploadToRomm(id: GameId) = true
    override val remoteTransfers: StateFlow<List<RemoteTransferRow>> = MutableStateFlow(emptyList())
    override val requests: StateFlow<List<DeviceCommand>> = MutableStateFlow(emptyList())
}

/** Downloads with this device's own transfer, the Thor's (away), and one waiting for the Thor to be back. */
private class AuditReachTransfers(cards: List<GameCard>) : TransfersOps {
    private val gb = 1L shl 30
    private val now = System.currentTimeMillis()
    private val own = TransferItem(
        "t1", "reach:k1", "reach", TransferDirection.DOWNLOAD, TransferKind.GAME, cards.getOrNull(31)?.title ?: "Velvet Orbit",
        detail = "From Gaming PC", platform = cards.getOrNull(31)?.platformId?.value, totalBytes = 3 * gb, doneBytes = gb, status = TransferStatus.ACTIVE, phase = TransferPhase.TRANSFERRING,
        target = "Internal storage  ·  roms/psx",
    )
    private val thorActive = TransferItem(
        "remote:thor:romm:rom:9", "romm:rom:9", "remote", TransferDirection.DOWNLOAD, TransferKind.GAME, cards.getOrNull(33)?.title ?: "Hollow Meridian",
        platform = cards.getOrNull(33)?.platformId?.value, totalBytes = 2 * gb, doneBytes = (0.86 * gb).toLong(), status = TransferStatus.ACTIVE, phase = TransferPhase.TRANSFERRING,
    )
    private val thorQueued = thorActive.copy(id = "remote:thor:reach:k4", key = "reach:k4", title = cards.getOrNull(34)?.title ?: "Glass Lantern", platform = cards.getOrNull(34)?.platformId?.value, status = TransferStatus.QUEUED, doneBytes = 0, phase = null)
    private val waiting = TransferItem(
        "request:c1", "request:c1", "request", TransferDirection.DOWNLOAD, TransferKind.GAME, cards.getOrNull(35)?.title ?: "Ember Tactics",
        platform = cards.getOrNull(35)?.platformId?.value, status = TransferStatus.WAITING, waiting = WaitReason.DEVICE, waitingFor = "Thor",
    )
    override val rows = MutableStateFlow(
        listOf(
            TransferRow(own, listOf(TransferAction.PAUSE, TransferAction.CANCEL)),
            TransferRow(thorActive, listOf(TransferAction.PAUSE, TransferAction.CANCEL), device = "thor", deviceName = "Thor", deviceOnline = false, deviceSeen = now - HOUR),
            TransferRow(thorQueued, listOf(TransferAction.PAUSE, TransferAction.CANCEL), device = "thor", deviceName = "Thor", deviceOnline = false, deviceSeen = now - HOUR),
            TransferRow(waiting, listOf(TransferAction.CANCEL), device = "thor", deviceName = "Thor", deviceOnline = false, deviceSeen = now - HOUR),
        ),
    )
    override val summary = MutableStateFlow(TransferSummary(activeDownloads = 1, progress = 0.33f))
    override val settings = MutableStateFlow(TransferSettings())
    override fun live(id: String) = MutableStateFlow(
        when (id) {
            "t1" -> TransferLive(gb, 3 * gb, 38L shl 20, 54)
            "remote:thor:romm:rom:9" -> TransferLive((0.86 * gb).toLong(), 2 * gb, 0, null)
            else -> TransferLive()
        },
    )
    override fun act(id: String, action: TransferAction) = Unit
    override fun pauseAll() = Unit
    override fun resumeAll() = Unit
    override fun clearFinished() = Unit
}

/**
 * The Remote Library: the RomM tab's other devices' shelf and a system's sections, a game's
 * "Available on" with a copy's options and Send to, the Sync tab's way in and the Remote Library
 * itself, Downloads with another device's transfers, its settings, and the second screen.
 */
internal fun AuditDriver.reachScreens() {
    fun withReach(base: FuseStore, romm: Boolean = true, transfers: Boolean = false): FuseStore {
        val reach = AuditReach(base)
        val r: RommOps = if (romm) AuditRomm(base) else base.romm
        val t = if (transfers) AuditReachTransfers(runBlocking { base.library.games(GameQuery()).first() }) else base.transfers
        return object : FuseStore by base {
            override val reach: ReachOps = reach
            override val romm: RommOps = r
            override val transfers: TransfersOps = t
        }
    }

    scenario("reach", "romm tab") {
        useLibrary { it.copy(romm = it.romm.copy(enabled = true)) }
        show(withReach(libraryStore))
        tab(Destination.CARTRIDGE)
        waitFor("New in Your Library")
        settle(900)
        tap(PadButton.DPAD_DOWN, 6)
        tap(PadButton.DPAD_RIGHT)
        waitFor("Not on RomM, from Another Device")
        shoot("other devices' games at the foot of RomM", 1_400)
        tap(PadButton.X)
        waitFor("Send to Another Device")
        shoot("another device's game: its options")
    }

    scenario("reach", "game info") {
        useLibrary { it.copy(romm = it.romm.copy(enabled = true)) }
        show(withReach(libraryStore))
        tab(Destination.CARTRIDGE)
        // A press can land while the tab row is still filling in: step on until RomM's page shows.
        repeat(3) {
            settle(600)
            if (!hasText("New in Your Library")) tap(PadButton.R1)
        }
        waitFor("New in Your Library")
        settle(900)
        // Not on RomM: this device's own games, the fourth shelf down.
        tap(PadButton.DPAD_DOWN, 4)
        waitFor("On this device, not on your RomM server")
        tap(PadButton.A)
        waitFor("Available on")
        shoot("a game's page with its buttons", 1_400)
        tap(PadButton.DPAD_DOWN, 2)
        shoot("Available on: here, two devices and RomM", 1_400)
        tap(PadButton.DPAD_RIGHT, 2)
        shoot("the Thor's copy chosen")
        tap(PadButton.A)
        waitFor("A different version")
        shoot("a copy's options")
        tapText("Send to Another Device")
        waitFor("Sent once it is back", ignoreCase = true)
        shoot("Send to: every device and where each stands", 1_200)
    }

    scenario("reach", "romm system") {
        useLibrary { it.copy(romm = it.romm.copy(enabled = true)) }
        val base = libraryStore
        val store = withReach(base)
        val sys = runBlocking { store.romm.systems.first().first() }
        // A system's page: its games on RomM, then this device's and other devices' that RomM hasn't got.
        val here = runBlocking { base.library.games(GameQuery(platform = sys.platform)).first() }.take(5)
        val romm = object : RommOps by store.romm {
            override fun notOnServerOn(platform: PlatformId): Flow<List<GameCard>> = flowOf(here)
        }
        val reach = object : ReachOps by store.reach {
            override val notOnRomm: StateFlow<List<HouseholdGame>> = MutableStateFlow(
                runBlocking { store.reach.games(null).first() }.take(4).map { it.copy(card = it.card.copy(platformId = sys.platform!!)) },
            )
        }
        show(object : FuseStore by store { override val romm: RommOps = romm; override val reach: ReachOps = reach })
        tab(Destination.CARTRIDGE)
        waitFor("New in Your Library")
        tap(PadButton.DPAD_DOWN, 3)
        tap(PadButton.A)
        waitFor("On RomM")
        shoot("a system in sections", 1_400)
        tap(PadButton.DPAD_DOWN, 6)
        shoot("down into the other sections, in the same column", 1_200)
    }

    scenario("reach", "sync tab and the Remote Library") {
        useSync(asHost = false)
        show(withReach(libraryStore, romm = false, transfers = true))
        tab(Destination.CARTRIDGE)
        tap(PadButton.DPAD_UP)
        focusText("Sync") { tap(PadButton.DPAD_RIGHT) }
        tap(PadButton.DPAD_DOWN)
        waitFor("Remote Library")
        shoot("the Sync tab with the Remote Library", 1_400)
        tap(PadButton.DPAD_DOWN)
        shoot("the Remote Library's card chosen")
        tap(PadButton.A)
        waitFor("Recently Added")
        shoot("the Remote Library", 1_400)
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.DPAD_RIGHT, 2)
        shoot("a recent game chosen")
        tap(PadButton.DPAD_DOWN)
        shoot("its systems")
        tap(PadButton.A)
        waitFor("not on this device")
        shoot("one system's games", 1_400)
        tap(PadButton.B)
        tap(PadButton.DPAD_UP, 2)
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Downloading to Thor")
        shoot("Downloads with another device's transfers", 1_400)
        tap(PadButton.DPAD_DOWN, 4)
        tap(PadButton.X)
        shoot("another device's transfer: its options")
    }

    scenario("reach", "settings") {
        useSync(asHost = false)
        show(withReach(libraryStore))
        openSettings()
        focusText("Addons")
        tap(PadButton.DPAD_RIGHT)
        tapText("Remote Library")
        waitFor("Share This Device's Games")
        shoot("Settings, Addons, Remote Library", 1_200)
    }

    scenario("reach", "second screen") {
        useLibrary()
        val store = withReach(libraryStore)
        val game = runBlocking { store.library.games(GameQuery()).first().first() }
        CompanionPage.current.value = 0
        Spotlight.set(null)
        view = AuditView.Companion(store, platform, DualScreenMode.LIBRARY_COMPANION)
        settle(1_200)
        Spotlight.set(game.id)
        waitFor("Gaming PC")
        shoot("a game in focus, with where else it is", 2_000)
        Spotlight.set(null)
    }
}
