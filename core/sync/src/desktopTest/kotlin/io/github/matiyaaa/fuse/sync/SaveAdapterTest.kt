package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveAdapterTest {
    /** A device's files as a map from path to contents ("" for a folder). */
    private class FakeEnv(override val host: String, override val home: String, val files: Map<String, String>) : SaveEnvironment {
        override fun exists(path: String) = path in files || files.keys.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") } || files[path] == ""
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
    }

    private val ct = GameKey.of("snes", null, null, "Chrono Trigger")

    @Test
    fun retroArchFollowsItsConfig() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf(
            "/home/mo/.config/retroarch/retroarch.cfg" to "savefile_directory = \"~/Saves\"\nsort_savefiles_enable = \"true\"\nsavestate_directory = \"default\"\n",
            "/home/mo/Saves/Snes9x/" to "",
        ))
        val spots = SaveAdapters.forEmulator("linux.retroarch")!!.locate(SaveQuery(ct, "snes", "/roms/snes/Chrono Trigger (USA).sfc", "linux.retroarch", core = "snes9x_libretro"), env)
        val save = spots.first { it.kind == SaveKind.SAVE }
        assertEquals("sram", save.format)
        assertEquals("/home/mo/Saves/Snes9x/Chrono Trigger (USA).srm", save.pathFor("save.srm"))
        val states = spots.first { it.kind == SaveKind.STATE }
        assertEquals("/home/mo/.config/retroarch/states/Chrono Trigger (USA).state1", states.pathFor("state1"))
        // States belong to their core.
        assertEquals("retroarch.state:snes9x_libretro", states.format)
    }

    @Test
    fun retroArchSavesBesideTheGameWhenSetSo() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.config/retroarch/retroarch.cfg" to "savefiles_in_content_dir = \"true\"", "/roms/snes/x" to "x"))
        val save = SaveAdapters.forEmulator("retroarch")!!.locate(SaveQuery(ct, "snes", "/roms/snes/CT.sfc", "retroarch"), env).first()
        assertEquals("/roms/snes/CT.srm", save.pathFor("save.srm"))
    }

    @Test
    fun androidsPrivateFoldersAreSaidSoNotGuessed() {
        val env = FakeEnv("ANDROID", "/storage/emulated/0", mapOf("/storage/emulated/0/RetroArch/retroarch.cfg" to "savefile_directory = \"/storage/emulated/0/Android/data/com.retroarch/files/saves\""))
        val save = SaveAdapters.forEmulator("retroarch")!!.locate(SaveQuery(ct, "snes", "/storage/emulated/0/roms/CT.sfc", "retroarch"), env).first()
        assertFalse(save.available)
        assertNotNull(save.note)
    }

    @Test
    fun duckStationFindsTheGamesOwnCard() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.local/share/duckstation/memcards/Final Fantasy VII (Disc 1)_1.mcd" to "card"))
        val g = GameKey.of("psx", "SCUS-94163", null, "Final Fantasy VII")
        val spots = SaveAdapters.forEmulator("linux.duckstation")!!.locate(SaveQuery(g, "psx", "/roms/psx/ff7.chd", "linux.duckstation", serial = "SCUS94163", title = "Final Fantasy VII (Disc 1)"), env)
        assertEquals("/home/mo/.local/share/duckstation/memcards/Final Fantasy VII (Disc 1)_1.mcd", spots.first().pathFor("card1.mcd"))
        // The same card RetroArch's PlayStation cores keep.
        assertEquals(SaveAdapters.sramFormat("psx"), spots.first().format)
    }

    @Test
    fun ppssppSavesAreTheGamesFolders() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf(
            "/home/mo/.config/ppsspp/PSP/SAVEDATA/ULUS10041DATA00/DATA.BIN" to "x",
            "/home/mo/.config/ppsspp/PSP/SAVEDATA/ULUS10041SYS/PARAM.SFO" to "y",
            "/home/mo/.config/ppsspp/PSP/SAVEDATA/ULES00151/DATA.BIN" to "z",
        ))
        val g = GameKey.of("psp", "ULUS10041", null, "Lumines")
        val spot = SaveAdapters.forEmulator("ppsspp")!!.locate(SaveQuery(g, "psp", "/roms/psp/l.iso", "ppsspp", serial = "ULUS-10041"), env).single()
        assertEquals(listOf("ULUS10041DATA00", "ULUS10041SYS"), spot.folders)
        assertEquals("/home/mo/.config/ppsspp/PSP/SAVEDATA", spot.root)
    }

    @Test
    fun emulatorsWithoutAnAdapterSayWhy() {
        assertNull(SaveAdapters.forEmulator("linux.xemu"))
        assertTrue("disk image" in SaveAdapters.whyNot("linux.xemu"))
        assertTrue("private Android folder" in SaveAdapters.whyNot("xendroid"))
        // Switch, 3DS and Wii U emulators have their own now.
        assertNotNull(SaveAdapters.forEmulator("windows.eden"))
        assertNotNull(SaveAdapters.forEmulator("azahar"))
        assertNotNull(SaveAdapters.forEmulator("linux.cemu"))
        assertEquals("retroarch", SaveAdapters.baseId("linux.retroarch-steam").removeSuffix("-steam"))
    }

    @Test
    fun aSaveMovesBetweenDevicesWhoseGamesAreNamedDifferently() = runBlocking {
        // The Deck's game is "Chrono Trigger (USA).sfc"; the PC's is "CT.smc". The save is "save.srm" in both.
        val root = Files.createTempDirectory("adapters").toFile()
        val host = SyncHost(HostStore(File(root, "host")), port = 0, bind = "127.0.0.1").start()
        try {
            val address = "127.0.0.1:${host.boundPort()}"
            val http = SyncClient.defaultClient()
            suspend fun device(name: String): Pair<SyncClient, SyncDevice> {
                val id = "dev-" + SyncCrypto.token(8)
                val link = SyncClient.pair(address, host.newPairingCode(), id, name, "LINUX", http)
                return SyncClient(link, http) to SyncDevice(File(root, name), id, name)
            }
            val (deckClient, deck) = device("Deck")
            val (pcClient, pc) = device("PC")
            val p = deckClient.createProfile(NewProfile("Mo", "fox")).id
            val deckRoms = File(root, "deck/roms").apply { mkdirs() }
            val pcRoms = File(root, "pc/roms").apply { mkdirs() }
            File(deckRoms, "Chrono Trigger (USA).srm").writeText("deck progress")
            val q = { rom: File -> SaveQuery(ct, "snes", rom.path.replace('\\', '/'), "mgba") }
            val env = FileSaveEnvironment("LINUX", root.path)
            val deckSpot = SaveAdapters.forEmulator("mgba")!!.locate(q(File(deckRoms, "Chrono Trigger (USA).sfc")), env).single()
            // mGBA keeps .sav beside the game; write it there.
            File(deckSpot.pathFor("save.srm")!!).writeText("deck progress")
            assertNotNull(deck.capture(p, Slots.of(ct, deckSpot), 600))
            deck.flush(deckClient)
            val pcSpot = SaveAdapters.forEmulator("mgba")!!.locate(q(File(pcRoms, "CT.smc")), env).single()
            assertIs<PrepareResult.Updated>(pc.prepare(pcClient, p, Slots.of(ct, pcSpot)))
            assertEquals("deck progress", File(pcRoms, "CT.sav").readText())
            // A state from RetroArch's core can't go to a device playing with another core.
            val state = SaveSlotFormats.compatible("retroarch.state:snes9x_libretro", "retroarch.state:bsnes_libretro")
            assertFalse(state)
        } finally {
            host.stop()
            root.deleteRecursively()
        }
    }
}
