package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.patches.Cht
import io.github.matiyaaa.fuse.launch.patches.CwCheats
import io.github.matiyaaa.fuse.launch.patches.DuckStationPatchRules
import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.patches.PatchState
import io.github.matiyaaa.fuse.launch.patches.Pcsx2PatchRules
import io.github.matiyaaa.fuse.launch.patches.PpssppHome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DuckStation's .cht files and game settings, and PPSSPP's CWCheat files, read and changed as each emulator does. */
class NativeCheatsTest {
    private val cht = """
        ; patches for one game
        [Widescreen 16:9]
        Author = somebody
        Description = Renders the 3D world in 16:9.
        OverrideAspectRatio = 16:9
        DisallowForAchievements = true
        Type = Gameshark
        Activation = EndFrame
        80012345 1234

        [60 FPS]
        Description = Runs at 60
        Option = 30 FPS:1
        Option = 60 FPS:2
        80012346 0001 ; a comment after the code

        #group=Player
        [Infinite Health]
        80013000 00FF

        [Hidden]
        Ignore = true
        80014000 0001

        [Moon Jump]
        Activation = Manual
        D0012345 0040
        80014000 0100
    """.trimIndent()

    @Test
    fun chtCodesAreListedAsDuckStationListsThem() {
        val codes = Cht.parse(cht)
        assertEquals(listOf("Widescreen 16:9", "60 FPS", "Player\\Infinite Health", "Player\\Moon Jump"), codes.map { it.name })
        assertEquals("somebody", codes[0].author)
        assertEquals("Renders the 3D world in 16:9.", codes[0].description)
        assertEquals("16:9", codes[0].aspectRatio)
        assertTrue(codes[1].hasOptions)
        assertEquals("Infinite Health", codes[2].label)
        assertEquals("Player", codes[2].group)
        assertTrue(codes[3].manual)
        assertFalse(codes[2].manual)

        // A later code with the same name replaces the earlier one, in place.
        val twice = Cht.parse("[A]\nDescription = first\n1 1\n[B]\n2 2\n[A]\nDescription = second\n3 3\n")
        assertEquals(listOf("A", "B"), twice.map { it.name })
        assertEquals("second", twice[0].description)
    }

    @Test
    fun chtFilesAreFoundByTheGamesSerial() {
        val rules = DuckStationPatchRules
        assertTrue(rules.matchesCht("SLUS-00594.cht", "SLUS-00594"))
        assertTrue(rules.matchesCht("SLUS-00594_0123456789ABCDEF.cht", "SLUS-00594"))
        assertTrue(rules.matchesCht("slus-00594.CHT", "SLUS-00594"))
        assertFalse(rules.matchesCht("SLUS-005941.cht", "SLUS-00594"))
        assertFalse(rules.matchesCht("SLUS-00594.ini", "SLUS-00594"))
        assertEquals("SLUS-00594.ini", rules.gameSettingsName("SLUS-00594"))
        assertEquals("SLUS-00594.cht", rules.zipEntry("SLUS-00594"))
    }

    @Test
    fun duckStationStatesFollowItsGameSettings() {
        val rules = DuckStationPatchRules
        val codes = Cht.parse(cht)
        val game = IniText("[Patches]\nEnable = 60 FPS\n\n[Cheats]\nEnable = Player\\Infinite Health\n")
        val patches = rules.states(codes, game, emptySet(), rules.PATCHES, widescreenFor = "16:9").associateBy { it.patch.name }
        assertEquals(PatchState.ON_FOR_ALL_GAMES, patches.getValue("Widescreen 16:9").state)
        assertEquals(PatchState.ON_IN_EMULATOR, patches.getValue("60 FPS").state)
        assertEquals(PatchState.OFF, patches.getValue("Player\\Infinite Health").state)
        assertFalse("Player\\Moon Jump" in patches, "applied by hand while playing, so not listed")

        val cheats = rules.states(codes, game, setOf("Player\\Infinite Health"), rules.CHEATS).associateBy { it.patch.name }
        assertEquals(PatchState.ON_BY_FUSE, cheats.getValue("Player\\Infinite Health").state)
        assertFalse(rules.cheatsOn(game), "cheats apply only once the game's own file says so")
        assertTrue(rules.cheatsOn(IniText("[Cheats]\nEnableCheats = true\n")))
        assertTrue(rules.databaseCheats(game))
        assertFalse(rules.databaseCheats(IniText("[Cheats]\nLoadCheatsFromDatabase = false\n")))

        // The same Enable lines PCSX2 uses, so turning one on and off again leaves the file as it was.
        val status = rules.states(codes, game, emptySet(), rules.CHEATS).first { it.patch.name == "60 FPS" }
        val before = game.toString()
        val owned = Pcsx2PatchRules.change(status, true, game, emptySet(), rules.CHEATS)!!
        assertEquals(listOf("Player\\Infinite Health", "60 FPS"), game.values("Cheats", "Enable"))
        Pcsx2PatchRules.change(rules.states(codes, game, owned, rules.CHEATS).first { it.patch.name == "60 FPS" }, false, game, owned, rules.CHEATS)
        assertEquals(before, game.toString())
    }

    @Test
    fun widescreenPatchesFollowDuckStationsScreenShape() {
        val rules = DuckStationPatchRules
        val global = IniText("[GPU]\nWidescreenHack = true\n\n[Display]\nAspectRatio = 16:9\n")
        assertEquals("16:9", rules.widescreenFor(IniText(""), global))
        assertNull(rules.widescreenFor(IniText("[Display]\nAspectRatio = 4:3\n"), global), "the game's own setting comes first")
        assertNull(rules.widescreenFor(IniText(""), IniText("[GPU]\nWidescreenHack = true\n\n[Display]\nAspectRatio = Auto (Game Native)\n")))
        assertNull(rules.widescreenFor(IniText(""), IniText("[GPU]\nWidescreenHack = false\n\n[Display]\nAspectRatio = 16:9\n")))
        assertTrue(rules.enhancementsOff(IniText(""), IniText("[Main]\nDisableAllEnhancements = true\n")))
    }

    private val cw = "_S ULUS-10041\n_G Some Game\n_C0 Infinite HP\n_L 0x20001234 0x000003E7\n_C1 Max Money\n_L 0x20005678 0x0098967F\n" +
        "_C0 No lines\n_S ULES-00001\n_G Other\n_C1 Other game cheat\n_L 0x20000002 0x00000003\n"

    @Test
    fun ppssppCheatsAreTheGamesBlockOnly() {
        val cheats = CwCheats.parse(cw, "ULUS10041")
        assertEquals(listOf("Infinite HP", "Max Money"), cheats.map { it.name })
        assertEquals(listOf(false, true), cheats.map { it.on })
        assertEquals(listOf(2, 4), cheats.map { it.line })
        assertTrue(CwCheats.parse(cw, "ULES00001").single().on)

        // One block for another id still counts, as PPSSPP allows; a second block doesn't.
        assertEquals(listOf("X"), CwCheats.parse("﻿_S ULES-99999\n_C1 X\n_L 0x1 0x2\n", "ULUS10041").map { it.name })
        assertEquals(listOf("Mine"), CwCheats.parse("_S ULES-99999\n_C1 X\n_L 0x1 0x2\n_S ULUS-10041\n_C0 Mine\n_L 0x1 0x2\n", "ULUS10041").map { it.name })
        assertEquals(emptyList(), CwCheats.parse("_S ULES-99999\n_C1 X\n_L 0x1 0x2\n_S ULES-88888\n_C0 Y\n_L 0x1 0x2\n", "ULUS10041").map { it.name })
    }

    @Test
    fun aPpssppCheatIsSwitchedOnItsOwnLine() {
        val crlf = cw.replace("\n", "\r\n")
        val cheat = CwCheats.parse(crlf, "ULUS10041").first()
        val on = CwCheats.set(crlf, cheat, true)!!
        assertEquals(crlf.replace("_C0 Infinite HP", "_C1 Infinite HP"), on)
        assertEquals(crlf, CwCheats.set(on, CwCheats.parse(on, "ULUS10041").first(), false))
        // The line moved since it was read: nothing is changed.
        assertNull(CwCheats.set("_S ULUS-10041\n_G Some Game\n_L 0x1 0x2\n", cheat, true))

        assertEquals("ULUS10041", PpssppHome.gameId("ULUS-10041"))
        assertNull(PpssppHome.gameId("SLUS-20946x"))
        assertEquals("/m/PSP/Cheats/ULUS10041.ini", PpssppHome("/m").cheatFile("ULUS10041"))
        assertEquals("/m/PSP/SYSTEM/ULUS10041_ppsspp.ini", PpssppHome("/m").gameSettingsFile("ULUS10041"))
    }

    private val raCheats = "cheats = 3\n\ncheat0_desc = \"Infinite Health\"\ncheat0_code = \"0300:FF\"\ncheat0_enable = false\n\n" +
        "cheat1_desc = \"Max Coins\"\ncheat1_code = \"0301:99\"\ncheat1_enable = \"true\"\n\ncheat2_code = \"0302:01\"\n"

    @Test
    fun retroArchCheatsAreReadAndSwitchedLineByLine() {
        val cheats = io.github.matiyaaa.fuse.launch.patches.RetroArchCheats.parse(raCheats)
        assertEquals(listOf("Infinite Health", "Max Coins", null), cheats.map { it.desc })
        assertEquals(listOf(false, true, false), cheats.map { it.on })

        val ra = io.github.matiyaaa.fuse.launch.patches.RetroArchCheats
        val on = ra.set(raCheats, 0, true)
        assertEquals(raCheats.replace("cheat0_enable = false", "cheat0_enable = true"), on, "its own quoting is kept")
        assertEquals(raCheats, ra.set(on, 0, false))
        // A cheat with no enable line gets one after its others.
        assertEquals(raCheats.replace("cheat2_code = \"0302:01\"\n", "cheat2_code = \"0302:01\"\ncheat2_enable = \"true\"\n"), ra.set(raCheats, 2, true))
        assertTrue(ra.parse(ra.set(raCheats, 2, true))[2].on)
    }

    @Test
    fun retroArchsFilesAndFoldersAreNamedAsItNamesThem() {
        val ra = io.github.matiyaaa.fuse.launch.patches.RetroArchCheats
        val cfg = io.github.matiyaaa.fuse.launch.patches.RetroArchCfg
        assertEquals("/c/mGBA/Golden Sun (USA).cht", ra.gameFile("/c", "mGBA", ra.contentName("/roms/gba/Golden Sun (USA).zip")))
        assertEquals("/k/mGBA/Golden Sun (USA).cfg", ra.overrideFile("/k", "mGBA", "Golden Sun (USA)"))
        val names = listOf("Golden Sun (USA).cht", "Golden Sun (Europe).cht", "Golden Sun - The Lost Age (USA).cht", "readme.txt")
        assertEquals("Golden Sun (USA).cht", ra.databaseMatch(names, "golden sun (usa)", null))
        assertNull(ra.databaseMatch(names, "Golden Sun (Japan)", "Golden Sun"), "two regions share the title: none is guessed")
        assertEquals("Golden Sun - The Lost Age (USA).cht", ra.databaseMatch(names, "GS2", "Golden Sun - The Lost Age"))

        assertEquals("/home/mo/.config/retroarch/cheats", cfg.folder("~/.config/retroarch/cheats", "/x", "/home/mo", null))
        assertEquals("/x", cfg.folder("default", "/x", "/home/mo", null))
        assertEquals("/x", cfg.folder("", "/x", "/home/mo", null))
        assertEquals("C:/RetroArch/cheats", cfg.folder(":\\cheats", "/x", "/home/mo", "C:/RetroArch"))

        val override = "video_smooth = \"true\"\n"
        val with = cfg.set(override, "apply_cheats_after_load", "true")
        assertEquals("video_smooth = \"true\"\napply_cheats_after_load = \"true\"\n", with)
        assertEquals("true", cfg.parse(with)["apply_cheats_after_load"])
        assertEquals(override, cfg.remove(with, "apply_cheats_after_load"))
        assertEquals("", cfg.remove(cfg.set("", "apply_cheats_after_load", "true"), "apply_cheats_after_load"))
    }
}
