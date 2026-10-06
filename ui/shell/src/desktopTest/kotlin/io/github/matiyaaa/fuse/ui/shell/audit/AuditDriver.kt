package io.github.matiyaaa.fuse.ui.shell.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastHost
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastState
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.GlyphConfig
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.shell.app.CompanionApp
import io.github.matiyaaa.fuse.ui.shell.app.FuseApp
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/** What the test window shows: the whole app for one store, or the companion screen. */
internal sealed interface AuditView {
    data object Blank : AuditView
    data class App(val store: FuseStore, val platform: PlatformUi, val generation: Int, val safeMode: io.github.matiyaaa.fuse.ui.shell.app.SafeMode? = null, val flipped: Boolean = false) : AuditView
    data class Companion(val store: FuseStore, val platform: PlatformUi, val mode: DualScreenMode, val onHide: (() -> Unit)? = null) : AuditView
    /** One piece of the interface on its own (the standby screen, which waits minutes to appear). */
    class Piece(val content: @Composable () -> Unit) : AuditView
}

/** A shot or navigation step that could not be done; the scenario stops and the gap is recorded. */
internal class NotCovered(message: String) : Exception(message)

/**
 * Drives the real interface for one test window: controller presses through the [InputRouter],
 * frames stepped in time with the real clock, and settled frames written as PNGs with a manifest
 * entry each. A scenario that fails is recorded as not covered and the app is restarted, so one
 * broken path never hides the rest.
 */
@OptIn(ExperimentalTestApi::class)
internal class AuditDriver(
    private val ui: ComposeUiTest,
    val size: AuditSize,
    val router: InputRouter,
    val root: File,
    val cache: File,
    /** A second, smaller library folder that onboarding suggests. */
    val sd: File,
    val scope: CoroutineScope,
) {
    val controls = AuditControls(cache)
    var view by mutableStateOf<AuditView>(AuditView.Blank)

    /** A second toast host over the app, for the toast kinds no code path in the shell emits. */
    val extraToasts = ToastState()

    private var group = ""
    private var screen = ""
    private var lastState = "*"

    // ------------------------------------------------------------------------------------ stores

    private var library: FuseStore? = null
    private var libraryPrefs: UiPrefs? = null
    private var data: FuseData? = null
    private var firstRun: FuseStore? = null

    /** The full sample library, built on first use. */
    val libraryStore: FuseStore
        get() = library ?: runBlocking { AuditLibrary.store(root, cache, controls, scope) }.let { (store, d) ->
            library = store
            libraryPrefs = store.prefs.value
            data = d
            store
        }

    /** The library store's database, for play sessions the audit opens itself. */
    val libraryData: FuseData get() = libraryStore.let { data!! }

    private var android: FuseStore? = null

    /** An Android device: its game apps in the Android system, APKs to install. Built on first use. */
    val androidStore: FuseStore
        get() = android ?: runBlocking { AuditLibrary.androidStore(cache, controls, scope) }.also { android = it }

    private var windows: FuseStore? = null

    /** A Windows PC (see [AuditLibrary.windowsStore]), built on first use. */
    val windowsStore: FuseStore
        get() = windows ?: runBlocking { AuditLibrary.windowsStore(cache, controls, scope) }.also { windows = it }

    /** The audit device as a Windows PC: Cartridge doesn't run there. */
    val windowsPlatform: PlatformUi by lazy {
        AuditPlatform(size, features = io.github.matiyaaa.fuse.ui.shell.screenshots.ScreenshotPlatform.features.copy(cartridge = false))
    }

    /** A first run with two library folders to suggest, built on first use. */
    val firstRunStore: FuseStore
        get() = firstRun ?: runBlocking { AuditLibrary.firstRunStore(root, sd, cache, controls, scope) }.also { firstRun = it }

    val platform: PlatformUi = AuditPlatform(size)

    /** A device that can take screenshots and recordings (Android). */
    val capturePlatform: PlatformUi by lazy { AuditPlatform(size, capture = AuditCapture()) }

    /** Phone Link as Settings sees it; scenarios set what it reports. */
    val phoneLink = AuditPhoneLink()

    /** Shows the library app from a fresh start (default focus everywhere) with the baseline settings. */
    fun useLibrary(platform: PlatformUi = this.platform, prefs: (UiPrefs) -> UiPrefs = { it }) {
        val store = libraryStore
        val base = libraryPrefs!!
        // -Pfuse.audit.theme renders every screen in that theme, unless a scenario picks its own.
        val theme = System.getProperty("fuse.audit.theme")?.takeIf { it.isNotBlank() }
        store.updatePrefs { prefs(base).let { p -> if (theme != null && p.themeId == base.themeId) p.copy(themeId = theme) else p } }
        controls.cartridge = io.github.matiyaaa.fuse.model.CartridgeStatus(installed = false)
        store.cartridge.refresh()
        show(store, platform)
    }

    /** The audit device with a second screen games and apps can open on. */
    val twoScreens: PlatformUi by lazy {
        AuditPlatform(size, features = io.github.matiyaaa.fuse.ui.shell.screenshots.ScreenshotPlatform.features.copy(secondScreen = true, launchOnOtherDisplay = true))
    }

    /** Shows [store] in a freshly started app. */
    fun show(store: FuseStore, platform: PlatformUi = this.platform, safeMode: io.github.matiyaaa.fuse.ui.shell.app.SafeMode? = null, flipped: Boolean = false) {
        val generation = ((view as? AuditView.App)?.generation ?: 0) + 1
        view = AuditView.App(store, platform, generation, safeMode, flipped)
        settle(1_600)
    }

    /** Restarts the app on the same store: navigation, focus and remembered selections start over. */
    fun restartApp() {
        val v = view as? AuditView.App ?: return
        view = v.copy(generation = v.generation + 1)
        settle(1_600)
    }

    @Composable
    fun Content() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val v = view) {
                AuditView.Blank -> Unit
                is AuditView.App -> key(v.generation) {
                    FuseApp(v.store, v.platform, router, phoneLink, safeMode = v.safeMode, showcaseElsewhere = v.flipped)
                    ExtraToasts(v.store, v.platform)
                }
                is AuditView.Companion -> key(v.mode, v.store) { CompanionApp(v.store, v.platform, v.mode, v.onHide) }
                is AuditView.Piece -> key(v) {
                    io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme {
                        androidx.compose.runtime.CompositionLocalProvider(io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter provides router) { v.content() }
                    }
                }
            }
        }
    }

    @Composable
    private fun ExtraToasts(store: FuseStore, platform: PlatformUi) {
        val prefs by store.prefs.collectAsState()
        FuseTheme(
            spec = ThemePresets.byId(prefs.themeId),
            motion = prefs.motion,
            quality = RenderQuality.of(prefs.performance, platform.device, prefs.lowPower),
            glyphs = GlyphConfig(prefs.input.glyphs, prefs.input.confirmOnRight),
            glass = prefs.glass,
            highContrastFocus = prefs.highContrastFocus,
        ) { ToastHost(extraToasts) }
    }

    // --------------------------------------------------------------------------------- scenarios

    /**
     * Runs one screen's steps when the `fuse.audit.only` filter wants it. Anything that fails is
     * recorded as not covered, with the reason, and the app restarts for the next scenario.
     */
    fun scenario(group: String, screen: String, block: () -> Unit) {
        if (!Audit.wants(group, screen)) return
        this.group = group
        this.screen = screen
        lastState = "*"
        println("Audit: ${size.name} $group/$screen")
        try {
            block()
        } catch (e: Throwable) {
            if (e is org.junit.internal.AssumptionViolatedException) throw e
            // Anything but a screen that couldn't be reached is a bug worth its stack trace.
            if (e !is NotCovered && e !is AssertionError) e.printStackTrace(System.out)
            val reason = (e.message ?: e.toString()).lines().take(12).joinToString(" | ")
            Audit.uncovered(AuditGap(size.label, group, screen, lastState, reason))
            recover()
        }
    }

    /** Records a screen or state that cannot be rendered, without failing the run. */
    fun gap(group: String, screen: String, state: String, reason: String) {
        if (!Audit.wants(group, screen)) return
        Audit.uncovered(AuditGap(size.label, group, screen, state, reason))
    }

    private fun recover() {
        controls.launchGate?.complete(RunResult.Failed("The audit released this launch."))
        controls.launchGate = null
        controls.resumeListing()
        router.releaseAll()
        runCatching { if (view is AuditView.App) restartApp() else settle(500) }
    }

    // ----------------------------------------------------------------------------------- frames

    /**
     * Lets the interface settle. It animates forever (clock, ambient light), so the test clock never
     * goes idle by itself: it is moved frame by frame in step with real time, so the store's
     * background work and the on-screen clocks agree with what the frames show.
     */
    fun settle(ms: Long = SETTLE_MS) {
        val start = System.nanoTime()
        var last = start
        while ((System.nanoTime() - start) / 1_000_000 < ms) {
            val now = System.nanoTime()
            ui.mainClock.advanceTimeBy(((now - last) / 1_000_000).coerceIn(FRAME_MS, MAX_STEP_MS))
            last = now
            Thread.sleep(4)
        }
    }

    fun pumpUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        var last = System.nanoTime()
        while (System.currentTimeMillis() < end) {
            val now = System.nanoTime()
            ui.mainClock.advanceTimeBy(((now - last) / 1_000_000).coerceIn(FRAME_MS, MAX_STEP_MS))
            last = now
            if (condition()) return
            Thread.sleep(10)
        }
        val tree = runCatching { ui.onRoot(useUnmergedTree = true).printToString(maxDepth = 80) }.getOrDefault("(no tree)")
        val texts = tree.lines().filter { "Text = " in it }.map { it.substringAfter("Text = ").trim() }.distinct().take(40)
        throw NotCovered("Waited ${timeoutMs / 1000} s for $what. On screen: ${texts.joinToString(", ")}")
    }

    fun hasText(text: String, ignoreCase: Boolean = true): Boolean =
        ui.onAllNodesWithText(text, substring = true, ignoreCase = ignoreCase, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Chooses the menu row showing exactly [text] the way a player does (menus whose order changes
     * between versions): Down until that row is the selected one, then A.
     */
    fun tapText(text: String, step: PadButton = PadButton.DPAD_DOWN, substring: Boolean = false) {
        waitFor(text.take(1))
        repeat(40) {
            settle(STEP_MS)
            val on = ui.onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring) and androidx.compose.ui.test.isSelected()).fetchSemanticsNodes()
            if (on.isNotEmpty()) {
                tap(PadButton.A)
                return
            }
            tap(step)
        }
        throw NotCovered("\"$text\" was never selected")
    }

    /** Moves with [step] until the item showing [text] is the selected one, without pressing it. */
    fun focusText(text: String, substring: Boolean = false, step: () -> Unit = { tap(PadButton.DPAD_DOWN) }) {
        waitFor(text.take(1))
        repeat(40) {
            settle(STEP_MS)
            val on = ui.onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring) and androidx.compose.ui.test.isSelected()).fetchSemanticsNodes()
            if (on.isNotEmpty()) return
            step()
        }
        throw NotCovered("\"$text\" was never selected")
    }

    /**
     * Moves with [step] until a selected item shows any of [texts] (each matched as part of its
     * text): for rows whose wording depends on the device ("Not installed", "Not found").
     */
    fun focusAny(vararg texts: String, step: () -> Unit = { tap(PadButton.DPAD_DOWN) }) {
        waitFor(texts.first().take(1))
        repeat(40) {
            settle(STEP_MS)
            val on = texts.any { t ->
                ui.onAllNodes(androidx.compose.ui.test.hasText(t, substring = true) and androidx.compose.ui.test.isSelected()).fetchSemanticsNodes().isNotEmpty()
            }
            if (on) return
            step()
        }
        throw NotCovered("None of ${texts.joinToString { "\"$it\"" }} was ever selected")
    }

    /**
     * Moves with [step] until the hint line offers [label]: how a screen says what is chosen when its
     * items carry no selection a test can read (a row of a page, a download). After each step it
     * gives the hints time to follow the selection (they change a few frames later, and frames are
     * slow on a busy machine), so it never steps past the item it is looking for.
     */
    fun focusHint(label: String, step: () -> Unit = { tap(PadButton.DPAD_DOWN) }) {
        repeat(40) { i ->
            if (i > 0) step()
            if (pumpFor(HINT_WAIT_MS) { hasExactText(label) }) return
        }
        throw NotCovered("The hint \"$label\" never showed")
    }

    /** Lets frames run for up to [ms] until [condition] holds; false when it never did. */
    private fun pumpFor(ms: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        var last = System.nanoTime()
        while (System.currentTimeMillis() < end) {
            val now = System.nanoTime()
            ui.mainClock.advanceTimeBy(((now - last) / 1_000_000).coerceIn(FRAME_MS, MAX_STEP_MS))
            last = now
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }

    /** True when some text on screen reads exactly [text] (a hint, a button label), not just contains it. */
    fun hasExactText(text: String): Boolean =
        ui.onAllNodesWithText(text, substring = false, ignoreCase = false, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun waitFor(text: String, timeoutMs: Long = 15_000, ignoreCase: Boolean = true) =
        pumpUntil("\"$text\"", timeoutMs) { hasText(text, ignoreCase) }

    fun waitGone(text: String, timeoutMs: Long = 10_000) = pumpUntil("\"$text\" to go away", timeoutMs) { !hasText(text) }

    // ------------------------------------------------------------------------------------ input

    /** Presses and releases a controller button, then lets the next frame compose. */
    fun tap(button: PadButton, times: Int = 1, source: InputSource = InputSource.GAMEPAD) {
        repeat(times) {
            router.press(button, source)
            router.release(button, source)
            settle(STEP_MS)
        }
    }

    /** One navigation action through the router, as a mapped button press would send it; returns what the screen did. */
    fun nav(action: NavAction): NavResult {
        val r = router.dispatch(action, InputSource.GAMEPAD)
        settle(STEP_MS)
        return r
    }

    /** Holds a button (long press: arranging Home, picking up a channel). */
    /**
     * The time the frames right after [action] took to make (composition, layout and drawing), the
     * slowest first: what a tab switch costs.
     */
    fun frameCost(frames: Int = 12, action: () -> Unit): List<Long> {
        action()
        return List(frames) {
            val t0 = System.nanoTime()
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            (System.nanoTime() - t0) / 1_000
        }.sortedDescending()
    }

    fun hold(button: PadButton, ms: Long = 900) {
        router.press(button, InputSource.GAMEPAD)
        settle(ms)
        router.release(button, InputSource.GAMEPAD)
        settle(STEP_MS)
    }

    /** Types on a hardware keyboard into the open text field (search, rename). */
    fun type(text: String) {
        pumpUntil("a text field to accept typing", 5_000) { router.textInput != null }
        router.textInput!!.type(text)
        settle(STEP_MS)
    }

    fun clearTyping(count: Int = 40) {
        val input = router.textInput ?: return
        repeat(count) { input.backspace() }
        settle(STEP_MS)
    }

    /** Moves down [index] rows in the open menu and chooses that row. */
    fun choose(index: Int) {
        tap(PadButton.DPAD_DOWN, index)
        tap(PadButton.A)
    }

    /** Back to Home from anywhere (the Guide button). */
    fun home() {
        tap(PadButton.MODE)
        settle(900)
    }

    /** Home, then along the top bar to [destination] with the shoulder buttons. */
    fun tab(destination: Destination) {
        home()
        tap(PadButton.R1, destination.ordinal)
        settle()
    }

    /**
     * Walks a grid of [count] items with the D-pad, starting on the first item. The column count is
     * learned by moving right until the first row ends, so it works at every screen size.
     */
    inner class Grid(private val count: Int) {
        var index = 0
            private set
        private var columns = 0

        private fun learn() {
            check(index == 0) { "Columns are learned from the first item" }
            var moves = 0
            while (moves < count - 1 && nav(NavAction.RIGHT) == NavResult.MOVED) moves++
            columns = moves + 1
            repeat(moves) { nav(NavAction.LEFT) }
        }

        fun goTo(to: Int) {
            require(to in 0 until count) { "No item $to in a grid of $count" }
            if (to == index) return
            if (columns == 0) learn()
            while (index / columns < to / columns) {
                if (nav(NavAction.DOWN) != NavResult.MOVED) throw NotCovered("Could not move down to item $to")
                index = (index + columns).coerceAtMost(count - 1)
            }
            while (index / columns > to / columns) {
                if (nav(NavAction.UP) != NavResult.MOVED) throw NotCovered("Could not move up to item $to")
                index -= columns
            }
            while (index < to) {
                if (nav(NavAction.RIGHT) != NavResult.MOVED) throw NotCovered("Could not move right to item $to")
                index++
            }
            while (index > to) {
                if (nav(NavAction.LEFT) != NavResult.MOVED) throw NotCovered("Could not move left to item $to")
                index--
            }
        }
    }

    // ------------------------------------------------------------------------------------ shots

    /** The centre of the lowest on-screen [text] (a tile's label rather than a header above it). */
    fun textCentre(text: String, topmost: Boolean = false): androidx.compose.ui.geometry.Offset {
        val nodes = ui.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "\"$text\" is not on screen" }
        return (if (topmost) nodes.minBy { it.boundsInRoot.top } else nodes.maxBy { it.boundsInRoot.top }).boundsInRoot.center
    }

    /** Where everything described as [description] (part of it) is on screen, as touch targets. */
    fun describedBounds(description: String): List<androidx.compose.ui.geometry.Rect> =
        ui.onAllNodes(androidx.compose.ui.test.hasContentDescription(description, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }

    /**
     * Advances the app's clock by exactly [ms] in frames, however slowly they render: for holds,
     * which [settle] (real time) can undercount when frames are slow.
     */
    fun advanceExactly(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(FRAME_MS, left)
            ui.mainClock.advanceTimeBy(step)
            left -= step
        }
    }

    /** Touch input on the whole window, in window pixels. A finger stays down between calls. */
    fun touch(block: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) {
        ui.onRoot().performTouchInput(block)
    }

    /** Settles, then writes the current frame as `<dir>/<size>/<group>/<screen>--<state>.png`. */
    fun shoot(state: String, settleMs: Long = SETTLE_MS) {
        lastState = state
        settle(settleMs)
        var frame = capture()
        if (isBlank(frame)) {
            settle(1_500)
            frame = capture()
            if (isBlank(frame)) throw NotCovered("The frame for \"$state\" rendered blank")
        }
        val dir = Audit.dir ?: return
        val file = File(dir, "${size.name}/$group/${Audit.slug(screen)}--${Audit.slug(state)}.png")
        file.parentFile.mkdirs()
        file.delete()
        ImageIO.write(frame, "png", file)
        Audit.shot(AuditShot(file.absolutePath, group, screen, state, size.label))
        println("Audit shot: ${file.absolutePath}")
        // A failure from here on happened after this shot, on the way to the next one.
        lastState = "after: $state"
    }

    private fun capture(): BufferedImage {
        val frame = ui.onRoot().captureToImage().toAwtImage()
        val rgb = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(frame, 0, 0, null); dispose() }
        return rgb
    }

    /** True when a grid of samples across the frame is a single colour. */
    private fun isBlank(img: BufferedImage): Boolean {
        val first = img.getRGB(0, 0)
        for (y in 0 until 24) for (x in 0 until 24) {
            if (img.getRGB(x * (img.width - 1) / 23, y * (img.height - 1) / 23) != first) return false
        }
        return true
    }

    companion object {
        const val SETTLE_MS = 1_400L
        const val STEP_MS = 90L
        private const val HINT_WAIT_MS = 1_200L
        private const val FRAME_MS = 16L
        private const val MAX_STEP_MS = 250L
    }
}
