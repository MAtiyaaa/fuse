package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScannedGame
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderInterpreterTest {
    private val fs = InMemoryFileSystem()
    private val source = LibrarySourceId(1)

    private suspend fun scan(
        platform: String,
        folder: String,
        policies: FolderPolicyResolver = FolderPolicyResolver.CatalogDefaults,
        options: ScanOptions = ScanOptions(),
    ): FolderScanResult = FolderInterpreter(fs, options)
        .scanPlatformFolder(PlatformCatalog.byId(platform)!!, folder, source, policies)

    private fun List<ScannedGame>.byTitle(title: String) = single { it.title == title }

    // Acceptance example 1: an extracted PS3 disc is one game.
    @Test
    fun extractedPs3FolderIsOneGame() = runTest {
        val game = "/ROMs/ps3/Metal Gear Solid 4"
        fs.file("$game/PS3_DISC.SFB", size = 32)
        fs.file("$game/PS3_GAME/PARAM.SFO", content = "\u0000PSF\u0001\u0001TITLE_ID\u0000TITLE\u0000\u0000\u0000\u0000BLUS30109\u0000\u0000Metal Gear Solid 4")
        fs.file("$game/PS3_GAME/USRDIR/EBOOT.BIN", size = 5_000_000)
        fs.file("$game/PS3_GAME/USRDIR/data/stage01.dat", size = 1_000_000)

        val result = scan("ps3", "/ROMs/ps3")
        val single = result.games.single()
        assertEquals("Metal Gear Solid 4", single.title)
        assertEquals(FolderInterpretation.FOLDER_IS_GAME, single.interpretation)
        assertEquals(LocationKind.FOLDER, single.kind)
        assertEquals(game, single.path)
        assertEquals(game, single.launchPath)
        assertEquals("BLUS30109", single.tags.serial)
        assertTrue(result.complete)
    }

    @Test
    fun ps5DumpFolderIsOneGame() = runTest {
        val game = "/ROMs/ps5/Astro Bot [PPSA01234]"
        fs.file("$game/eboot.bin", size = 5_000_000)
        fs.file("$game/sce_sys/param.json", content = "{\"titleId\": \"PPSA01234\"}")
        fs.file("$game/sce_sys/icon0.png", size = 1000)

        val single = scan("ps5", "/ROMs/ps5").games.single()
        assertEquals(FolderInterpretation.FOLDER_IS_GAME, single.interpretation)
        assertEquals(game, single.path)
    }

    @Test
    fun newSystemsReadTheirOwnFiles() = runTest {
        fs.file("/ROMs/tic80/Into the Dark.tic", size = 100)
        fs.file("/ROMs/vic20/Gridrunner.prg", size = 100)
        fs.file("/ROMs/vic20/notes.txt", size = 100)
        assertEquals(listOf("Into the Dark"), scan("tic-80", "/ROMs/tic80").games.map { it.title })
        assertEquals(listOf("Gridrunner"), scan("vic-20", "/ROMs/vic20").games.map { it.title })
    }

    // Acceptance example 2: a Switch folder with base, update and DLC folders is one game.
    @Test
    fun switchFolderWithBaseUpdateAndDlcIsOneGame() = runTest {
        val game = "/ROMs/switch/Game Name"
        fs.file("$game/base/Game Name [0100ABCD12345000][v0].nsp", size = 4000)
        fs.file("$game/update/Game Name [0100ABCD12345800][v131072].nsp", size = 300)
        fs.file("$game/dlc/a.nsp", size = 10)
        fs.file("$game/dlc/b.nsp", size = 20)

        val single = scan("switch", "/ROMs/switch").games.single()
        assertEquals("Game Name", single.title)
        assertEquals(FolderInterpretation.MULTI_FILE_GAME, single.interpretation)
        assertEquals("$game/base/Game Name [0100ABCD12345000][v0].nsp", single.launchPath)
        assertEquals(1, single.content.count { it.kind == ContentKind.UPDATE })
        assertEquals(listOf("a.nsp", "b.nsp"), single.content.filter { it.kind == ContentKind.DLC }.map { it.name })
        assertEquals(4330, single.sizeBytes)
        assertEquals("0100ABCD12345000", single.tags.serial)
    }

    @Test
    fun rommCategoryFoldersAreChildContentCaseInsensitively() = runTest {
        val game = "/roms/gba/Pokemon Emerald"
        fs.file("$game/Pokemon Emerald (USA).gba", size = 16_000_000)
        fs.file("$game/Manuals/manual.pdf")
        fs.file("$game/PATCHES/fix.ips")
        fs.file("$game/hacks/Emerald Kaizo.gba")
        fs.file("$game/DLC/extra.bin")
        fs.file("$game/Updates/u1.gba")
        fs.file("$game/soundtrackes/theme.mp3")
        fs.file("$game/readme.pdf")
        fs.file("$game/Pokemon Emerald (USA).sav")

        val single = scan("gba", "/roms/gba").games.single()
        assertEquals(FolderInterpretation.MULTI_FILE_GAME, single.interpretation)
        assertEquals("$game/Pokemon Emerald (USA).gba", single.launchPath)
        val kinds = single.content.groupBy({ it.kind }, { it.name })
        assertEquals(listOf("manual.pdf"), kinds[ContentKind.MANUAL]?.filter { it == "manual.pdf" })
        assertEquals(listOf("fix.ips"), kinds[ContentKind.PATCH])
        assertEquals(listOf("Emerald Kaizo.gba"), kinds[ContentKind.HACK])
        assertEquals(listOf("extra.bin"), kinds[ContentKind.DLC])
        assertEquals(listOf("u1.gba"), kinds[ContentKind.UPDATE])
        assertEquals(listOf("theme.mp3"), kinds[ContentKind.SOUNDTRACK])
        // The loose pdf at the root is a manual too; the save file is ignored.
        assertTrue(kinds[ContentKind.MANUAL]!!.contains("readme.pdf"))
        assertFalse(single.content.any { it.name.endsWith(".sav") })
        assertEquals(listOf("USA"), single.tags.regions)
    }

    @Test
    fun categoriesOnlyCountDirectlyUnderTheGameFolder() = runTest {
        val game = "/roms/gba/Game"
        fs.file("$game/Game.gba")
        fs.file("$game/extras/dlc/readme.txt")
        val single = scan("gba", "/roms/gba").games.single()
        assertEquals(FolderInterpretation.MULTI_FILE_GAME, single.interpretation)
        assertTrue(single.content.none { it.kind == ContentKind.DLC })
    }

    @Test
    fun categoryDirectoriesAreChildItemsToo() = runTest {
        val game = "/roms/wiiu/Zelda"
        fs.file("$game/Zelda.wua")
        fs.file("$game/dlc/Pack One/content/0001.app")
        fs.file("$game/dlc/Pack Two/content/0001.app")
        val single = scan("wiiu", "/roms/wiiu").games.single()
        val dlc = single.content.filter { it.kind == ContentKind.DLC }
        assertEquals(listOf("Pack One", "Pack Two"), dlc.map { it.name })
        assertTrue(dlc.all { it.isDirectory })
    }

    @Test
    fun organisationalFolderListsGamesIndividually() = runTest {
        fs.file("/roms/snes/Hacks/Super Mario World - Kaizo.sfc")
        fs.file("/roms/snes/Hacks/Zelda - Parallel Worlds.sfc")
        fs.file("/roms/snes/Hacks/Metroid - Redesign.sfc")
        fs.file("/roms/snes/Chrono Trigger (USA).sfc")

        val games = scan("snes", "/roms/snes").games
        assertEquals(4, games.size)
        assertTrue(games.all { it.interpretation == FolderInterpretation.SINGLE_FILE && it.kind == LocationKind.FILE })
        assertEquals("/roms/snes/Hacks/Metroid - Redesign.sfc", games.byTitle("Metroid - Redesign").path)
    }

    @Test
    fun regionFoldersAreWalkedAndGameFoldersInsideAreFound() = runTest {
        fs.file("/roms/psx/Europe/Crash Bandicoot (Europe).chd")
        fs.file("/roms/psx/Europe/Final Fantasy VII/Final Fantasy VII (Europe) (Disc 1).chd")
        fs.file("/roms/psx/Europe/Final Fantasy VII/Final Fantasy VII (Europe) (Disc 2).chd")
        fs.file("/roms/psx/USA/Spyro (USA).chd")

        val games = scan("psx", "/roms/psx").games
        assertEquals(setOf("Crash Bandicoot (Europe)", "Final Fantasy VII", "Spyro (USA)"), games.map { it.title }.toSet())
        val ff7 = games.byTitle("Final Fantasy VII")
        assertEquals(FolderInterpretation.MULTI_FILE_GAME, ff7.interpretation)
        assertEquals(2, ff7.discs.size)
        assertTrue(ff7.launchPath.endsWith("(Disc 1).chd"))
        assertEquals(2, ff7.tags.discTotal)
    }

    @Test
    fun bucketNameWithOneGameStaysOrganisational() = runTest {
        fs.file("/roms/snes/Hacks/Mario Hack.sfc")
        val game = scan("snes", "/roms/snes").games.single()
        assertEquals(LocationKind.FILE, game.kind)
        assertEquals("Mario Hack", game.title)
    }

    @Test
    fun seriesFolderWithNestedGamesIsOrganisational() = runTest {
        fs.file("/roms/psx/Final Fantasy/Final Fantasy VII.chd")
        fs.file("/roms/psx/Final Fantasy/Final Fantasy VIII/FF8 (Disc 1).chd")
        fs.file("/roms/psx/Final Fantasy/Final Fantasy VIII/FF8 (Disc 2).chd")
        val games = scan("psx", "/roms/psx").games
        assertEquals(setOf("Final Fantasy VII", "Final Fantasy VIII"), games.map { it.title }.toSet())
    }

    @Test
    fun m3uInsideGameFolderIsTheLaunchFile() = runTest {
        val game = "/roms/psx/Metal Gear Solid"
        fs.file("$game/Metal Gear Solid.m3u", content = "MGS (Disc 1).chd\nMGS (Disc 2).chd\n")
        fs.file("$game/MGS (Disc 1).chd")
        fs.file("$game/MGS (Disc 2).chd")
        val single = scan("psx", "/roms/psx").games.single()
        assertEquals("$game/Metal Gear Solid.m3u", single.launchPath)
        assertEquals(2, single.discs.size)
        assertTrue(single.content.isEmpty())
    }

    @Test
    fun playlistReferencingASubfolderHidesThoseDiscs() = runTest {
        fs.file("/roms/psx/Final Fantasy VII.m3u", content = "FF7 Discs/FF7 (Disc 1).chd\nFF7 Discs/FF7 (Disc 2).chd\n")
        fs.file("/roms/psx/FF7 Discs/FF7 (Disc 1).chd")
        fs.file("/roms/psx/FF7 Discs/FF7 (Disc 2).chd")
        val single = scan("psx", "/roms/psx").games.single()
        assertEquals("/roms/psx/Final Fantasy VII.m3u", single.launchPath)
        assertEquals(FolderInterpretation.MULTI_DISC, single.interpretation)
        assertEquals(listOf("/roms/psx/FF7 Discs/FF7 (Disc 1).chd", "/roms/psx/FF7 Discs/FF7 (Disc 2).chd"), single.discs.map { it.path })
    }

    @Test
    fun titleIdFilesFillTheSerial() = runTest {
        fs.file("/roms/psvita/Persona 4 Golden.psvita", content = "PCSE00120\n")
        fs.file("/roms/ps3/Demon's Souls.ps3", content = "BLUS30443")
        assertEquals("PCSE00120", scan("psvita", "/roms/psvita").games.single().tags.serial)
        assertEquals("BLUS30443", scan("ps3", "/roms/ps3").games.single().tags.serial)
    }

    @Test
    fun directoryInterpretedAsFileLaunchesTheInnerFile() = runTest {
        fs.file("/roms/psx/Jet Grind.cue/Jet Grind.cue", content = "FILE \"Jet Grind.bin\" BINARY\n")
        fs.file("/roms/psx/Jet Grind.cue/Jet Grind.bin")
        fs.file("/roms/ps3/Gran Turismo 5.ps3/PS3_GAME/PARAM.SFO", content = "BCES00569")
        fs.file("/roms/ps3/Gran Turismo 5.ps3/PS3_DISC.SFB")

        val psx = scan("psx", "/roms/psx").games.single()
        assertEquals("Jet Grind", psx.title)
        assertEquals("/roms/psx/Jet Grind.cue/Jet Grind.cue", psx.launchPath)
        assertEquals(FolderInterpretation.FOLDER_IS_GAME, psx.interpretation)

        val ps3 = scan("ps3", "/roms/ps3").games.single()
        assertEquals("Gran Turismo 5", ps3.title)
        assertEquals("/roms/ps3/Gran Turismo 5.ps3", ps3.launchPath)
        assertEquals("BCES00569", ps3.tags.serial)
    }

    @Test
    fun knownFolderStructures() = runTest {
        fs.file("/roms/wiiu/Zelda BotW/code/U-King.rpx")
        fs.file("/roms/wiiu/Zelda BotW/code/app.xml")
        fs.dir("/roms/wiiu/Zelda BotW/content")
        fs.file("/roms/wiiu/Zelda BotW/meta/meta.xml")
        fs.file("/roms/xbox360/Halo 3/default.xex")
        fs.file("/roms/psvita/Persona 4 Golden/sce_sys/param.sfo", content = "PCSE00120")
        fs.file("/roms/psvita/Persona 4 Golden/eboot.bin")
        fs.file("/roms/psp/Lumines/PSP_GAME/SYSDIR/EBOOT.BIN")

        val wiiu = scan("wiiu", "/roms/wiiu").games.single()
        assertEquals("/roms/wiiu/Zelda BotW/code/U-King.rpx", wiiu.launchPath)
        assertEquals(FolderInterpretation.FOLDER_IS_GAME, wiiu.interpretation)
        assertEquals("/roms/xbox360/Halo 3/default.xex", scan("xbox360", "/roms/xbox360").games.single().launchPath)
        val vita = scan("psvita", "/roms/psvita").games.single()
        assertEquals("/roms/psvita/Persona 4 Golden", vita.launchPath)
        assertEquals("PCSE00120", vita.tags.serial)
        assertEquals("/roms/psp/Lumines", scan("psp", "/roms/psp").games.single().launchPath)
    }

    // A folder-native system's game folder with data folders inside is one game, not one per folder.
    @Test
    fun foldersInsideAFolderNativeGameAreNeverGames() = runTest {
        fs.file("/roms/scummvm/Monkey Island/MONKEY.000", size = 10)
        fs.file("/roms/scummvm/Monkey Island/audio/track1.ogg", size = 10)
        fs.file("/roms/scummvm/Monkey Island/video/intro/part1.smk", size = 10)
        fs.file("/roms/scummvm/Monkey Island/video/intro/part2.smk", size = 10)
        fs.file("/roms/scummvm/Day of the Tentacle/TENTACLE.000", size = 10)

        val games = scan("scummvm", "/roms/scummvm").games
        assertEquals(listOf("Day of the Tentacle", "Monkey Island"), games.map { it.title }.sorted())
        assertEquals("/roms/scummvm/Monkey Island", games.byTitle("Monkey Island").path)
    }

    // Cemu's own folder copied into the library: its installed title, and none of its other folders.
    @Test
    fun emulatorSystemFoldersDoNotBecomeGames() = runTest {
        val mlc = "/roms/wiiu/mlc01"
        fs.file("$mlc/usr/title/00050000/101c9500/code/U-King.rpx", size = 100)
        fs.file("$mlc/usr/title/00050000/101c9500/meta/meta.xml", size = 10)
        fs.file("$mlc/usr/title/00050000/101c9500/content/data.bin", size = 10)
        for (i in 1..40) fs.file("$mlc/usr/save/00050000/1010ed00/user/8000000$i/save.dat", size = 10)
        fs.file("$mlc/sys/title/0005001b/10056000/content/x.bin", size = 10)
        fs.file("$mlc/usr/boss/00050000/a/b.dat", size = 10)

        val games = scan("wiiu", "/roms/wiiu").games
        assertEquals(listOf("$mlc/usr/title/00050000/101c9500"), games.map { it.path })
    }

    @Test
    fun pcGameFolderPicksTheOneRealProgram() = runTest {
        fs.file("/roms/win/Hollow Knight/hollow_knight.exe")
        fs.file("/roms/win/Hollow Knight/UnityCrashHandler64.exe")
        fs.file("/roms/win/Hollow Knight/unins000.exe")
        fs.file("/roms/win/Hollow Knight/hollow_knight_Data/level1")
        fs.file("/roms/win/Portal.desktop", content = "[Desktop Entry]\nExec=wine portal.exe\n")

        val games = scan("win", "/roms/win").games
        assertEquals("/roms/win/Hollow Knight/hollow_knight.exe", games.byTitle("Hollow Knight").launchPath)
        assertEquals(LocationKind.FILE, games.byTitle("Portal").kind)
    }

    @Test
    fun pcGameFolderWithAmbiguousProgramsLaunchesTheFolder() = runTest {
        fs.file("/roms/dos/Doom/DOOM.EXE")
        fs.file("/roms/dos/Doom/SETUP.EXE")
        fs.file("/roms/dos/Doom/DEICE.EXE")
        val game = scan("dos", "/roms/dos").games.single()
        assertEquals("/roms/dos/Doom/DOOM.EXE", game.launchPath)
        fs.file("/roms/dos/Commander Keen/KEEN4.EXE")
        fs.file("/roms/dos/Commander Keen/KEEN5.EXE")
        assertEquals("/roms/dos/Commander Keen", scan("dos", "/roms/dos").games.byTitle("Commander Keen").launchPath)
    }

    @Test
    fun folderAsGamePolicyMakesEveryFolderOneGame() = runTest {
        fs.file("/roms/snes/Collection/Alpha.sfc")
        fs.file("/roms/snes/Collection/Beta Quest.sfc")
        fs.file("/roms/snes/Collection/Gamma.sfc")
        val policies = FolderPolicyResolver.of(platformPolicies = mapOf(PlatformId("snes") to FolderPolicy.FOLDER_AS_GAME))
        val game = scan("snes", "/roms/snes", policies).games.single()
        assertEquals("Collection", game.title)
        assertEquals("/roms/snes/Collection/Alpha.sfc", game.launchPath)
        assertEquals(2, game.content.size)
    }

    @Test
    fun folderAsGameWithoutFilesLaunchesTheFolder() = runTest {
        fs.file("/roms/xbox360/Arcade Game/4D5307E6/000D0000/ABCDEF0123")
        val game = scan("xbox360", "/roms/xbox360").games.single()
        assertEquals("/roms/xbox360/Arcade Game", game.launchPath)
        assertEquals(FolderInterpretation.FOLDER_IS_GAME, game.interpretation)
    }

    @Test
    fun folderBrowserPolicyReturnsOneBrowsableEntry() = runTest {
        fs.file("/roms/snes/Collection/Alpha.sfc")
        fs.file("/roms/snes/Collection/Beta Quest.sfc")
        val policies = FolderPolicyResolver.of(platformPolicies = mapOf(PlatformId("snes") to FolderPolicy.FOLDER_BROWSER))
        val game = scan("snes", "/roms/snes", policies).games.single()
        assertEquals(FolderInterpretation.FOLDER_BROWSER, game.interpretation)
        assertEquals("/roms/snes/Collection", game.launchPath)
    }

    @Test
    fun filePolicyOnlyCountsFiles() = runTest {
        val game = "/roms/gba/Pokemon Emerald"
        fs.file("$game/Pokemon Emerald (USA).gba")
        fs.file("$game/hacks/Emerald Kaizo.gba")
        fs.file("$game/manuals/manual.pdf")
        val policies = FolderPolicyResolver.of(global = FolderPolicy.FILE)
        val games = scan("gba", "/roms/gba", policies).games
        assertEquals(setOf("Pokemon Emerald (USA)", "Emerald Kaizo"), games.map { it.title }.toSet())
        assertTrue(games.all { it.kind == LocationKind.FILE })
    }

    @Test
    fun perPathOverridesWinAndInherit() = runTest {
        fs.file("/roms/snes/Collection/Alpha.sfc")
        fs.file("/roms/snes/Collection/Beta Quest.sfc")
        fs.file("/roms/snes/Other/One.sfc")
        fs.file("/roms/snes/Other/Two.sfc")
        fs.file("/roms/snes/Other/Deep/Three.sfc")
        fs.file("/roms/snes/Other/Deep/Four.sfc")
        val policies = FolderPolicyResolver.of(
            overrides = mapOf(
                "/roms/snes/Collection" to FolderPolicy.FOLDER_AS_GAME,
                "/roms/snes/Other/" to FolderPolicy.FILE,
            ),
        )
        val games = scan("snes", "/roms/snes", policies).games
        assertEquals(setOf("Collection", "One", "Two", "Three", "Four"), games.map { it.title }.toSet())
        assertEquals(FolderPolicy.FILE, policies.policyFor(PlatformId("snes"), "/roms/snes/Other/Deep"))
        assertEquals(FolderPolicy.AUTO, policies.policyFor(PlatformId("snes"), "/roms/snes/Else"))
    }

    @Test
    fun looseSwitchUpdatesAndDlcAttachToTheirBaseGame() = runTest {
        fs.file("/roms/switch/Zelda BOTW [01007EF00011E000][v0].nsp")
        fs.file("/roms/switch/Zelda BOTW [01007EF00011E800][v786432].nsp")
        fs.file("/roms/switch/Zelda BOTW - Master Trials [01007EF00011F001].nsp")
        fs.file("/roms/switch/Mario Kart 8 Deluxe.xci")
        fs.file("/roms/switch/Mario Kart 8 Deluxe [UPD].nsp")
        fs.file("/roms/switch/Orphan [DLC].nsp")

        val games = scan("switch", "/roms/switch").games
        assertEquals(3, games.size)
        val zelda = games.byTitle("Zelda BOTW [01007EF00011E000][v0]")
        assertEquals(listOf(ContentKind.DLC, ContentKind.UPDATE), zelda.content.map { it.kind }.sorted())
        assertEquals(listOf(ContentKind.UPDATE), games.byTitle("Mario Kart 8 Deluxe").content.map { it.kind })
        assertTrue(games.byTitle("Orphan [DLC]").content.isEmpty())
    }

    @Test
    fun looseSwitchFilesInsideAGameFolderBecomeChildren() = runTest {
        val game = "/roms/switch/Splatoon 3"
        fs.file("$game/Splatoon 3.nsp")
        fs.file("$game/Splatoon 3 [UPD].nsp")
        fs.file("$game/Splatoon 3 - Expansion Pass [DLC].nsp")
        val single = scan("switch", "/roms/switch").games.single()
        assertEquals("$game/Splatoon 3.nsp", single.launchPath)
        assertEquals(setOf(ContentKind.UPDATE, ContentKind.DLC), single.content.map { it.kind }.toSet())
    }

    @Test
    fun excludedHiddenAndBiosEntriesAreSkipped() = runTest {
        fs.file("/roms/arcade/sf2.zip")
        fs.file("/roms/arcade/neogeo.zip")
        fs.file("/roms/arcade/images/sf2.png")
        fs.file("/roms/arcade/.hidden/secret.zip")
        fs.file("/roms/arcade/@eaDir/sf2.zip")
        fs.file("/roms/arcade/videos/sf2.mp4")
        val games = scan("arcade", "/roms/arcade").games
        assertEquals(listOf("sf2"), games.map { it.title })
    }

    @Test
    fun symlinkLoopsAreNotFollowedTwice() = runTest {
        fs.file("/roms/snes/Hacks/A.sfc")
        fs.file("/roms/snes/Hacks/B Game.sfc")
        fs.symlink("/roms/snes/Loop", "/roms/snes")
        fs.symlink("/roms/snes/Hacks/Again", "/roms/snes/Hacks")
        val result = scan("snes", "/roms/snes")
        assertEquals(setOf("A", "B Game"), result.games.map { it.title }.toSet())
        assertEquals(2, result.games.size)
    }

    @Test
    fun depthLimitStopsDeepWalks() = runTest {
        fs.file("/roms/snes/a/b/c/Deep Game.sfc")
        fs.file("/roms/snes/a/Shallow.sfc")
        fs.file("/roms/snes/a/Other Shallow.sfc")
        val shallow = scan("snes", "/roms/snes", options = ScanOptions(maxDepth = 2)).games
        assertEquals(setOf("Shallow", "Other Shallow"), shallow.map { it.title }.toSet())
        val deep = scan("snes", "/roms/snes", options = ScanOptions(maxDepth = 4)).games
        assertTrue(deep.any { it.title == "Deep Game" })
    }

    @Test
    fun unreadableSubfolderMakesTheScanIncomplete() = runTest {
        fs.file("/roms/gba/Good.gba")
        fs.file("/roms/gba/Locked/Secret.gba")
        fs.makeUnreadable("/roms/gba/Locked")
        val result = scan("gba", "/roms/gba")
        assertFalse(result.complete)
        assertEquals(listOf("Good"), result.games.map { it.title })
        assertTrue(result.errors.single().contains("/roms/gba/Locked"))
    }

    @Test
    fun unrecognisedFolderOnFolderNativePlatformIsStillAGame() = runTest {
        fs.file("/roms/ps3/Weird Dump/stuff.dat")
        val game = scan("ps3", "/roms/ps3").games.single()
        assertEquals("/roms/ps3/Weird Dump", game.launchPath)
        assertEquals(FolderInterpretation.FOLDER_IS_GAME, game.interpretation)
        // On a file-based platform an empty folder yields nothing.
        fs.file("/roms/snes/Empty/notes.txt")
        assertTrue(scan("snes", "/roms/snes").games.isEmpty())
    }

    @Test
    fun mainFilePrefersCleanDumpOverHackAndBeta() = runTest {
        val game = "/roms/snes/Chrono Trigger"
        fs.file("$game/Chrono Trigger (USA) (Beta).sfc")
        fs.file("$game/Chrono Trigger (USA) [h1].sfc")
        fs.file("$game/Chrono Trigger (USA) [!].sfc")
        val single = scan("snes", "/roms/snes").games.single()
        assertEquals("$game/Chrono Trigger (USA) [!].sfc", single.launchPath)
        assertEquals(setOf(ContentKind.PROTOTYPE, ContentKind.HACK), single.content.map { it.kind }.toSet())
    }
}
