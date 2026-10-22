package io.github.matiyaaa.fuse.tune

/**
 * Arx Libertatis' settings, from its own code (src/core/Config.cpp): `Config::init` reads each key
 * of `cfg.ini` with its default, `reader.getKey(Section::Video, Key::fullscreen,
 * Default::fullscreen)`, the section and key named by the `Section` and `Key` namespaces' strings and
 * the default by `Default`'s, which are grouped by type (`const bool fullscreen = true, ...`): a
 * `bool` a switch (written `true` or `false`, IniWriter's `std::boolalpha`), an `int` a number, a
 * `float` a decimal and a `std::string` text. Its file is written `key=value`. Key bindings (the
 * `key` section) are left out.
 */
object ArxLibertatis {
    const val CONFIG = "src/core/Config.cpp"
    val TREE = listOf("/$CONFIG")

    private val READ = Regex("""reader\s*\.\s*getKey\s*\(\s*Section::(\w+)\s*,\s*Key::(\w+)\s*,\s*Default::(\w+)\s*\)""")

    fun parse(files: Map<String, String>): List<Found> {
        val code = Source.withoutComments(files[CONFIG] ?: return emptyList())
        val sections = strings(namespace(code, "Section"))
        val keys = strings(namespace(code, "Key"))
        val defaults = typed(namespace(code, "Default"))
        val out = LinkedHashMap<String, Found>()
        for (m in READ.findAll(code)) {
            val section = sections[m.groupValues[1]] ?: continue
            if (section == "key") continue
            val key = keys[m.groupValues[2]] ?: continue
            val (type, raw) = defaults[m.groupValues[3]] ?: continue
            val value = raw?.let { Source.literal(it) ?: Source.string(it) }
            val found = when (type) {
                "bool" -> Found(null, section, key, Kind.BOOL, default = value?.takeIf { it == "true" || it == "false" }, perGame = false)
                "int" -> Found(null, section, key, Kind.INT, default = value?.takeIf { it.toLongOrNull() != null }, perGame = false)
                "float" -> Found(null, section, key, Kind.FLOAT, default = value?.takeIf { it.toDoubleOrNull() != null }, perGame = false)
                else -> Found(null, section, key, Kind.STRING, default = value ?: if (raw == null) "" else null, perGame = false)
            }
            out.putIfAbsent(found.identity, found)
        }
        return out.values.toList()
    }

    /** The body of `namespace name { ... }`. */
    private fun namespace(code: String, name: String): String {
        val m = Regex("""namespace\s+$name\s*\{""").find(code) ?: return ""
        return Source.arguments(code, m.range.last, '{', '}')?.let { (_, end) -> code.substring(m.range.last + 1, end) }.orEmpty()
    }

    /** `const std::string a = "x", b = "y";`: each name's string. */
    private fun strings(body: String): Map<String, String> =
        typed(body).mapNotNull { (k, v) -> v.second?.let(Source::string)?.let { k to it } }.toMap()

    /** `const <type> a = 1, b = 2;` blocks: each name's type and the value it is given (null for none). */
    private fun typed(body: String): Map<String, Pair<String, String?>> {
        val out = LinkedHashMap<String, Pair<String, String?>>()
        for (m in Regex("""const\s+(std::string|int|bool|float)\s+([^;]+);""").findAll(body)) {
            val type = if (m.groupValues[1] == "std::string") "string" else m.groupValues[1]
            val items = Source.arguments("(" + m.groupValues[2] + ")", 0)?.first.orEmpty()
            for (item in items) {
                val name = item.substringBefore('=').trim().takeIf { Regex("^\\w+$").matches(it) } ?: continue
                out.putIfAbsent(name, type to item.substringAfter('=', "").trim().ifEmpty { null })
            }
        }
        return out
    }
}
