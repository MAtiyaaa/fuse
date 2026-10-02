package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.backup.BackupPart
import io.github.matiyaaa.fuse.data.backup.BackupProblem
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Backups end to end: a file made on one install, opened and restored on another. */
class BackupStoreTest {
    private lateinit var first: File
    private lateinit var second: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        first = Files.createTempDirectory("fuse-backup-a").toFile()
        second = Files.createTempDirectory("fuse-backup-b").toFile()
        cache = Files.createTempDirectory("fuse-backup-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        for (root in listOf(first, second)) {
            File(root, "gba").mkdirs()
            File(root, "gba/Advance Wars (USA).gba").writeBytes(ByteArray(64))
            File(root, "gba/Golden Sun (USA).gba").writeBytes(ByteArray(64))
        }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(first, second, cache).forEach { it.deleteRecursively() }
    }

    private suspend fun store(name: String, root: File): Pair<FuseStore, FuseData> {
        val data = FuseData(DesktopDatabase.open(File(cache, "$name.db").absolutePath))
        val store = createFuseStore(FakeServices(data, File(cache, name).apply { mkdirs() }), scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }
        return store to data
    }

    @Test
    fun aBackupCarriesChangesAndChosenArtToAnotherInstall(): Unit = runBlocking {
        val (a, aData) = store("a", first)
        val wars = aData.games.idByPath(File(first, "gba/Advance Wars (USA).gba").absolutePath)!!
        a.library.setFavorite(wars, true)
        val picture = File(cache, "cover.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        a.media.setFromFile(MediaOwner.OfGame(wars), MediaKind.BOXART, picture.absolutePath)
        a.updatePrefs { it.copy(themeId = "daylight") }
        withTimeout(10_000) { while (aData.settings.current().appearance.themeId != "daylight") kotlinx.coroutines.delay(20) }

        val made = assertNotNull(a.backup.create())
        assertTrue(made.name.endsWith(".fusebackup"))
        assertEquals(1, made.pictures)

        val (b, bData) = store("b", second)
        val preview = assertIs<BackupOpened.Ready>(b.backup.open(made.bytes)).preview
        assertEquals(1, preview.games)
        assertEquals(1, preview.gamesHere)
        assertTrue(preview.hasSettings)

        val report = assertNotNull(b.backup.restore(preview, BackupPart.entries.toSet()))
        assertEquals(1, report.games)
        assertEquals(1, report.media)
        val here = bData.games.idByPath(File(second, "gba/Advance Wars (USA).gba").absolutePath)!!
        assertTrue(bData.games.get(here)!!.favorite)
        val art = bData.media.get(MediaOwner.OfGame(here)).first(MediaKind.BOXART)!!
        val restored = File(art.localPath!!)
        assertNotEquals(picture.absolutePath, restored.absolutePath)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), restored.readBytes())
        // The store follows restored settings at once.
        assertEquals("daylight", b.prefs.value.themeId)

        // Settings can go back to how they were before the restore.
        assertTrue(b.backup.canUndo.value)
        assertTrue(b.backup.undo())
        assertEquals("fuse", b.prefs.value.themeId)
        assertFalse(b.backup.canUndo.value)
    }

    @Test
    fun otherFilesAreRefused(): Unit = runBlocking {
        val (b, _) = store("c", second)
        assertEquals(BackupProblem.NOT_A_BACKUP, assertIs<BackupOpened.Failed>(b.backup.open("hello".encodeToByteArray())).problem)
    }
}
