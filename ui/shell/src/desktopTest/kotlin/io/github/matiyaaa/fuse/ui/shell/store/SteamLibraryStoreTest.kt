package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Steam on a computer: its libraries are library folders, their games come from Steam's own
 * manifests at their folders under steamapps/common, games left unticked are hidden, and games
 * kept as Fuse's own shortcut files move onto the library keeping what they had.
 */
class SteamLibraryStoreTest {
    private lateinit var base: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("fuse-steam").toFile()
        cache = Files.createTempDirectory("fuse-steam-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        base.deleteRecursively()
        cache.deleteRecursively()
    }

    /** A Steam library with Celeste, Hades and Portal 2 installed. */
    private fun library(): File {
        val lib = File(base, "SteamLibrary")
        fun install(id: Long, name: String) {
            File(lib, "steamapps/common/$name").mkdirs()
            File(lib, "steamapps/common/$name/$name.exe").writeBytes(ByteArray(64))
            File(lib, "steamapps/appmanifest_$id.acf").writeText(
                "\"AppState\"\n{\n\t\"appid\"\t\"$id\"\n\t\"name\"\t\"$name\"\n\t\"StateFlags\"\t\"4\"\n\t\"installdir\"\t\"$name\"\n}\n",
            )
        }
        install(504230, "Celeste")
        install(1145360, "Hades")
        install(620, "Portal 2")
        return lib
    }

    @Test
    fun aSteamLibraryIsAFolderOfTheLibraryAndUntickedGamesAreHidden(): Unit = runBlocking {
        val lib = library()
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        services.steamPlaces = SteamPlaces(roots = listOf(lib.absolutePath))
        val store = createFuseStore(services, scope)
        val found = store.sources.findSteamGames()
        assertEquals(listOf("Celeste", "Hades", "Portal 2"), found.map { it.name })

        store.sources.addSteamGames(found.filter { it.name != "Portal 2" })
        val shown = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }
        assertEquals(listOf("Celeste", "Hades"), shown.map { it.title }.sorted())
        val source = store.sources.sources.value.single()
        assertEquals(LibrarySourceKind.STEAM_LIBRARY, source.kind)
        assertEquals(lib.absolutePath, source.path)
        val hidden = withTimeout(20_000) { store.library.games(GameQuery(set = GameSet.HIDDEN)).first { it.isNotEmpty() } }
        assertEquals(listOf("Portal 2"), hidden.map { it.title })
        // Nothing is written for Steam games on a computer.
        assertTrue(!File(cache, "kept/steam").exists())
    }

    @Test
    fun gamesKeptAsShortcutsMoveOntoTheLibraryAndKeepTheirFavourite(): Unit = runBlocking {
        val lib = library()
        val data = FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath))
        // 0.2.7: Celeste as a shortcut file in Fuse's own folder, a favourite.
        val kept = File(cache, "kept/steam").apply { mkdirs() }
        File(kept, "Celeste.steam").writeText("504230")
        val first = FakeServices(data, cache)
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val old = createFuseStore(first, oldScope)
        data.sources.add(kept.absolutePath, "Steam", LibrarySourceKind.SHORTCUTS)
        old.sources.rescan(io.github.matiyaaa.fuse.model.ScanScope.QUICK)
        val celeste = withTimeout(20_000) { old.library.games(GameQuery()).first { it.size == 1 } }.single()
        old.library.setFavorite(celeste.id, true)
        oldScope.cancel()

        // 0.2.8 starts with Steam where it can find it.
        val services = FakeServices(data, cache)
        services.steamPlaces = SteamPlaces(roots = listOf(lib.absolutePath))
        val store = createFuseStore(services, scope)
        withTimeout(30_000) { store.sources.sources.first { list -> list.any { it.kind == LibrarySourceKind.STEAM_LIBRARY } && list.none { it.kind == LibrarySourceKind.SHORTCUTS } } }
        val moved = withTimeout(30_000) {
            store.library.games(GameQuery()).first { games -> games.size == 3 }
        }
        val same = moved.single { it.id == celeste.id }
        assertTrue(same.favorite)
        val game = withTimeout(10_000) { store.library.game(celeste.id).first { it != null } }!!
        assertEquals(File(lib, "steamapps/common/Celeste").absolutePath, game.game.location.path)
    }

    /**
     * An EmuDeck or ES-DE games folder keeps Steam's own shortcuts in a `steam` folder. On a
     * computer whose person said no to Steam (or never added it) they stay out; yes brings them in,
     * and no again hides them.
     */
    @Test
    fun aGamesFoldersSteamShortcutsFollowTheSteamAnswer(): Unit = runBlocking {
        val roms = File(base, "roms")
        File(roms, "snes").mkdirs()
        File(roms, "snes/Chrono Trigger.sfc").writeBytes(ByteArray(64))
        File(roms, "steam").mkdirs()
        File(roms, "steam/Hades.desktop").writeText("[Desktop Entry]\nName=Hades\nExec=steam steam://rungameid/1145360\nType=Application\n")
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(roms.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val steam = io.github.matiyaaa.fuse.model.PlatformId("steam")

        // Never added Steam on this computer: only the SNES game.
        withTimeout(20_000) { store.sources.scan.first { it.phase == io.github.matiyaaa.fuse.model.ScanPhase.DONE } }
        val first = withTimeout(20_000) { store.library.platforms.first { s -> s.any { it.platform.id.value == "snes" } } }
        assertTrue(first.none { it.platform.id == steam })

        // Yes: Hades comes in.
        store.updatePrefs { it.copy(steamGames = io.github.matiyaaa.fuse.ui.shell.store.impl.STEAM_ON) }
        withTimeout(20_000) { store.library.platforms.first { s -> s.any { it.platform.id == steam && it.gameCount == 1 } } }

        // No: gone from the library again (kept hidden, nothing deleted).
        store.updatePrefs { it.copy(steamGames = io.github.matiyaaa.fuse.ui.shell.store.impl.STEAM_OFF) }
        withTimeout(20_000) { store.library.platforms.first { s -> s.none { it.platform.id == steam } } }
        val hidden = withTimeout(20_000) { store.library.games(GameQuery(set = GameSet.HIDDEN)).first { it.isNotEmpty() } }
        assertEquals(listOf("Hades"), hidden.map { it.title })

        // Yes again: back, not hidden.
        store.updatePrefs { it.copy(steamGames = io.github.matiyaaa.fuse.ui.shell.store.impl.STEAM_ON) }
        withTimeout(20_000) { store.library.platforms.first { s -> s.any { it.platform.id == steam && it.gameCount == 1 } } }
    }
}
