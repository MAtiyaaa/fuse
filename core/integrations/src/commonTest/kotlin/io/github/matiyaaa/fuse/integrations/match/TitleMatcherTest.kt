package io.github.matiyaaa.fuse.integrations.match

import io.github.matiyaaa.fuse.model.MatchStrictness
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.ScrapeQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleMatcherTest {

    private val matcher = TitleMatcher()

    private fun query(title: String, platform: String = "snes", platformName: String = "Super Nintendo", year: Int? = null, file: String? = null) =
        ScrapeQuery(title = title, platform = PlatformId(platform), platformName = platformName, fileName = file, year = year)

    private fun input(id: String, title: String, platforms: List<String> = emptyList(), year: Int? = null) =
        MatchInput(ScrapeProviderId.IGDB, id, title, platformNames = platforms, year = year)

    @Test
    fun normalisation() {
        val n = TitleNormalizer::normalize
        assertEquals("final fantasy 7", n("Final Fantasy VII"))
        assertEquals(n("Final Fantasy 7"), n("FINAL FANTASY VII (USA) (Disc 1)"))
        assertEquals("legend of zelda a link to the past", n("The Legend of Zelda: A Link to the Past"))
        assertEquals(n("The Legend of Zelda: A Link to the Past"), n("Legend of Zelda, The - A Link to the Past (USA)"))
        assertEquals(n("Castlevania: Symphony of the Night"), n("Castlevania - Symphony of the Night [!]"))
        assertEquals(n("Ratchet & Clank"), n("Ratchet and Clank"))
        assertEquals(n("Pokémon Snap"), n("Pokemon Snap"))
        assertEquals(n("Don't Starve"), n("Dont Starve"))
        assertEquals("grand theft auto 5", n("Grand Theft Auto V"))
        assertEquals("street fighter 2 turbo", n("Street Fighter II Turbo"))
        assertEquals("rocky 4", n("Rocky IV"))
        // A lone X or I is a letter, not a numeral.
        assertEquals("mega man x", n("Mega Man X"))
        assertNotEquals(n("Mega Man X"), n("Mega Man 10"))
        assertEquals("i am setsuna", n("I Am Setsuna"))
        // Not every string of i/v/x is a numeral.
        assertEquals("iiii", n("IIII"))
        assertEquals("a", n("A"))
    }

    @Test
    fun similarityHandlesTyposAndSequels() {
        val typo = TitleMatcher.titleSimilarity("Super Metriod", "Super Metroid")
        assertTrue(typo.score > 0.9, "typo score ${typo.score}")
        val sequel = TitleMatcher.titleSimilarity("Super Mario Bros. 2", "Super Mario Bros. 3")
        assertTrue(sequel.numbersDiffer)
        assertTrue(sequel.score < 0.75, "sequel score ${sequel.score}")
        val partial = TitleMatcher.titleSimilarity("Sonic the Hedgehog", "Sonic the Hedgehog 2")
        assertTrue(partial.score < 0.8, "partial ${partial.score}")
        assertTrue(TitleMatcher.titleSimilarity("Pokemon Red", "Pokemon Blue").score < 0.75)
        assertEquals(1.0, TitleMatcher.titleSimilarity("Final Fantasy VII", "Final Fantasy 7").score)
    }

    @Test
    fun exactTitleAndPlatformIsAcceptedInEveryMode() {
        val inputs = listOf(
            input("1103", "Super Metroid", listOf("Super Nintendo Entertainment System", "SNES"), 1994),
            input("1104", "Metroid Fusion", listOf("Game Boy Advance"), 2002),
        )
        val q = query("Super Metroid", year = 1994)
        for (strictness in MatchStrictness.entries) {
            val decision = matcher.match(q, inputs, strictness)
            assertIs<MatchDecision.AutoAccept>(decision, "strictness $strictness")
            assertEquals("1103", decision.match.input.providerGameId)
            assertNull(decision.warning)
        }
        val top = matcher.rank(q, inputs).first().candidate
        assertTrue(top.confidence >= 0.99f, "confidence ${top.confidence}")
        assertTrue(top.reasons.any { "Title matches exactly" in it })
        assertTrue(top.reasons.any { "Same platform" in it })
        assertTrue(top.reasons.any { "Same year" in it })
    }

    @Test
    fun exactModeNeedsPlatformEvidence() {
        val inputs = listOf(input("5247", "Super Metroid"))
        val q = query("Super Metroid")
        assertIs<MatchDecision.NeedsReview>(matcher.match(q, inputs, MatchStrictness.EXACT))
        assertIs<MatchDecision.AutoAccept>(matcher.match(q, inputs, MatchStrictness.NORMAL))
    }

    @Test
    fun normalNeedsAGapToTheRunnerUp() {
        // Two different games with the same name on the same platform: ambiguous.
        val inputs = listOf(
            input("1", "Tetris", listOf("NES"), 1989),
            input("2", "Tetris", listOf("NES"), 1988),
        )
        val q = query("Tetris", platform = "nes", platformName = "Nintendo Entertainment System")
        assertIs<MatchDecision.NeedsReview>(matcher.match(q, inputs, MatchStrictness.NORMAL))
        assertIs<MatchDecision.NeedsReview>(matcher.match(q, inputs, MatchStrictness.EXACT))
        // With a year, one of them pulls ahead but the gap is still under 0.1.
        assertIs<MatchDecision.NeedsReview>(matcher.match(q.copy(year = 1989), inputs, MatchStrictness.NORMAL))
        // Aggressive takes the strictly better one and warns.
        val aggressive = matcher.match(q.copy(year = 1989), inputs, MatchStrictness.AGGRESSIVE)
        assertIs<MatchDecision.AutoAccept>(aggressive)
        assertEquals("1", aggressive.match.input.providerGameId)
        assertNotNull(aggressive.warning)
    }

    @Test
    fun typoIsAcceptedByNormalOnlyWithAClearLead() {
        val inputs = listOf(
            input("1103", "Super Metroid", listOf("SNES")),
            input("1104", "Metroid Prime", listOf("GameCube")),
        )
        val decision = matcher.match(query("Super Metriod"), inputs, MatchStrictness.NORMAL)
        assertIs<MatchDecision.AutoAccept>(decision)
        assertEquals("1103", decision.match.input.providerGameId)
        assertIs<MatchDecision.NeedsReview>(matcher.match(query("Super Metriod"), inputs, MatchStrictness.EXACT))
    }

    @Test
    fun lowConfidenceIsNeverAccepted() {
        val inputs = listOf(input("7", "Sonic the Hedgehog 2", listOf("Sega Mega Drive/Genesis")))
        val q = query("Sonic the Hedgehog", platform = "genesis", platformName = "Mega Drive")
        for (strictness in MatchStrictness.entries) {
            assertIs<MatchDecision.NeedsReview>(matcher.match(q, inputs, strictness), "strictness $strictness")
        }
        val weird = listOf(input("8", "Completely Different Game"))
        assertIs<MatchDecision.NeedsReview>(matcher.match(query("Zelda"), weird, MatchStrictness.AGGRESSIVE))
    }

    @Test
    fun wrongPlatformIsNeverAutoAccepted() {
        val inputs = listOf(input("9", "Tetris", listOf("Game Boy")))
        val q = query("Tetris", platform = "nes", platformName = "NES")
        for (strictness in MatchStrictness.entries) {
            assertIs<MatchDecision.NeedsReview>(matcher.match(q, inputs, strictness))
        }
        val scored = matcher.rank(q, inputs).single()
        assertEquals(PlatformEvidence.MISMATCH, scored.platform)
        assertTrue(scored.candidate.confidence <= 0.7f)
        assertTrue(scored.candidate.reasons.any { "Different platform" in it })
    }

    @Test
    fun fileNameCountsAsAQueryTitle() {
        val inputs = listOf(input("1", "Legend of Zelda: A Link to the Past", listOf("SNES")))
        val q = query("Zelda3", file = "/roms/snes/Legend of Zelda, The - A Link to the Past (USA).sfc")
        val decision = matcher.match(q, inputs, MatchStrictness.EXACT)
        assertIs<MatchDecision.AutoAccept>(decision)
    }

    @Test
    fun checksumIdentityCountsAsExact() {
        val inputs = listOf(MatchInput(ScrapeProviderId.SCREENSCRAPER, "3", "Sonic The Hedgehog (JP)", identifiedByChecksum = true, platformEvidence = PlatformEvidence.MATCH))
        assertIs<MatchDecision.AutoAccept>(matcher.match(query("Sonic", platform = "genesis"), inputs, MatchStrictness.EXACT))
    }

    @Test
    fun platformNames() {
        val snes = PlatformId("snes")
        assertEquals(PlatformEvidence.MATCH, PlatformNames.evidence(snes, "Super Nintendo", listOf("Super Nintendo (SNES)")))
        assertEquals(PlatformEvidence.MATCH, PlatformNames.evidence(PlatformId("genesis"), "Genesis", listOf("Sega Mega Drive/Genesis")))
        assertEquals(PlatformEvidence.MATCH, PlatformNames.evidence(PlatformId("win"), "Windows", listOf("PC (Microsoft Windows)")))
        assertEquals(PlatformEvidence.MISMATCH, PlatformNames.evidence(PlatformId("psx"), "PlayStation", listOf("PlayStation 2")))
        assertEquals(PlatformEvidence.UNKNOWN, PlatformNames.evidence(snes, "Super Nintendo", emptyList()))
    }
}
