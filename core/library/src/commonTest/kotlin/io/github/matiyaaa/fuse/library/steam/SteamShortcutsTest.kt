package io.github.matiyaaa.fuse.library.steam

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SteamShortcutsTest {
    private val fuse = SteamShortcut("Fuse", "\"/home/deck/Applications/Fuse.AppImage\"", "\"/home/deck/Applications/\"", launchOptions = "--fullscreen", tags = listOf("Fuse"))

    @Test
    fun aNewFileHoldsTheShortcut() {
        val bytes = assertNotNull(SteamShortcuts.add(null, fuse))
        val root = assertNotNull(BinaryVdf.parse(bytes))
        val entry = (root["shortcuts"] as BinaryVdf.Block)["0"] as BinaryVdf.Block
        assertEquals("Fuse", (entry["AppName"] as BinaryVdf.Str).value)
        assertEquals("--fullscreen", (entry["LaunchOptions"] as BinaryVdf.Str).value)
        assertTrue(SteamShortcuts.contains(bytes, fuse.exe))
        // Writing what was read gives the same bytes.
        assertContentEquals(bytes, BinaryVdf.write(root))
    }

    @Test
    fun anExistingGameIsKeptAndFuseIsAddedOnceAfterIt() {
        val other = assertNotNull(SteamShortcuts.add(null, SteamShortcut("Emulator", "\"/usr/bin/emu\"", "\"/usr/bin/\"")))
        val once = assertNotNull(SteamShortcuts.add(other, fuse))
        val twice = assertNotNull(SteamShortcuts.add(once, fuse.copy(launchOptions = "--windowed")))
        val list = BinaryVdf.parse(twice)!!["shortcuts"] as BinaryVdf.Block
        assertEquals(listOf("0", "1"), list.entries.map { it.first })
        assertEquals("Emulator", ((list["0"] as BinaryVdf.Block)["AppName"] as BinaryVdf.Str).value)
        assertEquals("--windowed", ((list["1"] as BinaryVdf.Block)["LaunchOptions"] as BinaryVdf.Str).value)
        assertFalse(SteamShortcuts.contains(other, fuse.exe))
    }

    @Test
    fun theIdHasItsTopBitSetAsSteamExpects() {
        assertTrue(SteamShortcuts.appId(fuse.exe, fuse.name) < 0)
        assertEquals(SteamShortcuts.appId(fuse.exe, fuse.name), SteamShortcuts.appId(fuse.exe, fuse.name))
    }

    @Test
    fun garbageIsRefusedRatherThanOverwritten() {
        assertEquals(null, SteamShortcuts.add(byteArrayOf(7, 1, 2), fuse))
    }
}
