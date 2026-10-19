package io.github.matiyaaa.fuse.tune

/**
 * Fallout 2 Community Edition's settings (a Fuse Ports port), from its own code: `gameConfigInit`
 * in src/game_config.cc gives every key of `fallout2.cfg` its default before the file is read,
 * `configSetInt(&gGameConfig, GAME_CONFIG_PREFERENCES_KEY, GAME_CONFIG_GAME_DIFFICULTY_KEY, 1)`,
 * its section and key named by the `#define`s in src/game_config.h (`"preferences"`,
 * `"game_difficulty"`). `configSetInt` is a number, `configSetDouble` a decimal and
 * `configSetString` text; the engine's own switches are numbers, 0 and 1. What only the map editor
 * sets (`if (isMapper)`) is left out, as the game never reads it.
 */
object Fallout2Ce {
    const val CONFIG = "src/game_config.cc"
    const val HEADER = "src/game_config.h"
    val FILES = listOf(CONFIG, HEADER)

    private val DEFINE = Regex("""^\s*#\s*define\s+(\w+)\s+"([^"]*)"""", RegexOption.MULTILINE)
    private val CALL = Regex("""\bconfigSet(Int|Double|String|Bool)\s*\(""")

    fun parse(files: Map<String, String>): List<Found> {
        val header = Source.withoutComments(files[HEADER] ?: return emptyList())
        val names = DEFINE.findAll(header).associate { it.groupValues[1] to it.groupValues[2] }
        val code = Source.withoutComments(files[CONFIG] ?: return emptyList())
        val start = code.indexOf("gameConfigInit").takeIf { it >= 0 } ?: return emptyList()
        // Only the defaults the game itself sets, before the map editor's own.
        val end = code.indexOf("if (isMapper)", start).takeIf { it >= 0 } ?: code.length
        val body = code.substring(start, end)
        val out = LinkedHashMap<String, Found>()
        for (c in CALL.findAll(body)) {
            val (args, _) = Source.arguments(body, c.range.last) ?: continue
            if (args.getOrNull(0)?.trim() != "&gGameConfig") continue
            val section = args.getOrNull(1)?.trim()?.let { names[it] ?: Source.string(it) } ?: continue
            val key = args.getOrNull(2)?.trim()?.let { names[it] ?: Source.string(it) } ?: continue
            val raw = args.getOrNull(3)?.trim()
            val found = when (c.groupValues[1]) {
                "Int" -> Found(null, section, key, Kind.INT, default = raw?.let(Source::literal)?.takeIf { it.toLongOrNull() != null })
                "Bool" -> Found(null, section, key, Kind.INT, default = raw?.let(Source::literal)?.let { if (it == "true") "1" else if (it == "false") "0" else it })
                "Double" -> Found(null, section, key, Kind.FLOAT, default = raw?.let(Source::literal)?.takeIf { it.toDoubleOrNull() != null })
                else -> Found(null, section, key, Kind.STRING, default = raw?.let { names[it] ?: Source.string(it) })
            }
            out.putIfAbsent(found.identity, found)
        }
        return out.values.toList()
    }
}
