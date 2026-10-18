package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.integrations.net.NetRoute
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.RommCollectionCard
import io.github.matiyaaa.fuse.ui.shell.store.RommGame
import io.github.matiyaaa.fuse.ui.shell.store.RommLink
import io.github.matiyaaa.fuse.ui.shell.store.RommOps
import io.github.matiyaaa.fuse.ui.shell.store.RommPresence
import io.github.matiyaaa.fuse.ui.shell.store.RommState
import io.github.matiyaaa.fuse.ui.shell.store.RommSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * A RomM server that answers from the sample library: its games as the server's (some here, some
 * not, one downloading), its systems plus one the library hasn't got, and a collection.
 */
internal class AuditRomm(base: FuseStore, private val waiting: Int = 0) : RommOps by RommOps.None {
    private val cards = runBlocking { base.library.games(GameQuery()).first() }
    private val games = cards.take(24).mapIndexed { i, c ->
        RommGame(
            romId = 1_000L + i, card = c,
            presence = when (i % 5) { 0 -> RommPresence.INSTALLED; 3 -> RommPresence.DOWNLOADING; else -> RommPresence.ON_SERVER },
            game = c.id.takeIf { i % 5 == 0 }, sizeBytes = (i + 1) * 180_000_000L, isNew = i < 6,
        )
    }
    override val supported = true
    override val state: StateFlow<RommState> = MutableStateFlow(
        RommState(RommLink.ONLINE, NetRoute.LOCAL, version = "4.4.0", account = "pat", games = 1_284, newGames = 6, syncedAt = System.currentTimeMillis() - 4 * 60_000),
    )
    override val newGames: StateFlow<List<RommGame>> = MutableStateFlow(games.take(6))
    override val recent: StateFlow<List<RommGame>> = MutableStateFlow(games.drop(6))
    override val systems: StateFlow<List<RommSystem>> = MutableStateFlow(
        cards.map { it.platformId }.distinct().take(5).mapIndexed { i, p -> RommSystem(p, p.value, p.value.uppercase(), 40 + i * 13, if (i == 0) 40 else i * 3, 0) } +
            RommSystem(PlatformId("ps4"), "ps4", "PlayStation 4", 12, 0, 0),
    )
    override val collections: StateFlow<List<RommCollectionCard>> = MutableStateFlow(listOf(RommCollectionCard("user-1", "Couch co-op", false, 18, cards.take(4))))
    override val notOnServer: StateFlow<io.github.matiyaaa.fuse.ui.shell.store.RommNotOnServer> =
        MutableStateFlow(io.github.matiyaaa.fuse.ui.shell.store.RommNotOnServer(cards.drop(24).take(8), 31))
    override fun games(slug: String?): Flow<List<RommGame>> = flowOf(games)
    override fun markNewSeen() = Unit

    /** Saves on the server: a few games, each with its newest from one of the household's devices. */
    override suspend fun saveReport(): io.github.matiyaaa.fuse.ui.shell.store.RommSaveReport {
        val devices = listOf("Steam Deck", "Living Room PC", "AYN Thor")
        val standings = io.github.matiyaaa.fuse.sync.romm.RommSaveStanding.entries
        val now = java.time.Instant.now()
        val games = cards.take(7).mapIndexed { i, c ->
            val saves = (0 until (3 - i % 3)).map { k ->
                io.github.matiyaaa.fuse.romm.RommSave(
                    id = 500L + i * 10 + k, romId = 1_000L + i, fileName = "${c.title}.srm", sizeBytes = 8_192L * (i + 1),
                    contentHash = null, slot = null, emulator = "mgba",
                    updatedAt = now.minusSeconds(60L * (5 + i * 47 + k * 300)).toString(),
                    originDevice = null, originDeviceName = devices[(i + k) % devices.size],
                )
            }
            io.github.matiyaaa.fuse.ui.shell.store.RommSaveGame(1_000L + i, c.title, c.id.takeIf { i % 4 != 3 }, c.platformId, saves, standings[i % standings.size])
        }
        return io.github.matiyaaa.fuse.ui.shell.store.RommSaveReport("https://romm.home.arpa", games, games.sumOf { g -> g.saves.sumOf { it.sizeBytes } }, waiting)
    }

    override suspend fun sendWaitingSaves(): Int = waiting
}

/** The RomM tab: its line, the shelves with a game chosen, the systems and the options of a game not here. */
internal fun AuditDriver.rommScreens() {
    scenario("romm", "tab") {
        useLibrary { it.copy(romm = it.romm.copy(enabled = true)) }
        val base = libraryStore
        show(object : FuseStore by base { override val romm: RommOps = AuditRomm(base) })
        tab(Destination.CARTRIDGE)
        settle(900)
        // RomM holds Addons' first place, so the page opens on it.
        waitFor("New in Your Library")
        shoot("RomM's line", 1_200)
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.DPAD_RIGHT)
        shoot("a new game chosen", 1_500)
        tap(PadButton.DPAD_DOWN, 2)
        tap(PadButton.DPAD_RIGHT, 5)
        shoot("systems, one not in the library", 1_500)
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.DPAD_RIGHT)
        shoot("games not on RomM", 1_500)
        tap(PadButton.DPAD_DOWN)
        shoot("a collection chosen", 1_500)
        tap(PadButton.DPAD_UP, 3)
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.X)
        waitFor("Find Details and Art")
        shoot("a game's options")
    }

    scenario("romm", "saves tab") {
        useLibrary { it.copy(romm = it.romm.copy(enabled = true, configured = true, saveSync = true)) }
        val base = libraryStore
        show(object : FuseStore by base { override val romm: RommOps = AuditRomm(base) })
        tab(Destination.CARTRIDGE)
        settle(900)
        // RomM's saves are Addons' only part here, so the page opens on them.
        waitFor("Your saves on RomM")
        shoot("RomM's saves: every game, the newest of each and how this device's stands", 1_500)
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.A)
        waitFor("Open the Game")
        shoot("a game's saves on RomM, newest first", 900)
    }

    scenario("romm", "saves waiting to go") {
        useLibrary { it.copy(romm = it.romm.copy(enabled = true, configured = true, saveSync = true)) }
        val base = libraryStore
        show(object : FuseStore by base { override val romm: RommOps = AuditRomm(base, waiting = 2) })
        tab(Destination.CARTRIDGE)
        settle(900)
        waitFor("2 saves wait to go to RomM")
        shoot("saves that couldn't reach RomM, and sending them now", 1_500)
    }
}
