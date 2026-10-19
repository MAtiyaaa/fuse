package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each emulator's save states, found where its own code keeps them, as each game's alone. */
class SaveStatesTest {
    /** A device's files: text and folders ("" in [files]). */
    private class FakeEnv(override val host: String, override val home: String, val files: Map<String, String> = emptyMap(), val vars: Map<String, String> = emptyMap()) : SaveEnvironment {
        override fun exists(path: String) = path in files || files.keys.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") } || files[path] == ""
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.filter { it.isNotEmpty() }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
        override fun env(name: String) = vars[name]
    }

    private fun q(platform: String, rom: String, emulator: String, serial: String? = null) =
        SaveQuery(GameKey.of(platform, null, null, "Game"), platform, rom, emulator, serial = serial)

    private fun states(emulator: String, query: SaveQuery, env: SaveEnvironment): SaveSpot {
        val adapter = SaveAdapters.forEmulator(emulator)!!
        assertTrue(adapter.keepsStates, "$emulator keeps states")
        return adapter.locate(query, env).single { it.kind == SaveKind.STATE }
    }

    @Test
    fun pcsx2sAreTheGamesBySerialInSstatesOrWhereItsSettingsMovedThem() {
        val data = "/home/mo/.config/PCSX2"
        val game = q("ps2", "/roms/ps2/Okami.iso", "linux.pcsx2", serial = "SLUS-21115")
        val usual = states("linux.pcsx2", game, FakeEnv("LINUX", "/home/mo", mapOf("$data/inis/PCSX2.ini" to "[Folders]\nMemoryCards = memcards\n")))
        assertEquals(Triple("pcsx2.state", "$data/sstates", "SLUS-21115 ("), Triple(usual.format, usual.root, usual.only), "\"SLUS-21115 (1A2B3C4D).01.p2s\" and its resume")
        val moved = states("linux.pcsx2", game, FakeEnv("LINUX", "/home/mo", mapOf("$data/inis/PCSX2.ini" to "[Folders]\nSavestates = /games/states\n")))
        assertEquals("/games/states", moved.root)
    }

    @Test
    fun ppssppsAreTheGamesByItsIdInPpssppState() {
        val stick = "/home/mo/.config/ppsspp"
        val spot = states("ppsspp", q("psp", "/roms/psp/Patapon.iso", "ppsspp", serial = "UCUS98711"), FakeEnv("LINUX", "/home/mo", mapOf("$stick/PSP/SAVEDATA/" to "")))
        assertEquals(Triple("ppsspp.state", "$stick/PSP/PPSSPP_STATE", "UCUS98711_"), Triple(spot.format, spot.root, spot.only), "\"UCUS98711_1.01_0.ppst\" and its picture")
    }

    @Test
    fun dolphinsAreTheGamesBySixCharacterIdInStateSavesForWiiAndGameCube() {
        val user = "/home/mo/.dolphin-emu"
        val env = FakeEnv("LINUX", "/home/mo", mapOf("$user/Config/" to ""))
        for ((platform, id) in listOf("ngc" to "GALE01", "wii" to "RSBE01")) {
            val spot = states("dolphin", q(platform, "/roms/$platform/game.iso", "dolphin", serial = id), env)
            assertEquals(Triple("dolphin.state", "$user/StateSaves", "$id.s"), Triple(spot.format, spot.root, spot.only), platform)
        }
        assertTrue(SaveAdapters.forEmulator("dolphin")!!.locate(q("ngc", "/roms/ngc/game.iso", "dolphin", serial = "GALE"), env).none { it.kind == SaveKind.STATE }, "only a whole id tells the game's own")
    }

    @Test
    fun flycastsAreNamedAfterTheGamesFileInItsDataFolder() {
        val data = "/home/mo/.local/share/flycast"
        val spot = states("flycast", q("dc", "/roms/dc/Crazy Taxi.gdi", "flycast"), FakeEnv("LINUX", "/home/mo", mapOf("$data/" to "", "/home/mo/.config/flycast/emu.cfg" to "[config]\nPerGameVmu = no\n")))
        assertEquals("$data/Crazy Taxi.state", spot.pathFor("Crazy Taxi.state"), "the first slot has no number")
        assertEquals("$data/Crazy Taxi_3.state", spot.pathFor("Crazy Taxi_3.state"))
    }

    @Test
    fun handheldsKeepTheirsBesideTheGameUnlessMoved() {
        val melon = states("linux.melonds", q("nds", "/roms/nds/Mario Kart DS.nds", "linux.melonds"), FakeEnv("LINUX", "/home/mo"))
        assertEquals((1..8).map { "/roms/nds/Mario Kart DS.ml$it" }, melon.files.map { it.path })
        val moved = states(
            "linux.melonds", q("nds", "/roms/nds/Mario Kart DS.nds", "linux.melonds"),
            FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.config/melonDS/melonDS.toml" to "[Instance0]\nSavestatePath = \"/states/ds\"\n")),
        )
        assertEquals("/states/ds/Mario Kart DS.ml1", moved.files.first().path)
        val mgba = states("linux.mgba", q("gba", "/roms/gba/Golden Sun.gba", "linux.mgba"), FakeEnv("LINUX", "/home/mo"))
        assertEquals((0..9).map { "/roms/gba/Golden Sun.ss$it" }, mgba.files.map { it.path })
    }

    @Test
    fun mednafensCarryTheGamesFingerprintAsItsSaveDoes() {
        val base = "/home/mo/.mednafen"
        val hash = "0123456789abcdef0123456789abcdef"
        val spot = states("mednafen", q("psx", "/roms/psx/Vagrant Story.cue", "mednafen"), FakeEnv("LINUX", "/home/mo", mapOf("$base/sav/Vagrant Story.$hash.sav" to "")))
        assertEquals("$base/mcs/Vagrant Story.$hash.mc0", spot.files.first().path)
        assertEquals(10, spot.files.size)
    }

    @Test
    fun arcadeEmulatorsKeepEachSystemsOwn() {
        val mame = states("mame", q("arcade", "/roms/arcade/sf2.zip", "mame"), FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.mame/mame.ini" to "state_directory           /states/mame\n")))
        assertEquals(Triple("mame.state", "/states/mame", listOf("sf2")), Triple(mame.format, mame.root, mame.folders), "a folder per system, each slot a file")
        val usual = states("mame", q("arcade", "/roms/arcade/sf2.zip", "mame"), FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.mame/" to "")))
        assertEquals("/home/mo/.mame/sta", usual.root)
        val supermodel = states("linux.supermodel", q("model3", "/roms/model3/daytona2.zip", "linux.supermodel"), FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.supermodel/" to "")))
        assertEquals((0..9).map { "/home/mo/.supermodel/Saves/daytona2.st$it" }, supermodel.files.map { it.path })
    }
}
