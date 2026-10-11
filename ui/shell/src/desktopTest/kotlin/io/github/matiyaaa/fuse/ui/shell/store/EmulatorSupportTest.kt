package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Developer options' Emulator support: every emulator Fuse knows here, and a report to share. */
class EmulatorSupportTest {
    private val cache: File = Files.createTempDirectory("fuse-support").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cache.deleteRecursively()
    }

    @Test
    fun installedEmulatorsComeFirstWithWhatFuseDoesWithThem(): Unit = runBlocking {
        val store = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache), scope)
        store.emulators.refresh()
        withTimeout(20_000) { store.emulators.installed.first { it.size == 2 } }
        val rows = store.emulators.support()
        assertTrue(rows.size > 2, "every emulator Fuse knows for Linux")
        val installed = rows.takeWhile { it.installed }
        assertEquals(setOf("DuckStation", "mGBA"), installed.map { it.name }.toSet())
        val duck = rows.first { it.name == "DuckStation" }
        assertTrue(duck.features.containsAll(listOf("starts games", "saves", "patches", "cheats", "settings")), duck.features.toString())
        assertEquals("/usr/bin/duckstation-qt", duck.path)
        assertFalse(rows.drop(installed.size).any { it.installed })

        val report = store.emulators.supportReport()
        assertTrue(report.startsWith("Fuse emulator support report"))
        assertTrue("## Installed" in report && "## Known, not installed" in report)
        assertTrue("DuckStation [linux.duckstation]: installed, via PATH" in report, report.lines().take(8).joinToString("\n"))
    }
}
