package io.github.matiyaaa.fuse.ui.shell.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.shell.app.FuseApp
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
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
 * Renders the README screenshots from the real interface over the real store: a sample library of
 * invented games is scanned from a temporary folder, the app is driven with controller presses, and
 * each settled frame is written as a PNG.
 *
 * Not part of the normal test run. It only runs when the `fuse.screenshots.dir` system property
 * names an output folder, which the `desktopScreenshots` Gradle task sets:
 *
 *     ./gradlew :ui:shell:desktopScreenshots
 */
@OptIn(ExperimentalTestApi::class)
class ReadmeScreenshots {
    private val outDir: File? = System.getProperty(OUTPUT_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        assumeTrue("Set -D$OUTPUT_PROPERTY=<folder> (or run :ui:shell:desktopScreenshots) to render screenshots", outDir != null)
        outDir!!.mkdirs()
        root = Files.createTempDirectory("fuse-shots-lib").toFile()
        cache = Files.createTempDirectory("fuse-shots-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        if (outDir == null) return
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    private fun newServices() =
        ScreenshotServices(FuseData(DesktopDatabase.open(File(cache, "fuse-${System.nanoTime()}.db").absolutePath)), cache)

    /** The sample library scanned, with favourites and play history, and setup already done. */
    private fun libraryStore(): FuseStore = runBlocking {
        SampleLibrary.writeTo(root)
        val services = newServices()
        val store = createFuseStore(services, scope)
        store.updatePrefs { it.copy(onboardingDone = true) }
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(60_000) {
            store.sources.scan.first { it.phase == ScanPhase.DONE }
            store.library.platforms.first { it.size == SampleLibrary.platformCount }
            store.emulators.installed.first { it.isNotEmpty() }
        }
        val cards = withTimeout(30_000) { store.library.games(GameQuery()).first { it.size == SampleLibrary.games.size } }
        SampleLibrary.applyHistory(store, services.data, cards, Clock.System.now().toEpochMilliseconds())
        withTimeout(30_000) {
            store.library.home.first { feed ->
                feed.continuePlaying.size >= 8 && feed.favorites.size == SampleLibrary.games.count { it.favorite } && feed.playtime.weekSeconds > 0
            }
        }
        store
    }

    @Test
    fun tour() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        val store = libraryStore()
        val router = InputRouter(scope)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(DENSITY)) {
                FuseApp(store, ScreenshotPlatform, router)
            }
        }

        // Home, Flow mode: Continue Playing is the first shelf and its first game is in focus.
        pumpUntil { hasText("Continue playing", ignoreCase = true) }
        settle()
        shoot("home")

        // Home, Channels mode.
        store.updatePrefs { it.copy(home = it.home.copy(mode = HomeMode.CHANNELS)) }
        settle()
        shoot("home-channels")
        store.updatePrefs { it.copy(home = it.home.copy(mode = HomeMode.FLOW)) }
        settle()

        // Library, Icon layout (the default).
        router.tap(PadButton.R1)
        settle()
        router.tap(PadButton.DPAD_RIGHT)
        router.tap(PadButton.DPAD_RIGHT)
        router.tap(PadButton.DPAD_DOWN)
        settle()
        shoot("library-icons")

        // Library, Cover Grid.
        store.updatePrefs { it.copy(defaultLayout = LibraryLayout.COVER_GRID) }
        settle()
        shoot("library-covers")

        // Systems.
        router.tap(PadButton.R1)
        settle()
        router.tap(PadButton.DPAD_RIGHT)
        settle()
        shoot("systems")

        // A game page, opened from Home through the game's options.
        router.tap(PadButton.L1)
        router.tap(PadButton.L1)
        settle()
        router.tap(PadButton.X)
        settle(800)
        router.tap(PadButton.DPAD_DOWN)
        router.tap(PadButton.A)
        settle()
        shoot("game")
        router.tap(PadButton.B)
        settle()

        // Quick menu.
        router.tap(PadButton.START)
        settle()
        shoot("quick-menu")
    }

    @Test
    fun onboarding() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        val store = runBlocking { createFuseStore(newServices(), scope) }
        val router = InputRouter(scope)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(DENSITY)) {
                FuseApp(store, ScreenshotPlatform, router)
            }
        }
        pumpUntil { hasText("Welcome to Fuse") }
        settle(3_000)
        shoot("onboarding")
    }

    private fun ComposeUiTest.hasText(text: String, ignoreCase: Boolean = false): Boolean =
        onAllNodesWithText(text, substring = true, ignoreCase = ignoreCase, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Lets the interface settle: the clock moves frame by frame (the interface animates forever, so
     * it never goes idle by itself) while real time passes for the store's background work.
     */
    private fun ComposeUiTest.settle(ms: Long = 2_000) {
        var elapsed = 0L
        while (elapsed < ms) {
            mainClock.advanceTimeBy(FRAME_MS)
            Thread.sleep(6)
            elapsed += FRAME_MS
        }
    }

    private fun ComposeUiTest.pumpUntil(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            mainClock.advanceTimeBy(FRAME_MS)
            if (condition()) return
            Thread.sleep(10)
        }
        val tree = runCatching { onRoot(useUnmergedTree = true).printToString(maxDepth = 60) }.getOrDefault("(no tree)")
        throw AssertionError("Condition not met within $timeoutMs ms. Screen:\n" + tree.lines().filter { "Text" in it }.joinToString("\n"))
    }

    private fun ComposeUiTest.shoot(name: String) {
        val image = onRoot().captureToImage().toAwtImage()
        val file = File(outDir, "$name.png")
        ImageIO.write(image, "png", file)
        println("Screenshot: ${file.absolutePath} (${file.length() / 1024} KB)")
    }

    private fun InputRouter.tap(button: PadButton) {
        press(button, InputSource.GAMEPAD)
        release(button, InputSource.GAMEPAD)
    }

    companion object {
        const val OUTPUT_PROPERTY = "fuse.screenshots.dir"

        /** 1920 x 1080 pixels at 1.5x: the 1280 x 720 dp layout of a 1080p handheld. */
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val DENSITY = 1.5f
        private const val FRAME_MS = 16L
    }
}
