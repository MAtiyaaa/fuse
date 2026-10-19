package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.library.steam.SteamArt
import io.github.matiyaaa.fuse.library.steam.SteamGameAction
import io.github.matiyaaa.fuse.library.steam.SteamGameEntry
import io.github.matiyaaa.fuse.library.steam.SteamGamePlanItem
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.platform.SteamIntegration
import io.github.matiyaaa.fuse.ui.shell.store.FakeServices
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Keep Steam in Step: off until chosen; on, only games that join or leave the library change Steam. */
class SteamInStepTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    /** Steam as Fuse sees it, noting what Fuse asks of it. */
    private class RecordingSteam : SteamIntegration {
        val added = CopyOnWriteArrayList<List<SteamGameEntry>>()
        val removed = CopyOnWriteArrayList<Set<Long>?>()
        @Volatile var inSteam: Set<Long> = emptySet()
        override val gameMode = false
        override suspend fun added() = false
        override suspend fun addFuse() = Result.success("")
        override suspend fun steamRunning() = false
        override suspend fun gamesInSteam() = inSteam
        override suspend fun planGames(games: List<SteamGameEntry>) = games.map { SteamGamePlanItem(it, SteamGameAction.CREATE) }
        override suspend fun addGames(games: List<SteamGameEntry>, art: Map<Long, SteamArt>, collections: Boolean): Result<String> {
            added += games
            inSteam = inSteam + games.map { it.gameId }
            return Result.success("Added")
        }
        override suspend fun removeGames(gameIds: Set<Long>?): Result<String> {
            removed += gameIds
            inSteam = inSteam - gameIds.orEmpty()
            return Result.success("Removed")
        }
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-instep").toFile()
        cache = Files.createTempDirectory("fuse-instep-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "gba").mkdirs()
        File(root, "gba/Golden Sun (USA).gba").writeBytes(ByteArray(1024) { it.toByte() })
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    private suspend fun library(): FuseStore {
        val store = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache), scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) {
            store.sources.scan.first { it.phase == ScanPhase.DONE }
            store.library.games(GameQuery(platform = PlatformId("gba"))).first { it.isNotEmpty() }
        }
        return store
    }

    private suspend fun FuseStore.newGame(file: String): Long {
        File(root, "gba/$file").writeBytes(ByteArray(2048) { (it * 7).toByte() })
        sources.rescan(ScanScope.FULL)
        return withTimeout(20_000) { library.games(GameQuery(platform = PlatformId("gba"))).first { it.size >= 2 } }.single { it.title.startsWith("Mother") }.id.value
    }

    private fun appWith(store: FuseStore, steam: RecordingSteam): AppState {
        val platform = object : PlatformUi by TestPlatform {
            override val steam: SteamIntegration = steam
        }
        return AppState(store, platform, scope, Route.Root(io.github.matiyaaa.fuse.model.Destination.HOME))
    }

    @Test
    fun offUntilChosenSoANewGameStaysOutOfSteam(): Unit = runBlocking {
        val store = library()
        assertEquals(false, store.prefs.value.steam.automatic)
        val steam = RecordingSteam()
        val app = appWith(store, steam)
        scope.launch { app.keepSteamInStep() }
        delay(300)
        store.newGame("Mother 3.gba")
        delay(1_500)
        assertTrue(steam.added.isEmpty())
        assertTrue(steam.removed.isEmpty())
    }

    @Test
    fun onlyGamesThatJoinOrLeaveChangeSteam(): Unit = runBlocking {
        val store = library()
        val existing = store.library.games(GameQuery()).first().single().id.value
        store.updatePrefs { it.copy(steam = it.steam.copy(automatic = true)) }
        val steam = RecordingSteam()
        val app = appWith(store, steam)
        scope.launch { app.keepSteamInStep() }
        delay(500)
        // A game already there when it was turned on isn't added by it.
        assertTrue(steam.added.isEmpty())

        val joined = store.newGame("Mother 3.gba")
        withTimeout(10_000) { while (steam.added.isEmpty()) delay(20) }
        assertEquals(listOf(joined), steam.added.single().map { it.gameId })
        assertTrue(existing !in steam.inSteam)
        // Fast Start, as How Steam Starts Them says by default.
        assertEquals(null, steam.added.single().single().direct)

        // It leaves the library: its entry comes out of Steam.
        store.library.removeFromFuse(io.github.matiyaaa.fuse.model.GameId(joined))
        withTimeout(15_000) { while (steam.removed.none { joined in it.orEmpty() }) delay(20) }
        assertTrue(joined !in steam.inSteam)
    }
}
