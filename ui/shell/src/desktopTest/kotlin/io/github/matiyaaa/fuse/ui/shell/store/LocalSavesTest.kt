package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A game's saves on this device show without any save sync. */
class LocalSavesTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-local-saves").toFile()
        cache = Files.createTempDirectory("fuse-local-saves-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun aGamesSaveFileShowsWithFuseOneOff() = runBlocking {
        File(root, "gba").mkdirs()
        File(root, "gba/Golden Sun (USA).gba").writeBytes(ByteArray(64))
        lateinit var services: FakeServices
        services = FakeServices(
            FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache,
            installedEmulators = listOf(InstalledEmulator(EmulatorId("linux.mgba"), "mGBA", Host.LINUX, "/usr/bin/mgba-qt", platforms = setOf(PlatformId("gba")), detectedVia = "PATH")),
            sync = { port, sc ->
                io.github.matiyaaa.fuse.sync.JvmSyncService(
                    File(cache, "sync"), services.data.settings, services.secrets, port, "LINUX", "Desk", "test",
                    io.github.matiyaaa.fuse.sync.NoHostLifetime("test"), sc,
                )
            },
        )
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        assertEquals(false, store.prefs.value.sync.enabled, "Fuse One is off")

        val before = store.library.localSaves(game.id)
        assertEquals("mGBA", before.emulator)
        assertTrue(before.saves.isEmpty())
        assertNotNull(before.note)

        // mGBA keeps the save beside the game.
        File(root, "gba/Golden Sun (USA).sav").writeBytes(ByteArray(32_768) { 1 })
        val after = store.library.localSaves(game.id)
        val save = after.saves.single()
        assertEquals(32_768L, save.bytes)
        assertTrue(save.path.endsWith("Golden Sun (USA).sav"), save.path)
        assertEquals(io.github.matiyaaa.fuse.sync.SaveKind.SAVE, save.kind)
    }
}
