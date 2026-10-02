package io.github.matiyaaa.fuse.ui.shell.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.shell.app.FuseApp
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.io.File
import java.nio.file.Files
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
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue

/**
 * Renders screenshots from the real interface over the real store (the README and website now use
 * captures from a device; these renders are for checking screens at a glance): a library of
 * well-known games ([SampleLibrary]) is scanned from a temporary folder and given its details, Fuse
 * fills its art over the network the way it does on a device (libretro thumbnails for games, Art
 * Book Next for systems), the app is driven with controller presses, and each settled frame is
 * written as a WebP.
 *
 * Not part of the normal test run. It only runs when the `fuse.screenshots.dir` system property
 * names an output folder, which the `desktopScreenshots` Gradle task sets:
 *
 *     ./gradlew :ui:shell:desktopScreenshots
 *
 * With a SteamGridDB API key in the environment (`STEAMGRIDDB_API_KEY`, only ever handed to Fuse's
 * key store, never printed), SteamGridDB is searched first. `-Pfuse.screenshots.sources` picks the
 * sources instead, as a comma list of [ScrapeProviderId] names.
 *
 * Each frame is written as a WebP (see [shoot]).
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
        // A fixed, readable folder name: the game page shows where a game's file is.
        root = File(System.getProperty("java.io.tmpdir"), "fuse-sample-library").apply { deleteRecursively(); mkdirs() }
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

    private fun newServices(live: Boolean = false) =
        ScreenshotServices(FuseData(DesktopDatabase.open(File(cache, "fuse-${System.nanoTime()}.db").absolutePath)), cache, live)

    private val key: String = System.getenv(KEY_VARIABLE)?.trim().orEmpty()

    /**
     * The sources art is filled from: `fuse.screenshots.sources`, or else SteamGridDB (with a key)
     * and the libretro thumbnails.
     */
    private val sources: List<ScrapeProviderId> =
        System.getProperty(SOURCES_PROPERTY).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            .map { ScrapeProviderId.valueOf(it.uppercase()) }
            .ifEmpty { listOfNotNull(ScrapeProviderId.STEAMGRIDDB.takeIf { key.isNotEmpty() }, ScrapeProviderId.LIBRETRO) }

    /**
     * The sample library scanned and given its details, its art and system art filled from
     * [sources], with favourites, collections and play history, and setup done.
     */
    private fun libraryStore(): FuseStore = runBlocking {
        assumeTrue("SteamGridDB needs $KEY_VARIABLE", ScrapeProviderId.STEAMGRIDDB !in sources || key.isNotEmpty())
        SampleLibrary.writeTo(root)
        val services = newServices(live = true)
        if (key.isNotEmpty()) services.secrets.put(SecretKeys.SGDB_API_KEY, key)
        services.data.settings.update {
            it.copy(
                scraping = it.scraping.copy(providerOrder = sources, disabledProviders = ScrapeProviderId.entries - sources.toSet(), autoFill = false),
                // Online, Fuse would otherwise find this build "out of date" and say so on every screen.
                updates = it.updates.copy(checkForUpdates = false),
            )
        }
        val store = createFuseStore(services, scope)
        store.updatePrefs { it.copy(onboardingDone = true) }
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(60_000) {
            store.sources.scan.first { it.phase == ScanPhase.DONE }
            store.library.platforms.first { it.size == SampleLibrary.platformCount }
            store.emulators.installed.first { it.isNotEmpty() }
        }
        withTimeout(30_000) { store.library.games(GameQuery()).first { it.size == SampleLibrary.games.size } }
        val ids = SampleLibrary.applyDetails(services.data, root)
        SampleLibrary.applyHistory(store, services.data, ids, Clock.System.now().toEpochMilliseconds())

        // Art for every game and system, fetched and stored the way Fill everything does it.
        println("Filling art from ${sources.joinToString { it.displayName }} and system art from Art Book Next...")
        store.media.fillEverything()
        store.media.downloadSystemArt()
        withTimeout(FILL_TIMEOUT_MS) {
            store.media.fillProgress.first { it?.finished == true }
            store.media.systemArtProgress.first { it?.finished == true }
        }
        reportArt(store)
        withTimeout(30_000) {
            store.library.home.first { feed ->
                feed.continuePlaying.size >= 8 &&
                    feed.favorites.size == SampleLibrary.games.count { it.favorite } &&
                    // Series (Zelda, Mario Kart and the like) join the collections the user made.
                    feed.collections.size >= SampleLibrary.collections.size &&
                    feed.playtime.weekSeconds > 0
            }
        }
        store
    }

    /** Lists what the fill found, so a game matched to the wrong one (or to nothing) is easy to spot. */
    private suspend fun reportArt(store: FuseStore) {
        val cards = store.library.games(GameQuery()).first()
        for (card in cards.sortedBy { it.title }) {
            val a = card.art
            val kinds = listOfNotNull(
                "box".takeIf { a.boxart != null }, "square".takeIf { a.square != null }, "icon".takeIf { a.icon != null },
                "grid".takeIf { a.grid != null }, "hero".takeIf { a.hero != null }, "logo".takeIf { a.logo != null },
            )
            println("Art: ${card.title}: ${kinds.ifEmpty { listOf("none") }.joinToString()}")
        }
        for (p in store.library.platforms.first()) {
            val a = p.art
            println("System art: ${p.platform.name}: ${if (a.logo != null) "logo" else "no logo"}, ${if (listOf(a.hero, a.boxart, a.grid).any { it != null }) "artwork" else "no artwork"}")
        }
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
        // A press that goes nowhere (left of the first tile) so the hints show controller buttons.
        router.tap(PadButton.DPAD_LEFT)
        settle(3_000)
        shoot("home")

        // Home, Channels mode: a board arranged and resized as a user would.
        store.updatePrefs { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS, board = SampleLibrary.channelBoard)) }
        settle()
        shoot("home-channels")
        store.updatePrefs { it.copy(home = HomeLayoutConfig()) }
        settle()

        // Systems, with the logos, artwork and colours from Art Book Next, on the Switch.
        router.tap(PadButton.R1)
        settle()
        router.tap(PadButton.DPAD_DOWN)
        router.tap(PadButton.DPAD_RIGHT)
        settle(3_000)
        shoot("systems")

        // Library, Icon layout (the default), a few games along.
        router.tap(PadButton.R1)
        settle()
        repeat(5) { router.tap(PadButton.DPAD_RIGHT) }
        settle(3_000)
        shoot("library-icons")

        // Library, Cover Grid, on the first game.
        store.updatePrefs { it.copy(defaultLayout = LibraryLayout.COVER_GRID) }
        settle()
        repeat(5) { router.tap(PadButton.DPAD_LEFT) }
        settle(3_000)
        shoot("library-covers")

        // A game page, opened from Home through the game's options (Options, then Game Info).
        router.tap(PadButton.L1)
        router.tap(PadButton.L1)
        settle()
        router.tap(PadButton.X)
        pumpUntil { hasText("Game Info") }
        choose(router, "Game Info")
        pumpUntil { hasText("Starts in") }
        settle(3_000)
        shoot("game")
        router.tap(PadButton.B)
        settle()

        // Quick menu (Start).
        router.tap(PadButton.START)
        pumpUntil { hasText("Arrange Home") }
        settle()
        shoot("quick-menu")

        // The theme gallery, with the one in use chosen: Settings (through the quick menu's Display),
        // back to the sections, Appearance, then Theme.
        choose(router, "Display", step = PadButton.DPAD_RIGHT)
        pumpUntil { hasText("Theme, motion, glass, CRT") }
        settle()
        router.tap(PadButton.B)
        select(router, "Appearance", step = PadButton.DPAD_UP)
        router.tap(PadButton.DPAD_RIGHT)
        select(router, "Theme")
        router.tap(PadButton.A)
        pumpUntil { hasText("Starlight") }
        settle(3_000)
        shoot("themes")
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
        router.tap(PadButton.DPAD_LEFT)
        settle(3_000)
        shoot("onboarding")
    }

    /**
     * Moves with [step] until the item labelled [text] is the selected one, then presses A: the way
     * a player picks it, without depending on where a menu puts it.
     */
    private fun ComposeUiTest.choose(router: InputRouter, text: String, step: PadButton = PadButton.DPAD_DOWN) {
        select(router, text, step)
        router.tap(PadButton.A)
    }

    /** Moves with [step] until the item labelled exactly [text] is the selected one. */
    private fun ComposeUiTest.select(router: InputRouter, text: String, step: PadButton = PadButton.DPAD_DOWN) {
        repeat(40) {
            settle(150)
            if (onAllNodes(androidx.compose.ui.test.hasText(text) and isSelected()).fetchSemanticsNodes().isNotEmpty()) return
            router.tap(step)
        }
        throw AssertionError("\"$text\" was never selected")
    }

    private fun ComposeUiTest.hasText(text: String, ignoreCase: Boolean = false): Boolean =
        onAllNodesWithText(text, substring = true, ignoreCase = ignoreCase, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Lets the interface settle for at least [ms] of both real and test-clock time. The interface
     * animates forever (clock, ambient light), so the test clock never goes idle by itself: it is
     * moved frame by frame in step with real time, so the store's background work and the on-screen
     * clocks agree with the frames, and it keeps going while frames render slowly until animations
     * have had their full time.
     */
    private fun ComposeUiTest.settle(ms: Long = 2_000) {
        val start = System.nanoTime()
        var last = start
        var advanced = 0L
        while (advanced < ms || (System.nanoTime() - start) / 1_000_000 < ms) {
            val now = System.nanoTime()
            val step = ((now - last) / 1_000_000).coerceIn(FRAME_MS, MAX_STEP_MS)
            mainClock.advanceTimeBy(step)
            advanced += step
            last = now
            Thread.sleep(4)
        }
    }

    private fun ComposeUiTest.pumpUntil(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        var last = System.nanoTime()
        while (System.currentTimeMillis() < end) {
            val now = System.nanoTime()
            mainClock.advanceTimeBy(((now - last) / 1_000_000).coerceIn(FRAME_MS, MAX_STEP_MS))
            last = now
            if (condition()) return
            Thread.sleep(10)
        }
        val tree = runCatching { onRoot(useUnmergedTree = true).printToString(maxDepth = 60) }.getOrDefault("(no tree)")
        throw AssertionError("Condition not met within $timeoutMs ms. Screen:\n" + tree.lines().filter { "Text" in it }.joinToString("\n"))
    }

    /**
     * Writes the current frame as a WebP at [WEBP_QUALITY]: the art makes these photographic, and a
     * lossless PNG of one is several times larger without looking any different.
     */
    private fun ComposeUiTest.shoot(name: String) {
        val frame = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
        val data = frame.encodeToData(EncodedImageFormat.WEBP, WEBP_QUALITY) ?: error("Skia could not encode $name as WebP")
        val file = File(outDir, "$name.webp")
        file.parentFile.mkdirs()
        file.writeBytes(data.bytes)
        println("Screenshot: ${file.absolutePath} (${file.length() / 1024} KB)")
    }

    private fun InputRouter.tap(button: PadButton) {
        press(button, InputSource.GAMEPAD)
        release(button, InputSource.GAMEPAD)
    }

    companion object {
        const val OUTPUT_PROPERTY = "fuse.screenshots.dir"
        const val SOURCES_PROPERTY = "fuse.screenshots.sources"
        const val KEY_VARIABLE = "STEAMGRIDDB_API_KEY"

        /** The sources answer a few requests a second; a library of this size takes a few minutes. */
        private const val FILL_TIMEOUT_MS = 20 * 60_000L

        /** 1920 x 1080 pixels at 1.5x: the 1280 x 720 dp layout of a 1080p handheld. */
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val DENSITY = 1.5f
        private const val FRAME_MS = 16L
        private const val WEBP_QUALITY = 90

        /**
         * Frames render slowly in software at this size, so each step follows real time (the
         * on-screen clock only ticks when the test clock reaches the next minute); the cap only
         * guards against a single huge jump.
         */
        private const val MAX_STEP_MS = 1_000L
    }
}
