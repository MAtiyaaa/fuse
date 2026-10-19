package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.PlaySessionSource
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

/** A system's play for the Systems page, from the library's own records of play. */
class SystemActivityStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-activity").toFile()
        cache = Files.createTempDirectory("fuse-activity-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(root, cache).forEach { it.deleteRecursively() }
    }

    @Test
    fun aSystemsPlayIsItsGamesTimeWithTheLastAndTheMostPlayed(): Unit = runBlocking {
        val data = FuseData(DesktopDatabase.open(File(cache, "activity.db").absolutePath))
        val services = FakeServices(data, cache)
        File(root, "ps2").mkdirs()
        listOf("Okami", "Ico", "Shadow of the Colossus").forEach { File(root, "ps2/$it.iso").writeBytes(ByteArray(64)) }
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val games = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 3 } }.associateBy { it.title }
        val ps2 = PlatformId("ps2")

        val before = withTimeout(10_000) { store.library.systemActivity(ps2).first() }
        assertEquals(SystemActivity(), before, "none played yet")

        // An hour of Shadow of the Colossus, then ten minutes of Okami after it.
        data.playSessions.record(games.getValue("Shadow of the Colossus").id, 1_000_000, 1_000_000 + 3_600_000, PlaySessionSource.FUSE_LAUNCH)
        data.playSessions.record(games.getValue("Okami").id, 5_000_000, 5_000_000 + 600_000, PlaySessionSource.FUSE_LAUNCH)
        val after = withTimeout(10_000) { store.library.systemActivity(ps2).first { it.played == 2 } }
        assertEquals(4_200L, after.seconds)
        assertEquals(listOf("Okami", "Shadow of the Colossus"), after.recent.map { it.title }, "newest first")
        assertEquals(listOf("Shadow of the Colossus", "Okami"), after.mostPlayed.map { it.title })
    }
}
