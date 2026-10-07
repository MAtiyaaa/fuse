package io.github.matiyaaa.fuse.ui.shell.perf

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.shell.audit.AuditDriver
import io.github.matiyaaa.fuse.ui.shell.audit.AuditSamples
import io.github.matiyaaa.fuse.ui.shell.audit.AuditServices
import io.github.matiyaaa.fuse.ui.shell.audit.AuditSize
import io.github.matiyaaa.fuse.ui.shell.audit.openSettings
import io.github.matiyaaa.fuse.ui.shell.settings.settingsSections
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.io.File
import java.nio.file.Files
import java.time.Duration
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * How much each frame costs on the interactions people reported as laggy: scrolling Settings and
 * Storage, scrolling the Library, running along Systems, switching tabs quickly and scrolling a
 * game's options. The real app runs headless over a library of [EXTRA_GAMES] generated games plus
 * the audit's samples, driven by controller presses and touch, and every frame's CPU time (compose,
 * layout and Skia drawing) is recorded while the interaction runs. A JFR recording samples the test
 * thread, so the summary also says where the time went.
 *
 * Software rendering makes absolute numbers larger than on a device; compare runs with each other.
 * Not part of the normal test run: `./gradlew :ui:shell:desktopPerf -Pfuse.perf.dir=<folder>`.
 */
@OptIn(ExperimentalTestApi::class)
class PerfHarness {
    private val outDir: File? = System.getProperty(DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
    private val only: List<String> = System.getProperty(ONLY_PROPERTY).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope
    private val results = mutableListOf<Pair<String, List<Double>>>()

    @BeforeTest
    fun setUp() {
        assumeTrue("Set -D$DIR_PROPERTY=<folder> (or run :ui:shell:desktopPerf) to measure", outDir != null)
        outDir!!.mkdirs()
        val tmp = File(System.getProperty("java.io.tmpdir"))
        root = File(tmp, "fuse-perf-library").apply { deleteRecursively(); mkdirs() }
        cache = Files.createTempDirectory("fuse-perf-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        if (outDir == null) return
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun interactions() {
        val size = AuditSize.M
        runDesktopComposeUiTest(size.widthPx, size.heightPx) {
            val router = InputRouter(scope)
            mainClock.autoAdvance = false
            val driver = AuditDriver(this, size, router, root, cache, File(root.parentFile, "fuse-perf-sd"), scope)
            setContent { CompositionLocalProvider(LocalDensity provides Density(size.density)) { driver.Content() } }
            val store = runBlocking { perfStore(driver) }
            // The store reads its saved settings as it starts, after setup was marked done: mark it again.
            store.updatePrefs { it.copy(onboardingDone = true) }
            // -Dfuse.perf.device=cpu measures a device drawing without a graphics card, the slowest kind.
            val cpu = System.getProperty(DEVICE_PROPERTY) == "cpu"
            driver.show(store, if (cpu) io.github.matiyaaa.fuse.ui.shell.audit.AuditPlatform(size, drawing = io.github.matiyaaa.fuse.ui.shell.platform.DrawingInfo("Software", gpu = false)) else driver.platform)
            driver.waitFor("Continue playing", 30_000)
            driver.settle(2_000)

            fun measure(name: String, frames: Int, everyFrames: Int, action: (Int) -> Unit) {
                if (only.isNotEmpty() && only.none { name.startsWith(it) }) return
                val recording = Recording().apply {
                    enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2))
                    enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(2))
                    start()
                }
                val times = ArrayList<Double>(frames)
                // Each frame's span in wall time, to put the profile's samples into the frame they fell in.
                val spans = ArrayList<Pair<java.time.Instant, java.time.Instant>>(frames)
                for (f in 0 until frames) {
                    if (f % everyFrames == 0) action(f / everyFrames)
                    val w0 = java.time.Instant.now()
                    val t0 = System.nanoTime()
                    mainClock.advanceTimeBy(FRAME_MS)
                    times += (System.nanoTime() - t0) / 1e6
                    spans += w0 to java.time.Instant.now()
                }
                recording.stop()
                val jfr = File(outDir, "$name.jfr")
                recording.dump(jfr.toPath())
                recording.close()
                results += name to times
                profiles += name to profile(jfr) + perFrame(jfr, spans, times)
                println("Perf: $name ${summary(times)}")
                driver.settle(600)
            }
            fun press(b: PadButton) { router.press(b, InputSource.GAMEPAD); router.release(b, InputSource.GAMEPAD) }

            // Home to Systems and Home to Library, one press each: the first time (nothing built
            // yet) and again. Frame 0 is the one the press lands in; the rest is the slide.
            fun switch(name: String, presses: Int) {
                driver.home()
                driver.settle(1_500)
                measure(name, frames = 30, everyFrames = 30) { repeat(presses) { press(PadButton.R1) } }
                val first = results.last().second.take(3).joinToString(" ") { "%.1f".format(it) }
                println("Perf: $name first frames $first ms, done in ${results.last().second.indexOfLast { it > FRAME_MS * 0.5 }.coerceAtLeast(0) * FRAME_MS} ms")
            }
            // Home at rest: what a frame costs with nothing changing.
            driver.home()
            driver.settle(1_500)
            measure("idle-home", frames = 20, everyFrames = 1000) { }
            // What the rest costs, layer by layer: Home without the room's art, Settings, and nothing at all.
            if (only.any { it.startsWith("idle") }) {
                store.updatePrefs { it.copy(showHero = false) }
                driver.settle(1_000)
                measure("idle-home-noart", frames = 20, everyFrames = 1000) { }
                store.updatePrefs { it.copy(showHero = true) }
                driver.openSettings()
                driver.settle(1_500)
                measure("idle-settings", frames = 20, everyFrames = 1000) { }
                val shown = driver.view
                driver.view = io.github.matiyaaa.fuse.ui.shell.audit.AuditView.Blank
                driver.settle(500)
                measure("idle-blank", frames = 20, everyFrames = 1000) { }
                driver.view = shown
                driver.settle(1_500)
            }
            switch("switch-systems-cold", 1)
            switch("switch-library-cold", 2)
            switch("switch-systems-warm", 1)
            switch("switch-library-warm", 2)

            // Tabs: R1 along the top bar and back, a press every ~110 ms.
            driver.home()
            measure("tabs-run", frames = 90, everyFrames = 7) { i -> press(if (i < 6) PadButton.R1 else PadButton.L1) }

            // Systems: a run to the right along the grid, a press every ~80 ms.
            driver.tab(Destination.SYSTEMS)
            driver.settle(1_500)
            measure("systems-run", frames = 80, everyFrames = 5) { i -> press(if (i < 8) PadButton.DPAD_RIGHT else PadButton.DPAD_LEFT) }

            // Library: down the grid with the D-pad, then a touch fling.
            driver.tab(Destination.LIBRARY)
            driver.settle(1_500)
            measure("library-dpad", frames = 90, everyFrames = 6) { press(PadButton.DPAD_DOWN) }
            measure("library-fling", frames = 60, everyFrames = 60) { driver.touch { swipeUp(startY = height * 0.85f, endY = height * 0.25f, durationMillis = 120) } }
            // The same run without the room's art, then in Low Power Mode: what the backdrop and the
            // effects cost of every frame.
            if (only.any { it.startsWith("library-") }) {
                store.updatePrefs { it.copy(showHero = false) }
                driver.settle(1_000)
                measure("library-dpad-nohero", frames = 90, everyFrames = 6) { press(PadButton.DPAD_UP) }
                store.updatePrefs { it.copy(showHero = true, lowPower = true) }
                driver.settle(1_000)
                measure("library-dpad-lowpower", frames = 90, everyFrames = 6) { press(PadButton.DPAD_DOWN) }
                store.updatePrefs { it.copy(lowPower = false) }
                driver.settle(1_000)
            }

            // Settings: the longest section, down its rows, then a fling.
            driver.openSettings()
            val longest = settingsSections.indexOfFirst { it.id == "media" }.takeIf { it >= 0 } ?: 3
            driver.tap(PadButton.DPAD_DOWN, longest)
            driver.tap(PadButton.DPAD_RIGHT)
            driver.settle(1_000)
            measure("settings-dpad", frames = 90, everyFrames = 5) { press(PadButton.DPAD_DOWN) }
            measure("settings-fling", frames = 60, everyFrames = 60) { driver.touch { swipeUp(startY = height * 0.85f, endY = height * 0.3f, durationMillis = 120) } }

            // Storage: down hundreds of games, then a fling.
            driver.openSettings()
            driver.tap(PadButton.DPAD_DOWN, settingsSections.indexOfFirst { it.id == "storage" })
            driver.tap(PadButton.DPAD_RIGHT)
            driver.tapText("Games and space")
            driver.waitFor("All systems", 30_000)
            driver.settle(3_000)
            measure("storage-dpad", frames = 120, everyFrames = 4) { press(PadButton.DPAD_DOWN) }
            measure("storage-fling", frames = 60, everyFrames = 60) { driver.touch { swipeUp(startY = height * 0.85f, endY = height * 0.3f, durationMillis = 120) } }

            // A game's options from Home, down and up its rows.
            driver.home()
            driver.settle(800)
            press(PadButton.X)
            driver.waitFor("Game Info")
            measure("options-dpad", frames = 60, everyFrames = 5) { i -> press(if (i < 7) PadButton.DPAD_DOWN else PadButton.DPAD_UP) }
            press(PadButton.B)

            writeSummary()
        }
    }

    /** The audit's library plus [EXTRA_GAMES] generated games spread over its systems, scanned and played. */
    private suspend fun perfStore(driver: AuditDriver): FuseStore {
        AuditSamples.writeTo(root)
        val folders = AuditSamples.games.groupBy { it.folder }.mapValues { (_, g) -> g.first().ext }.entries.toList()
        for (i in 0 until EXTRA_GAMES) {
            val (folder, ext) = folders[i % folders.size]
            File(root, folder).apply { mkdirs() }.let { File(it, "Generated Game ${(i + 1).toString().padStart(4, '0')}.$ext").writeBytes(ByteArray(512 + i)) }
        }
        val services = AuditServices.create(cache, driver.controls)
        val store = createFuseStore(services, scope)
        // -Dfuse.perf.lowPower=true measures with Low Power Mode (no blur, still backgrounds), to compare.
        store.updatePrefs { it.copy(onboardingDone = true, lowPower = System.getProperty("fuse.perf.lowPower") == "true") }
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val total = AuditSamples.games.size + EXTRA_GAMES
        withTimeout(180_000) { store.sources.scan.first { it.phase == ScanPhase.DONE } }
        val cards = withTimeout(60_000) { store.library.games(GameQuery()).first { it.size >= total } }
        AuditSamples.applyHistory(store, services.data, cards.filter { c -> AuditSamples.games.any { it.title == c.title } }, Clock.System.now().toEpochMilliseconds())
        return store
    }

    private fun summary(times: List<Double>): String {
        val s = times.sorted()
        fun p(q: Double) = s[((s.size - 1) * q).toInt()]
        return "frames=${s.size} mean=%.1f p50=%.1f p95=%.1f max=%.1f ms".format(s.average(), p(0.5), p(0.95), s.last())
    }

    private val profiles = mutableListOf<Pair<String, String>>()

    /**
     * Where the UI thread spent one interaction: by phase (composition, layout, drawing, the rest),
     * then Fuse's own code (inclusive) and the top of the stack (self). Java and native samples both
     * count, so Skia's drawing shows under the Java frame that asked for it.
     */
    private fun profile(jfr: File): String {
        val events = RecordingFile.readAllEvents(jfr.toPath()).filter { it.eventType.name == "jdk.ExecutionSample" || it.eventType.name == "jdk.NativeMethodSample" }
        val ui = events.filter { it.getThread("sampledThread")?.javaName?.startsWith("AWT-EventQueue") == true }
        val inclusive = HashMap<String, Int>()
        val self = HashMap<String, Int>()
        val phases = HashMap<String, Int>()
        var samples = 0
        for (e in ui) {
            val frames = e.stackTrace?.frames ?: continue
            samples++
            val names = frames.map { "${it.method.type.name}.${it.method.name}" }
            self.merge(names.firstOrNull() ?: "?", 1, Int::plus)
            names.filter { it.startsWith("io.github.matiyaaa") }.map { it.substringBefore("\$\$Lambda") }.distinct().forEach { inclusive.merge(it, 1, Int::plus) }
            val phase = names.firstNotNullOfOrNull { n ->
                when {
                    n.startsWith("org.jetbrains.skia") || ".draw" in n || "DrawScope" in n || "GraphicsLayer" in n || "RenderNode" in n -> "draw"
                    "MeasureAndLayoutDelegate" in n || ".measure" in n || ".remeasure" in n || ".placeAt" in n || "LayoutNode.layout" in n -> "layout"
                    "Recomposer" in n || "ComposerImpl" in n || "recompose" in n -> "compose"
                    else -> null
                }
            } ?: "other"
            phases.merge(phase, 1, Int::plus)
        }
        fun pct(n: Int) = "%5.1f%%".format(n * 100.0 / samples.coerceAtLeast(1))
        return buildString {
            appendLine("UI thread samples: $samples (every 2 ms)")
            appendLine("Phases: " + phases.entries.sortedByDescending { it.value }.joinToString("  ") { "${it.key} ${pct(it.value).trim()}" })
            appendLine("Fuse code, inclusive:")
            inclusive.entries.sortedByDescending { it.value }.take(30).forEach { appendLine("${pct(it.value)}  ${it.key}") }
            appendLine("Self:")
            self.entries.sortedByDescending { it.value }.take(15).forEach { appendLine("${pct(it.value)}  ${it.key}") }
        }
    }

    /**
     * The first frames of an interaction one by one: their time, and how it splits between
     * composition, layout and drawing (from the profile's samples in each frame's span), with Fuse's
     * own code that took the most of the work that isn't drawing. Drawing is what a graphics card
     * takes over on a device; the rest is what every device pays.
     */
    private fun perFrame(jfr: File, spans: List<Pair<java.time.Instant, java.time.Instant>>, times: List<Double>, first: Int = 6): String {
        val events = RecordingFile.readAllEvents(jfr.toPath()).filter {
            (it.eventType.name == "jdk.ExecutionSample" || it.eventType.name == "jdk.NativeMethodSample") &&
                it.getThread("sampledThread")?.javaName?.startsWith("AWT-EventQueue") == true
        }
        return buildString {
            appendLine("Frames one by one (samples every 2 ms):")
            for (i in 0 until minOf(first, spans.size)) {
                val (a, b) = spans[i]
                val inFrame = events.filter { !it.startTime.isBefore(a) && !it.startTime.isAfter(b) }
                val phases = HashMap<String, Int>()
                val work = HashMap<String, Int>()
                for (e in inFrame) {
                    val names = e.stackTrace?.frames?.map { "${it.method.type.name}.${it.method.name}" } ?: continue
                    val phase = phaseOf(names)
                    phases.merge(phase, 1, Int::plus)
                    if (phase != "draw") names.filter { it.startsWith("io.github.matiyaaa") }.map { it.substringBefore("\$\$Lambda") }.distinct().forEach { work.merge(it, 1, Int::plus) }
                }
                appendLine(
                    "  frame $i: %.1f ms  (".format(times[i]) +
                        listOf("compose", "layout", "draw", "other").joinToString("  ") { "$it ${(phases[it] ?: 0) * 2} ms" } + ")",
                )
                work.entries.sortedByDescending { it.value }.take(6).forEach { appendLine("      ${it.value * 2} ms  ${it.key}") }
            }
        }
    }

    private fun phaseOf(names: List<String>): String = names.firstNotNullOfOrNull { n ->
        when {
            n.startsWith("org.jetbrains.skia") || ".draw" in n || "DrawScope" in n || "GraphicsLayer" in n || "RenderNode" in n -> "draw"
            "MeasureAndLayoutDelegate" in n || ".measure" in n || ".remeasure" in n || ".placeAt" in n || "LayoutNode.layout" in n -> "layout"
            "Recomposer" in n || "ComposerImpl" in n || "recompose" in n -> "compose"
            else -> null
        }
    } ?: "other"

    private fun writeSummary() {
        val out = buildString {
            appendLine("| Interaction | Frames | Mean ms | p50 | p95 | Max |")
            appendLine("|---|---|---|---|---|---|")
            for ((name, times) in results) {
                val s = times.sorted()
                fun p(q: Double) = s[((s.size - 1) * q).toInt()]
                appendLine("| $name | ${s.size} | %.1f | %.1f | %.1f | %.1f |".format(s.average(), p(0.5), p(0.95), s.last()))
            }
            for ((name, text) in profiles) {
                appendLine()
                appendLine("## $name")
                append(text)
            }
        }
        File(outDir, "summary.md").writeText(out)
        println(out)
    }

    companion object {
        const val DIR_PROPERTY = "fuse.perf.dir"
        const val ONLY_PROPERTY = "fuse.perf.only"
        private const val EXTRA_GAMES = 600
        private const val DEVICE_PROPERTY = "fuse.perf.device"
        private const val FRAME_MS = 16L
    }
}
