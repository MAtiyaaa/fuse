package io.github.matiyaaa.fuse.tune

/**
 * OpenRCT2's settings (a Fuse Ports port), from its own code (src/openrct2/config/Config.cpp): each
 * `ReadXxx` function opens its section of `config.ini` with `reader->ReadSection("general")` and
 * reads every key there with its default: `reader->GetBoolean("use_vsync", true)` a switch, written
 * `true` or `false` (`IniWriter::WriteBoolean`), `GetInt32` and `GetInt64` a number, `GetFloat` a
 * decimal, `GetString` text, and `GetEnum<T>("key", default, Enum_X)` one of the words the
 * `ConfigEnum` table `Enum_X` lists (`ConfigEnumEntry<T>("METRIC", MeasurementFormat::metric)`).
 * A default worked out when it runs (the language of the computer, a constant from elsewhere) is
 * left as none; an enum read through a table of its own making (the languages) has no words listed
 * here, so it is left out.
 */
object OpenRct2 {
    const val CONFIG = "src/openrct2/config/Config.cpp"
    val FILES = listOf(CONFIG)

    private val TABLE = Regex("""\b(Enum_\w+)\s*=\s*ConfigEnum<\s*[\w:]+\s*>\s*\(""")
    private val ENTRY = Regex("""ConfigEnumEntry<\s*[\w:]+\s*>\s*\(\s*"([^"]*)"\s*,\s*([\w:]+)\s*\)""")
    private val CALL = Regex("""reader->(ReadSection|GetBoolean|GetInt32|GetInt64|GetFloat|GetString|GetEnum)\s*(?:<[^>()]*>)?\s*\(""")

    fun parse(files: Map<String, String>): List<Found> {
        val code = Source.withoutComments(files[CONFIG] ?: return emptyList())
        // Each table's words, and which word each enum member is written as.
        val tables = HashMap<String, List<Pair<String, String>>>()
        for (t in TABLE.findAll(code)) {
            val (args, _) = Source.arguments(code, t.range.last) ?: continue
            tables[t.groupValues[1]] = ENTRY.findAll(args.joinToString(",")).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }
        val out = LinkedHashMap<String, Found>()
        var section: String? = null
        for (c in CALL.findAll(code)) {
            val (args, _) = Source.arguments(code, c.range.last) ?: continue
            if (c.groupValues[1] == "ReadSection") {
                section = args.firstOrNull()?.let(Source::string)
                continue
            }
            val within = section ?: continue
            val key = args.firstOrNull()?.let(Source::string) ?: continue
            val raw = args.getOrNull(1)?.trim()
            val found = when (c.groupValues[1]) {
                "GetBoolean" -> Found(null, within, key, Kind.BOOL, default = raw?.let(Source::literal)?.takeIf { it == "true" || it == "false" })
                "GetInt32", "GetInt64" -> Found(null, within, key, Kind.INT, default = raw?.let(Source::literal)?.takeIf { it.toLongOrNull() != null })
                "GetFloat" -> Found(null, within, key, Kind.FLOAT, default = raw?.let(Source::literal)?.takeIf { it.toDoubleOrNull() != null })
                "GetString" -> Found(null, within, key, Kind.STRING, default = raw?.let(Source::string))
                else -> {
                    val words = args.getOrNull(2)?.trim()?.let { tables[it] } ?: continue
                    val default = raw?.let { r -> words.firstOrNull { it.second == r }?.first }
                    Found(null, within, key, Kind.ENUM, default = default, choices = words.map { Choice(it.first) })
                }
            }
            out.putIfAbsent(found.identity, found)
        }
        return out.values.toList()
    }
}
