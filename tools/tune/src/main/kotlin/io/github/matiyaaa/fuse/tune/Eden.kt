package io.github.matiyaaa.fuse.tune

/**
 * The settings of Eden (and the emulators of its family, which keep yuzu's settings code), from its
 * own code:
 *
 * - src/common/settings.h: `SwitchableSetting<bool> use_vsync{linkage, true, "use_vsync",
 *   Category::Renderer}`, each with its default, a ranged one's minimum and maximum
 *   (`SwitchableSetting<int, true> x{linkage, 100, 0, 200, "key", ...}`), its key and category, then
 *   optionally its specialization and whether it is saved. A `SwitchableSetting` can be set for one
 *   game (`custom/<title id>.ini`); a plain `Setting` only for every game; one not saved is left out.
 * - src/common/settings.cpp `TranslateCategory`: the ini section each category is kept under.
 * - src/common/settings_enums.h: `ENUM(VSyncMode, Immediate, Mailbox, Fifo, FifoRelaxed)`, written
 *   as each member's number (`Setting::ToString`, settings_setting.h).
 * - src/qt_common/config/shared_translation.cpp: `INSERT(Settings, use_vsync, tr("VSync Mode:"),
 *   tr("..."))`, each setting's name and description, and `PAIR(VSyncMode, Fifo, tr("FIFO (VSync)"))`
 *   what each value is called.
 *
 * Its files (src/frontend_common/config.cpp, SimpleIni without spaces) keep a value as `key=value`
 * with `key\default` saying whether it is the default and, in a game's file, `key\use_global`
 * (`Config::WriteSettingGeneric`).
 */
object Eden {
    const val SETTINGS = "src/common/settings.h"
    const val CATEGORIES = "src/common/settings.cpp"
    const val ENUMS = "src/common/settings_enums.h"
    const val WORDS = "src/qt_common/config/shared_translation.cpp"
    val FILES = listOf(SETTINGS, CATEGORIES, ENUMS, WORDS)

    private val DECLARATION = Regex("""\b(SwitchableSetting|Setting)\s*<\s*([\w:<>]+?)\s*(?:,\s*(true|false)\s*)?>\s*(\w+)\s*\{""")
    private val ENUM_MACRO = Regex("""\bENUM\s*\(\s*(\w+)\s*,([^;]*)\)\s*;""")
    private val INSERT = Regex("""\bINSERT\s*\(""")
    private val PAIR = Regex("""\bPAIR\s*\(\s*(\w+)\s*,\s*(\w+)\s*,\s*tr\s*\(((?:\s*"(?:[^"\\]|\\.)*")+)\s*\)\s*\)""")
    private val INTEGERS = setOf("u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "int", "unsigned", "long", "size_t")
    private val DECIMALS = setOf("f32", "f64", "float", "double")

    fun parse(files: Map<String, String>): List<Found> {
        val settings = Source.withoutComments(files[SETTINGS] ?: return emptyList())
        val sections = sections(Source.withoutComments(files[CATEGORIES].orEmpty()))
        val enums = enums(Source.withoutComments(files[ENUMS].orEmpty()))
        val words = Source.withoutComments(files[WORDS].orEmpty())
        val (named, valueNames) = names(words)
        val out = LinkedHashMap<String, Found>()
        for (d in DECLARATION.findAll(settings)) {
            val (args, _) = Source.arguments(settings, d.range.last, '{', '}') ?: continue
            if (args.firstOrNull()?.trim() != "linkage") continue
            val keyAt = args.indexOfFirst { Source.string(it) != null }.takeIf { it >= 2 } ?: continue
            // As the file has it (Config::AdjustKey): a `/` is a `\`, a space `%20`.
            val key = Source.string(args[keyAt])?.replace("/", "\\")?.replace(" ", "%20") ?: continue
            val category = args.getOrNull(keyAt + 1)?.trim()?.removePrefix("Category::") ?: continue
            val section = sections[category] ?: continue
            // After the category: its specialization, then whether it is saved.
            if (args.drop(keyAt + 2).firstOrNull { it.trim() == "true" || it.trim() == "false" }?.trim() == "false") continue
            val type = d.groupValues[2].substringAfterLast("::")
            val member = d.groupValues[4]
            val raw = args[1].trim()
            val ranged = keyAt >= 4
            val (label, about) = named[member] ?: (null to null)
            val tab = category.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            val perGame = d.groupValues[1] == "SwitchableSetting"
            val found = when {
                type == "bool" -> Found(null, section, key, Kind.BOOL, default = raw.takeIf { it == "true" || it == "false" }, perGame = perGame, label = label, about = about, tab = tab)
                type in INTEGERS -> Found(
                    null, section, key, Kind.INT, default = Source.literal(raw)?.takeIf { it.toLongOrNull() != null },
                    min = if (ranged) Source.literal(args[2])?.toLongOrNull() else null, max = if (ranged) Source.literal(args[3])?.toLongOrNull() else null,
                    perGame = perGame, label = label, about = about, tab = tab,
                )
                type in DECIMALS -> Found(null, section, key, Kind.FLOAT, default = Source.literal(raw)?.takeIf { it.toDoubleOrNull() != null }, perGame = perGame, label = label, about = about, tab = tab)
                type == "string" -> Found(null, section, key, Kind.STRING, default = Source.string(raw), perGame = perGame, label = label, about = about, tab = tab)
                enums.containsKey(type) -> {
                    val members = enums.getValue(type)
                    val names = valueNames[type].orEmpty()
                    val choices = members.mapIndexed { i, m -> Choice(i.toString(), names[m] ?: m) }
                    val default = raw.substringAfterLast("::").let { m -> members.indexOf(m).takeIf { it >= 0 }?.toString() }
                    Found(null, section, key, Kind.ENUM, default = default, choices = choices, perGame = perGame, label = label, about = about, tab = tab)
                }
                else -> continue
            }
            out.putIfAbsent(found.identity, found)
        }
        return out.values.toList()
    }

    /** `TranslateCategory`'s switch: each category to the section it is written under. */
    private fun sections(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        val body = text.substringAfter("TranslateCategory", "").ifEmpty { return out }
        val pending = ArrayList<String>()
        for (m in Regex("""case\s+Category::(\w+)\s*:|return\s+"([^"]*)"\s*;""").findAll(body)) {
            if (m.groupValues[1].isNotEmpty()) pending += m.groupValues[1]
            else if (pending.isNotEmpty()) {
                pending.forEach { out.putIfAbsent(it, m.groupValues[2]) }
                pending.clear()
            } else break
        }
        return out
    }

    /** Every enum's members in order: the `ENUM(...)` macro's, and those written out (`enum class AudioEngine : u32 {...}`). */
    private fun enums(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for ((name, members) in Source.enums(text)) out[name] = members.sortedBy { it.second }.map { it.first }
        for (m in ENUM_MACRO.findAll(text)) out[m.groupValues[1]] = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return out
    }

    /** Each setting's name and description by its member, and each enum value's name. */
    private fun names(text: String): Pair<Map<String, Pair<String?, String?>>, Map<String, Map<String, String>>> {
        fun tr(arg: String?): String? {
            val inside = Regex("""^\s*tr\s*\((.*)\)\s*$""", RegexOption.DOT_MATCHES_ALL).find(arg.orEmpty())?.groupValues?.get(1) ?: return null
            val joined = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(inside).joinToString("") { it.groupValues[1] }
            return joined.replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\").trim().trimEnd(':').ifEmpty { null }
        }
        val named = HashMap<String, Pair<String?, String?>>()
        for (m in INSERT.findAll(text)) {
            val (args, _) = Source.arguments(text, m.range.last) ?: continue
            if (args.size < 4) continue
            named.putIfAbsent(args[1].trim(), tr(args[2]) to tr(args[3]))
        }
        val values = HashMap<String, MutableMap<String, String>>()
        for (m in PAIR.findAll(text)) {
            val label = tr("tr(${m.groupValues[3]})") ?: continue
            values.getOrPut(m.groupValues[1]) { LinkedHashMap() }.putIfAbsent(m.groupValues[2], label)
        }
        return named to values
    }
}
