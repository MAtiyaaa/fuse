package io.github.matiyaaa.fuse.tune

/**
 * sm64coopdx's settings, from its own code (src/pc/configfile.c): the `options` table names each
 * line of `sm64config.txt` and what it holds, `{.name = "fullscreen", .type = CONFIG_TYPE_BOOL,
 * .boolValue = &configWindow.fullscreen}`, and `configfile_save` writes them `name value`: a switch
 * `true` or `false`, a number, text as it is. Its default is the variable's own
 * (`unsigned int configFiltering = 2;`, or a field of `ConfigWindow configWindow = { .fullscreen =
 * false, ... }`). Key and button bindings are left out (each is a list of codes the game's own
 * controls menu sets).
 */
object Sm64CoopDx {
    const val CONFIG = "src/pc/configfile.c"
    val TREE = listOf("/$CONFIG")

    private val ENTRY = Regex("""\{\s*\.name\s*=\s*"([\w.]+)"\s*,\s*\.type\s*=\s*CONFIG_TYPE_(\w+)\s*,\s*\.\w+\s*=\s*&?([\w.\[\]]+)\s*}""")

    fun parse(files: Map<String, String>): List<Found> {
        val code = Source.withoutComments(files[CONFIG] ?: return emptyList())
        val out = LinkedHashMap<String, Found>()
        for (m in ENTRY.findAll(code)) {
            val name = m.groupValues[1]
            val raw = default(code, m.groupValues[3])
            val found = when (m.groupValues[2]) {
                "BOOL" -> Found(null, "", name, Kind.BOOL, default = raw?.takeIf { it == "true" || it == "false" }, perGame = false)
                "UINT", "U64" -> Found(null, "", name, Kind.INT, default = raw?.takeIf { it.toLongOrNull() != null }, min = 0, perGame = false)
                "FLOAT" -> Found(null, "", name, Kind.FLOAT, default = raw?.takeIf { it.toDoubleOrNull() != null }, perGame = false)
                "STRING" -> Found(null, "", name, Kind.STRING, default = raw, perGame = false)
                else -> null
            } ?: continue
            out.putIfAbsent(found.identity, found)
        }
        return out.values.toList()
    }

    /** What a variable starts as: its definition's value, or its field's in its struct's. */
    private fun default(code: String, variable: String): String? {
        if ('.' in variable) {
            val (struct, field) = variable.substringBefore('.') to variable.substringAfter('.')
            val m = Regex("""\b${Regex.escape(struct)}\s*=\s*\{""").find(code) ?: return null
            val (items, _) = Source.arguments(code, m.range.last, '{', '}') ?: return null
            return items.firstNotNullOfOrNull { Regex("""^\.\s*${Regex.escape(field)}\s*=\s*(.+)$""", RegexOption.DOT_MATCHES_ALL).find(it.trim())?.groupValues?.get(1) }
                ?.trim()?.let(Source::literal)
        }
        val m = Regex("""^[\w\s]*\b${Regex.escape(variable.substringBefore('['))}\s*(?:\[[^\]]*])?\s*=\s*([^;]+);""", RegexOption.MULTILINE).find(code) ?: return null
        return Source.literal(m.groupValues[1].trim())
    }
}
