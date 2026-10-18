package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.linux.LinuxDetector
import io.github.matiyaaa.fuse.launch.linux.LinuxSuites
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Installs EmuDeck and RetroDECK made, laid out as their own documentation says (EmuDeck's AppImages
 * in ~/Applications with its folder in ~/.config/EmuDeck; RetroDECK as one Flatpak), told apart from
 * the person's own and from Flatpak's.
 */
class LinuxSuitesTest {
    private fun deck(emuDeck: Boolean, flatpaks: Set<String> = emptySet()) = FakeLinux(
        homeDir = "/home/deck",
        executables = setOf("/home/deck/Applications/Vita3K/Vita3K", "/home/deck/Downloads/rpcs3-v0.0.35.AppImage"),
        files = if (emuDeck) setOf("/home/deck/.config/EmuDeck") else emptySet(),
        dirs = mapOf(
            "/home/deck/Applications" to listOf("Cemu.AppImage", "pcsx2-Qt.AppImage"),
            "/home/deck/Downloads" to listOf("rpcs3-v0.0.35.AppImage"),
        ),
        flatpaks = flatpaks,
    )

    @Test
    fun emuDecksOwnAppImagesAreMarkedAsItsAndNothingElseIs() {
        val found = LinuxDetector.detect(deck(emuDeck = true, flatpaks = setOf("org.DolphinEmu.dolphin-emu"))).associateBy { it.id.value }
        assertEquals("/home/deck/Applications/Cemu.AppImage", found.getValue("linux.cemu").appId)
        assertEquals(LinuxSuites.EMUDECK, found.getValue("linux.cemu").installedBy)
        assertEquals(LinuxSuites.EMUDECK, found.getValue("linux.pcsx2").installedBy)
        // An AppImage the person put in Downloads is theirs; a Flatpak is Flatpak's, whoever asked for it.
        assertNull(found.getValue("linux.rpcs3").installedBy)
        assertNull(found.getValue("linux.dolphin").installedBy)
    }

    @Test
    fun withoutEmuDeckTheSameFilesAreThePersonsOwn() {
        val found = LinuxDetector.detect(deck(emuDeck = false)).associateBy { it.id.value }
        assertNull(found.getValue("linux.cemu").installedBy)
        assertNull(found.getValue("linux.pcsx2").installedBy)
    }

    @Test
    fun retroDeckIsItsOneFlatpak() {
        assertEquals(setOf(LinuxSuites.RETRODECK), LinuxSuites.found(deck(emuDeck = false, flatpaks = setOf("net.retrodeck.retrodeck"))))
        assertEquals(setOf(LinuxSuites.EMUDECK, LinuxSuites.RETRODECK), LinuxSuites.found(deck(emuDeck = true, flatpaks = setOf("net.retrodeck.retrodeck"))))
        assertEquals(emptySet(), LinuxSuites.found(deck(emuDeck = false, flatpaks = setOf("org.DolphinEmu.dolphin-emu"))))
        assertTrue("pcsx2" in LinuxSuites.RETRODECK_EMULATORS && "rpcs3" in LinuxSuites.RETRODECK_EMULATORS)
    }
}
