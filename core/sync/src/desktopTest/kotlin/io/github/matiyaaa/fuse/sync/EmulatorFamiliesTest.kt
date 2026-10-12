package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bringing a person's things over between forks of one emulator: only what is theirs, never over what is there. */
class EmulatorFamiliesTest {
    private class FakeEnv(override val host: String, override val home: String, val files: Map<String, String>) : SaveEnvironment {
        override fun exists(path: String) = path in files || files.keys.any { it.startsWith("$path/") }
        override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") } || files[path] == ""
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
        override fun readText(path: String, limit: Int) = files[path]
    }

    private val share = "/home/mo/.local/share"

    @Test
    fun familiesAreForksThatKeepThingsTheSameWay() {
        assertEquals(listOf("eden", "citron", "sudachi", "yuzu", "suyu", "torzu"), EmulatorFamilies.family("linux.eden-nightly"))
        assertEquals(listOf("azahar", "citra", "lime3ds"), EmulatorFamilies.family("windows.lime3ds"))
        assertNull(EmulatorFamilies.family("linux.ryujinx"))
    }

    @Test
    fun yuzusThingsComeOverToEdenWithoutReplacingAnything() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf(
            "$share/yuzu/keys/prod.keys" to "k",
            "$share/yuzu/nand/user/save/0000000000000000/A/0100F2C0115B6000/save.bin" to "s",
            "$share/yuzu/load/0100F2C0115B6000/Mod/romfs/a" to "m",
            "$share/yuzu/shader/vulkan.bin" to "x",
            "$share/yuzu/log/yuzu_log.txt" to "x",
            "$share/yuzu/config/qt-config.ini" to "x",
            "$share/eden/keys/prod.keys" to "eden's own",
            "$share/eden/config/qt-config.ini" to "eden's own",
            "$share/citron/" to "",
        ))
        val others = EmulatorFamilies.others(env, "linux.eden")
        // Citron's folder holds nothing of a person's, so there is nothing to bring from it.
        assertEquals(listOf("yuzu"), others.map { it.id })
        val move = EmulatorFamilies.plan(env, others.single(), FamilyFolder("eden", "Eden", "$share/eden"))
        assertEquals(
            listOf("load/0100F2C0115B6000/Mod/romfs/a", "nand/user/save/0000000000000000/A/0100F2C0115B6000/save.bin"),
            move.files.sorted(),
        )
        assertEquals(1, move.kept, "Eden's own keys stay")
        assertTrue(move.leftOut.any { "Shader caches" in it } && move.leftOut.any { "Settings" in it })
    }

    @Test
    fun citrasThingsComeOverToAzaharButItsSaveStatesStay() {
        val env = FakeEnv("LINUX", "/home/mo", mapOf(
            "$share/citra-emu/sdmc/Nintendo 3DS/a/b/title/00040000/00055d00/data/00000001/main" to "s",
            "$share/citra-emu/sysdata/aes_keys.txt" to "k",
            "$share/citra-emu/states/00040000000EC300.01.cst" to "st",
            "$share/azahar-emu/config/qt-config.ini" to "x",
        ))
        val from = EmulatorFamilies.others(env, "linux.azahar").single()
        assertEquals("Citra", from.name)
        val move = EmulatorFamilies.plan(env, from, FamilyFolder("azahar", "Azahar", EmulatorFamilies.folder(env, "azahar")!!))
        assertEquals(2, move.files.size)
        assertTrue(move.files.none { it.startsWith("states/") })
        assertTrue(move.leftOut.any { "Save states" in it })
    }
}
