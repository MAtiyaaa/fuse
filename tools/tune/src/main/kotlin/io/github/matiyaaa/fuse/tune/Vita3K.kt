package io.github.matiyaaa.fuse.tune

/**
 * The settings of Vita3K (the PlayStation Vita), from its own code:
 *
 * - vita3k/config/include/config/config.h: `CONFIG_INDIVIDUAL`, each setting Vita3K keeps in its
 *   `config.yml` as `code(bool, "v-sync", true, v_sync)`: its type, its name in the file, its
 *   default and its member. Key bindings (`PhysicalKeyCode`) and lists are left out.
 * - vita3k/config/src/settings.cpp, `load_custom_config`: those a game's own
 *   `config/config_<title id>.xml` holds, each read as `out.v_sync = gpu.attribute("v-sync")`
 *   from an element of `<config>` (`const auto gpu = config_child.child("gpu")`), by a name that
 *   is sometimes its own (`enable-ngs` for `ngs-enable`).
 *
 * Vita3K reads a game's element whole: once a game's file has `<gpu>`, every GPU setting comes from
 * it, so Fuse makes a game's file as Vita3K's own dialog does, with every one of them in it.
 */
object Vita3K {
    const val CONFIG = "vita3k/config/include/config/config.h"
    const val CUSTOM = "vita3k/config/src/settings.cpp"
    val FILES = listOf(CONFIG, CUSTOM)

    private val CODE = Regex("""\bcode\s*\(""")
    private val ELEMENT = Regex("""\bconst\s+auto\s+(\w+)\s*=\s*config_child\s*\.\s*child\s*\(\s*"([\w-]+)"\s*\)""")
    private val READ = Regex("""\bout\s*\.\s*(\w+)\s*=\s*(?:(\w+)|config_child\s*\.\s*child\s*\(\s*"([\w-]+)"\s*\))\s*\.\s*attribute\s*\(\s*"([\w-]+)"\s*\)""")
    private val INTEGERS = setOf("int", "uint32_t", "uint64_t", "int32_t", "int64_t", "unsigned")

    fun parse(files: Map<String, String>): List<Found> {
        val config = Source.withoutComments(files[CONFIG] ?: return emptyList())
        val start = config.indexOf("#define CONFIG_INDIVIDUAL").takeIf { it >= 0 } ?: return emptyList()
        val end = Regex("""\n\s*\n""").find(config, start)?.range?.first ?: config.length
        val list = config.substring(start, end)
        // A game's: as a build for computers reads them (`#ifdef __ANDROID__` parts left out).
        val custom = Source.select(Source.withoutComments(files[CUSTOM].orEmpty())) { false }
        val body = custom.substringAfter("bool load_custom_config(", "").substringBefore("bool save_custom_config(")
        val elements = ELEMENT.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        val forGame = READ.findAll(body).mapNotNull { m ->
            val element = m.groupValues[3].ifEmpty { elements[m.groupValues[2]] ?: return@mapNotNull null }
            m.groupValues[1] to (element to m.groupValues[4])
        }.toMap()
        val out = LinkedHashMap<String, Found>()
        for (c in CODE.findAll(list)) {
            val (args, _) = Source.arguments(list, c.range.last) ?: continue
            if (args.size != 4) continue
            val type = args[0].trim()
            val key = Source.string(args[1]) ?: continue
            val raw = args[2].trim()
            val member = args[3].trim()
            val game = forGame[member]
            val kind = when {
                type == "bool" -> Kind.BOOL
                type in INTEGERS -> Kind.INT
                type == "float" || type == "double" -> Kind.FLOAT
                type == "std::string" -> Kind.STRING
                else -> continue
            }
            val default = when (kind) {
                Kind.BOOL -> raw.takeIf { it == "true" || it == "false" }
                Kind.INT -> Source.literal(raw)?.takeIf { it.toLongOrNull() != null } ?: if (raw == "false") "0" else null
                Kind.FLOAT -> Source.literal(raw)?.let { if (it.startsWith(".")) "0$it" else it }
                else -> if (raw == "std::string{}") "" else Source.string(raw)
            }
            val found = Found(
                null, "", key, kind, default = default, perGame = game != null,
                gameSection = game?.first, gameKey = game?.second?.takeIf { it != key },
                tab = game?.first?.let { if (it.length <= 3) it.uppercase() else it.replaceFirstChar { c -> c.uppercase() } } ?: "Emulator",
            )
            out.putIfAbsent(found.identity, found)
        }
        return out.values.toList()
    }
}
