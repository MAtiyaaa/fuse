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
import kotlin.test.assertTrue

class MoreAdaptersTest {
    /** A device's files: text, bytes ([bin]) and folders ("" in [files]), plus the save folders the person chose. */
    private class FakeEnv(
        override val host: String,
        override val home: String,
        val files: Map<String, String> = emptyMap(),
        val bin: Map<String, ByteArray> = emptyMap(),
        val chosen: Map<String, String> = emptyMap(),
    ) : SaveEnvironment {
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
        val extra = byteArrayOf(0x00, 0xE0.toByte(), 0x11, 0x00, 0xF0.toByte(), 0x7E, 0x00, 0x01)
        val ryu = FakeEnv("LINUX", "/home/mo", files = mapOf("$saves/0000000000000001/0/" to "", "$saves/0000000000000002/0/" to ""), bin = mapOf("$saves/0000000000000002/ExtraData0" to extra))
        val r = SaveAdapters.forEmulator("ryujinx")!!.locate(q("switch", "/roms/switch/Zelda [01007EF00011E000].nsp", "ryujinx"), ryu).single()
        assertEquals("$saves/0000000000000002/0", r.root)
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
}
