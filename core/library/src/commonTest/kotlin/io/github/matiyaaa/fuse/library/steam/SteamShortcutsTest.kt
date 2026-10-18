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
        // Adding again never replaces the launch options already on the entry (the person's to change).
        assertEquals("--fullscreen", ((list["1"] as BinaryVdf.Block)["LaunchOptions"] as BinaryVdf.Str).value)
        assertFalse(SteamShortcuts.contains(other, fuse.exe))
    }

    @Test
    fun addingAgainNeverMakesASecondEntryAndKeepsWhatThePersonChanged() {
        val first = assertNotNull(SteamShortcuts.add(null, fuse))
        // The person renames it, gives it their own options and puts it in a collection, in Steam.
        val root = BinaryVdf.parse(first)!!
        val entry = (root["shortcuts"] as BinaryVdf.Block)["0"] as BinaryVdf.Block
        entry["AppName"] = BinaryVdf.Str("Fuse (Living Room)")
        entry["LaunchOptions"] = BinaryVdf.Str("--fullscreen --safe-mode")
        entry["tags"] = BinaryVdf.Block(mutableListOf<Pair<String, BinaryVdf.Value>>("0" to BinaryVdf.Str("Emulation")))
        val edited = BinaryVdf.write(root)
        // A newer AppImage in another place: the same entry is brought up to date, not a second one.
        val moved = fuse.copy(exe = "\"/home/deck/Apps/Fuse-0.4.2.AppImage\"")
        val owned = { exe: String, _: String -> exe.substringAfterLast('/').startsWith("Fuse") }
        val again = assertNotNull(SteamShortcuts.add(edited, moved, owned))
        val list = BinaryVdf.parse(again)!!["shortcuts"] as BinaryVdf.Block
        assertEquals(1, list.entries.size)
        val e = list["0"] as BinaryVdf.Block
        assertEquals(moved.exe, (e["Exe"] as BinaryVdf.Str).value)
        assertEquals("Fuse (Living Room)", (e["AppName"] as BinaryVdf.Str).value)
        assertEquals("--fullscreen --safe-mode", (e["LaunchOptions"] as BinaryVdf.Str).value)
        assertEquals(listOf("Emulation", "Fuse"), (e["tags"] as BinaryVdf.Block).entries.map { (it.second as BinaryVdf.Str).value })
        // The same again changes nothing at all.
        assertContentEquals(again, SteamShortcuts.add(again, moved, owned))
    }

    @Test
    fun removingTakesOutOnlyFusesEntry() {
        val other = assertNotNull(SteamShortcuts.add(null, SteamShortcut("Emulator", "\"/usr/bin/emu\"", "\"/usr/bin/\"")))
        val both = assertNotNull(SteamShortcuts.add(other, fuse))
        val removed = assertNotNull(SteamShortcuts.remove(both) { exe, _ -> exe.endsWith("Fuse.AppImage") })
        val list = BinaryVdf.parse(removed)!!["shortcuts"] as BinaryVdf.Block
        assertEquals(listOf("0"), list.entries.map { it.first })
        assertEquals("Emulator", ((list["0"] as BinaryVdf.Block)["AppName"] as BinaryVdf.Str).value)
        // Nothing of Fuse's: the very same bytes.
        assertContentEquals(other, SteamShortcuts.remove(other) { exe, _ -> exe.endsWith("Fuse.AppImage") })
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

    /** Steam's list with a game Fuse put there first (it runs Fuse too, with --play), then Fuse itself. */
    private fun gameThenFuse(fuseIcon: String): ByteArray {
        val game = SteamShortcut("Metroid Fusion", fuse.exe, fuse.startDir, icon = "/grid/111_icon.png", launchOptions = "--play 42")
        val first = SteamShortcuts.add(null, game)!!
        // Fuse's own entry after it, as Steam would have written it.
        val root = BinaryVdf.parse(first)!!
        val list = root["shortcuts"] as BinaryVdf.Block
        val own = BinaryVdf.parse(SteamShortcuts.add(null, fuse.copy(icon = fuseIcon))!!)!!["shortcuts"] as BinaryVdf.Block
        list.entries += "1" to own.entries.single().second
        return BinaryVdf.write(root)
    }

    private fun entry(bytes: ByteArray, index: String) = (BinaryVdf.parse(bytes)!!["shortcuts"] as BinaryVdf.Block)[index] as BinaryVdf.Block

    @Test
    fun aFastStartGameIsNeverTakenForFuseEither() {
        val owned = { exe: String, _: String -> exe.endsWith("Fuse.AppImage") }
        val game = SteamShortcuts.add(null, SteamShortcut("Metroid Fusion", fuse.exe, fuse.startDir, launchOptions = "--run 42"))!!
        assertTrue(SteamShortcuts.idsOf(game, owned).isEmpty())
        val added = SteamShortcuts.add(game, fuse, owned)!!
        assertEquals(2, (BinaryVdf.parse(added)!!["shortcuts"] as BinaryVdf.Block).entries.size)
        assertEquals("--run 42", (entry(added, "0")["LaunchOptions"] as BinaryVdf.Str).value)
    }

    @Test
    fun aGameThatStartsFuseIsNeverTakenForFuse() {
        val owned = { exe: String, _: String -> exe.endsWith("Fuse.AppImage") }
        val bytes = gameThenFuse("/grid/fuse_icon.png")
        // Its id isn't Fuse's, adding Fuse leaves it alone, and so do a repair and a removal.
        assertEquals(listOf(SteamShortcuts.appId(fuse.exe, "Fuse").toLong() and 0xFFFFFFFFL), SteamShortcuts.idsOf(bytes, owned))
        val added = SteamShortcuts.add(bytes, fuse.copy(icon = "/grid/new_icon.png"), owned)!!
        assertEquals("Metroid Fusion", (entry(added, "0")["AppName"] as BinaryVdf.Str).value)
        assertEquals("--play 42", (entry(added, "0")["LaunchOptions"] as BinaryVdf.Str).value)
        assertEquals("/grid/111_icon.png", (entry(added, "0")["icon"] as BinaryVdf.Str).value)
        val rebuilt = SteamShortcuts.rebuild(bytes, fuse, owned)!!
        assertEquals(2, (BinaryVdf.parse(rebuilt)!!["shortcuts"] as BinaryVdf.Block).entries.size)
        val removed = SteamShortcuts.remove(bytes, owned)!!
        assertEquals("Metroid Fusion", (entry(removed, "0")["AppName"] as BinaryVdf.Str).value)
        assertEquals(1, (BinaryVdf.parse(removed)!!["shortcuts"] as BinaryVdf.Block).entries.size)
    }

    @Test
    fun anIconWhosePictureIsGoneIsPutBack() {
        val owned = { exe: String, _: String -> exe.endsWith("Fuse.AppImage") }
        val bytes = gameThenFuse("/grid/removed_icon.png")
        val exists = { path: String -> path != "/grid/removed_icon.png" }
        val repaired = assertNotNull(SteamShortcuts.repairIcons(bytes, owned, exists) { "/grid/${it}_icon.png" })
        val id = SteamShortcuts.appId(fuse.exe, "Fuse").toLong() and 0xFFFFFFFFL
        assertEquals("/grid/${id}_icon.png", (entry(repaired, "1")["icon"] as BinaryVdf.Str).value)
        // The game's own icon is the game's business.
        assertEquals("/grid/111_icon.png", (entry(repaired, "0")["icon"] as BinaryVdf.Str).value)
        // Nothing to do once it is there.
        kotlin.test.assertNull(SteamShortcuts.repairIcons(repaired, owned, { true }) { "/grid/${it}_icon.png" })
        // Adding Fuse again gives it the new icon too, where the old one is gone.
        val added = SteamShortcuts.add(bytes, fuse.copy(icon = "/grid/new_icon.png"), owned, exists)!!
        assertEquals("/grid/new_icon.png", (entry(added, "1")["icon"] as BinaryVdf.Str).value)
        // But a picture of the person's own choosing stays.
        val kept = SteamShortcuts.add(gameThenFuse("/home/deck/mine.png"), fuse.copy(icon = "/grid/new_icon.png"), owned, exists)!!
        assertEquals("/home/deck/mine.png", (entry(kept, "1")["icon"] as BinaryVdf.Str).value)
    }
}
