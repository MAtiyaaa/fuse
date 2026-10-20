package io.github.matiyaaa.fuse.launch.ports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recomps as their own code has them: the folder each keeps its files in on every system, the
 * files Fuse may add as mods, release versions compared number by number, and their graphics file
 * changed one setting at a time with everything else left as it was.
 */
class RecompsTest {
    private val zelda = Recomps.byId("zelda64recomp")!!
    private val banjo = Recomps.byId("banjorecomp")!!

    @Test
    fun eachRecompKeepsItsFilesWhereItsCodeSays() {
        assertEquals("/home/ana/.config/Zelda64Recompiled", Recomps.folder(zelda, "linux", "/home/ana", emptyMap()))
        assertEquals("/srv/zelda", Recomps.folder(zelda, "linux", "/home/ana", mapOf("APP_FOLDER_PATH" to "/srv/zelda/")), "APP_FOLDER_PATH wins on Linux")
        assertEquals("C:/Users/ana/AppData/Local/BanjoRecompiled", Recomps.folder(banjo, "windows", "C:\\Users\\ana", mapOf("LOCALAPPDATA" to "C:\\Users\\ana\\AppData\\Local")))
        assertEquals("/Users/ana/Library/Application Support/Zelda64Recompiled", Recomps.folder(zelda, "macos", "/Users/ana", emptyMap()))
        assertEquals("/games/zelda", Recomps.folder(zelda, "windows", "C:/Users/ana", emptyMap(), programDir = "/games/zelda/", portable = true), "portable.txt keeps them beside the program")
        assertEquals("saves/mm.n64.us.1.0.bin", Recomps.saveFile(zelda))
        assertEquals("saves/bk.n64.us.1.0.bin", Recomps.saveFile(banjo))
        assertNull(Recomps.folder(zelda, "android", "/data", emptyMap()))
    }

    @Test
    fun recompsAreKnownByTheirRomsHashAndNothingElseIsOne() {
        assertEquals(banjo, Recomps.byHash("1B67585D56E07F8C"))
        assertNull(Recomps.byHash("0000000000000000"))
        assertNull(Recomps.of(ByteArray(64) { 1 }), "not an N64 ROM")
        // An N64 ROM's first bytes, but not one any recomp is made from.
        val rom = ByteArray(4096).also { it[0] = 0x80.toByte(); it[1] = 0x37; it[2] = 0x12; it[3] = 0x40 }
        assertNull(Recomps.of(rom))
        assertEquals(Recomps.all.size, Recomps.all.map { it.id }.toSet().size)
        assertEquals(Recomps.all.size, Recomps.all.map { it.xxh3 }.toSet().size)
    }

    @Test
    fun onlyModsAndTexturePacksAreAddedAndEachByItsOwnName() {
        assertTrue(Recomps.isMod("Randomizer.nrm") && Recomps.isMod("HD Textures.RTZ"))
        assertFalse(Recomps.isMod("readme.txt") || Recomps.isMod("mod.dll"))
        assertTrue(Recomps.canAdd("pack.zip") && Recomps.canAdd("x.nrm"))
        assertFalse(Recomps.canAdd("x.7z"))
        assertEquals("rando.nrm", Recomps.modName("MMRecompRando/mods/rando.nrm"))
        assertEquals("evil.nrm", Recomps.modName("../../evil.nrm"), "never a path out of the mods folder")
        assertEquals("tex.rtz", Recomps.modName("dir\\tex.rtz"))
        assertNull(Recomps.modName("dir/"))
        assertNull(Recomps.modName("lib/patch.so"))
    }

    @Test
    fun releasesAreComparedNumberByNumber() {
        assertEquals(true, Recomps.newer("v1.2.3", "1.2.2"))
        assertEquals(false, Recomps.newer("v1.2.2", "1.2.2"))
        assertEquals(false, Recomps.newer("1.0.3", "1.0.10"))
        assertEquals(true, Recomps.newer("v1.10.0", "1.9"))
        assertEquals(true, Recomps.newer("v0.2.0-dev", "0.1.0"))
        assertNull(Recomps.newer("nightly", "1.0"))
    }

    @Test
    fun graphicsChangeOneSettingAndLeaveTheRestAsTheyWere() {
        val file = """{"res_option":"Original","msaa_option":"MSAA4X","developer_mode":false,"rr_manual_value":60}"""
        val res = RecompSettings.graphics(zelda).single { it.key == "res_option" }
        assertEquals("Original (240p)", RecompSettings.current(file, res)?.label)
        val auto = res.choices.single { it.value.content == "Auto" }
        val changed = assertNotNull(RecompSettings.with(file, "res_option", auto.value))
        val o = Json.parseToJsonElement(changed) as JsonObject
        assertEquals(JsonPrimitive("Auto"), o["res_option"])
        assertEquals(JsonPrimitive("MSAA4X"), o["msaa_option"])
        assertEquals(JsonPrimitive(false), o["developer_mode"])
        assertEquals(JsonPrimitive(60), o["rr_manual_value"], "what Fuse doesn't show stays as it was")
        assertEquals(listOf("developer_mode", "msaa_option", "res_option", "rr_manual_value"), o.keys.sorted())
        // A file not there yet; one that can't be read is never written over.
        assertEquals(JsonPrimitive("Auto"), (Json.parseToJsonElement(RecompSettings.with(null, "res_option", auto.value)!!) as JsonObject)["res_option"])
        assertNull(RecompSettings.with("not json", "res_option", auto.value))
        assertNull(RecompSettings.current("{}", res), "its default, when the file doesn't say")
    }

    @Test
    fun theNewerSettingsMenuWritesSomeValuesItsOwnWay() {
        fun keys(r: Recomp, key: String) = RecompSettings.graphics(r).single { it.key == key }.choices.map { it.value.content }
        assertEquals(listOf("1", "2", "4"), keys(zelda, "ds_option"))
        assertEquals(listOf("0", "2", "4"), keys(banjo, "ds_option"), "RecompFrontend's Off is 0")
        assertTrue("Full" in keys(zelda, "hr_option") && "Expand" in keys(banjo, "hr_option"))
        assertTrue("MSAA8X" in keys(zelda, "msaa_option") && "MSAA8X" !in keys(banjo, "msaa_option"))
        val ds = RecompSettings.graphics(banjo).single { it.key == "ds_option" }
        assertEquals("Off", RecompSettings.current("""{"ds_option":0}""", ds)?.label)
        assertEquals("2x", RecompSettings.current("""{"ds_option":2}""", ds)?.label)
        assertTrue(RecompSettings.isControls("""{"keyboard":[]}""") && !RecompSettings.isControls("[]") && !RecompSettings.isControls(null))
    }
}
