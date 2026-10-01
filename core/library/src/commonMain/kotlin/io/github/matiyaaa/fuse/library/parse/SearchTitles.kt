package io.github.matiyaaa.fuse.library.parse

/**
 * The name a game is searched by, made from its file or folder name without extension (as
 * [io.github.matiyaaa.fuse.model.GameTitles.original] keeps it). Stricter than [DisplayNameCleaner]:
 * providers search by words, so everything that is not the name goes.
 *
 * Rules, in order:
 * 1. Copy markers ("Copy of X", "X - Copy", "X - Copy (2)", "X copy 2"), a GOG installer's
 *    "setup_" and a credit after a tag ("(Hack) by Someone") are removed.
 * 2. Every bracket tag goes ([FilenameParser]): region, language, revision, version, disc, dump
 *    flags, serials and title ids, TOSEC date and publisher, sizes, release variants and unknown
 *    tags alike. A tag that holds the title (`BLUS30001-[Metal Gear Solid 4]`) is used instead, and
 *    a PlayStation serial, a disc marker or a TOSEC version in the title itself goes too.
 * 3. A list number or product code in front goes ([LeadingNumbers]). Underscores become spaces. A
 *    release group after a dash goes ("-SKIDROW", "-GOG", "-TENOKE", and in a name with dots or
 *    underscores instead of spaces any upper-case group, "-XYZ"). A name with dots instead of spaces
 *    ("Super.Mario.World") loses its version ("v1.4.2", "Build.1234", a trailing "1.6.8") and the
 *    dots become spaces, with single letters joined again ("S.T.A.L.K.E.R" -> "STALKER").
 * 4. Removed after the first word: versions ("v1.0.2", "Ver 1.1", "Version 2", a trailing "1.6.8"),
 *    builds ("Build 1234"), sizes ("1.2GB") and bitness ("64bit", "x64").
 * 5. Removed at the start: list numbers and codes again, a GameCube or Wii disc id ("GALE01 - "),
 *    a 3DS or Switch title id. At the end: upper-case region codes ("USA", "EUR", "JPN", "PAL",
 *    "NTSC-U"), upper-case platform codes ("SNES", "N64", "PSX"), disc and track markers ("Disk A",
 *    "CD 2", "Track 01"), a title id and "Repack". Rules 4 and 5 repeat until nothing changes.
 * 6. A trailing article moves to the front ("Legend of Zelda, The - ..."), separators left empty
 *    are collapsed, and a name with no spaces written in camel case is split ("SuperMarioWorld",
 *    "HollowKnight", "Mario64"; "DmC", "NieR" and "McDonald" stay whole).
 * 7. A name without capitals gets them ("hollow knight" -> "Hollow Knight").
 * 8. If nothing would be left, the display title ([DisplayNameCleaner]) is used.
 *
 * Numbers that belong to the name stay ("1942", "007 GoldenEye", "Cyberpunk 2077", "Kingdom Hearts
 * HD 2.5 ReMIX"), and so does a bare number in front ("12 Contra"), because many names start with
 * one ("1080 Snowboarding"); [alternatives] offers the name without it.
 */
object SearchTitles {
    private val whitespace = Regex("\\s+")
    private val copyOf = Regex("^copy\\s+(?:\\(\\d+\\)\\s+)?of\\s+", RegexOption.IGNORE_CASE)
    private val copySuffix = Regex("\\s+-\\s+copy(?:\\s*\\(\\d+\\))?$", RegexOption.IGNORE_CASE)
    private val finderCopy = Regex("\\s+copy(?:\\s+\\d+)?$")
    private val installer = Regex("^setup_", RegexOption.IGNORE_CASE)
    private val credit = Regex("(?<=[)\\]])\\s*by\\s+[^()\\[\\]]+$", RegexOption.IGNORE_CASE)
    private val braces = Regex("\\{[^}]*\\}")
    private val strayBrackets = Regex("[()\\[\\]{}]")

    private val group = Regex("-([A-Za-z0-9]{2,12})$")
    private val knownGroups = setOf(
        "skidrow", "reloaded", "codex", "plaza", "cpy", "hoodlum", "prophet", "razor1911", "fairlight", "flt", "tenoke",
        "rune", "empress", "doge", "darksiders", "tinyiso", "simplex", "goldberg", "ali213", "3dm", "p2p", "elamigos",
        "fitgirl", "dodi", "kaos", "xatab", "gog", "steamrip", "dinobytes", "postmortem", "hi2u", "repack",
    )
    private val romanNumeral = Regex("^(?:I{1,3}|IV|VI{0,3}|IX|XI{0,3}|XIV|XV)$")
    private val sceneVersion = Regex("^(?:v|ver|version|build)\\d*$", RegexOption.IGNORE_CASE)

    private val version = Regex("[\\s-]+v\\d+(?:\\.\\d+)+[a-z]?(?=[\\s-]|$)", RegexOption.IGNORE_CASE)
    private val versionWord = Regex("[\\s-]+ver(?:sion|\\.)?\\s*\\d+(?:\\.\\d+)*[a-z]?(?=[\\s-]|$)", RegexOption.IGNORE_CASE)
    private val build = Regex("[\\s-]+build\\s*\\d+(?:\\.\\d+)*(?=[\\s-]|$)", RegexOption.IGNORE_CASE)
    private val bareVersion = Regex("\\s+\\d+(?:\\.\\d+){2,}$")
    private val size = Regex("[\\s-]+\\d+(?:[.,]\\d+)?\\s?[KMGT]i?B(?=[\\s-]|$)", RegexOption.IGNORE_CASE)
    private val bitness = Regex("[\\s-]+(?:x64|x86|win64|win32|(?:32|64)[\\s-]?bits?)(?=[\\s-]|$)", RegexOption.IGNORE_CASE)

    private val trailing = listOf(
        Regex("(?:\\s+-)?\\s+(?:USA|EUR|JPN|JAP|PAL|NTSC(?:-UC|-[UJK])?|PAL-[EM])$"),
        Regex("(?:\\s+-)?\\s+(?:SNES|SFC|NES|N64|GBA|GBC|NDS|3DS|NSW|PSX|PS1|PS2|PS3|PS4|PS5|PSP|PSV|GCN|NGC|WIIU|SMS|PCE|TG16|NGPC)$"),
        Regex("[\\s-]+(?:disc|disk|cd|dvd)\\s*(?:\\d{1,2}|[a-d])(?:\\s*(?:of|/)\\s*\\d{1,2})?$", RegexOption.IGNORE_CASE),
        Regex("[\\s-]+track\\s*\\d{1,2}$", RegexOption.IGNORE_CASE),
        Regex("[\\s-]+repack$", RegexOption.IGNORE_CASE),
        Regex("[\\s-]+(?:0004[0-9A-Fa-f]{12}|01[0-9A-Fa-f]{14})$"),
    )
    private val titleIdInFront = Regex("^(?:0004[0-9A-Fa-f]{12}|01[0-9A-Fa-f]{14})(?:\\s*[-.:]\\s*|\\s+)")
    private val discIdInFront = Regex("^[GRSDW][A-Z0-9]{2}[EJPDFISKUXYZWA]\\d[0-9A-Z](?:\\s*[-.:]\\s*|\\s+)")
    private val edges = Regex("^[\\s.,;:-]+|[\\s,;:-]+$")
    private val emptySeparators = Regex("(?<=\\s)-(?:\\s+-)+(?=\\s|$)")
    private val bareNumber = Regex("^\\d{1,3}\\s+(?=\\p{L})")

    // Splits "SuperMarioWorld", "NBAJam", "3DDot", "Mario64" and "1080Snowboarding", never after "Mc"
    // and never before a capital that ends the word ("DmC", "NieR").
    private val camel = Regex("(?<=\\p{Ll})(?<!Mc)(?=\\p{Lu}\\p{Ll})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})|(?<=\\p{Ll})(?=\\d)|(?<=\\d)(?=\\p{Lu}\\p{Ll})")
    private val smallWords = setOf("a", "an", "the", "of", "and", "in", "on", "to", "for", "at", "by", "or", "vs")

    /** The search title for [original] (a file or folder name without extension). */
    fun clean(original: String): String = steps(original).title

    /**
     * Other spellings worth a search when [clean]'s title finds nothing: the name without a bare
     * number of up to three digits in front ("12 Contra" -> "Contra", "007 GoldenEye" ->
     * "GoldenEye") and the camel case name as written ("EarthBound"), when [clean] split it.
     * Providers' results are scored against these too, so longer numbers ("1080 Snowboarding") are
     * left alone.
     */
    fun alternatives(original: String): List<String> {
        val result = steps(original)
        return buildList {
            bareNumber.find(result.title)?.let { add(result.title.substring(it.range.last + 1).trim()) }
            result.unsplit?.let(::add)
        }.filter { it != result.title && it.any(Char::isLetter) }.distinct()
    }

    private class Result(val title: String, val unsplit: String? = null)

    private fun steps(original: String): Result {
        var s = collapse(original)
        s = s.without(copyOf).without(copySuffix).without(finderCopy).without(installer).without(credit).without(braces)

        val parsed = FilenameParser.parse(s, hasExtension = false)
        val outside = parsed.nameTags.sortedByDescending { it.range.first }
            .fold(parsed.stem) { acc, tag -> acc.replaceRange(tag.range, " ") }
        val titleTag = parsed.nameTags.firstOrNull { it.kind == TagKind.TITLE }
        var t = when {
            titleTag != null -> titleTag.text
            outside.isNotBlank() -> parsed.baseTitle
            // Only tags: an unknown one is probably the name ("[Dredge]").
            else -> parsed.unknownTags.firstOrNull()?.text ?: return fallback(original)
        }

        t = LeadingNumbers.strip(collapse(t.replace(strayBrackets, " ")))
        val scene = ' ' !in t && ('.' in t || '_' in t)
        t = collapse(t.replace('_', ' '))
        group.find(t)?.takeIf { it.range.first > 0 }?.let { m ->
            val name = m.groupValues[1]
            // In a scene name any upper-case group goes ("-XYZ"), but not a Roman numeral ("-III").
            val upper = name.count { it.isUpperCase() } >= 2 && name.none { it.isLowerCase() } && !romanNumeral.matches(name)
            if (name.lowercase() in knownGroups || (scene && upper)) t = t.substring(0, m.range.first)
        }
        if (' ' !in t && '.' in t) t = undot(t)

        var previous: String
        do {
            previous = t
            for (r in listOf(version, versionWord, build, size, bitness)) t = collapse(t.without(r, " "))
            t = t.without(bareVersion)
            for (r in trailing) t = t.without(r)
            t = t.without(titleIdInFront).without(discIdInFront)
            t = LeadingNumbers.strip(t)
            t = t.without(edges)
        } while (t != previous)

        t = DisplayNameCleaner.invertArticles(t)
        t = collapse(t.replace(emptySeparators, "-")).removePrefix("- ").removeSuffix(" -").trim()
        var unsplit: String? = null
        if (' ' !in t) {
            val split = collapse(t.replace(camel, " "))
            if (split != t) {
                unsplit = t
                t = split
            }
        }
        t = capitalise(t)
        if (t.none { it.isLetterOrDigit() }) return fallback(original)
        return Result(t, unsplit)
    }

    /** "Dredge.v1.4.2" -> "Dredge", "S.T.A.L.K.E.R.Shadow.of.Chernobyl" -> "STALKER Shadow of Chernobyl". */
    private fun undot(t: String): String {
        val parts = t.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2 || parts.all { it.length == 1 }) return t
        val words = ArrayList<String>()
        var i = 0
        while (i < parts.size) {
            val p = parts[i]
            val next = parts.getOrNull(i + 1)
            if (sceneVersion.matches(p) && (p.any { it.isDigit() } || next?.all { it.isDigit() } == true)) {
                i++
                while (i < parts.size && parts[i].all { it.isDigit() }) i++
                continue
            }
            words += p
            i++
        }
        // A trailing "1.6.8" is a version as well.
        val run = words.takeLastWhile { w -> w.all { it.isDigit() } }.size
        if (run >= 3 && run < words.size) repeat(run) { words.removeAt(words.lastIndex) }
        // Two or more single capitals in a row were one word.
        val out = ArrayList<String>()
        i = 0
        while (i < words.size) {
            var end = i
            while (end < words.size && words[end].length == 1 && words[end][0].isUpperCase()) end++
            if (end - i >= 2) {
                out += words.subList(i, end).joinToString("")
                i = end
            } else {
                out += words[i++]
            }
        }
        return out.joinToString(" ")
    }

    private fun capitalise(t: String): String {
        if (t.none { it.isLetter() } || t.any { it.isUpperCase() }) return t
        return t.split(' ').mapIndexed { i, w -> if (i > 0 && w in smallWords) w else w.replaceFirstChar { it.uppercaseChar() } }
            .joinToString(" ")
    }

    private fun fallback(original: String): Result =
        Result(DisplayNameCleaner.clean(original).takeIf { c -> c.any { it.isLetterOrDigit() } } ?: original.trim())

    /** This with [r] replaced, unless nothing but separators would be left. */
    private fun String.without(r: Regex, replacement: String = ""): String {
        val out = r.replace(this, replacement)
        return if (out.any { it.isLetterOrDigit() }) out else this
    }

    private fun collapse(s: String): String = s.replace(whitespace, " ").trim()
}
