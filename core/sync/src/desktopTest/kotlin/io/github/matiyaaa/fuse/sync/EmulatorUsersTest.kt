package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Emulators with users of their own: Fuse follows the one each plays as, and never guesses between several. */
class EmulatorUsersTest {
    private class Env(override val host: String, val files: Map<String, String>) : SaveEnvironment {
        override val home = if (host == "WINDOWS") "C:/Users/mo" else "/home/mo"
        override fun exists(path: String) = path in files || files.keys.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") }
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
    }

    @Test
    fun cemuKeepsTheAccountItPlaysAsAndTheSharedData() {
        val data = "/home/mo/.local/share/Cemu"
        val user = "$data/mlc01/usr/save/00050000/1010ec00/user"
        val env = Env("LINUX", mapOf("/home/mo/.config/Cemu/settings.xml" to "<content><AccountId>80000002</AccountId></content>",
            "$user/80000001/a" to "x", "$user/80000002/a" to "x", "$user/common/c" to "x"))
        val q = SaveQuery(GameKey.of("wiiu", "000500001010EC00", null, "MK8"), "wiiu", "/roms/wiiu/MK8/code/Turbo.rpx", "cemu", serial = "000500001010EC00")
        val spot = SaveAdapters.forEmulator("cemu")!!.locate(q, env).single()
        assertEquals(user, spot.root)
        assertEquals(listOf("80000002", "common"), spot.folders)
    }

    @Test
    fun xeniaUsesTheSignedInProfile() {
        val content = "C:/Users/mo/Documents/Xenia/content"
        val env = Env("WINDOWS", mapOf(
            "C:/Emu/Xenia/xenia-canary.config.toml" to "[Profiles]\nlogged_profile_slot_0_xuid = \"E030000000000002\"\n",
            "C:/Emu/Xenia/content/E030000000000001/4D5307E6/00000001/s" to "x",
            "C:/Emu/Xenia/content/E030000000000002/" to "",
        ))
        val q = SaveQuery(GameKey.of("xbox360", "4D5307E6", null, "Halo 3"), "xbox360", "C:/roms/Halo 3 [4D5307E6].iso", "xenia-canary", emulatorPath = "C:/Emu/Xenia/xenia_canary.exe", serial = "4D5307E6")
        assertEquals("C:/Emu/Xenia/content/E030000000000002/4D5307E6/00000001", SaveAdapters.forEmulator("xenia-canary")!!.locate(q, env).single().root)
        // Two profiles, none signed in, neither with the game: no guess.
        val unsure = Env("WINDOWS", mapOf("$content/E030000000000001/" to "", "$content/E030000000000002/" to ""))
        assertFalse(SaveAdapters.forEmulator("xenia")!!.locate(q.copy(emulatorPath = null, emulatorId = "xenia"), unsure).single().available)
    }

    @Test
    fun vita3kUsesItsUserAndPpssppItsInstalledMemoryStick() {
        val ux0 = "/home/mo/.local/share/Vita3K/Vita3K/ux0"
        val vita = Env("LINUX", mapOf("/home/mo/.config/Vita3K/config.yml" to "user-id: '01'\n", "$ux0/user/00/savedata/" to "", "$ux0/user/01/savedata/" to ""))
        val vq = SaveQuery(GameKey.of("psvita", "PCSE00082", null, "Game"), "psvita", "/roms/vita/PCSE00082.vpk", "vita3k", serial = "PCSE00082")
        assertEquals("$ux0/user/01/savedata", SaveAdapters.forEmulator("vita3k")!!.locate(vq, vita).single().root)
        val psp = Env("WINDOWS", mapOf("C:/Emu/PPSSPP/installed.txt" to "D:/PSP Stick\n", "D:/PSP Stick/PSP/SAVEDATA/" to "", "C:/Emu/PPSSPP/memstick/PSP/" to ""))
        val pq = SaveQuery(GameKey.of("psp", "ULUS10041", null, "Game"), "psp", "C:/roms/game.iso", "ppsspp", emulatorPath = "C:/Emu/PPSSPP/PPSSPPWindows64.exe", serial = "ULUS10041")
        assertEquals("D:/PSP Stick/PSP/SAVEDATA", SaveAdapters.forEmulator("ppsspp")!!.locate(pq, psp).single().root)
    }
}
