package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MoreAdaptersTest {
    /** A device's files: text, bytes ([bin]) and folders ("" in [files]), plus the save folders the person chose. */
    private class FakeEnv(
        override val host: String,
        override val home: String,
        val files: Map<String, String> = emptyMap(),
        val bin: Map<String, ByteArray> = emptyMap(),
        val chosen: Map<String, String> = emptyMap(),
        val roots: List<String> = emptyList(),
        val times: Map<String, Long> = emptyMap(),
        val learnedMap: Map<String, List<String>> = emptyMap(),
    ) : SaveEnvironment {
        override fun storageRoots() = roots
        override fun modified(path: String) = times[path] ?: if (exists(path)) 0L else null
        override fun learned(game: String, format: String) = learnedMap["$game|$format"].orEmpty()
        private val all get() = files.keys + bin.keys
        override fun exists(path: String) = path in all || all.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = all.any { it.startsWith("$path/") } || files[path] == ""
        override fun list(path: String) = all.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.filter { it.isNotEmpty() }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
        override fun readBytes(path: String, offset: Long, length: Int): ByteArray? {
            val b = bin[path] ?: return null
            if (offset >= b.size) return null
            return b.copyOfRange(offset.toInt(), minOf(b.size, offset.toInt() + length))
        }
        override fun saveFolder(emulator: String) = chosen[emulator]
    }

    private fun q(platform: String, rom: String, emulator: String, serial: String? = null, title: String = "") =
        SaveQuery(GameKey.of(platform, null, null, title.ifEmpty { "Game" }), platform, rom, emulator, serial = serial, title = title)

    @Test
    fun dsSavesOnTheThorAreFoundWhereTheEmulatorsPutThem() {
        val mama = q("nds", "/storage/emulated/0/Emulation/roms/nds/Cooking Mama.nds", "melonds")
        // melonDS with its save folder set in the person's storage.
        val melon = FakeEnv("ANDROID", "/storage/emulated/0", files = mapOf("/storage/emulated/0/melonDS/saves/Cooking Mama.sav" to "x"))
        assertEquals("/storage/emulated/0/melonDS/saves/Cooking Mama.sav", SaveAdapters.forEmulator("melonds")!!.locate(mama, melon).single().files.single().path)
        // Nothing found: the note names melonDS's own settings, not only Fuse's.
        val nothing = SaveAdapters.forEmulator("melonds")!!.locate(mama, FakeEnv("ANDROID", "/storage/emulated/0")).single()
        assertTrue("ROM search folders" in nothing.note!! && "save folder" in nothing.note!!, nothing.note)
        // DraStic's folder on an SD card.
        val card = "/storage/1234-ABCD"
        val drastic = FakeEnv("ANDROID", "/storage/emulated/0", files = mapOf("$card/DraStic/backup/" to ""), roots = listOf(card))
        assertEquals("$card/DraStic/backup/Cooking Mama.dsv", SaveAdapters.forEmulator("drastic")!!.locate(mama.copy(emulatorId = "drastic"), drastic).single().files.single().path)
    }

    @Test
    fun edenUserIdsReadTheSameInBothLayouts() {
        // Legacy: user_id[1] then user_id[0] as {:016X}; newer: the 16 bytes in order, lower case.
        val legacy = "0123456789ABCDEFFEDCBA9876543210"
        val raw = EdenUser.fromLegacy(legacy)
        assertEquals("1032547698badcfeefcdab8967452301", raw)
        assertEquals(legacy, EdenUser.toLegacy(raw))
        assertEquals("0100A3D008C5C000", EdenUser.masked("0100A3D008C5C0FF"))
    }

    @Test
    fun edenFindsTheNewerAccountLayoutTheThorMissed() {
        // The Thor: Eden's Save Data Directory chosen in Fuse; Eden keeps Red Dead Redemption in the newer layout.
        val chosen = "/storage/emulated/0/Emulation/saves/switch"
        val legacyUser = "37111DFF1B5BCC59550000000000000A"
        val raw = EdenUser.fromLegacy(legacyUser)
        val rdr = q("switch", "/roms/switch/[ROMsNS] Red Dead Redemption v0 (01007820196A6000) (BASE).nsp", "eden", serial = "01007820196A6000")
        val phone = FakeEnv("ANDROID", "/storage/emulated/0", files = mapOf(
            "$chosen/user/save/0000000000000000/$legacyUser/" to "",
            "$chosen/user/save/account/$raw/01007820196A6000/0/slot1.dat" to "x",
        ), chosen = mapOf("eden" to chosen))
        val spot = SaveAdapters.forEmulator("eden")!!.locate(rdr, phone).single()
        assertEquals("$chosen/user/save/account/$raw/01007820196A6000/0", spot.root)
        // Both layouts there: Eden prefers the newer one, and so does Fuse.
        val both = FakeEnv("ANDROID", "/storage/emulated/0", files = mapOf(
            "$chosen/user/save/0000000000000000/$legacyUser/01007820196A6000/old.dat" to "x",
            "$chosen/user/save/account/$raw/01007820196A6000/0/slot1.dat" to "x",
        ), chosen = mapOf("eden" to chosen))
        assertEquals("$chosen/user/save/account/$raw/01007820196A6000/0", SaveAdapters.forEmulator("eden")!!.locate(rdr, both).single().root)
        // The low byte of the title id is cleared in the newer layout.
        val masked = FakeEnv("ANDROID", "/storage/emulated/0", files = mapOf(
            "$chosen/user/save/account/$raw/0100A3D008C5C000/0/a" to "x",
        ), chosen = mapOf("eden" to chosen))
        assertEquals("$chosen/user/save/account/$raw/0100A3D008C5C000/0",
            SaveAdapters.forEmulator("eden")!!.locate(q("switch", "/r/g.nsp", "eden", serial = "0100A3D008C5C0FF"), masked).single().root)
    }

    @Test
    fun edenDeviceSavesAndOtherUsers() {
        val space = "/home/mo/.local/share/eden/nand/user/save"
        val game = q("switch", "/roms/switch/Game [0100000000010000].nsp", "eden")
        // A device save (not tied to a user), newer and legacy.
        val newer = FakeEnv("LINUX", "/home/mo", files = mapOf("$space/device/0100000000010000/0/d" to "x"))
        assertEquals("$space/device/0100000000010000/0", SaveAdapters.forEmulator("eden")!!.locate(game, newer).single().mounts[SwitchNand.DEVICE])
        val legacy = FakeEnv("LINUX", "/home/mo", files = mapOf("$space/0000000000000000/00000000000000000000000000000000/0100000000010000/d" to "x"))
        assertEquals("$space/0000000000000000/00000000000000000000000000000000/0100000000010000", SaveAdapters.forEmulator("eden")!!.locate(game, legacy).single().mounts[SwitchNand.DEVICE])
        // An account save and a device save of one game: both, as one save, never one left behind.
        val u = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"
        val both = FakeEnv("LINUX", "/home/mo", files = mapOf(
            "$space/0000000000000000/$u/0100000000010000/a" to "x",
            "$space/device/0100000000010000/0/d" to "x",
        ))
        val bothSpot = SaveAdapters.forEmulator("eden")!!.locate(game, both).single()
        assertEquals("$space/0000000000000000/$u/0100000000010000", bothSpot.root)
        assertEquals("$space/device/0100000000010000/0", bothSpot.mounts[SwitchNand.DEVICE])
        assertEquals("$space/device/0100000000010000/0/sub/d.bin", bothSpot.pathFor("@device/sub/d.bin"))
        // Two users, the game saved by only one: that one, never the first in the list.
        val a = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val b = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        val two = FakeEnv("LINUX", "/home/mo", files = mapOf(
            "$space/0000000000000000/$a/" to "",
            "$space/0000000000000000/$b/0100000000010000/s" to "x",
        ))
        assertEquals("$space/0000000000000000/$b/0100000000010000", SaveAdapters.forEmulator("eden")!!.locate(game, two).single().root)
        // Two users, neither has it, no profile selected: Fuse doesn't guess.
        val neither = FakeEnv("LINUX", "/home/mo", files = mapOf("$space/0000000000000000/$a/" to "", "$space/0000000000000000/$b/" to ""))
        val none = SaveAdapters.forEmulator("eden")!!.locate(game, neither).single()
        assertFalse(none.available)
        assertTrue("more than one user" in none.note!!, none.note)
        // The config's current user picks, through Eden's profile list (its second entry here).
        val profiles = ByteArray(0x10 + 2 * 0xC8).also { bytes ->
            EdenUser.fromLegacy(b).chunked(2).forEachIndexed { i, h -> bytes[0x10 + 0xC8 + i] = h.toInt(16).toByte() }
        }
        val selected = FakeEnv("LINUX", "/home/mo", files = mapOf(
            "/home/mo/.config/eden/qt-config.ini" to "[System]\ncurrent_user=1\n",
            "$space/0000000000000000/$a/0100000000010000/s" to "x",
            "$space/0000000000000000/$b/" to "",
        ), bin = mapOf("/home/mo/.local/share/eden/nand/system/save/8000000000000010/su/avators/profiles.dat" to profiles))
        val spot = SaveAdapters.forEmulator("eden")!!.locate(game, selected).single()
        assertEquals("$space/0000000000000000/$b/0100000000010000", spot.root, "the selected user's place, not user A's save")
        assertTrue("may not have saved yet" in spot.note!!, spot.note)
    }

    @Test
    fun edenSavesWhereItsConfigOrThePersonPutThem() {
        val user = "0123456789ABCDEF0123456789ABCDEF"
        val zelda = q("switch", "/roms/switch/Zelda [01007EF00011E000].nsp", "eden")
        // A save data folder of its own, named in Eden's config (as its Data Storage settings write it).
        val saves = "/mnt/games/eden-saves"
        val config = "[Data%20Storage]\nnand_directory\\default=true\nnand_directory=\nsave_directory\\default=false\nsave_directory=$saves/\n[UI]\nsave_directory=/elsewhere\n"
        // On Linux its config is in the XDG config folder, apart from its data (common/fs/path_util.cpp).
        val desktop = FakeEnv("LINUX", "/home/mo", files = mapOf(
            "/home/mo/.config/eden/qt-config.ini" to config,
            "/home/mo/.local/share/eden/nand/user/save/0000000000000000/$user/" to "",
            "$saves/user/save/0000000000000000/$user/01007EF00011E000/" to "",
        ))
        assertEquals("$saves/user/save/0000000000000000/$user/01007EF00011E000", SaveAdapters.forEmulator("eden")!!.locate(zelda, desktop).single().root)
        // The Flatpak keeps both in its own folder.
        val flatpak = FakeEnv("LINUX", "/home/mo", files = mapOf(
            "/home/mo/.var/app/dev.eden_emu.eden/config/eden/qt-config.ini" to config,
            "/home/mo/.var/app/dev.eden_emu.eden/data/eden/nand/user/save/0000000000000000/$user/" to "",
            "$saves/user/save/0000000000000000/$user/01007EF00011E000/" to "",
        ))
        assertEquals("$saves/user/save/0000000000000000/$user/01007EF00011E000", SaveAdapters.forEmulator("eden")!!.locate(zelda, flatpak).single().root)
        // A NAND moved elsewhere, with saves left in it (an empty save_directory), on a portable Windows install.
        val nand = "D:/Switch/nand"
        val portable = FakeEnv("WINDOWS", "C:/Users/mo", files = mapOf(
            "C:/Emu/Eden/user/config/qt-config.ini" to "[Data%20Storage]\nnand_directory\\default=false\nnand_directory=D:\\Switch\\nand\\\nsave_directory=\n",
            "C:/Emu/Eden/user/nand/user/save/0000000000000000/$user/" to "",
            "$nand/user/save/0000000000000000/$user/01007EF00011E000/" to "",
        ))
        assertEquals("$nand/user/save/0000000000000000/$user/01007EF00011E000", SaveAdapters.forEmulator("eden")!!.locate(zelda.copy(emulatorPath = "C:/Emu/Eden/eden.exe"), portable).single().root)
        // On macOS it keeps the XDG folders too.
        val mac = FakeEnv("MACOS", "/Users/mo", files = mapOf("/Users/mo/.local/share/eden/nand/user/save/0000000000000000/$user/01007EF00011E000/" to ""))
        assertEquals("/Users/mo/.local/share/eden/nand/user/save/0000000000000000/$user/01007EF00011E000", SaveAdapters.forEmulator("eden")!!.locate(zelda, mac).single().root)
        // On Android, the folder chosen in Eden's Save Data Directory (or NAND Directory) and in Fuse.
        for (chosen in listOf("/storage/emulated/0/Eden/saves", "/storage/emulated/0/Eden/nand")) {
            val phone = FakeEnv("ANDROID", "/storage/emulated/0", files = mapOf("$chosen/user/save/0000000000000000/$user/01007EF00011E000/" to ""), chosen = mapOf("eden" to chosen))
            val spot = SaveAdapters.forEmulator("eden")!!.locate(zelda, phone).single()
            assertEquals("$chosen/user/save/0000000000000000/$user/01007EF00011E000", spot.root, chosen)
        }
        // Not chosen yet: it says which Eden setting to use.
        val none = SaveAdapters.forEmulator("eden")!!.locate(zelda, FakeEnv("ANDROID", "/storage/emulated/0")).single()
        assertFalse(none.available)
        assertTrue("Save Data Directory" in none.note!!, none.note)
    }

    @Test
    fun anyPs5PortsKeepTheirSavesInTheirOwnFolder() {
        val sarah = q("ps5", "/games/ps5/Dreaming Sarah/app.elf", "linux.anyps5", serial = "PPSA02929")
        val spot = SaveAdapters.forEmulator("linux.anyps5")!!.locate(sarah, FakeEnv("LINUX", "/home/mo")).single()
        assertEquals("anyps5.savedata", spot.format)
        assertEquals("/games/ps5/Dreaming Sarah", spot.root)
        assertEquals(listOf("_sd", "_sd_mem"), spot.folders)
        // The same game's Windows port takes the same save.
        val windows = SaveAdapters.forEmulator("windows.anyps5")!!.locate(sarah.copy(romPath = "D:/PS5/Sarah/app.exe", emulatorId = "windows.anyps5"), FakeEnv("WINDOWS", "C:/Users/mo")).single()
        assertEquals("D:/PS5/Sarah", windows.root)
        assertEquals(spot.format, windows.format)
    }

    @Test
    fun duckStationOnWindowsKeepsItsDataWhereItsOwnCodeSays() {
        val ff7 = q("psx", "C:/roms/psx/Final Fantasy VII.chd", "windows.duckstation", serial = "SCUS-94163", title = "Final Fantasy VII")
        fun card(env: FakeEnv) = SaveAdapters.forEmulator("windows.duckstation")!!.locate(ff7.copy(emulatorPath = "C:/Emu/DuckStation/duckstation-qt-x64-ReleaseLTCG.exe"), env).first().pathFor("card1.mcd")
        // A new install: %LOCALAPPDATA%\DuckStation.
        assertEquals("C:/Users/mo/AppData/Local/DuckStation/memcards/Final Fantasy VII_1.mcd", card(FakeEnv("WINDOWS", "C:/Users/mo", files = mapOf("C:/Users/mo/AppData/Local/DuckStation/memcards/" to ""))))
        // An older one still in Documents, which DuckStation keeps using.
        assertEquals("C:/Users/mo/Documents/DuckStation/memcards/Final Fantasy VII_1.mcd", card(FakeEnv("WINDOWS", "C:/Users/mo", files = mapOf("C:/Users/mo/Documents/DuckStation/memcards/" to "", "C:/Users/mo/AppData/Local/DuckStation/memcards/" to ""))))
        // Portable: settings.ini beside the program is enough.
        assertEquals("C:/Emu/DuckStation/memcards/Final Fantasy VII_1.mcd", card(FakeEnv("WINDOWS", "C:/Users/mo", files = mapOf("C:/Emu/DuckStation/settings.ini" to "", "C:/Emu/DuckStation/memcards/" to "", "C:/Users/mo/AppData/Local/DuckStation/memcards/" to ""))))
    }

    @Test
    fun duckStationsFlatpakKeepsItsDataInItsConfigFolder() {
        val ff7 = q("psx", "/roms/psx/Final Fantasy VII.chd", "linux.duckstation", serial = "SCUS-94163", title = "Final Fantasy VII")
        // Flatpak sets XDG_CONFIG_HOME inside its sandbox, so DuckStation's SetDataRoot picks config/duckstation.
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("/home/mo/.var/app/org.duckstation.DuckStation/config/duckstation/memcards/" to ""))
        assertEquals(
            "/home/mo/.var/app/org.duckstation.DuckStation/config/duckstation/memcards/Final Fantasy VII_1.mcd",
            SaveAdapters.forEmulator("linux.duckstation")!!.locate(ff7, env).first().pathFor("card1.mcd"),
        )
    }

    @Test
    fun rpcs3FollowsDevHdd0WhereItsVfsConfigMovedIt() {
        val game = q("ps3", "/roms/ps3/Demon's Souls/PS3_GAME/USRDIR/EBOOT.BIN", "rpcs3", serial = "BLUS30443")
        val base = "/home/mo/.config/rpcs3"
        val moved = "/mnt/big/ps3-hdd0"
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf(
            "$base/config/vfs.yml" to "\$(EmulatorDir): \"\"\n/dev_hdd0/: $moved/\n/dev_hdd1/: \$(EmulatorDir)dev_hdd1/\n",
            "$moved/home/00000001/savedata/BLUS30443-SAVE01/" to "",
        ))
        val spot = SaveAdapters.forEmulator("rpcs3")!!.locate(game, env).single()
        assertEquals("$moved/home/00000001/savedata", spot.root)
        assertEquals(listOf("BLUS30443-SAVE01"), spot.folders)
        // Left as RPCS3 comes: beside its config.
        val plain = FakeEnv("LINUX", "/home/mo", files = mapOf("$base/config/vfs.yml" to "/dev_hdd0: \$(EmulatorDir)dev_hdd0/\n", "$base/dev_hdd0/home/00000001/savedata/" to ""))
        assertEquals("$base/dev_hdd0/home/00000001/savedata", SaveAdapters.forEmulator("rpcs3")!!.locate(game, plain).single().root)
    }

    @Test
    fun kenjiNxIsRyujinxUnderneath() {
        assertEquals(SaveAdapters.forEmulator("ryujinx"), SaveAdapters.forEmulator("kenji-nx"))
        val chosen = "/storage/emulated/0/Kenji"
        val extra = attribute("01007EF00011E000", "0000000000000001", "0000000000000000", 1)
        val env = FakeEnv("ANDROID", "/storage/emulated/0", files = mapOf("$chosen/bis/user/save/0000000000000001/0/" to ""), bin = mapOf("$chosen/bis/user/save/0000000000000001/ExtraData0" to extra), chosen = mapOf("kenji-nx" to chosen))
        val spot = SaveAdapters.forEmulator("kenji-nx")!!.locate(q("switch", "/roms/switch/Zelda [01007EF00011E000].nsp", "kenji-nx"), env).single()
        assertEquals("$chosen/bis/user/save/0000000000000001/0", spot.root)
        val none = SaveAdapters.forEmulator("kenji-nx")!!.locate(q("switch", "/roms/switch/Zelda [01007EF00011E000].nsp", "kenji-nx"), FakeEnv("ANDROID", "/storage/emulated/0")).single()
        assertTrue("Kenji-NX" in none.note!!, none.note)
    }

    @Test
    fun draSticSavesInItsChosenFolder() {
        val env = FakeEnv("ANDROID", "/storage/emulated/0", files = mapOf("/storage/emulated/0/DraStic/backup/" to ""), chosen = mapOf("drastic" to "/storage/emulated/0/DraStic"))
        val spot = SaveAdapters.forEmulator("drastic")!!.locate(q("nds", "/roms/nds/Pokemon Platinum.nds", "drastic"), env).single()
        assertTrue(spot.available)
        assertEquals("nds.dsv", spot.format)
        assertEquals("/storage/emulated/0/DraStic/backup/Pokemon Platinum.dsv", spot.pathFor("save.srm"))
    }

    @Test
    fun anEmulatorFuseCantReachSaysWhatToDo() {
        val env = FakeEnv("ANDROID", "/storage/emulated/0")
        val spot = SaveAdapters.forEmulator("lemuroid")!!.locate(q("snes", "/roms/snes/CT.sfc", "lemuroid"), env).single()
        assertFalse(spot.available)
        assertTrue(spot.note!!.contains("private Android folder"))
    }

    @Test
    fun aChosenFolderComesFirst() {
        // PPSSPP on Android with its memory stick moved to a card.
        val env = FakeEnv("ANDROID", "/storage/emulated/0", files = mapOf("/storage/1234-ABCD/PSP/SAVEDATA/ULUS10041DATA/" to ""), chosen = mapOf("ppsspp" to "/storage/1234-ABCD/PSP"))
        val spot = SaveAdapters.forEmulator("ppsspp")!!.locate(q("psp", "/roms/psp/game.iso", "ppsspp", serial = "ULUS10041"), env).first { it.kind == SaveKind.SAVE }
        assertTrue(spot.root!!.startsWith("/storage/1234-ABCD/PSP"))
    }

    @Test
    fun mupenKeepsEveryPartUnderTheNameItGaveTheGame() {
        val dir = "/home/mo/.local/share/mupen64plus/save"
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("$dir/SUPER MARIO 64-0123ABCD.eep" to "e"))
        val spot = SaveAdapters.forEmulator("mupen64plus")!!.locate(q("n64", "/roms/n64/Super Mario 64 (USA).z64", "mupen64plus", title = "Super Mario 64"), env).single()
        assertEquals("n64.split", spot.format)
        assertEquals("$dir/SUPER MARIO 64-0123ABCD.eep", spot.pathFor("save.eep"))
        assertEquals("$dir/SUPER MARIO 64-0123ABCD.sra", spot.pathFor("save.sra"))
    }

    @Test
    fun threeDsFindsTheSaveByTheCartridgesTitleId() {
        val header = ByteArray(0x200)
        "NCSD".encodeToByteArray().copyInto(header, 0x100)
        // Media id 0004000000055D00 (Pokemon X), little-endian.
        byteArrayOf(0x00, 0x5D, 0x05, 0x00, 0x00, 0x00, 0x04, 0x00).copyInto(header, 0x108)
        val base = "/home/mo/.local/share/azahar-emu"
        val id0 = "0123456789abcdef0123456789abcdef"
        val id1 = "fedcba9876543210fedcba9876543210"
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("$base/sdmc/Nintendo 3DS/$id0/$id1/title/" to ""), bin = mapOf("/roms/3ds/X.3ds" to header))
        val spots = SaveAdapters.forEmulator("azahar")!!.locate(q("3ds", "/roms/3ds/X.3ds", "azahar"), env)
        assertEquals("$base/sdmc/Nintendo 3DS/$id0/$id1/title/00040000/00055d00/data", spots.single { it.kind == SaveKind.SAVE }.root)
        // Save states sit in the user folder's states, by title id and slot.
        val states = spots.single { it.kind == SaveKind.STATE }
        assertEquals("$base/states/0004000000055D00.01.cst", states.pathFor("state01"))
    }

    @Test
    fun switchTitleIdsFromTheNameOrTheTicket() {
        val env = FakeEnv("LINUX", "/home/mo")
        // A tag in the file name; an update's id is the game's.
        assertEquals("01007EF00011E000", SwitchIds.of(q("switch", "/roms/switch/Zelda [01007EF00011E800][v3].nsp", "eden"), env))
        // An NSP whose ticket names the rights id.
        val names = "0123.nca\u000001007EF00011E0000000000000000004.tik\u0000"
        val table = names.encodeToByteArray()
        val nsp = ByteArray(0x10 + 2 * 0x18 + table.size)
        "PFS0".encodeToByteArray().copyInto(nsp, 0)
        nsp[4] = 2
        nsp[8] = table.size.toByte()
        table.copyInto(nsp, 0x10 + 2 * 0x18)
        val withTicket = FakeEnv("LINUX", "/home/mo", bin = mapOf("/roms/switch/game.nsp" to nsp))
        assertEquals("01007EF00011E000", SwitchIds.fromNsp(withTicket, "/roms/switch/game.nsp"))
        assertNull(SwitchIds.fromNsp(withTicket, "/roms/switch/game.xci"))
    }

    @Test
    fun yuzuFamilyAndRyujinxFindTheSwitchSave() {
        val user = "00000000000000000000000000000001"
        val eden = "/home/mo/.local/share/eden/nand/user/save/0000000000000000"
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("$eden/$user/01007EF00011E000/" to ""))
        val spot = SaveAdapters.forEmulator("eden")!!.locate(q("switch", "/roms/switch/Zelda [01007EF00011E000].nsp", "eden"), env).single()
        assertEquals("$eden/$user/01007EF00011E000", spot.root)

        val saves = "/home/mo/.config/Ryujinx/bis/user/save"
        val ryu = FakeEnv("LINUX", "/home/mo", files = mapOf("$saves/0000000000000001/0/" to "", "$saves/0000000000000002/0/" to ""),
            bin = mapOf("$saves/0000000000000002/ExtraData0" to attribute("01007EF00011E000", "0000000000000001", "0000000000000000", 1)))
        val r = SaveAdapters.forEmulator("ryujinx")!!.locate(q("switch", "/roms/switch/Zelda [01007EF00011E000].nsp", "ryujinx"), ryu).single()
        assertEquals("$saves/0000000000000002/0", r.root)
    }

    /** A save's attribute as Ryujinx writes ExtraData0: program id, user (high, low), then the save's type. */
    private fun attribute(program: String, high: String, low: String, type: Int): ByteArray {
        fun le(hex: String) = hex.chunked(2).reversed().map { it.toInt(16).toByte() }
        return (le(program) + le(high) + le(low) + List(8) { 0.toByte() } + listOf(type.toByte())).toByteArray()
    }

    @Test
    fun ryujinxTakesItsOwnUsersSaveAndTheDeviceSaveTogether() {
        val base = "/home/mo/.config/Ryujinx"
        val saves = "$base/bis/user/save"
        val zelda = q("switch", "/roms/switch/Zelda [01007EF00011E000].nsp", "ryujinx")
        val files = mapOf("$saves/0000000000000001/0/a" to "x", "$saves/0000000000000002/0/b" to "x", "$saves/0000000000000003/0/d" to "x")
        val bin = mapOf(
            "$saves/0000000000000001/ExtraData0" to attribute("01007EF00011E000", "0000000000000001", "0000000000000000", 1),
            "$saves/0000000000000002/ExtraData0" to attribute("01007EF00011E000", "0000000000000002", "0000000000000000", 1),
            "$saves/0000000000000003/ExtraData0" to attribute("01007EF00011E000", "0000000000000000", "0000000000000000", 3),
        )
        // Two users with saves, the second selected: that one, with the device save as its other part.
        val chosen = FakeEnv("LINUX", "/home/mo", files = files + ("$base/system/Profiles.json" to "{\"profiles\":[],\"last_opened\":\"00000000000000020000000000000000\"}"), bin = bin)
        val spot = SaveAdapters.forEmulator("ryujinx")!!.locate(zelda, chosen).single()
        assertEquals("$saves/0000000000000002/0", spot.root)
        assertEquals("$saves/0000000000000003/0", spot.mounts[SwitchNand.DEVICE])
        // Two users and no selection: Fuse doesn't guess between them.
        val unsure = SaveAdapters.forEmulator("ryujinx")!!.locate(zelda, FakeEnv("LINUX", "/home/mo", files = files, bin = bin)).single()
        assertFalse(unsure.available)
        // The selected user has no save of it yet: never someone else's.
        val other = FakeEnv("LINUX", "/home/mo", files = files + ("$base/system/Profiles.json" to "{\"last_opened\":\"00000000000000090000000000000000\"}"), bin = bin)
        assertFalse(SaveAdapters.forEmulator("ryujinx")!!.locate(zelda, other).single().available)
    }

    @Test
    fun cemuReadsTheTitleIdFromTheGamesMeta() {
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf(
            "/home/mo/.local/share/Cemu/mlc01/" to "",
            "/roms/wiiu/Mario Kart 8/meta/meta.xml" to "<menu><title_id type=\"hexBinary\" length=\"8\">000500001010EC00</title_id></menu>",
        ))
        val spot = SaveAdapters.forEmulator("cemu")!!.locate(q("wiiu", "/roms/wiiu/Mario Kart 8/code/Turbo.rpx", "cemu"), env).single()
        assertEquals("/home/mo/.local/share/Cemu/mlc01/usr/save/00050000/1010ec00/user", spot.root)
    }

    @Test
    fun xeniaUsesTheTitleIdTag() {
        val content = "/home/mo/Documents/Xenia/content"
        val env = FakeEnv("WINDOWS", "/home/mo", files = mapOf("$content/E030000012345678/" to ""), chosen = mapOf("xenia" to content))
        val spot = SaveAdapters.forEmulator("xenia")!!.locate(q("xbox360", "/roms/x360/Halo 3 [4D5307E6].iso", "xenia"), env).single()
        assertEquals("$content/E030000012345678/4D5307E6/00000001", spot.root)
    }

    @Test
    fun supermodelsNvramIsWhereItsFileSystemPathPutsIt() {
        val sm = SaveAdapters.forEmulator("linux.supermodel")!!
        val game = q("model3", "/roms/model3/daytona2.zip", "linux.supermodel")
        fun path(env: FakeEnv, query: SaveQuery = game) = sm.locate(query, env).single().files.single().path
        // Nothing of its own yet: the data folder, ~/.local/share/supermodel/NVRAM.
        assertEquals("/home/mo/.local/share/supermodel/NVRAM/daytona2.nv", path(FakeEnv("LINUX", "/home/mo")))
        // ~/.supermodel before that, and a Config folder in the game's folder (where it starts) before everything.
        assertEquals("/home/mo/.supermodel/NVRAM/daytona2.nv", path(FakeEnv("LINUX", "/home/mo", files = mapOf("/home/mo/.supermodel/" to "", "/home/mo/.local/share/supermodel/" to ""))))
        assertEquals("/roms/model3/NVRAM/daytona2.nv", path(FakeEnv("LINUX", "/home/mo", files = mapOf("/roms/model3/Config/" to "", "/home/mo/.supermodel/" to ""))))
        // On Windows, beside the program.
        val win = game.copy(emulatorId = "windows.supermodel", emulatorPath = "C:/Emu/Supermodel/Supermodel.exe")
        assertEquals("C:/Emu/Supermodel/NVRAM/daytona2.nv", path(FakeEnv("WINDOWS", "C:/Users/mo"), win))
    }

    @Test
    fun xeniaOnLinuxKeepsItsContentInTheDataFolder() {
        // Without XDG_DATA_HOME, Xenia's user folder is ~/.local/share (filesystem_posix.cc), not the home folder.
        val content = "/home/mo/.local/share/Xenia/content"
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("$content/E030000012345678/4D5307E6/" to ""))
        val spot = SaveAdapters.forEmulator("xenia")!!.locate(q("xbox360", "/roms/x360/Halo 3 [4D5307E6].iso", "xenia"), env).single()
        assertEquals("$content/E030000012345678/4D5307E6/00000001", spot.root)
    }

    @Test
    fun conversionsKeepTheSave() {
        val raw = ByteArray(0x80000) { (it % 251).toByte() }
        val footer = ByteArray(122).also { "|-DESMUME SAVE-|".encodeToByteArray().copyInto(it, 122 - 16) }
        val dsv = raw + footer
        assertContentEquals(raw, SaveConversions.convert("nds.dsv", "sram", mapOf("save.srm" to dsv))!!["save.srm"])
        // A raw save without the footer is left as it is.
        assertContentEquals(raw, SaveConversions.convert("nds.dsv", "sram", mapOf("save.srm" to raw))!!["save.srm"])

        val eep = ByteArray(0x800) { 7 }
        val sra = ByteArray(0x8000) { 9 }
        val srm = SaveConversions.convert("n64.split", "retroarch.n64", mapOf("save.eep" to eep, "save.sra" to sra))!!["save.srm"]!!
        assertEquals(0x48800, srm.size)
        val back = SaveConversions.convert("retroarch.n64", "n64.split", mapOf("save.srm" to srm))!!
        assertEquals(setOf("save.eep", "save.sra"), back.keys)
        assertContentEquals(eep, back["save.eep"])
        assertContentEquals(sra, back["save.sra"])
        assertTrue(SaveSlotFormats.compatible("nds.dsv", "sram"))
        assertFalse(SaveSlotFormats.compatible("3ds.savedata", "sram"))
    }

    @Test
    fun aDraSticSaveReachesMelonDsAndComesBack() = runBlocking {
        val root = Files.createTempDirectory("convert").toFile()
        val host = SyncHost(HostStore(File(root, "host")), port = 0, bind = "127.0.0.1").start()
        try {
            val address = "127.0.0.1:${host.boundPort()}"
            val http = SyncClient.defaultClient()
            suspend fun device(name: String): Pair<SyncClient, SyncDevice> {
                val id = "dev-" + SyncCrypto.token(8)
                val link = SyncClient.pair(address, host.newPairingCode(), id, name, "LINUX", http)
                return SyncClient(link, http) to SyncDevice(File(root, name), id, name)
            }
            val (phoneClient, phone) = device("Phone")
            val (pcClient, pc) = device("PC")
            val p = phoneClient.createProfile(NewProfile("Mo", "fox")).id
            val game = GameKey.of("nds", null, null, "Pokemon Platinum")
            val drastic = File(root, "phone/DraStic").apply { File(this, "backup").mkdirs() }
            val pcRoms = File(root, "pc/roms").apply { mkdirs() }
            val raw = ByteArray(0x80000) { (it % 13).toByte() }
            val footer = ByteArray(122).also { "|-DESMUME SAVE-|".encodeToByteArray().copyInto(it, 122 - 16) }
            File(drastic, "backup/Pokemon Platinum.dsv").writeBytes(raw + footer)

            val phoneEnv = WithSaveFolders(FileSaveEnvironment("ANDROID", root.path, variables = { null })) { mapOf("drastic" to drastic.path) }
            val phoneSlot = Slots.of(game, SaveAdapters.forEmulator("drastic")!!.locate(SaveQuery(game, "nds", "/roms/Pokemon Platinum.nds", "drastic"), phoneEnv).single())
            assertNotNull(phone.capture(p, phoneSlot, 600))
            phone.flush(phoneClient)

            val pcEnv = FileSaveEnvironment("LINUX", root.path, variables = { null })
            val pcQuery = SaveQuery(game, "nds", File(pcRoms, "Pokemon Platinum.nds").path, "melonds")
            val pcSlot = { Slots.of(game, SaveAdapters.forEmulator("melonds")!!.locate(pcQuery, pcEnv).single()) }
            assertIs<PrepareResult.Updated>(pc.prepare(pcClient, p, pcSlot()))
            // melonDS gets the raw save, without DraStic's footer.
            assertContentEquals(raw, File(pcRoms, "Pokemon Platinum.sav").readBytes())
            // Playing without saving makes no new save from the converted copy.
            assertNull(pc.capture(p, pcSlot(), 60))
            assertIs<PrepareResult.Ready>(pc.prepare(pcClient, p, pcSlot()))

            // Progress on the PC goes back to DraStic.
            val later = ByteArray(0x80000) { (it % 17).toByte() }
            File(pcRoms, "Pokemon Platinum.sav").writeBytes(later)
            assertNotNull(pc.capture(p, pcSlot(), 900))
            pc.flush(pcClient)
            assertIs<PrepareResult.Updated>(phone.prepare(phoneClient, p, phoneSlot))
            assertContentEquals(later, File(drastic, "backup/Pokemon Platinum.dsv").readBytes())
        } finally {
            host.stop()
            root.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- data folders anywhere (Android)

    private val ruby3ds = "0004000000055D00"

    /** An NCSD cartridge header with [titleId] at 0x108 (little-endian), as a .3ds image starts. */
    private fun ncsd(titleId: String): ByteArray = ByteArray(0x120).also { b ->
        "NCSD".encodeToByteArray().copyInto(b, 0x100)
        val id = titleId.toULong(16)
        for (i in 0 until 8) b[0x108 + i] = ((id shr (8 * i)) and 0xFFu).toByte()
    }

    @Test
    fun azaharsFolderIsFoundWhereverItWasPicked() {
        // Picked inside an emulation folder three levels down, on the device's own storage.
        val user = "/storage/emulated/0/Emulation/3DS/azahar"
        val env = FakeEnv(
            "ANDROID", "/storage/emulated/0",
            files = mapOf("$user/sdmc/Nintendo 3DS/" to "", "$user/config/" to "", "/storage/emulated/0/Music/" to ""),
            bin = mapOf("/roms/3ds/Ruby.3ds" to ncsd(ruby3ds)),
            roots = listOf("/storage/emulated/0"),
        )
        val save = SaveAdapters.forEmulator("azahar")!!.locate(q("3ds", "/roms/3ds/Ruby.3ds", "azahar"), env).first()
        assertTrue(save.available, save.note)
        assertEquals("$user/sdmc/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000/title/00040000/00055d00/data", save.root)
    }

    @Test
    fun azaharsFolderOnACardIsFoundAndTheOneHoldingTheGameWins() {
        val card = "/storage/1234-ABCD/Azahar"
        val old = "/storage/emulated/0/citra-old"
        val title = "sdmc/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000/title/00040000/00055d00/data/00000001/main"
        val env = FakeEnv(
            "ANDROID", "/storage/emulated/0",
            files = mapOf("$old/sdmc/Nintendo 3DS/" to "", "$old/nand/" to "", "$card/nand/" to "", "$card/$title" to "save"),
            bin = mapOf("/roms/3ds/Ruby.3ds" to ncsd(ruby3ds)),
            roots = listOf("/storage/emulated/0", "/storage/1234-ABCD"),
            // The other folder changed more recently, but this game's save is on the card.
            times = mapOf(old to 9_000L, card to 1_000L),
        )
        val save = SaveAdapters.forEmulator("azahar")!!.locate(q("3ds", "/roms/3ds/Ruby.3ds", "azahar"), env).first()
        assertTrue(save.root!!.startsWith(card), save.root)
    }

    @Test
    fun foldersInsideOtherAppsStorageAndPhotosAreNeverSearched() {
        val env = FakeEnv(
            "ANDROID", "/storage/emulated/0",
            files = mapOf("/storage/emulated/0/Android/data/org.azahar_emu.azahar/files/sdmc/Nintendo 3DS/" to "", "/storage/emulated/0/Android/data/org.azahar_emu.azahar/files/nand/" to ""),
            bin = mapOf("/roms/3ds/Ruby.3ds" to ncsd(ruby3ds)),
            roots = listOf("/storage/emulated/0"),
        )
        val save = SaveAdapters.forEmulator("azahar")!!.locate(q("3ds", "/roms/3ds/Ruby.3ds", "azahar"), env).first()
        assertFalse(save.available)
        assertTrue(save.note!!.contains("Save folders"))
    }

    @Test
    fun theFinderLooksOnlyOnAndroid() {
        val env = FakeEnv("LINUX", "/home/mo", files = mapOf("/home/mo/Emulation/azahar/sdmc/Nintendo 3DS/" to "", "/home/mo/Emulation/azahar/nand/" to ""), roots = listOf("/home/mo"))
        assertTrue(DataFolders.find(env, "3ds", listOf("azahar")) { d -> d.takeIf { env.isDirectory("$it/nand") } }.isEmpty())
    }

    @Test
    fun ppssppDolphinDuckStationAndArmsx2AreFoundWhereverPicked() {
        val env = FakeEnv(
            "ANDROID", "/storage/emulated/0",
            files = mapOf(
                "/storage/emulated/0/Games/ppsspp/PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN" to "x",
                "/storage/emulated/0/Games/Dolphin/GC/" to "", "/storage/emulated/0/Games/Dolphin/Config/" to "",
                "/storage/emulated/0/Games/DuckStation/memcards/" to "", "/storage/emulated/0/Games/DuckStation/settings.ini" to "",
                "/storage/emulated/0/Games/ARMSX2/memcards/Mcd001.ps2" to "card",
            ),
            roots = listOf("/storage/emulated/0"),
        )
        val psp = SaveAdapters.forEmulator("ppsspp")!!.locate(q("psp", "/roms/psp/game.iso", "ppsspp", serial = "ULUS10041"), env).first()
        assertEquals("/storage/emulated/0/Games/ppsspp/PSP/SAVEDATA", psp.root)
        assertEquals(listOf("ULUS10041DATA00"), psp.folders)
        val gc = SaveAdapters.forEmulator("dolphin")!!.locate(q("ngc", "/roms/gc/game.iso", "dolphin", serial = "GALE01"), env).first()
        // Dolphin's own default: slot A is a GCI folder, this game's files in it.
        assertEquals("/storage/emulated/0/Games/Dolphin/GC/USA/Card A", gc.root)
        assertEquals("01-GALE-", gc.only)
        val psx = SaveAdapters.forEmulator("duckstation")!!.locate(q("psx", "/roms/psx/game.cue", "duckstation", title = "Game"), env).first()
        assertTrue(psx.files.single().path.startsWith("/storage/emulated/0/Games/DuckStation/memcards/"))
        val ps2 = SaveAdapters.forEmulator("armsx2")!!.locate(q("ps2", "/roms/ps2/game.iso", "armsx2"), env).first()
        assertEquals("/storage/emulated/0/Games/ARMSX2/memcards/Mcd001.ps2", ps2.files.first().path)
    }

    // ---------------------------------------------------------------- game ids from more files, or learned

    @Test
    fun aThreeDsTitleIdIsReadFromAnNcchAndACia() {
        val ncch = ByteArray(0x120).also { b ->
            "NCCH".encodeToByteArray().copyInto(b, 0x100)
            val id = ruby3ds.toULong(16)
            for (i in 0 until 8) b[0x118 + i] = ((id shr (8 * i)) and 0xFFu).toByte()
        }
        // A CIA: header 0x2020, a cert chain and ticket, then a TMD (RSA-2048) whose title id is big-endian.
        val cert = 0xA00
        val ticket = 0x350
        val tmdAt = ((((0x2020 + 63) / 64 * 64) + cert + 63) / 64 * 64 + ticket + 63) / 64 * 64
        val cia = ByteArray(tmdAt + 0x240 + 0x200).also { b ->
            fun le(at: Int, v: Int) { for (i in 0 until 4) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
            le(0, 0x2020); le(0x08, cert); le(0x0C, ticket); le(0x10, 0x240 + 0x200)
            b[tmdAt + 1] = 0x01; b[tmdAt + 3] = 0x04
            val id = "0004000E00055D00".toULong(16)
            for (i in 0 until 8) b[tmdAt + 0x140 + 0x4C + i] = ((id shr (8 * (7 - i))) and 0xFFu).toByte()
        }
        val env = FakeEnv("LINUX", "/home/mo", bin = mapOf("/roms/a.cxi" to ncch, "/roms/b.cia" to cia))
        assertEquals(ruby3ds, ThreeDs.titleId(q("3ds", "/roms/a.cxi", "azahar"), env))
        // An update's CIA belongs to the game: the save is kept under the game's id.
        assertEquals(ruby3ds, ThreeDs.titleId(q("3ds", "/roms/b.cia", "azahar"), env))
    }

    @Test
    fun aCompressedThreeDsGameIsPlacedOnceAPlayTaughtItsFolder() {
        val user = "/home/mo/.local/share/azahar-emu"
        val titles = "$user/sdmc/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000/title/00040000"
        val game = q("3ds", "/roms/Ruby.zcci", "azahar")
        val before = FakeEnv("LINUX", "/home/mo", files = mapOf("$titles/" to ""), bin = mapOf("/roms/Ruby.zcci" to "Z3DS....".encodeToByteArray()))
        val waiting = SaveAdapters.forEmulator("azahar")!!.locate(game, before).first()
        assertFalse(waiting.available)
        assertEquals(titles, waiting.learnIn)
        val after = FakeEnv(
            "LINUX", "/home/mo", files = mapOf("$titles/00055d00/data/00000001/main" to "save"),
            learnedMap = mapOf("${game.game.id}|3ds.savedata" to listOf("00055d00")),
        )
        val save = SaveAdapters.forEmulator("azahar")!!.locate(game, after)
        assertEquals("$titles/00055d00/data", save.first().root)
        assertTrue(save.last().files.first().path.endsWith("/states/0004000000055D00.00.cst"))
    }

    @Test
    fun pspWiiAndSwitchSavesAreLearnedWhenTheirIdsAreUnknown() {
        val home = "/home/mo"
        val psp = q("psp", "/roms/psp/game.iso", "ppsspp")
        val wii = q("wii", "/roms/wii/game.rvz", "dolphin")
        val sw = q("switch", "/roms/switch/game.xci", "eden")
        val stick = "$home/.config/ppsspp/PSP/SAVEDATA"
        val nand = "$home/.local/share/dolphin-emu/Wii/title/00010000"
        val users = "$home/.local/share/eden/nand/user/save/0000000000000000"
        val user = "0123456789ABCDEF0123456789ABCDEF"
        val files = mapOf("$stick/" to "", "$nand/" to "", "$users/$user/" to "")
        val blank = FakeEnv("LINUX", home, files = files)
        assertEquals(stick, SaveAdapters.forEmulator("ppsspp")!!.locate(psp, blank).single().learnIn)
        assertEquals(nand, SaveAdapters.forEmulator("dolphin")!!.locate(wii, blank).single().learnIn)
        assertEquals("$users/$user", SaveAdapters.forEmulator("eden")!!.locate(sw, blank).single().learnIn)
        val taught = FakeEnv(
            "LINUX", home, files = files,
            learnedMap = mapOf(
                "${psp.game.id}|psp.savedata" to listOf("ULUS10041DATA00", "ULUS10041DATA01"),
                "${wii.game.id}|wii.nand" to listOf("52534245"),
                "${sw.game.id}|switch.savedata" to listOf("0100000000010000"),
            ),
        )
        assertEquals(listOf("ULUS10041DATA00", "ULUS10041DATA01"), SaveAdapters.forEmulator("ppsspp")!!.locate(psp, taught).single().folders)
        assertEquals("$nand/52534245/data", SaveAdapters.forEmulator("dolphin")!!.locate(wii, taught).single().root)
        assertEquals("$users/$user/0100000000010000", SaveAdapters.forEmulator("eden")!!.locate(sw, taught).single().root)
    }

    @Test
    fun aPortsSavesAreTheFolderItsLauncherNamesAndGoOnlyToTheSamePort() {
        val launcher = "/home/ana/.local/share/fuse/ports/skyport/skyport.fuseport"
        val game = q("ports", launcher, "linux.port", title = "Sky Racer")
        val env = FakeEnv(
            "LINUX", "/home/ana",
            files = mapOf(launcher to """{"port":"skyport","version":"2.1","argv":["/p/skyport"],"cwd":"/p","saves":"/home/ana/.local/share/fuse/ports/skyport/home/saves"}"""),
        )
        val spot = SaveAdapters.forEmulator("linux.port")!!.locate(game, env).single()
        assertEquals("/home/ana/.local/share/fuse/ports/skyport/home/saves", spot.root)
        assertEquals("port.skyport", spot.format, "another port's engine never reads it")
        assertEquals(SaveKind.SAVE, spot.kind)
        assertSame(SaveAdapters.forEmulator("windows.port"), SaveAdapters.forEmulator("linux.port"), "the same on every computer")
        // A port that keeps no saves of its own, and a launcher Fuse can't read, are said.
        val none = FakeEnv("LINUX", "/home/ana", files = mapOf(launcher to """{"port":"skyport","version":"2.1","argv":["/p/skyport"],"cwd":"/p"}"""))
        assertFalse(SaveAdapters.forEmulator("linux.port")!!.locate(game, none).single().available)
        assertFalse(SaveAdapters.forEmulator("linux.port")!!.locate(game, FakeEnv("LINUX", "/home/ana")).single().available)
    }
}
