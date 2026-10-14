package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A missing core comes from where RetroArch's own Core Downloader gets it, into the person's own folder. */
class RetroArchBuildbotTest {
    @Test
    fun theBuildbotsAddressForEachSystem() {
        assertEquals("https://buildbot.libretro.com/nightly/linux/x86_64/latest/mgba_libretro.so.zip", RetroArchBuildbot.url(Host.LINUX, "amd64", "mgba"))
        assertEquals("https://buildbot.libretro.com/nightly/windows/x86_64/latest/snes9x_libretro.dll.zip", RetroArchBuildbot.url(Host.WINDOWS, "amd64", "snes9x"))
        assertEquals("https://buildbot.libretro.com/nightly/apple/osx/arm64/latest/genesis_plus_gx_libretro.dylib.zip", RetroArchBuildbot.url(Host.MACOS, "aarch64", "genesis_plus_gx"))
        assertEquals("https://buildbot.libretro.com/nightly/apple/osx/x86_64/latest/fceumm_libretro.dylib.zip", RetroArchBuildbot.url(Host.MACOS, "x86_64", "fceumm"))
    }

    @Test
    fun nothingForOtherSystemsOrOddNames() {
        assertNull(RetroArchBuildbot.url(Host.ANDROID, "aarch64", "mgba"))
        assertNull(RetroArchBuildbot.url(Host.LINUX, "aarch64", "mgba"))
        assertNull(RetroArchBuildbot.url(Host.LINUX, "amd64", "../evil"))
        assertNull(RetroArchBuildbot.url(Host.LINUX, "amd64", "Mgba"))
    }

    @Test
    fun onlyALibraryIsKept() {
        assertTrue(RetroArchBuildbot.looksLikeLibrary(Host.LINUX, byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 2)))
        assertFalse(RetroArchBuildbot.looksLikeLibrary(Host.LINUX, "<html>".encodeToByteArray()))
        assertTrue(RetroArchBuildbot.looksLikeLibrary(Host.WINDOWS, byteArrayOf(0x4D, 0x5A, 0)))
        assertTrue(RetroArchBuildbot.looksLikeLibrary(Host.MACOS, byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte())))
    }

    @Test
    fun thePersonsOwnFolderNeverASystemOne() {
        val dirs = listOf("/home/deck/.config/retroarch/cores", "/usr/lib/libretro")
        assertEquals("/home/deck/.config/retroarch/cores", RetroArchBuildbot.installFolder(dirs, "/home/deck") { false })
        assertNull(RetroArchBuildbot.installFolder(listOf("/usr/lib/libretro"), "/home/deck") { true })
        // RetroArch's own setting first, when it is the person's.
        val set = listOf("/home/deck/Emulation/cores", "/home/deck/.config/retroarch/cores")
        assertEquals("/home/deck/.config/retroarch/cores", RetroArchBuildbot.installFolder(set, "/home/deck") { it.endsWith(".config/retroarch/cores") })
    }
}
