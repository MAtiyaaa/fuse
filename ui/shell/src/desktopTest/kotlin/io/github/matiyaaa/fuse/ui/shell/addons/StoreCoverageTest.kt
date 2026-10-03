package io.github.matiyaaa.fuse.ui.shell.addons

import io.github.matiyaaa.fuse.integrations.obtainium.PackDocument
import io.github.matiyaaa.fuse.launch.android.AndroidEmulatorCatalog
import io.github.matiyaaa.fuse.model.AppKind
import io.github.matiyaaa.fuse.model.KnownApps
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every app in both editions of the Store's pack is something Fuse knows: an emulator in its launch
 * catalog, or a streaming app or tool it lists in Apps. Only "Track Only" entries (the pack itself,
 * driver lists) are left out. A new pack release that adds an app fails this until Fuse knows it.
 */
class StoreCoverageTest {
    private val packages = AndroidEmulatorCatalog.allPackages().toSet()
    private val names = AndroidEmulatorCatalog.defs.map { it.name }

    @Test
    fun everyStoreAppIsAnEmulatorOrAnApp() {
        for (edition in listOf("pack-standard.json", "pack-dual-screen.json")) {
            val pack = PackDocument.parse(File("../../core/integrations/src/desktopTest/resources/obtainium/$edition").readText()).getOrThrow()
            val unknown = pack.apps
                .filterNot { it.rules.trackOnly }
                .filter { app ->
                    val pkg = app.packageName ?: app.id
                    // Apps listed without a package (RetroArch) are matched by name, as the Store does.
                    val base = app.name.substringBefore(" (").trim()
                    pkg !in packages && KnownApps.kindOf(pkg) == null && names.none { it.equals(base, ignoreCase = true) }
                }
                .map { "${it.name} (${it.packageName ?: it.id})" }
            assertEquals(emptyList(), unknown, "$edition: apps Fuse doesn't know")
        }
    }

    @Test
    fun streamingAppsAreStreaming() {
        assertEquals(AppKind.STREAMING, KnownApps.kindOf("com.limelight"))
        assertEquals(AppKind.STREAMING, KnownApps.kindOf("com.limelight.noir"))
    }
}
