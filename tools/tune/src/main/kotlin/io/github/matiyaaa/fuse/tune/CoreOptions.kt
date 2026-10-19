package io.github.matiyaaa.fuse.tune

/**
 * A libretro core's own options, as its `libretro_core_options.h` declares them for RetroArch
 * (libretro.h, `RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2`): the US English definitions
 * (`option_defs_us`), each `{ key, desc, desc_categorized, info, info_categorized, category_key,
 * { { value, label }, ..., { NULL, NULL } }, default_value }`, or in the first version of the
 * interface (`SET_CORE_OPTIONS`) `{ key, desc, info, { values }, default_value }`, and the categories
 * (`option_cats_us`, `{ key, desc, info }`) that are their tabs. A core that fills `option_defs_us`
 * when it starts reads the same entries from `option_defs` (libretro-fceumm). A key or text written
 * as macros and string pieces (`CORE_NAME "_region"`, Beetle PSX's `BEETLE_OPT(renderer)`, made
 * `MEDNAFEN_CORE_NAME_MODULE "_" #_o`) is read through the `#define`s of the file and of the core's
 * other files ([defines]), as the core's build has them.
 *
 * RetroArch keeps a game's own core options in `config/<core>/<game>.opt` and every game's in
 * `config/<core>/<core>.opt`, each `key = "value"` (retroarch's core_option_manager.c), so every
 * option can be a game's.
 */
object CoreOptions {
    private fun definitions(name: String) = Regex("""struct\s+retro_core_option(_v2)?_definition\s+$name\s*\[\s*\w*\s*\]\s*=\s*\{""")
    private val CATEGORIES = Regex("""struct\s+retro_core_option_v2_category\s+\w*option_cats_us\s*\[\s*\w*\s*\]\s*=\s*\{""")
    /** `#define NAME value`, `#define NAME(arg) value`, or `#define NAME` alone; each on its own line. */
    private val DEFINE = Regex("""^[ \t]*#[ \t]*define[ \t]+(\w+)(?:\(([ \t]*\w*[ \t]*)\))?(?:[ \t]+(.*?))?[ \t]*$""", RegexOption.MULTILINE)

    /** A macro: its parameter when it takes one, and what it stands for. */
    data class Define(val parameter: String?, val body: String)

    /**
     * The `#define`s in [text] that a build with [flags] defined (`HAVE_HW`, from the core's recipe)
     * sees, the first of each name kept.
     */
    fun defines(text: String, flags: Set<String> = emptySet()): Map<String, Define> {
        val built = Source.select(Source.withoutComments(text)) { name -> name in flags }
        val out = LinkedHashMap<String, Define>()
        for (d in DEFINE.findAll(built)) out.putIfAbsent(d.groupValues[1], Define(d.groupValues[2].trim().takeIf { d.groups[2] != null }, d.groupValues[3]))
        return out
    }

    /** The macros a key or text uses that [text]'s own `#define`s leave unknown, for [parse] to be given. */
    fun unknown(text: String): Set<String> {
        val own = DEFINE.findAll(Source.withoutComments(text)).map { it.groupValues[1] }.toSet()
        val keys = Regex("""\{\s*([A-Z_][A-Z0-9_]*)\s*(?:\(|"|,)""").findAll(Source.withoutComments(text)).map { it.groupValues[1] }
        return keys.filter { it != "NULL" && it !in own }.toSet()
    }

    fun parse(text: String, more: Map<String, Define> = emptyMap()): List<Found> {
        // Options a build may leave out (`#ifdef HAVE_OPENGL`) are kept: each build lists only what it has, and RetroArch shows those.
        val clean = Source.withoutComments(text).lines().joinToString("\n") { line ->
            val t = line.trim()
            if (t.startsWith("#") && !t.removePrefix("#").trim().startsWith("define")) "" else line
        }
        val defines = LinkedHashMap<String, Define>().apply {
            for (d in DEFINE.findAll(clean)) putIfAbsent(d.groupValues[1], Define(d.groupValues[2].trim().takeIf { d.groups[2] != null }, d.groupValues[3]))
            for ((k, v) in more) putIfAbsent(k, v)
        }
        fun str(arg: String?): String? = arg?.let { text(it, defines) }
        val tabs = CATEGORIES.find(clean)?.let { m -> entries(clean, m.range.last) }.orEmpty()
            .mapNotNull { e -> str(e.getOrNull(0))?.let { key -> key to str(e.getOrNull(1)) } }.toMap()
        val m = definitions("\\w*option_defs_us").find(clean) ?: definitions("option_defs").find(clean) ?: return emptyList()
        val v2 = m.groupValues[1].isNotEmpty()
        val out = LinkedHashMap<String, Found>()
        for (e in entries(clean, m.range.last)) {
            val key = str(e.getOrNull(0))?.takeIf { it.isNotBlank() } ?: continue
            val (valuesAt, defaultAt) = if (v2) 6 to 7 else 3 to 4
            val values = e.getOrNull(valuesAt)?.trim()?.takeIf { it.startsWith("{") }?.let { v ->
                entries(v, 0).mapNotNull { pair -> str(pair.getOrNull(0))?.let { value -> Choice(value, str(pair.getOrNull(1))?.takeIf { it != value }) } }
            }.orEmpty()
            if (values.isEmpty()) continue
            val default = str(e.getOrNull(defaultAt))?.takeIf { d -> values.any { it.value == d } } ?: values.first().value
            val label = str(e.getOrNull(1))
            val about = str(e.getOrNull(if (v2) 3 else 2))
            val tab = if (v2) str(e.getOrNull(5))?.let { tabs[it] ?: it } else null
            val found = Found(null, "", key, Kind.ENUM, default = default, choices = values, label = label, about = about, tab = tab)
            out.putIfAbsent(found.identity, found)
        }
        return out.values.toList()
    }

    /** The `{ ... }` entries of the initializer list whose brace is at [open], each as its arguments; up to the `{ NULL }` that ends it. */
    private fun entries(text: String, open: Int): List<List<String>> {
        val (items, _) = Source.arguments(text, open, '{', '}') ?: return emptyList()
        val out = ArrayList<List<String>>()
        for (item in items) {
            val t = item.trim()
            if (!t.startsWith("{")) continue
            val (args, _) = Source.arguments(t, 0, '{', '}') ?: continue
            if (args.firstOrNull()?.trim() == "NULL") break
            out += args
        }
        return out
    }

    /** A C string expression's text: string pieces and macros naming them, joined; null for NULL or anything else. */
    private fun text(arg: String, defines: Map<String, Define>, depth: Int = 0): String? {
        val t = arg.trim()
        if (t.isEmpty() || t == "NULL" || depth > 6) return null
        val out = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                c.isWhitespace() -> i++
                c == '"' -> {
                    var j = i + 1
                    while (j < t.length && t[j] != '"') j += if (t[j] == '\\') 2 else 1
                    out.append(Source.string(t.substring(i, (j + 1).coerceAtMost(t.length))) ?: return null)
                    i = j + 1
                }
                // A bracketed piece (`("beetle_psx_" # _o)`): its text.
                c == '(' -> {
                    var level = 0
                    var j = i
                    while (j < t.length) {
                        if (t[j] == '(') level++ else if (t[j] == ')' && --level == 0) break
                        if (t[j] == '"') { j++; while (j < t.length && t[j] != '"') j += if (t[j] == '\\') 2 else 1 }
                        j++
                    }
                    if (j >= t.length) return null
                    out.append(text(t.substring(i + 1, j), defines, depth + 1) ?: return null)
                    i = j + 1
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
                    val define = defines[t.substring(i, j)] ?: return null
                    val parameter = define.parameter
                    if (parameter == null) {
                        out.append(text(define.body, defines, depth + 1) ?: return null)
                        i = j
                    } else {
                        // `NAME(arg)`: the body with `#param` as the argument's text and `param` as the argument.
                        while (j < t.length && t[j].isWhitespace()) j++
                        if (j >= t.length || t[j] != '(') return null
                        val close = t.indexOf(')', j).takeIf { it > 0 } ?: return null
                        val argument = t.substring(j + 1, close).trim()
                        val body = if (parameter.isEmpty()) define.body else define.body
                            .replace(Regex("#\\s*${Regex.escape(parameter)}\\b"), "\"$argument\"")
                            .replace(Regex("\\b${Regex.escape(parameter)}\\b"), argument)
                        out.append(text(body, defines, depth + 1) ?: return null)
                        i = close + 1
                    }
                }
                else -> return null
            }
        }
        return out.toString()
    }
}
