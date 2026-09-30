package io.github.matiyaaa.fuse.library.parse

/** How sure the cleaner is that a cleaned title only lost noise. */
enum class CleanupConfidence {
    /** Nothing to clean. */
    UNCHANGED,

    /** Only recognised tags were removed (region, language, revision, dump flags...). */
    SAFE,

    /** Something ambiguous was removed or left alone; the UI should ask the user to check. */
    REVIEW,
}

/**
 * The result of cleaning one title, for the Clean Display Names preview.
 *
 * @property removed Tags and text that were dropped, as written.
 * @property warnings Human-readable reasons for [CleanupConfidence.REVIEW].
 */
data class CleanupPreview(
    val original: String,
    val cleaned: String,
    val changed: Boolean,
    val confidence: CleanupConfidence,
    val removed: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/**
 * Turns file-style titles into display titles. Only the display title changes; files are never
 * touched and the original title is kept, so cleanup can always be undone.
 *
 * Rules, applied to a title without its extension:
 * 1. Recognised tags are removed: region, language, revision, version, disc, dump flags
 *    (`[!]`, `[b]`, `[h1]`, `[T+Eng]`...), serials, TOSEC year and publisher, video standards,
 *    RomM provider ids, update/DLC markers, licensing tags (Unl, PD, Hack, Pirate) and
 *    re-release or compatibility notes ("(Virtual Console)", "(SGB Enhanced)", "(Evercade)").
 * 2. Release variants stay visible because they tell two entries apart: Beta, Alpha, Proto,
 *    Demo, Sample, Preview, Kiosk, Promo, Debug.
 * 3. Unknown round-bracket tags are kept verbatim, since they are usually part of the name
 *    ("Tetris (Tengen)", "Game (Bonus Disc)").
 * 4. Unknown square-bracket tags are removed (they are scene or dump notes almost every time),
 *    and the result is marked [CleanupConfidence.REVIEW].
 * 5. Trailing articles move to the front of each " - " segment:
 *    "Legend of Zelda, The - A Link to the Past" -> "The Legend of Zelda - A Link to the Past".
 * 6. Underscores become spaces when the title has no spaces at all; whitespace is collapsed.
 * 7. A list number or product code in front is removed ([LeadingNumbers]): "12 - Title",
 *    "123. Title", "12) Title", "#12 Title", "0123 Title", "0123-Title", "A123 - Title",
 *    "NUS-012 Title". Numbers that are usually the title stay: years 1900..2099 ("1943 - The
 *    Battle of Midway"), 007, numbers with no separator and no leading zero ("1942",
 *    "1080 Snowboarding") and short numbers tied to a word ("10-Yard Fight", "3-D WorldRunner").
 * 8. A bare version at the very end is removed when it has a dot ("Title v1.1", "Title V1.0.3");
 *    "Title v2" stays.
 * 9. Separators left empty by removals are collapsed: "A - - B" becomes "A - B", and a
 *    leading or trailing " -" is dropped.
 * 10. If nothing would be left, the original is returned unchanged and marked for review.
 */
object DisplayNameCleaner {
    private val articles = listOf("The", "A", "An", "Die", "Der", "Das", "Le", "La", "Les", "El", "Los", "Las", "Il")
    private val trailingArticle = Regex(
        "^(.+?),\\s*(${articles.joinToString("|")})$",
        RegexOption.IGNORE_CASE,
    )
    private val trailingElision = Regex("^(.+?),\\s*(L')$", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("\\s+")
    private val trailingVersion = Regex("\\s+[vV]\\d+(?:\\.\\d+)+[a-z]?$")
    private val emptySeparators = Regex("(?<=\\s)-(?:\\s+-)+(?=\\s|$)")

    /** The cleaned display title for [original] (a title without extension). */
    fun clean(original: String): String = preview(original).cleaned

    /** Cleans every title, for the "Clean Display Names" preview list. */
    fun previewAll(titles: List<String>): List<CleanupPreview> = titles.map(::preview)

    /** Cleans one title and explains what was removed. */
    fun preview(original: String): CleanupPreview {
        val parsed = FilenameParser.parse(original, hasExtension = false)
        val removed = ArrayList<String>()
        val warnings = ArrayList<String>()
        val kept = ArrayList<String>()
        var title = parsed.baseTitle

        for (tag in parsed.nameTags) {
            when (tag.kind) {
                TagKind.UNKNOWN -> if (tag.bracket == Bracket.ROUND) {
                    kept.add(tag.raw)
                } else {
                    removed.add(tag.raw)
                    warnings.add("Removed unrecognised tag ${tag.raw}")
                }
                TagKind.STATUS -> {
                    val variant = NameFlags.releaseVariants.any { tag.text.startsWith(it, ignoreCase = true) }
                    if (variant) kept.add(tag.raw) else removed.add(tag.raw)
                }
                TagKind.TITLE -> title = tag.text
                else -> removed.add(tag.raw)
            }
        }

        // Text the parser took out of the title itself (" - Disc 2", a bare serial, a TOSEC version).
        val outside = FilenameParser.collapse(stripTags(parsed))
        val titleTag = parsed.nameTags.any { it.kind == TagKind.TITLE }
        // The parser falls back to the whole name when only tags are left; cleaning must not.
        if (outside.isEmpty() && !titleTag) title = ""
        if (outside.isNotEmpty() && outside != parsed.baseTitle && !titleTag) {
            val extra = when {
                outside.startsWith(parsed.baseTitle) -> outside.removePrefix(parsed.baseTitle)
                outside.endsWith(parsed.baseTitle) -> outside.removeSuffix(parsed.baseTitle)
                else -> outside
            }.trim(' ', '-', '_')
            if (extra.isNotEmpty()) removed.add(extra)
        }

        if (!title.contains(' ') && title.contains('_')) title = title.replace('_', ' ')
        title = collapse(title)
        listNumber(title)?.let { number ->
            removed.add(number.value.trim())
            title = title.substring(number.range.last + 1)
        }
        trailingVersion.find(title)?.takeIf { it.range.first > 0 }?.let { version ->
            removed.add(version.value.trim())
            title = title.substring(0, version.range.first)
        }
        title = invertArticles(collapseSeparators(title))
        if ('(' in title || ')' in title || '[' in title || ']' in title) {
            warnings.add("Unbalanced brackets left in the title")
        }

        var cleaned = collapse((listOf(title) + kept).joinToString(" "))
        if (cleaned.isBlank()) {
            cleaned = original.trim()
            warnings.add("Nothing would be left after cleaning; kept the original")
        }
        val changed = cleaned != original
        val confidence = when {
            warnings.isNotEmpty() -> CleanupConfidence.REVIEW
            !changed -> CleanupConfidence.UNCHANGED
            else -> CleanupConfidence.SAFE
        }
        return CleanupPreview(original, cleaned, changed, confidence, removed, warnings)
    }

    /**
     * Moves a trailing article to the front of each " - " separated segment:
     * "Legend of Zelda, The - A Link to the Past" -> "The Legend of Zelda - A Link to the Past",
     * "Amerzone, L'" -> "L'Amerzone".
     */
    fun invertArticles(title: String): String = title.split(" - ").joinToString(" - ") { segment ->
        val s = segment.trim()
        trailingElision.matchEntire(s)?.let { m -> return@joinToString m.groupValues[2] + m.groupValues[1] }
        trailingArticle.matchEntire(s)?.let { m -> return@joinToString m.groupValues[2] + " " + m.groupValues[1] }
        s
    }

    private fun stripTags(parsed: ParsedName): String {
        var s = parsed.stem
        parsed.nameTags.sortedByDescending { it.range.first }.forEach { s = s.replaceRange(it.range, " ") }
        return s
    }

    /** A list number or code in front of [title], per rule 7, or null. */
    private fun listNumber(title: String): MatchResult? = LeadingNumbers.find(title)

    /** "A - - B" -> "A - B"; a leading or trailing " -" is dropped. */
    private fun collapseSeparators(title: String): String =
        collapse(title.replace(emptySeparators, "-")).removePrefix("- ").removeSuffix(" -").trim()

    private fun collapse(s: String): String = s.replace(whitespace, " ").trim()
}

/**
 * List numbers and product codes that collections and dumps put in front of a game's name
 * ("0123 - Metroid Fusion", "12. Pepsiman", "A123-Tetris"), told apart from numbers that belong to
 * the title ("1942", "1080 Snowboarding", "007 GoldenEye", "10-Yard Fight", "1943 - The Battle of
 * Midway"). Used for display names and for the names Fuse searches art with.
 */
object LeadingNumbers {
    // Spaced separators: "12 - Title", "12 : Title", "12 _ Title".
    private val spaced = Regex("^#?(\\d{1,6})\\s+[-:_]\\s+(?=\\S)")
    // Dot or bracket, spaces optional: "12. Title", "12.Title", "12) Title".
    private val dotted = Regex("^#?(\\d{1,6})\\s*[.)]\\s*(?=\\p{L})")
    // Tight dash or underscore: only three digits or more, so "10-Yard Fight" keeps its number.
    private val tight = Regex("^#?(\\d{3,6})[-_]\\s*(?=\\p{L})")
    // Leading zero and a space: "0123 Title", "045 Title".
    private val zero = Regex("^#?(0\\d{1,5})\\s+(?=\\p{L})")
    // A hash number: "#12 Title".
    private val hashed = Regex("^#(\\d{1,6})\\s+(?=\\p{L})")
    // Product codes: "NUS-012 Title", "SLUS-00001. Title", "A123 - Title", "B123_Title".
    private val code = Regex("^([A-Z]{2,5}-\\d{2,6}|[A-Z]\\d{3,6})(?:\\s*[-_.:)]\\s*|\\s+)(?=\\p{L})")

    /** The number or code in front of [title] to remove, or null when there is none. */
    fun find(title: String): MatchResult? {
        val m = spaced.find(title) ?: dotted.find(title) ?: tight.find(title) ?: zero.find(title)
            ?: hashed.find(title) ?: code.find(title) ?: return null
        val number = m.groupValues[1]
        if (number == "007") return null
        if (number.length == 4 && number.all { it.isDigit() } && number.toInt() in 1900..2099) return null
        // Something must be left, and it must not be only a number.
        val rest = title.substring(m.range.last + 1).trim()
        if (rest.none { it.isLetter() }) return null
        return m
    }

    /** [title] without a leading list number or code. */
    fun strip(title: String): String = find(title)?.let { title.substring(it.range.last + 1).trim() } ?: title
}
