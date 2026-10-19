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

    private val minishCap = "/home/deck/ROMs/gba/Minish Cap.gba"
    private val systems = mapOf(15L to "Game Boy Advance", 30L to "Game Boy Advance", 31L to "Game Boy Advance", 21L to "GameCube", 3L to "Super Nintendo")

    /** A list of [shortcuts] as Steam keeps it, two with one program as they can be (not merged, as Fuse's own entry is). */
    private fun raw(vararg shortcuts: SteamShortcut): ByteArray {
        val list = BinaryVdf.Block()
        shortcuts.forEachIndexed { i, s ->
            list.entries += i.toString() to BinaryVdf.Block().also { b ->
                b["AppName"] = BinaryVdf.Str(s.name)
                b["Exe"] = BinaryVdf.Str(s.exe)
                b["StartDir"] = BinaryVdf.Str(s.startDir)
                b["LaunchOptions"] = BinaryVdf.Str(s.launchOptions)
            }
        }
        return BinaryVdf.write(BinaryVdf.Block().also { it["shortcuts"] = list })
    }

    /** Each system's games started the person's own way: two through RetroArch's Flatpak, one through mGBA; others with frame generation, an AppImage's mount and another way of writing /home. */
    private fun habits(): ByteArray = raw(
        SteamShortcut("Metroid Fusion", "\"/usr/bin/flatpak\"", "\"/\"", launchOptions = "run org.libretro.RetroArch -L /cores/mgba_libretro.so \"$metroid\""),
        SteamShortcut("Minish Cap", "\"/usr/bin/flatpak\"", "\"/\"", launchOptions = "run org.libretro.RetroArch -L /cores/mgba_libretro.so \"$minishCap\""),
        SteamShortcut("Golden Sun", "\"/usr/bin/mgba-qt\"", "\"/usr/bin\"", launchOptions = "-f \"$goldenSun\""),
        SteamShortcut(
            "Wind Waker", "\"/home/deck/Applications/Dolphin.AppImage\"", "\"/tmp/.mount_DolphiAbC123/usr/bin\"",
            launchOptions = "LSFGVK_ENV=1 LSFGVK_MULTIPLIER=3 QT_QPA_PLATFORM=xcb %command% -b -e '$windWaker'",
        ),
        SteamShortcut("Chrono Trigger", "\"/usr/bin/snes9x-gtk\"", "\"/usr/bin\"", launchOptions = "/var/home/deck/ROMs/snes/Chrono\\ Trigger.sfc"),
        // A program inside an AppImage's mount is gone once it closes: nothing to learn from.
        SteamShortcut("Mario Kart", "\"/tmp/.mount_DolphiAbC123/usr/bin/dolphin-emu\"", "\"/\"", launchOptions = "-e '$windWaker'"),
    )

    @Test
    fun eachSystemsWayIsLearnedExactlyAsTheirShortcutsWriteIt() {
        val all = files + (minishCap to 31L)
        val learned = SteamLearn.habits(listOf(habits()), isFuse, all, systems::get).associateBy { it.system }
        assertEquals(setOf("Game Boy Advance", "GameCube", "Super Nintendo"), learned.keys)

        // The way most of a system's shortcuts start it, quotes and all.
        val gba = learned.getValue("Game Boy Advance")
        assertEquals("flatpak" to 2, gba.program to gba.shortcuts)
        assertEquals("\"/usr/bin/flatpak\"" to "\"/\"", gba.exe to gba.startDir)
        assertEquals("run org.libretro.RetroArch -L /cores/mgba_libretro.so " to "", gba.before to gba.after)
        assertEquals(SteamQuoting.DOUBLE, gba.quoting)
        assertEquals(SteamWritten("\"/usr/bin/flatpak\"", "\"/\"", "run org.libretro.RetroArch -L /cores/mgba_libretro.so \"$goldenSun\""), gba.forGame(goldenSun))

        // Frame generation set aside (offered as Fuse's own), the other variables kept, the AppImage's own folder for the mount.
        val gc = learned.getValue("GameCube")
        assertEquals(SteamFrameWrapper("LSFGVK_ENV=1 LSFGVK_MULTIPLIER=3", 3), gc.frameGeneration)
        assertEquals("QT_QPA_PLATFORM=xcb %command% -b -e ", gc.before)
        assertEquals(SteamQuoting.SINGLE, gc.quoting)
        assertEquals("\"/home/deck/Applications\"", gc.startDir)

        // /home written as /var/home, the game's file with backslashes as they write it.
        val snes = learned.getValue("Super Nintendo")
        assertEquals(mapOf("/home/" to "/var/home/"), snes.paths)
        assertEquals(SteamQuoting.PLAIN, snes.quoting)
        assertEquals("/var/home/deck/ROMs/snes/Secret\\ of\\ Mana\\ \\(USA\\).sfc", snes.forGame("/home/deck/ROMs/snes/Secret of Mana (USA).sfc").options)
        // Found by its file written that other way too.
        assertEquals("/var/home/deck/ROMs/snes/Chrono Trigger.sfc", SteamLearn.find(habits(), isFuse, all).single { it.gameId == 3L }.path)
    }

    @Test
    fun aGamesFileIsWrittenSoTheShellReadsItBackExactly() {
        val odd = "/roms/it's \"odd\" \$HOME `x` \\ (1).gba"
        for (q in SteamQuoting.entries) {
            val h = SteamHabit("GBA", "x", "\"/x\"", "\"/\"", "-f ", " --go", q)
            assertEquals(listOf("-f", odd, "--go"), SteamLearn.words(h.forGame(odd).options), q.name)
        }
    }

    @Test
    fun aDirectEntryIsWrittenTheirWayWithFusesMarkFirst() {
        val gba = SteamLearn.habits(listOf(habits()), isFuse, files + (minishCap to 31L), systems::get).single { it.system == "Game Boy Advance" }
        val gc = SteamLearn.habits(listOf(habits()), isFuse, files + (minishCap to 31L), systems::get).single { it.system == "GameCube" }
        val golden = SteamGameEntry(30, "Golden Sun", "Game Boy Advance", direct = SteamDirect(listOf("/usr/bin/mgba-qt", goldenSun), env = mapOf("LSFGVK_ENV" to "1", "LSFGVK_MULTIPLIER" to "2"), written = gba.forGame(goldenSun)))
        val zelda = SteamGameEntry(21, "Wind Waker", "GameCube", direct = SteamDirect(listOf("/usr/bin/dolphin-emu", windWaker), written = gc.forGame(windWaker)))
        val after = SteamGames.write(null, fuse, listOf(golden, zelda), isFuse)!!
        val (g, z) = entries(after)
        assertEquals("\"/usr/bin/flatpak\"" to "\"/\"", g.str("Exe") to g.str("StartDir"))
        assertEquals("FUSE_GAME=30 LSFGVK_ENV=1 LSFGVK_MULTIPLIER=2 %command% run org.libretro.RetroArch -L /cores/mgba_libretro.so \"$goldenSun\"", g.str("LaunchOptions"))
        assertEquals("FUSE_GAME=21 QT_QPA_PLATFORM=xcb %command% -b -e '$windWaker'", z.str("LaunchOptions"))
        assertEquals(mapOf(30L to SteamStart.DIRECT, 21L to SteamStart.DIRECT), SteamGames.starts(after, isFuse))
        // Written again, nothing changes.
        assertEquals(listOf(SteamGameAction.UNCHANGED, SteamGameAction.UNCHANGED), SteamGames.plan(after, fuse, listOf(golden, zelda), isFuse)!!.map { it.action })
        // Fuse's own entries are never learned from.
        assertTrue(SteamLearn.habits(listOf(after), isFuse, files, systems::get).isEmpty())
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
