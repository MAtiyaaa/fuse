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
 * 7. A list number in front is removed when it is three to five digits followed by ". "
 *    ("001. Title"), or four or five digits followed by " - " that do not read as a year
 *    1900..2099 ("0123 - Metroid Fusion"). Shorter numbers, years and numbers without a
 *    separator stay, because they are usually the title: "12 - Title", "1943 - The Battle of
 *    Midway", "007 - The World Is Not Enough", "1942", "2048".
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
    private val listNumberDot = Regex("^(\\d{3,5})\\s*\\.\\s+(?=\\S)")
    private val listNumberDash = Regex("^(\\d{4,5})\\s*-\\s+(?=\\S)")
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

    /** A list number in front of [title], per rule 7, or null. */
    private fun listNumber(title: String): MatchResult? {
        listNumberDot.find(title)?.let { return it }
        val dash = listNumberDash.find(title) ?: return null
        return dash.takeUnless { it.groupValues[1].length == 4 && it.groupValues[1].toInt() in 1900..2099 }
    }

    /** "A - - B" -> "A - B"; a leading or trailing " -" is dropped. */
    private fun collapseSeparators(title: String): String =
        collapse(title.replace(emptySeparators, "-")).removePrefix("- ").removeSuffix(" -").trim()

    private fun collapse(s: String): String = s.replace(whitespace, " ").trim()
}
