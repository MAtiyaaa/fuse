package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where each emulator's mods go for a game, found as its saves are. */
class ModFoldersTest {
    private class FakeEnv(override val host: String, override val home: String, val files: Map<String, String>) : SaveEnvironment {
        override fun exists(path: String) = path in files || files.keys.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") } || files[path] == ""
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
    }

    private fun q(platform: String, emulator: String, serial: String?, rom: String = "/roms/$platform/game") =
        SaveQuery(GameKey.of(platform, serial, null, "Game"), platform, rom, emulator, serial = serial)

    @Test
    fun dolphinsTexturesAndModsAreInItsLoadFolder() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.local/share/dolphin-emu/GC/" to "", "/home/mo/.local/share/dolphin-emu/Load/" to ""))
        val h = ModFolders.locate(q("ngc", "linux.dolphin", "GALE01"), env).getOrThrow()
        assertEquals("/home/mo/.local/share/dolphin-emu/Load/Textures", h.folder)
        assertEquals("/home/mo/.local/share/dolphin-emu/Load/GraphicMods", h.graphicsMods)
        assertEquals("/home/mo/.local/share/dolphin-emu/GameSettings/GALE01.ini", h.settingsFile)
        // Without the game's id there is nowhere to put them.
        assertTrue("game's id" in ModFolders.locate(q("ngc", "linux.dolphin", null), env).exceptionOrNull()!!.message!!)
    }

    @Test
    fun edensModsGoInTheLoadFolderItsConfigNames() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf(
            "/home/mo/.local/share/eden/config/qt-config.ini" to "[Data%20Storage]\nload_directory\\default=false\nload_directory=/mnt/sd/eden-load\n",
            "/mnt/sd/eden-load/" to "",
        ))
        val h = ModFolders.locate(q("switch", "linux.eden", "0100F2C0115B6000"), env).getOrThrow()
        assertEquals("/mnt/sd/eden-load/0100F2C0115B6000", h.folder)
        // With nothing configured, `load` in its folder; an update's id files mods under the game's.
        val plain = FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.local/share/eden/nand/" to ""))
        assertEquals("/home/mo/.local/share/eden/load/0100F2C0115B6000", ModFolders.locate(q("switch", "linux.eden", "0100F2C0115B6800"), plain).getOrThrow().folder)
    }

    @Test
    fun ryujinxUsesTheGamesFolderAlreadyThereAsItIsNamed() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.config/Ryujinx/mods/contents/0100F2C0115B6000/Old/romfs/a" to "x"))
        assertEquals("/home/mo/.config/Ryujinx/mods/contents/0100F2C0115B6000", ModFolders.locate(q("switch", "linux.ryujinx", "0100f2c0115b6000"), env).getOrThrow().folder)
        val fresh = FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.config/Ryujinx/bis/" to ""))
        assertEquals("/home/mo/.config/Ryujinx/mods/contents/0100f2c0115b6000", ModFolders.locate(q("switch", "linux.ryujinx", "0100F2C0115B6000"), fresh).getOrThrow().folder)
    }

    @Test
    fun cemusPacksAreInItsDataFolder() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf("/home/mo/.local/share/Cemu/graphicPacks/" to ""))
        val h = ModFolders.locate(q("wiiu", "linux.cemu", "00050000101C9400"), env).getOrThrow()
        assertEquals("/home/mo/.local/share/Cemu/graphicPacks", h.folder)
        assertEquals("00050000101C9400", h.gameId)
    }

    @Test
    fun androidAndUnknownEmulatorsSayWhyNot() {
        val android = FakeEnv("ANDROID", "/storage/emulated/0", emptyMap())
        assertTrue(ModFolders.locate(q("switch", "android.eden", "0100F2C0115B6000"), android).isFailure)
        assertTrue(ModFolders.locate(q("snes", "linux.retroarch", null), android).isFailure)
    }
}
