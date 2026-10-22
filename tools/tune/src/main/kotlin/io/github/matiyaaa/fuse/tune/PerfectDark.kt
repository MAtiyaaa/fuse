package io.github.matiyaaa.fuse.tune

/**
 * The Perfect Dark PC port's settings (its Dab's mod build), from its own code: each is registered
 * where its part of the port starts, `configRegisterInt("Video.VSync", &vidVsync, -1, 1)`
 * (port/src/config.c: `configRegisterInt`, `UInt`, `Float` and `String`), its section the name up to
 * the last dot and its key the rest, with the range it may take. `pd.ini` is written
 * `[Section]` then `key=value` lines (`configSave`). A whole number from 0 to 1 is a switch, written
 * 1 or 0. Its default is the variable's own where the code gives one (`s32 vidVsync = 1;`). Keys
 * made as the port runs (each player's own, `Game.Player%d`) are left out.
 */
object PerfectDark {
    val TREE = listOf("/port/src/**/*.c", "/port/include/**/*.h")

    private val REGISTER = Regex("""\bconfigRegister(Int|UInt|Float|String)\s*\(""")

    fun parse(files: Map<String, String>): List<Found> {
        val code = files.filterKeys { it.endsWith(".c") || it.endsWith(".h") }.mapValues { Source.withoutComments(it.value) }
        val all = code.values.joinToString("\n")
        val out = LinkedHashMap<String, Found>()
        for ((_, text) in code) {
            for (m in REGISTER.findAll(text)) {
                val (args, _) = Source.arguments(text, m.range.last) ?: continue
                val name = args.getOrNull(0)?.let(Source::string) ?: continue
                if ('%' in name || '.' !in name) continue
                val section = name.substringBeforeLast('.')
                val key = name.substringAfterLast('.')
                val variable = args.getOrNull(1)?.trim()?.removePrefix("&")?.trim().orEmpty()
                val raw = default(all, variable)
                val min = args.getOrNull(2)?.let(Source::literal)
                val max = args.getOrNull(3)?.let(Source::literal)
                val found = when (m.groupValues[1]) {
                    "Int", "UInt" -> if (min == "0" && max == "1") {
                        Found(null, section, key, Kind.BOOL, default = raw?.takeIf { it == "0" || it == "1" }, perGame = false)
                    } else {
                        Found(null, section, key, Kind.INT, default = raw?.takeIf { it.toLongOrNull() != null }, min = min?.toDoubleOrNull()?.toLong(), max = max?.toDoubleOrNull()?.toLong(), perGame = false)
                    }
                    "Float" -> Found(null, section, key, Kind.FLOAT, default = raw?.takeIf { it.toDoubleOrNull() != null }, perGame = false)
                    else -> Found(null, section, key, Kind.STRING, default = raw, perGame = false)
                }
                out.putIfAbsent(found.identity, found)
            }
        }
        return out.values.toList()
    }

    /** What a plain variable starts as, where it is defined once with a value (`s32 vidVsync = 1;`). */
    private fun default(code: String, variable: String): String? {
        if (!Regex("^\\w+$").matches(variable)) return null
        val defs = Regex("""^[ \t]*(?:static\s+)?(?:const\s+)?[\w\s]+?\b${Regex.escape(variable)}\s*(?:\[[^\]]*])?\s*=\s*([^;,]+);""", RegexOption.MULTILINE)
            .findAll(code).map { it.groupValues[1].trim() }.toList().distinct()
        return defs.singleOrNull()?.let(Source::literal)
    }
}
