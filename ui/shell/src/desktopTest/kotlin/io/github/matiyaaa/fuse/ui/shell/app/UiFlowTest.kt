package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.CapabilityProfile
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchDisplay
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.designsystem.sound.UiSounds
import io.github.matiyaaa.fuse.ui.shell.platform.Haptics
import io.github.matiyaaa.fuse.ui.shell.platform.HomeRole
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformFeatures
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.platform.QuickControls
import io.github.matiyaaa.fuse.ui.shell.platform.StorageAccess
import io.github.matiyaaa.fuse.ui.shell.platform.StorageState
import io.github.matiyaaa.fuse.ui.shell.platform.VideoPreview
import io.github.matiyaaa.fuse.ui.shell.store.FakeServices
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The real interface over the real store, rendered headless and driven with controller presses:
 * first-run onboarding, and Home to a launched game.
 */
@OptIn(ExperimentalTestApi::class)
class UiFlowTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-ui-lib").toFile()
        cache = Files.createTempDirectory("fuse-ui-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "gba").mkdirs()
        File(root, "gba/Advance Wars (USA).gba").writeBytes(ByteArray(512))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    private fun newServices() = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse-${System.nanoTime()}.db").absolutePath)), cache)

    @Test
    fun firstRunStartsOnboardingAndConfirmAdvances() = runComposeUiTest {
        val services = newServices()
        val store = runBlocking { createFuseStore(services, scope) }
        val router = InputRouter(scope)
        mainClock.autoAdvance = false
        setContent { FuseApp(store, TestPlatform, router) }

        pumpUntil { onAllNodesWithText("Welcome to Fuse").fetchSemanticsNodes().isNotEmpty() }
        router.tap(PadButton.A)
        pumpUntil { onAllNodesWithText("Tuned for this device").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Tuned for this device").assertExists()
    }

    @Test
    fun homeShowsTheLibraryAndConfirmLaunchesTheGame() = runComposeUiTest {
        val services = newServices()
        val store: FuseStore = runBlocking {
            createFuseStore(services, scope).also { s ->
                s.updatePrefs { it.copy(onboardingDone = true) }
                s.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
                withTimeout(20_000) { s.library.home.first { feed -> feed.recentlyAdded.isNotEmpty() } }
            }
        }
        val router = InputRouter(scope)
        mainClock.autoAdvance = false
        setContent { FuseApp(store, TestPlatform, router) }

        // Home opens on the Systems shelf; the new game waits on the shelf below it.
        pumpUntil { onAllNodesWithText("NEW IN YOUR LIBRARY").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("Game Boy Advance", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "The system is focused first")
        router.tap(PadButton.DPAD_DOWN)
        // Focus moved to the game: its name is on the stage.
        pumpUntil { onAllNodesWithText("Advance Wars", substring = true).fetchSemanticsNodes().isNotEmpty() }

        // Confirm on the focused game starts it through the launcher, with mGBA's documented command.
        router.tap(PadButton.A)
        pumpUntil { services.launched.isNotEmpty() }
        val plan = assertIs<LaunchPlan.Command>(services.launched.single().plan)
        assertTrue(plan.argv.any { it.endsWith("Advance Wars (USA).gba") }, plan.argv.toString())
        assertEquals("linux.mgba", plan.emulatorId.value)
    }

    @Test
    fun oneMouseClickPlaysAGame() = runComposeUiTest {
        val services = newServices()
        val store: FuseStore = runBlocking {
            createFuseStore(services, scope).also { s ->
                s.updatePrefs { it.copy(onboardingDone = true) }
                s.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
                withTimeout(20_000) { s.library.home.first { feed -> feed.recentlyAdded.isNotEmpty() } }
            }
        }
        val router = InputRouter(scope)
        mainClock.autoAdvance = false
        setContent { FuseApp(store, TestPlatform, router) }
        pumpUntil { onAllNodesWithText("NEW IN YOUR LIBRARY").fetchSemanticsNodes().isNotEmpty() }
        // The game's tile, as a mouse sees it: moved over, then clicked once.
        // Its tile shows the made-up art's initials while there is no cover.
        val tile = androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("AW"))
        pumpUntil { onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        var clicks = 0
        while (services.launched.isEmpty() && clicks < 4) {
            onAllNodes(tile, useUnmergedTree = true).onFirst().performMouseInput {
                moveTo(center)
                click(center)
            }
            clicks++
            repeat(20) { mainClock.advanceTimeBy(32); Thread.sleep(8) }
            if (services.launched.isEmpty()) runCatching { pumpUntil(1_500) { services.launched.isNotEmpty() } }
        }
        assertEquals(1, clicks, "Clicks it took to play a game")
    }

    @Test
    fun aGameOpenedOnTheOtherScreenLeavesFuseUsableHere() = runComposeUiTest {
        val services = newServices().also { it.secondDisplay = 2 }
        val store: FuseStore = runBlocking {
            createFuseStore(services, scope).also { s ->
                s.updatePrefs { it.copy(onboardingDone = true) }
                s.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
                withTimeout(20_000) { s.library.home.first { feed -> feed.recentlyAdded.isNotEmpty() } }
                s.settings.set(ScopedSettings.LaunchScreen, ScopeRef.platform(PlatformId("gba")), LaunchDisplay.SECONDARY)
            }
        }
        val router = InputRouter(scope)
        mainClock.autoAdvance = false
        setContent { FuseApp(store, TwoScreenPlatform, router) }

        pumpUntil { onAllNodesWithText("NEW IN YOUR LIBRARY").fetchSemanticsNodes().isNotEmpty() }
        router.tap(PadButton.DPAD_DOWN)
        pumpUntil { onAllNodesWithText("Advance Wars", substring = true).fetchSemanticsNodes().isNotEmpty() }
        router.tap(PadButton.A)
        pumpUntil { services.launched.isNotEmpty() }
        assertEquals(2, services.launchedOn.single(), "The game went to the bottom screen")

        // Fuse stays in front on this screen: the veil marks the moment and lifts, so the controller
        // works here again, instead of holding the screen for the four seconds a full launch takes.
        val launchedAt = mainClock.currentTime
        mainClock.autoAdvance = true
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Starting").fetchSemanticsNodes().isEmpty() }
        val shown = mainClock.currentTime - launchedAt
        assertTrue(shown < 2_500, "The veil stayed ${shown} ms")
    }
}

/** A handheld with a second screen that games can open on. */
private object TwoScreenPlatform : PlatformUi by TestPlatform {
    override val features = PlatformFeatures(windowModes = true, launchOnOtherDisplay = true)
}

/**
 * The interface animates forever (clock, ambient light), so the test clock never goes idle on its
 * own. This advances it by frames, while real time passes for the store's background work.
 */
@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.ComposeUiTest.pumpUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        mainClock.advanceTimeBy(64)
        if (condition()) return
        Thread.sleep(16)
    }
    val tree = runCatching { onRoot(useUnmergedTree = true).printToString(maxDepth = 60) }.getOrDefault("(no tree)")
    throw AssertionError("Condition not met within $timeoutMs ms. Screen:\n" + tree.lines().filter { "Text" in it }.joinToString("\n"))
}

private fun InputRouter.tap(button: PadButton) {
    press(button, InputSource.GAMEPAD)
    release(button, InputSource.GAMEPAD)
}

/** A plain Linux desktop with nothing optional available. */
private object TestPlatform : PlatformUi {
    override val host = Host.LINUX
    override val features = PlatformFeatures(windowModes = true)
    override val device = CapabilityProfile(
        cpuCores = 8, totalRamMb = 16_384, isLowRamDevice = false, maxRefreshRate = 60f,
        screenWidthPx = 1280, screenHeightPx = 720, densityDpi = 160, displayCount = 1,
    )
    override val status: StateFlow<SystemStatus> = MutableStateFlow(SystemStatus())
    override val displays: StateFlow<List<DisplayInfo>> = MutableStateFlow(emptyList())
    override val performance: StateFlow<List<PerformanceMetric>> = MutableStateFlow(emptyList())
    override val sounds: UiSounds = UiSounds.Silent
    override val haptics: Haptics = Haptics.None
    override val homeRole: HomeRole? = null
    override val storage = object : StorageAccess {
        override val state: StateFlow<StorageState> = MutableStateFlow(StorageState.NOT_NEEDED)
        override fun request() = Unit
        override suspend fun pickFolder(title: String): String? = null
        override suspend fun pickImage(title: String): String? = null
        override fun refresh() = Unit
    }
    override val quick = object : QuickControls {
        override val brightness: StateFlow<Float?> = MutableStateFlow(null)
        override val volume: StateFlow<Float?> = MutableStateFlow(null)
        override fun setBrightness(value: Float) = Unit
        override fun setVolume(value: Float) = Unit
        override fun openWifi() = Unit
        override fun openBluetooth() = Unit
        override fun openDisplaySettings() = Unit
        override fun openSoundSettings() = Unit
        override fun openSystemSettings() = Unit
        override fun openControllerSettings() = Unit
    }
    override val video: VideoPreview? = null
    override val appVersion = "0.0.1"
    override fun openUrl(url: String) = Unit
    override fun restart() = Unit
    override fun exit() = Unit
}
