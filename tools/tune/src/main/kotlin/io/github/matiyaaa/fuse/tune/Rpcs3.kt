package io.github.matiyaaa.fuse.tune

/**
 * RPCS3's settings, from its own code. rpcs3/Emu/system_config.h declares them as a tree: each
 * `struct node_x : cfg::node` names its part of config.yml in its constructor
 * (`cfg::node(_this, "Video")`), and holds entries such as `cfg::_bool name{ this, "Key", default }`,
 * `cfg::_int<min, max>`, `cfg::uint<min, max>`, `cfg::string` and `cfg::_enum<type>`. An enum is
 * written as the words rpcs3/Emu/system_config_types.cpp gives each member
 * (`case ppu_decoder_type::llvm: return "Recompiler (LLVM)";`). The section is the entry's path in
 * config.yml, its parts joined by [Found.PATH] (a part can hold a `/`: `Input/Output`). A game's
 * own configuration (`custom_configs/config_<serial>.yml`) can hold any of them. Entries that are
 * lists or maps (`set_entry`, `node_map_entry`, `log_entry`) aren't single values, so they are left
 * out.
 */
object Rpcs3 {
    const val CONFIG = "rpcs3/Emu/system_config.h"
    const val TYPES = "rpcs3/Emu/system_config_types.cpp"
    val FILES = listOf(CONFIG, TYPES)

    private val ENTRY = Regex("""^\s*(?:cfg::)?(_bool|_int\s*<\s*(-?\d+)\s*,\s*(-?\d+)\s*>|uint\s*<\s*(\d+)\s*,\s*(\d+)\s*>|uint64|uint32|_float\s*<[^>]*>|string|_enum\s*<\s*([\w:]+)\s*>|\w+_setting)\s+\w+\s*\{\s*this\s*,\s*"([^"]+)"(?:\s*,\s*([^,}]+))?""")

    fun parse(files: Map<String, String>): List<Found> {
        val text = Source.withoutComments(files[CONFIG] ?: return emptyList())
        val words = enumWords(Source.withoutComments(files[TYPES].orEmpty()))
        // A struct derived from an enum entry (`struct fifo_setting : public cfg::_enum<rsx_fifo_mode>`).
        val derived = HashMap<String, String>()
        Regex("""struct\s+(\w+)\s*:\s*(?:public\s+)?cfg::_enum<\s*([\w:]+)\s*>""").findAll(text).forEach { derived[it.groupValues[1]] = it.groupValues[2] }

        val out = LinkedHashMap<String, Found>()
        // The path of nodes the current line is in, by the brace depth each was opened at.
        val path = ArrayList<Pair<Int, String>>()
        var depth = 0
        var pendingNode = false
        for (line in text.lines()) {
            if (Regex("""struct\s+\w+\s*:\s*cfg::node\b""").containsMatchIn(line)) pendingNode = true
            Regex("""cfg::node\s*\(\s*_this\s*,\s*"([^"]+)"""").find(line)?.let { m ->
                if (pendingNode) {
                    path += (depth - 1) to m.groupValues[1]
                    pendingNode = false
                }
            }
            ENTRY.find(line)?.let { m ->
                val section = path.joinToString(Found.PATH) { it.second }
                if (section.isEmpty()) return@let
                val key = m.groupValues[7]
                val default = m.groupValues[8].trim()
                val type = m.groupValues[1].replace(" ", "")
                val found = when {
                    type == "_bool" -> Found(null, section, key, Kind.BOOL, Source.literal(default) ?: "false")
                    type.startsWith("_int") -> Found(null, section, key, Kind.INT, Source.literal(default), m.groupValues[2].toLongOrNull(), m.groupValues[3].toLongOrNull())
                    type == "uint64" || type == "uint32" -> Found(null, section, key, Kind.INT, Source.literal(default) ?: "0", 0)
                    type.startsWith("uint") -> Found(null, section, key, Kind.INT, Source.literal(default), m.groupValues[4].toLongOrNull(), m.groupValues[5].toLongOrNull())
                    type.startsWith("_float") -> Found(null, section, key, Kind.FLOAT, Source.literal(default))
                    type == "string" -> Found(null, section, key, Kind.STRING, Source.string(default) ?: "")
                    else -> {
                        val enumType = (m.groupValues[6].takeIf { it.isNotEmpty() } ?: derived[type])?.substringAfterLast("::")
                        val members = enumType?.let(words::get)
                        if (members == null) {
                            Found(null, section, key, Kind.STRING)
                        } else {
                            val chosen = default.substringAfterLast("::").trim()
                            Found(null, section, key, Kind.ENUM, members.firstOrNull { it.first == chosen }?.second, choices = members.map { Choice(it.second) })
                        }
                    }
                }
                out.putIfAbsent(found.identity, found)
            }
            for (c in line) {
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    while (path.isNotEmpty() && depth <= path.last().first) path.removeAt(path.size - 1)
                }
            }
        }
        return out.values.toList()
    }

    /** Each enum's members with the words RPCS3 writes for them, in the order its `format` lists them. */
    fun enumWords(types: String): Map<String, List<Pair<String, String>>> {
        val out = HashMap<String, List<Pair<String, String>>>()
        for (m in Regex("""fmt_class_string<\s*(\w+)\s*>::format""").findAll(types)) {
            val body = types.substring(m.range.last).substringBefore("\n}\n")
            out[m.groupValues[1]] = Regex("""case\s+[\w:]*::(\w+)\s*:\s*return\s*"([^"]*)"""").findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }
        return out
    }
}
