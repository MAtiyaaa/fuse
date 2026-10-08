package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.shell.app.FuseApp
import io.github.matiyaaa.fuse.ui.shell.app.FuseAppSession
import io.github.matiyaaa.fuse.ui.shell.app.CompanionApp
import io.github.matiyaaa.fuse.ui.shell.app.ShowcaseApp
import io.github.matiyaaa.fuse.ui.shell.store.DisplaySession
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import java.lang.reflect.Proxy
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class RehearsalTest {
    @Test fun composingSandboxAndCompanionRootsCannotAccessProductionPreferencesOrPlatform() {
        var productionAccesses = 0
        val production = Proxy.newProxyInstance(FuseStore::class.java.classLoader, arrayOf(FuseStore::class.java)) { _, method, _ ->
            productionAccesses++
            error("Production store accessed by sandbox composition: ${method.name}")
        } as FuseStore
        val platform = Proxy.newProxyInstance(PlatformUi::class.java.classLoader, arrayOf(PlatformUi::class.java)) { _, method, _ ->
            productionAccesses++
            error("Production platform accessed by sandbox composition: ${method.name}")
        } as PlatformUi
        val scope = CoroutineScope(SupervisorJob())
        val display = DisplaySession().apply { rehearsalOpen = true }
        val owner = FuseAppSession(Host.LINUX, display, playerSession = null, shareWindowFocus = false)
        try {
            runDesktopComposeUiTest(1280, 1200) {
                mainClock.autoAdvance = false
                val router = InputRouter(scope)
                setContent {
                    Column {
                        Box(Modifier.fillMaxWidth().height(800.dp)) { FuseApp(production, platform, router, session = owner) }
                        Box(Modifier.fillMaxWidth().height(200.dp)) { CompanionApp(production, platform, io.github.matiyaaa.fuse.model.DualScreenMode.LIBRARY_COMPANION, displaySession = display) }
                        Box(Modifier.fillMaxWidth().height(200.dp)) { ShowcaseApp(production, platform, displaySession = display) }
                    }
                }
                repeat(8) { mainClock.advanceTimeBy(16); onRoot().captureToImage() }
                onAllNodesWithText("Onboarding rehearsal").assertCountEquals(2)
                assertEquals(0, productionAccesses)
            }
        } finally { scope.cancel() }
    }

    @Test fun startingRehearsalRemovesProductionPreferenceSubscriptions() {
        val scope = CoroutineScope(SupervisorJob())
        val baseline = RehearsalStore(scope)
        val display = DisplaySession()
        val owner = FuseAppSession(Host.LINUX, display, playerSession = null)
        var forbidden = false
        var forbiddenReads = 0
        val production = Proxy.newProxyInstance(FuseStore::class.java.classLoader, arrayOf(FuseStore::class.java)) { _, method, args ->
            when (method.name) {
                "getDisplaySession" -> display
                "getPrefs" -> {
                    if (forbidden) { forbiddenReads++; error("Production preferences read while rehearsal owns the root") }
                    baseline.prefs
                }
                else -> method.invoke(baseline, *(args ?: emptyArray()))
            }
        } as FuseStore
        try {
            runDesktopComposeUiTest(1280, 800) {
                mainClock.autoAdvance = false
                val router = InputRouter(scope)
                setContent { FuseApp(production, FakePlatformUi(Host.LINUX), router, session = owner) }
                repeat(8) { mainClock.advanceTimeBy(16); onRoot().captureToImage() }
                assertTrue(baseline.prefs.subscriptionCount.value > 0)
                owner.dev.rehearsalOpen = true
                repeat(8) { mainClock.advanceTimeBy(16); onRoot().captureToImage() }
                assertEquals(0, baseline.prefs.subscriptionCount.value)
                forbidden = true
                baseline.prefs.value = baseline.prefs.value.copy(themeId = "daylight")
                repeat(8) { mainClock.advanceTimeBy(16); onRoot().captureToImage() }
                assertEquals(0, forbiddenReads)
                forbidden = false
                display.rehearsalOpen = false
                repeat(8) { mainClock.advanceTimeBy(16); onRoot().captureToImage() }
                assertTrue(baseline.prefs.subscriptionCount.value > 0)
            }
        } finally { scope.cancel(); baseline.close() }
    }

    @Test fun fakeInstallationNeverReadsOrWritesProductionAndDiscardsTwentyProfiles() = runBlocking {
        var productionAccesses = 0
        val production = Proxy.newProxyInstance(FuseStore::class.java.classLoader, arrayOf(FuseStore::class.java)) { _, method, _ ->
            productionAccesses++
            error("Production access during rehearsal: ${method.name}")
        } as FuseStore
        val scope = CoroutineScope(SupervisorJob())
        try {
            val realApp = AppState(production, FakePlatformUi(Host.LINUX), scope, Route.Onboarding)
            realApp.dev.rehearsalOpen = true
            val fake = RehearsalStore(scope)
            assertEquals(0, productionAccesses)
            assertTrue(fake.people.connect("any fake address", "any code", null).isSuccess)
            repeat(20) { i ->
                assertTrue(fake.people.createProfile("Person $i", "cat", "1234").isSuccess)
            }
            assertEquals(20, fake.people.profiles.value.size)
            assertTrue(fake.people.switchTo(fake.people.profiles.value.last().id, "any PIN").isSuccess)
            fake.sources.add("/rehearsal/ROMs", LibrarySourceKind.ROMS_ROOT)
            fake.credentials.put("sgdb.apikey", "pretend")
            fake.updatePrefs { it.copy(onboardingDone = true) }
            assertEquals(0, productionAccesses)
            fake.close()
            assertTrue(fake.people.profiles.value.isEmpty())
            assertNull(fake.people.activeProfile.value)
            assertFalse(fake.prefs.value.onboardingDone)
            assertEquals(0, productionAccesses)
            val next = RehearsalStore(scope)
            assertTrue(next.people.profiles.value.isEmpty())
            assertTrue(next.sources.sources.value.isEmpty())
            assertTrue(next.credentials.stored.value.isEmpty())
            next.close()
        } finally { scope.cancel() }
    }

    @Test fun hostInputRedirectsToIndependentRehearsalRouterAndReturnsOnExit() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val parent = io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter(scope)
            val nested = io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter(scope)
            var realEvents = 0
            var fakeEvents = 0
            parent.register(0) { realEvents++; io.github.matiyaaa.fuse.ui.designsystem.input.NavResult.CONSUMED }
            nested.register(0) { fakeEvents++; io.github.matiyaaa.fuse.ui.designsystem.input.NavResult.CONSUMED }
            parent.redirectTo = nested
            parent.press(io.github.matiyaaa.fuse.model.PadButton.A, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
            parent.release(io.github.matiyaaa.fuse.model.PadButton.A, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
            assertEquals(0, realEvents)
            assertEquals(1, fakeEvents)
            parent.redirectTo = null
            parent.dispatch(io.github.matiyaaa.fuse.model.NavAction.BACK, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.KEYBOARD)
            assertEquals(1, realEvents)
        } finally { scope.cancel() }
    }

    @Test fun pickerSteamAndServerConnectionsAreFake() = runBlocking {
        val platform = FakePlatformUi(Host.LINUX)
        assertTrue(platform.storage.pickFolder("Games")!!.startsWith("/rehearsal/"))
        assertFalse(platform.steam!!.added())
        assertTrue(platform.steam!!.addForSetup().isSuccess)
        assertTrue(platform.steam!!.added())
        val scope = CoroutineScope(SupervisorJob())
        val fake = RehearsalStore(scope)
        try {
            assertTrue(fake.romm.usePassword("fake", "fake").isSuccess)
            val tested = fake.jellyfin.test("fake.example:8096").getOrThrow()
            fake.updatePrefs { it.copy(jellyfin = it.jellyfin.copy(enabled = true, localAddress = tested.first)) }
            assertTrue(fake.jellyfin.signIn("fake", "fake").isSuccess)
        } finally { scope.cancel(); fake.close() }
    }
}
