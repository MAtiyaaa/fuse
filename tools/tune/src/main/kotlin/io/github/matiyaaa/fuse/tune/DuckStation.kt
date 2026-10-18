package io.github.matiyaaa.fuse.tune

/**
 * DuckStation's settings, from its own code (src/core/settings.cpp): every
 * `si.GetBoolValue("Section", "Key", default)` (and its Int, UInt, Float, String and StringView
 * kinds) in the functions that load settings. A value read through `ParseXName(...)` is an enum,
 * written as the names the matching `Settings::ParseXName` looks up in its `s_..._names` array and
 * called what its `s_..._display_names` array says; names inside `#ifdef _WIN32` or
 * `#ifdef __APPLE__` are those systems' alone (see [Choices]).
 * A default written as a constant (`DEFAULT_GPU_RENDERER`) is read from src/core/settings.h, and
 * one written as a name (`GetRendererName(DEFAULT_GPU_RENDERER)`) is the array's entry at that
 * member's value in src/core/types.h, as `GetRendererName` indexes it. A constant defined more than
 * once (one per system or processor) is left as worked out at run time.
 * DuckStation reads a game's own settings over the same keys, so every one can be set per game.
 */
object DuckStation {
    const val SETTINGS = "src/core/settings.cpp"
    const val HEADER = "src/core/settings.h"
    const val TYPES = "src/core/types.h"
    val FILES = listOf(SETTINGS, HEADER, TYPES)

    private val GET = Regex("""si\.Get(Bool|Int|UInt|Float|Double|String|StringView|Tiny\w*)Value\(""")

    fun parse(files: Map<String, String>): List<Found> {
        val text = Source.withoutComments(files[SETTINGS] ?: return emptyList())
        val constants = Source.constants(Source.withoutComments(files[HEADER].orEmpty()))
        val enums = Source.enums(files[TYPES].orEmpty())
        val arrays = Choices.arrays(text)
        // Which array each Parse and Get function looks its names up in.
        val lookups = HashMap<String, String>()
        for (m in Regex("""Settings::((?:Parse|Get)\w+)\s*\([^)]*\)\s*\{""").findAll(text)) {
            val body = text.substring(m.range.last, minOf(text.length, m.range.last + 1500)).substringBefore("\n}")
            Regex("""\b(s_\w+_names)\b""").find(body)?.let { lookups[m.groupValues[1]] = it.groupValues[1] }
        }
        val out = LinkedHashMap<String, Found>()
        for (m in GET.findAll(text)) {
            val (args, _) = Source.arguments(text, m.range.last) ?: continue
            val section = args.getOrNull(0)?.let(Source::string) ?: continue
            val key = args.getOrNull(1)?.let(Source::string) ?: continue
            val default = args.getOrNull(2)?.let { default(it, constants, enums, lookups, arrays) }
            // `ParseXName(si.Get...` just before: an enum by its names.
            val before = text.substring(maxOf(0, m.range.first - 80), m.range.first)
            val parser = Regex("""(Parse\w+)\s*\(\s*$""").find(before)?.groupValues?.get(1)
            val choices = parser?.let(lookups::get)?.let { choices(it, arrays) }
            val found = when {
                choices != null -> Found(null, section, key, Kind.ENUM, default, choices = choices)
                m.groupValues[1] == "Bool" -> Found(null, section, key, Kind.BOOL, default)
                m.groupValues[1] in setOf("Int", "UInt") -> Found(null, section, key, Kind.INT, default)
                m.groupValues[1] in setOf("Float", "Double") -> Found(null, section, key, Kind.FLOAT, default)
                else -> Found(null, section, key, Kind.STRING, default)
            }
            out.putIfAbsent(found.identity, found)
        }
        return out.values.toList()
    }

    /** The names in [array], each called what the display names array beside it says. */
    private fun choices(array: String, arrays: Map<String, List<Choices.Entry>>): List<Choice>? {
        val names = arrays[array] ?: return null
        val display = arrays[array.removeSuffix("_names") + "_display_names"]?.takeIf { it.size == names.size }
        return names.mapIndexedNotNull { i, e ->
            val value = e.value ?: return@mapIndexedNotNull null
            Choice(value, display?.get(i)?.label?.takeIf { it != value }, e.hosts)
        }
    }

    /** A default as written: a plain value, a constant, or a name function given a constant. */
    private fun default(
        arg: String,
        constants: Map<String, String>,
        enums: Map<String, List<Pair<String, Long>>>,
        lookups: Map<String, String>,
        arrays: Map<String, List<Choices.Entry>>,
    ): String? {
        Source.literal(arg)?.let { return it }
        val t = arg.trim()
        if (Regex("""^(?:\w+::)*\w+$""").matches(t)) return constants[t.substringAfterLast("::")]?.let(Source::literal)
        val call = Regex("""^(?:Settings::)?(Get\w+)\s*\(\s*(?:Settings::)?(\w+)\s*\)$""").matchEntire(t) ?: return null
        val array = lookups[call.groupValues[1]]?.let(arrays::get) ?: return null
        val member = constants[call.groupValues[2]] ?: return null
        val type = member.substringBefore("::").trim()
        val value = enums[type]?.firstOrNull { it.first == member.substringAfterLast("::").trim() }?.second ?: return null
        return array.getOrNull(value.toInt())?.value
    }

}
