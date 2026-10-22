package io.github.matiyaaa.fuse.tune

/**
 * The settings of the Xbox 360 recompilations built on the ReXGlue SDK, from their own code and the
 * SDK's: each is declared once, `REXCVAR_DEFINE_BOOL(vsync, true, "GPU", "Enable vertical sync")`
 * (include/rex/cvar.h: its name, default, category and description), with what it may take chained
 * after it (`.range(0, 4)`, `.allowed({"fsr", "cas", "none"})`). The game's config file is flat TOML,
 * `name = value` at the top (`rex::cvar::LoadConfig` reads a table's keys joined by `_`, and
 * `SerializeToTOML` writes them so), a switch `true` or `false` and text in quotes. Each is shown
 * under its category. Those only for debugging (`.debug_only()`) are left out.
 */
object Rexglue {
    /** The game's code, and the SDK's where it is a submodule (thirdparty/rexglue-sdk). */
    val TREE = listOf("/src/**/*.cpp", "/src/**/*.h")
    val SUBMODULES = mapOf("thirdparty/rexglue-sdk" to listOf("/src/**/*.cpp", "/src/**/*.h"))

    private val DEFINE = Regex("""\bREXCVAR_DEFINE_(BOOL|INT32|INT64|UINT32|UINT64|DOUBLE|FLOAT|STRING)\s*\(""")

    fun parse(files: Map<String, String>): List<Found> {
        val code = files.filterKeys { Regex("""\.(cpp|h|hpp)$""").containsMatchIn(it) }.mapValues { Source.withoutComments(it.value) }
        val out = LinkedHashMap<String, Found>()
        for ((path, text) in code) {
            // The macro's own definition isn't a setting.
            if (path.endsWith("rex/cvar.h")) continue
            for (m in DEFINE.findAll(text)) {
                val (args, end) = Source.arguments(text, m.range.last) ?: continue
                val name = args.getOrNull(0)?.trim()?.takeIf { Regex("^\\w+$").matches(it) } ?: continue
                val raw = args.getOrNull(1)?.trim().orEmpty()
                val category = args.getOrNull(2)?.let(::text)
                val about = args.drop(3).joinToString(",").let(::text)
                val chain = text.substring(end + 1, minOf(text.length, end + 400)).substringBefore(';')
                if (".debug_only" in chain) continue
                val range = Regex("""\.range\s*\(\s*([^,]+),\s*([^)]+)\)""").find(chain)
                val allowed = Regex("""\.allowed\s*\(\s*\{([^}]*)\}""").find(chain)?.groupValues?.get(1)
                    ?.let { list -> Regex(""""((?:[^"\\]|\\.)*)"""").findAll(list).map { it.groupValues[1] }.toList() }?.takeIf { it.isNotEmpty() }
                val found = when (m.groupValues[1]) {
                    "BOOL" -> Found(null, "", name, Kind.BOOL, default = Source.literal(raw)?.takeIf { it == "true" || it == "false" }, perGame = false, about = about, tab = category)
                    "STRING" -> if (allowed != null) {
                        Found(null, "", name, Kind.ENUM, default = Source.string(raw), choices = allowed.map { Choice(it) }, perGame = false, about = about, tab = category)
                    } else {
                        Found(null, "", name, Kind.STRING, default = Source.string(raw), perGame = false, about = about, tab = category)
                    }
                    "DOUBLE", "FLOAT" -> Found(null, "", name, Kind.FLOAT, default = Source.literal(raw)?.takeIf { it.toDoubleOrNull() != null }, perGame = false, about = about, tab = category)
                    else -> Found(
                        null, "", name, Kind.INT, default = Source.literal(raw)?.takeIf { it.toLongOrNull() != null },
                        min = range?.groupValues?.get(1)?.let(Source::literal)?.toDoubleOrNull()?.toLong(),
                        max = range?.groupValues?.get(2)?.let(Source::literal)?.toDoubleOrNull()?.toLong(),
                        perGame = false, about = about, tab = category,
                    )
                }
                out.putIfAbsent(found.identity, found)
            }
        }
        return out.values.toList()
    }

    private fun text(arg: String): String? {
        val pieces = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(arg).map { it.groupValues[1] }.toList()
        if (pieces.isEmpty() || arg.trim().replace(Regex(""""(?:[^"\\]|\\.)*""""), "").isNotBlank()) return null
        return pieces.joinToString("").replace("\\n", " ").replace("\\\"", "\"").replace(Regex("""\s+"""), " ").trim().takeIf { it.isNotEmpty() }
    }
}
