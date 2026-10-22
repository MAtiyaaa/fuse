package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.model.AndroidPort
import io.github.matiyaaa.fuse.model.AndroidPortStatus
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameLocation
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.shell.store.PortMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which library games and installed apps an Android port is, and the order ports are offered in. */
class AndroidPortCatalogTest {
    private fun game(id: Long, file: String, platform: String) = Game(
        id = GameId(id), platformId = PlatformId(platform), titles = GameTitles(original = file),
        location = GameLocation(LibrarySourceId(1), "/roms/$platform/$file.z64", LocationKind.FILE, "/roms/$platform/$file.z64"),
    )

    private fun port(id: String, game: String, vararg systems: String, checked: Boolean = false, repo: String = "https://github.com/someone/$id", status: AndroidPortStatus = AndroidPortStatus.BETA, pkg: String? = null, titles: List<String> = emptyList(), roms: List<String> = emptyList()) =
        AndroidPort(id = id, name = id, game = game, titles = titles, systems = systems.toList(), repo = repo, checked = checked, status = status, packageName = pkg, roms = roms)

    @Test
    fun aGameIsMatchedByItsNameWhateverItsTagsAndArticles() {
        val mm = port("mm", "The Legend of Zelda: Majora's Mask", "n64")
        assertEquals(PortMatch.NAME, AndroidPortCatalog.match(mm, game(1, "Legend of Zelda, The - Majora's Mask (USA)", "n64")))
        assertEquals(PortMatch.NAME, AndroidPortCatalog.match(mm, game(2, "The Legend of Zelda - Majora's Mask (Europe) (En,Fr,De,Es)", "n64")))
        assertNull(AndroidPortCatalog.match(mm, game(3, "Legend of Zelda, The - Ocarina of Time (USA)", "n64")))
        assertNull(AndroidPortCatalog.match(mm, game(4, "Legend of Zelda, The - Majora's Mask (USA)", "ngc")), "another system's game is never it")
    }

    @Test
    fun aVersionsWordAndAccentsDontKeepAGameFromItsPort() {
        val red = port("gen1recomp", "Pokémon Red", "gb", "gbc", "gba", titles = listOf("Pokémon Yellow Version: Special Pikachu Edition", "Pokémon Emerald"))
        assertEquals(PortMatch.NAME, AndroidPortCatalog.match(red, game(1, "Pokemon - Red Version (USA, Europe) (SGB Enhanced)", "gb")))
        assertEquals(PortMatch.NAME, AndroidPortCatalog.match(red, game(2, "Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced)", "gbc")))
        assertEquals(PortMatch.NAME, AndroidPortCatalog.match(red, game(3, "Pokemon - Emerald Version (USA, Europe)", "gba")))
        assertNull(AndroidPortCatalog.match(red, game(4, "Pokemon - Blue Version (USA, Europe) (SGB Enhanced)", "gb")), "a game it doesn't name")
    }

    @Test
    fun theRomAPortIsMadeForIsSaidAboveItsName() {
        val mm = port("mm", "The Legend of Zelda: Majora's Mask", "n64", roms = listOf("ef18b4a9e2386169"))
        val g = game(1, "Legend of Zelda, The - Majora's Mask (USA)", "n64")
        assertEquals(PortMatch.ROM, AndroidPortCatalog.match(mm, g, romXxh3 = "EF18B4A9E2386169"))
        assertEquals(PortMatch.NAME, AndroidPortCatalog.match(mm, g, romXxh3 = "0000000000000000"), "another ROM is still the game by name")
    }

    @Test
    fun fusesCheckedEntryTakesThePlaceOfAListingOfTheSameProject() {
        val checked = listOf(port("starship-android", "Star Fox 64", "n64", checked = true, repo = "https://github.com/izzy2lost/Starship"))
        val listed = listOf(
            port("starship-android-listing", "Star Fox 64", "n64", repo = "https://github.com/IZZY2LOST/Starship/"),
            port("another", "Star Fox 64", "n64"),
        )
        assertEquals(listOf("starship-android", "another"), AndroidPortCatalog.merge(checked, listed).map { it.id })
    }

    @Test
    fun aCheckedEntryTakesItsGamesDetailsAndPicturesFromItsListing() {
        val checked = listOf(
            port("starship-android", "Star Fox 64", "n64", checked = true, repo = "https://github.com/izzy2lost/Starship"),
            port("pd-android", "Perfect Dark", "n64", checked = true, repo = "https://github.com/izzy2lost/Perfect-Dark").copy(cover = "https://example.org/pd-own.png", coverCredit = "Box art"),
        )
        val listed = listOf(
            // The same project: its game's details and its picture of the port.
            port("starship-listed", "Star Fox 64", "n64", repo = "https://github.com/izzy2lost/Starship").copy(
                publisher = "Nintendo", year = 1997, genre = "shooter", cover = "https://example.org/sf.png", coverCredit = "Wikipedia", screenshot = "https://example.org/starship.png",
            ),
            // Another project of the same game: its game's details, never its picture of another port.
            port("pd-other", "Perfect Dark", "n64").copy(publisher = "Rare", year = 2000, cover = "https://example.org/pd.png", coverCredit = "Wikipedia", screenshot = "https://example.org/other.png"),
        )
        val merged = AndroidPortCatalog.merge(checked, listed).associateBy { it.id }
        val sf = merged.getValue("starship-android")
        assertEquals(listOf("Nintendo", "1997", "shooter"), listOf(sf.publisher, sf.year.toString(), sf.genre))
        assertEquals("https://example.org/sf.png" to "Wikipedia", sf.cover to sf.coverCredit)
        assertEquals("https://example.org/starship.png", sf.screenshot)
        val pd = merged.getValue("pd-android")
        assertEquals("https://example.org/pd-own.png" to "Box art", pd.cover to pd.coverCredit, "its own box art stays")
        assertEquals("Rare", pd.publisher)
        assertNull(pd.screenshot, "another port's picture isn't this one's")
    }

    @Test
    fun aPortWithoutBoxArtTakesAnothersOfItsGameAndSystem() {
        val ports = AndroidPortCatalog.shareCovers(listOf(
            port("a", "The Legend of Zelda: Majora's Mask", "n64").copy(cover = "https://example.org/mm.png", coverCredit = "Wikipedia"),
            port("b", "Legend of Zelda - Majora's Mask", "n64"),
            port("c", "The Legend of Zelda: Majora's Mask", "3ds"),
        )).associateBy { it.id }
        assertEquals("https://example.org/mm.png" to "Wikipedia", ports.getValue("b").cover to ports.getValue("b").coverCredit)
        assertNull(ports.getValue("c").cover, "another system's game has its own box")
    }

    @Test
    fun portsOfOneGameAreOfferedVerifiedThenCheckedThenByTheRomThenSteadiest() {
        val ports = listOf(
            port("listed-stable", "x", "n64", status = AndroidPortStatus.STABLE) to PortMatch.NAME,
            port("checked-alpha", "x", "n64", checked = true, status = AndroidPortStatus.ALPHA) to PortMatch.NAME,
            port("checked-rom", "x", "n64", checked = true, status = AndroidPortStatus.BETA) to PortMatch.ROM,
            port("verified-alpha", "x", "n64", checked = true, status = AndroidPortStatus.ALPHA).copy(verified = "2026-10-22") to PortMatch.NAME,
            port("listed-alpha", "x", "n64", status = AndroidPortStatus.ALPHA) to PortMatch.NAME,
        )
        assertEquals(
            listOf("verified-alpha", "checked-rom", "checked-alpha", "listed-stable", "listed-alpha"),
            ports.sortedBy { (p, m) -> AndroidPortCatalog.order(p, m) }.map { it.first.id },
        )
    }

    @Test
    fun anAppIsKnownByItsPackageThenByItsWholeNameNeverAPart() {
        val ports = listOf(
            port("2 Ship 2 Harkinian (Android)", "The Legend of Zelda: Majora's Mask", "n64"),
            port("2 Ship 2 Harkinian (Outdated)", "The Legend of Zelda: Majora's Mask", "n64"),
            port("Starship", "Star Fox 64", "n64", pkg = "com.starship.android"),
            port("Ship of Harkinian", "The Legend of Zelda: Ocarina of Time", "n64"),
        )
        assertEquals(listOf("Starship"), AndroidPortCatalog.identify("com.starship.android", "Anything", ports).map { it.id })
        assertEquals(listOf("Ship of Harkinian"), AndroidPortCatalog.identify("org.other", "x", ports, pinned = mapOf("Ship of Harkinian" to "org.other")).map { it.id })
        val forks = AndroidPortCatalog.identify("com.unknown", "2 Ship 2 Harkinian", ports)
        assertEquals(2, forks.size, "both forks are named so")
        assertEquals("The Legend of Zelda: Majora's Mask", AndroidPortCatalog.oneGame(forks)?.game, "and both play one game")
        assertTrue(AndroidPortCatalog.identify("com.unknown", "Harkinian", ports).isEmpty(), "a part of a name is never enough")
        assertTrue(AndroidPortCatalog.identify("com.unknown", "Ship", ports).isEmpty())
        assertNull(AndroidPortCatalog.oneGame(ports.take(1) + ports.drop(3)), "ports of two games are no one game")
    }

    @Test
    fun theLikeliestGamesForAnAppComeFirst() {
        val games = listOf(
            game(1, "Star Fox 64 (USA) (Rev 1)", "n64"),
            game(2, "Legend of Zelda, The - Majora's Mask (USA)", "n64"),
            game(3, "Legend of Zelda, The - Ocarina of Time (USA)", "n64"),
            game(4, "Mario Kart 64 (USA)", "n64"),
        )
        assertEquals(listOf(2L, 3L), AndroidPortCatalog.guessGames("Zelda Majora's Mask Android", games).map { it.id.value })
        assertEquals(listOf(1L), AndroidPortCatalog.guessGames("Star Fox 64 Recompiled", games).map { it.id.value }.take(1))
        assertTrue(AndroidPortCatalog.guessGames("Calculator", games).isEmpty())
    }
}
