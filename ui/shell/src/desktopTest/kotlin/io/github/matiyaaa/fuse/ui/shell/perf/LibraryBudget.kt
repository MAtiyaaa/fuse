package io.github.matiyaaa.fuse.ui.shell.perf

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.ui.shell.store.FakeServices
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * Pillar 10.1: a household's library of 20,000 games (-Pfuse.perf.games), made of files across
 * eleven systems and scanned from nothing by the real store, held to budgets: the scan, Home
 * with every system on it, the whole library as a list, a search, and the heap the store keeps once
 * it settles. The numbers go to `budget.json` in -Pfuse.perf.dir, so runs can be compared; a budget
 * missed fails the run. Not part of the normal test run:
 * `./gradlew :ui:shell:desktopPerf --tests '*LibraryBudget*' -Pfuse.perf.dir=<folder> -Pfuse.perf.games=20000`.
 */
class LibraryBudget {
    private val outDir: File? = System.getProperty("fuse.perf.dir")?.takeIf { it.isNotBlank() }?.let(::File)
    private val games: Int = System.getProperty("fuse.perf.games")?.toIntOrNull()?.coerceIn(100, 50_000) ?: 20_000
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-budget-roms").toFile()
        cache = Files.createTempDirectory("fuse-budget-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun aHouseholdsLibraryStaysWithinItsBudgets(): Unit = runBlocking {
        assumeTrue("Run through desktopPerf with -Pfuse.perf.dir", outDir != null)
        // Eleven systems, each its usual folder and file type; names like a real collection's.
        val systems = listOf("snes" to "sfc", "gba" to "gba", "n64" to "z64", "nes" to "nes", "megadrive" to "md", "gb" to "gb",
            "gbc" to "gbc", "nds" to "nds", "pcengine" to "pce", "mastersystem" to "sms", "gamegear" to "gg")
        val words = listOf("Quest", "Legend", "Racer", "Star", "Dungeon", "Kart", "Soccer", "Puzzle", "Knight", "Island", "Rocket", "Shadow")
        for (i in 0 until games) {
            val (folder, ext) = systems[i % systems.size]
            val name = "${words[i % words.size]} ${words[(i / words.size) % words.size]} ${i / (words.size * words.size) + 1} (USA)"
            File(root, folder).apply { mkdirs() }.let { File(it, "$name.$ext").writeBytes(ByteArray(64 + i % 512)) }
        }
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        store.updatePrefs { it.copy(onboardingDone = true) }
        val rt = Runtime.getRuntime()
        fun heapMb(): Long {
            repeat(3) { System.gc(); Thread.sleep(200) }
            return (rt.totalMemory() - rt.freeMemory()) / (1 shl 20)
        }
        val before = heapMb()

        val start = TimeSource.Monotonic.markNow()
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        // Home with every system on it, from the moment the folder was added.
        val home = scope.async { store.library.home.first { it.systems.size >= systems.size }; start.elapsedNow().inWholeMilliseconds }
        withTimeout(BUDGET_SCAN_MS * 3) { store.sources.scan.first { it.phase == ScanPhase.DONE } }
        val all = withTimeout(BUDGET_SCAN_MS * 3) { store.library.games(GameQuery()).first { it.size >= games } }
        val scanMs = start.elapsedNow().inWholeMilliseconds
        val homeMs = withTimeout(BUDGET_HOME_MS * 3) { home.await() }

        val listMark = TimeSource.Monotonic.markNow()
        val again = store.library.games(GameQuery()).first { it.size >= games }
        val listMs = listMark.elapsedNow().inWholeMilliseconds

        val searchMark = TimeSource.Monotonic.markNow()
        val found = store.library.search("Kart Rocket").first { it.query.isNotEmpty() }
        val searchMs = searchMark.elapsedNow().inWholeMilliseconds

        val heldMb = heapMb() - before
        val results = linkedMapOf(
            "games" to all.size.toLong(), "scanMs" to scanMs, "homeMs" to homeMs, "listMs" to listMs,
            "searchMs" to searchMs, "heapHeldMb" to heldMb,
        )
        outDir!!.mkdirs()
        File(outDir, "budget.json").writeText(results.entries.joinToString(",\n  ", "{\n  ", "\n}\n") { (k, v) -> "\"$k\": $v" })
        println("Library budget: $results")

        assertTrue(again.size >= games && found.query.isNotEmpty())
        assertTrue(scanMs <= BUDGET_SCAN_MS, "the scan took $scanMs ms, over its $BUDGET_SCAN_MS")
        assertTrue(homeMs <= BUDGET_HOME_MS, "Home took $homeMs ms after the scan, over its $BUDGET_HOME_MS")
        assertTrue(listMs <= BUDGET_LIST_MS, "the whole library took $listMs ms to list, over its $BUDGET_LIST_MS")
        assertTrue(searchMs <= BUDGET_SEARCH_MS, "a search took $searchMs ms, over its $BUDGET_SEARCH_MS")
        assertTrue(heldMb <= BUDGET_HEAP_MB, "the store kept $heldMb MB, over its $BUDGET_HEAP_MB")
    }

    private companion object {
        // For 20,000 games. Measured on Fuse's four-core build container over four runs (18 October
        // 2026): the scan 6.4 to 12.0 s, Home with every system 9.7 to 10.6 s from the start (the
        // last three runs; it fills while the scan runs), the list 0.2 to 0.5 s, a search 0.5 to
        // 2.1 s, 25 to 39 MB kept. The budgets are about five times the slowest, for a CI runner's
        // two cores and a slow day; a real regression still crosses them.
        const val BUDGET_SCAN_MS = 60_000L
        const val BUDGET_HOME_MS = 60_000L
        const val BUDGET_LIST_MS = 5_000L
        const val BUDGET_SEARCH_MS = 10_000L
        const val BUDGET_HEAP_MB = 256L
    }
}
