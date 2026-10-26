package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlaySessionSource
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** How It Runs Here: only the person makes a game playable, and the report holds their word alone. */
class CompatStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-compat").toFile()
        cache = Files.createTempDirectory("fuse-compat-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "snes").mkdirs()
        File(root, "snes/Super Metroid (USA).sfc").writeBytes(ByteArray(64))
        File(root, "snes/Chrono Trigger (USA).sfc").writeBytes(ByteArray(64))
        File(root, "snes/F-Zero (USA).sfc").writeBytes(ByteArray(64))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun aGameIsPlayableOnlyOnThePersonsWord(): Unit = runBlocking {
        val data = FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath))
        val store = createFuseStore(FakeServices(data, cache), scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val games = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 3 } }.associateBy { it.title }
        val metroid = games.getValue("Super Metroid").id
        val chrono = games.getValue("Chrono Trigger").id
        val fzero = games.getValue("F-Zero").id

        assertEquals(CompatStatus.UNTESTED, store.compat.of(metroid).first().status)
        // Started and played here: it launches, nothing more, until the person says.
        val now = System.currentTimeMillis()
        data.playSessions.record(metroid, now - 3_600_000, now - 600_000, PlaySessionSource.FUSE_LAUNCH)
        // Time brought in from elsewhere is no proof it runs here.
        data.playSessions.record(chrono, now - 3_600_000, now - 600_000, PlaySessionSource.IMPORTED)
        val launched = withTimeout(10_000) { store.compat.of(metroid).first { it.status == CompatStatus.LAUNCHES } }
        assertEquals(1, launched.sessionsHere)
        assertEquals(50 * 60L, launched.playedHereSeconds)
        assertEquals(CompatStatus.UNTESTED, store.compat.of(chrono).first().status)

        store.compat.set(metroid, CompatVerdict.PLAYS_WELL)
        store.compat.set(chrono, CompatVerdict.WITH_PROBLEMS, setOf(CompatProblem.SOUND, CompatProblem.SPEED))
        store.compat.set(fzero, CompatVerdict.DOES_NOT_WORK, setOf(CompatProblem.SOUND))
        assertEquals(CompatStatus.VERIFIED, store.compat.of(metroid).first().status)
        val withProblems = store.compat.of(chrono).first()
        assertEquals(CompatStatus.WITH_ISSUES, withProblems.status)
        assertEquals(setOf(CompatProblem.SOUND, CompatProblem.SPEED), withProblems.note?.problems)
        val broken = store.compat.of(fzero).first()
        assertEquals(CompatStatus.UNSUPPORTED, broken.status)
        // Problems belong only to a game that plays with them.
        assertTrue(broken.note!!.problems.isEmpty())

        val report = store.compat.report()
        for (line in listOf("Verified playable (1)", "  Super Metroid (", "Playable with issues (1)", "problems: sound, speed", "Doesn't work here (1)", "  F-Zero (")) {
            assertTrue(line in report, "\"$line\" in:\n$report")
        }
        assertFalse(root.absolutePath in report, "no folder names")

        store.compat.set(metroid, null)
        assertEquals(CompatStatus.LAUNCHES, store.compat.of(metroid).first().status)
        assertFalse("Super Metroid" in store.compat.report())
    }
}
