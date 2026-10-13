package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** RetroArch on a computer plays a game with a core that is really installed, or Fuse says what to install. */
class RetroArchCoreLaunchTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    private val retroArch = InstalledEmulator(EmulatorId("linux.retroarch"), "RetroArch", Host.LINUX, "/usr/bin/retroarch", platforms = setOf(PlatformId("nes"), PlatformId("gba")), detectedVia = "PATH")
    private val mgba = InstalledEmulator(EmulatorId("linux.mgba"), "mGBA", Host.LINUX, "/usr/bin/mgba-qt", platforms = setOf(PlatformId("gba")), detectedVia = "PATH")

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-cores").toFile()
        cache = Files.createTempDirectory("fuse-cores-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "nes").mkdirs()
        File(root, "nes/Mega Man 2 (USA).nes").writeBytes(ByteArray(64))
        File(root, "gba").mkdirs()
        File(root, "gba/Golden Sun (USA).gba").writeBytes(ByteArray(64))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    private suspend fun store(vararg emulators: InstalledEmulator, cores: Set<String>?): Pair<FuseStore, FakeServices> {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse-${System.nanoTime()}.db").absolutePath)), cache, installedEmulators = emulators.toList())
        services.retroArchCores = cores
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }
        return store to services
    }

    private suspend fun FuseStore.game(platform: String) = library.games(GameQuery()).first().first { it.platformId.value == platform }

    @Test
    fun aSystemPlaysInTheCoreThatIsInstalledNotTheUsualOne() = runBlocking {
        // Only Nestopia is installed; FCEUmm is RetroArch's usual NES core.
        val (store, services) = store(retroArch, cores = setOf("nestopia"))
        assertIs<LaunchOutcome.Started>(store.library.launch(store.game("nes").id))
        val argv = assertIs<LaunchPlan.Command>(services.launched.single().plan).argv
        assertTrue(argv.any { it.endsWith("nestopia_libretro.so") }, argv.toString())
        assertTrue(argv.none { "fceumm" in it }, argv.toString())
    }

    @Test
    fun withoutACoreForTheSystemItsOwnEmulatorPlaysIt() = runBlocking {
        // RetroArch has an NES core but none for GBA; mGBA itself is installed.
        val (store, services) = store(retroArch, mgba, cores = setOf("nestopia"))
        assertIs<LaunchOutcome.Started>(store.library.launch(store.game("gba").id))
        assertEquals("linux.mgba", services.launched.single().installed?.id?.value)
    }

    @Test
    fun withNothingThatPlaysItFuseSaysWhatToInstall() = runBlocking {
        val (store, services) = store(retroArch, cores = setOf("nestopia"))
        val problem = assertIs<LaunchOutcome.Problem>(store.library.launch(store.game("gba").id)).problem
        assertEquals("RetroArch has no Game Boy Advance core", problem.title)
        assertTrue("mGBA" in problem.message, problem.message)
        assertTrue(services.launched.isEmpty(), "nothing was started")
    }

    @Test
    fun whereFuseCantSeeTheCoresItStartsAsBefore() = runBlocking {
        // A copy whose cores Fuse can't see (from Steam, say): RetroArch is asked with the usual core.
        val (store, services) = store(retroArch, cores = null)
        assertIs<LaunchOutcome.Started>(store.library.launch(store.game("gba").id))
        assertEquals("linux.retroarch", services.launched.single().installed?.id?.value)
    }
}
