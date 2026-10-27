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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Fuse Instant: library folders are followed, and a game copied in joins the library by itself. */
class FolderFollowTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-follow").toFile()
        cache = Files.createTempDirectory("fuse-follow-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "snes").mkdirs()
        File(root, "snes/Super Metroid (USA).sfc").writeBytes(ByteArray(64))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    /** Remembers what it was asked to follow and lets the test say a folder changed. */
    private class Recorder : FolderWatch {
        val folders = MutableStateFlow<List<String>>(emptyList())
        var changed: () -> Unit = {}
        var closed = 0

        override fun watch(folders: List<String>, onChange: () -> Unit): AutoCloseable {
            this.folders.value = folders
            changed = onChange
            return AutoCloseable { closed++ }
        }
    }

    @Test
    fun aGameCopiedInJoinsTheLibrary(): Unit = runBlocking {
        val watch = Recorder()
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache).apply { folderWatch = watch }
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }

        // The library folder and the system folder the scan found in it.
        val followed = withTimeout(20_000) { watch.folders.first { it.size >= 2 } }
        assertTrue(followed.any { File(it) == root.absoluteFile }, followed.toString())
        assertTrue(followed.any { File(it) == File(root, "snes").absoluteFile }, followed.toString())

        // A game copied in, the system telling: in the library with no scan asked for.
        File(root, "snes/Chrono Trigger (USA).sfc").writeBytes(ByteArray(64))
        File(root, "snes").setLastModified(System.currentTimeMillis() + 2_000)
        watch.changed()
        val after = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }
        assertEquals(setOf("Super Metroid", "Chrono Trigger"), after.map { it.title }.toSet())

        // A new system folder is followed too, once a scan has found it.
        File(root, "gba").mkdirs()
        File(root, "gba/Golden Sun (USA).gba").writeBytes(ByteArray(64))
        root.setLastModified(System.currentTimeMillis() + 4_000)
        watch.changed()
        withTimeout(20_000) { watch.folders.first { list -> list.any { File(it) == File(root, "gba").absoluteFile } } }
        assertTrue(watch.closed >= 1, "the old watch is let go when the folders change")

        // A game kept in a folder of its own: its folder is followed too, so a second disc copied
        // into it is noticed, and a game folder made later is followed once a scan has run.
        File(root, "snes/Hacks").mkdirs()
        File(root, "snes/Hacks/Super Metroid Redesign.sfc").writeBytes(ByteArray(64))
        File(root, "snes").setLastModified(System.currentTimeMillis() + 6_000)
        watch.changed()
        withTimeout(20_000) { watch.folders.first { list -> list.any { File(it) == File(root, "snes/Hacks").absoluteFile } } }
        // Hidden folders (a download being put together) are never followed.
        assertFalse(watch.folders.value.any { File(it).name.startsWith(".") }, watch.folders.value.toString())
    }

    @Test
    fun savesAndDownloadsInProgressAreNotGames() {
        assertTrue(FolderWatch.counts("Super Metroid (USA).sfc"))
        assertTrue(FolderWatch.counts("Final Fantasy VII (Disc 1).chd"))
        assertTrue(FolderWatch.counts("game.zip"))
        assertFalse(FolderWatch.counts("Super Metroid (USA).srm"))
        assertFalse(FolderWatch.counts("Pokemon Emerald.sav"))
        assertFalse(FolderWatch.counts("Zelda.state3"))
        assertFalse(FolderWatch.counts("big.iso.part"))
        assertFalse(FolderWatch.counts("big.iso.crdownload"))
        assertFalse(FolderWatch.counts(".DS_Store"))
        assertFalse(FolderWatch.counts("notes.txt~"))
    }
}
