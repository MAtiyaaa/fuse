package io.github.matiyaaa.fuse.ui.shell.audit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue

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

    @Test fun m04Game() = audit(AuditSize.M) { gameScreens(exhaustive = true) }

    @Test fun m05Launch() = audit(AuditSize.M) { launchScreens() }

    @Test fun m06Search() = audit(AuditSize.M) { searchScreens(exhaustive = true) }

    @Test fun m07AppsAndCartridge() = audit(AuditSize.M) { appsScreens(exhaustive = true); cartridgeScreens(exhaustive = true) }

    @Test fun m08Overlays() = audit(AuditSize.M) { overlayScreens(exhaustive = true) }

    @Test fun m09Settings() = audit(AuditSize.M) { settingsScreens(exhaustive = true) }

    @Test fun m10Onboarding() = audit(AuditSize.M) { onboardingScreens(exhaustive = true) }

    @Test fun m11Looks() = audit(AuditSize.M) { lookScreens() }

    @Test fun m12Companion() = audit(AuditSize.M) { companionScreens() }

    @Test fun m13Everything() = audit(AuditSize.M) { everythingScreens() }

    /** Every widget at every size, at the Deck's size: the same layout in dp for far fewer pixels. */
    @Test fun widgetGallery() = audit(AuditSize.D) { widgetGallery() }

    @Test fun companionSecondScreen() = audit(AuditSize.C) { companionScreens() }

    @Test fun keyScreensH() = audit(AuditSize.H) { keyScreens() }

    @Test fun keyScreensD() = audit(AuditSize.D) { keyScreens() }

    @Test fun keyScreensP() = audit(AuditSize.P) { keyScreens() }

    @Test fun keyScreensV() = audit(AuditSize.V) { keyScreens() }

    @Test fun keyScreensT() = audit(AuditSize.T) { keyScreens() }
}
