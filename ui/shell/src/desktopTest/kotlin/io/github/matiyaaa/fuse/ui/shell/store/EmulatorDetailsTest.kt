package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** An emulator's page and the emulators offered for one game. */
class EmulatorDetailsTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-emu").toFile()
        cache = Files.createTempDirectory("fuse-emu-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "gba").mkdirs()
        File(root, "gba/Advance Wars (USA).gba").writeBytes(ByteArray(64))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun gamesGetTheirSystemsEmulatorsAndPagesSayWhatTheyRun(): Unit = runBlocking {
        val store = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache), scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        withTimeout(10_000) { store.emulators.installed.first { it.isNotEmpty() } }

        val options = store.emulators.optionsForGame(game.id)
        // Shortcut openers only run shortcuts; they aren't offered for a cartridge game.
        assertFalse(options.any { it.name == "Desktop shortcut" || it.name == "Shell script" }, options.toString())
        val mgba = options.first { it.id == EmulatorId("linux.mgba") }
        assertTrue(mgba.installed)
        assertNull(mgba.unavailable)

        store.emulators.setPlatformEmulator(PlatformId("gba"), EmulatorId("linux.mgba"))
        val details = assertNotNull(store.emulators.details(EmulatorId("linux.mgba")))
        assertEquals(listOf("Game Boy Advance"), details.systems)
        assertEquals(listOf("Game Boy Advance"), details.chosenFor)
        assertEquals("PATH", details.foundVia)
        assertEquals(game.id, store.emulators.testGame(EmulatorId("linux.mgba"))?.id)
        assertNull(store.emulators.testGame(EmulatorId("linux.duckstation")))
    }
}
