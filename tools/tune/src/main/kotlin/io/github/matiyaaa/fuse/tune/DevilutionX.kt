package io.github.matiyaaa.fuse.tune

/**
 * DevilutionX's settings (a Fuse Ports port), from its own code: each `XxxOptions::XxxOptions()`
 * in Source/options.cpp starts with `OptionCategoryBase("Graphics", ...)`, the section of
 * `diablo.ini` its entries are kept in, then one initialiser per entry:
 * `fullscreen("Fullscreen", flags, N_("Fullscreen"), N_("Display the game ..."), true)`, its key,
 * flags, name, description and default, and for a list the values it offers
 * (`{ { ScalingQuality::NearestPixel, N_("Nearest Pixel") }, ... }`, or numbers `{ 22050, 44100 }`).
 * The entry's type is its member's in Source/options.h: `OptionEntryBoolean` a switch, written `1`
 * or `0` (`Ini::set(bool)` in Source/utils/ini.cpp), `OptionEntryEnum<E>` the numbers of E's
 * members, `OptionEntryInt<T>` a number. Entries flagged `Invisible`, which DevilutionX's own
 * settings never show, and those of its own kinds (keys, pad buttons, the resolution, audio device
 * and language, each with a picker of its own in the game) are left out. The code is read as a
 * desktop build compiles it: SDL2, the Speex resampler on, none of the consoles' or phones' macros.
 */
object DevilutionX {
    const val OPTIONS = "Source/options.cpp"
    const val HEADER = "Source/options.h"
    val FILES = listOf(OPTIONS, HEADER)

    /** What a desktop build (DevilutionX's AppImage, its Windows and macOS builds) has defined. */
    private val DESKTOP = mapOf(
        "SDL_VERSION_ATLEAST(2,0,0)" to true,
        "DEVILUTIONX_RESAMPLER_SPEEX" to true,
        "DVL_AULIB_SUPPORTS_SDL_RESAMPLER" to true,
    )

    private val CATEGORY = Regex("""(\w+)::\1\s*\(\s*\)\s*:\s*OptionCategoryBase\s*\(""")
    private val STRUCT = Regex("""struct\s+(\w+)\s*:\s*OptionCategoryBase\s*\{""")
    private val MEMBER = Regex("""^\s*(OptionEntry\w+)(?:<\s*([\w:]+)\s*>)?\s+(\w+)\s*;""", RegexOption.MULTILINE)
    private val DEFINE = Regex("""^\s*#\s*define\s+(\w+)\s+(\S+)\s*$""", RegexOption.MULTILINE)

    fun parse(files: Map<String, String>): List<Found> {
        val truth: (String) -> Boolean? = { DESKTOP[it] }
        val cpp = Source.withoutComments(files[OPTIONS] ?: return emptyList())
        val header = Source.select(Source.withoutComments(files[HEADER] ?: return emptyList()), truth)
        val defines = DEFINE.findAll(cpp).associate { it.groupValues[1] to it.groupValues[2] }
        val code = Source.select(cpp, truth)
        val enums = Source.enums(header)
        val members = members(header)
        val out = LinkedHashMap<String, Found>()
        for (c in CATEGORY.findAll(code)) {
            val type = c.groupValues[1]
            val (catArgs, catEnd) = Source.arguments(code, c.range.last) ?: continue
            val section = catArgs.firstOrNull()?.let(Source::string) ?: continue
            val own = members[type].orEmpty()
            var i = catEnd + 1
            // `, name(args)` after `, name(args)`, until the constructor's body opens.
            while (true) {
                while (i < code.length && code[i].isWhitespace()) i++
                if (i >= code.length || code[i] != ',') break
                var j = i + 1
                while (j < code.length && code[j].isWhitespace()) j++
                val start = j
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val name = code.substring(start, j)
                while (j < code.length && code[j].isWhitespace()) j++
                if (name.isEmpty() || j >= code.length || code[j] != '(') break
                val (args, end) = Source.arguments(code, j) ?: break
                i = end + 1
                val member = own[name] ?: continue
                entry(section, member, args, enums, defines)?.let { out.putIfAbsent(it.identity, it) }
            }
        }
        return out.values.toList()
    }

    /** Each `XxxOptions` struct's entries: member name to its type and, for a template, its argument. */
    private fun members(header: String): Map<String, Map<String, Pair<String, String?>>> {
        val out = HashMap<String, Map<String, Pair<String, String?>>>()
        for (s in STRUCT.findAll(header)) {
            val end = Source.arguments(header, s.range.last, '{', '}')?.second ?: continue
            val body = header.substring(s.range.last + 1, end)
            out[s.groupValues[1]] = MEMBER.findAll(body).associate { it.groupValues[3] to (it.groupValues[1] to it.groupValues[2].ifEmpty { null }) }
        }
        return out
    }

    private fun entry(
        section: String,
        member: Pair<String, String?>,
        args: List<String>,
        enums: Map<String, List<Pair<String, Long>>>,
        defines: Map<String, String>,
    ): Found? {
        val key = args.getOrNull(0)?.let(Source::string) ?: return null
        if ("Invisible" in args.getOrNull(1).orEmpty()) return null
        val label = args.getOrNull(2)?.let(::text)
        val about = args.getOrNull(3)?.let(::text)
        val raw = args.getOrNull(4)?.trim()
        val list = args.getOrNull(5)?.trim()?.takeIf { it.startsWith("{") }
        val (type, of) = member
        return when (type) {
            "OptionEntryBoolean" -> Found(null, section, key, Kind.BOOL, default = raw?.let(Source::literal)?.let { if (it == "true") "1" else "0" }, label = label, about = about)
            "OptionEntryEnum" -> {
                val members = enums[of?.substringAfterLast("::")] ?: return null
                fun number(v: String?): String? = v?.trim()?.substringAfterLast("::")?.let { m -> members.firstOrNull { it.first == m }?.second?.toString() }
                val choices = list?.let { pairs(it) }.orEmpty().mapNotNull { (v, l) -> number(v)?.let { Choice(it, l) } }
                if (choices.isEmpty()) return null
                Found(null, section, key, Kind.ENUM, default = number(raw), choices = choices, label = label, about = about)
            }
            "OptionEntryInt" -> {
                val default = raw?.let { Source.literal(it) ?: defines[it]?.let(Source::literal) }
                val numbers = list?.let { l -> Source.arguments(l, 0, '{', '}')?.first.orEmpty().mapNotNull { Source.literal(it)?.takeIf { n -> n.toLongOrNull() != null } } }.orEmpty()
                if (numbers.isNotEmpty()) Found(null, section, key, Kind.ENUM, default = default, choices = numbers.map { Choice(it) }, label = label, about = about)
                else Found(null, section, key, Kind.INT, default = default, label = label, about = about)
            }
            else -> null
        }
    }

    /** `{ { A::B, N_("Label") }, ... }`: each value and what it is called. */
    private fun pairs(list: String): List<Pair<String, String?>> =
        Source.arguments(list, 0, '{', '}')?.first.orEmpty().mapNotNull { item ->
            val (parts, _) = Source.arguments(item.trim(), 0, '{', '}') ?: return@mapNotNull null
            parts.getOrNull(0)?.let { it to parts.getOrNull(1)?.let(::text) }
        }

    /** The text of `N_("…")`, `_("…")` or a plain string. */
    private fun text(arg: String): String? {
        val t = arg.trim()
        val inner = Regex("""^(?:N_|_|P_)\s*\((.*)\)$""", RegexOption.DOT_MATCHES_ALL).find(t)?.groupValues?.get(1) ?: t
        return Source.string(inner)
    }
}
