package io.github.matiyaaa.fuse.library.parse

/**
 * What a Switch file's name says it is, read the many ways people name them: a base game, an
 * update or DLC, with or without a title id.
 *
 * - `Game [0100ABCD12345000][v0][US].nsp`, `Game[0100ABCD12345000][US][v0].nsp` (an id in brackets)
 * - `Game 0100ABCD12345800__v65536__.nsz`, `game-[0100abcd12345800][v131072][1.0.2].nsp` (an id
 *   outside brackets, lower case)
 * - `Game v1.1.3.nsp`, `Game v524288.nsp`, `Game Update v1.6.15.13.nsp`, `Game (Update 1.0.1).nsp`,
 *   `Game [Update v1.4.1].nsp`, `Game [Up v1.86.1233].nsp`, `Game v1.1.0[...][65536][UPD].nsp`
 * - `Game DLC.Booster.Course.Pass.nsp`, `Game [Hat DLC][USA][v0].nsp`, `Game__DLC_Pack_1_Name___v196608_.nsp`
 * - `Game Base eShop NSP.nsp`, `Game Switch XCI Base Game.xci`, `Game(1).nsp`, `Game [...]-002.xci`,
 *   `Game [...](nsw2u.xyz).xci`, `Game [...] - nswpedia.com(1).nsp`
 * - `Game [0100ABCD12345000]+[v13.0.0+99DLC][Patched].xci`: one file holding the game, its update and DLC.
 *
 * An update named with its game's own id but a version above 0 (`Game [0100ABCD12345000][v131072]`)
 * is an update: the version tells, whatever the id says. Nothing here reads the files themselves.
 */
object SwitchNames {
    /** One Switch file name, read. */
    data class Name(
        /** BASE, UPDATE or DLC when the name says so; null when it says nothing either way. */
        val kind: SwitchTitleKind?,
        /** The title id in the name, upper-cased, if any. */
        val titleId: String?,
        /** The base game's title id, from [titleId]. */
        val baseId: String?,
        /** The game's title, without tags, versions and markers, for finding the game a file belongs to. */
        val key: String,
        /** The whole name without noise (bracketed names of DLC kept), for telling copies apart. */
        val fullKey: String,
        /** The version the name gives, normalised (`65536`, `1.0.3`), or null. */
        val version: String?,
        /** One file holding the game with its update and DLC (`+[v13.0.0+99DLC]`). */
        val bundle: Boolean,
        /** Bracketed text that names something (`[New Uniform Set]`), not a tag Fuse knows. */
        val extra: Boolean,
    )

    private val extensions = setOf("nsp", "nsz", "xci", "xcz", "nca", "nro", "nso")

    private val idRegex = Regex("(?<![0-9A-Za-z])(01[0-9A-Fa-f]{14})(?![0-9A-Za-z])")

    // A Switch version: a multiple of 65536 (or 0), with or without a "v", in brackets or not.
    private val numericVersion = Regex("(?<![0-9A-Za-z.])v?(\\d{1,8})(?![0-9.]*\\.\\d)(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)

    // A dotted version: "v1.1.3", "v.1.2.0", "1.0.3.37670", "v1.25.9.19_5499".
    private val dottedVersion = Regex("(?<![0-9A-Za-z])(?:v\\.?\\s*)?(\\d{1,4}(?:\\.\\d{1,6}){1,4})(?:_\\d+)?(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)
    private val vDotted = Regex("(?<![0-9A-Za-z])v\\.?\\s*\\d{1,4}(?:\\.\\d{1,6}){1,4}", RegexOption.IGNORE_CASE)

    private val bundleRegex = Regex("\\+\\s*\\[?v?[0-9.]+\\s*\\+\\s*\\d*\\s*dlcs?\\]?", RegexOption.IGNORE_CASE)
    private val siteRegex = Regex("(?:\\s-\\s*)?\\b[a-z0-9-]+\\.(?:com|net|org|xyz|io|to|cc|me|ru)\\b", RegexOption.IGNORE_CASE)
    private val copySuffix = Regex("(?:\\(\\d{1,2}\\)|-\\d{3})\\s*$")
    private val firmware = Regex("^fw\\s*\\d", RegexOption.IGNORE_CASE)
    private val sceneName = Regex("^[a-z0-9]{2,5}-[a-z0-9]+(?:_[a-z0-9.]+)+$")

    // Any 16 hex digits (homebrew ids start with other digits than "01"): never part of a title.
    private val anyId = Regex("(?<![0-9A-Za-z])[0-9A-Fa-f]{16}(?![0-9A-Za-z])")

    // A version stuck to the word before it: "Balladv196608".
    private val gluedVersion = Regex("(?<=[a-z])v(\\d{5,8})(?![0-9A-Za-z])")

    private val updateWords = setOf("update", "upd", "updated")
    private val dlcWords = setOf("dlc", "dlcs", "addon", "addons", "aoc")
    private val baseWords = setOf("base")

    // Words that only describe the file, never the game.
    private val noiseWords = setOf(
        "nsp", "nsz", "xci", "xcz", "eshop", "patched", "trimmed", "untrimmed", "decrypted", "repack",
        "us", "usa", "eu", "eur", "europe", "jp", "jpn", "japan", "world", "global", "asia", "kor", "ww",
        "app", "romslab", "nswpedia", "nsw2u",
    )

    /** Reads [fileName] (with its extension, or a folder's name). */
    fun read(fileName: String): Name {
        var name = clean(fileName.trim())
        name = gluedVersion.replace(name) { m -> if ((m.groupValues[1].toLongOrNull() ?: 1) % 65536 == 0L) " v" + m.groupValues[1] else m.value }
        name.substringAfterLast('.', "").lowercase().takeIf { it in extensions }?.let { name = name.dropLast(it.length + 1) }
        // Scene names: "sxs-super_mario_bros_wonder_v327680" (a group's short tag, then the title in snake case).
        if (sceneName.matches(name)) name = name.substringAfter('-')
        val bundle = bundleRegex.containsMatchIn(name)
        name = siteRegex.replace(name, " ")
        name = name.replace(Regex("\\(\\s*\\)|\\[\\s*\\]"), " ").trim()
        name = copySuffix.replace(name, "").trim()

        val titleId = idRegex.find(name)?.groupValues?.get(1)?.uppercase()
        val baseId = titleId?.let(Serials::switchBaseTitleId)

        // Every bracketed group, and what is outside them.
        val groups = Regex("[\\[(]([^\\[\\]()]*)[\\])]").findAll(name).map { it.groupValues[1].trim() }.toList()
        val outside = Regex("[\\[(][^\\[\\]()]*[\\])]").replace(name, " ")

        val words = { s: String ->
            s.lowercase().replace("'", "").replace("&", " and ")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() }
        }
        val allWords = words(name.replace(idRegex, " "))
        var dlcWord = allWords.any { it in dlcWords }
        val updateWord = allWords.any { it in updateWords } || Regex("(?<![a-z])up\\s+v?\\d", RegexOption.IGNORE_CASE).containsMatchIn(name)
        val baseWord = allWords.any { it in baseWords }

        // Versions: Switch's own numbers first, then dotted ones.
        var numeric: Long? = null
        var dotted: String? = null
        for (g in groups + listOf(outside)) {
            if (firmware.containsMatchIn(g)) continue
            val text = g.replace(idRegex, " ")
            for (m in numericVersion.findAll(text)) {
                val token = m.value
                val n = m.groupValues[1].toLongOrNull() ?: continue
                val explicit = token.startsWith("v", ignoreCase = true)
                // A bare number counts only as the whole of a bracketed tag, or when it is a Switch version.
                val whole = g.trim().equals(token, ignoreCase = true)
                if ((explicit && (n == 0L || n % 65536 == 0L)) || (n > 0 && n % 65536 == 0L && (whole || explicit))) {
                    numeric = maxOf(numeric ?: 0, n)
                }
            }
        }
        val dottedSources = buildList {
            groups.filterNot { firmware.containsMatchIn(it) }.forEach(::add)
            add(outside)
        }
        for (s in dottedSources) {
            val text = s.replace(idRegex, " ")
            val m = dottedVersion.find(text) ?: continue
            // A dotted number counts with a "v", after "update"/"up", or as a bracketed tag of its own.
            val hasV = vDotted.containsMatchIn(m.value)
            val afterUpdate = Regex("(?:update|upd|up)\\s*[:-]?\\s*v?\\.?\\s*${Regex.escape(m.groupValues[1])}", RegexOption.IGNORE_CASE).containsMatchIn(text)
            val whole = s.trim().removePrefix("v").removePrefix("V").trim() == m.groupValues[1]
            if (hasV || afterUpdate || (whole && s != outside)) {
                dotted = m.groupValues[1]
                break
            }
        }

        // Text in brackets that names something rather than tagging the file.
        val extraGroups = groups.filter { g -> isExtra(g) }
        val extraNamesDlc = extraGroups.any { g -> words(g).any { it in dlcWords } }
        if (extraNamesDlc) dlcWord = true

        val versionValue = numeric
        val versionAboveZero = (versionValue != null && versionValue > 0) ||
            (dotted != null && dotted!!.split('.').any { it.trimStart('0').isNotEmpty() })

        val idKind = titleId?.let(Serials::switchTitleKind)
        val kind = when {
            bundle -> SwitchTitleKind.BASE
            idKind == SwitchTitleKind.DLC -> SwitchTitleKind.DLC
            idKind == SwitchTitleKind.UPDATE -> SwitchTitleKind.UPDATE
            idKind == SwitchTitleKind.BASE ->
                if (updateWord || (versionValue != null && versionValue > 0)) SwitchTitleKind.UPDATE else SwitchTitleKind.BASE
            dlcWord -> SwitchTitleKind.DLC
            updateWord -> SwitchTitleKind.UPDATE
            versionValue == 0L -> SwitchTitleKind.BASE
            versionAboveZero -> SwitchTitleKind.UPDATE
            baseWord -> SwitchTitleKind.BASE
            else -> null
        }

        val key = keyOf(words(stripVersions(outside)))
        val fullText = buildString {
            append(stripVersions(outside))
            extraGroups.forEach { append(' ').append(it) }
        }
        val fullKey = keyOf(words(fullText))
        val version = numeric?.toString() ?: dotted
        return Name(kind, titleId, baseId, key, fullKey, version, bundle, extraGroups.isNotEmpty() && !extraNamesDlc)
    }

    /** The game's title words: markers, noise and versions gone. */
    private fun keyOf(words: List<String>): String {
        val out = words.toMutableList()
        out.removeAll { it in updateWords || it in dlcWords || it in noiseWords }
        // "Base Game" describes the file; "Base" alone too.
        var i = 0
        while (i < out.size) {
            if (out[i] == "base") {
                out.removeAt(i)
                if (i < out.size && out[i] == "game") out.removeAt(i)
            } else {
                i++
            }
        }
        // A trailing word said once already only describes the file ("... Switch Edition Switch XCI").
        while (out.size > 1 && out.last() in out.dropLast(1)) out.removeAt(out.lastIndex)
        return out.joinToString(" ")
    }

    private fun stripVersions(text: String): String =
        text.replace(idRegex, " ")
            .replace(anyId, " ")
            .replace(Regex("(?<![0-9A-Za-z])v\\.?\\s*\\d{1,4}(?:\\.\\d{1,6}){1,4}(?:_\\d+)?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?<![0-9A-Za-z])(?:update|upd|up)\\s*[:-]?\\s*v?\\.?\\s*\\d{1,4}(?:\\.\\d{1,6}){1,4}(?:_\\d+)?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?<![0-9A-Za-z])v\\d{1,8}(?![0-9A-Za-z])", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?<![0-9A-Za-z.])\\d{5,8}(?![0-9A-Za-z.])"), " ")

    /** True for bracketed text that names something, not a tag (region, version, id, marker, site). */
    private fun isExtra(group: String): Boolean {
        val g = group.trim()
        if (g.isEmpty()) return false
        if (anyId.containsMatchIn(g)) return false
        if (Regex("^\\d+(?:\\.\\d+)?\\s*(?:gb|mb|kb)$", RegexOption.IGNORE_CASE).matches(g)) return false
        if (firmware.containsMatchIn(g)) return false
        val words = g.lowercase().replace(Regex("[^\\p{L}\\p{N}.]+"), " ").trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val tagWords = updateWords + dlcWords + baseWords + noiseWords + setOf("v", "game", "cr", "ver", "version", "update", "up", "rev")
        val versionish = Regex("^v?\\.?\\d+(?:\\.\\d+)*$")
        val region = FilenameParser.parse("Title ($g)", hasExtension = false).tags.let { it.regions.isNotEmpty() || it.languages.isNotEmpty() }
        if (region) return false
        // Only markers, versions and short codes ("CR-13", "US", "v0", "13.0.4"): a tag.
        val meaningful = words.filterNot { it in tagWords || versionish.matches(it) || it.matches(Regex("^[a-z]{1,2}-?\\d+$")) }
        if (meaningful.isEmpty()) return false
        // "DLC Shang Tsung", "Astronomers Hat DLC", "Tomb Raider Special Pack": a name.
        return true
    }

    /** Folds look-alike punctuation (Windows' "꞉" for ":", curly quotes, full-width brackets) and drops ™ and ®. */
    private fun clean(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '꞉', '：' -> append(':')
                '’', '‘', 'ʼ' -> append('\'')
                '“', '”' -> append('"')
                '【', '［' -> append('[')
                '】', '］' -> append(']')
                '（' -> append('(')
                '）' -> append(')')
                '™', '®', '©' -> Unit
                else -> append(c)
            }
        }
    }
}
