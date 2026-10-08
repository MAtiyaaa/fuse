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

    @Test
    fun anotherProgramNamedFuseIsNotChanged() {
        val unrelated = SteamShortcut("Fuse", "\"/games/puzzle.exe\"", "\"/games\"")
        val before = SteamShortcuts.add(null, unrelated)!!
        val after = SteamShortcuts.add(before, fuse)!!
        assertTrue(SteamShortcuts.contains(after, unrelated.exe))
        assertTrue(SteamShortcuts.contains(after, fuse.exe))
    }

    @Test
    fun repairIsIdempotentKeepsUnrelatedEntriesAndExistingSteamId() {
        val root = BinaryVdf.parse(SteamShortcuts.add(null, fuse)!!)!!
        val list = root["shortcuts"] as BinaryVdf.Block
        (list["0"] as BinaryVdf.Block)["appid"] = BinaryVdf.Int32(-123)
        val duplicate = BinaryVdf.parse(SteamShortcuts.add(null, fuse.copy(exe = "\"/old/Fuse.AppImage\""))!!)!!["shortcuts"] as BinaryVdf.Block
        list.entries += "1" to duplicate["0"]!!
        val unrelated = SteamShortcut("Other", "\"/games/other\"", "\"/games\"")
        val before = SteamShortcuts.add(BinaryVdf.write(root), unrelated)!!
        fun owned(exe: String, name: String) = exe.endsWith("/Fuse.AppImage")
        val once = SteamShortcuts.rebuild(before, fuse, ::owned)!!
        val twice = SteamShortcuts.rebuild(once, fuse, ::owned)!!
        assertContentEquals(once, twice)
        assertEquals(listOf(4294967173L), SteamShortcuts.idsOf(twice, ::owned))
        assertTrue(SteamShortcuts.contains(twice, unrelated.exe))
    }

    @Test
    fun everyTruncatedShortcutAndTrailingGarbageIsRefused() {
        val valid = SteamShortcuts.add(null, fuse)!!
        for (length in 1 until valid.size) assertEquals(null, SteamShortcuts.add(valid.copyOf(length), fuse), "length $length")
        assertEquals(null, SteamShortcuts.add(valid + byteArrayOf(0), fuse))
    }
    @Test
    fun malformedShortcutRootIsNeverReplaced() {
        val root = BinaryVdf.Block(mutableListOf("shortcuts" to BinaryVdf.Str("not a list")))
        val bytes = BinaryVdf.write(root)
        val shortcut = SteamShortcut("Fuse", "fuse", ".")
        kotlin.test.assertNull(SteamShortcuts.add(bytes, shortcut))
        kotlin.test.assertNull(SteamShortcuts.rebuild(bytes, shortcut) { _, _ -> true })
    }

}
