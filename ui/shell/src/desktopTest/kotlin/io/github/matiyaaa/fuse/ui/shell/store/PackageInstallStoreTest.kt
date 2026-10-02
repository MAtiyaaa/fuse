package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Package installs with RPCS3 and Vita3K, through the store. */
class PackageInstallStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-pkg").toFile()
        cache = Files.createTempDirectory("fuse-pkg-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "ps3").mkdirs()
        File(root, "ps3/Sky Racer [NPUB30001].iso").writeBytes(ByteArray(64))
        File(root, "ps3/Sky Racer [NPUB30001] (Update 1.02).pkg").writeBytes(ByteArray(64))
        File(root, "ps3/Someone Else [NPUB39999].pkg").writeBytes(ByteArray(64))
        File(root, "psvita").mkdirs()
        File(root, "psvita/Pocket Quest [PCSE00001].vpk").writeBytes(ByteArray(64))
        File(root, "psvita/Pocket Quest [PCSE00001].pkg").writeBytes(ByteArray(64))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun packagesGoToTheirEmulatorsInstaller(): Unit = runBlocking {
        val services = FakeServices(
            FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache,
            installedEmulators = listOf(
                InstalledEmulator(EmulatorId("linux.rpcs3"), "RPCS3", Host.LINUX, "/usr/bin/rpcs3", platforms = setOf(PlatformId("ps3")), detectedVia = "PATH"),
                InstalledEmulator(EmulatorId("linux.vita3k"), "Vita3K", Host.LINUX, "/usr/bin/Vita3K", platforms = setOf(PlatformId("psvita")), detectedVia = "PATH"),
            ),
        )
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val games = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }

        val ps3 = games.first { it.platformId == PlatformId("ps3") }
        val option = store.library.packages(ps3.id).single()
        assertEquals("RPCS3", option.emulatorName)
        assertEquals("Update", option.kind)
        assertEquals(false, option.needsKey)
        assertIs<LaunchOutcome.Started>(store.library.installPackage(option))
        val argv = (services.launched.last().plan as LaunchPlan.Command).argv
        assertEquals(listOf("/usr/bin/rpcs3", "--installpkg", File(root, "ps3/Sky Racer [NPUB30001] (Update 1.02).pkg").absolutePath), argv)
        // Installing isn't playing.
        assertNull(services.data.playSessions.openSession().first())

        val vita = games.first { it.platformId == PlatformId("psvita") }
        val vitaOption = store.library.packages(vita.id).single()
        assertTrue(vitaOption.needsKey)
        assertIs<LaunchOutcome.Problem>(store.library.installPackage(vitaOption, null))
        assertIs<LaunchOutcome.Started>(store.library.installPackage(vitaOption, "ZRIFKEY"))
        assertEquals("ZRIFKEY", (services.launched.last().plan as LaunchPlan.Command).argv.last())
        // The key is nowhere in Fuse's own records.
        assertTrue(store.health.diagnostics().contains("ZRIFKEY").not())
    }
}
