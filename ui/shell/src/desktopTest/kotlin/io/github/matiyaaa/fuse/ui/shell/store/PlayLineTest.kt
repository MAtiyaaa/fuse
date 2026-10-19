package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The line under Play says what pressing it does: where the game starts, and with what. */
class PlayLineTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-playline").toFile()
        cache = Files.createTempDirectory("fuse-playline-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(root, cache).forEach { it.deleteRecursively() }
    }

    @Test
    fun theLineSaysWhereItStartsWithItsCoreAndFrameGeneration(): Unit = runBlocking {
        val duck = InstalledEmulator(EmulatorId("linux.duckstation"), "DuckStation", Host.LINUX, "/usr/bin/duckstation-qt", platforms = setOf(PlatformId("psx")), detectedVia = "PATH")
        val ra = InstalledEmulator(EmulatorId("linux.retroarch"), "RetroArch", Host.LINUX, "/usr/bin/retroarch", platforms = setOf(PlatformId("gba")), detectedVia = "PATH")
        val steam = InstalledEmulator(EmulatorId("linux.steam"), "Steam", Host.LINUX, "/usr/bin/steam", platforms = setOf(PlatformId("steam")), detectedVia = "PATH")
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache, installedEmulators = listOf(duck, ra, steam))
        File(root, "psx").mkdirs()
        File(root, "psx/Crash Racer (USA).cue").writeText("FILE \"Crash Racer (USA).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n")
        File(root, "psx/Crash Racer (USA).bin").writeBytes(ByteArray(2352))
        File(root, "gba").mkdirs()
        File(root, "gba/Sky Racer (USA).gba").writeBytes(ByteArray(512))

        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val games = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size >= 2 } }
        suspend fun line(title: String, check: (String?) -> Boolean = { true }): String? =
            withTimeout(10_000) { store.library.game(games.single { it.title.startsWith(title) }.id).first { d -> d != null && check(d.emulator.playLine) } }!!.emulator.playLine

        assertEquals("Starts in DuckStation.", line("Crash Racer"))
        assertEquals("Starts in RetroArch, with the mGBA core.", line("Sky Racer"))
        // A game of Steam's own library starts through Steam by its app id.
        val lib = File(root.parentFile, "${root.name}-steam")
        File(lib, "steamapps/common/Portal/Portal.exe").apply { parentFile.mkdirs() }.writeBytes(ByteArray(64))
        File(lib, "steamapps/appmanifest_400.acf").writeText("\"AppState\"\n{\n\t\"appid\"\t\"400\"\n\t\"name\"\t\"Portal\"\n\t\"StateFlags\"\t\"4\"\n\t\"installdir\"\t\"Portal\"\n}\n")
        services.steamPlaces = io.github.matiyaaa.fuse.ui.shell.store.SteamPlaces(roots = listOf(lib.absolutePath))
        store.sources.addSteamGames(store.sources.findSteamGames())
        val portal = withTimeout(20_000) { store.library.games(GameQuery()).first { l -> l.any { it.title == "Portal" } } }.single { it.title == "Portal" }
        assertEquals(
            "Starts through Steam, which keeps the game's own saves and play time.",
            withTimeout(10_000) { store.library.game(portal.id).first { it?.emulator?.playLine != null } }!!.emulator.playLine,
        )
        lib.deleteRecursively()
        // Frame generation where lsfg-vk is set up and chosen.
        services.frameGeneration = true
        store.settings.set(ScopedSettings.FrameGeneration, ScopeRef.platform(PlatformId("gba")), 2)
        assertEquals("Starts in RetroArch, with the mGBA core and frame generation at 2x.", line("Sky Racer") { it?.contains("frame") == true })
    }
}
