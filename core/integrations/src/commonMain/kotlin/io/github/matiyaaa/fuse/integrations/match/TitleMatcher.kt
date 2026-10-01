package io.github.matiyaaa.fuse.integrations.match

import io.github.matiyaaa.fuse.model.MatchStrictness
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScrapeCandidate
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.ScrapeQuery
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a candidate says about its platform relative to the query's. */
enum class PlatformEvidence { MATCH, MISMATCH, UNKNOWN }

/** One provider result as the matcher sees it. */
data class MatchInput(
    val provider: ScrapeProviderId,
    val providerGameId: String,
    val title: String,
    val alternativeTitles: List<String> = emptyList(),
    /** Platform names or abbreviations the provider lists for the game. */
    val platformNames: List<String> = emptyList(),
    /** Set when the search itself was limited to the platform; overrides [platformNames]. */
    val platformEvidence: PlatformEvidence? = null,
    val year: Int? = null,
    val regions: List<String> = emptyList(),
    val previewUrl: String? = null,
    /** True when the provider matched the ROM's checksum, not just the title. */
    val identifiedByChecksum: Boolean = false,
)

/** A scored [MatchInput]. [candidate] carries the confidence and the human reasons. */
data class ScoredMatch(
    val input: MatchInput,
    val candidate: ScrapeCandidate,
    /** Normalised titles are equal, also with spaces removed (or the checksum matched). */
    val titleExact: Boolean,
    val platform: PlatformEvidence,
    /** The confidence before it is clamped to 0..1, for ranking and the gap rules. */
    val score: Double = candidate.confidence.toDouble(),
)

/** What to do with a ranked candidate list. */
sealed interface MatchDecision {
    /** Safe to apply without asking. [warning] is set for aggressive accepts the UI should flag. */
    data class AutoAccept(val match: ScoredMatch, val warning: String? = null) : MatchDecision

    /** Show [candidates] (best first) to the user. */
    data class NeedsReview(val candidates: List<ScoredMatch>) : MatchDecision

    data object NoCandidates : MatchDecision
}

/**
 * Scores provider results against a [ScrapeQuery] and decides whether one can be accepted.
 *
 * Title similarity compares the normalised titles ([TitleNormalizer]) word by word ([TitleWords]),
 * taking the best pair of the query's names and the candidate's:
 * - 1.0 (exact) when the words are equal, also with the spaces removed ("FireRed" and "Fire Red"),
 *   or when the ROM checksum matched.
 * - 0.95 when the only difference is weak words on one side: articles and small words (the, a, an,
 *   of, and, in, to, for, on) and edition words (edition, version, remastered, remaster, deluxe,
 *   complete, definitive, HD, GOTY, game of the year, director's cut, enhanced, ultimate,
 *   collection, anniversary). 0.92 when both sides have some. "Gold" or "Special" alone is a name
 *   ("Pokemon Gold"), not an edition.
 * - Between 0.9 and 0.8 when one side has every word of the other but small words and adds
 *   significant ones (a series name, a subtitle, leftover junk): 0.9 minus 0.1 x the share of its
 *   significant words that are added, and 0.02 less when the other side has small words of its own.
 * - Otherwise (both sides have words the other lacks, an edition word counting too, as in "Pokemon
 *   Gold" and "Pokemon Silver") 0.45 x the share of significant words in common plus 0.25 x the
 *   Jaro-Winkler similarity of the titles, so titles that only look alike ("Dredge" and "Dredgers")
 *   stay under 0.3.
 * Each forgiven typo and a changed word order take 0.05 off. A different sequel number (both have
 * numbers that differ, or one has a number other than 1 that the other lacks) multiplies the result
 * by 0.6. Anything short of exact stays at or below 0.97.
 *
 * Confidence is 0.95 x title, then same platform +0.04, different platform -0.30, same year +0.03,
 * one year off +0.01, three or more years off -0.10, preferred region +0.01. So an equal title alone
 * shows 0.95 and, with the platform confirmed, 0.99. It is shown clamped to 0..1; ranking and the
 * gap rules use the unclamped sum, so a matching year still tells two equal titles apart.
 *
 * Auto-accept rules: EXACT needs equal titles and a confirmed platform with no equally exact rival;
 * NORMAL needs >= 0.9 and either a lead of >= 0.1 over the next candidate or an equal title that no
 * other candidate on a possible platform shares; AGGRESSIVE needs >= 0.75 and a strict lead (and
 * returns a warning when NORMAL would not have accepted). A different platform is never
 * auto-accepted.
 */
class TitleMatcher(
    val normalThreshold: Float = 0.9f,
    val normalGap: Float = 0.1f,
    val aggressiveThreshold: Float = 0.75f,
    val maxReviewCandidates: Int = 10,
) {

    /** Scores one input. */
    fun score(query: ScrapeQuery, input: MatchInput): ScoredMatch {
        val reasons = ArrayList<String>()
        val queryTitles = queryTitles(query)
        val candidateTitles = (listOf(input.title) + input.alternativeTitles).filter { it.isNotBlank() }

        var title = 0.0
        var exact = false
        var numbersDiffer = false
        if (input.identifiedByChecksum) {
            title = 1.0
            exact = true
            reasons += "ROM checksum matches"
        } else {
            for (q in queryTitles) for (c in candidateTitles) {
                val s = titleSimilarity(q, c)
                if (s.score > title) {
                    title = s.score
                    exact = s.exact
                    numbersDiffer = s.numbersDiffer
                }
            }
            reasons += when {
                exact && queryTitles.any { q -> candidateTitles.any { it.equals(q, ignoreCase = true) } } -> "Title matches exactly"
                exact -> "Title matches after normalising"
                else -> "Title similarity ${(title * 100).roundToInt()}%"
            }
            if (numbersDiffer) reasons += "Sequel or edition number differs"
        }

        val platform = input.platformEvidence
            ?: PlatformNames.evidence(query.platform, query.platformName, input.platformNames)
        var confidence = TITLE_WEIGHT * title
        when (platform) {
            PlatformEvidence.MATCH -> {
                confidence += PLATFORM_BONUS
                reasons += "Same platform"
            }
            PlatformEvidence.MISMATCH -> {
                confidence -= 0.30
                reasons += "Different platform (${input.platformNames.take(3).joinToString(", ")})"
            }
            PlatformEvidence.UNKNOWN -> reasons += "Provider does not say which platform"
        }

        val qYear = query.year
        val cYear = input.year
        if (qYear != null && cYear != null) {
            val d = abs(qYear - cYear)
            when {
                d == 0 -> { confidence += 0.03; reasons += "Same year ($cYear)" }
                d == 1 -> { confidence += 0.01; reasons += "Year within one ($cYear vs $qYear)" }
                d >= 3 -> { confidence -= 0.10; reasons += "Year differs ($cYear vs $qYear)" }
                else -> reasons += "Year close ($cYear vs $qYear)"
            }
        }

        val preferred = query.preferredRegion
        if (preferred != null && input.regions.any { it.equals(preferred, ignoreCase = true) }) {
            confidence += 0.01
            reasons += "Preferred region ($preferred)"
        }

        val finalScore = confidence.coerceIn(0.0, 1.0).toFloat()
        return ScoredMatch(
            input = input,
            candidate = ScrapeCandidate(
                provider = input.provider,
                providerGameId = input.providerGameId,
                title = input.title,
                platformName = input.platformNames.firstOrNull(),
                year = input.year,
                confidence = finalScore,
                reasons = reasons,
                previewUrl = input.previewUrl,
            ),
            titleExact = exact,
            platform = platform,
            score = confidence,
        )
    }

    /** Scores and sorts [inputs], best first, dropping duplicates of the same provider id. */
    fun rank(query: ScrapeQuery, inputs: List<MatchInput>): List<ScoredMatch> = inputs
        .distinctBy { it.provider to it.providerGameId }
        .map { score(query, it) }
        .sortedWith(compareByDescending<ScoredMatch> { it.score }.thenByDescending { it.titleExact })

    /** Applies the [strictness] rules to a list from [rank]. */
    fun decide(ranked: List<ScoredMatch>, strictness: MatchStrictness): MatchDecision {
        val top = ranked.firstOrNull() ?: return MatchDecision.NoCandidates
        val runnerUp = ranked.getOrNull(1)
        val best = top.score
        val next = runnerUp?.score ?: 0.0
        // An equal title wins unless another candidate on a possible platform has it too.
        val onlyExact = top.titleExact && ranked.drop(1).none { it.titleExact && it.platform != PlatformEvidence.MISMATCH }
        val normal = best >= normalThreshold && (best - next >= normalGap - EPSILON || onlyExact)
        val accepted = top.platform != PlatformEvidence.MISMATCH && best >= aggressiveThreshold && when (strictness) {
            MatchStrictness.EXACT ->
                top.titleExact && top.platform == PlatformEvidence.MATCH &&
                    !(runnerUp != null && runnerUp.titleExact && runnerUp.platform == PlatformEvidence.MATCH)
            MatchStrictness.NORMAL -> normal
            MatchStrictness.AGGRESSIVE -> best > next
        }
        if (!accepted) return MatchDecision.NeedsReview(ranked.take(maxReviewCandidates))
        val warning = if (strictness == MatchStrictness.AGGRESSIVE && !normal) {
            "Accepted by aggressive matching at ${(top.candidate.confidence * 100).roundToInt()}% confidence; check it"
        } else {
            null
        }
        return MatchDecision.AutoAccept(top, warning)
    }

    /** [rank] then [decide]. */
    fun match(query: ScrapeQuery, inputs: List<MatchInput>, strictness: MatchStrictness): MatchDecision =
        decide(rank(query, inputs), strictness)

    private fun queryTitles(query: ScrapeQuery): List<String> {
        val base = query.fileName?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.let { if ('.' in it) it.substringBeforeLast('.') else it }
        return (listOfNotNull(query.title, base) + query.alsoKnownAs).filter { it.isNotBlank() }.distinct()
    }

    /** Similarity of two raw titles. */
    data class TitleScore(val score: Double, val exact: Boolean, val numbersDiffer: Boolean)

    companion object {
        private const val EPSILON = 1e-4
        private const val TITLE_WEIGHT = 0.95
        private const val PLATFORM_BONUS = 0.04
        private const val MAX_INEXACT = 0.97
        private const val SEQUEL_FACTOR = 0.6

        /** Title-only similarity of two raw titles (0..1, 1 only when they are equal once normalised). */
        fun titleSimilarity(a: String, b: String): TitleScore {
            val ta = TitleNormalizer.tokens(a)
            val tb = TitleNormalizer.tokens(b)
            if (ta.isEmpty() || tb.isEmpty()) return TitleScore(0.0, false, false)
            if (ta == tb || ta.joinToString("") == tb.joinToString("")) return TitleScore(1.0, true, false)

            val words = TitleWords.align(ta, tb)
            // A title made only of weak words ("The Collection") counts them all.
            val weakA = TitleWords.weak(ta).takeUnless { w -> w.all { it } } ?: BooleanArray(ta.size)
            val weakB = TitleWords.weak(tb).takeUnless { w -> w.all { it } } ?: BooleanArray(tb.size)
            val extraA = ta.indices.filter { !words.matchedA[it] }
            val extraB = tb.indices.filter { !words.matchedB[it] }
            val strongA = extraA.count { !weakA[it] }
            val strongB = extraB.count { !weakB[it] }
            val editionA = extraA.count { weakA[it] && ta[it] !in TitleWords.filler }
            val editionB = extraB.count { weakB[it] && tb[it] !in TitleWords.filler }
            val weakOnlyA = extraA.size - strongA
            val weakOnlyB = extraB.size - strongB

            var s = when {
                strongA == 0 && strongB == 0 -> when {
                    weakOnlyA == 0 && weakOnlyB == 0 -> 1.0
                    weakOnlyA == 0 || weakOnlyB == 0 -> 0.95
                    else -> 0.92
                }
                // One side has every significant word of the other and adds some. An edition word left
                // over on the other side means a word was swapped instead ("Pokemon Gold" and "Pokemon Silver").
                (strongA == 0 && editionA == 0) || (strongB == 0 && editionB == 0) -> {
                    val aInB = strongA == 0 && editionA == 0
                    val total = if (aInB) weakB.count { !it } else weakA.count { !it }
                    val small = if (aInB) weakOnlyA else weakOnlyB
                    0.9 - 0.1 * (strongA + strongB) / total - if (small > 0) 0.02 else 0.0
                }
                else -> {
                    val shared = ta.indices.count { words.matchedA[it] && !weakA[it] }
                    val overlap = shared.toDouble() / (shared + strongA + strongB)
                    0.45 * overlap + 0.25 * Similarity.jaroWinkler(ta.joinToString(" "), tb.joinToString(" "))
                }
            }
            s -= 0.05 * words.typos + if (words.reordered) 0.05 else 0.0

            val numbersA = TitleWords.numbers(ta)
            val numbersB = TitleWords.numbers(tb)
            val onlyA = numbersA - numbersB
            val onlyB = numbersB - numbersA
            val numbersDiffer = (onlyA.isNotEmpty() && onlyB.isNotEmpty()) || (onlyA + onlyB).any { it != "1" }
            if (numbersDiffer) s *= SEQUEL_FACTOR
            return TitleScore(s.coerceIn(0.0, MAX_INEXACT), false, numbersDiffer)
        }
    }
}

/** Decides whether provider platform names refer to a Fuse platform. */
object PlatformNames {
    private val aliases: Map<String, Set<String>> = run {
        val snes = setOf("snes", "super nintendo", "super nintendo entertainment system", "super famicom", "sfc", "super nes")
        val nes = setOf("nes", "nintendo entertainment system", "famicom", "family computer", "nintendo famicom")
        mapOf(
            "snes" to snes,
            "sfam" to snes,
            "nes" to nes,
            "famicom" to nes,
            "fds" to setOf("fds", "famicom disk system", "family computer disk system"),
            "n64" to setOf("n64", "nintendo 64"),
            "gb" to setOf("gb", "game boy", "nintendo game boy"),
            "gbc" to setOf("gbc", "game boy color", "nintendo game boy color"),
            "gba" to setOf("gba", "game boy advance", "nintendo game boy advance"),
            "nds" to setOf("nds", "ds", "nintendo ds"),
            "3ds" to setOf("3ds", "nintendo 3ds"),
            "ngc" to setOf("ngc", "gc", "gcn", "gamecube", "nintendo gamecube"),
            "wii" to setOf("wii", "nintendo wii"),
            "wiiu" to setOf("wiiu", "wii u", "nintendo wii u"),
            "switch" to setOf("switch", "nintendo switch"),
            "virtualboy" to setOf("virtualboy", "virtual boy", "nintendo virtual boy"),
            "psx" to setOf("psx", "ps1", "ps", "playstation", "sony playstation", "playstation 1"),
            "ps2" to setOf("ps2", "playstation 2", "sony playstation 2"),
            "ps3" to setOf("ps3", "playstation 3", "sony playstation 3"),
            "psp" to setOf("psp", "playstation portable", "sony psp", "sony playstation portable"),
            "psvita" to setOf("psvita", "vita", "ps vita", "playstation vita", "sony playstation vita"),
            "genesis" to setOf(
                "genesis", "megadrive", "mega drive", "md", "sega genesis", "sega mega drive",
                "sega mega drive genesis", "mega drive genesis", "genesis mega drive", "sega genesis mega drive",
            ),
            "sms" to setOf("sms", "master system", "sega master system", "mark iii", "master system mark iii"),
            "gamegear" to setOf("gamegear", "game gear", "gg", "sega game gear"),
            "segacd" to setOf("segacd", "sega cd", "mega cd", "sega mega cd", "mega cd sega cd"),
            "sega32" to setOf("32x", "sega 32x", "sega32x"),
            "saturn" to setOf("saturn", "sega saturn"),
            "dc" to setOf("dc", "dreamcast", "sega dreamcast"),
            "tg16" to setOf(
                "tg16", "turbografx 16", "turbografx16", "pc engine", "pce", "pc engine turbografx 16",
                "turbografx 16 pc engine", "nec pc engine", "nec turbografx 16",
            ),
            "atari2600" to setOf("2600", "atari 2600", "atari2600", "vcs"),
            "atari7800" to setOf("7800", "atari 7800", "atari7800"),
            "lynx" to setOf("lynx", "atari lynx"),
            "jaguar" to setOf("jaguar", "atari jaguar"),
            "arcade" to setOf("arcade", "mame", "fbneo", "fba"),
            "win" to setOf("win", "pc", "windows", "microsoft windows", "pc windows", "pc microsoft windows"),
            "dos" to setOf("dos", "ms dos", "pc dos"),
            "xbox" to setOf("xbox", "microsoft xbox"),
            "xbox360" to setOf("xbox360", "xbox 360", "x360", "microsoft xbox 360"),
        )
    }

    fun normalize(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    /** Every reading of [name]: the whole name, the part before "(", the part inside it, and "/" splits. */
    private fun readings(name: String): List<String> {
        val inner = Regex("\\(([^)]*)\\)").findAll(name).map { it.groupValues[1] }.toList()
        val outer = name.replace(Regex("\\([^)]*\\)"), " ")
        return (listOf(name, outer) + inner + name.split('/') + outer.split('/'))
            .map(::normalize).filter { it.isNotEmpty() }.distinct()
    }

    /** MATCH when any of [candidateNames] names [platform]; UNKNOWN when there are none. */
    fun evidence(platform: PlatformId, platformName: String, candidateNames: List<String>): PlatformEvidence {
        if (candidateNames.isEmpty()) return PlatformEvidence.UNKNOWN
        val wanted = aliases[platform.value].orEmpty() + normalize(platformName) + normalize(platform.value)
        val hit = candidateNames.any { name -> readings(name).any { it in wanted } }
        return if (hit) PlatformEvidence.MATCH else PlatformEvidence.MISMATCH
    }
}
