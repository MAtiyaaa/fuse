package io.github.matiyaaa.fuse.ui.shell.perf

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import io.github.matiyaaa.fuse.ui.designsystem.effects.UiRenderTrace
import io.github.matiyaaa.fuse.ui.designsystem.effects.traceUiRoot
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
import kotlin.math.roundToLong
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
 * This is a deterministic software CPU workload including Skia rasterization and readback.
 * Virtual cadence and viewport are controlled; timings still depend on this host and instrumentation.
 * It does not measure a hardware renderer, display refresh, GPU presentation or compositor latency.
 * Not part of the normal test run: `./gradlew :ui:shell:desktopPerf -Pfuse.perf.dir=<folder>`.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class PerfHarness {
    private val outDir: File? = System.getProperty(DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
    private val only: List<String> = System.getProperty(ONLY_PROPERTY).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope
    private lateinit var imageHttp: HttpClient
    private var previousImageLoader: coil3.ImageLoader? = null
    private val results = mutableListOf<Pair<String, List<Double>>>()
    private val budgets = mutableMapOf<String, Double>()
    private val gaps = mutableListOf<String>()
    private var activeCase = PerfCase(AuditSize.J, 60)

    private data class PerfCase(val size: AuditSize, val hz: Int) {
        val key get() = "${size.name}-${hz}hz"
        val budgetMs get() = 1000.0 / hz
    }

    /** Explicit matrix keeps the default run small; entries are audit viewport @ virtual cadence. */
    private fun cases(): List<PerfCase> {
        val raw = System.getProperty("fuse.perf.matrix").orEmpty()
        if (raw == "standard") return listOf(PerfCase(AuditSize.D, 60), PerfCase(AuditSize.J, 120), PerfCase(AuditSize.V, 60), PerfCase(AuditSize.T, 60), PerfCase(AuditSize.U, 60))
        if (raw.isNotBlank()) return raw.split(',').map { entry ->
            val parts = entry.trim().split('@')
            require(parts.size == 2) { "Use fuse.perf.matrix=D@60,J@120 or standard" }
            val hz = parts[1].toInt()
            require(hz in 30..240) { "Virtual cadence must be between 30 and 240 Hz" }
            PerfCase(AuditSize.valueOf(parts[0]), hz)
        }.distinct()
        val size = System.getProperty(SIZE_PROPERTY)?.takeIf(String::isNotBlank)?.let(AuditSize::valueOf) ?: AuditSize.J
        val hz = System.getProperty("fuse.perf.refresh")?.toInt() ?: 60
        require(hz in 30..240)
        return listOf(PerfCase(size, hz))
    }

    @BeforeTest
    fun setUp() {
        assumeTrue("Set -D$DIR_PROPERTY=<folder> (or run :ui:shell:desktopPerf) to measure", outDir != null)
        outDir!!.mkdirs()
        val tmp = File(System.getProperty("java.io.tmpdir"))
        root = File(tmp, "fuse-perf-library").apply { deleteRecursively(); mkdirs() }
        cache = Files.createTempDirectory("fuse-perf-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Fixture images use the same canned server as its metadata. No image request leaves the test.
        previousImageLoader = SingletonImageLoader.get(PlatformContext.INSTANCE)
        imageHttp = HttpClient(MockEngine { request ->
            io.github.matiyaaa.fuse.ui.shell.audit.AuditJellyfin.answer(this, request) ?: respondError(HttpStatusCode.NotFound)
        })
        SingletonImageLoader.setUnsafe(io.github.matiyaaa.fuse.ui.shell.platform.fuseImageLoader(PlatformContext.INSTANCE, File(cache, "perf-images").path, imageHttp, lowMemory = false))
    }

    @AfterTest
    fun tearDown() {
        if (outDir == null) return
        UiRenderTrace.enabled = false
        scope.cancel()
        previousImageLoader?.let { SingletonImageLoader.setUnsafe(it) }
        imageHttp.close()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun interactions() {
        for (case in cases()) {
        activeCase = case
        val size = case.size
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runDesktopComposeUiTest(size.widthPx, size.heightPx) {
            val router = InputRouter(scope)
            mainClock.autoAdvance = false
            val driver = AuditDriver(this, size, router, root, cache, File(root.parentFile, "fuse-perf-sd"), scope)
            UiRenderTrace.enabled = true
            UiRenderTrace.renderer = "Headless Skia software, offscreen test target"
            setContent {
                SideEffect { UiRenderTrace.composition() }
                CompositionLocalProvider(LocalDensity provides Density(size.density)) {
                    Box(Modifier.fillMaxSize().traceUiRoot()) { driver.Content() }
                }
            }
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
                UiRenderTrace.reset()
                val recording = Recording().apply {
                    enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2))
                    enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(2))
                    enable("jdk.ObjectAllocationSample")
                    start()
                }
                val times = ArrayList<Double>(frames)
                // Each frame's span in wall time, to put the profile's samples into the frame they fell in.
                val spans = ArrayList<Pair<java.time.Instant, java.time.Instant>>(frames)
                for (f in 0 until frames) {
                    if (f % everyFrames == 0) action(f / everyFrames)
                    val w0 = java.time.Instant.now()
                    val t0 = System.nanoTime()
                    // Alternate integer milliseconds where needed, preserving the requested virtual
                    // cadence without accumulating 60/120 Hz rounding drift. This is not vsync.
                    val before = (f * case.budgetMs).roundToLong()
                    val after = ((f + 1) * case.budgetMs).roundToLong()
                    mainClock.advanceTimeBy(after - before, ignoreFrameDuration = true)
                    // Force the full real shell through Skia painting. This includes offscreen
                    // readback overhead and is not GPU/compositor latency or engine-only timing.
                    onRoot().captureToImage()
                    times += (System.nanoTime() - t0) / 1e6
                    spans += w0 to java.time.Instant.now()
                }
                recording.stop()
                val label = "${case.key}-$name"
                val jfr = File(outDir, "$label.jfr")
                recording.dump(jfr.toPath())
                recording.close()
                results += label to times
                budgets[label] = case.budgetMs
                uiThread += label to uiThreadMs(jfr, spans)
                profiles += label to profile(jfr) + perFrame(jfr, spans, times) + UiRenderTrace.report()
                println("Perf: $label ${summary(times)}")
                driver.settle(600)
            }
            fun wants(name: String) = only.isEmpty() || only.any { name.startsWith(it) }
            fun press(b: PadButton) { router.press(b, InputSource.GAMEPAD); router.release(b, InputSource.GAMEPAD) }

            // Home to Systems and Home to Library, one press each: the first time (nothing built
            // yet) and again. Frame 0 is the one the press lands in; the rest is the slide.
            fun switch(name: String, presses: Int) {
                if (only.isNotEmpty() && only.none { name.startsWith(it) }) return
                driver.home()
                driver.settle(1_500)
                measure(name, frames = 30, everyFrames = 30) { repeat(presses) { press(PadButton.R1) } }
                val first = results.last().second.take(3).joinToString(" ") { "%.1f".format(it) }
                println("Perf: ${case.key}-$name first capture-inclusive frames $first ms")
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

            if (wants("home")) {
                val baselineHome = store.prefs.value.home
                store.updatePrefs { it.copy(home = io.github.matiyaaa.fuse.model.HomeLayoutConfig(mode = io.github.matiyaaa.fuse.model.HomeMode.CHANNELS)) }
                driver.home()
                driver.settle(1_500)
                measure("home-rapid-nav", 90, 1) { i -> press(if (i % 2 == 0) PadButton.DPAD_RIGHT else PadButton.DPAD_LEFT) }
                // Exercise the actual board's controller resize modifier and spring reflow.
                driver.tap(PadButton.DPAD_LEFT)
                driver.hold(PadButton.A)
                driver.waitFor("Put down")
                driver.tap(PadButton.A)
                router.press(PadButton.X, InputSource.GAMEPAD)
                driver.settle(400)
                measure("home-widget-resize", 90, 5) { i ->
                    press(listOf(PadButton.DPAD_RIGHT, PadButton.DPAD_DOWN, PadButton.DPAD_LEFT, PadButton.DPAD_UP)[i % 4])
                }
                router.release(PadButton.X, InputSource.GAMEPAD)
                press(PadButton.B)
                store.updatePrefs { it.copy(home = baselineHome) }
                driver.settle(900)
            }

            // Tabs: R1 along the top bar and back, a press every ~110 ms.
            driver.home()
            measure("tabs-run", frames = 90, everyFrames = 7) { i -> press(if (i < 6) PadButton.R1 else PadButton.L1) }

            // Systems: a run to the right along the grid, a press every ~80 ms.
            driver.tab(Destination.SYSTEMS)
            driver.settle(1_500)
            measure("systems-run", frames = 80, everyFrames = 5) { i -> press(if (i < 8) PadButton.DPAD_RIGHT else PadButton.DPAD_LEFT) }

            measure("systems-rapid-reversal", 90, 1) { i -> press(if (i % 2 == 0) PadButton.DPAD_RIGHT else PadButton.DPAD_LEFT) }
            measure("systems-held-dpad", 90, 89) { i ->
                if (i == 0) router.press(PadButton.DPAD_RIGHT, InputSource.GAMEPAD)
                else router.release(PadButton.DPAD_RIGHT, InputSource.GAMEPAD)
            }
            router.release(PadButton.DPAD_RIGHT, InputSource.GAMEPAD)

            // Library: down the grid with the D-pad, then a touch fling.
            driver.tab(Destination.LIBRARY)
            driver.settle(1_500)
            measure("library-dpad", frames = 90, everyFrames = 6) { press(PadButton.DPAD_DOWN) }
            measure("library-rapid-reversal", frames = 90, everyFrames = 1) { i -> press(if (i % 2 == 0) PadButton.DPAD_RIGHT else PadButton.DPAD_LEFT) }
            measure("library-held-dpad", frames = 90, everyFrames = 89) { i ->
                if (i == 0) router.press(PadButton.DPAD_DOWN, InputSource.GAMEPAD)
                else router.release(PadButton.DPAD_DOWN, InputSource.GAMEPAD)
            }
            router.release(PadButton.DPAD_DOWN, InputSource.GAMEPAD)
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

            // Only the interactions asked for (-Dfuse.perf.only) are walked to, so a run of a few is quick.
            // Storage: down hundreds of games, then a fling.
            if (wants("storage")) {
            driver.openSettings()
            driver.tap(PadButton.DPAD_DOWN, settingsSections.indexOfFirst { it.id == "storage" })
            driver.tap(PadButton.DPAD_RIGHT)
            driver.tapText("Games and space")
            driver.waitFor("All systems", 30_000)
            driver.settle(3_000)
            measure("storage-dpad", frames = 120, everyFrames = 4) { press(PadButton.DPAD_DOWN) }
            measure("storage-fling", frames = 60, everyFrames = 60) { driver.touch { swipeUp(startY = height * 0.85f, endY = height * 0.3f, durationMillis = 120) } }
            }

            // A game's options from Home, down and up its rows.
            if (wants("options")) {
            driver.home()
            driver.settle(800)
            press(PadButton.X)
            driver.waitFor("Game Info")
            measure("options-dpad", frames = 60, everyFrames = 5) { i -> press(if (i < 7) PadButton.DPAD_DOWN else PadButton.DPAD_UP) }
            press(PadButton.B)
            }

            if (wants("quick-menu")) {
                driver.home()
                press(PadButton.START)
                measure("quick-menu", frames = 60, everyFrames = 3) { i -> press(if (i % 2 == 0) PadButton.DPAD_DOWN else PadButton.DPAD_UP) }
                press(PadButton.B)
            }
            // Reuse the audit's real shell fixture flows rather than engine-only stand-ins.
            fun fixture(name: String, run: () -> Unit) {
                if (!wants(name)) return
                try { run() }
                catch (e: io.github.matiyaaa.fuse.ui.shell.audit.NotCovered) {
                    gaps += "${case.key} $name: ${e.message}"
                    router.releaseAll()
                    driver.show(store)
                }
            }
            fixture("profiles") {
                val service = driver.controls.sync ?: throw io.github.matiyaaa.fuse.ui.shell.audit.NotCovered("No audit Sync service")
                service.household(asHost = false)
                driver.home()
                driver.settle(2_000)
                val people = service.profiles.value
                check(people.size >= 2)
                measure("profiles-retarget", 90, 2) { i -> service.activeProfile.value = people[i % people.size] }
                measure("profiles-tab-spam", 90, 2) { i ->
                    service.activeProfile.value = people[i % people.size]
                    press(if (i % 2 == 0) PadButton.R1 else PadButton.L1)
                }
                runBlocking { service.setEnabled(false) }
                driver.show(store)
            }
            fixture("romm") {
                store.updatePrefs { it.copy(romm = it.romm.copy(enabled = true)) }
                val backed = object : FuseStore by store {
                    override val romm = io.github.matiyaaa.fuse.ui.shell.audit.AuditRomm(store)
                }
                driver.show(backed)
                driver.tab(Destination.CARTRIDGE)
                driver.waitFor("New in Your Library")
                driver.settle(1_000)
                measure("romm-rapid-reversal", 90, 1) { i -> press(if (i % 2 == 0) PadButton.DPAD_RIGHT else PadButton.DPAD_LEFT) }
                measure("romm-shelves", 90, 5) { i -> press(if (i < 9) PadButton.DPAD_DOWN else PadButton.DPAD_UP) }
                store.updatePrefs { it.copy(romm = it.romm.copy(enabled = false)) }
                driver.show(store)
            }
            fixture("jellyfin") {
                store.updatePrefs { it.copy(cartridgeEnabled = false, jellyfin = it.jellyfin.copy(enabled = true, mode = "REMOTE", remoteAddress = io.github.matiyaaa.fuse.ui.shell.audit.AuditJellyfin.HOST)) }
                driver.settle(900)
                val service = store.jellyfin ?: throw io.github.matiyaaa.fuse.ui.shell.audit.NotCovered("No Jellyfin service")
                runBlocking { service.signIn("pat", "audit").getOrThrow() }
                driver.tab(Destination.CARTRIDGE)
                driver.waitFor("Continue watching", 30_000)
                driver.settle(1_500)
                measure("jellyfin-shelves", 90, 5) { i -> press(if (i < 9) PadButton.DPAD_DOWN else PadButton.DPAD_UP) }
                driver.tab(Destination.CARTRIDGE)
                driver.tap(PadButton.DPAD_DOWN, 2)
                driver.tap(PadButton.A)
                driver.waitFor("A to Z")
                measure("jellyfin-grid-reversal", 90, 1) { i -> press(if (i % 2 == 0) PadButton.DPAD_RIGHT else PadButton.DPAD_LEFT) }
                store.updatePrefs { it.copy(jellyfin = it.jellyfin.copy(enabled = false)) }
                driver.show(store)
            }
            fixture("store") {
                val android = driver.androidStore
                android.updatePrefs { it.copy(storeVariant = io.github.matiyaaa.fuse.model.StoreVariant.STANDARD) }
                driver.show(android)
                driver.tab(Destination.CARTRIDGE)
                driver.waitFor("Standard edition", 30_000)
                driver.tap(PadButton.DPAD_DOWN)
                driver.settle(1_500)
                measure("store-shelf-reversal", 90, 1) { i -> press(if (i % 2 == 0) PadButton.DPAD_RIGHT else PadButton.DPAD_LEFT) }
                measure("store-shelves", 90, 5) { i -> press(if (i < 9) PadButton.DPAD_DOWN else PadButton.DPAD_UP) }
                driver.show(store)
            }
            if (wants("background-scraping")) gaps += "${case.key} background-scraping: audit provider responses do not model a sustained scrape workload; not measured"
            UiRenderTrace.enabled = false
        }
        scope.cancel()
        }
        writeSummary()
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
        return "frames=${s.size} mean=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f ms, CPU budget overruns=${s.count { it > activeCase.budgetMs }}".format(s.average(), p(0.5), p(0.95), p(0.99), s.last())
    }

    private val profiles = mutableListOf<Pair<String, String>>()

    /** Each frame's work on the UI thread other than Skia painting pixels, per interaction. */
    private val uiThread = mutableListOf<Pair<String, List<Double>>>()

    /**
     * Each frame's UI thread time that is not Skia painting pixels: composition, layout and the
     * drawing code recording what to draw. This sampled estimate excludes stacks in Skia/Skiko;
     * it is not a measured hardware main-thread duration or a prediction of GPU performance.
     */
    private fun uiThreadMs(jfr: File, spans: List<Pair<java.time.Instant, java.time.Instant>>): List<Double> {
        val events = RecordingFile.readAllEvents(jfr.toPath()).filter {
            (it.eventType.name == "jdk.ExecutionSample" || it.eventType.name == "jdk.NativeMethodSample") &&
                it.getThread("sampledThread")?.javaName?.startsWith("AWT-EventQueue") == true
        }.sortedBy { it.startTime }
        return spans.map { (a, b) ->
            events.count { e ->
                !e.startTime.isBefore(a) && !e.startTime.isAfter(b) &&
                    e.stackTrace?.frames?.none { it.method.type.name.startsWith("org.jetbrains.skia") || it.method.type.name.startsWith("org.jetbrains.skiko") } != false
            } * SAMPLE_MS
        }
    }

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
     * own code that took the most of the work that isn't drawing. Phase estimates come from
     * sampled stacks, not instrumented phase durations or a hardware presentation trace.
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
            appendLine("Headless real Fuse shell, Skia software target. Each timed frame includes offscreen pixel capture/readback.")
            appendLine("These are CPU/render harness costs, not GPU presentation, compositor latency or physical device frame times.")
            appendLine("JFR sampling and trace instrumentation are enabled in every measured run.")
            appendLine("Case labels name an audit viewport and virtual cadence. They do not identify measured display refresh.")
            appendLine("CPU budget overruns compare capture-inclusive wall time with that case's virtual frame budget.")
            if (gaps.isNotEmpty()) { appendLine(); appendLine("Coverage gaps:"); gaps.distinct().forEach { appendLine("- $it") }; appendLine() }
            appendLine("| Interaction | Frames | Mean ms | p50 | p95 | p99 | Max | CPU budget overruns | Sampled non-Skia AWT mean ms | Sampled non-Skia AWT p95 |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            for ((name, times) in results) {
                val s = times.sorted()
                fun p(q: Double) = s[((s.size - 1) * q).toInt()]
                val u = uiThread.firstOrNull { it.first == name }?.second.orEmpty().sorted()
                val up = if (u.isEmpty()) 0.0 else u[((u.size - 1) * 0.95).toInt()]
                appendLine("| $name | ${s.size} | %.1f | %.1f | %.1f | %.1f | %.1f | ${s.count { it > budgets.getValue(name) }} | %.1f | %.1f |".format(s.average(), p(0.5), p(0.95), p(0.99), s.last(), u.average().takeIf { !it.isNaN() } ?: 0.0, up))
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
        private const val SIZE_PROPERTY = "fuse.perf.size"
        private const val SAMPLE_MS = 2.0
    }
}
