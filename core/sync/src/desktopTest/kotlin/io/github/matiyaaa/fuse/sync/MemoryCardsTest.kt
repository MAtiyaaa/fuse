package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PlayStation memory cards as DuckStation and PCSX2 are actually set: each slot, its type, its file or folder. */
class MemoryCardsTest {
    private class Env(val files: Map<String, String>) : SaveEnvironment {
        override val host = "LINUX"
        override val home = "/home/mo"
        override fun exists(path: String) = path in files || files.keys.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") }
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
    }

    private val ds = "/home/mo/.local/share/duckstation"
    private fun q(serial: String? = "SLUS-00594", title: String = "Metal Gear Solid", rom: String = "/roms/psx/Metal Gear Solid (Disc 1).chd", emu: String = "duckstation") =
        SaveQuery(GameKey.of("psx", serial, null, title), "psx", rom, emu, serial = serial, title = title)

    @Test
    fun duckStationByDefaultIsOneCardPerGameByTitle() {
        val env = Env(mapOf("$ds/settings.ini" to "", "$ds/memcards/Metal Gear Solid_1.mcd" to "x"))
        val spots = SaveAdapters.forEmulator("duckstation")!!.locate(q(), env)
        val save = spots.single { it.kind == SaveKind.SAVE }
        assertEquals(listOf(SpotFile("card1.mcd", "$ds/memcards/Metal Gear Solid_1.mcd")), save.files)
        assertTrue(spots.none { it.kind == SaveKind.MEMORY_CARD })
    }

    @Test
    fun duckStationFollowsEachSlotsTypeTheGamesOwnSettingsAndItsCardsFolder() {
        val env = Env(mapOf(
            "$ds/settings.ini" to "[MemoryCards]\nDirectory = /mnt/cards\nCard1Type = PerGame\nCard2Type = Shared\nCard2Path = everyone.mcd\n",
            "$ds/gamesettings/SLUS-00594.ini" to "[MemoryCards]\nCard1Type = PerGameFileTitle\n",
        ))
        val spots = SaveAdapters.forEmulator("duckstation")!!.locate(q(), env)
        assertEquals(listOf(SpotFile("card1.mcd", "/mnt/cards/Metal Gear Solid (Disc 1)_1.mcd")), spots.single { it.kind == SaveKind.SAVE }.files)
        val shared = spots.single { it.kind == SaveKind.MEMORY_CARD }
        assertEquals("psx.sharedcard", shared.format)
        assertEquals(listOf(SpotFile("shared2.mcd", "/mnt/cards/everyone.mcd")), shared.files)
        // A card set to None keeps nothing.
        val none = Env(mapOf("$ds/settings.ini" to "[MemoryCards]\nCard1Type = None\n"))
        assertTrue(SaveAdapters.forEmulator("duckstation")!!.locate(q(), none).none { it.kind != SaveKind.STATE })
    }

    private val ps2 = "/home/mo/.config/PCSX2"

    @Test
    fun pcsx2ReadsItsSlotsNamesAndFolder() {
        val env = Env(mapOf("$ps2/inis/PCSX2.ini" to "[Folders]\nMemoryCards = /mnt/ps2cards\n[MemoryCards]\nSlot1_Enable = true\nSlot1_Filename = Mine.ps2\nSlot2_Enable = false\nSlot2_Filename = Mcd002.ps2\n"))
        val spot = SaveAdapters.forEmulator("pcsx2")!!.locate(q(serial = "SLUS-20946", emu = "pcsx2"), env).single()
        assertEquals("ps2.card", spot.format)
        assertEquals(listOf(SpotFile("Mcd001.ps2", "/mnt/ps2cards/Mine.ps2")), spot.files)
    }

    @Test
    fun pcsx2FolderCardsAreTheirOwnKind() {
        val env = Env(mapOf("$ps2/inis/PCSX2.ini" to "", "$ps2/memcards/Mcd001.ps2/_pcsx2_superblock" to "x"))
        val spot = SaveAdapters.forEmulator("pcsx2")!!.locate(q(serial = "SLUS-20946", emu = "pcsx2"), env).single()
        assertEquals("ps2.foldercard", spot.format)
        assertEquals("$ps2/memcards/Mcd001.ps2", spot.mounts["Mcd001.ps2"])
        assertEquals(listOf(SpotFile("Mcd002.ps2", "$ps2/memcards/Mcd002.ps2")), spot.files)
        assertNull(spot.mounts["Mcd002.ps2"])
    }
}

class SaveConversionsTest {
    @Test
    fun malformedSavesAreRefusedNeverCutOrPadded() {
        // An EEPROM of a size no N64 game has: refused, not truncated into place.
        kotlin.test.assertNull(SaveConversions.convert("n64.split", "retroarch.n64", mapOf("save.eep" to ByteArray(0x900))))
        kotlin.test.assertNotNull(SaveConversions.convert("n64.split", "retroarch.n64", mapOf("save.eep" to ByteArray(0x200))))
        kotlin.test.assertNull(SaveConversions.convert("retroarch.n64", "n64.split", mapOf("save.srm" to ByteArray(1000))))
        // A DS save of an odd size, footer or not: refused.
        kotlin.test.assertNull(SaveConversions.convert("nds.dsv", "sram", mapOf("save.srm" to ByteArray(1000))))
        assertEquals(512 * 1024, SaveConversions.convert("nds.dsv", "sram", mapOf("save.srm" to ByteArray(512 * 1024)))!!.values.single().size)
    }
}
