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
    /** Normalised titles are equal (or the checksum matched). */
    val titleExact: Boolean,
    val platform: PlatformEvidence,
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
 * Title similarity (1.0 only for equal normalised titles or a checksum match) blends Jaro-Winkler on
 * the normalised titles (50%), a token-sort Levenshtein ratio (30%) and a typo-tolerant token-set
 * overlap (20%); a different sequel number multiplies it by 0.75. Confidence is 0.90 x title, then
 * same platform +0.06, different platform -0.30, same year +0.03, one year off +0.01, three or more
 * years off -0.10, preferred region +0.01, clamped to 0..1. So an equal title alone scores 0.90, with
 * the platform confirmed 0.96, and with the year too 0.99.
 *
 * Auto-accept rules: EXACT needs equal titles and a confirmed platform with no equally exact rival;
 * NORMAL needs >= 0.9 and a lead of >= 0.1 over the next candidate; AGGRESSIVE needs >= 0.75 and a
 * strict lead (and returns a warning). A different platform is never auto-accepted.
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
                confidence += 0.06
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
        )
    }

    /** Scores and sorts [inputs], best first, dropping duplicates of the same provider id. */
    fun rank(query: ScrapeQuery, inputs: List<MatchInput>): List<ScoredMatch> = inputs
        .distinctBy { it.provider to it.providerGameId }
        .map { score(query, it) }
        .sortedWith(compareByDescending<ScoredMatch> { it.candidate.confidence }.thenByDescending { it.titleExact })

    /** Applies the [strictness] rules to a list from [rank]. */
    fun decide(ranked: List<ScoredMatch>, strictness: MatchStrictness): MatchDecision {
        val top = ranked.firstOrNull() ?: return MatchDecision.NoCandidates
        val runnerUp = ranked.getOrNull(1)
        val best = top.candidate.confidence
        val next = runnerUp?.candidate?.confidence ?: 0f
        val accepted = top.platform != PlatformEvidence.MISMATCH && best >= aggressiveThreshold && when (strictness) {
            MatchStrictness.EXACT ->
                top.titleExact && top.platform == PlatformEvidence.MATCH &&
                    !(runnerUp != null && runnerUp.titleExact && runnerUp.platform == PlatformEvidence.MATCH)
            MatchStrictness.NORMAL -> best >= normalThreshold && best - next >= normalGap - EPSILON
            MatchStrictness.AGGRESSIVE -> best > next
        }
        if (!accepted) return MatchDecision.NeedsReview(ranked.take(maxReviewCandidates))
        val warning = if (strictness == MatchStrictness.AGGRESSIVE && (best < normalThreshold || best - next < normalGap)) {
            "Accepted by aggressive matching at ${(best * 100).roundToInt()}% confidence; check it"
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
        return listOfNotNull(query.title, base).filter { it.isNotBlank() }.distinct()
    }

    /** Similarity of two raw titles. */
    data class TitleScore(val score: Double, val exact: Boolean, val numbersDiffer: Boolean)

    companion object {
        private const val EPSILON = 1e-4f
        private const val TITLE_WEIGHT = 0.90

        /** Title-only similarity of two raw titles (0..1, 1 only when the normalised forms are equal). */
        fun titleSimilarity(a: String, b: String): TitleScore {
            val ta = TitleNormalizer.tokens(a)
            val tb = TitleNormalizer.tokens(b)
            if (ta.isEmpty() || tb.isEmpty()) return TitleScore(0.0, false, false)
            if (ta == tb) return TitleScore(1.0, true, false)
            val na = ta.joinToString(" ")
            val nb = tb.joinToString(" ")
            var s = 0.5 * Similarity.jaroWinkler(na, nb) +
                0.3 * Similarity.tokenSortRatio(ta, tb) +
                0.2 * Similarity.softTokenSet(ta, tb)
            val numbersA = ta.filter { t -> t.all { it.isDigit() } }.toSet()
            val numbersB = tb.filter { t -> t.all { it.isDigit() } }.toSet()
            val numbersDiffer = numbersA != numbersB
            if (numbersDiffer) s *= 0.75
            return TitleScore(s.coerceAtMost(0.97), false, numbersDiffer)
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
