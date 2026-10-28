package io.github.matiyaaa.fuse.sync

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Saves kept outside any emulator: PlayStation saves on their own (MemcardRex's .mcs, a PS3's
 * .psv, an Action Replay's .psx) and whole cards in a wrapper (a DexDrive's .gme), and GameCube
 * saves (.gci), each brought to where the emulator reads it.
 */
class SavesFromAnywhereTest {
    private fun <T> temp(block: (File) -> T): T {
        val dir = Files.createTempDirectory("fuse-saves-anywhere").toFile()
        return try { block(dir) } finally { dir.deleteRecursively() }
    }

    /** A save of [blocks] blocks called [name], its first block starting with its title frame. */
    private fun save(name: String, blocks: Int = 1, fill: Int = 7): Ps1Card.Save {
        val data = ByteArray(blocks * Ps1Card.BLOCK) { fill.toByte() }
        data[0] = 'S'.code.toByte()
        data[1] = 'C'.code.toByte()
        return Ps1Card.Save(name, data)
    }

    @Test
    fun savesGoOntoACardBesideTheOnesThereAndComeBackOff() {
        val mgs = save("BASLUS-00594MGS", blocks = 2, fill = 1)
        val ff = save("BASCUS-94163FF7", fill = 2)
        val card = Ps1Card.write(Ps1Card.write(Ps1Card.blank(), mgs)!!, ff)!!
        assertTrue(Ps1Card.isCard(card))
        val on = Ps1Card.saves(card).associateBy { it.name }
        assertEquals(setOf("BASLUS-00594MGS", "BASCUS-94163FF7"), on.keys)
        assertContentEquals(mgs.data, on.getValue("BASLUS-00594MGS").data)
        // The same save again replaces it rather than taking more blocks.
        val again = Ps1Card.write(card, save("BASLUS-00594MGS", blocks = 2, fill = 9))!!
        assertEquals(2, Ps1Card.saves(again).size)
        assertEquals(9.toByte(), Ps1Card.saves(again).single { it.name.endsWith("MGS") }.data[100])
        // Every directory frame's checksum holds: XOR of its first 127 bytes.
        for (frame in 0..15) {
            var x = 0
            for (i in 0 until 127) x = x xor (card[frame * 128 + i].toInt() and 0xFF)
            assertEquals(x.toByte(), card[frame * 128 + 127])
        }
        // A card with fifteen blocks used has no room for more.
        val full = Ps1Card.write(Ps1Card.blank(), save("BIG", blocks = 15))!!
        assertNull(Ps1Card.write(full, save("MORE")))
    }

    @Test
    fun singleSavesAreToldByWhatTheyHold() {
        val s = save("BASLUS-00594MGS", blocks = 1)
        val mcs = Ps1Card.mcs(s)
        assertEquals("BASLUS-00594MGS", Ps1Card.saveIn(mcs)?.name)
        // A PS3's .psv: "\0VSP", its name at 0x64, its blocks from 0x84.
        val psv = ByteArray(0x84 + Ps1Card.BLOCK).also { b ->
            "\u0000VSP".encodeToByteArray().copyInto(b)
            "BASLUS-00594MGS".encodeToByteArray().copyInto(b, 0x64)
            s.data.copyInto(b, 0x84)
        }
        assertEquals("BASLUS-00594MGS", Ps1Card.saveIn(psv)?.name)
        // An Action Replay's: its name first, its blocks from byte 54.
        val ar = ByteArray(54 + Ps1Card.BLOCK).also { b -> "BASLUS-00594MGS".encodeToByteArray().copyInto(b); s.data.copyInto(b, 54) }
        assertEquals("BASLUS-00594MGS", Ps1Card.saveIn(ar)?.name)
        assertNull(Ps1Card.saveIn(ByteArray(Ps1Card.BLOCK)))
        // A DexDrive card: 3904 bytes of its own, then the card.
        val card = Ps1Card.write(Ps1Card.blank(), s)!!
        assertContentEquals(card, Ps1Card.cardIn("Mine.gme", ByteArray(3904) + card))
        assertNull(Ps1Card.cardIn("Mine.gme", card))
    }

    @Test
    fun aSaveOnItsOwnImportsOntoTheGamesDuckStationCardKeepingTheOthers() = temp { root ->
        val ds = File(root, ".local/share/duckstation").apply { mkdirs() }
        File(ds, "settings.ini").writeText("")
        // The game's own card already holds another save.
        File(ds, "memcards").mkdirs()
        val other = save("BASLUS-00001OTHER", fill = 3)
        File(ds, "memcards/Metal Gear Solid_1.mcd").writeBytes(Ps1Card.write(Ps1Card.blank(), other)!!)
        val source = File(root, "Downloads").apply { mkdirs() }
        File(source, "SLUS-00594 Metal Gear Solid.mcs").writeBytes(Ps1Card.mcs(save("BASLUS-00594MGS", fill = 5)))
        val q = SaveQuery(GameKey.of("psx", "SLUS-00594", null, "Metal Gear Solid"), "psx", "/roms/psx/Metal Gear Solid.chd", "duckstation", serial = "SLUS-00594", title = "Metal Gear Solid")
        val plan = JvmSaveImporter(File(root, "stage")).scan(source, listOf(q), FileSaveEnvironment("LINUX", root.path) { null })
        val entry = plan.entries.single()
        assertEquals(Ps1Card.SINGLE, entry.sourceFormat)
        val target = entry.targets.single()
        assertEquals("psx.mcd", target.format)
        assertTrue(target.conversion)
        assertNotNull(entry.selectedTarget)

        // Onto the card that is there: both saves on it.
        val merged = SaveConversions.onCard(File(ds, "memcards/Metal Gear Solid_1.mcd").readBytes(), mapOf("card1.mcd" to File(source, "SLUS-00594 Metal Gear Solid.mcs").readBytes()))!!
        assertEquals(setOf("BASLUS-00001OTHER", "BASLUS-00594MGS"), Ps1Card.saves(merged).map { it.name }.toSet())
    }

    private fun ps2(name: String): ByteArray = javaClass.getResourceAsStream("/ps2/$name")!!.use { it.readBytes() }

    private val kingdom = SaveQuery(GameKey.of("ps2", "SLUS-21050", null, "Kingdom Hearts"), "ps2", "/roms/ps2/Kingdom Hearts.chd", "pcsx2", serial = "SLUS-21050", title = "Kingdom Hearts")

    @Test
    fun aPs2SaveOnItsOwnGoesOntoPcsx2sFirstCardBesideTheOthersAndTheSecondCardStays() = temp { root ->
        val pcsx2 = File(root, ".config/PCSX2").apply { File(this, "inis").mkdirs(); File(this, "memcards").mkdirs() }
        File(pcsx2, "inis/PCSX2.ini").writeText("")
        val ico = Ps2Card.saveIn(ps2("save.psu"))!!.let { Ps2Card.Save(it.dir.copy(name = "BASLES-50330ICO"), it.files.take(1)) }
        File(pcsx2, "memcards/Mcd001.ps2").writeBytes(Ps2Card.write(Ps2Card.blank(), ico)!!)
        val second = Ps2Card.blank(1).also { File(pcsx2, "memcards/Mcd002.ps2").writeBytes(it) }
        val source = File(root, "Downloads").apply { mkdirs() }
        // Named for nothing: the save says inside which game it is.
        val max = File(source, "kh-100percent.max").apply { writeBytes(ps2("save.max")) }
        val env = FileSaveEnvironment("LINUX", root.path) { null }
        val plan = JvmSaveImporter(File(root, "stage")).scan(source, listOf(kingdom), env)
        val entry = plan.entries.single()
        assertEquals(Ps2Card.SINGLE, entry.sourceFormat)
        val target = entry.targets.single()
        assertEquals(Ps2Card.FILE_CARD, target.format)
        assertTrue(target.conversion)
        assertEquals(SaveImportConfidence.TITLE_ID, target.confidence)

        val spot = Pcsx2.locate(kingdom, env).first { it.kind == SaveKind.MEMORY_CARD }
        runBlocking { SyncDevice(File(root, "device"), "pc", "PC").importSave("person", Slots.of(kingdom.game, spot), Ps2Card.SINGLE, mapOf(max.name to max), "Kingdom Hearts") }
        val card = File(pcsx2, "memcards/Mcd001.ps2").readBytes()
        assertEquals(listOf("BASLES-50330ICO", "BASLUS-21050GAME"), Ps2Card.saves(card))
        assertEquals(4, Ps2Card.read(card, "BASLUS-21050GAME")!!.files.size)
        assertContentEquals(second, File(pcsx2, "memcards/Mcd002.ps2").readBytes(), "the second card stays as it was")
    }

    @Test
    fun oneOfPcsx2sCardsImportedWholeLeavesTheOtherAsItWas() = temp { root ->
        val pcsx2 = File(root, ".config/PCSX2").apply { File(this, "inis").mkdirs(); File(this, "memcards").mkdirs() }
        File(pcsx2, "inis/PCSX2.ini").writeText("")
        File(pcsx2, "memcards/Mcd001.ps2").writeBytes(Ps2Card.blank())
        val second = Ps2Card.write(Ps2Card.blank(), Ps2Card.saveIn(ps2("save.psu"))!!)!!.also { File(pcsx2, "memcards/Mcd002.ps2").writeBytes(it) }
        val incoming = File(root, "Mcd001.ps2").apply { writeBytes(Ps2Card.write(Ps2Card.blank(7), Ps2Card.saveIn(ps2("save.sps"))!!)!!) }
        val env = FileSaveEnvironment("LINUX", root.path) { null }
        val spot = Pcsx2.locate(kingdom, env).first { it.kind == SaveKind.MEMORY_CARD }
        runBlocking { SyncDevice(File(root, "device"), "pc", "PC").importSave("person", Slots.of(kingdom.game, spot), Ps2Card.FILE_CARD, mapOf("Mcd001.ps2" to incoming), "Memory cards") }
        assertContentEquals(incoming.readBytes(), File(pcsx2, "memcards/Mcd001.ps2").readBytes())
        assertContentEquals(second, File(pcsx2, "memcards/Mcd002.ps2").readBytes())
    }

    @Test
    fun aPs2SaveOnItsOwnGoesIntoAFolderCardAsAFolderOfItsFiles() = temp { root ->
        val pcsx2 = File(root, ".config/PCSX2").apply { File(this, "inis").mkdirs() }
        File(pcsx2, "inis/PCSX2.ini").writeText("")
        val folder = File(pcsx2, "memcards/Mcd001.ps2").apply { mkdirs() }
        File(folder, "_pcsx2_superblock").writeText("superblock")
        File(folder, "BASLES-50330ICO").mkdirs()
        File(folder, "BASLES-50330ICO/icon.sys").writeText("ico")
        // An older copy of the same save, with a file the new one hasn't.
        File(folder, "BASLUS-21050GAME").mkdirs()
        File(folder, "BASLUS-21050GAME/old.bin").writeText("old")
        File(pcsx2, "memcards/Mcd002.ps2").writeBytes(Ps2Card.blank())
        val source = File(root, "Downloads").apply { mkdirs() }
        val psu = File(source, "BASLUS-21050GAME.psu").apply { writeBytes(ps2("save.psu")) }
        val env = FileSaveEnvironment("LINUX", root.path) { null }
        val spot = Pcsx2.locate(kingdom, env).first { it.kind == SaveKind.MEMORY_CARD }
        assertEquals(Ps2Card.FOLDER_CARD, spot.format)
        runBlocking { SyncDevice(File(root, "device"), "pc", "PC").importSave("person", Slots.of(kingdom.game, spot), Ps2Card.SINGLE, mapOf(psu.name to psu), "Kingdom Hearts") }
        assertEquals(listOf("BASLUS-21050GAME", "empty", "icon.sys", "view.ico"), File(folder, "BASLUS-21050GAME").list()!!.sorted())
        assertEquals(3000L, File(folder, "BASLUS-21050GAME/BASLUS-21050GAME").length())
        assertEquals("ico", File(folder, "BASLES-50330ICO/icon.sys").readText())
        assertEquals("superblock", File(folder, "_pcsx2_superblock").readText())
        assertTrue(Ps2Card.isCard(File(pcsx2, "memcards/Mcd002.ps2").readBytes()))
    }

    @Test
    fun aGameCubeSaveGoesIntoDolphinsFolderByDolphinsName() = temp { root ->
        val gci = ByteArray(0x40 + 0x2000).also { b ->
            "GALE01".encodeToByteArray().copyInto(b)
            "SuperSmashBros0110290334".encodeToByteArray().copyInto(b, 8)
            b[0x38] = 0
            b[0x39] = 1
        }
        assertEquals("GALE01", Gci.gameId(gci))
        assertEquals("01-GALE-SuperSmashBros0110290334.gci", Gci.dolphinName(gci))
        assertNull(Gci.gameId(gci + ByteArray(5)))

        File(root, ".local/share/dolphin-emu/GC").mkdirs()
        val source = File(root, "Downloads").apply { mkdirs() }
        File(source, "melee.gci").writeBytes(gci)
        val q = SaveQuery(GameKey.of("ngc", "GALE01", null, "Super Smash Bros. Melee"), "ngc", "/roms/ngc/Melee.rvz", "dolphin", serial = "GALE01", title = "Super Smash Bros. Melee")
        val plan = JvmSaveImporter(File(root, "stage")).scan(source, listOf(q), FileSaveEnvironment("LINUX", root.path) { null })
        val entry = plan.entries.single()
        assertEquals("gc.gci", entry.sourceFormat)
        val target = entry.targets.single()
        assertTrue(target.destination.endsWith("GC/USA/Card A"), target.destination)
    }
}
