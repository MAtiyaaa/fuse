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
        val spot = SaveAdapters.forEmulator("pcsx2")!!.locate(q(serial = "SLUS-20946", emu = "pcsx2"), env).single { it.kind != SaveKind.STATE }
        assertEquals("ps2.card", spot.format)
        assertEquals(listOf(SpotFile("Mcd001.ps2", "/mnt/ps2cards/Mine.ps2")), spot.files)
    }

    @Test
    fun pcsx2FolderCardsAreTheirOwnKind() {
        val env = Env(mapOf("$ps2/inis/PCSX2.ini" to "", "$ps2/memcards/Mcd001.ps2/_pcsx2_superblock" to "x"))
        val spot = SaveAdapters.forEmulator("pcsx2")!!.locate(q(serial = "SLUS-20946", emu = "pcsx2"), env).single { it.kind != SaveKind.STATE }
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

class DolphinCardsTest {
    private class Env(val files: Map<String, String>, val vars: Map<String, String> = emptyMap()) : SaveEnvironment {
        override val host = "LINUX"
        override val home = "/home/mo"
        override fun env(name: String) = vars[name]
        override fun exists(path: String) = path in files || files.keys.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") }
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
    }

    private fun q(serial: String = "GALE01", platform: String = "ngc") = SaveQuery(GameKey.of(platform, serial, null, "Game"), platform, "/roms/gc/game.iso", "dolphin", serial = serial, title = "Game")
    private val data = "/home/mo/.local/share/dolphin-emu"

    @Test
    fun theDefaultIsAGciFolderWithThisGamesFilesOnly() {
        val env = Env(mapOf("$data/GC/USA/Card A/01-GALE-SuperSmash.gci" to "x", "$data/GC/USA/Card A/01-GMSE-Sunshine.gci" to "x"))
        val spot = SaveAdapters.forEmulator("dolphin")!!.locate(q(), env).single { it.kind != SaveKind.STATE }
        assertEquals("gc.gci", spot.format)
        assertEquals("$data/GC/USA/Card A", spot.root)
        assertEquals("01-GALE-", spot.only)
    }

    @Test
    fun configuredSlotsAndFoldersAreFollowed() {
        val env = Env(mapOf(
            "/home/mo/.config/dolphin-emu/Dolphin.ini" to "[Core]\nSlotA = 1\nMemcardAPath = /mnt/gc/Mine.USA.raw\nSlotB = 8\nGCIFolderBPath = /mnt/gci/USA\n[General]\nNANDRootPath = /mnt/nand\n",
            "$data/GC/" to "",
        ))
        val spots = SaveAdapters.forEmulator("dolphin")!!.locate(q(serial = "GALP01"), env)
        val gci = spots.single { it.kind == SaveKind.SAVE }
        assertEquals("/mnt/gci/EUR", gci.root, "the folder's region follows the game's")
        val card = spots.single { it.kind == SaveKind.MEMORY_CARD }
        assertEquals(listOf(SpotFile("MemoryCardA.EUR.raw", "/mnt/gc/Mine.EUR.raw")), card.files)
        val wii = SaveAdapters.forEmulator("dolphin")!!.locate(q(serial = "RMGE01", platform = "wii"), env).single { it.kind != SaveKind.STATE }
        assertEquals("/mnt/nand/title/00010000/524d4745/data", wii.root)
    }

    @Test
    fun dolphinsUserPathVariableWins() {
        val env = Env(mapOf("/srv/dolphin/GC/" to ""), vars = mapOf("DOLPHIN_EMU_USERPATH" to "/srv/dolphin"))
        assertEquals("/srv/dolphin/GC/USA/Card A", SaveAdapters.forEmulator("dolphin")!!.locate(q(), env).single { it.kind != SaveKind.STATE }.root)
    }
}

class GciFolderTest {
    @Test
    fun onlyThisGamesFilesAreCarriedAndReplaced(): Unit = kotlinx.coroutines.runBlocking {
        val root = java.nio.file.Files.createTempDirectory("gci").toFile()
        try {
            val card = java.io.File(root, "Card A").apply { mkdirs() }
            java.io.File(card, "01-GALE-Smash.gci").writeText("john smash")
            java.io.File(card, "01-GMSE-Sunshine.gci").writeText("someone's sunshine")
            val game = GameKey.of("ngc", "GALE01", null, "Smash")
            val spot = SaveSpot(SaveKind.SAVE, "gc.gci", root = card.path, only = "01-GALE-")
            val d = SyncDevice(java.io.File(root, "state"), "pc", "PC")
            assertEquals(listOf("01-GALE-Smash.gci"), Slots.of(game, spot).files.map { it.path })
            d.handover("john", Slots.of(game, spot))
            d.handover("mark", Slots.of(game, spot))
            assertTrue(!java.io.File(card, "01-GALE-Smash.gci").exists())
            assertEquals("someone's sunshine", java.io.File(card, "01-GMSE-Sunshine.gci").readText(), "another game's save on the card is never touched")
            d.handover("john", Slots.of(game, spot))
            assertEquals("john smash", java.io.File(card, "01-GALE-Smash.gci").readText())
        } finally { root.deleteRecursively() }
    }
}
