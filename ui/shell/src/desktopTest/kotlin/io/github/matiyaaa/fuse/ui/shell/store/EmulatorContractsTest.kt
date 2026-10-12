package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.launch.AdapterRegistry
import io.github.matiyaaa.fuse.launch.RetroArchCores
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.sync.SaveAdapters
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every emulator in every catalog, and every RetroArch core, has a row that says what Fuse does
 * with it, and docs/emulator-contracts.md is that table. To write the file again after a change:
 * `FUSE_WRITE_CONTRACTS=1 ./gradlew :ui:shell:desktopTest --tests '*EmulatorContracts*'`.
 */
class EmulatorContractsTest {
    private val doc = File("../../docs/emulator-contracts.md")

    @Test
    fun everyEmulatorInEveryCatalogHasARow() {
        val ids = EmulatorContracts.rows.map { it.id }.toSet()
        for (a in AdapterRegistry.Default.adapters) {
            val fam = EmulatorContracts.family(a.id.value)
            if (fam in setOf("steam", "steam-url", "desktop", "shortcut", "open", "script", "anyps5")) continue
            assertTrue(a.id.value in ids, "${a.id.value} has no row")
        }
        // All four systems' catalogs are there.
        assertEquals(setOf(Host.ANDROID, Host.LINUX, Host.WINDOWS, Host.MACOS), EmulatorContracts.rows.map { it.host }.toSet())
    }

    @Test
    fun anEmulatorWhoseSavesDontSyncSaysWhy() {
        val generic = SaveAdapters.whyNot("no-such-emulator")
        val unexplained = EmulatorContracts.rows.filter { it.saves.level == EmulatorContracts.Level.NOT_SUPPORTED && it.saves.detail == generic }.map { it.id }
        assertEquals(emptyList(), unexplained, "emulators without a save adapter and without a reason")
    }

    @Test
    fun everyRetroArchCoreHasARow() {
        val cores = EmulatorContracts.cores.map { it.core }.toSet()
        for (host in listOf(Host.ANDROID, Host.LINUX, Host.WINDOWS, Host.MACOS)) for (p in PlatformCatalog.all) {
            for (core in RetroArchCores.coresFor(host, p.id)) assertTrue(core in cores, "$core has no row")
        }
    }

    @Test
    fun theDocumentIsTheTable() {
        val table = EmulatorContracts.markdown()
        if (System.getenv("FUSE_WRITE_CONTRACTS") == "1") doc.writeText(table)
        assertEquals(table, doc.takeIf { it.isFile }?.readText(), "docs/emulator-contracts.md is out of date; write it again (see this test's comment)")
    }
}
