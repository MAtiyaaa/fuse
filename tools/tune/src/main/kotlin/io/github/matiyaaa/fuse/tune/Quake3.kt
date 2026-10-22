package io.github.matiyaaa.fuse.tune

/**
 * The settings of the ports on the Quake III engine (Quake3e, OpenJK, iortcw, OpenMoHAA, Lilium
 * Voyager, Spearmint), from their own code. A console variable is made where it is first asked for,
 * with its default and flags: `Cvar_Get( "r_mode", "-2", CVAR_ARCHIVE | CVAR_LATCH )` in the
 * engine, a game module's table (`{ &cg_fov, "cg_fov", "90", CVAR_ARCHIVE }`) or its own call
 * (`gi.cvar( "g_saberAutoBlocking", "1", CVAR_ARCHIVE )`). Those flagged `CVAR_ARCHIVE` (and
 * Quake3e's `CVAR_ARCHIVE_ND`) are the ones it writes to its config file (`Cvar_WriteVariables`:
 * `seta name "value"`), so those are the settings.
 *
 * Its kind is the range the code gives it, where it does (Quake3e's `Cvar_CheckRange( r_mode,
 * "-2", "13", CV_INTEGER )`, ioquake3's `Cvar_CheckRange( var, min, max, qtrue )`: whole numbers 0
 * to 1 a switch, other whole numbers a number, else a decimal), else its default's (a whole number,
 * a decimal, or text). A description is Quake3e's own (`Cvar_SetDescription`). Each is shown under
 * the part of the engine its name's prefix says (`r_` Renderer, `s_` Sound, `cg_` Game).
 */
object Quake3 {
    private val DECLARE = Regex(""""([A-Za-z_][\w.]*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*([^,)};]*\bCVAR_ARCHIVE(?:_ND)?\b[^,)};]*)""")
    private val ASSIGN = Regex("""\b(\w+)\s*=\s*(?:\w+\s*(?:->|\.)\s*)?\w*[Cc]var\w*\s*\(\s*"([A-Za-z_][\w.]*)"""")
    private val RANGE = Regex("""Cvar_CheckRange\s*\(""")
    private val DESCRIPTION = Regex("""Cvar_SetDescription\s*\(""")
    private val TABS = listOf(
        "r_" to "Renderer", "gl_" to "Renderer", "vk_" to "Renderer", "s_" to "Sound", "snd_" to "Sound", "in_" to "Input", "j_" to "Input",
        "m_" to "Input", "cg_" to "Game", "g_" to "Game", "ui_" to "Interface", "cl_" to "Client", "con_" to "Console", "com_" to "System",
        "net_" to "Network", "sv_" to "Server",
    )

    fun parse(files: Map<String, String>): List<Found> {
        val code = files.filterKeys { Regex("""\.(c|cpp|h)$""").containsMatchIn(it) }.mapValues { Source.withoutComments(it.value) }
        // The variable each name is held in, for its range and description.
        val held = HashMap<String, String>()
        val ranges = HashMap<String, Triple<String, String, String>>()
        val descriptions = HashMap<String, String>()
        for ((_, text) in code) {
            for (m in ASSIGN.findAll(text)) held.putIfAbsent(m.groupValues[1], m.groupValues[2])
        }
        for ((_, text) in code) {
            for (m in RANGE.findAll(text)) {
                val (args, _) = Source.arguments(text, m.range.last) ?: continue
                val name = held[args.getOrNull(0)?.trim().orEmpty()] ?: continue
                if (args.size >= 4) ranges.putIfAbsent(name, Triple(args[1].trim(), args[2].trim(), args[3].trim()))
            }
            for (m in DESCRIPTION.findAll(text)) {
                val (args, _) = Source.arguments(text, m.range.last) ?: continue
                val name = held[args.getOrNull(0)?.trim().orEmpty()] ?: continue
                args.getOrNull(1)?.let(::text)?.let { descriptions.putIfAbsent(name, it) }
            }
        }
        val out = LinkedHashMap<String, Found>()
        for ((_, text) in code) {
            for (m in DECLARE.findAll(text)) {
                val name = m.groupValues[1]
                if ('.' in name) continue
                val default = m.groupValues[2]
                val flags = m.groupValues[3]
                if ("CVAR_CHEAT" in flags || "CVAR_ROM" in flags) continue
                out.putIfAbsent(name.lowercase(), found(name, default, ranges[name], descriptions[name]))
            }
        }
        return out.values.toList()
    }

    private fun found(name: String, default: String, range: Triple<String, String, String>?, about: String?): Found {
        val tab = TABS.firstOrNull { name.lowercase().startsWith(it.first) }?.second ?: "Other"
        if (range != null) {
            val min = number(range.first)
            val max = number(range.second)
            val kind = range.third
            val whole = "CV_INTEGER" in kind || kind == "qtrue" || kind == "1"
            if (whole && min == 0.0 && max == 1.0) {
                return Found(null, "", name, Kind.BOOL, default = if (default.trim() == "0" || default.isBlank()) "0" else "1", perGame = false, about = about, tab = tab)
            }
            if (whole) {
                return Found(null, "", name, Kind.INT, default = default.takeIf { it.toLongOrNull() != null }, min = min?.toLong(), max = max?.toLong(), perGame = false, about = about, tab = tab)
            }
            if ("CV_FLOAT" in kind || kind == "qfalse" || kind == "0") {
                return Found(null, "", name, Kind.FLOAT, default = default.takeIf { it.toDoubleOrNull() != null }, perGame = false, about = about, tab = tab)
            }
        }
        return when {
            default.toLongOrNull() != null -> Found(null, "", name, Kind.INT, default = default, perGame = false, about = about, tab = tab)
            default.toDoubleOrNull() != null -> Found(null, "", name, Kind.FLOAT, default = default, perGame = false, about = about, tab = tab)
            else -> Found(null, "", name, Kind.STRING, default = default, perGame = false, about = about, tab = tab)
        }
    }

    /** A bound as written: a number, or a string of one (Quake3e's `"-2"`); null for none (`NULL`). */
    private fun number(raw: String): Double? = (Source.string(raw) ?: Source.literal(raw))?.toDoubleOrNull()

    private fun text(arg: String): String? {
        val pieces = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(arg).map { it.groupValues[1] }.toList()
        if (pieces.isEmpty() || arg.trim().replace(Regex(""""(?:[^"\\]|\\.)*""""), "").isNotBlank()) return null
        return pieces.joinToString("").replace("\\n", " ").replace("\\t", " ").replace("\\\"", "\"").replace(Regex("""\s+"""), " ").trim().takeIf { it.isNotEmpty() }
    }
}
