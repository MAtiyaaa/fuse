package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RetroArch's saves as the configuration in effect for a game says, and as each core really keeps them. */
class RetroArchCoresTest {
    private class Env(val files: Map<String, String>) : SaveEnvironment {
        override val host = "LINUX"
        override val home = "/home/mo"
        override fun exists(path: String) = path in files || files.keys.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") }
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
    }

    private val ra = "/home/mo/.config/retroarch"
    private fun q(platform: String, rom: String, core: String, serial: String? = null) =
        SaveQuery(GameKey.of(platform, serial, null, "Game"), platform, rom, "retroarch", core = core, serial = serial, title = "Game")

    @Test
    fun theGamesOwnOverrideWinsAndTheClockTravelsWithTheSave() {
        val env = Env(mapOf(
            "$ra/retroarch.cfg" to "savefile_directory = \"/mnt/saves\"\n",
            "$ra/config/Gambatte/Gambatte.cfg" to "savefile_directory = \"/mnt/gb\"\n",
            "$ra/config/Gambatte/Pokemon Crystal.cfg" to "savefile_directory = \"/mnt/crystal\"\n",
            "/mnt/crystal/" to "",
        ))
        val save = SaveAdapters.forEmulator("retroarch")!!.locate(q("gbc", "/roms/gbc/Pokemon Crystal.gbc", "gambatte"), env).first { it.kind == SaveKind.SAVE }
        assertEquals(listOf(SpotFile("save.srm", "/mnt/crystal/Pokemon Crystal.srm"), SpotFile("save.rtc", "/mnt/crystal/Pokemon Crystal.rtc")), save.files)
        // Without the game's override, the core's.
        val other = SaveAdapters.forEmulator("retroarch")!!.locate(q("gbc", "/roms/gbc/Tetris.gbc", "gambatte"), env).first { it.kind == SaveKind.SAVE }
        assertEquals("/mnt/gb/Tetris.srm", other.files.first().path)
    }

    @Test
    fun flycastKeepsOneSharedVmuUnlessGamesGetTheirOwn() {
        val shared = Env(mapOf("$ra/retroarch.cfg" to "", "$ra/saves/" to ""))
        val vmu = SaveAdapters.forEmulator("retroarch")!!.locate(q("dc", "/roms/dc/Sonic.chd", "flycast", serial = "MK-51000"), shared).first { it.kind != SaveKind.STATE }
        assertEquals(SaveKind.MEMORY_CARD, vmu.kind)
        assertEquals("$ra/system/dc/vmu_save_A1.bin", vmu.files.single().path)
        val own = Env(mapOf("$ra/retroarch.cfg" to "", "$ra/saves/" to "", "$ra/config/retroarch-core-options.cfg" to "flycast_per_content_vmus = \"VMU A1\"\n"))
        val game = SaveAdapters.forEmulator("retroarch")!!.locate(q("dc", "/roms/dc/Sonic.chd", "flycast", serial = "MK-51000"), own).first { it.kind != SaveKind.STATE }
        assertEquals("dc.vmu.game", game.format)
        assertEquals("$ra/saves/MK-51000.A1.bin", game.files.single().path)
    }

    @Test
    fun coresThatKeepSavesInTheirOwnStorageSaySoInsteadOfPretending() {
        val env = Env(mapOf("$ra/retroarch.cfg" to "", "$ra/saves/" to ""))
        val dolphin = SaveAdapters.forEmulator("retroarch")!!.locate(q("ngc", "/roms/gc/Game.iso", "dolphin"), env).first { it.kind == SaveKind.SAVE }
        assertFalse(dolphin.available)
        assertTrue("Dolphin itself syncs" in dolphin.note!!)
        val pure = SaveAdapters.forEmulator("retroarch")!!.locate(q("dos", "/roms/dos/Doom.zip", "dosbox_pure"), env).first { it.kind == SaveKind.SAVE }
        assertEquals("$ra/saves/Doom.pure.zip", pure.files.first().path)
    }
}

class Rpcs3UsersTest {
    private class Env(val files: Map<String, String>) : SaveEnvironment {
        override val host = "LINUX"
        override val home = "/home/mo"
        override fun exists(path: String) = path in files || files.keys.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") }
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
    }

    private val base = "/home/mo/.config/rpcs3"
    private val q = SaveQuery(GameKey.of("ps3", "BLUS30109", null, "Game"), "ps3", "/roms/ps3/Game", "rpcs3", serial = "BLUS30109", title = "Game")

    @Test
    fun theActiveUsersSavesNotTheFirstUsers() {
        val env = Env(mapOf(
            "$base/dev_hdd0/home/00000001/savedata/BLUS30109-A/SAVE" to "x",
            "$base/dev_hdd0/home/00000002/savedata/" to "",
            "$base/GuiConfigs/CurrentSettings.ini" to "[Meta]\nactive_user=00000002\n",
        ))
        assertEquals("$base/dev_hdd0/home/00000002/savedata", SaveAdapters.forEmulator("rpcs3")!!.locate(q, env).single().root)
        // No active user said: the only one with this game's save.
        val noActive = Env(env.files - "$base/GuiConfigs/CurrentSettings.ini")
        assertEquals("$base/dev_hdd0/home/00000001/savedata", SaveAdapters.forEmulator("rpcs3")!!.locate(q, noActive).single().root)
        // Two users, neither with it, none said: Fuse doesn't guess.
        val unsure = Env(mapOf("$base/dev_hdd0/home/00000001/savedata/" to "", "$base/dev_hdd0/home/00000002/savedata/" to ""))
        assertFalse(SaveAdapters.forEmulator("rpcs3")!!.locate(q, unsure).single().available)
    }
}
