package io.github.matiyaaa.fuse.tune

/**
 * The settings of shadPS4 (the PlayStation 4), from its own code:
 *
 * - src/core/emulator_settings.h: each group of settings (`struct GPUSettings`), each setting with
 *   its type and default (`Setting<u32> readbacks_mode{GpuReadbacksMode::Disabled};`, an int set to
 *   an enum's member taking that enum's members as its choices, written as their numbers), the
 *   ones a group writes (`NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE(GPUSettings, ...)`, by their own names),
 *   and the ones a game's own file can hold (`GetOverrideableFields`: `make_override<GPUSettings>(
 *   "readbacks_mode", &GPUSettings::readbacks_mode)`). Read as a Linux build compiles it.
 * - src/core/emulator_settings.cpp, `EmulatorSettingsImpl::Save`: the name each group is kept under
 *   in `config.json` and a game's `custom_configs/<serial>.json` (`j["GPU"] = m_gpu;`, with
 *   `GPUSettings m_gpu` in the header).
 *
 * Lists and paths (the game folders) are left out: they are this computer's own.
 */
object ShadPs4 {
    const val SETTINGS = "src/core/emulator_settings.h"
    const val SAVE = "src/core/emulator_settings.cpp"
    val FILES = listOf(SETTINGS, SAVE)

    private val GROUP = Regex("""\bstruct\s+(\w+Settings)\s*\{""")
    private val SETTING = Regex("""\bSetting\s*<\s*([\w:<>]+?)\s*>\s+(\w+)\s*(?:\{([^;]*)\})?\s*;""")
    private val OVERRIDE = Regex("""make_override\s*<\s*(\w+)\s*>\s*\(\s*"(\w+)"""")
    private val SAVED = Regex("""NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE\s*\(\s*(\w+)\s*,([^)]*)\)""")
    private val MEMBER = Regex("""\b(\w+Settings)\s+m_(\w+)\s*(?:\{\s*\})?\s*;""")
    private val NAMED = Regex("""\bj\s*\[\s*"(\w+)"\s*\]\s*=\s*m_(\w+)\s*;""")
    private val INTEGERS = setOf("int", "u32", "s32", "u64", "s64", "u16", "s16", "u8", "s8", "unsigned long long", "long long", "unsigned")
    private val DECIMALS = setOf("double", "float", "f32", "f64")

    fun parse(files: Map<String, String>): List<Found> {
        // As a Linux build compiles it: `#ifdef _WIN32` parts left out.
        val header = Source.select(Source.withoutComments(files[SETTINGS] ?: return emptyList())) { name -> name == "__linux__" || name == "__unix__" }
        val save = Source.withoutComments(files[SAVE].orEmpty())
        val enums = Source.enums(header)
        // The name in the file each group is kept under, by the struct: `GPUSettings m_gpu;` and `j["GPU"] = m_gpu;`.
        val fields = MEMBER.findAll(header).associate { it.groupValues[2] to it.groupValues[1] }
        val names = NAMED.findAll(save).mapNotNull { m -> fields[m.groupValues[2]]?.let { it to m.groupValues[1] } }.toMap()
        val saved = SAVED.findAll(header).associate { m -> m.groupValues[1] to m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
        val forGames = OVERRIDE.findAll(header).map { it.groupValues[1] to it.groupValues[2] }.toSet()
        val out = LinkedHashMap<String, Found>()
        for (g in GROUP.findAll(header)) {
            val struct = g.groupValues[1]
            val section = names[struct] ?: continue
            val body = block(header, g.range.last) ?: continue
            val written = saved[struct] ?: continue
            for (s in SETTING.findAll(body)) {
                val type = s.groupValues[1].replace(Regex("""\s+"""), " ").trim()
                val key = s.groupValues[2]
                if (key !in written) continue
                val raw = s.groupValues[3].trim()
                val perGame = (struct to key) in forGames
                val found = when {
                    type == "bool" -> Found(null, section, key, Kind.BOOL, default = raw.takeIf { it == "true" || it == "false" } ?: "false", perGame = perGame)
                    type in INTEGERS -> {
                        // An int set to an enum's member takes that enum's members.
                        val enumName = raw.substringBefore("::", "").takeIf { it.isNotEmpty() && it in enums }
                        if (enumName != null) {
                            val members = enums.getValue(enumName)
                            Found(
                                null, section, key, Kind.ENUM, default = members.firstOrNull { it.first == raw.substringAfter("::") }?.second?.toString(),
                                choices = members.map { (name, value) -> Choice(value.toString(), name) }, perGame = perGame,
                            )
                        } else {
                            Found(null, section, key, Kind.INT, default = Source.literal(raw.replace("'", "").ifEmpty { "0" })?.takeIf { it.toLongOrNull() != null }, perGame = perGame)
                        }
                    }
                    type in DECIMALS -> Found(null, section, key, Kind.FLOAT, default = Source.literal(raw.ifEmpty { "0.0" }), perGame = perGame)
                    type == "std::string" -> Found(null, section, key, Kind.STRING, default = if (raw.isEmpty()) "" else Source.string(raw), perGame = perGame)
                    else -> continue
                }
                out.putIfAbsent(found.identity, found.copy(tab = section))
            }
        }
        return out.values.toList()
    }

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
