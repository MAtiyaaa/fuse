package io.github.matiyaaa.fuse.library.movein

import io.github.matiyaaa.fuse.model.PlayStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moving in: what ES-DE (es-de.org, its gamelist.xml and custom collections), RetroArch (runtime
 * logs and content_favorites.lpl), Pegasus (favorites.txt) and Steam (localconfig.vdf) keep, read
 * as they write it, and matched to the games here.
 */
class MoveInTest {
    @Test
    fun anEsDeGamelistGivesEachGamesMarks() {
        val xml = """<?xml version="1.0"?>
            <alternativeEmulator><label>DuckStation</label></alternativeEmulator>
            <gameList>
                <game>
                    <path>./Crash Bandicoot (USA).chd</path>
                    <name>Crash Bandicoot</name>
                    <rating>0.8</rating>
                    <favorite>true</favorite>
                    <completed>true</completed>
                    <playcount>3</playcount>
                    <lastplayed>20231015T201500</lastplayed>
                </game>
                <game><path>./Tom &amp; Jerry.chd</path><hidden>true</hidden></game>
                <game><path>./Untouched.chd</path><name>Nothing said</name></game>
                <folder><path>./Final Fantasy VII</path><favorite>true</favorite><gametime>7200</gametime></folder>
            </gameList>"""
        val games = EsDeGamelist.parse(xml, "psx")
        assertEquals(listOf("Crash Bandicoot (USA).chd", "Tom & Jerry.chd", "Final Fantasy VII"), games.map { it.path })
        val crash = games[0]
        assertTrue(crash.favorite)
        assertEquals(PlayStatus.BEATEN, crash.status)
        assertEquals(8, crash.rating)
        assertEquals(epochOf(2023, 10, 15, 20, 15, 0), crash.lastPlayedAt)
        assertTrue(games[1].hidden)
        assertEquals(7_200L, games[2].seconds)
        // Local time: a day starting an hour ahead of UTC is an hour earlier.
        assertEquals(epochOf(2023, 10, 15, 19, 15, 0), EsDeGamelist.time("20231015T201500", 3_600_000))
        assertNull(EsDeGamelist.time("not a date"))
    }

    @Test
    fun collectionsRunsAndFavouritesAreReadAsWritten() {
        val c = EsDeCollection.parse("custom-Couch games.cfg", "%ROMPATH%/psx/Crash.chd\n%ROMPATH%/n64/Mario Kart 64.z64\n\n")!!
        assertEquals("Couch games", c.name)
        assertEquals(listOf("psx/Crash.chd", "n64/Mario Kart 64.z64"), c.paths)
        assertEquals("psx", EsDeCollection.systemOf(c.paths[0]))
        assertNull(EsDeCollection.systemOf("/roms/psx/Crash.chd"))

        val run = RetroArchRuntime.parse("""{"version": "1.0", "runtime": "12:03:09", "last_played": "2024-02-01 08:30:00"}""", "Crash Bandicoot (USA)")!!
        assertEquals(12 * 3_600L + 3 * 60 + 9, run.seconds)
        assertEquals(epochOf(2024, 2, 1, 8, 30, 0), run.lastPlayedAt)
        assertNull(RetroArchRuntime.parse("""{"runtime": "0:00:00"}""", "Never"))

        val favourites = RetroArchFavourites.parse("""{"version": "1.5", "items": [{"path": "/roms/snes/Earthbound.zip#Earthbound.sfc", "label": "Earthbound"}, {"path": "C:\\Games\\psx\\Crash.chd"}]}""")
        assertEquals(listOf("/roms/snes/Earthbound.zip", "C:\\Games\\psx\\Crash.chd"), favourites.map { it.path })
        assertEquals("Crash.chd", favourites[1].fileName)

        assertEquals(listOf("/roms/gba/Golden Sun.gba"), PegasusFavourites.parse("# favourites\n/roms/gba/Golden Sun.gba\n").map { it.path })

        val steam = SteamPlaytime.parse(
            """"UserLocalConfigStore" { "Software" { "Valve" { "Steam" { "apps" {
                "1145360" { "LastPlayed" "1700000000" "Playtime" "754" }
                "220" { "Playtime" "0" }
            } } } } }""",
        )
        assertEquals(1, steam.size)
        assertEquals(1145360L, steam[0].steamAppId)
        assertEquals(754 * 60L, steam[0].seconds)
        assertEquals(1_700_000_000_000L, steam[0].lastPlayedAt)
    }

    @Test
    fun gamesMatchByFileThenNameOnTheirSystem() {
        val here = listOf(
            HereGame(1, "psx", "/roms/psx/Crash.chd", "/roms/psx/Crash.chd"),
            HereGame(2, "ps2", "/roms/ps2/Crash.iso", "/roms/ps2/Crash.iso"),
            HereGame(3, "snes", "/roms/snes/Earthbound.zip", "/roms/snes/Earthbound.zip"),
            HereGame(4, "psx", "/roms/psx/Spyro.chd", "/roms/psx/Spyro.chd"),
            HereGame(5, "steam", "steam://1145360", "steam://1145360", steamAppId = 1145360),
            HereGame(6, "gba", "/roms/gba/Same.gba", "/roms/gba/Same.gba"),
            HereGame(7, "gba", "/other/gba/Same.gba", "/other/gba/Same.gba"),
        )
        val platformOf = { s: String -> mapOf("psx" to "psx", "snes" to "snes", "gba" to "gba")[s] }
        val moved = listOf(
            MovedGame("/roms/snes/Earthbound.zip", favorite = true),
            MovedGame("Crash.chd", system = "psx", favorite = true),
            MovedGame("Spyro", seconds = 60),
            MovedGame("steam:1145360", seconds = 60, steamAppId = 1145360),
            MovedGame("Same.gba", system = "gba", favorite = true),
            MovedGame("Nowhere.chd", system = "psx", favorite = true),
        )
        val m = MoveInMatch.match(moved, here, platformOf)
        assertEquals(3L, m[moved[0]])
        assertEquals(1L, m[moved[1]])
        // RetroArch's name without its extension.
        assertEquals(4L, m[moved[2]])
        assertEquals(5L, m[moved[3]])
        // Two of that name on one system: neither is guessed at.
        assertNull(m[moved[4]])
        assertNull(m[moved[5]])
    }
}
