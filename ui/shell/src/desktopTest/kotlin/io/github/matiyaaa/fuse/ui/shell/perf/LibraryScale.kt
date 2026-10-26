package io.github.matiyaaa.fuse.ui.shell.perf

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.PlaySessionSource
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.ui.shell.store.FakeServices
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * Fuse 0.4.5: the library at every size the Evolution Update holds itself to (-Pfuse.perf.scale,
 * 100,1000,5000,10000,25000 by default), each game with a cover, a logo and a background drawn
 * from a pool of real images on disk, and some history (favourites, play) so Home's rows fill.
 * For each size, from the real store over the real database:
 *
 * - **scan**: the library found from nothing (cold);
 * - **start**: a new store over that database, as Fuse opening again: Home with every system and
 *   its rows, from the moment the store is made (with the work Home doesn't need held back until
 *   then, as the apps hold it), then (as when the library is opened from Home) the
 *   whole library listed, every game with its art, from the moment it is asked for;
 * - **list**: the library listed again with the store warm, once and then 20 times (p50, p95), as
 *   opening the library again does;
 * - **home**: Home opened again with the store warm, 20 times (p50, p95);
 * - **search**: the first search right after the start (which may have to build the index), 40
 *   more (p50, p95) while the start's background work (series, suggestions) still runs, then once
 *   Fuse has settled for a few seconds a first search nobody made yet and 40 more (p50, p95);
 * - **heap**: what the store keeps once it settles.
 *
 * Results go to `scale.json` in -Pfuse.perf.dir. Not part of the normal test run:
 * `./gradlew :ui:shell:desktopPerf --tests '*LibraryScale*' -Pfuse.perf.dir=<folder> [-Pfuse.perf.scale=100,10000]`.
 */
class LibraryScale {
    private val outDir: File? = System.getProperty("fuse.perf.dir")?.takeIf { it.isNotBlank() }?.let(::File)
    private val sizes: List<Int> = System.getProperty("fuse.perf.scale").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        .ifEmpty { listOf(100, 1_000, 5_000, 10_000, 25_000) }
    private lateinit var work: File
    private val scopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() {
        work = Files.createTempDirectory("fuse-scale").toFile()
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        work.deleteRecursively()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @Test
    fun everySizeOfLibrary(): Unit = runBlocking {
        assumeTrue("Run through desktopPerf with -Pfuse.perf.dir", outDir != null)
        val art = ArtPool.make(File(work, "art"))
        val rows = sizes.map { n -> measure(n, art) }
        outDir!!.mkdirs()
        File(outDir, "scale.json").writeText(rows.joinToString(",\n", "[\n", "\n]\n") { r ->
            r.entries.joinToString(", ", "  {", "}") { (k, v) -> "\"$k\": $v" }
        })
        println("Library scale:\n" + rows.joinToString("\n") { it.toString() })
    }

    private suspend fun measure(games: Int, art: ArtPool): Map<String, Any> {
        val root = File(work, "roms-$games").apply { mkdirs() }
        val cache = File(work, "cache-$games").apply { mkdirs() }
        val db = File(cache, "fuse.db").absolutePath
        for (i in 0 until games) {
            val (folder, ext) = SYSTEMS[i % SYSTEMS.size]
            val name = "${WORDS[i % WORDS.size]} ${WORDS[(i / WORDS.size) % WORDS.size]} ${i / (WORDS.size * WORDS.size) + 1} (USA)"
            File(root, folder).apply { mkdirs() }.let { File(it, "$name.$ext").writeBytes(ByteArray(64 + i % 512)) }
        }

        // The scan, from nothing.
        val scanScope = newScope()
        val first = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(db)), cache), scanScope)
        first.updatePrefs { it.copy(onboardingDone = true) }
        val scanMark = TimeSource.Monotonic.markNow()
        first.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(SCAN_LIMIT_MS) { first.sources.scan.first { it.phase == ScanPhase.DONE } }
        val listed = withTimeout(SCAN_LIMIT_MS) { first.library.games(GameQuery()).first { it.size >= games } }
        val scanMs = scanMark.elapsedNow().inWholeMilliseconds

        // Art and history, written straight into the database as a filled library would have them.
        val data = FuseData(DesktopDatabase.open(db))
        val ids = listed.map { it.id }
        data.database.transaction {
            val now = System.currentTimeMillis()
            ids.forEachIndexed { i, id ->
                fun put(kind: MediaKind, file: File, w: Int, h: Int) = data.database.mediaQueries.insert(
                    "game", id.value.toString(), kind.name, MediaSource.STEAMGRIDDB.name, file.absolutePath, null,
                    w.toLong(), h.toLong(), 0.5, 0.5, 1.0, 0L, now,
                )
                put(MediaKind.BOXART, art.covers[i % art.covers.size], ArtPool.COVER_W, ArtPool.COVER_H)
                put(MediaKind.LOGO, art.logos[i % art.logos.size], ArtPool.LOGO_W, ArtPool.LOGO_H)
                put(MediaKind.HERO, art.backgrounds[i % art.backgrounds.size], ArtPool.BG_W, ArtPool.BG_H)
            }
        }
        val now = System.currentTimeMillis()
        val rnd = Random(games)
        ids.filterIndexed { i, _ -> i % 50 == 7 }.forEach { data.games.setFavorite(it, true) }
        ids.shuffled(rnd).take(minOf(60, ids.size)).forEachIndexed { k, id ->
            val end = now - k * 3_600_000L - 60_000L
            data.playSessions.record(id, end - (20 + k) * 60_000L, end, PlaySessionSource.FUSE_LAUNCH)
        }
        scanScope.cancel()

        // Fuse opening again over that library: Home, and the whole library with its art.
        val rt = Runtime.getRuntime()
        fun heapMb(): Long {
            repeat(3) { System.gc(); Thread.sleep(150) }
            return (rt.totalMemory() - rt.freeMemory()) / (1 shl 20)
        }
        val before = heapMb()
        val scope = newScope()
        phase("start", games)
        // As the apps do: work the first screen doesn't need waits until Home is up (StartupGate).
        io.github.matiyaaa.fuse.ui.designsystem.effects.StartupGate.hold()
        val startMark = TimeSource.Monotonic.markNow()
        val store: FuseStore = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(db)), cache), scope)
        val systems = SYSTEMS.size.coerceAtMost(games)
        val homeMs = withTimeout(START_LIMIT_MS) {
            store.library.home.first { it.systems.size >= systems && it.continuePlaying.isNotEmpty() && it.favorites.isNotEmpty() }
            startMark.elapsedNow().inWholeMilliseconds
        }
        io.github.matiyaaa.fuse.ui.designsystem.effects.StartupGate.open()
        phase("library", games)
        val libraryMark = TimeSource.Monotonic.markNow()
        val startListMs = withTimeout(START_LIMIT_MS) {
            val all = store.library.games(GameQuery()).first { it.size >= games }
            check(all.count { it.art.boxart != null } >= games) { "every game should have its cover" }
            libraryMark.elapsedNow().inWholeMilliseconds
        }

        // The library again, warm, and searches: the first builds the index.
        phase("list", games)
        val listMark = TimeSource.Monotonic.markNow()
        store.library.games(GameQuery()).first { it.size >= games }
        val listMs = listMark.elapsedNow().inWholeMilliseconds
        val listTimes = (0 until 20).map {
            val m = TimeSource.Monotonic.markNow()
            store.library.games(GameQuery()).first { it.size >= games }
            m.elapsedNow().inWholeMicroseconds / 1000.0
        }.sorted()
        val homeTimes = (0 until 20).map {
            val m = TimeSource.Monotonic.markNow()
            store.library.home.first { it.systems.size >= systems && it.continuePlaying.isNotEmpty() && it.favorites.isNotEmpty() }
            m.elapsedNow().inWholeMicroseconds / 1000.0
        }.sorted()
        val queries = listOf("kart", "legend rocket", "shadow knight 2", "quest", "puzzle island", "zz none", "racer", "star dungeon", "soccer 1", "knight")
        phase("search", games)
        val firstMark = TimeSource.Monotonic.markNow()
        store.library.search(queries.first()).first()
        val firstSearchMs = firstMark.elapsedNow().inWholeMilliseconds
        val searchTimes = (0 until 40).map { k ->
            val m = TimeSource.Monotonic.markNow()
            store.library.search(queries[k % queries.size]).first()
            m.elapsedNow().inWholeMicroseconds / 1000.0
        }.sorted()
        // Fuse left alone for a moment, as before most searches, then a search nobody made yet.
        phase("settle", games)
        kotlinx.coroutines.delay(SETTLE_MS)
        val settledMark = TimeSource.Monotonic.markNow()
        store.library.search("island quest").first()
        val settledSearchMs = settledMark.elapsedNow().inWholeMicroseconds / 1000.0
        val settledTimes = (0 until 40).map { k ->
            val m = TimeSource.Monotonic.markNow()
            store.library.search(queries[k % queries.size]).first()
            m.elapsedNow().inWholeMicroseconds / 1000.0
        }.sorted()
        phase("end", games)
        // What the background work cost while all that ran, from the Performance Observatory.
        store.observatory.jobs.first { it.isNotEmpty() }.forEach { j ->
            println("SCALE-JOB $games ${j.label}: ${j.runs} runs, last ${j.lastMs} ms, longest ${j.longestMs} ms, total ${j.totalMs} ms, held ${j.held}")
        }
        val probe = store.observatory.probe()
        println("SCALE-PROBE $games $probe")
        // The library list's parts on their own, warm: its rows, and its art.
        repeat(3) {
            val rowsMark = TimeSource.Monotonic.markNow()
            val rowsRead = data.games.observeAll(io.github.matiyaaa.fuse.model.SortOrder.TITLE, false).first()
            val rowsMs = rowsMark.elapsedNow().inWholeMilliseconds
            val artMark = TimeSource.Monotonic.markNow()
            data.media.mediaFor(rowsRead.map { io.github.matiyaaa.fuse.model.MediaOwner.OfGame(it.id) })
            println("SCALE-PARTS $games rows ${rowsMs} ms, art ${artMark.elapsedNow().inWholeMilliseconds} ms")
        }
        val heldMb = (heapMb() - before).coerceAtLeast(0)
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
        return linkedMapOf(
            "games" to games, "scanMs" to scanMs, "startHomeMs" to homeMs, "startLibraryMs" to startListMs,
            "warmListMs" to listMs, "warmListP50Ms" to p(listTimes, 50), "warmListP95Ms" to p(listTimes, 95),
            "warmHomeP50Ms" to p(homeTimes, 50), "warmHomeP95Ms" to p(homeTimes, 95),
            "firstSearchMs" to firstSearchMs, "searchP50Ms" to p(searchTimes, 50), "searchP95Ms" to p(searchTimes, 95),
            "settledSearchMs" to "%.2f".format(settledSearchMs), "settledP50Ms" to p(settledTimes, 50), "settledP95Ms" to p(settledTimes, 95),
            "heapHeldMb" to heldMb,
        )
    }

    /** The [pct]th percentile of sorted [times]. */
    private fun p(times: List<Double>, pct: Int) = "%.2f".format(times[((times.size * pct) / 100).coerceAtMost(times.size - 1)])

    /** Marks where a measured stretch begins, so a profile of the run can be cut into them. */
    private fun phase(name: String, games: Int) = println("SCALE-PHASE $name $games ${System.currentTimeMillis()}")

    /**
     * A pool of real images to point games' art at: covers and logos with text-like shapes and
     * gradients (so they compress and decode like real art, not like flat colour), backgrounds at
     * 1080p. Every game has its own triple; neighbouring games' differ.
     */
    class ArtPool(val covers: List<File>, val logos: List<File>, val backgrounds: List<File>) {
        companion object {
            const val COVER_W = 600
            const val COVER_H = 840
            const val LOGO_W = 800
            const val LOGO_H = 310
            const val BG_W = 1920
            const val BG_H = 1080

            fun make(dir: File): ArtPool {
                dir.mkdirs()
                val rnd = Random(45)
                fun image(w: Int, h: Int, alpha: Boolean, seed: Int): BufferedImage {
                    val img = BufferedImage(w, h, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
                    val g = img.createGraphics()
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val r = Random(seed)
                    fun c(a: Int = 255) = Color(r.nextInt(256), r.nextInt(256), r.nextInt(256), a)
                    if (!alpha) {
                        g.paint = GradientPaint(0f, 0f, c(), w.toFloat(), h.toFloat(), c())
                        g.fillRect(0, 0, w, h)
                    }
                    repeat(if (alpha) 6 else 30) {
                        g.color = c(if (alpha) 255 else 140)
                        val x = r.nextInt(w); val y = r.nextInt(h)
                        if (r.nextBoolean()) g.fillOval(x - w / 8, y - h / 8, w / 4, h / 4) else g.fillRect(x - w / 10, y - h / 12, w / 5, h / 6)
                    }
                    g.dispose()
                    return img
                }
                val covers = (0 until COVERS).map { k -> File(dir, "cover-$k.jpg").also { ImageIO.write(image(COVER_W, COVER_H, false, rnd.nextInt()), "jpg", it) } }
                val logos = (0 until LOGOS).map { k -> File(dir, "logo-$k.png").also { ImageIO.write(image(LOGO_W, LOGO_H, true, rnd.nextInt()), "png", it) } }
                val backgrounds = (0 until BACKGROUNDS).map { k -> File(dir, "bg-$k.jpg").also { ImageIO.write(image(BG_W, BG_H, false, rnd.nextInt()), "jpg", it) } }
                return ArtPool(covers, logos, backgrounds)
            }

            private const val COVERS = 400
            private const val LOGOS = 400
            private const val BACKGROUNDS = 120
        }
    }

    private companion object {
        val SYSTEMS = listOf(
            "snes" to "sfc", "gba" to "gba", "n64" to "z64", "nes" to "nes", "megadrive" to "md", "gb" to "gb",
            "gbc" to "gbc", "nds" to "nds", "pcengine" to "pce", "mastersystem" to "sms", "gamegear" to "gg",
        )
        val WORDS = listOf("Quest", "Legend", "Racer", "Star", "Dungeon", "Kart", "Soccer", "Puzzle", "Knight", "Island", "Rocket", "Shadow")
        const val SCAN_LIMIT_MS = 900_000L
        const val START_LIMIT_MS = 300_000L

        /** How long Fuse is left alone before the settled search (the index is built again 1.5 s after the last change). */
        const val SETTLE_MS = 4_000L
    }
}
