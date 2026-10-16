package io.github.matiyaaa.fuse.library.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fuse Identity: the one way Fuse decides a game elsewhere is a game here, scenario by scenario. */
class GameIdentityTest {
    @Test
    fun aSwitchGameIsTheSameReleaseWhateverUpdateItCarries() {
        val romm = Ids.releaseTitle("Red Dead Redemption.xci")
        assertEquals(romm, Ids.releaseTitle("Red Dead Redemption [01007820196A6000] [v131072] (1G+1U).nsp"))
        assertEquals(romm, Ids.releaseTitle("[ROMsNS] Red Dead Redemption v0 (01007820196A6000) (BASE).nsp"))
        assertEquals(Ids.releaseTitle("Mario and Sonic at the Olympic Games Tokyo 2020.xci"), Ids.releaseTitle("Mario & Sonic at the Olympic Games Tokyo 2020 [010019401051C000][v0].xci"))
        // A version written as a version still tells releases apart.
        assertTrue(Ids.releaseTitle("Mario Kart 8 Deluxe (USA) (v1.7.1).xci") != Ids.releaseTitle("Mario Kart 8 Deluxe (USA).xci"))
    }

    private fun game(
        id: Long,
        platform: String,
        file: String,
        title: String = file.substringBefore(" (").substringBeforeLast('.'),
        serial: String? = null,
        discSerial: String? = null,
        md5: String? = null,
        links: Map<LinkKind, String> = emptyMap(),
        scanned: String? = null,
        missing: Boolean = false,
    ) = GameEvidence(
        id = id,
        platform = platform,
        fileName = file,
        title = title,
        serials = listOfNotNull(discSerial?.let { SerialId(Ids.serial(it), fromContent = true) }, serial?.let { SerialId(Ids.serial(it)) }),
        hashes = setOfNotNull(ContentHash.of(HashKind.MD5, md5)),
        links = links,
        scannedPlatform = scanned,
        missing = missing,
    )

    @Test
    fun `ids are written one way`() {
        assertEquals("slus00067", Ids.serial("SLUS-00067"))
        assertEquals("slus00067", Ids.serial("slus_000.67"))
        assertEquals("CUSA07408", Ids.psId("Bloodborne [cusa_07408]"))
        assertEquals("pokemon-ruby", Ids.workTitle("Pokemon Ruby (USA)"))
        assertEquals(Ids.workTitle("Pokemon Ruby (USA)"), Ids.workTitle("Pokemon Ruby (Europe) (Rev 1)"))
        assertTrue(Ids.releaseTitle("Metroid Fusion (USA)") != Ids.releaseTitle("Metroid Fusion (Europe)"))
    }

    @Test
    fun `1 the same disc with and without a serial in its name has one household key`() {
        val named = game(1, "ps2", "God of War (USA) (SCUS-97399).iso", title = "God of War (USA) (SCUS-97399)", serial = "SCUS-97399", discSerial = "SCUS-97399")
        val plain = game(2, "ps2", "God of War.iso", title = "God of War", discSerial = "SCUS-97399")
        assertEquals(HouseholdKeys.primary(named), HouseholdKeys.primary(plain))
        assertEquals("ps2:s.scus97399", HouseholdKeys.primary(plain))
    }

    @Test
    fun `2 regions are different copies but the same game`() {
        val usa = game(1, "gba", "Golden Sun (USA).gba", md5 = "aa")
        val eur = game(10, "gba", "Golden Sun (Europe).gba", md5 = "bb")
        assertTrue(GameMatcher.match(listOf(eur), listOf(usa), MatchLevel.RELEASE).isEmpty())
        assertEquals(Evidence.WORK_TITLE, GameMatcher.match(listOf(eur.copy(hashes = emptySet())), listOf(usa), MatchLevel.WORK).single().evidence)
        // Saves and play time keep following the title, as they always have.
        assertEquals(HouseholdKeys.primary(usa), HouseholdKeys.primary(eur))
    }

    @Test
    fun `3 a rename never changes who a game is`() {
        // Identity is built from the game's own title; a person's rename is never passed in.
        val g = game(1, "snes", "Chrono Trigger (USA).sfc")
        assertEquals("snes:t.chrono-trigger", HouseholdKeys.primary(g))
    }

    @Test
    fun `4 two games sharing a title are never joined by it`() {
        val original = game(1, "psx", "Tomb Raider (USA).bin", serial = "SLUS-00152")
        val other = game(2, "psx", "Tomb Raider (USA).chd", serial = "SLUS-01234")
        assertTrue(GameMatcher.match(listOf(other.copy(id = 9)), listOf(original), MatchLevel.RELEASE).isEmpty())
        // Two local games with the title: a remote one with only the title matches neither.
        val remote = game(9, "psx", "Tomb Raider (USA).iso")
        val locals = listOf(original, game(3, "psx", "Tomb Raider (USA).iso2", title = "Tomb Raider"))
        assertTrue(GameMatcher.match(listOf(remote), locals, MatchLevel.WORK).none { it.evidence == Evidence.WORK_TITLE })
        assertFalse(GameMatcher.sameHousehold(listOf(HouseholdKeys.primary(original)), listOf(HouseholdKeys.primary(other))))
    }

    @Test
    fun `5 a multi-disc game is one game by its first disc`() {
        val set = game(1, "psx", "Final Fantasy VII (USA).m3u", discSerial = "SCUS-94163")
        val remote = game(9, "psx", "Final Fantasy VII (USA) (Disc 1).chd", serial = "SCUS-94163")
        assertEquals(Evidence.SERIAL, GameMatcher.find(remote, listOf(set))?.evidence)
    }

    @Test
    fun `6 hacks and translations are other games`() {
        val original = game(1, "gba", "Mother 3 (Japan).gba")
        val translated = game(9, "gba", "Mother 3 (Japan) [T+Eng].gba", title = "Mother 3")
        val hack = game(8, "gba", "Mother 3 (Japan) (Hack).gba", title = "Mother 3")
        assertTrue(GameMatcher.match(listOf(translated, hack), listOf(original), MatchLevel.RELEASE).isEmpty())
    }

    @Test
    fun `7 a game on a drive that is out keeps its identity but is never a copy to match`() {
        val offline = game(1, "gba", "Golden Sun (USA).gba", missing = true)
        assertNull(GameMatcher.find(game(9, "gba", "Golden Sun (USA).gba"), listOf(offline)))
        assertEquals("gba:t.golden-sun", HouseholdKeys.primary(offline))
    }

    @Test
    fun `8 a game moved to another system keeps the old system's key as an alias`() {
        val moved = game(1, "gbc", "Pokemon Crystal (USA).gb", scanned = "gb")
        val keys = HouseholdKeys.all(moved)
        assertEquals("gbc:t.pokemon-crystal", keys.first())
        assertTrue("gb:t.pokemon-crystal" in keys)
    }

    @Test
    fun `9 arcade games are their set names`() {
        val local = game(1, "arcade", "sf2.zip", title = "sf2")
        assertEquals(Evidence.FILE_NAME, GameMatcher.find(game(9, "arcade", "sf2.zip", title = "Street Fighter II"), listOf(local))?.evidence)
    }

    @Test
    fun `10 console ids identify PS4 folders, Steam apps and Android packages`() {
        val folder = game(1, "ps4", "Bloodborne", title = "Bloodborne")
        val withId = game(2, "ps4", "CUSA-00900", title = "CUSA-00900")
        assertEquals(Evidence.SERIAL, GameMatcher.find(game(9, "ps4", "Bloodborne [CUSA00900].zip"), listOf(withId))?.evidence)
        assertEquals(Evidence.FILE_NAME, GameMatcher.find(game(8, "ps4", "Bloodborne.zip"), listOf(folder))?.evidence)
        val steam = game(3, "pc", "Hades", links = mapOf(LinkKind.STEAM_APP to "1145360"))
        assertEquals(Evidence.LINKED, GameMatcher.find(game(7, "pc", "hades-something", links = mapOf(LinkKind.STEAM_APP to "1145360")), listOf(steam))?.evidence)
    }

    @Test
    fun `11 two copies of a game share one household key`() {
        val a = game(1, "gba", "Golden Sun (USA).gba")
        val b = game(2, "gba", "Golden Sun (USA) (Rev 1).gba")
        assertEquals(HouseholdKeys.primary(a), HouseholdKeys.primary(b))
    }

    @Test
    fun `12 an explicit link beats every guess, and a no is never asked again`() {
        val linked = game(1, "gba", "whatever.gba", links = mapOf(LinkKind.ROMM_ROM to "99"))
        val byName = game(2, "gba", "Golden Sun (USA).gba")
        val remote = game(99, "gba", "Golden Sun (USA).gba", links = mapOf(LinkKind.ROMM_ROM to "99"))
        assertEquals(IdentityMatch(99, 1, Evidence.LINKED), GameMatcher.find(remote, listOf(linked, byName)))
        val unlinked = remote.copy(links = emptyMap())
        assertNull(GameMatcher.find(unlinked, listOf(byName), vetoed = setOf(2L)))
    }

    @Test
    fun `13 the game's own serial wins over its name's`() {
        val g = game(1, "psx", "Game (SLUS-00001).bin", serial = "SLUS-00001", discSerial = "SLUS-00002")
        assertEquals("psx:s.slus00002", HouseholdKeys.primary(g))
        assertTrue("psx:s.slus00001" in HouseholdKeys.all(g))
    }

    @Test
    fun `14 every key an earlier Fuse used is still this game's`() {
        // 0.4.2.10 keyed a disc by its file name's serial, else its title.
        val g = game(1, "ps2", "Okami.iso", title = "Okami", discSerial = "SLUS-21115")
        val before = "ps2:t.okami"
        assertTrue(before in HouseholdKeys.all(g))
        assertTrue(GameMatcher.sameHousehold(listOf(before), HouseholdKeys.all(g)) || HouseholdKeys.all(g).contains(before))
    }

    @Test
    fun `15 different links never join games`() {
        val a = game(1, "gba", "Golden Sun (USA).gba", links = mapOf(LinkKind.ROMM_ROM to "5"))
        val b = game(9, "gba", "Golden Sun (USA).gba", links = mapOf(LinkKind.ROMM_ROM to "9"))
        assertNull(GameMatcher.find(b, listOf(a)))
        assertNull(GameMatcher.find(b, listOf(a), MatchLevel.WORK))
    }

    @Test
    fun `nothing is taken twice and the strongest evidence wins across games`() {
        val locals = listOf(game(1, "gba", "Golden Sun (USA).gba", md5 = "aa"), game(2, "gba", "Golden Sun (USA).zip"))
        val remotes = listOf(game(9, "gba", "Golden Sun (USA).gba", md5 = "aa"), game(8, "gba", "Golden Sun (USA).gba"))
        val m = GameMatcher.match(remotes, locals)
        assertEquals(IdentityMatch(9, 1, Evidence.HASH), m.first { it.remote == 9L })
        assertTrue(m.none { it.remote == 8L && it.local == 1L })
    }
}
