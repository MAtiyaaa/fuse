package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Fuse Fast Start: one game started with no window, the way Play starts it, from a store that runs nothing else. */
class FastStartTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-run").toFile()
        cache = Files.createTempDirectory("fuse-run-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "gba").mkdirs()
        File(root, "gba/Golden Sun (USA).gba").writeBytes(ByteArray(1024) { it.toByte() })
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    /**
     * A library with one game in it, scanned by Fuse as it runs normally, which is then closed: the
     * run store opens the same data with nothing else running.
     */
    private suspend fun library(): Pair<FakeServices, GameId> {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val open = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = createFuseStore(services, open)
            store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
            val game = withTimeout(20_000) {
                store.sources.scan.first { it.phase == ScanPhase.DONE }
                store.library.games(GameQuery(platform = PlatformId("gba"))).first { it.isNotEmpty() }.single()
            }
            return services to game.id
        } finally {
            open.cancel()
        }
    }

    @Test
    fun theGameStartsAsPlayStartsItAndTheRunEndsOnlyOnceItHas(): Unit = runBlocking {
        val (services, id) = library()
        val run = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = createRunStore(services, run)
            val exit = CompletableDeferred<Unit>()
            services.exit = exit
            val outcome = async { FastStart.run(store, id) }
            withTimeout(10_000) { while (services.launched.isEmpty()) delay(20) }
            // mGBA's command, as Play gives it.
            val plan = assertIs<LaunchPlan.Command>(services.launched.single().plan)
            assertEquals("linux.mgba", plan.emulatorId.value)
            // While the game runs, the run goes on (Steam sees the game for as long as it plays).
            delay(300)
            assertFalse(outcome.isCompleted)
            assertNotNull(services.data.playSessions.openSession().first())
            exit.complete(Unit)
            assertEquals(FastStart.Outcome.Played, withTimeout(10_000) { outcome.await() })
            // Its session is counted, and closed, before the run says it is done.
            assertNull(services.data.playSessions.openSession().first())
            val sessions = services.data.playSessions.sessions(id).first()
            assertEquals(1, sessions.size)
            assertNotNull(sessions.single().endedAt)
        } finally {
            run.cancel()
        }
    }

    @Test
    fun aGameThatCantStartOpensFuseOnItToSayWhy(): Unit = runBlocking {
        val (services, _) = library()
        val run = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = createRunStore(services, run)
            // A Steam entry for a game no longer in the library: nothing is started, and Fuse opens to say so.
            assertIs<FastStart.Outcome.NeedsYou>(withTimeout(10_000) { FastStart.run(store, GameId(987_654)) })
            assertEquals(0, services.launched.size)
        } finally {
            run.cancel()
        }
    }

    @Test
    fun aDirectEntrysCommandIsExactlyWhatPlayRuns(): Unit = runBlocking {
        val (services, id) = library()
        val run = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = createRunStore(services, run)
            val direct = assertIs<DirectCommand.Ready>(store.library.directCommand(id))
            assertEquals("mGBA", direct.emulator)
            assertTrue(direct.argv.last().endsWith("Golden Sun (USA).gba"), direct.argv.toString())
            // Nothing was started to work it out.
            assertEquals(0, services.launched.size)
            services.exit = CompletableDeferred<Unit>().also { it.complete(Unit) }
            assertEquals(FastStart.Outcome.Played, withTimeout(10_000) { FastStart.run(store, id) })
            val played = assertIs<LaunchPlan.Command>(services.launched.single().plan)
            assertEquals(played.argv, direct.argv)
            assertEquals(played.workingDir, direct.workingDir)
            assertEquals(played.env, direct.env)
            // A game Fuse doesn't have can't have one.
            assertIs<DirectCommand.Unavailable>(store.library.directCommand(GameId(987_654)))
        } finally {
            run.cancel()
        }
    }

    @Test
    fun frameGenerationChosenForASystemReachesPlayAndDirectEntriesOnlyWhereLsfgVkIsSetUp(): Unit = runBlocking {
        val (services, id) = library()
        val run = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = createRunStore(services, run)
            store.settings.set(io.github.matiyaaa.fuse.model.ScopedSettings.FrameGeneration, io.github.matiyaaa.fuse.model.ScopeRef.platform(PlatformId("gba")), 3)
            // lsfg-vk isn't set up: nothing of it on the command.
            assertTrue(assertIs<DirectCommand.Ready>(store.library.directCommand(id)).env.keys.none { it.startsWith("LSFGVK") })
            services.frameGeneration = true
            val on = assertIs<DirectCommand.Ready>(store.library.directCommand(id))
            assertEquals("1", on.env["LSFGVK_ENV"])
            assertEquals("3", on.env["LSFGVK_MULTIPLIER"])
            // The game's own choice wins over its system's.
            store.settings.set(io.github.matiyaaa.fuse.model.ScopedSettings.FrameGeneration, io.github.matiyaaa.fuse.model.ScopeRef.game(id), 0)
            assertTrue(assertIs<DirectCommand.Ready>(store.library.directCommand(id)).env.keys.none { it.startsWith("LSFGVK") })
        } finally {
            run.cancel()
        }
    }

    @Test
    fun aSessionFromSteamIsCountedOnceAndOnlyWithinADay(): Unit = runBlocking {
        val (services, id) = library()
        val run = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = createRunStore(services, run)
            val now = System.currentTimeMillis()
            assertTrue(store.library.recordSteamSession(id, now - 3_600_000, now - 600_000))
            assertFalse(store.library.recordSteamSession(id, now - 3_600_000, now - 600_000))
            // Longer than a day, or for a game Fuse doesn't have: not a session.
            assertFalse(store.library.recordSteamSession(id, now - 3 * 86_400_000L, now - 600_000))
            assertFalse(store.library.recordSteamSession(GameId(987_654), now - 3_600_000, now))
            assertEquals(1, services.data.playSessions.sessions(id).first().size)
        } finally {
            run.cancel()
        }
    }

    @Test
    fun theRunStoreStartsNoScan(): Unit = runBlocking {
        val (services, _) = library()
        // A new game in the folder: Fuse open would find it on its next quick scan; a run never scans.
        File(root, "gba/Mother 3.gba").writeBytes(ByteArray(512))
        val run = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = createRunStore(services, run)
            delay(1_500)
            assertEquals(ScanPhase.IDLE, store.sources.scan.value.phase)
            assertEquals(1, services.data.games.platformCounts().first()[PlatformId("gba")])
        } finally {
            run.cancel()
        }
    }
}
