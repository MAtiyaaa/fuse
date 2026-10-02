package io.github.matiyaaa.fuse.integrations.obtainium

/** How an installed version stands against the newest one upstream. */
enum class VersionStanding {
    /** The installed version is the newest (or newer, as a nightly can be). */
    CURRENT,

    /** Upstream has a newer version. */
    BEHIND,

    /** The two can't be compared honestly (different schemes, no numbers), so nothing is claimed. */
    UNKNOWN,
}

/**
 * Version names as the Obtainium Emulation Pack produces them: extracted with the pack's own
 * patterns (Obtainium's `versionExtractionRegEx` with `matchGroupToUse` templates such as
 * `$1$3.$2$4$6$5`), sorted the way Obtainium sorts links and releases, and compared only as far as
 * they really can be.
 */
object VersionText {
    /**
     * The version in [text] by [pattern], built from [groups] (`$1`, `$1-vanilla`, or a bare group
     * number). Like Obtainium, the last match counts. Null when there is no pattern; a failure when
     * the pattern is set but finds nothing, or when the pattern itself can't be read.
     */
    fun extract(pattern: String?, groups: String?, text: String): Result<String?> {
        if (pattern.isNullOrEmpty()) return Result.success(null)
        val regex = compile(pattern) ?: return Result.failure(PackException("The catalogue's version pattern can't be read."))
        val match = regex.findAll(text).lastOrNull() ?: return Result.failure(PackException("No version name was found."))
        val template = groups?.trim()?.takeIf { it.isNotEmpty() } ?: "0"
        val version = fill(match, template)
        return if (version.isNullOrEmpty()) Result.failure(PackException("No version name was found.")) else Result.success(version)
    }

    private fun fill(match: MatchResult, template: String): String? {
        val t = if (DIGITS.matches(template)) "$$template" else template
        val tokens = GROUP.findAll(t).map { it.value }.distinct().toList()
        if (tokens.isEmpty()) return null
        // Longer tokens first, so "$10" is never read as "$1" followed by "0".
        var out = t
        for (token in tokens.sortedByDescending { it.length }) {
            val n = token.substring(1).toIntOrNull() ?: continue
            val value = if (n <= match.groupValues.lastIndex) match.groupValues[n] else ""
            out = if ("\\$token" in out) out.replace("\\$token", token) else out.replace(token, value)
        }
        return out
    }

    /** A pattern from the catalogue, or null when it isn't one this device can compile. */
    fun compile(pattern: String): Regex? = try {
        Regex(pattern)
    } catch (e: Exception) {
        null
    }

    /**
     * Obtainium's natural order: digit runs compare as numbers ("1.10" after "1.9"), other runs as
     * text, and a digit run sorts after a text run at the same place.
     */
    fun compareAlphaNumeric(a: String, b: String): Int {
        val pa = split(a)
        val pb = split(b)
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val xn = x[0].isAsciiDigit()
            val yn = y[0].isAsciiDigit()
            val c = when {
                xn && yn -> {
                    val xv = x.toLongOrNull()
                    val yv = y.toLongOrNull()
                    if (xv != null && yv != null) xv.compareTo(yv) else x.compareTo(y)
                }
                !xn && !yn -> x.compareTo(y)
                else -> return if (xn) 1 else -1
            }
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }

    private fun split(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val parts = ArrayList<String>()
        val sb = StringBuilder().append(s[0])
        var numeric = s[0].isAsciiDigit()
        for (i in 1 until s.length) {
            val d = s[i].isAsciiDigit()
            if (d == numeric) {
                sb.append(s[i])
            } else {
                parts += sb.toString()
                sb.clear().append(s[i])
                numeric = d
            }
        }
        parts += sb.toString()
        return parts
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /**
     * The shapes Obtainium recognises as version names ("1.2", "1.2.3-beta4", "2.0+12"), in its
     * order. Two names that share a shape can be put in order; two that don't, can't.
     */
    private val standardPatterns: List<String> = buildList {
        val basics = listOf("[0-9]+", "[0-9]+\\.[0-9]+", "[0-9]+\\.[0-9]+\\.[0-9]+", "[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
        val preSuffixes = listOf("-", "\\+")
        val suffixes = listOf("alpha", "beta", "rc", "pre", "dev", "snapshot", "nightly", "ose", "[0-9]+")
        val finals = listOf("\\+[0-9]+", "[0-9]+")
        val seen = LinkedHashSet<String>()
        for (basic in basics) {
            seen += basic
            for (pre in preSuffixes) {
                for (suffix in suffixes) {
                    seen += "$basic$suffix"
                    seen += "$basic$pre$suffix"
                    for (f in finals) {
                        seen += "$basic$suffix$f"
                        seen += "$basic$pre$suffix$f"
                    }
                }
            }
        }
        addAll(seen)
    }
    private val looseRegexes by lazy { standardPatterns.map { it to Regex(it) } }

    private fun formats(version: String): Set<String> =
        looseRegexes.filter { (_, r) -> r.containsMatchIn(version) }.mapTo(LinkedHashSet()) { it.first }

    /**
     * Obtainium's numeric comparison: the most specific shape both names share decides, and its
     * digit runs are compared in order. Null when the names share no shape.
     */
    fun compareNumerically(a: String, b: String): Int? {
        val common = formats(a).intersect(formats(b))
        if (common.isEmpty()) return null
        val runs = Regex("[0-9]+")
        var best = common.first()
        var bestRuns = runs.findAll(best).count()
        for (f in common) {
            val n = runs.findAll(f).count()
            if (n > bestRuns || (n == bestRuns && f.length > best.length)) {
                best = f
                bestRuns = n
            }
        }
        val shape = Regex(best)
        fun numbers(v: String) = runs.findAll(shape.find(v)!!.value).map { it.value.toLongOrNull() ?: Long.MAX_VALUE }.toList()
        val x = numbers(a)
        val y = numbers(b)
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return if (x[i] > y[i]) 1 else -1
        return 0
    }

    /**
     * How [installed] (the app's own version name, as Android reports it) stands against [latest]
     * (the newest upstream version, as the catalogue names it). Only a version made of the same
     * kind of numbers is compared: "1.19.3" against "v1.19.3" is current and against "1.20" is
     * behind, but a commit name against a release number is [VersionStanding.UNKNOWN], never a
     * made-up update.
     */
    fun standing(installed: String, latest: String): VersionStanding {
        val a = clean(installed)
        val b = clean(latest)
        if (a.isEmpty() || b.isEmpty()) return VersionStanding.UNKNOWN
        if (a.equals(b, ignoreCase = true)) return VersionStanding.CURRENT
        val x = numbers(a) ?: return VersionStanding.UNKNOWN
        val y = numbers(b) ?: return VersionStanding.UNKNOWN
        // A dotted version against a bare number (a build number, a date) is not the same scheme.
        if ((x.size > 1) != (y.size > 1)) return VersionStanding.UNKNOWN
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrNull(i) ?: 0L).compareTo(y.getOrNull(i) ?: 0L)
            if (c != 0) return if (c < 0) VersionStanding.BEHIND else VersionStanding.CURRENT
        }
        return VersionStanding.CURRENT
    }

    private fun clean(v: String) = v.trim().removePrefix("v").removePrefix("V").trim()

    /** The version's numbers: its first dotted run ("2.6" in "Cemu 2.6"), else its first number. */
    private fun numbers(v: String): List<Long>? {
        val run = DOTTED.find(v)?.value ?: NUMBER.find(v)?.value ?: return null
        val parts = run.split('.').map { it.toLongOrNull() ?: return null }
        return parts.takeIf { it.isNotEmpty() }
    }

    private val DIGITS = Regex("^\\d+$")
    private val GROUP = Regex("\\$\\d+")
    private val DOTTED = Regex("\\d+(?:\\.\\d+)+")
    private val NUMBER = Regex("\\d+")
}
