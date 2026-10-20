package io.github.matiyaaa.fuse.launch.ports

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PlayStation games' native ports as their projects ship them: started with the dump's folder in
 * place, saves where their code keeps them, known by the game's title ids, and the list the
 * nightly check reads (ports/native.json) naming the same files Fuse finds them by.
 */
class PsPortsTest {
    private val bloodborne = PsPorts.byId("bloodborne-pc")!!
    private val spartan = PsPorts.byId("spartanport")!!
    private val pt = PsPorts.byId("pt-pc")!!

    @Test
    fun eachStartsAsItsProjectSaysWithTheDumpInPlace() {
        assertEquals(listOf("cmd.exe", "/c", "run.bat", "D:/Games/CUSA03173"), PsPorts.argv(bloodborne, "windows", "D:/Ports/Bloodborne/", "D:/Games/CUSA03173"))
        assertNull(PsPorts.argv(bloodborne, "linux", "/opt/bb", "/games/bb"), "no Linux build of its own")
        assertEquals(listOf("/home/ana/Games/spartanport-x86_64.AppImage", "--play"), PsPorts.argv(spartan, "linux", "/home/ana/Games", "/x"))
        assertEquals(listOf("D:/SpartanPort/Play SpartanPort.exe"), PsPorts.argv(spartan, "windows", "D:/SpartanPort", "/x"))
    }

    @Test
    fun savesAreWhereTheirCodeKeepsThem() {
        assertEquals("D:/Ports/Bloodborne/user/savedata", PsPorts.saves(bloodborne, "windows", "D:/Ports/Bloodborne", "unused"))
        val data = PsPorts.dataHome("/home/ana", emptyMap())
        assertEquals("/home/ana/.local/share", data)
        assertEquals("/data/xdg/spartanport/user/savedata", PsPorts.saves(spartan, "linux", "/home/ana/Games", PsPorts.dataHome("/home/ana", mapOf("XDG_DATA_HOME" to "/data/xdg"))))
        assertEquals("/opt/pt/data", PsPorts.saves(pt, "linux", "/opt/pt", data))
        assertEquals(listOf("PT_Save_Data0", "PT_Save_Data1"), pt.saveFiles, "only its two slots: data/ holds its settings and caches too")
    }

    @Test
    fun gamesAreKnownByTheirTitleIds() {
        assertEquals(listOf("spartanport"), PsPorts.forTitle("cusa01715").map { it.id })
        assertEquals(listOf("pt-pc"), PsPorts.forTitle("CUSA01127").map { it.id })
        assertTrue(PsPorts.forTitle("CUSA00001").isEmpty())
        assertEquals("God of War III Remastered", NativePorts.game("spartanport"))
        assertEquals("Banjo: Recompiled", NativePorts.name("banjorecomp"))
        assertTrue(NativePorts.isRecomp("zelda64recomp") && !NativePorts.isRecomp("pt-pc"))
    }

    @Test
    fun theNightlyListChecksTheFilesFuseFindsEachBy() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "ports/native.json") }.first { it.isFile }
        val ports = (Json.parseToJsonElement(file.readText()) as JsonObject)["ports"]!!.jsonArray.map { it.jsonObject }
        assertEquals(PsPorts.all.map { it.id }, ports.map { it["id"]!!.jsonPrimitive.content })
        for (p in ports) {
            val port = PsPorts.byId(p["id"]!!.jsonPrimitive.content)!!
            assertEquals(port.repo, p["repo"]!!.jsonPrimitive.content)
            for (b in (p["builds"] as JsonArray).map { it.jsonObject }) {
                val os = b["os"]!!.jsonPrimitive.content
                val marks = port.marks[os] ?: error("${port.id} has no $os build Fuse finds")
                val has = (b["has"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                // What the release's zip holds is what Fuse finds it by (P.T.'s game archives come from its installer, not the zip).
                assertTrue(has.all { it in marks }, "${port.id} $os: $has not in $marks")
            }
        }
    }
}
