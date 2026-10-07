package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
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
    fun aGridScrolledUpByTouchStaysWhereTheFingerLeftIt() = runComposeUiTest(testTimeout = kotlin.time.Duration.parse("3m")) {
        repeat(80) { File(root, "gba/Quest %02d (USA).gba".format(it)).writeBytes(ByteArray(512)) }
        val services = newServices()
        val store: FuseStore = runBlocking {
            createFuseStore(services, scope).also { s ->
                s.updatePrefs { it.copy(onboardingDone = true) }
                s.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
                withTimeout(30_000) { s.library.home.first { feed -> feed.recentlyAdded.isNotEmpty() } }
            }
        }
        val router = InputRouter(scope)
        mainClock.autoAdvance = false
        setContent { FuseApp(store, TestPlatform, router) }
        pumpUntil { onAllNodesWithText("NEW IN YOUR LIBRARY").fetchSemanticsNodes().isNotEmpty() }
        router.tap(PadButton.R1)
        repeat(20) { mainClock.advanceTimeBy(64); Thread.sleep(4) }
        router.tap(PadButton.R1)
        // Advance Wars sorts first: its tile shows its initials while it has no cover.
        val first = androidx.compose.ui.test.hasText("AW")
        fun firstOnScreen(): Boolean {
            val height = onRoot().fetchSemanticsNode().size.height
            return onAllNodes(first, useUnmergedTree = true).fetchSemanticsNodes().any { it.boundsInRoot.bottom > 0f && it.boundsInRoot.top < height }
        }
        pumpUntil(30_000) { firstOnScreen() }

        // The controller walks far down the grid, so the first row scrolls away.
        repeat(14) { router.tap(PadButton.DPAD_DOWN); repeat(6) { mainClock.advanceTimeBy(64) } }
        pumpUntil { !firstOnScreen() }

        // A finger drags the grid back up to the top.
        var swipes = 0
        while (!firstOnScreen() && swipes < 12) {
            onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.85f, durationMillis = 300) }
            repeat(10) { mainClock.advanceTimeBy(64); Thread.sleep(4) }
            swipes++
        }
        assertTrue(firstOnScreen(), "The first row is back on screen after $swipes swipes")

        // Nothing throws it back down to the game the controller had chosen.
        repeat(40) { mainClock.advanceTimeBy(64); Thread.sleep(4) }
        assertTrue(firstOnScreen(), "The grid stayed where the finger left it")

        // The controller carries on from what is on screen, not from the row scrolled away.
        router.tap(PadButton.DPAD_RIGHT)
        repeat(40) { mainClock.advanceTimeBy(64); Thread.sleep(4) }
        assertTrue(firstOnScreen(), "A press after the touch scroll keeps the top of the grid in view")
    }

    @Test
    fun tabsSwitchedQuicklyMidSlideStillShowThePage() = runComposeUiTest {
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
        repeat(20) { mainClock.advanceTimeBy(64) }
        // R1 starts Systems sliding in; R1 again while it is part way is a quick switch to the Library.
        router.tap(PadButton.R1)
        mainClock.advanceTimeBy(64)
        router.tap(PadButton.R1)
        repeat(30) { mainClock.advanceTimeBy(64); Thread.sleep(4) }
        pumpUntil { onAllNodesWithText("Advance Wars", substring = true).fetchSemanticsNodes().isNotEmpty() }
        repeat(20) { mainClock.advanceTimeBy(64) }
        // The Library is really there: its game's name is drawn bright, not left faded mid-slide.
        val image = onRoot().captureToImage().toAwtImage()
        val node = onAllNodesWithText("Advance Wars", substring = true).fetchSemanticsNodes().first()
        val r = node.boundsInRoot
        var brightest = 0
        for (y in r.top.toInt().coerceAtLeast(0) until r.bottom.toInt().coerceAtMost(image.height)) {
            for (x in r.left.toInt().coerceAtLeast(0) until r.right.toInt().coerceAtMost(image.width)) {
                val rgb = image.getRGB(x, y)
                brightest = maxOf(brightest, ((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF)) / 3)
            }
        }
        assertTrue(brightest > 150, "The Library's text is drawn at brightness $brightest")
    }

    @Test
    fun aTabChosenFromHomeIsThereInTheVeryNextFrame() = runComposeUiTest {
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
        // Home has its first moments to itself; Systems and the Library are built behind it meanwhile.
        repeat(40) { mainClock.advanceTimeBy(64); Thread.sleep(4) }
        fun placed(d: io.github.matiyaaa.fuse.model.Destination) =
            onAllNodesWithTag("page.${d.name}", useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).any { it.layoutInfo.isPlaced }
        fun built(d: io.github.matiyaaa.fuse.model.Destination) =
            onAllNodesWithTag("page.${d.name}", useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        assertTrue(placed(io.github.matiyaaa.fuse.model.Destination.HOME))
        assertTrue(!placed(io.github.matiyaaa.fuse.model.Destination.SYSTEMS), "Systems isn't drawn over Home")
        // One press, one frame: Systems is on screen, and Home is no longer drawn under it.
        router.tap(PadButton.R1)
        mainClock.advanceTimeBy(16)
        assertTrue(placed(io.github.matiyaaa.fuse.model.Destination.SYSTEMS), "Systems is there the frame after the press")
        assertTrue(!placed(io.github.matiyaaa.fuse.model.Destination.HOME), "Home isn't drawn under it")
        // The Library was built ahead too, though nobody has opened it yet.
        assertTrue(built(io.github.matiyaaa.fuse.model.Destination.LIBRARY), "the Library is ready before its first visit")
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

    @Test
    fun setupMakesTheFirstProfileAndComesBackToTheStepItLeft() = runComposeUiTest {
        lateinit var services: FakeServices
        services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse-${System.nanoTime()}.db").absolutePath)), cache, sync = { port, sc ->
            io.github.matiyaaa.fuse.sync.JvmSyncService(
                File(cache, "sync"), services.data.settings, services.secrets, port, "LINUX", "Steam Deck", "test",
                io.github.matiyaaa.fuse.sync.NoHostLifetime("test"), sc,
            )
        })
        val store = runBlocking { createFuseStore(services, scope) }
        val svc = assertNotNullOf(store.sync.service)
        val router = InputRouter(scope)
        mainClock.autoAdvance = false
        val app = AppState(store, TestPlatform, scope, Route.Onboarding)
        setContent {
            io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme {
                androidx.compose.runtime.CompositionLocalProvider(io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter provides router) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(1280.dp, 720.dp)) {
                        if (app.navigator.current == Route.Onboarding) io.github.matiyaaa.fuse.ui.shell.onboarding.OnboardingScreen(app)
                        else androidx.compose.foundation.text.BasicText("Somewhere else")
                        io.github.matiyaaa.fuse.ui.shell.sync.WhoAreYouOverlay(app)
                        io.github.matiyaaa.fuse.ui.shell.sync.ProfileArrival(app)
                    }
                }
            }
        }
        fun shows(text: String) = onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        pumpUntil { shows("Welcome to Fuse") }
        router.tap(PadButton.A)
        pumpUntil { shows("Tuned for this device") }
        router.tap(PadButton.A)
        // Fuse Sync comes before Who's playing, so a household's profiles are there to choose from.
        pumpUntil { shows("Play on, anywhere") }
        onAllNodesWithText("Skip", useUnmergedTree = true).onFirst().performClick()
        // Who's playing: no host needed, and skipping it is fine.
        pumpUntil { shows("Make your profile") }
        router.tap(PadButton.A)
        pumpUntil { shows("New Profile") }
        mainClock.advanceTimeBy(600)
        router.tap(PadButton.A)
        mainClock.advanceTimeBy(120)
        assertEquals("Profile name", app.textInput?.title)
        app.textInput!!.onDone("Mo")
        app.textInput = null
        mainClock.advanceTimeBy(200)
        // From the PIN, down into the pictures, then A goes to Create Profile and A makes it.
        router.tap(PadButton.DPAD_DOWN)
        mainClock.advanceTimeBy(120)
        router.tap(PadButton.A)
        mainClock.advanceTimeBy(120)
        router.tap(PadButton.A)
        mainClock.advanceTimeBy(120)
        router.tap(PadButton.A)
        // The first profile here gets the grand welcome, and the step greets them.
        pumpUntil { app.profileArrival != null }
        assertTrue(app.arrivalGrand, "the first profile's arrival is the grand one")
        pumpUntil { app.profileArrival == null && shows("Hi, Mo") }
        assertEquals(listOf("Mo"), svc.profiles.value.map { it.name })
        assertTrue(shows("Add Another"))
        // Off to set up Fuse Sync (a page of its own) and back: the same step, not the beginning.
        app.go(Route.SyncSetup(host = true))
        pumpUntil { shows("Somewhere else") }
        app.back()
        pumpUntil { shows("Hi, Mo") }
        assertTrue(!shows("Welcome to Fuse"))
        // Setup opened afresh starts at the beginning.
        app.go(Route.Onboarding)
        pumpUntil { shows("Welcome to Fuse") }
    }

    private fun <T : Any> assertNotNullOf(v: T?): T = kotlin.test.assertNotNull(v)

    @Test
    fun theSyncTabShowsAtLeastFourGamesAtOnceEvenOnTheThorsUpperScreen() = runComposeUiTest {
        val data = FuseData(DesktopDatabase.open(File(cache, "fuse-${System.nanoTime()}.db").absolutePath))
        val sync = io.github.matiyaaa.fuse.ui.shell.audit.AuditSync(data.settings).also { it.household(asHost = true) }
        val store = runBlocking { createFuseStore(FakeServices(data, cache, sync = { _, _ -> sync }), scope) }
        val router = InputRouter(scope)
        mainClock.autoAdvance = false
        val app = AppState(store, TestPlatform, scope, Route.Root(io.github.matiyaaa.fuse.model.Destination.CARTRIDGE))
        var size by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.DpSize(640.dp, 360.dp))
        setContent {
            io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme {
                androidx.compose.runtime.CompositionLocalProvider(io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter provides router) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(size).testTag("window")) {
                        // Below the top line and the folded tabs, as Addons places it.
                        io.github.matiyaaa.fuse.ui.shell.sync.SyncTab(app, active = true, topPadding = 120.dp)
                    }
                }
            }
        }
        pumpUntil { onAllNodesWithTag("sync.game", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // The AYN Thor's upper screen at its largest text, a 6 inch handheld and a 1080p one: once
        // in the games, at least four whole rows are in view.
        for (s in listOf(androidx.compose.ui.unit.DpSize(640.dp, 360.dp), androidx.compose.ui.unit.DpSize(853.dp, 480.dp), androidx.compose.ui.unit.DpSize(1280.dp, 720.dp))) {
            size = s
            mainClock.advanceTimeBy(600)
            router.tap(PadButton.DPAD_DOWN)
            mainClock.advanceTimeBy(1_200)
            val window = onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
            val top = window.top + with(density) { 120.dp.toPx() }
            val whole = onAllNodesWithTag("sync.game", useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.boundsInRoot }.count { it.top >= top - 0.5f && it.bottom <= window.bottom + 0.5f }
            assertTrue(whole >= 4, "only $whole whole games in view at $s")
            router.tap(PadButton.DPAD_UP)
            mainClock.advanceTimeBy(1_200)
        }
    }

    @Test
    fun theProfileEditorFitsTheThorsScreensAndTheDpadReachesEveryPart() = runComposeUiTest {
        val store = runBlocking { createFuseStore(newServices(), scope) }
        val router = InputRouter(scope)
        mainClock.autoAdvance = false
        val app = AppState(store, TestPlatform, scope, Route.Root(io.github.matiyaaa.fuse.model.Destination.HOME))
        var size by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.DpSize(640.dp, 360.dp))
        var made: Triple<String, String, io.github.matiyaaa.fuse.ui.shell.sync.PinChoice>? = null
        var backs = 0
        setContent {
            io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme {
                androidx.compose.runtime.CompositionLocalProvider(io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter provides router) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(size).testTag("window")) {
                        io.github.matiyaaa.fuse.ui.shell.sync.ProfileEditor(
                            app, "New Profile", "Everyone gets their own saves.", "Create Profile", io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.UserPlus,
                            onSubmit = { n, a, p -> made = Triple(n, a, p); null },
                            onBack = { backs++ },
                        )
                    }
                }
            }
        }
        // The AYN Thor's upper screen at its largest text, its lower screen, and a 4:3 handheld: the
        // buttons are always wholly on screen.
        for (s in listOf(androidx.compose.ui.unit.DpSize(640.dp, 360.dp), androidx.compose.ui.unit.DpSize(496.dp, 432.dp), androidx.compose.ui.unit.DpSize(427.dp, 320.dp))) {
            size = s
            mainClock.advanceTimeBy(600)
            val window = onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
            val buttons = onNodeWithTag("editor.buttons", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(buttons.top >= window.top && buttons.bottom <= window.bottom + 0.5f && buttons.left >= window.left && buttons.right <= window.right + 0.5f, "buttons $buttons outside $window at $s")
        }
        fun press(b: PadButton) { router.tap(b); mainClock.advanceTimeBy(120) }
        // Name first: A asks for it, and typing it moves on to the PIN.
        press(PadButton.A)
        assertEquals("Profile name", app.textInput?.title)
        app.textInput!!.onDone("Mo")
        app.textInput = null
        mainClock.advanceTimeBy(200)
        // The PIN is next, right below the name (A asks for one; closing the keyboard keeps the D-pad).
        press(PadButton.A)
        assertTrue(app.textInput?.title?.startsWith("A PIN for Mo") == true)
        app.textInput = null
        mainClock.advanceTimeBy(200)
        // Down into the pictures (no tap needed), along them, then down to the buttons.
        press(PadButton.DPAD_DOWN)
        // The pictures are one row on this short screen: Left all the way reaches the first.
        repeat(io.github.matiyaaa.fuse.ui.designsystem.components.FuseAvatars.all.size) { press(PadButton.DPAD_LEFT) }
        press(PadButton.DPAD_RIGHT)
        press(PadButton.DPAD_RIGHT)
        press(PadButton.A)
        // On the buttons: Left is Cancel, Right back to Create, A makes the profile.
        press(PadButton.DPAD_LEFT)
        press(PadButton.DPAD_RIGHT)
        press(PadButton.A)
        pumpUntil { made != null }
        assertEquals("Mo", made!!.first)
        assertEquals(io.github.matiyaaa.fuse.ui.designsystem.components.FuseAvatars.all[2].id, made!!.second)
        // B leaves, from anywhere.
        press(PadButton.B)
        assertEquals(1, backs)
    }

}

/** A handheld with a second screen that games can open on. */
private object TwoScreenPlatform : PlatformUi by TestPlatform {
    override val features = PlatformFeatures(windowModes = true, launchOnOtherDisplay = true)

}

/**
 * The interface animates forever (clock, ambient light), so the test clock never goes idle on its
 * own. This advances it by frames, while real time passes for the store's background work.
 *
 * The wait lasts until both [timeoutMs] of the interface's own time (the test clock) and [timeoutMs]
 * of real time have passed: what the interface plays out (a page changing, the profile arrival)
 * takes its own time whatever the machine, and a slow runner only takes longer in real time to get
 * there, while the store's background work still gets its real time. Real time is capped
 * separately, and generously, only to stop a test that is truly stuck.
 */
@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.ComposeUiTest.pumpUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val until = mainClock.currentTime + timeoutMs
    val realEnd = System.currentTimeMillis() + timeoutMs
    val stuck = System.currentTimeMillis() + REAL_TIME_CAP_MS
    while ((mainClock.currentTime < until || System.currentTimeMillis() < realEnd) && System.currentTimeMillis() < stuck) {
        mainClock.advanceTimeBy(64)
        if (condition()) return
        Thread.sleep(16)
    }
    val tree = runCatching { onRoot(useUnmergedTree = true).printToString(maxDepth = 60) }.getOrDefault("(no tree)")
    throw AssertionError("Condition not met within $timeoutMs ms. Screen:\n" + tree.lines().filter { "Text" in it }.joinToString("\n"))
}

/** The most real time a wait may take, whatever the machine: only a test that is truly stuck reaches it. */
private const val REAL_TIME_CAP_MS = 120_000L

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
