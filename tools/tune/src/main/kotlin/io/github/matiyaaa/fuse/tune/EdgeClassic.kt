package io.github.matiyaaa.fuse.tune

/**
 * EDGE-Classic's settings, from its own code: each console variable is defined once,
 * `EDGE_DEFINE_CONSOLE_VARIABLE(name, "default", flags)` (con_var.h, with `_CLAMPED` adding its
 * least and most), and those flagged `kConsoleVariableFlagArchive` are the ones it writes to
 * `edge-classic.cfg` (`WriteConsoleVariables`: `/name<tab>"value"`), so those are the settings. A
 * whole number from 0 to 1 is a switch (1 or 0), another a number, a decimal a decimal, else text.
 * Each is shown under the part of the engine its source file is (`r_` Renderer, `s_` Sound).
 */
object EdgeClassic {
    val TREE = listOf("/source_files/edge/*.cc")

    private val DEFINE = Regex("""\bEDGE_DEFINE_CONSOLE_VARIABLE(_CLAMPED|_WITH_CALLBACK|_WITH_CALLBACK_CLAMPED)?\s*\(""")
    private val PARTS = listOf(
        "r_" to "Renderer", "s_" to "Sound", "i_" to "System", "m_" to "Menu", "am_" to "Automap", "hu_" to "HUD", "p_" to "Play",
        "g_" to "Game", "e_" to "Engine", "n_" to "Network", "con_" to "Console", "w_" to "Data", "v_" to "Video",
    )

    fun parse(files: Map<String, String>): List<Found> {
        val out = LinkedHashMap<String, Found>()
        for ((path, raw) in files) {
            if (!path.endsWith(".cc")) continue
            val code = Source.withoutComments(raw)
            val file = path.substringAfterLast('/')
            val tab = PARTS.firstOrNull { file.startsWith(it.first) }?.second ?: "Other"
            for (m in DEFINE.findAll(code)) {
                val (args, _) = Source.arguments(code, m.range.last) ?: continue
                val name = args.getOrNull(0)?.trim()?.takeIf { Regex("^\\w+$").matches(it) } ?: continue
                val default = args.getOrNull(1)?.let(Source::string) ?: continue
                if ("kConsoleVariableFlagArchive" !in args.getOrNull(2).orEmpty()) continue
                val clamped = m.groupValues[1].endsWith("CLAMPED")
                val bounds = if (clamped) args.takeLast(2).map { Source.literal(it)?.toDoubleOrNull() } else listOf(null, null)
                val (min, max) = bounds[0] to bounds[1]
                val found = when {
                    default.toLongOrNull() != null && min == 0.0 && max == 1.0 ->
                        Found(null, "", name, Kind.BOOL, default = if (default == "0") "0" else "1", perGame = false, tab = tab)
                    default.toLongOrNull() != null -> Found(null, "", name, Kind.INT, default = default, min = min?.toLong(), max = max?.toLong(), perGame = false, tab = tab)
                    default.toDoubleOrNull() != null -> Found(null, "", name, Kind.FLOAT, default = default, perGame = false, tab = tab)
                    else -> Found(null, "", name, Kind.STRING, default = default, perGame = false, tab = tab)
                }
                out.putIfAbsent(found.identity, found)
            }
        }
        return out.values.toList()
    }
}
