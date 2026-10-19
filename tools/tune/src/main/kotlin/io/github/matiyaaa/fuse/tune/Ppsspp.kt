package io.github.matiyaaa.fuse.tune

/**
 * PPSSPP's settings, from its own code (Core/Config.cpp): the `static const ConfigSetting
 * xxxSettings[] = { ConfigSetting("Key", SETTING(g_Config, iField), default, CfgFlag::...), ... }`
 * tables, each under the section the `g_sectionMeta` table names for it (`{ &g_Config,
 * graphicsSettings, ARRAY_SIZE(graphicsSettings), "Graphics" }`). Only those flagged
 * `CfgFlag::PER_GAME` can be set for one game, and those flagged `CfgFlag::DONT_SAVE` are never
 * written, so they are left out. A table kept under more than one section (the display layout, for
 * landscape and portrait) is in each. A field's first letter says its kind, as PPSSPP names them:
 * `b` a switch (written `True` or `False`), `i` and `u` a number, `f` a decimal, `s` text.
 */
object Ppsspp {
    const val CONFIG = "Core/Config.cpp"
    val FILES = listOf(CONFIG)

    private val TABLE = Regex("""static\s+(?:const\s+)?ConfigSetting\s+(\w+)\s*\[\s*\]\s*=\s*\{""")
    private val META = Regex("""\{\s*&[\w.\[\]]+\s*,\s*(\w+)\s*,\s*ARRAY_SIZE\(\s*\w+\s*\)\s*,\s*"([^"]+)"""")
    private val SETTING = Regex("""\b(?:Reported)?ConfigSetting\s*\(""")

    fun parse(files: Map<String, String>): List<Found> {
        val text = Source.withoutComments(files[CONFIG] ?: return emptyList())
        val sections = LinkedHashMap<String, MutableList<String>>()
        for (m in META.findAll(text)) sections.getOrPut(m.groupValues[1]) { mutableListOf() } += m.groupValues[2]
        val out = LinkedHashMap<String, Found>()
        for (t in TABLE.findAll(text)) {
            val under = sections[t.groupValues[1]] ?: continue
            val (entries, _) = Source.arguments(text, t.range.last, '{', '}') ?: continue
            for (entry in entries) {
                val call = SETTING.find(entry) ?: continue
                val (args, _) = Source.arguments(entry, call.range.last) ?: continue
                val key = args.getOrNull(0)?.let(Source::string) ?: continue
                val field = args.getOrNull(1)?.let { Regex("""SETTING\(\s*\w+\s*,\s*(\w+)""").find(it)?.groupValues?.get(1) } ?: continue
                val flags = args.lastOrNull().orEmpty()
                if ("DONT_SAVE" in flags) continue
                val default = args.getOrNull(2)?.let(Source::literal)
                val kind = when (field.firstOrNull()) {
                    'b' -> Kind.BOOL
                    'i', 'u' -> Kind.INT
                    'f' -> Kind.FLOAT
                    's' -> Kind.STRING
                    else -> continue
                }
                for (section in under) {
                    val found = Found(
                        null, section, key, kind,
                        default = if (kind == Kind.BOOL) default?.let { if (it == "true") "True" else "False" } else default,
                        perGame = "PER_GAME" in flags,
                    )
                    out.putIfAbsent(found.identity, found)
                }
            }
        }
        return out.values.toList()
    }
}
