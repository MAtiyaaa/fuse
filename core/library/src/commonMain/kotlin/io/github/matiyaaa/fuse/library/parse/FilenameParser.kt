package io.github.matiyaaa.fuse.library.parse

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.FilenameTags

/** Which bracket a tag was written in. */
enum class Bracket { ROUND, SQUARE }

/** What Fuse understood a bracket tag to be. */
enum class TagKind {
    REGION,
    LANGUAGE,
    REVISION,
    VERSION,
    DISC,
    DUMP_FLAG,
    STATUS,
    TRANSLATION,
    SERIAL,
    YEAR,
    PUBLISHER,
    VIDEO,
    PROVIDER_ID,
    CONTENT_MARKER,

    /**
     * A re-release channel or compatibility note that does not make it a different game:
     * "(Virtual Console)", "(SGB Enhanced)", "(Evercade)". Recognised but not stored.
     */
    DISTRIBUTION,

    /** The tag holds the title itself, as in `BLUS30001-[Game Name]`. */
    TITLE,

    /** Several recognised kinds in one tag, for example "(Europe, En)". */
    MIXED,

    /** Not a known tag: probably part of the title ("(Tengen)", "(Bonus Disc)"). */
    UNKNOWN,
}

/**
 * One bracket tag as found in a name.
 *
 * @property text Content between the brackets, trimmed.
 * @property range Character range of the whole tag including brackets, in the parsed stem.
 */
data class NameTag(
    val text: String,
    val bracket: Bracket,
    val kind: TagKind,
    val range: IntRange,
) {
    /** The tag as written, with its brackets. */
    val raw: String get() = if (bracket == Bracket.ROUND) "($text)" else "[$text]"
}

/**
 * A file or folder name split into title, extension and tags. Parsing never changes files.
 *
 * @property baseTitle Title without any tags or extension, for example "Metroid Fusion".
 * @property extension Lower-case extension without the dot, or "".
 * @property stem The name without its extension, exactly as given.
 * @property tags Region, language, revision, version, disc, flags and serial.
 * @property year TOSEC release year, when present.
 * @property publisher TOSEC publisher (the tag right after the year), when present.
 * @property translation Target language of a fan translation ("[T+Fre]" -> "French").
 * @property nameTags Every bracket tag, in order, with its classification.
 * @property discSort Sort key for multi-disc ordering (disc number, then side).
 */
data class ParsedName(
    val baseTitle: String,
    val extension: String,
    val stem: String,
    val tags: FilenameTags,
    val year: Int? = null,
    val publisher: String? = null,
    val translation: String? = null,
    val nameTags: List<NameTag> = emptyList(),
    val discSort: Int? = null,
) {
    /** Tags Fuse could not classify, in order. */
    val unknownTags: List<NameTag> get() = nameTags.filter { it.kind == TagKind.UNKNOWN }

    /** True when the name carries an update marker ("[UPD]", "(Update)", a Switch update id or version). */
    val isUpdate: Boolean get() = NameFlags.UPDATE in tags.flags

    /** True when the name carries a DLC marker ("[DLC]", a Switch DLC title id). */
    val isDlc: Boolean get() = NameFlags.DLC in tags.flags
}

/**
 * Parses No-Intro, Redump, GoodTools and TOSEC style names, for example
 * `Metroid Fusion (USA) (Rev 1) [!].gba`, `Final Fantasy VII (Disc 2 of 3) (Europe) (En,Fr,De).chd`,
 * `Pokemon - Emerald Version (U) [T+Fre].gba` or `Game (1990)(Publisher)(Disk 1 of 2)[cr].adf`.
 *
 * Tags follow RomM's `parse_tags`: every `(...)` and `[...]` group, split on commas. Region and
 * language codes are normalised to full names ("U" -> "USA", "En" -> "English"). Unbalanced or
 * nested brackets never throw: an unclosed bracket is treated as ordinary text, and a nested
 * group stays inside its outer tag.
 */
object FilenameParser {
    private val revisionRegex = Regex(
        "^rev(?:ision)?(?:[\\s._-]+([0-9a-z][0-9a-z.]*)|(\\d[0-9a-z.]*))$",
        RegexOption.IGNORE_CASE,
    )
    private val versionRegex = Regex("^(?:version|ver|v)[\\s._-]*(\\d[0-9a-z.\\-]*)$", RegexOption.IGNORE_CASE)
    private val discRegex = Regex(
        "^(disc|disk|cd|dvd|tape|side)(?:[\\s._-]*([0-9]{1,2})|[\\s._-]+([a-z]))" +
            "(?:\\s*(?:of|/)\\s*([0-9]{1,2}))?(?:\\s*side\\s*([a-z]))?$",
        RegexOption.IGNORE_CASE,
    )

    // A disc marker at the end of the untagged title: "Game - Disc 2", "Game CD1", "Game_Disk_3".
    private val trailingDiscRegex = Regex(
        "[\\s_-]+(?:disc|disk|cd)[\\s_]*([0-9]{1,2})(?:\\s*of\\s*([0-9]{1,2}))?$",
        RegexOption.IGNORE_CASE,
    )

    // The same marker anywhere before a tag or the end, for grouping keys.
    private val inlineDiscRegex = Regex(
        "[\\s_-]+(?:disc|disk|cd)[\\s_]*[0-9]{1,2}(?:\\s*of\\s*[0-9]{1,2})?(?=\\s*[(\\[]|\\s*$)",
        RegexOption.IGNORE_CASE,
    )
    private val dumpFlagRegex = Regex("^(cr|tr|[abfhmoptuvx])(\\d{0,3})([A-Za-z]?)(?:\\s.*)?$")
    private val translationRegex = Regex("^T[+-]?([A-Za-z]{2,3})(?:[^A-Za-z].*)?$")
    private val yearRegex = Regex("^(19|20)[0-9x]{2}(?:-[01x][0-9x](?:-[0-3x][0-9x])?)?$", RegexOption.IGNORE_CASE)
    private val statusRegex = Regex("^([a-z ]+?)(?:\\s*\\d+)?$", RegexOption.IGNORE_CASE)
    private val hackRegex = Regex("^hack(?:\\s.*)?$", RegexOption.IGNORE_CASE)
    private val multiLanguageRegex = Regex("^M\\d{1,2}$")
    private val providerRegex = Regex("^([a-z]+)-(\\d+)$", RegexOption.IGNORE_CASE)
    private val tosecVersionRegex = Regex("\\s+v(\\d[0-9a-z.]*)$", RegexOption.IGNORE_CASE)
    private val extensionRegex = Regex("^[a-z0-9]{1,8}$")

    /**
     * Parses [name]. When [hasExtension] is true the last ".xyz" part (1 to 8 letters or digits)
     * is split off; pass false for folder names and for titles that no longer have an extension.
     */
    fun parse(name: String, hasExtension: Boolean = true): ParsedName {
        val trimmed = name.trim()
        val ext = if (hasExtension) FsPath.extension(trimmed).takeIf { extensionRegex.matches(it) } ?: "" else ""
        val stem = if (ext.isEmpty()) trimmed else trimmed.substring(0, trimmed.length - ext.length - 1)
        return parseStem(stem, ext)
    }

    private class Acc {
        val regions = LinkedHashSet<String>()
        val languages = LinkedHashSet<String>()
        val flags = LinkedHashSet<String>()
        var revision: String? = null
        var version: String? = null
        var discNumber: Int? = null
        var discTotal: Int? = null
        var discSide: Int = 0
        var serial: String? = null
        var year: Int? = null
        var publisher: String? = null
        var translation: String? = null
    }

    private fun parseStem(stem: String, ext: String): ParsedName {
        val split = splitTags(stem)
        val acc = Acc()
        val tags = ArrayList<NameTag>(split.tags.size)
        var previous: NameTag? = null
        for ((text, bracket, range) in split.tags) {
            val tosecPublisher = previous?.kind == TagKind.YEAR && previous.range.last + 1 == range.first
            val kind = if (tosecPublisher && bracket == Bracket.ROUND) {
                acc.publisher = text.takeIf { it != "-" }
                TagKind.PUBLISHER
            } else {
                classifyTag(text, bracket, acc)
            }
            val tag = NameTag(text, bracket, kind, range)
            tags.add(tag)
            previous = tag
        }

        var title = collapse(split.outside)

        // Title-level disc marker ("Game - Disc 2") when no tag carried one.
        if (acc.discNumber == null) {
            trailingDiscRegex.find(title)?.let { m ->
                acc.discNumber = m.groupValues[1].toInt()
                acc.discTotal = m.groupValues[2].toIntOrNull()
                title = title.substring(0, m.range.first)
            }
        }

        // TOSEC puts the version in the title: "Game v1.2 (1990)(Publisher)".
        if (acc.version == null && acc.year != null) {
            tosecVersionRegex.find(title)?.let { m ->
                acc.version = m.groupValues[1]
                title = title.substring(0, m.range.first)
            }
        }

        // A bare serial in the title ("BLUS30001", "BLUS30001 - Game").
        var consumedTag: NameTag? = null
        if (acc.serial == null) {
            Serials.find(title)?.let { (serial, range) ->
                acc.serial = serial
                val rest = collapse((title.substring(0, range.first) + " " + title.substring(range.last + 1)).trim(' ', '-', '_'))
                title = if (rest.isNotEmpty()) {
                    rest
                } else {
                    // "BLUS30001-[Game Name]": the only unknown tag is the real title.
                    val candidate = tags.singleOrNull { it.kind == TagKind.UNKNOWN }
                    consumedTag = candidate
                    candidate?.text ?: serial
                }
            }
        }

        title = collapse(collapse(title).trim(' ', '-', '_')).ifEmpty { collapse(stem) }
        val finalTags = tags.map { if (it === consumedTag) it.copy(kind = TagKind.TITLE) else it }

        return ParsedName(
            baseTitle = title,
            extension = ext,
            stem = stem,
            tags = FilenameTags(
                regions = acc.regions.toList(),
                languages = acc.languages.toList(),
                revision = acc.revision,
                version = acc.version,
                discNumber = acc.discNumber,
                discTotal = acc.discTotal,
                flags = acc.flags.toList(),
                serial = acc.serial,
            ),
            year = acc.year,
            publisher = acc.publisher,
            translation = acc.translation,
            nameTags = finalTags,
            discSort = acc.discNumber?.let { it * 10 + acc.discSide },
        )
    }

    private data class RawTag(val text: String, val bracket: Bracket, val range: IntRange)

    private class Split(val outside: String, val tags: List<RawTag>)

    /** Separates top-level bracket groups from the rest of the text. */
    private fun splitTags(stem: String): Split {
        val outside = StringBuilder()
        val tags = ArrayList<RawTag>()
        var i = 0
        while (i < stem.length) {
            val c = stem[i]
            if (c == '(' || c == '[') {
                val end = findClose(stem, i)
                if (end > i) {
                    val inner = stem.substring(i + 1, end).trim()
                    if (inner.isNotEmpty()) {
                        tags.add(RawTag(inner, if (c == '(') Bracket.ROUND else Bracket.SQUARE, i..end))
                    }
                    outside.append(' ')
                    i = end + 1
                    continue
                }
            }
            outside.append(c)
            i++
        }
        return Split(outside.toString(), tags)
    }

    /** Index of the bracket closing the one at [start], counting nested groups of either kind; -1 if unclosed. */
    private fun findClose(s: String, start: Int): Int {
        var depth = 0
        for (j in start until s.length) {
            when (s[j]) {
                '(', '[' -> depth++
                ')', ']' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
        }
        return -1
    }

    private fun classifyTag(text: String, bracket: Bracket, acc: Acc): TagKind {
        // Whole-tag forms first: they may contain commas or spaces.
        classifyWhole(text, bracket, acc)?.let { return it }
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return TagKind.UNKNOWN
        val kinds = parts.map { classifyPart(it, bracket, acc) }
        return when {
            kinds.any { it == null } -> TagKind.UNKNOWN
            kinds.distinct().size == 1 -> kinds.first()!!
            else -> TagKind.MIXED
        }
    }

    private fun classifyWhole(text: String, bracket: Bracket, acc: Acc): TagKind? {
        if (bracket == Bracket.SQUARE) {
            if (text == "!") return flag(acc, NameFlags.VERIFIED, TagKind.DUMP_FLAG)
            if (text == "!p") return flag(acc, NameFlags.PENDING, TagKind.DUMP_FLAG)
            translationRegex.matchEntire(text)?.let { m ->
                val lang = TagTables.translationCodes[m.groupValues[1].lowercase()]
                    ?: TagTables.languageCodes[m.groupValues[1].lowercase()]
                if (lang != null) {
                    acc.translation = acc.translation ?: lang
                    return flag(acc, NameFlags.TRANSLATION, TagKind.TRANSLATION)
                }
            }
            dumpFlagRegex.matchEntire(text)?.let { m ->
                TagTables.dumpFlags[m.groupValues[1]]?.let { return flag(acc, it, TagKind.DUMP_FLAG) }
            }
        }
        Serials.normalize(text)?.let { serial ->
            acc.serial = acc.serial ?: serial
            // A Switch update/DLC id marks the file as such.
            when (Serials.switchTitleKind(serial)) {
                SwitchTitleKind.UPDATE -> acc.flags.add(NameFlags.UPDATE)
                SwitchTitleKind.DLC -> acc.flags.add(NameFlags.DLC)
                else -> Unit
            }
            return TagKind.SERIAL
        }
        if (hackRegex.matches(text)) return flag(acc, NameFlags.HACK, TagKind.STATUS)
        val lower = text.lowercase()
        TagTables.contentMarkers[lower]?.let { return flag(acc, it, TagKind.CONTENT_MARKER) }
        revisionRegex.matchEntire(text)?.let { m ->
            val value = m.groupValues[1].ifEmpty { m.groupValues[2] }
            acc.revision = acc.revision ?: value.uppercaseIfLetter()
            return TagKind.REVISION
        }
        versionRegex.matchEntire(text)?.let { m ->
            val v = m.groupValues[1]
            acc.version = acc.version ?: v
            // Switch updates carry their version as a multiple of 65536 ("[v131072]").
            v.toLongOrNull()?.let { n -> if (n > 0 && n % 65536 == 0L) acc.flags.add(NameFlags.UPDATE) }
            return TagKind.VERSION
        }
        discRegex.matchEntire(text)?.let { m ->
            val word = m.groupValues[1].lowercase()
            val number = m.groupValues[2].toIntOrNull() ?: letterIndex(m.groupValues[3])
            if (word == "side") {
                if (acc.discNumber == null) acc.discNumber = number
                acc.discSide = number
            } else {
                acc.discNumber = acc.discNumber ?: number
                acc.discTotal = acc.discTotal ?: m.groupValues[4].toIntOrNull()
                m.groupValues[5].takeIf { it.isNotEmpty() }?.let { acc.discSide = letterIndex(it) }
            }
            return TagKind.DISC
        }
        if (bracket == Bracket.ROUND && yearRegex.matches(text)) {
            acc.year = acc.year ?: text.take(4).toIntOrNull()
            return TagKind.YEAR
        }
        if (lower in TagTables.videoStandards) return TagKind.VIDEO
        if (bracket == Bracket.ROUND && lower in TagTables.distributionTags) return TagKind.DISTRIBUTION
        providerRegex.matchEntire(text)?.let { m ->
            if (m.groupValues[1].lowercase() in TagTables.providerIdPrefixes) return TagKind.PROVIDER_ID
        }
        statusRegex.matchEntire(text)?.let { m ->
            TagTables.statusWords[m.groupValues[1].trim().lowercase()]?.let { flagName ->
                val kind = if (flagName == NameFlags.TRANSLATION) TagKind.TRANSLATION else TagKind.STATUS
                return flag(acc, flagName, kind)
            }
        }
        return classifyPart(text, bracket, acc)
    }

    /** Region / language parts, which may appear several to a tag: "(USA, Europe)", "(En,Fr,De)". */
    private fun classifyPart(part: String, bracket: Bracket, acc: Acc): TagKind? {
        TagTables.regionCodes[part]?.let { acc.regions.add(it); return TagKind.REGION }
        TagTables.regionNames[part.lowercase()]?.let { acc.regions.add(it); return TagKind.REGION }
        if (part.length in 2..4 && part.all { it in TagTables.comboRegionLetters }) {
            part.forEach { c -> acc.regions.add(TagTables.regionCodes.getValue(c.toString())) }
            return TagKind.REGION
        }
        TagTables.languageCodes[part.lowercase()]?.takeIf { part.length == 2 }?.let {
            acc.languages.add(it)
            return TagKind.LANGUAGE
        }
        TagTables.languageNames[part.lowercase()]?.let { acc.languages.add(it); return TagKind.LANGUAGE }
        if (multiLanguageRegex.matches(part)) return TagKind.LANGUAGE
        if (bracket == Bracket.ROUND) {
            TagTables.statusWords[part.lowercase()]?.let { return flag(acc, it, TagKind.STATUS) }
        }
        return null
    }

    private fun flag(acc: Acc, name: String, kind: TagKind): TagKind {
        acc.flags.add(name)
        return kind
    }

    private fun letterIndex(letter: String): Int = letter.lowercase()[0] - 'a' + 1

    private fun String.uppercaseIfLetter(): String = if (length == 1 && this[0].isLetter()) uppercase() else this

    private val whitespace = Regex("\\s+")

    internal fun collapse(s: String): String = s.replace(whitespace, " ").trim()

    /**
     * [stem] with disc tags and title-level disc markers removed, whitespace collapsed. Two discs
     * of the same release produce the same key, while different releases ("(USA)" vs "(Europe)")
     * do not.
     */
    fun withoutDisc(parsed: ParsedName): String {
        var s = parsed.stem
        parsed.nameTags.filter { it.kind == TagKind.DISC }.sortedByDescending { it.range.first }.forEach { tag ->
            s = s.removeRange(tag.range)
        }
        s = inlineDiscRegex.replace(collapse(s), "")
        return collapse(s).trim(' ', '-', '_')
    }
}
