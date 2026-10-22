package io.github.matiyaaa.fuse.tune

/**
 * The settings of the ports on id Tech 4 (dhewm 3, openQ4), from their own code: every console
 * variable is declared once, `idCVar r_mode( "r_mode", "5", CVAR_ARCHIVE | CVAR_RENDERER |
 * CVAR_INTEGER, "video mode number" )`, its name, default, flags and description, then either the
 * range a number may take (`0, 1, idCmdSystem::ArgCompletion_Integer<0,1>`) or the words it takes
 * (`r_renderApiArgs`, a list of strings ending in NULL). Those flagged `CVAR_ARCHIVE` are the ones
 * the engine writes to its config file (`idCVarSystemLocal::WriteFlaggedVariables`, `seta name
 * "value"` lines), so those are the settings. `CVAR_BOOL` is a switch, written 1 or 0;
 * `CVAR_INTEGER` a number and `CVAR_FLOAT` a decimal; anything else is text. Each is shown under
 * the part of the engine its flags name (Renderer, Sound, Game). Tools' variables
 * (`CVAR_TOOL`) and cheats are left out.
 */
object IdTech4 {
    val TREE = listOf("/neo/**/*.cpp", "/neo/**/*.h", "/src/**/*.cpp", "/src/**/*.h")

    private val DECLARE = Regex("""\bidCVar\s+\w+\s*\(""")
    private val TABS = listOf("CVAR_RENDERER" to "Renderer", "CVAR_SOUND" to "Sound", "CVAR_GUI" to "Interface", "CVAR_GAME" to "Game", "CVAR_SYSTEM" to "System")

    fun parse(files: Map<String, String>): List<Found> {
        val code = files.filterKeys { it.endsWith(".cpp") || it.endsWith(".h") }.mapValues { Source.withoutComments(it.value) }
        val all = code.values.joinToString("\n")
        val out = LinkedHashMap<String, Found>()
        for ((_, text) in code) {
            for (m in DECLARE.findAll(text)) {
                val (args, _) = Source.arguments(text, m.range.last) ?: continue
                val name = args.getOrNull(0)?.let(Source::string) ?: continue
                val default = args.getOrNull(1)?.let(Source::string) ?: continue
                val flags = args.getOrNull(2).orEmpty()
                if ("CVAR_ARCHIVE" !in flags || "CVAR_TOOL" in flags || "CVAR_CHEAT" in flags) continue
                val about = args.getOrNull(3)?.let(::text)
                val tab = TABS.firstOrNull { it.first in flags }?.second ?: "Other"
                val rest = args.drop(4)
                val words = rest.firstOrNull()?.trim()?.takeIf { Regex("^\\w+$").matches(it) && Source.literal(it) == null }?.let { words(it, all) }
                val found = when {
                    "CVAR_BOOL" in flags -> Found(null, "", name, Kind.BOOL, default = if (default.trim() == "0" || default.isBlank()) "0" else "1", perGame = false, about = about, tab = tab)
                    words != null -> Found(null, "", name, Kind.ENUM, default = default, choices = words.map { Choice(it) }, perGame = false, about = about, tab = tab)
                    "CVAR_INTEGER" in flags -> Found(
                        null, "", name, Kind.INT, default = default.takeIf { it.toLongOrNull() != null },
                        min = rest.getOrNull(0)?.let(Source::literal)?.toDoubleOrNull()?.toLong(), max = rest.getOrNull(1)?.let(Source::literal)?.toDoubleOrNull()?.toLong(),
                        perGame = false, about = about, tab = tab,
                    )
                    "CVAR_FLOAT" in flags -> Found(null, "", name, Kind.FLOAT, default = default.takeIf { it.toDoubleOrNull() != null }, perGame = false, about = about, tab = tab)
                    else -> Found(null, "", name, Kind.STRING, default = default, perGame = false, about = about, tab = tab)
                }
                out.putIfAbsent(found.identity, found)
            }
        }
        return out.values.toList()
    }

    /** `static const char *name[] = { "a", "b", NULL }`: the words a variable takes. */
    private fun words(name: String, text: String): List<String>? {
        val m = Regex("""\b${Regex.escape(name)}\s*\[\s*\w*\s*]\s*=\s*\{""").find(text) ?: return null
        val items = Source.arguments(text, m.range.last, '{', '}')?.first?.map { it.trim() }?.filter { it.isNotEmpty() && it != "NULL" && it != "nullptr" } ?: return null
        return items.map { Source.string(it) ?: return null }.takeIf { it.isNotEmpty() }
    }

    /** A description: its strings joined, its line breaks as spaces. */
    private fun text(arg: String): String? {
        val pieces = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(arg).map { it.groupValues[1] }.toList()
        if (pieces.isEmpty() || arg.trim().replace(Regex(""""(?:[^"\\]|\\.)*""""), "").isNotBlank()) return null
        return pieces.joinToString("").replace("\\n", " ").replace("\\t", " ").replace("\\\"", "\"").replace(Regex("""\s+"""), " ").trim().takeIf { it.isNotEmpty() }
    }
}
