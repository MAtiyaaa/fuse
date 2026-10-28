package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResumePointsTest {
    private class Disk(val files: Map<String, Long>) : SaveEnvironment {
        override val host = "LINUX"
        override val home = "/home/u"
        override fun exists(path: String) = path in files
        override fun isDirectory(path: String) = files.keys.any { it.startsWith("$path/") }
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct()
        override fun readText(path: String, limit: Int): String? = null
        override fun modified(path: String): Long? = files[path]
    }

    @Test
    fun duckStationsResumeStateNotItsSlots() {
        val dir = "/home/u/.local/share/duckstation/savestates"
        val spot = SaveSpot(SaveKind.STATE, "duckstation.state", listOf(SpotFile("state1.sav", "$dir/SLUS-00001_1.sav"), SpotFile("resume.sav", "$dir/SLUS-00001_resume.sav")))
        assertEquals(ResumePoint("$dir/SLUS-00001_resume.sav", 20L, "duckstation.state"), ResumePoints.find(listOf(spot), Disk(mapOf("$dir/SLUS-00001_1.sav" to 10L, "$dir/SLUS-00001_resume.sav" to 20L))))
        // Only a slot the person chose: nothing to continue from.
        assertNull(ResumePoints.find(listOf(spot), Disk(mapOf("$dir/SLUS-00001_1.sav" to 10L))))
    }

    @Test
    fun pcsx2sResumeStateOfThisGameOnly() {
        val dir = "/home/u/.config/PCSX2/sstates"
        val spot = SaveSpot(SaveKind.STATE, "pcsx2.state", root = dir, only = "SLUS-20946 (")
        val disk = Disk(
            mapOf(
                "$dir/SLUS-20946 (2C8A0D9B).01.p2s" to 30L,
                "$dir/SLUS-20946 (2C8A0D9B).resume.p2s" to 20L,
                "$dir/SLUS-20946 (AAAAAAAA).resume.p2s" to 40L,
                "$dir/SLES-50000 (12345678).resume.p2s" to 90L,
            ),
        )
        assertEquals("$dir/SLUS-20946 (AAAAAAAA).resume.p2s", ResumePoints.find(listOf(spot), disk)?.path)
    }

    @Test
    fun retroArchsAutomaticState() {
        val spot = SaveSpot(SaveKind.STATE, "retroarch.state:mgba", listOf(SpotFile("state.auto", "/s/a.state.auto"), SpotFile("state", "/s/a.state")))
        assertEquals("/s/a.state.auto", ResumePoints.find(listOf(spot), Disk(mapOf("/s/a.state.auto" to 5L)))?.path)
        // A save, not a state, never counts; nor a state Fuse can't reach.
        assertNull(ResumePoints.find(listOf(spot.copy(kind = SaveKind.SAVE)), Disk(mapOf("/s/a.state.auto" to 5L))))
        assertNull(ResumePoints.find(listOf(spot.copy(available = false)), Disk(mapOf("/s/a.state.auto" to 5L))))
    }
}
