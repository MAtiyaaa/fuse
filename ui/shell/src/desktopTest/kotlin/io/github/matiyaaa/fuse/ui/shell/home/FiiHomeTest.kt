package io.github.matiyaaa.fuse.ui.shell.home

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.BoardLook
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.HomeStyle
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.TestPlatform
import io.github.matiyaaa.fuse.ui.shell.store.FakeServices
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fii's Home: picking a Fii theme unlocks it and asks whether to use it, leaving the Fii themes on it
 * asks whether to go back, every switch of Home style is asked, and the board's look round-trips.
 */
class FiiHomeTest {
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope
    private lateinit var app: AppState

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-fii").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val store = runBlocking { createFuseStore(services, scope) }
        store.updatePrefs { it.copy(onboardingDone = true, home = it.home.withStyle(HomeStyle.FUSED)) }
        app = AppState(store, TestPlatform, scope, Route.Root(io.github.matiyaaa.fuse.model.Destination.HOME))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cache.deleteRecursively()
    }

    private val prefs get() = app.store.prefs.value
    private fun pick(id: String) = app.choice!!.options.first { it.id == id }.onSelect()

    @Test
    fun pickingFiiUnlocksItsHomeAndAsksBeforeUsingIt() {
        assertEquals(listOf(HomeStyle.FUSED, HomeStyle.NETWORK), prefs.homeStyles(), "Fii's Home is hidden until unlocked")
        app.useTheme(ThemePresets.Fii)
        assertEquals("fii", prefs.themeId)
        assertTrue(FII_UNLOCK in prefs.unlocked && FII_UNLOCK in prefs.unlockedNew)
        assertEquals(HomeStyle.FUSED, prefs.home.style, "nothing changes before the answer")
        assertEquals("Fii has its own Home", app.choice?.title)
        pick("themehome.use")
        assertNull(app.choice)
        assertEquals(HomeStyle.FII, prefs.home.style)
        assertEquals(HomeMode.CHANNELS, prefs.home.mode, "a Fuse without Fii's look still shows the board")
        assertEquals(HomeStyle.FUSED, prefs.home.beforeFii)
        assertFalse(FII_UNLOCK in prefs.unlockedNew, "looked at once used")
    }

    @Test
    fun keepingMyHomeStillOffersFiisMarkedNew() {
        app.useTheme(ThemePresets.Fii)
        pick("themehome.keep")
        assertEquals(HomeStyle.FUSED, prefs.home.style)
        app.chooseHomeStyle()
        val fii = app.choice!!.options.first { it.id == "homestyle.fii" }
        assertEquals(Trailing.Badge("Unlocked"), fii.trailing)
        fii.onSelect()
        assertEquals(HomeStyle.FII, prefs.home.style)
        assertTrue(prefs.unlockedNew.isEmpty())
    }

    @Test
    fun fiiAndFiiNightAskNothingBetweenThem() {
        app.useTheme(ThemePresets.Fii)
        pick("themehome.use")
        app.useTheme(ThemePresets.FiiNight)
        assertNull(app.choice)
        assertEquals(HomeStyle.FII, prefs.home.style)
    }

    @Test
    fun leavingFiiOnItsHomeAsksToGoBack() {
        app.store.updatePrefs { it.copy(home = it.home.withStyle(HomeStyle.NETWORK)) }
        app.useTheme(ThemePresets.Fii)
        pick("themehome.use")
        app.useTheme(ThemePresets.Fuse)
        assertEquals("Go back to the Home you had?", app.choice?.title)
        pick("themehome.back")
        assertEquals(HomeStyle.NETWORK, prefs.home.style)
        assertTrue(FII_UNLOCK in prefs.unlocked, "Fii's Home stays unlocked")
        assertTrue(HomeStyle.FII in prefs.homeStyles())
    }

    @Test
    fun keepingFiisHomeOnAnotherThemeIsAllowed() {
        app.useTheme(ThemePresets.Fii)
        pick("themehome.use")
        app.useTheme(ThemePresets.Fuse)
        pick("themehome.stay")
        assertEquals("fuse", prefs.themeId)
        assertEquals(HomeStyle.FII, prefs.home.style)
    }

    @Test
    fun theLookRoundTripsAndOlderSettingsReadAsFuse() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val fii = HomeLayoutConfig(mode = HomeMode.CHANNELS).withStyle(HomeStyle.FII)
        assertEquals(fii, json.decodeFromString<HomeLayoutConfig>(json.encodeToString(HomeLayoutConfig.serializer(), fii)))
        val older = json.decodeFromString<HomeLayoutConfig>("""{"mode":"CHANNELS"}""")
        assertEquals(BoardLook.FUSE, older.look)
        assertEquals(HomeStyle.FUSED, older.style)
        assertEquals(HomeStyle.NETWORK, fii.withStyle(HomeStyle.NETWORK).style)
        assertEquals(BoardLook.FUSE, fii.withStyle(HomeStyle.NETWORK).look)
    }

    @Test
    fun duoAndTrioEachUnlockTheirOwnHome() {
        app.useTheme(ThemePresets.Duo)
        assertEquals("Duo has its own Home", app.choice?.title)
        pick("themehome.use")
        assertEquals(HomeStyle.DUO, prefs.home.style)
        assertEquals(BoardLook.DUO, prefs.home.look)
        assertTrue(DUO_UNLOCK in prefs.unlocked)
        // Duo Night asks nothing; Trio offers its own Home, and keeps Duo's plain Home to go back to.
        app.useTheme(ThemePresets.DuoNight)
        assertNull(app.choice)
        app.useTheme(ThemePresets.TrioNight)
        assertEquals("Trio has its own Home", app.choice?.title)
        pick("themehome.use")
        assertEquals(HomeStyle.TRIO, prefs.home.style)
        assertEquals(HomeStyle.FUSED, prefs.home.beforeFii, "the plain Home from before the themed ones")
        assertEquals(listOf(HomeStyle.FUSED, HomeStyle.NETWORK, HomeStyle.DUO, HomeStyle.TRIO), prefs.homeStyles())
        app.useTheme(ThemePresets.Fuse)
        pick("themehome.back")
        assertEquals(HomeStyle.FUSED, prefs.home.style)
    }

    @Test
    fun everyThemedHomeRoundTrips() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        for (style in listOf(HomeStyle.DUO, HomeStyle.TRIO)) {
            val config = HomeLayoutConfig(mode = HomeMode.CHANNELS).withStyle(style)
            assertEquals(style, json.decodeFromString<HomeLayoutConfig>(json.encodeToString(HomeLayoutConfig.serializer(), config)).style)
        }
    }
}
