package io.github.matiyaaa.fuse.tune

/**
 * The settings Cemu lets a Wii U game have: its game profile (`gameProfiles/<title id>.ini`), as
 * Cemu's own code reads it.
 *
 * - src/Cafe/GameProfile/GameProfile.cpp, `GameProfile::Load`: the section each is read in
 *   (`boost::iequals(iniParser.GetCurrentSectionName(), "CPU")`) and how: a switch
 *   (`gameProfile_loadBooleanOption2(iniParser, "disableAudio", m_disableAudio)`), a number in a
 *   range (`gameProfile_loadIntegerOption(iniParser, "threadQuantum", m_threadQuantum, 1000U,
 *   536870912U)`, or through a local that sets the member, as `graphics_api` does), a choice of an
 *   enum (`gameProfile_loadEnumOption(iniParser, "cpuMode", m_cpuMode)`), an option read by name
 *   that sets a member (`FindOption("precompiledShaders")`), or one per controller
 *   (`fmt::format("controller{}", (i + 1))` for each of eight).
 * - src/Cafe/GameProfile/GameProfile.h: each member's type and default (`uint32 m_threadQuantum =
 *   kThreadQuantumDefault`); a `std::optional` one has none of its own and follows Cemu's.
 * - src/config/CemuConfig.h: the enums, and the words `GameProfile::Save` writes each member as
 *   (`fmt::formatter<const CPUMode>`: `case CPUMode::Auto: name = "Auto"`), which `Load` reads back
 *   as well as the member's number. A member its code marks deprecated isn't offered.
 */
object Cemu {
    const val PROFILE = "src/Cafe/GameProfile/GameProfile.cpp"
    const val PROFILE_HEADER = "src/Cafe/GameProfile/GameProfile.h"
    const val CONFIG = "src/config/CemuConfig.h"
    val FILES = listOf(PROFILE, PROFILE_HEADER, CONFIG)

    private val SECTION = Regex("""GetCurrentSectionName\s*\(\s*\)\s*,\s*"(\w+)"""")
    private val READ = Regex("""\bgameProfile_load(BooleanOption2|IntegerOption|EnumOption)\s*\(""")
    private val BY_NAME = Regex("""\bauto\s+(\w+)\s*=\s*iniParser\s*\.\s*FindOption\s*\(\s*"(\w+)"\s*\)""")
    private val NUMBERED = Regex("""for\s*\(\s*int\s+(\w+)\s*=\s*0\s*;\s*\1\s*<\s*(\d+)\s*;[^)]*\)\s*\{[^}]*?FindOption\s*\(\s*fmt::format\s*\(\s*"(\w+)\{\}"""")
    private val MEMBER = Regex("""^\s*(std::optional\s*<\s*(\w+)\s*>|std::array\s*<\s*std::optional\s*<\s*std::string\s*>\s*,\s*\d+\s*>|[\w:]+)\s+m_(\w+)\s*(?:=\s*([^;{]+?)\s*|\{\s*\}\s*)?;""", RegexOption.MULTILINE)
    private val CONSTANT = Regex("""static\s+const(?:expr)?\s+\w+\s+(\w+)\s*=\s*([^;]+);""")
    private val FORMATTER = Regex("""formatter\s*<\s*const\s+(\w+)\s*>\s*:[^{]*\{""")
    private val NAMED = Regex("""case\s+\w+::(\w+)\s*:\s*name\s*=\s*"([^"]*)"""")
    private val INTEGERS = setOf("uint32", "sint32", "uint64", "sint64", "uint16", "sint16", "uint8", "sint8", "int", "unsigned")

    /** A member of [GameProfile]: its type (the inner one of an optional), whether it is optional, and its default as written. */
    private data class Member(val type: String, val optional: Boolean, val default: String?)

    fun parse(files: Map<String, String>): List<Found> {
        val cpp = Source.withoutComments(files[PROFILE] ?: return emptyList())
        val header = Source.withoutComments(files[PROFILE_HEADER] ?: return emptyList())
        val rawConfig = files[CONFIG].orEmpty()
        val config = Source.withoutComments(rawConfig)
        val start = Regex("""bool\s+GameProfile::Load\s*\(""").find(cpp) ?: return emptyList()
        val open = cpp.indexOf('{', start.range.last).takeIf { it > 0 } ?: return emptyList()
        val load = block(cpp, open) ?: return emptyList()
        val constants = CONSTANT.findAll(header).associate { it.groupValues[1] to it.groupValues[2].trim() }
        val members = MEMBER.findAll(header).associate { m ->
            val optional = m.groupValues[1].startsWith("std::optional")
            val type = if (optional) m.groupValues[2] else m.groupValues[1]
            m.groupValues[3] to Member(type.substringAfterLast("::"), optional, m.groupValues[4].trim().takeIf { it.isNotEmpty() })
        }
        // Each enum's members, and the words Cemu writes each as.
        val enums = Source.enums(config).mapValues { (_, list) -> list.filterNot { (name, _) -> deprecated(rawConfig, name) } }
        val words = FORMATTER.findAll(config).associate { f ->
            val body = block(config, f.range.last).orEmpty()
            f.groupValues[1] to NAMED.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        }
        fun enumOf(type: String, min: Long? = null, max: Long? = null): List<Pair<Choice, String>>? {
            val list = enums[type] ?: return null
            val named = words[type]
            // Written as Save writes it: the formatter's words where it has them, else the number.
            return list.filter { (_, v) -> (min == null || v >= min) && (max == null || v <= max) }.map { (name, value) ->
                val label = name.removePrefix("k").takeIf { it.isNotEmpty() }
                (named?.get(name)?.let { Choice(it) } ?: Choice(value.toString(), label)) to name
            }
        }
        fun defaultOf(m: Member, choices: List<Pair<Choice, String>>? = null): String? {
            if (m.optional) return null
            val raw = m.default ?: return null
            if (choices != null) return choices.firstOrNull { it.second == raw.substringAfterLast("::") }?.first?.value
            return Source.literal(constants[raw] ?: raw)
        }

        val out = ArrayList<Pair<Int, Found>>()
        val sections = SECTION.findAll(load).map { it.range.first to it.groupValues[1] }.toList()
        fun sectionAt(at: Int) = sections.lastOrNull { it.first < at }?.second
        for (m in READ.findAll(load)) {
            val section = sectionAt(m.range.first) ?: continue
            val (args, _) = Source.arguments(load, m.range.last) ?: continue
            val key = args.getOrNull(1)?.let(Source::string) ?: continue
            val target = args.getOrNull(2)?.trim()?.removePrefix("&") ?: continue
            val found = when (m.groupValues[1]) {
                "BooleanOption2" -> members[target.removePrefix("m_")]?.let { mem ->
                    Found(null, section, key, Kind.BOOL, default = defaultOf(mem))
                }
                "IntegerOption" -> {
                    // (parser, name, member, min, max), or through a local: (parser, name, &local, unset, min, max).
                    val local = !target.startsWith("m_")
                    val min = Source.literal(args.getOrNull(if (local) 4 else 3).orEmpty())?.toLongOrNull()
                    val max = Source.literal(args.getOrNull(if (local) 5 else 4).orEmpty())?.toLongOrNull()
                    val member = if (local) {
                        Regex("""\bm_(\w+)\s*=\s*\(\s*(\w+)\s*\)\s*${Regex.escape(target)}\s*\.\s*value""").find(load)?.groupValues?.get(1)
                    } else target.removePrefix("m_")
                    val mem = member?.let(members::get)
                    val choices = mem?.let { enumOf(it.type, min, max) }
                    if (choices != null) {
                        Found(null, section, key, Kind.ENUM, default = defaultOf(mem, choices), choices = choices.map { it.first })
                    } else if (mem == null || mem.type in INTEGERS) {
                        Found(null, section, key, Kind.INT, default = mem?.let { defaultOf(it) }, min = min, max = max)
                    } else null
                }
                "EnumOption" -> members[target.removePrefix("m_")]?.let { mem ->
                    enumOf(mem.type)?.let { choices -> Found(null, section, key, Kind.ENUM, default = defaultOf(mem, choices), choices = choices.map { it.first }) }
                }
                else -> null
            } ?: continue
            out += m.range.first to found
        }
        // Read by name, setting a member in the lines after (its legacy words read as the member's).
        for (m in BY_NAME.findAll(load)) {
            val section = sectionAt(m.range.first) ?: continue
            val after = load.substring(m.range.last)
            val member = Regex("""\bm_(\w+)\s*=""").find(after)?.groupValues?.get(1) ?: continue
            val mem = members[member] ?: continue
            val choices = enumOf(mem.type) ?: continue
            out += m.range.first to Found(null, section, m.groupValues[2], Kind.ENUM, default = defaultOf(mem, choices), choices = choices.map { it.first })
        }
        // One per controller: `controller1` to `controller8`, each a profile's name.
        for (m in NUMBERED.findAll(load)) {
            val section = sectionAt(m.range.first) ?: continue
            for (i in 1..m.groupValues[2].toInt()) out += (m.range.first + i) to Found(null, section, "${m.groupValues[3]}$i", Kind.STRING)
        }
        return out.sortedBy { it.first }.map { it.second }.distinctBy { it.identity }.map { it.copy(tab = it.section) }
    }

    /** Whether the line of [name] in an enum says it is deprecated (`DualcoreRecompiler = 2, // deprecated`). */
    private fun deprecated(raw: String, name: String): Boolean =
        Regex("""^\s*$name\b[^\n]*//[^\n]*deprecated""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).containsMatchIn(raw)

    /** The text inside the brace at [open]. */
    private fun block(text: String, open: Int): String? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, i)
                '"' -> { i++; while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1 }
            }
            i++
        }
        return null
    }
}
