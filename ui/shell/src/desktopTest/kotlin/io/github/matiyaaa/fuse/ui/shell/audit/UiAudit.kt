package io.github.matiyaaa.fuse.ui.shell.audit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files

/**
 * The UI audit: every screen, overlay and state of the real interface, rendered headless over the
 * real store and driven with controller presses, at the sizes Fuse runs at (see [AuditSize]).
 * Everything at M (1280 x 720 dp); the key screens at the other sizes; the companion screen at M
 * and on a dual-screen handheld's second screen. Each PNG is listed in `<dir>/manifest.json` with
 * its group, screen, state and size, next to what could not be covered and why.
 *
 * Not part of the normal test run. It only runs when the `fuse.audit.dir` system property names an
 * output folder, which the `desktopAudit` Gradle task sets:
 *
 *     ./gradlew :ui:shell:desktopAudit -Pfuse.audit.dir=/tmp/audit [-Pfuse.audit.only=home,library/all] [-Pfuse.audit.sizes=M,H]
 */
@OptIn(ExperimentalTestApi::class)
class UiAudit {
    private lateinit var root: File
    private lateinit var sd: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        assumeTrue("Set -D${Audit.DIR_PROPERTY}=<folder> (or run :ui:shell:desktopAudit) to render the audit", Audit.dir != null)
        Audit.dir!!.mkdirs()
        // Fixed, readable folder names: the game page and onboarding show them.
        val tmp = File(System.getProperty("java.io.tmpdir"))
        root = File(tmp, "fuse-audit-library").apply { deleteRecursively(); mkdirs() }
        sd = File(tmp, "fuse-audit-sdcard").apply { deleteRecursively(); mkdirs() }
        cache = Files.createTempDirectory("fuse-audit-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        if (Audit.dir == null) return
        scope.cancel()
        root.deleteRecursively()
        sd.deleteRecursively()
        cache.deleteRecursively()
    }

    private fun audit(size: AuditSize, block: AuditDriver.() -> Unit) {
        assumeTrue("Size ${size.name} is not in -D${Audit.SIZES_PROPERTY}", Audit.sizeEnabled(size))
        runDesktopComposeUiTest(size.widthPx, size.heightPx) {
            val router = InputRouter(scope)
            mainClock.autoAdvance = false
            val driver = AuditDriver(this, size, router, root, cache, sd, scope)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(size.density)) { driver.Content() }
            }
            driver.settle(300)
            driver.block()
        }
    }

    @Test fun m01Home() = audit(AuditSize.M) { homeFlow(exhaustive = true); homeChannels(exhaustive = true); homeEmpty() }

    @Test fun m02Library() = audit(AuditSize.M) { libraryScreens(exhaustive = true) }

    @Test fun m03Systems() = audit(AuditSize.M) { systemsScreens(exhaustive = true) }

    @Test fun m04Game() = audit(AuditSize.M) { gameScreens(exhaustive = true); contentScreens() }

    @Test fun m05Launch() = audit(AuditSize.M) { launchScreens() }

    @Test fun m06Search() = audit(AuditSize.M) { searchScreens(exhaustive = true) }

    @Test fun m07AppsAndCartridge() = audit(AuditSize.M) { appsScreens(exhaustive = true); cartridgeScreens(exhaustive = true) }

    @Test fun m08Overlays() = audit(AuditSize.M) { overlayScreens(exhaustive = true) }

    @Test fun m09Settings() = audit(AuditSize.M) { settingsScreens(exhaustive = true) }

    @Test fun m10Onboarding() = audit(AuditSize.M) { onboardingScreens(exhaustive = true) }

    @Test fun m11Looks() = audit(AuditSize.M) { lookScreens() }

    @Test fun m12Companion() = audit(AuditSize.M) { companionScreens() }

    @Test fun m13Everything() = audit(AuditSize.M) { everythingScreens() }

    @Test fun m14Addons() = audit(AuditSize.M) { addonsScreens() }

    /** Every widget at every size, at the Deck's size: the same layout in dp for far fewer pixels. */
    @Test fun widgetGallery() = audit(AuditSize.D) { widgetGallery() }

    @Test fun widgetGalleryM() = audit(AuditSize.M) { widgetGallery() }

    @Test fun companionSecondScreen() = audit(AuditSize.C) { companionScreens() }

    @Test fun keyScreensH() = audit(AuditSize.H) { keyScreens() }

    /** Addons at a 6 inch handheld's size, where the Store's pages are shortest. */
    @Test fun addonsH() = audit(AuditSize.H) { addonsScreens() }

    @Test fun keyScreensD() = audit(AuditSize.D) { keyScreens() }

    @Test fun keyScreensP() = audit(AuditSize.P) { keyScreens() }

    @Test fun keyScreensV() = audit(AuditSize.V) { keyScreens() }

    @Test fun keyScreensT() = audit(AuditSize.T) { keyScreens() }

    @Test fun keyScreensU() = audit(AuditSize.U) { keyScreens() }

    @Test fun keyScreensS() = audit(AuditSize.S) { keyScreens() }

    @Test fun keyScreensQ() = audit(AuditSize.Q) { keyScreens() }

    @Test fun keyScreensO() = audit(AuditSize.O) { keyScreens() }

    @Test fun keyScreensG() = audit(AuditSize.G) { keyScreens() }

    @Test fun keyScreensE() = audit(AuditSize.E) { keyScreens() }

    @Test fun keyScreensK() = audit(AuditSize.K) { keyScreens() }

    @Test fun keyScreensA() = audit(AuditSize.A) { keyScreens() }

    @Test fun keyScreensB() = audit(AuditSize.B) { keyScreens() }

    @Test fun keyScreensY() = audit(AuditSize.Y) { keyScreens() }

    @Test fun keyScreensW() = audit(AuditSize.W) { keyScreens() }

    @Test fun keyScreensZ() = audit(AuditSize.Z) { keyScreens() }

    @Test fun keyScreensX() = audit(AuditSize.X) { keyScreens() }

    @Test fun keyScreensR() = audit(AuditSize.R) { keyScreens() }

    @Test fun keyScreensF() = audit(AuditSize.F) { keyScreens() }

    /** Every setup step at the sizes where it is tightest: a phone held sideways, the Deck and a 6 inch handheld. */
    @Test fun onboardingP() = audit(AuditSize.P) { onboardingScreens(exhaustive = true) }

    @Test fun onboardingD() = audit(AuditSize.D) { onboardingScreens(exhaustive = true) }

    @Test fun onboardingH() = audit(AuditSize.H) { onboardingScreens(exhaustive = true) }

    /** What 0.2.6 added, at 1080p and on a phone held sideways. */
    @Test fun m15Details() = audit(AuditSize.M) { detailScreens() }

    @Test fun detailsP() = audit(AuditSize.P) { detailScreens() }

    /** What 0.2.7 changed, at 1080p, on a 6 inch handheld, and on a second screen. */
    @Test fun m16Swap() = audit(AuditSize.M) { swapScreens() }

    @Test fun swapH() = audit(AuditSize.H) { swapScreens() }

    @Test fun swapC() = audit(AuditSize.C) { swapCompanion(); flippedMenus() }

    @Test fun flippedH() = audit(AuditSize.H) { flippedShowcase() }

    @Test fun lowerScreenL() = audit(AuditSize.L) { flippedMenus(); jellyfinDualScreens() }

    @Test fun companionL() = audit(AuditSize.L) { companionScreens() }

    @Test fun quickM() = audit(AuditSize.M) { quickScreens() }

    @Test fun quickH() = audit(AuditSize.H) { quickScreens() }

    @Test fun edgeBackM() = audit(AuditSize.M) { edgeBackScreens() }

    /** Fuse Sync by Fuse: at 1080p, on a 6 inch handheld, a phone held upright, and a small screen. */
    @Test fun syncM() = audit(AuditSize.M) { syncScreens() }

    @Test fun syncthingM() = audit(AuditSize.M) { syncthingScreens() }

    @Test fun saveFoldersM() = audit(AuditSize.M) { saveFolderScreens() }

    @Test fun joinM() = audit(AuditSize.M) { joinScreens() }

    @Test fun joinH() = audit(AuditSize.H) { joinScreens() }

    @Test fun saveFoldersH() = audit(AuditSize.H) { saveFolderScreens() }

    @Test fun syncthingH() = audit(AuditSize.H) { syncthingScreens() }

    @Test fun syncH() = audit(AuditSize.H) { syncScreens() }

    @Test fun syncV() = audit(AuditSize.V) { syncScreens() }

    @Test fun syncS() = audit(AuditSize.S) { syncScreens() }

    /** Who's playing and the profile editor on both of the AYN Thor's screens. */
    @Test fun syncJ() = audit(AuditSize.J) { syncScreens() }

    @Test fun syncL() = audit(AuditSize.L) { syncScreens() }

    @Test fun syncthingS() = audit(AuditSize.S) { syncthingScreens() }

    @Test fun homePagesM() = audit(AuditSize.M) { homePages() }

    @Test fun homePagesH() = audit(AuditSize.H) { homePages() }

    @Test fun flippedM() = audit(AuditSize.M) { flippedShowcase() }

    @Test fun openingM() = audit(AuditSize.M) { setupOpening() }

    @Test fun openingP() = audit(AuditSize.P) { setupOpening(); welcomeMark() }

    /** What 0.2.8 changed, at 1080p, on a 6 inch handheld, on a phone held upright and on a second screen. */
    @Test fun m17Media() = audit(AuditSize.M) { mediaScreens() }

    @Test fun mediaH() = audit(AuditSize.H) { mediaScreens() }

    @Test fun mediaV() = audit(AuditSize.V) { mediaScreens() }

    @Test fun phoneTypingM() = audit(AuditSize.M) { phoneTypingScreens() }

    @Test fun phoneTypingH() = audit(AuditSize.H) { phoneTypingScreens() }

    @Test fun phoneTypingV() = audit(AuditSize.V) { phoneTypingScreens() }

    @Test fun jellyfinM() = audit(AuditSize.M) { jellyfinScreens() }

    @Test fun jellyfinH() = audit(AuditSize.H) { jellyfinScreens() }

    @Test fun jellyfinV() = audit(AuditSize.V) { jellyfinScreens() }

    @Test fun jellyfinTwoScreensC() = audit(AuditSize.C) { jellyfinDualScreens() }

    @Test fun jellyfinTwoScreensH() = audit(AuditSize.H) { jellyfinDualScreens() }

    @Test fun storageM() = audit(AuditSize.M) { storageScreens() }

    @Test fun storageH() = audit(AuditSize.H) { storageScreens() }

    @Test fun storageV() = audit(AuditSize.V) { storageScreens() }

    @Test fun storageD() = audit(AuditSize.D) { storageScreens() }

    @Test fun storageT() = audit(AuditSize.T) { storageScreens() }

    @Test fun tabSwitchH() = audit(AuditSize.H) { tabSwitchCost(java.io.File(System.getProperty("fuse.audit.dir", "build/audit"), "tab-switch.txt")) }
}
