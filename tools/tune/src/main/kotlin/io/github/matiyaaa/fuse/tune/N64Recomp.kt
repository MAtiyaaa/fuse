package io.github.matiyaaa.fuse.tune

/**
 * The N64 recompilations' settings (Fuse Ports ports built on N64ModernRuntime), from their own
 * code. Each tab of their settings is a JSON file of its own in the app's folder (`general.json`,
 * `graphics.json`, `sound.json`), one flat object of keys, and there are two ways a game declares
 * them:
 *
 * - [Frontend]: games built on RecompFrontend (Banjo, Donkey Kong 64 and the others since) add each
 *   option to a tab's `recomp::config::Config` (librecomp/include/librecomp/config.hpp):
 *   `config.add_enum_option(id, name, description, options, default, hidden)` with options
 *   `{ Enum::Member, "Key", "Label" }` (written as its key, `Config::get_storage_json`; or as its
 *   number where the tab serializes it so, `on_json_serialize_option`), `add_number_option(id, name,
 *   description, min, max, step, precision, percent, default, hidden)` (a whole number when its
 *   precision is 0), `add_percent_number_option(id, name, description, default)` (0 to 100),
 *   `add_bool_option` and `add_string_option`. A tab's file is `<its id>.json`
 *   (`create_config_tab(name, id, ...)`); the game adds its own options to RecompFrontend's tabs
 *   (`add_general_options(create_general_tab(...))`). Options it hides (`hidden`) aren't shown by
 *   the game and are left out; so are those of General that the game turns off
 *   (`GeneralTabOptions`).
 * - [Classic]: games from Zelda64Recomp's template (Zelda, Dinosaur Planet, Mega Man 64 and
 *   others) write each file in `save_<tab>_config` (src/game/config.cpp): `config_json["key"] =
 *   getter()`, read back with `from_or_default(config_json, "key", default)`; graphics through
 *   ultramodern's `to_json(json&, const GraphicsConfig&)` (`{"res_option", config.res_option}`)
 *   with its defaults in `from_json`. An enum is written as the word `NLOHMANN_JSON_SERIALIZE_ENUM`
 *   gives its member. Controls (bindings, not settings) are left out.
 */
object N64Recomp {
    /** The game's own code and the shared code it builds with, as read from a checkout. */
    val CLASSIC_TREE = listOf("/src/**/*.cpp", "/src/**/*.h", "/src/**/*.hpp", "/include/**")
    val CLASSIC_SUBMODULES = mapOf("lib/N64ModernRuntime" to listOf("/ultramodern/include/**", "/librecomp/include/**"))
    val FRONTEND_TREE = CLASSIC_TREE
    val FRONTEND_SUBMODULES = mapOf("lib/RecompFrontend" to listOf("/recompui/src/config/**", "/recompui/include/**"))

    object Frontend {
        private val ADD = Regex("""\b(\w+)\s*\.\s*add_(enum|bool|number|percent_number|string)_option\s*\(""")
        private val VECTOR = Regex("""EnumOptionVector\s+(\w+)\s*=\s*\{""")
        private val CONFIG_FUNCTION = Regex("""(\w+)\s*\(\s*(?:const\s+)?recomp::config::Config\s*&\s*(\w+)\s*\)\s*\{""")
        private val TAB_FUNCTION = Regex("""recomp::config::Config\s*&\s*(create_\w+_tab)\s*\([^)]*\)\s*\{""")
        private val MADE = Regex("""(?:auto|recomp::config::Config)\s*&\s*(\w+)\s*=\s*(?:recompui::)?(?:config::)?(create_\w+_tab|get_\w+_config)\s*\(""")
        private val TAB_ID = Regex("""create_config_tab\s*\(""")

        fun parse(files: Map<String, String>): List<Found> {
            val code = files.filterKeys { Regex("""\.(cpp|h|hpp)$""").containsMatchIn(it) }.mapValues { Source.withoutComments(it.value) }
            val all = code.values.joinToString("\n")
            val names = stringConstants(all)
            val enums = Source.enums(all)
            val vectors = vectors(all)
            // The flags the game gives General's own options (has_rumble_strength), over their defaults.
            val flags = HashMap<String, Boolean>()
            Regex("""struct\s+GeneralTabOptions\s*\{([^}]*)\}""").find(all)?.groupValues?.get(1)?.let { body ->
                Regex("""bool\s+(\w+)\s*=\s*(true|false)""").findAll(body).forEach { flags[it.groupValues[1]] = it.groupValues[2] == "true" }
            }
            Regex("""\w+\s*\.\s*(has_\w+)\s*=\s*(true|false)\s*;""").findAll(all).forEach { flags[it.groupValues[1]] = it.groupValues[2] == "true" }

            // Each tab's id, by the function that makes it, and by the functions the game adds its options with.
            val tabOf = HashMap<String, String>()
            for (m in TAB_FUNCTION.findAll(all)) {
                val body = block(all, m.range.last) ?: continue
                val call = TAB_ID.find(body) ?: continue
                val (args, _) = Source.arguments(body, call.range.last) ?: continue
                args.getOrNull(1)?.let { resolve(it, names) }?.let { tabOf[m.groupValues[1]] = it }
            }
            val out = LinkedHashMap<String, Found>()
            fun read(body: String, file: String) {
                for (a in ADD.findAll(body)) {
                    val (args, _) = Source.arguments(body, a.range.last) ?: continue
                    option(a.groupValues[2], args, file, names, vectors, enums, body)?.let { out.putIfAbsent(it.identity, it) }
                }
            }
            // RecompFrontend's own tabs, with General's options the game turned off left out.
            for (m in TAB_FUNCTION.findAll(all)) {
                val id = tabOf[m.groupValues[1]] ?: continue
                var body = block(all, m.range.last) ?: continue
                for (c in Regex("""if\s*\(\s*options\s*\.\s*(\w+)\s*\)\s*\{""").findAll(body).toList().reversed()) {
                    if (flags[c.groupValues[1]] != false) continue
                    val end = Source.arguments(body, c.range.last, '{', '}')?.second ?: continue
                    body = body.substring(0, c.range.first) + " ".repeat(end + 1 - c.range.first) + body.substring(end + 1)
                }
                read(body, "$id.json")
            }
            // The game's own options: the functions it hands a tab to.
            val variables = MADE.findAll(all).associate { it.groupValues[1] to it.groupValues[2] }
            for (m in CONFIG_FUNCTION.findAll(all)) {
                val function = m.groupValues[1]
                if (function.startsWith("create_")) continue
                val body = block(all, m.range.last) ?: continue
                val id = Regex("""\b$function\s*\(\s*(\w+)\s*\)""").findAll(all).firstNotNullOfOrNull { call ->
                    val maker = variables[call.groupValues[1]] ?: return@firstNotNullOfOrNull null
                    tabOf[maker.replace(Regex("^get_(\\w+)_config$"), "create_$1_tab")]
                } ?: continue
                read(body, "$id.json")
            }
            return out.values.toList()
        }

        private fun option(
            type: String,
            args: List<String>,
            file: String,
            names: Map<String, String>,
            vectors: Map<String, List<Triple<String, String, String?>>>,
            enums: Map<String, List<Pair<String, Long>>>,
            body: String,
        ): Found? {
            val key = args.getOrNull(0)?.let { resolve(it, names) } ?: return null
            val label = args.getOrNull(1)?.let(::text)?.takeIf { it.isNotBlank() }
            val about = args.getOrNull(2)?.let(::text)?.takeIf { it.isNotBlank() }
            fun hidden(at: Int) = args.getOrNull(at)?.trim() == "true"
            val tab = words(file.removeSuffix(".json"))
            return when (type) {
                "enum" -> {
                    if (hidden(5)) return null
                    val list = vectors[args.getOrNull(3)?.trim()] ?: return null
                    // Written as its number where the tab serializes it so (Downsampling Quality).
                    val numbered = Regex("""on_json_serialize_option\s*\(\s*${Regex.escape(args[0].trim())}\s*,""").containsMatchIn(body)
                    fun valueOf(member: String): String? = if (!numbered) list.firstOrNull { same(it.first, member) }?.second else {
                        val (e, m) = member.split("::").takeLast(2).let { it.getOrNull(0) to it.getOrNull(1) }
                        enums[e]?.firstOrNull { it.first == m }?.second?.toString()
                    }
                    val choices = list.mapNotNull { (member, k, l) -> valueOf(member)?.let { Choice(it, l?.takeIf { it != k } ?: if (numbered) k else null) } }
                    if (choices.isEmpty()) return null
                    Found(file, "", key, Kind.ENUM, default = args.getOrNull(4)?.trim()?.let(::valueOf), choices = choices, perGame = false, label = label, about = about, tab = tab)
                }
                "bool" -> {
                    if (hidden(4)) return null
                    Found(file, "", key, Kind.BOOL, default = args.getOrNull(3)?.let(Source::literal) ?: "false", perGame = false, label = label, about = about, tab = tab)
                }
                "string" -> {
                    if (hidden(4)) return null
                    Found(file, "", key, Kind.STRING, default = args.getOrNull(3)?.let(Source::string), perGame = false, label = label, about = about, tab = tab)
                }
                "percent_number" -> {
                    if (hidden(4)) return null
                    Found(file, "", key, Kind.INT, default = args.getOrNull(3)?.let(::whole), min = 0, max = 100, perGame = false, label = label, about = about, tab = tab)
                }
                else -> {
                    if (hidden(9)) return null
                    val precision = args.getOrNull(6)?.let(Source::literal)?.toIntOrNull() ?: 0
                    val min = args.getOrNull(3)?.let(Source::literal)?.toDoubleOrNull() ?: 0.0
                    val max = args.getOrNull(4)?.let(Source::literal)?.toDoubleOrNull() ?: 0.0
                    val raw = args.getOrNull(8)?.let(Source::literal) ?: if (args.size <= 8) "0" else null
                    if (precision == 0) {
                        Found(file, "", key, Kind.INT, default = raw?.let(::whole), min = min.toLong(), max = max.toLong().takeIf { max > min }, perGame = false, label = label, about = about, tab = tab)
                    } else {
                        Found(file, "", key, Kind.FLOAT, default = raw, perGame = false, label = label, about = about, tab = tab)
                    }
                }
            }
        }

        /** `static EnumOptionVector x = { {Enum::Member, "Key", "Label"}, ... }`: each member, its key and its label. */
        private fun vectors(text: String): Map<String, List<Triple<String, String, String?>>> {
            val out = HashMap<String, List<Triple<String, String, String?>>>()
            for (m in VECTOR.findAll(text)) {
                val open = m.range.last
                val end = Source.arguments(text, open, '{', '}')?.second ?: continue
                // Preprocessor lines (a choice only some systems' builds have) don't split the list.
                val body = "{" + text.substring(open + 1, end).lines().filterNot { it.trim().startsWith("#") }.joinToString("\n") + "}"
                val items = Source.arguments(body, 0, '{', '}')?.first.orEmpty().mapNotNull { item ->
                    val parts = Source.arguments(item.trim(), 0, '{', '}')?.first ?: return@mapNotNull null
                    val key = parts.getOrNull(1)?.let(Source::string) ?: return@mapNotNull null
                    Triple(parts[0].trim(), key, parts.getOrNull(2)?.let(Source::string))
                }
                out.putIfAbsent(m.groupValues[1], items)
            }
            return out
        }
    }

    object Classic {
        private val FILENAME = Regex("""(\w+)_filename\s*=\s*u8?"([^"]+)"""")
        private val SAVE = Regex("""bool\s+save_(\w+)_config\s*\([^)]*\)\s*\{""")
        private val ASSIGN = Regex("""config_json\s*\[\s*"(\w+)"\s*]\s*=\s*([^;]+);""")
        private val TO_JSON = Regex("""to_json\s*\(\s*config_json\s*\[\s*"(\w+)"\s*]\s*,\s*([^;]+)\)\s*;""")
        private val GRAPHICS_TO = Regex("""void\s+\w*to_json\s*\(\s*(?:nlohmann::)?json\s*&\s*\w+\s*,\s*const\s+[\w:]*GraphicsConfig\s*&\s*\w+\s*\)\s*\{""")
        private val GRAPHICS_FROM = Regex("""void\s+\w*from_json\s*\(\s*const\s+(?:nlohmann::)?json\s*&\s*\w+\s*,\s*[\w:]*GraphicsConfig\s*&\s*\w+\s*\)\s*\{""")

        fun parse(files: Map<String, String>): List<Found> {
            val code = files.filterKeys { Regex("""\.(cpp|h|hpp)$""").containsMatchIn(it) }.mapValues { Source.withoutComments(it.value) }
            val all = code.values.joinToString("\n")
            val words = serializedEnums(all)
            val constants = Regex("""constexpr\s+(?:auto|int|bool|float|double|[\w:]+)\s+(\w+)\s*=\s*([^;]+);""").findAll(all).associate { it.groupValues[1] to it.groupValues[2].trim() }
            val fileNames = FILENAME.findAll(all).associate { it.groupValues[1] to it.groupValues[2] }
            val defaults = HashMap<String, String>()
            for (m in Regex("""from_or_default\s*\(""").findAll(all)) {
                val (args, _) = Source.arguments(all, m.range.last) ?: continue
                val key = args.getOrNull(1)?.let(Source::string) ?: continue
                args.getOrNull(2)?.let { defaults.putIfAbsent(key, it.trim()) }
            }
            // The types of GraphicsConfig's fields, for a default worked out when it runs (wm_default()).
            val fieldTypes = Regex("""(?:struct|class)\s+GraphicsConfig\s*\{([^}]*)\}""").find(all)?.groupValues?.get(1)?.let { body ->
                Regex("""([\w:]+)\s+(\w+)\s*;""").findAll(body).associate { it.groupValues[2] to it.groupValues[1] }
            }.orEmpty()

            fun constant(expr: String): String = constants[expr.trim()]?.let(::constant) ?: expr.trim()
            fun build(file: String, key: String, raw: String?, type: String?): Found? {
                val tab = words(file.removeSuffix(".json"))
                val expr = raw?.let(::constant)
                val enumName = (expr?.takeIf { "::" in it && Source.literal(it) == null }?.split("::")?.takeLast(2)?.first() ?: type?.substringAfterLast("::"))
                    ?.takeIf { it in words }
                if (enumName != null) {
                    val list = words.getValue(enumName)
                    val member = expr?.substringAfterLast("::")
                    return Found(file, "", key, Kind.ENUM, default = list.firstOrNull { it.first == member }?.second, choices = list.map { Choice(it.second) }, perGame = false, tab = tab)
                }
                val literal = expr?.let(Source::literal)
                val t = type?.substringAfterLast("::")
                return when {
                    literal == "true" || literal == "false" || t == "bool" -> Found(file, "", key, Kind.BOOL, default = literal?.takeIf { it == "true" || it == "false" }, perGame = false, tab = tab)
                    literal?.toLongOrNull() != null || t in INTS -> Found(file, "", key, Kind.INT, default = literal?.takeIf { it.toLongOrNull() != null }, perGame = false, tab = tab)
                    literal?.toDoubleOrNull() != null || t == "float" || t == "double" -> Found(file, "", key, Kind.FLOAT, default = literal?.takeIf { it.toDoubleOrNull() != null }, perGame = false, tab = tab)
                    t == "string" -> Found(file, "", key, Kind.STRING, default = literal, perGame = false, tab = tab)
                    else -> null
                }
            }
            /** What a getter (`zelda64::get_main_volume()`) returns, from where it is declared. */
            fun returned(expr: String): String? {
                val name = Regex("""(\w+)\s*\(\s*\)\s*$""").find(expr.trim())?.groupValues?.get(1) ?: return null
                return Regex("""([\w:]+)\s+(?:[\w:]+::)?$name\s*\(\s*\)\s*[;{]""").findAll(all)
                    .map { it.groupValues[1] }.firstOrNull { it != "return" && it != "else" }
            }

            val out = LinkedHashMap<String, Found>()
            for (m in SAVE.findAll(all)) {
                val tab = m.groupValues[1]
                val file = fileNames[tab] ?: continue
                val body = block(all, m.range.last) ?: continue
                if ("add_input_bindings" in body) continue
                if (Regex("""to_json\s*\(\s*config_json\s*,""").containsMatchIn(body)) {
                    // Graphics: ultramodern's GraphicsConfig, its defaults in from_json.
                    val to = GRAPHICS_TO.find(all)?.let { block(all, it.range.last) } ?: continue
                    val from = GRAPHICS_FROM.find(all)?.let { block(all, it.range.last) }.orEmpty()
                    // Its own bookkeeping (`_config_version`) isn't a setting.
                    for (p in Regex("""\{\s*"([a-zA-Z]\w*)"\s*,\s*\w+\s*\.\s*(\w+)\s*}""").findAll(to)) {
                        val key = p.groupValues[1]
                        val raw = Regex("""from_or_default\s*\(\s*\w+\s*,\s*"$key"\s*,\s*([^;]+)\)\s*;""").find(from)?.groupValues?.get(1)
                        build(file, key, raw?.takeUnless { it.trim().endsWith(")") }, fieldTypes[p.groupValues[2]])?.let { out.putIfAbsent(it.identity, it) }
                    }
                    continue
                }
                for (a in ASSIGN.findAll(body) + TO_JSON.findAll(body)) {
                    val key = a.groupValues[1]
                    val raw = defaults[key]?.takeUnless { '?' in it || it.trim().endsWith(")") }
                    build(file, key, raw, returned(a.groupValues[2]))?.let { out.putIfAbsent(it.identity, it) }
                }
            }
            return out.values.toList()
        }

        private val INTS = setOf("int", "int32_t", "uint32_t", "int64_t", "uint64_t", "long", "unsigned", "size_t", "uint8_t", "int8_t", "uint16_t", "int16_t")

        /** `NLOHMANN_JSON_SERIALIZE_ENUM(ns::Enum, { {ns::Enum::A, "A"}, ... })`: each enum's members and their words. */
        fun serializedEnums(text: String): Map<String, List<Pair<String, String>>> {
            val out = HashMap<String, List<Pair<String, String>>>()
            for (m in Regex("""NLOHMANN_JSON_SERIALIZE_ENUM\s*\(""").findAll(text)) {
                val (args, _) = Source.arguments(text, m.range.last) ?: continue
                val name = args.getOrNull(0)?.trim()?.substringAfterLast("::") ?: continue
                val list = args.drop(1).joinToString(",").trim()
                val items = Source.arguments(list, 0, '{', '}')?.first.orEmpty().mapNotNull { item ->
                    val parts = Source.arguments(item.trim(), 0, '{', '}')?.first ?: return@mapNotNull null
                    val word = parts.getOrNull(1)?.let(Source::string) ?: return@mapNotNull null
                    parts[0].trim().substringAfterLast("::") to word
                }
                if (items.isNotEmpty()) out.putIfAbsent(name, items)
            }
            return out
        }
    }

    /** The body of the block whose opening brace is at [open]. */
    private fun block(text: String, open: Int): String? = Source.arguments(text, open, '{', '}')?.let { (_, end) -> text.substring(open + 1, end) }

    /** Whether two enum members (`ultramodern::renderer::Resolution::Auto`, `Resolution::Auto`) are the same one. */
    private fun same(a: String, b: String): Boolean = a.trim().split("::").takeLast(2) == b.trim().split("::").takeLast(2)

    /** A number as a whole one (`25.0` is 25), or null. */
    private fun whole(raw: String): String? = Source.literal(raw)?.toDoubleOrNull()?.takeIf { it == Math.floor(it) }?.toLong()?.toString()

    /** A tab's name as words (`general` is General). */
    private fun words(id: String): String = id.split('_', '-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /**
     * Every `const std::string name = "value"` (and `std::string_view`) in [text], under the
     * namespaces it is declared in: `recompui::config::graphics::id` to `graphics`.
     */
    fun stringConstants(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val token = Regex("""namespace\s+([\w:]+)\s*\{|\{|}|(?:inline\s+|static\s+)?(?:const|constexpr)\s+std::string(?:_view)?\s+(\w+)\s*=\s*"((?:[^"\\]|\\.)*)"\s*;|"(?:[^"\\]|\\.)*"""")
        val stack = ArrayList<String?>()
        for (m in token.findAll(text)) {
            val t = m.value
            when {
                t.startsWith("namespace") -> stack += m.groupValues[1]
                t == "{" -> stack += null
                t == "}" -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                m.groupValues[2].isNotEmpty() -> out[(stack.filterNotNull() + m.groupValues[2]).joinToString("::")] = m.groupValues[3]
            }
        }
        return out
    }

    /** What [arg] names: a string as written, else the constant whose name it ends with. */
    fun resolve(arg: String, names: Map<String, String>): String? {
        Source.string(arg)?.let { return it }
        val parts = arg.trim().split("::").filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { !Regex("^\\w+$").matches(it) }) return null
        val matches = names.filterKeys { k -> k.split("::").takeLast(parts.size) == parts }
        return matches.values.distinct().singleOrNull()
    }

    /** The text of a string argument, adjacent strings joined (`"a" "b"`), the game's markup taken out. */
    private fun text(arg: String): String? {
        val pieces = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(arg).map { it.groupValues[1] }.toList()
        if (pieces.isEmpty() || arg.trim().replace(Regex(""""(?:[^"\\]|\\.)*""""), "").isNotBlank()) return null
        return pieces.joinToString("").replace("\\\"", "\"").replace("\\\\", "\\")
            .replace(Regex("""<br\s*/?>"""), " ").replace(Regex("""<[^>]+>"""), "").replace(Regex("""\s+"""), " ").trim()
    }
}
