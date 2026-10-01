package io.github.matiyaaa.fuse.integrations.match

import io.github.matiyaaa.fuse.model.MatchStrictness
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.ScrapeQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun sim(a: String, b: String) = TitleMatcher.titleSimilarity(a, b)

    @Test
    fun exactNameClearlyWinsOverWordsThatOnlyLookAlike() {
        // The owner's case: "Dredge" offered DREDGE at 90% and Dredgers at 80%.
        val inputs = listOf(input("1", "Dredgers"), input("2", "DREDGE"), input("3", "Dredge Wars"))
        val q = query("Dredge", platform = "win", platformName = "Windows")
        val ranked = matcher.rank(q, inputs)
        assertEquals(listOf("DREDGE", "Dredge Wars", "Dredgers"), ranked.map { it.candidate.title })
        assertTrue(ranked[0].candidate.confidence >= 0.95f, "DREDGE ${ranked[0].candidate.confidence}")
        assertTrue(ranked[2].candidate.confidence <= 0.6f, "Dredgers ${ranked[2].candidate.confidence}")
        val decision = matcher.decide(ranked, MatchStrictness.NORMAL)
        assertIs<MatchDecision.AutoAccept>(decision)
        assertEquals("2", decision.match.input.providerGameId)
        // Confirmed by the platform it shows 0.99.
        val confirmed = matcher.rank(q, listOf(input("2", "DREDGE", listOf("PC (Microsoft Windows)")))).single()
        assertEquals(0.99f, confirmed.candidate.confidence, 0.001f)
    }

    @Test
    fun wordsWithLettersAddedAreOtherWords() {
        for ((a, b) in listOf("Dredge" to "Dredgers", "Mario" to "Marios", "Zelda" to "Zeldas", "Zelda" to "Zeld")) {
            val s = sim(a, b)
            assertTrue(s.score <= 0.6, "$a / $b ${s.score}")
            assertFalse(s.exact)
        }
        // Typos are forgiven in longer words only: one letter changed or two neighbours swapped.
        assertTrue(sim("Metriod Fusion", "Metroid Fusion").score >= 0.9)
        assertTrue(sim("Castlevanai", "Castlevania").score >= 0.9)
        assertTrue(sim("Super Metrxid", "Super Metroid").score >= 0.9)
        assertTrue(sim("Mrio Kart", "Mario Kart").score < 0.6)
        assertTrue(sim("Metroid Fusoin Remix", "Metroid Fusion").score < sim("Metroid Fusion Remix", "Metroid Fusion").score)
    }

    @Test
    fun aTypoStillMatchesWell() {
        val inputs = listOf(input("1", "Metroid Fusion", listOf("Game Boy Advance")), input("2", "Metroid Prime", listOf("GameCube")))
        val q = query("Metriod Fusion", platform = "gba", platformName = "Game Boy Advance")
        val decision = matcher.match(q, inputs, MatchStrictness.NORMAL)
        assertIs<MatchDecision.AutoAccept>(decision)
        assertEquals("1", decision.match.input.providerGameId)
        assertTrue(decision.match.candidate.confidence >= 0.9f)
        assertFalse(decision.match.titleExact)
    }

    @Test
    fun aDifferentSequelNumberNeverPassesAsTheGame() {
        val s = sim("Hades", "Hades II")
        assertTrue(s.numbersDiffer)
        assertTrue(s.score < 0.6, "Hades II ${s.score}")
        val ranked = matcher.rank(query("Hades", platform = "win", platformName = "Windows"), listOf(input("2", "Hades II"), input("1", "Hades")))
        assertEquals("Hades", ranked.first().candidate.title)
        // Alone, on the right platform and year, it is still only offered.
        val alone = listOf(input("2", "Hades II", listOf("PC (Microsoft Windows)"), 2024))
        val q = query("Hades", platform = "win", platformName = "Windows", year = 2024)
        for (strictness in MatchStrictness.entries) {
            assertIs<MatchDecision.NeedsReview>(matcher.match(q, alone, strictness), "strictness $strictness")
        }
        // A lone letter is a name, not a number: Mega Man X is not Mega Man 10.
        val x = sim("Mega Man X", "Mega Man 10")
        assertTrue(x.numbersDiffer)
        assertTrue(x.score < 0.5, "Mega Man 10 ${x.score}")
        assertTrue(sim("Mega Man X4", "Mega Man X3").numbersDiffer)
        assertTrue(sim("Doom", "Doom 64").score < 0.6)
        // A 1 that one side leaves out is not a sequel.
        assertFalse(sim("Mega Man", "Mega Man 1").numbersDiffer)
    }

    @Test
    fun namesWrittenWithOrWithoutSpacesAreEqual() {
        for ((a, b) in listOf("Fire Red" to "FireRed", "Mega Man" to "Megaman", "Mega Man X" to "MegaMan X", "Super Mario World" to "SuperMarioWorld", "Doom 3" to "Doom3")) {
            val s = sim(a, b)
            assertEquals(1.0, s.score, "$a / $b")
            assertTrue(s.exact)
        }
        // Inside longer names too, with an edition word left over.
        assertEquals(0.95, sim("Pokemon Fire Red", "Pokémon FireRed Version").score, 1e-9)
        val decision = matcher.match(query("Pokemon Fire Red", platform = "gba", platformName = "Game Boy Advance"), listOf(input("1", "Pokemon FireRed")), MatchStrictness.EXACT)
        assertIs<MatchDecision.NeedsReview>(decision)
        assertIs<MatchDecision.AutoAccept>(matcher.match(query("Pokemon Fire Red", platform = "gba", platformName = "Game Boy Advance"), listOf(input("1", "Pokemon FireRed", listOf("GBA"))), MatchStrictness.EXACT))
    }

    @Test
    fun editionAndSmallWordsCountLess() {
        assertEquals(0.95, sim("Dredge", "DREDGE: Complete Edition").score, 1e-9)
        assertEquals(0.95, sim("Mario Kart 8", "Mario Kart 8 Deluxe").score, 1e-9)
        assertEquals(0.95, sim("The Witcher 3 Wild Hunt", "The Witcher 3: Wild Hunt - Game of the Year Edition").score, 1e-9)
        assertEquals(0.95, sim("Blade Runner Directors Cut", "Blade Runner").score, 1e-9)
        assertEquals(0.92, sim("Dredge Deluxe Edition", "Dredge Complete Edition").score, 1e-9)
        // A swapped word is not an edition: Gold and Silver are different games.
        assertTrue(sim("Pokemon Gold", "Pokemon Silver").score < 0.6)
        assertTrue(sim("Pokemon Gold Version", "Pokemon Silver Version").score < 0.6)
        // The exact name still wins when an edition of it is right behind.
        val inputs = listOf(input("1", "DREDGE: Complete Edition"), input("2", "DREDGE"))
        val decision = matcher.match(query("Dredge", platform = "win", platformName = "Windows"), inputs, MatchStrictness.NORMAL)
        assertIs<MatchDecision.AutoAccept>(decision)
        assertEquals("2", decision.match.input.providerGameId)
    }

    @Test
    fun aNameInsideALongerOneScoresHighButBelowExact() {
        val botw = sim("Breath of the Wild", "The Legend of Zelda: Breath of the Wild")
        assertTrue(botw.score in 0.85..0.9, "botw ${botw.score}")
        // The same the other way round: the query has words the candidate lacks.
        assertEquals(sim("Castlevania: Symphony of the Night", "Symphony of the Night").score, sim("Symphony of the Night", "Castlevania: Symphony of the Night").score)
        assertTrue(sim("Super Metroid Junk", "Super Metroid").score in 0.85..0.9)
        // The more the candidate adds, the lower it scores.
        assertTrue(sim("Wild", "The Legend of Zelda: Breath of the Wild").score < botw.score)
        // Never ahead of the exact name.
        val inputs = listOf(input("1", "The Legend of Zelda: Breath of the Wild", listOf("Switch")), input("2", "Breath of the Wild", listOf("Switch")))
        val q = query("Breath of the Wild", platform = "switch", platformName = "Nintendo Switch")
        assertEquals("2", matcher.rank(q, inputs).first().input.providerGameId)
        // Alone and on the right platform it is offered under NORMAL, taken by AGGRESSIVE with a warning.
        val alone = listOf(inputs[0])
        assertIs<MatchDecision.NeedsReview>(matcher.match(q, alone, MatchStrictness.NORMAL))
        val aggressive = matcher.match(q, alone, MatchStrictness.AGGRESSIVE)
        assertIs<MatchDecision.AutoAccept>(aggressive)
        assertNotNull(aggressive.warning)
    }

    @Test
    fun wordOrderCostsALittle() {
        val s = sim("Zelda Breath of the Wild", "Breath of the Wild Zelda")
        assertTrue(s.score in 0.9..0.97, "order ${s.score}")
        assertFalse(s.exact)
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
