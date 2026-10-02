package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.ui.shell.settings.SettingsIndex
import io.github.matiyaaa.fuse.ui.shell.settings.settingsSections
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

/** Search with filters, suggestions and settings, through the store. */
class SearchStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-search").toFile()
        cache = Files.createTempDirectory("fuse-search-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "gba").mkdirs()
        File(root, "gba/Advance Wars (USA).gba").writeBytes(ByteArray(64))
        File(root, "gba/Golden Sun (USA).gba").writeBytes(ByteArray(64))
        File(root, "snes").mkdirs()
        File(root, "snes/Super Metroid (USA).sfc").writeBytes(ByteArray(64))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun filtersSuggestionsAndChips(): Unit = runBlocking {
        val store = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache), scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 3 } }

        val gba = store.library.search("platform:gba ").first()
        assertEquals(setOf("Advance Wars", "Golden Sun"), gba.games.map { it.title }.toSet())
        assertEquals("Game Boy Advance", gba.chips.single().label)
        // With a filter, only games answer.
        assertTrue(gba.platforms.isEmpty())

        val typo = store.library.search("metriod").first()
        assertEquals(listOf("Super Metroid"), typo.games.map { it.title })

        val values = store.library.search("platform:").first().suggestions.map { it.label }
        assertTrue("Game Boy Advance" in values && "Super Nintendo" in values, values.toString())
        assertEquals("platform:snes ", store.library.search("platform:sup").first().suggestions.single().text)

        assertTrue(store.library.search("").first().suggestions.any { it.label == "By system" })
        assertTrue(store.library.search("year:soon ").first().chips.single().valid.not())
    }

    @Test
    fun settingsAreFoundByTheirNamesAndOtherWords() {
        val rumble = SettingsIndex.search("rumble", settingsSections)
        assertEquals("Vibration", rumble.first().title)
        assertEquals("Settings, Controls", rumble.first().path)
        assertEquals("Storage and backups", SettingsIndex.search("backup", settingsSections).first().section.label)
        assertTrue(SettingsIndex.search("x", settingsSections).isEmpty())
    }
}
