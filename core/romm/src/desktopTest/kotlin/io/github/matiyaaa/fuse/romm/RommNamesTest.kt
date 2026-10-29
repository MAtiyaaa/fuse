package io.github.matiyaaa.fuse.romm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RommNamesTest {
    private fun rom(id: Long, slug: String, name: String, file: String, multi: Boolean = false) =
        RommRom(id = id, platformId = slug.hashCode().toLong(), platformSlug = slug, name = name, fsName = file, multi = multi)

    private fun apart(vararg roms: RommRom) = RommNames.apart(roms.toList()) { it.name }

    @Test
    fun gamesGivenOneNameAreNamedByTheirFiles() {
        // As a real server had them: four games matched to one, all called by the first one's name.
        val names = apart(
            rom(209, "ps2", "Tom Clancy's Splinter Cell", "Tom Clancy's Splinter Cell - Pandora Tomorrow.iso"),
            rom(210, "ps2", "Tom Clancy's Splinter Cell", "Tom Clancy's Splinter Cell.iso"),
            rom(212, "ps2", "Tom Clancy's Splinter Cell", "Tom Clancy's Splinter Cell - Double Agent.iso"),
            rom(217, "ps2", "Tom Clancy's Splinter Cell", "Tom Clancy's Splinter Cell - Chaos Theory.iso"),
        )
        assertEquals(
            mapOf(
                209L to "Tom Clancy's Splinter Cell: Pandora Tomorrow",
                212L to "Tom Clancy's Splinter Cell: Double Agent",
                217L to "Tom Clancy's Splinter Cell: Chaos Theory",
            ),
            names,
        )
    }

    @Test
    fun discsKeptApartSayWhichDisc() {
        val names = apart(
            rom(41, "ngc", "Metal Gear Solid: The Twin Snakes", "Metal Gear Solid - The Twin Snakes (Disc 2).rvz"),
            rom(42, "ngc", "Metal Gear Solid: The Twin Snakes", "Metal Gear Solid - The Twin Snakes (Disc 1).rvz"),
        )
        assertEquals("Metal Gear Solid: The Twin Snakes (Disc 2)", names[41])
        assertEquals("Metal Gear Solid: The Twin Snakes (Disc 1)", names[42])
    }

    @Test
    fun regionsThenFormatsTellCopiesApart() {
        val regions = apart(
            rom(1, "snes", "Chrono Trigger", "Chrono Trigger (USA).sfc"),
            rom(2, "snes", "Chrono Trigger", "Chrono Trigger (Japan).sfc"),
        )
        assertEquals("Chrono Trigger (USA)", regions[1])
        assertEquals("Chrono Trigger (Japan)", regions[2])
        val formats = apart(
            rom(60, "3ds", "Tomodachi Life", "Tomodachi Life.3ds"),
            rom(348, "3ds", "Tomodachi Life", "Tomodachi Life.cci"),
        )
        assertEquals("Tomodachi Life (3DS)", formats[60])
        assertEquals("Tomodachi Life (CCI)", formats[348])
    }

    @Test
    fun theSameGameOnOtherSystemsAndNamesThePersonGaveStayAsTheyAre() {
        // Deadpool on three systems is three games, each told by its system where they are listed together.
        assertTrue(apart(rom(221, "ps3", "Deadpool", "Deadpool.iso"), rom(251, "ps4", "Deadpool", "Deadpool"), rom(339, "xbox360", "Deadpool", "Deadpool.iso")).isEmpty())
        val named = RommNames.apart(listOf(rom(1, "ps2", "Psychonauts", "Psychonauts (USA).iso"), rom(2, "ps2", "Psychonauts", "Psychonauts (Europe).iso"))) { r -> if (r.id == 1L) null else r.name }
        assertTrue(named.isEmpty())
    }

    @Test
    fun copiesLeftUnfinishedAreKnown() {
        assertTrue(RommContent.unfinished(rom(112, "ps2", "Jak II", "Jak II.iso.part")))
        assertTrue(RommContent.unfinished(rom(1, "ps2", "Game", "Game.iso.filepart")))
        assertTrue(RommContent.unfinished(rom(2, "ps2", "Game", "Game.ISO.CRDOWNLOAD")))
        assertFalse(RommContent.unfinished(rom(176, "ps2", "Jak II", "Jak II.iso")))
        // A folder is the server's own: its name says nothing about a copy under way.
        assertFalse(RommContent.unfinished(rom(3, "switch", "Game", "Game.part", multi = true)))
    }
}
