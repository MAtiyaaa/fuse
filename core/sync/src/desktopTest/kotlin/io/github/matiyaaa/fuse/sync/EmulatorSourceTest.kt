package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Where each emulator really keeps a game's save, as its own source code decides it (the file and
 * function are named on each adapter). One case per thing the source says that Fuse once got wrong.
 */
class EmulatorSourceTest {
    private class FakeEnv(
        override val host: String,
        override val home: String,
        val files: Map<String, String> = emptyMap(),
        val bin: Map<String, ByteArray> = emptyMap(),
        val vars: Map<String, String> = emptyMap(),
    ) : SaveEnvironment {
        private val all get() = files.keys + bin.keys
        override fun env(name: String) = vars[name]
        override fun exists(path: String) = path in all || all.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = all.any { it.startsWith("$path/") } || files[path] == ""
        override fun list(path: String) = all.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.filter { it.isNotEmpty() }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
        override fun readBytes(path: String, offset: Long, length: Int): ByteArray? {
            val b = bin[path] ?: return null
            if (offset >= b.size) return null
            return b.copyOfRange(offset.toInt(), minOf(b.size, offset.toInt() + length))
        }
    }

    private fun q(platform: String, rom: String, emulator: String, serial: String? = null, title: String = "", core: String? = null, emuPath: String? = null) =
        SaveQuery(GameKey.of(platform, null, null, title.ifEmpty { "Game" }), platform, rom, emulator, emulatorPath = emuPath, core = core, serial = serial, title = title)

    @Test
    fun retroArchSortsByContentAloneWithoutTheCoresFolder() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf(
            "/home/mo/.config/retroarch/retroarch.cfg" to "savefile_directory = \"~/Saves\"\nsort_savefiles_by_content_enable = \"true\"\nsort_savefiles_enable = \"false\"\n",
            "/home/mo/Saves/" to "",
        ))
        val save = SaveAdapters.forEmulator("retroarch")!!.locate(q("snes", "/roms/snes/CT.sfc", "retroarch", core = "snes9x_libretro"), env).first()
        assertEquals("/home/mo/Saves/snes/CT.srm", save.pathFor("save.srm"))
    }

    @Test
    fun retroArchSortsBesideTheGameToo() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.config/retroarch/retroarch.cfg" to "savefiles_in_content_dir = \"true\"\nsort_savefiles_enable = \"true\"\n", "/roms/snes/x" to "x"))
        val save = SaveAdapters.forEmulator("retroarch")!!.locate(q("snes", "/roms/snes/CT.sfc", "retroarch", core = "snes9x_libretro"), env).first()
        assertEquals("/roms/snes/Snes9x/CT.srm", save.pathFor("save.srm"))
    }

    @Test
    fun retroArchOnAMacSavesInDocumentsUnlessPortable() {
        val cfg = "/Users/mo/Library/Application Support/RetroArch/config/retroarch.cfg"
        val env = FakeEnv("MACOS", "/Users/mo", mapOf(cfg to "savefile_directory = \"default\"\n", "/Users/mo/Documents/RetroArch/saves/" to ""))
        val save = SaveAdapters.forEmulator("macos.retroarch")!!.locate(q("gba", "/roms/gba/Zelda.gba", "macos.retroarch"), env).first()
        assertEquals("/Users/mo/Documents/RetroArch/saves/Zelda.srm", save.pathFor("save.srm"))

        val steam = SaveAdapters.forEmulator("macos.retroarch-steam")!!.locate(q("gba", "/roms/gba/Zelda.gba", "macos.retroarch-steam"), env).first()
        assertEquals("/Users/mo/Library/Application Support/RetroArch/saves/Zelda.srm", steam.pathFor("save.srm"))
    }

    @Test
    fun retroArchSavesBesideTheGameWhenItsFolderIsEmpty() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.config/retroarch/retroarch.cfg" to "savefile_directory = \"\"\n", "/roms/gba/x" to "x"))
        val save = SaveAdapters.forEmulator("retroarch")!!.locate(q("gba", "/roms/gba/Zelda.gba", "retroarch"), env).first()
        assertEquals("/roms/gba/Zelda.srm", save.pathFor("save.srm"))
    }

    @Test
    fun vita3kPortableAndItsOwnPrefPath() {
        val portable = FakeEnv("WINDOWS", "C:/Users/mo", mapOf("C:/Emu/Vita3K/portable/fs/ux0/user/" to ""))
        val p = SaveAdapters.forEmulator("windows.vita3k")!!.locate(q("psvita", "/roms/vita/Persona.vpk", "windows.vita3k", serial = "PCSE-00120", emuPath = "C:/Emu/Vita3K/Vita3K.exe"), portable).single()
        assertEquals("C:/Emu/Vita3K/portable/fs/ux0/user/00/savedata", p.root)
        assertEquals(listOf("PCSE00120"), p.folders)

        val moved = FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.config/Vita3K/config.yml" to "pref-path: /mnt/games/vita/\n", "/mnt/games/vita/ux0/" to ""))
        assertEquals("/mnt/games/vita/ux0/user/00/savedata", SaveAdapters.forEmulator("vita3k")!!.locate(q("psvita", "/x.vpk", "vita3k", serial = "PCSE00120"), moved).single().root)

        val mac = FakeEnv("MACOS", "/Users/mo", mapOf("/Users/mo/Library/Application Support/Vita3K/Vita3K/fs/ux0/" to ""))
        assertEquals("/Users/mo/Library/Application Support/Vita3K/Vita3K/fs/ux0/user/00/savedata", SaveAdapters.forEmulator("vita3k")!!.locate(q("psvita", "/x.vpk", "vita3k", serial = "PCSE00120"), mac).single().root)

        assertFalse(SaveAdapters.forEmulator("vita3k")!!.locate(q("psvita", "/x.vpk", "vita3k"), FakeEnv("ANDROID", "/storage/emulated/0")).single().available)
    }

    @Test
    fun shadPs4ReadsTheNewerPerUserFolderAndTheOlderOne() {
        val user = "/home/mo/.local/share/shadPS4"
        val newer = FakeEnv("LINUX", "/home/mo", mapOf("$user/home/1000/savedata/CUSA00207/SPRJ0005/" to ""))
        assertEquals("$user/home/1000/savedata", SaveAdapters.forEmulator("shadps4")!!.locate(q("ps4", "/x.pkg", "shadps4", serial = "CUSA00207"), newer).single().root)

        val older = FakeEnv("LINUX", "/home/mo", mapOf("$user/savedata/1/CUSA00207/SPRJ0005/" to ""))
        assertEquals("$user/savedata/1", SaveAdapters.forEmulator("shadps4")!!.locate(q("ps4", "/x.pkg", "shadps4", serial = "CUSA-00207"), older).single().root)

        // A fresh install with no saves yet: the newer layout, where it will make them.
        val fresh = FakeEnv("LINUX", "/home/mo", mapOf("$user/home/" to ""))
        assertEquals("$user/home/1000/savedata", SaveAdapters.forEmulator("shadps4")!!.locate(q("ps4", "/x.pkg", "shadps4", serial = "CUSA00207"), fresh).single().root)
    }

    /** A GD-ROM's first high density track, raw sectors, with the boot header Flycast reads. */
    private fun track3(product: String): ByteArray {
        val b = ByteArray(2352)
        "SEGA SEGAKATANA SEGA ENTERPRISES".encodeToByteArray().copyInto(b, 16)
        product.padEnd(10).encodeToByteArray().copyInto(b, 16 + 0x40)
        return b
    }

    @Test
    fun flycastGivesEachGameItsOwnVmuNamedByItsProductNumber() {
        val data = "/home/mo/.local/share/flycast"
        val gdi = "3\n1 0 4 2352 track01.bin 0\n2 600 0 2352 track02.raw 0\n3 45000 4 2352 track03.bin 0\n"
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("$data/" to "", "/roms/dc/Sonic/Sonic.gdi" to gdi), bin = mapOf("/roms/dc/Sonic/track03.bin" to track3("MK-51000")))
        val spot = SaveAdapters.forEmulator("flycast")!!.locate(q("dc", "/roms/dc/Sonic/Sonic.gdi", "flycast"), env).single { it.kind != SaveKind.STATE }
        assertEquals("$data/MK-51000_vmu_save_A1.bin", spot.pathFor("vmu_save_A1.bin"))
        // Never mixed with a VMU every game shares.
        assertEquals("dc.vmu.game", spot.format)

        // With Per-game VMU off, the one shared VMU, the same kind as Redream's.
        val shared = FakeEnv("LINUX", "/home/mo", files = mapOf("$data/" to "", "/home/mo/.config/flycast/emu.cfg" to "[config]\nPerGameVmu = no\n"))
        val s = SaveAdapters.forEmulator("flycast")!!.locate(q("dc", "/roms/dc/Sonic/Sonic.chd", "flycast"), shared).single { it.kind != SaveKind.STATE }
        assertEquals("$data/vmu_save_A1.bin", s.pathFor("vmu_save_A1.bin"))
        assertEquals("dc.vmu", s.format)

        // A CHD's header can't be read: it says so rather than guessing a name.
        assertFalse(SaveAdapters.forEmulator("flycast")!!.locate(q("dc", "/roms/dc/Sonic.chd", "flycast"), FakeEnv("LINUX", "/home/mo", files = mapOf("$data/" to ""))).single { it.kind != SaveKind.STATE }.available)
    }

    @Test
    fun flycastFollowsItsVmuPathAndItsMacDataFolder() {
        val env = FakeEnv("MACOS", "/Users/mo", files = mapOf(
            "/Users/mo/Library/Application Support/Flycast/emu.cfg" to "[config]\nDreamcast.VMUPath = /Volumes/Cards\nPerGameVmu = no\n",
            "/Volumes/Cards/" to "",
        ))
        assertEquals("/Volumes/Cards/vmu_save_A1.bin", SaveAdapters.forEmulator("flycast")!!.locate(q("dc", "/x.chd", "flycast"), env).single { it.kind != SaveKind.STATE }.pathFor("vmu_save_A1.bin"))
        val mac = FakeEnv("MACOS", "/Users/mo", files = mapOf("/Users/mo/Library/Application Support/Flycast/data/" to "", "/Users/mo/Library/Application Support/Flycast/emu.cfg" to "[config]\nPerGameVmu = no\n"))
        assertEquals("/Users/mo/Library/Application Support/Flycast/data/vmu_save_A1.bin", SaveAdapters.forEmulator("flycast")!!.locate(q("dc", "/x.chd", "flycast"), mac).single { it.kind != SaveKind.STATE }.pathFor("vmu_save_A1.bin"))
    }

    @Test
    fun dreamcastProductNumbersFromPlainImagesToo() {
        val env = FakeEnv("LINUX", "/home/mo", bin = mapOf("/roms/x.iso" to track3("T-8101N").copyOfRange(16, 2352)))
        assertEquals("T-8101N", DreamcastDisc.productNumber(env, "/roms/x.iso"))
        assertNull(DreamcastDisc.productNumber(FakeEnv("LINUX", "/home/mo", bin = mapOf("/roms/y.iso" to ByteArray(200))), "/roms/y.iso"))
    }

    @Test
    fun playKeepsItsCardInVfsMc0() {
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("/home/mo/.local/share/Play Data Files/config.xml" to "<Config/>"))
        assertEquals("/home/mo/.local/share/Play Data Files/vfs/mc0", SaveAdapters.forEmulator("play")!!.locate(q("ps2", "/x.iso", "play"), env).single().root)
        // On Linux, XDG_CONFIG_HOME when it is set (Play!'s PathUtils).
        val xdg = FakeEnv("LINUX", "/home/mo", files = mapOf("/cfg/Play Data Files/" to ""), vars = mapOf("XDG_CONFIG_HOME" to "/cfg"))
        assertEquals("/cfg/Play Data Files/vfs/mc0", SaveAdapters.forEmulator("play")!!.locate(q("ps2", "/x.iso", "play"), xdg).single().root)
        val moved = FakeEnv("WINDOWS", "C:/Users/mo", files = mapOf(
            "C:/Users/mo/Documents/Play Data Files/config.xml" to "<Config><Preference Name=\"ps2.mc0.directory\" Type=\"path\" Value=\"D:/PS2/mc0\"/></Config>",
            "D:/PS2/mc0/" to "",
        ))
        assertEquals("D:/PS2/mc0", SaveAdapters.forEmulator("play")!!.locate(q("ps2", "/x.iso", "play"), moved).single().root)
    }

    @Test
    fun cemuPortableAndItsOwnMlcPath() {
        val portable = FakeEnv("WINDOWS", "C:/Users/mo", files = mapOf("C:/Emu/Cemu/portable/mlc01/" to ""))
        val p = SaveAdapters.forEmulator("cemu")!!.locate(q("wiiu", "/x.wua", "cemu", serial = "000500001010EC00", emuPath = "C:/Emu/Cemu/Cemu.exe"), portable).single()
        assertEquals("C:/Emu/Cemu/portable/mlc01/usr/save/00050000/1010ec00/user", p.root)

        val moved = FakeEnv("LINUX", "/home/mo", files = mapOf(
            "/home/mo/.local/share/Cemu/" to "",
            "/home/mo/.config/Cemu/settings.xml" to "<content><mlc_path>/mnt/wiiu/mlc</mlc_path></content>",
            "/mnt/wiiu/mlc/usr/" to "",
        ))
        assertEquals("/mnt/wiiu/mlc/usr/save/00050000/1010ec00/user", SaveAdapters.forEmulator("cemu")!!.locate(q("wiiu", "/x.wua", "cemu", serial = "000500001010EC00"), moved).single().root)
    }

    @Test
    fun xeniaOnLinuxFromAnOlderBuildInTheHomeFolderSkipsTheSharedProfile() {
        val content = "/home/mo/Xenia/content"
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("$content/0000000000000000/4D5307E6/00000002/" to "", "$content/E030000012345678/" to ""))
        val spot = SaveAdapters.forEmulator("xenia")!!.locate(q("xbox360", "/roms/Halo 3 [4D5307E6].iso", "xenia"), env).single()
        assertEquals("$content/E030000012345678/4D5307E6/00000001", spot.root)
    }

    @Test
    fun ryujinxTakesTheGamesAccountSaveOverItsCache() {
        val saves = "/home/mo/.config/Ryujinx/bis/user/save"
        fun extra(type: Int) = ByteArray(0x40).also { b ->
            byteArrayOf(0x00, 0xE0.toByte(), 0x11, 0x00, 0xF0.toByte(), 0x7E, 0x00, 0x01).copyInto(b)
            b[0x20] = type.toByte()
        }
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("$saves/0000000000000001/0/" to "", "$saves/0000000000000002/0/" to ""),
            bin = mapOf("$saves/0000000000000001/ExtraData0" to extra(5), "$saves/0000000000000002/ExtraData0" to extra(1)))
        val spot = SaveAdapters.forEmulator("ryujinx")!!.locate(q("switch", "/roms/Zelda [01007EF00011E000].nsp", "ryujinx"), env).single()
        assertEquals("$saves/0000000000000002/0", spot.root)
    }

    @Test
    fun mupenFindsASaveUnderItsShortenedGoodName() {
        val dir = "/home/mo/.local/share/mupen64plus/save"
        // "Star Wars - Shadows of the Empire (U) [!]", cut to its first 32 characters, then the MD5's first 8.
        val name = "Star Wars - Shadows of the Empir-1B4F0D6F"
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("$dir/$name.eep" to "x"))
        val spot = SaveAdapters.forEmulator("mupen64plus")!!.locate(q("n64", "/roms/sote.z64", "mupen64plus", title = "Star Wars: Shadows of the Empire"), env).single()
        assertEquals("$dir/$name.eep", spot.pathFor("save.eep"))
        // A different game that only shares a start isn't taken for it.
        val other = SaveAdapters.forEmulator("mupen64plus")!!.locate(q("n64", "/roms/x.z64", "mupen64plus", title = "Star Wars"), env).single()
        assertFalse(other.available)
    }

    @Test
    fun mameReadsItsNvramFolderFromMameIni() {
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("/home/mo/.mame/mame.ini" to "#\n# CORE SEARCH PATH OPTIONS\n#\nnvram_directory          \$HOME/.mame/nv\n"))
        val spot = SaveAdapters.forEmulator("mame")!!.locate(q("arcade", "/roms/arcade/pacman.zip", "mame"), env).single { it.kind != SaveKind.STATE }
        assertEquals("/home/mo/.mame/nv", spot.root)
        assertEquals(listOf("pacman"), spot.folders)
    }

    @Test
    fun scummVmFollowsItsSavepathAndPortableFolder() {
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("/home/mo/.config/scummvm/scummvm.ini" to "[scummvm]\nsavepath=/data/scummvm saves\n", "/data/scummvm saves/" to ""))
        assertEquals("/data/scummvm saves", SaveAdapters.forEmulator("scummvm")!!.locate(q("scummvm", "/g/monkey", "scummvm"), env).single().root)
        val portable = FakeEnv("WINDOWS", "C:/Users/mo", files = mapOf("C:/ScummVM/Saved games/" to ""))
        assertEquals("C:/ScummVM/Saved games", SaveAdapters.forEmulator("scummvm")!!.locate(q("scummvm", "/g/monkey", "scummvm", emuPath = "C:/ScummVM/scummvm.exe"), portable).single().root)
    }

    @Test
    fun ePsxeOnWindowsKeepsCardsBesideIt() {
        val env = FakeEnv("WINDOWS", "C:/Users/mo", files = mapOf("C:/ePSXe/memcards/epsxe000.mcr" to "x"))
        assertEquals("C:/ePSXe/memcards/epsxe000.mcr", SaveAdapters.forEmulator("epsxe")!!.locate(q("psx", "/x.cue", "epsxe", emuPath = "C:/ePSXe/ePSXe.exe"), env).single().pathFor("card1.mcr"))
    }
}
