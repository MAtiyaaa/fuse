package io.github.matiyaaa.fuse.library.steam

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shortcuts other tools made, written here by hand: found by the game's own file, taken over in place. */
class SteamLearnTest {
    private val fuse = "/home/deck/Applications/Fuse.AppImage"
    private val isFuse: (String) -> Boolean = { it.substringAfterLast('/').startsWith("Fuse") }
    private val metroid = "/home/deck/ROMs/gba/Metroid Fusion (USA).gba"
    private val windWaker = "/run/media/mmcblk0p1/Emulation/roms/gc/Zelda Wind Waker.rvz"
    private val goldenSun = "/home/deck/ROMs/gba/Golden Sun.gba"
    private val files = mapOf(metroid to 15L, windWaker to 21L, goldenSun to 30L, "/home/deck/ROMs/snes/Chrono Trigger.sfc" to 3L)

    private fun list(vararg shortcuts: SteamShortcut): ByteArray =
        shortcuts.fold(null as ByteArray?) { bytes, s -> SteamShortcuts.add(bytes, s) }!!

    private fun entries(bytes: ByteArray) = (BinaryVdf.parse(bytes)!!["shortcuts"] as BinaryVdf.Block).entries.map { it.second as BinaryVdf.Block }
    private fun BinaryVdf.Block.str(k: String) = (this[k] as? BinaryVdf.Str)?.value

    /** A list as Steam ROM Manager, an EmuDeck launcher, a person's own `%command%` entry and a browser leave it. */
    private fun theirs(): ByteArray = list(
        SteamShortcut("Metroid Fusion", "\"/usr/bin/flatpak\"", "\"/\"", launchOptions = "run org.libretro.RetroArch -L /cores/mgba_libretro.so \"$metroid\"", tags = listOf("GBA")),
        SteamShortcut("Wind Waker", "\"/home/deck/Emulation/tools/launchers/dolphin-emu.sh\"", "\"/home/deck\"", launchOptions = "-b -e '$windWaker'"),
        SteamShortcut("Golden Sun", "\"/usr/bin/mgba-qt\"", "\"/usr/bin\"", launchOptions = "gamemoderun %command% -f /home/deck/ROMs/gba/Golden\\ Sun.gba"),
        // The same name as a Fuse game, but another file: never matched by name.
        SteamShortcut("Chrono Trigger", "\"/usr/bin/snes9x\"", "\"/usr/bin\"", launchOptions = "\"/media/other/Chrono Trigger.sfc\""),
        SteamShortcut("Firefox", "\"/usr/bin/firefox\"", "\"/usr/bin\""),
    )

    @Test
    fun shortcutsAreFoundByTheGamesOwnFileOnly() {
        val found = SteamLearn.find(theirs(), isFuse, files)
        assertEquals(listOf(15L, 21L, 30L), found.map { it.gameId })
        assertEquals(listOf("flatpak", "dolphin-emu.sh", "mgba-qt"), found.map { it.program })
        assertEquals(listOf(metroid, windWaker, goldenSun), found.map { it.path })
        assertEquals("Metroid Fusion", found.first().name)
        assertEquals(SteamShortcuts.appId("\"/usr/bin/flatpak\"", "Metroid Fusion").toLong() and 0xFFFFFFFFL, found.first().appId)
    }

    @Test
    fun fusesOwnEntriesAndGamesFuseAlreadyHasInSteamAreLeftOut() {
        val withFuse = SteamGames.write(theirs(), fuse, listOf(SteamGameEntry(15, "Metroid Fusion (Fuse)")), isFuse)!!
        val found = SteamLearn.find(withFuse, isFuse, files)
        assertEquals(listOf(21L, 30L), found.map { it.gameId })
        assertTrue(SteamLearn.find(null, isFuse, files).isEmpty())
        assertTrue(SteamLearn.find(byteArrayOf(1, 2, 3), isFuse, files).isEmpty())
    }

    @Test
    fun takenOverInPlaceKeepingTheNameArtAndCollections() {
        val before = theirs()
        val found = SteamLearn.find(before, isFuse, files).first { it.gameId == 15L }
        val after = SteamGames.takeOver(before, fuse, mapOf(found.appId to SteamGameEntry(15, "Metroid Fusion", "Game Boy Advance")), isFuse)!!
        val list = entries(after)
        assertEquals(entries(before).size, list.size)
        val taken = list[0]
        assertEquals("Metroid Fusion", taken.str("AppName"))
        assertEquals("\"$fuse\"", taken.str("Exe"))
        assertEquals("--run 15", taken.str("LaunchOptions"))
        assertEquals(listOf("GBA", "Game Boy Advance"), (taken["tags"] as BinaryVdf.Block).entries.map { (it.second as BinaryVdf.Str).value })
        // The id Steam files its art under is the one it had.
        assertEquals(mapOf(15L to found.appId), SteamGames.inSteam(after, isFuse))
        // Every other shortcut is exactly as it was.
        assertEquals(entries(before).drop(1).map { it.str("Exe") to it.str("LaunchOptions") }, list.drop(1).map { it.str("Exe") to it.str("LaunchOptions") })
        // Now Fuse's: not offered again, and taking it over again changes nothing.
        assertTrue(SteamLearn.find(after, isFuse, files).none { it.gameId == 15L })
        assertContentEquals(after, SteamGames.takeOver(after, fuse, mapOf(found.appId to SteamGameEntry(15, "Metroid Fusion")), isFuse))
    }

    @Test
    fun aShortcutCanBecomeADirectEntry() {
        val found = SteamLearn.find(theirs(), isFuse, files).first { it.gameId == 21L }
        val direct = SteamGameEntry(21, "Wind Waker", direct = SteamDirect(listOf("/usr/bin/dolphin-emu", "-b", "-e", windWaker)))
        val taken = entries(SteamGames.takeOver(theirs(), fuse, mapOf(found.appId to direct), isFuse)!!)[1]
        assertEquals("\"/usr/bin/dolphin-emu\"", taken.str("Exe"))
        assertEquals("FUSE_GAME=21 %command% -b -e '$windWaker'", taken.str("LaunchOptions"))
        assertEquals(mapOf(21L to SteamStart.DIRECT), SteamGames.starts(SteamGames.takeOver(theirs(), fuse, mapOf(found.appId to direct), isFuse), isFuse))
    }

    @Test
    fun wordsAreSplitAsAShellSplitsThem() {
        assertEquals(listOf("run", "org.x", "/a b/c.gba"), SteamLearn.words("run org.x \"/a b/c.gba\""))
        assertEquals(listOf("-e", "/it's here.iso"), SteamLearn.words("-e '/it'\\''s here.iso'"))
        assertEquals(listOf("/a b.gba"), SteamLearn.words("/a\\ b.gba"))
        assertEquals(listOf("say \"hi\" \$HOME"), SteamLearn.words("\"say \\\"hi\\\" \\\$HOME\""))
        assertEquals(emptyList(), SteamLearn.words("   "))
        assertEquals(listOf(""), SteamLearn.words("''"))
        // Quoted as Fuse quotes a Direct entry's arguments, read back the same.
        val args = listOf("-f", "/roms/Minish Cap (USA).gba", "it's", "")
        assertEquals(args, SteamLearn.words(args.joinToString(" ") { SteamGames.shell(it) }))
    }
}
