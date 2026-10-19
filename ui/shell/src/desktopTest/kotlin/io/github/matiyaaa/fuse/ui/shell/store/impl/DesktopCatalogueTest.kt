package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.launch.linux.LinuxCatalog
import io.github.matiyaaa.fuse.model.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every emulator Fuse knows on Linux is in the Store, installed by Fuse, unless it says why not. */
class DesktopCatalogueTest {
    /** Not emulators the Store could offer, or with no build anyone can install today. */
    private val leftOut = mapOf(
        "retroarch-steam" to "RetroArch from Steam: Steam installs it",
        "steam" to "Steam itself",
        "steam-url" to "Steam links",
        "script" to "the person's own scripts",
        "lime3ds" to "ended; Azahar carries it on",
        "citra" to "ended; Azahar carries it on",
        "mupen64plus" to "m64p has no build for Linux any more, and no Flathub app",
        "pico8" to "sold by Lexaloffle, not free to fetch",
    )

    private val linuxEntries = DesktopCatalogue.entries.filter { it.hosts == null || Host.LINUX in it.hosts }

    @Test
    fun everyLinuxEmulatorIsInTheStore() {
        val offered = linuxEntries.mapNotNull { it.emulator }.toSet()
        val missing = LinuxCatalog.defs.map { it.id.removePrefix("linux.") }.filter { it !in offered && it !in leftOut }
        assertEquals(emptyList(), missing, "Linux emulators missing from the Store")
    }

    @Test
    fun everyLinuxEntryIsInstalledByFuse() {
        val linkedOnly = linuxEntries.filter { it.sourceFor(Host.LINUX) == null }.map { it.key }
        assertEquals(emptyList(), linkedOnly, "Linux entries with no download Fuse can install")
    }

    @Test
    fun kytyPs5TakesEachComputersOwnFileFromABuild() {
        val kyty = DesktopCatalogue.entries.single { it.key == "kytyps5" }
        // The files of one build, as its release workflow names them.
        val files = listOf("Windows-x64.zip", "macOS-x86_64.zip", "Linux-x86_64.tar.gz").map { "KytyPS5-2026-10-19-e5f0ee7-$it" }
        for ((host, file) in listOf(Host.WINDOWS to files[0], Host.MACOS to files[1], Host.LINUX to files[2])) {
            val rule = kyty.pick!!.invoke(host, false, null)!!
            assertEquals(listOf(file), files.filter { rule.containsMatchIn(it) }, "$host")
            assertTrue(kyty.sourceFor(host) != null, "$host installs it")
        }
    }

    @Test
    fun theLeftOutAreRealEmulatorIds() {
        val ids = LinuxCatalog.defs.map { it.id.removePrefix("linux.") }.toSet()
        assertTrue(leftOut.keys.all { it in ids }, "an id in the left-out list no longer exists: ${leftOut.keys - ids}")
    }
}
