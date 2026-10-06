package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Each handheld emulator's save where its own code puts it, not one rule for all. */
class HandheldSavesTest {
    private class Env(override val host: String, override val home: String, val files: Map<String, String>, val vars: Map<String, String> = emptyMap(), val roots: List<String> = emptyList()) : SaveEnvironment {
        override fun exists(path: String) = path in files || files.keys.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") }
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
        override fun env(name: String) = vars[name]
        override fun storageRoots() = roots
    }

    private val platinum = GameKey.of("nds", null, null, "Pokemon Platinum")
    private val ruby = GameKey.of("gba", null, null, "Pokemon Ruby")

    private fun one(emulator: String, q: SaveQuery, env: SaveEnvironment) = SaveAdapters.forEmulator(emulator)!!.locate(q, env).single()

    @Test
    fun `melonDS on a computer follows SaveFilePath in its config, else saves beside the game`() {
        val q = SaveQuery(platinum, "nds", "/roms/nds/Pokemon Platinum.nds", "linux.melonds")
        val plain = one("linux.melonds", q, Env("LINUX", "/home/mo", emptyMap()))
        assertEquals("/roms/nds/Pokemon Platinum.sav", plain.pathFor("save.srm"))
        assertEquals(SaveConfidence.KNOWN, plain.confidence)

        val toml = "[Instance0]\nSaveFilePath = \"/home/mo/DS Saves\"\nSavestatePath = \"\"\n"
        val set = one("linux.melonds", q, Env("LINUX", "/home/mo", mapOf("/home/mo/.config/melonDS/melonDS.toml" to toml)))
        assertEquals("/home/mo/DS Saves/Pokemon Platinum.sav", set.pathFor("save.srm"))

        // The Flatpak's config counts too, and a save already beside the game is the one in use.
        val flat = Env("LINUX", "/home/mo", mapOf(
            "/home/mo/.var/app/net.kuribo64.melonDS/config/melonDS/melonDS.toml" to toml,
            "/roms/nds/Pokemon Platinum.sav" to "x",
        ))
        val found = one("linux.melonds", q, flat)
        assertEquals("/roms/nds/Pokemon Platinum.sav", found.pathFor("save.srm"))
        assertEquals(SaveConfidence.FOUND, found.confidence)

        // A portable melonDS on Windows keeps its config beside the program.
        val win = Env("WINDOWS", "C:/Users/mo", mapOf("D:/Emu/melonDS/portable/melonDS.toml" to "[Instance0]\nSaveFilePath = \"D:\\\\Saves\"\n"))
        val portable = one("windows.melonds", q.copy(romPath = "E:/DS/Pokemon Platinum.nds", emulatorId = "windows.melonds", emulatorPath = "D:/Emu/melonDS/melonDS.exe"), win)
        assertEquals("D:/Saves/Pokemon Platinum.sav", portable.pathFor("save.srm"))
    }

    @Test
    fun `melonDS on Android warns that only games in its own list save beside them`() {
        val q = SaveQuery(platinum, "nds", "/storage/emulated/0/ROMs/nds/Pokemon Platinum.nds", "melonds")
        val none = one("melonds", q, Env("ANDROID", "/storage/emulated/0", emptyMap()))
        assertEquals("/storage/emulated/0/ROMs/nds/Pokemon Platinum.sav", none.pathFor("save.srm"))
        assertTrue(none.available)
        assertTrue("ROM search folders" in none.note!!)

        val private = "/storage/emulated/0/Android/data/me.magnum.melonds.nightly/files/saves/Pokemon Platinum.sav"
        val inPrivate = one("melonds-nightly", q.copy(emulatorId = "melonds-nightly"), Env("ANDROID", "/storage/emulated/0", mapOf(private to "x")))
        assertEquals(private, inPrivate.pathFor("save.srm"))
        assertEquals(SaveConfidence.FOUND, inPrivate.confidence)
    }

    @Test
    fun `NooDS saves beside the game unless its separate saves folder is on`() {
        val q = SaveQuery(platinum, "nds", "/roms/Pokemon Platinum.nds", "linux.noods")
        assertEquals("/roms/Pokemon Platinum.sav", one("linux.noods", q, Env("LINUX", "/home/mo", emptyMap())).pathFor("save.srm"))
        val sep = Env("LINUX", "/home/mo", mapOf("/home/mo/.config/noods/noods.ini" to "directBoot=1\nsavesFolder=1\n"))
        assertEquals("/home/mo/.config/noods/saves/Pokemon Platinum.sav", one("linux.noods", q, sep).pathFor("save.srm"))
        // On Android the separate folder is NooDS's private one: said, not guessed at.
        val android = Env("ANDROID", "/storage/emulated/0", mapOf("/storage/emulated/0/Android/data/com.hydra.noods/files/noods.ini" to "savesFolder=1\n"))
        assertFalse(one("noods", q.copy(emulatorId = "noods"), android).available)
    }

    @Test
    fun `mGBA follows savegamePath, relative to its config folder`() {
        val q = SaveQuery(ruby, "gba", "/roms/gba/Pokemon Ruby.gba", "linux.mgba")
        val env = Env("LINUX", "/home/mo", mapOf("/home/mo/.config/mgba/config.ini" to "[ports.qt]\nsavegamePath=saves\n"))
        assertEquals("/home/mo/.config/mgba/saves/Pokemon Ruby.sav", one("linux.mgba", q, env).pathFor("save.srm"))
        assertEquals("/roms/gba/Pokemon Ruby.sav", one("linux.mgba", q, Env("LINUX", "/home/mo", emptyMap())).pathFor("save.srm"))
    }

    @Test
    fun `Mednafen's save is found by the game's name and fingerprint, with the GBA's EEPROM and clock`() {
        val q = SaveQuery(ruby, "gba", "/roms/gba/Pokemon Ruby.gba", "linux.mednafen")
        val hash = "784a036ff1aae709e90167186639b75e"
        val env = Env("LINUX", "/home/mo", mapOf("/home/mo/.mednafen/sav/Pokemon Ruby.$hash.sav" to "x", "/home/mo/.mednafen/mednafen.cfg" to "filesys.path_sav sav\n"))
        val spot = one("linux.mednafen", q, env)
        assertEquals("mednafen:gba", spot.format)
        assertEquals("/home/mo/.mednafen/sav/Pokemon Ruby.$hash.eep", spot.pathFor("save.eep"))
        assertEquals(SaveConfidence.FOUND, spot.confidence)
        // Never played there: Fuse can't name the file, so it says so.
        assertFalse(one("linux.mednafen", q, Env("LINUX", "/home/mo", mapOf("/home/mo/.mednafen/sav/Other.$hash.sav" to "x"))).available)
        // Two versions of the game: left alone.
        val two = Env("LINUX", "/home/mo", mapOf("/home/mo/.mednafen/sav/Pokemon Ruby.$hash.sav" to "x", "/home/mo/.mednafen/sav/Pokemon Ruby.${hash.reversed()}.sav" to "y"))
        assertFalse(one("linux.mednafen", q, two).available)
    }

    @Test
    fun `My Boy keeps its own save folder, and a save beside the game wins when that's where it is`() {
        val q = SaveQuery(ruby, "gba", "/storage/emulated/0/ROMs/Pokemon Ruby.gba", "my-boy")
        assertEquals("/storage/emulated/0/MyBoy/save/Pokemon Ruby.sav", one("my-boy", q, Env("ANDROID", "/storage/emulated/0", emptyMap())).pathFor("save.srm"))
        val beside = Env("ANDROID", "/storage/emulated/0", mapOf("/storage/emulated/0/ROMs/Pokemon Ruby.sav" to "x"))
        assertEquals("/storage/emulated/0/ROMs/Pokemon Ruby.sav", one("my-boy", q, beside).pathFor("save.srm"))
        assertEquals("/storage/emulated/0/MyOldBoy/save/Pokemon Ruby.sav", one("my-oldboy", q.copy(emulatorId = "my-oldboy"), Env("ANDROID", "/storage/emulated/0", emptyMap())).pathFor("save.srm"))
    }

    @Test
    fun `an emulator that doesn't publish its layout is a guess, and says so`() {
        val q = SaveQuery(ruby, "gba", "/storage/emulated/0/ROMs/Pokemon Ruby.gba", "pizza-boy-gba")
        val spot = one("pizza-boy-gba", q, Env("ANDROID", "/storage/emulated/0", emptyMap()))
        assertEquals(SaveConfidence.GUESS, spot.confidence)
        assertNotNull(spot.note)
    }
}
