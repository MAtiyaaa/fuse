package io.github.matiyaaa.fuse.tune

/**
 * The settings of the Harbour Masters' ports and those on their libultraship (Ship of Harkinian,
 * 2 Ship 2 Harkinian, Starship, SpaghettiKart, Ghostship, Lighthouse, PaperBoat, BattleShip), from
 * their own menus. Every setting is a console variable (CVar) libultraship keeps in the port's one
 * JSON file under `CVars`, its name split at each dot into objects
 * (`ConsoleVariable::Save` in src/ship/config/ConsoleVariable.cpp: `gSettings.Menu.Theme` is
 * `{"CVars": {"gSettings": {"Menu": {"Theme": 2}}}}`). A switch is a whole number there, 1 or 0.
 *
 * The menus name each one, in either of two ways:
 * - `AddWidget(path, "Label", WIDGET_CVAR_CHECKBOX).CVar(CVAR_SETTING("Name")).Options(
 *   CheckboxOptions().Tooltip("...").DefaultValue(true))`, under the menu's section and sidebar
 *   (`WidgetPath path = { "Settings", "General", ... }`, then `path.sidebarName = "Graphics"` or
 *   `path = { "Enhancements", "Cheats", ... }`);
 *   `WIDGET_CVAR_COMBOBOX` with `.ComboMap(map)` (`{ { value, "Label" }, ... }`) and
 *   `.DefaultIndex(...)`, `WIDGET_CVAR_SLIDER_INT` and `_FLOAT` with `.Min`, `.Max` and
 *   `.DefaultValue`.
 * - `UIWidgets::CVarCheckbox("Label", "gName", { .tooltip = "...", .defaultValue = true })`,
 *   `CVarSliderInt(label, cvar, min, max, default, ...)`, `CVarSliderFloat` alike and
 *   `CVarCombobox(label, cvar, items, { .defaultIndex = 0 })` (an array of labels, each its index),
 *   under the menu they are drawn in (`BeginMenu("Graphics")`); or with the options as a chain
 *   (`UIWidgets::CheckboxOptions().DefaultValue(true)`).
 *
 * A CVar named through macros (`CVAR_SETTING(var)`, `CVAR_PREFIX_SETTING "." var`) is read through
 * the port's own `#define`s. The developer tools' menus are left out, as are controls without a
 * name of their own, colours, buttons and anything a widget holds that isn't one of these.
 */
object Libultraship {
    val TREE = listOf("/soh/soh/**/*.cpp", "/soh/soh/**/*.h", "/soh/soh/**/*.hpp", "/mm/2s2h/**/*.cpp", "/mm/2s2h/**/*.h", "/mm/2s2h/**/*.hpp", "/src/port/**", "/port/**", "/include/**/*.h", "/CMake/*.cmake", "/cmake/*.cmake")

    /** The sections left out: tools for the port's own developers (Dev Tools, Developer). */
    private val LEFT_OUT = Regex("""(?i)\bdev(?:eloper)?\b""")

    private val EVENT = Regex(
        """(?:WidgetPath\s+)?\b(\w+)\s*=\s*\{(?=\s*")|(\w+)\s*\.\s*(sectionName|sidebarName)\s*=\s*"((?:[^"\\]|\\.)*)"|\bAddWidget\s*\(|UIWidgets\s*::\s*(CVarCheckbox|CVarSliderInt|CVarSliderFloat|CVarCombobox)\s*\(|BeginMenu\s*\(\s*"((?:[^"\\]|\\.)*)"""",
    )

    fun parse(files: Map<String, String>): List<Found> {
        val code = files.filterKeys { Regex("""\.(cpp|c|h|hpp)$""").containsMatchIn(it) && "libultraship/" !in it }
            .mapValues { Source.withoutComments(it.value) }
        val all = code.values.joinToString("\n")
        // Prefixes some ports define in CMake (`set(CVAR_PREFIX_SETTING "gSettings")`), passed to the compiler.
        val macros = Macros(all + "\n" + cmakeDefines(files.filterKeys { it.endsWith(".cmake") }.values.joinToString("\n")))
        val members = members(all)
        val maps = comboMaps(all, members)
        val out = LinkedHashMap<String, Found>()
        for ((_, text) in code) {
            val paths = HashMap<String, MutableList<String>>()
            var menu: String? = null
            for (e in EVENT.findAll(text)) {
                when {
                    e.groupValues[1].isNotEmpty() -> {
                        val (args, _) = Source.arguments(text, e.range.last, '{', '}') ?: continue
                        paths[e.groupValues[1]] = args.take(2).map { Source.string(it) ?: "" }.toMutableList()
                    }
                    e.groupValues[3].isNotEmpty() -> {
                        val p = paths.getOrPut(e.groupValues[2]) { mutableListOf("", "") }
                        p[if (e.groupValues[3] == "sectionName") 0 else 1] = e.groupValues[4]
                    }
                    e.groupValues[6].isNotEmpty() -> menu = e.groupValues[6]
                    e.value.startsWith("AddWidget") -> {
                        val (args, end) = Source.arguments(text, e.range.last) ?: continue
                        val path = paths[args.getOrNull(0)?.trim().orEmpty()]
                        val tab = path?.filter { it.isNotEmpty() }?.joinToString(" / ")
                        if (path?.any { LEFT_OUT.containsMatchIn(it) } == true) continue
                        widget(args, chain(text, end + 1), tab, macros, maps, members)?.let { out.putIfAbsent(it.identity, it) }
                    }
                    else -> {
                        val (args, _) = Source.arguments(text, e.range.last) ?: continue
                        if (menu != null && LEFT_OUT.containsMatchIn(menu)) continue
                        positional(e.groupValues[5], args, menu, macros, maps, members, all)?.let { out.putIfAbsent(it.identity, it) }
                    }
                }
            }
        }
        return out.values.toList()
    }

    /** The calls chained after a call that ends at [from] (`.CVar(...).Options(...)`), as one text. */
    private fun chain(text: String, from: Int): String {
        var i = from
        val out = StringBuilder()
        while (true) {
            var j = i
            while (j < text.length && text[j].isWhitespace()) j++
            if (j >= text.length || text[j] != '.') break
            val name = Regex("""\.\s*(\w+)\s*\(""").matchAt(text, j) ?: break
            val (_, end) = Source.arguments(text, name.range.last) ?: break
            out.append(text, j, end + 1)
            i = end + 1
        }
        return out.toString()
    }

    /** What a chain says by [name] (`.Tooltip("...")`): its first argument. */
    private fun option(chain: String, name: String): String? {
        val m = Regex("""\.\s*$name\s*\(""").find(chain) ?: return null
        return Source.arguments(chain, m.range.last)?.first?.firstOrNull()
    }

    private fun widget(args: List<String>, chain: String, tab: String?, macros: Macros, maps: Map<String, List<Pair<String, String>>>, members: Map<String, Long>): Found? {
        val label = args.getOrNull(1)?.let(::label)
        val type = args.getOrNull(2)?.trim() ?: return null
        val name = option(chain, "CVar")?.let(macros::expand) ?: return null
        val about = option(chain, "Tooltip")?.let(macros::expand)
        return when (type) {
            "WIDGET_CVAR_CHECKBOX" -> found(name, Kind.BOOL, default = switch(option(chain, "DefaultValue")), label = label, about = about, tab = tab)
            "WIDGET_CVAR_SLIDER_INT" -> found(
                name, Kind.INT, default = option(chain, "DefaultValue")?.let { number(it, members) },
                min = option(chain, "Min")?.let { number(it, members) }?.toLongOrNull(), max = option(chain, "Max")?.let { number(it, members) }?.toLongOrNull(),
                label = label, about = about, tab = tab,
            )
            "WIDGET_CVAR_SLIDER_FLOAT" -> found(name, Kind.FLOAT, default = option(chain, "DefaultValue")?.let(Source::literal), label = label, about = about, tab = tab)
            "WIDGET_CVAR_COMBOBOX" -> {
                val map = option(chain, "ComboMap")?.trim()?.removePrefix("&")?.let { maps[it] } ?: return null
                found(name, Kind.ENUM, default = option(chain, "DefaultIndex")?.let { number(it, members) } ?: "0", choices = map.map { Choice(it.first, it.second) }, label = label, about = about, tab = tab)
            }
            else -> null
        }
    }

    private fun positional(
        type: String,
        args: List<String>,
        menu: String?,
        macros: Macros,
        maps: Map<String, List<Pair<String, String>>>,
        members: Map<String, Long>,
        all: String,
    ): Found? {
        val label = args.getOrNull(0)?.let(::label)
        val name = args.getOrNull(1)?.let(macros::expand) ?: return null
        fun opts(at: Int): String = args.getOrNull(at).orEmpty()
        fun field(options: String, designated: String, chained: String): String? =
            Regex("""\.$designated\s*=\s*([^,}]+)""").find(options)?.groupValues?.get(1)?.trim() ?: option(options, chained)
        return when (type) {
            "CVarCheckbox" -> {
                val o = opts(2)
                found(name, Kind.BOOL, default = switch(field(o, "defaultValue", "DefaultValue")), label = label, about = field(o, "tooltip", "Tooltip")?.let(macros::expand), tab = menu)
            }
            "CVarSliderInt" -> {
                // `(label, cvar, min, max, default, options)`, or `(label, cvar, options)` with them in the chain.
                if (args.size <= 3) {
                    val o = opts(2)
                    found(name, Kind.INT, default = field(o, "defaultValue", "DefaultValue")?.let { number(it, members) },
                        min = field(o, "min", "Min")?.let { number(it, members) }?.toLongOrNull(), max = field(o, "max", "Max")?.let { number(it, members) }?.toLongOrNull(),
                        label = label, about = field(o, "tooltip", "Tooltip")?.let(macros::expand), tab = menu)
                } else {
                    found(name, Kind.INT, default = number(opts(4), members), min = number(opts(2), members)?.toLongOrNull(), max = number(opts(3), members)?.toLongOrNull(),
                        label = label, about = field(opts(5), "tooltip", "Tooltip")?.let(macros::expand), tab = menu)
                }
            }
            "CVarSliderFloat" -> {
                val default = if (args.size <= 3) field(opts(2), "defaultValue", "DefaultValue") else opts(4)
                found(name, Kind.FLOAT, default = default?.let(Source::literal), label = label, about = field(opts(if (args.size <= 3) 2 else 5), "tooltip", "Tooltip")?.let(macros::expand), tab = menu)
            }
            else -> {
                val items = args.getOrNull(2)?.trim()?.removePrefix("&") ?: return null
                val map = maps[items] ?: labels(items, all)?.mapIndexed { i, l -> i.toString() to l } ?: return null
                val o = opts(3)
                found(name, Kind.ENUM, default = field(o, "defaultIndex", "DefaultIndex")?.let { number(it, members) } ?: "0",
                    choices = map.map { Choice(it.first, it.second) }, label = label, about = field(o, "tooltip", "Tooltip")?.let(macros::expand), tab = menu)
            }
        }
    }

    /** A setting at the CVar [name]: under `CVars`, in the objects its dots make. */
    private fun found(
        name: String,
        kind: Kind,
        default: String? = null,
        min: Long? = null,
        max: Long? = null,
        choices: List<Choice> = emptyList(),
        label: String?,
        about: String?,
        tab: String?,
    ): Found? {
        // A control the menu shows with no name of its own (a window's sizing) isn't offered.
        if (label == null) return null
        val parts = name.split('.')
        if (parts.any { it.isEmpty() || !Regex("""^[\w\-\[\] ]+$""").matches(it) }) return null
        val section = (listOf("CVars") + parts.dropLast(1)).joinToString(Found.PATH)
        // Outside the menu (a tracker's own window): named by where its CVar is (`gTrackers.CheckTracker` is Trackers / Check Tracker).
        val where = tab ?: parts.dropLast(1).joinToString(" / ") { words(it.removePrefix("g")) }.ifEmpty { null }
        if (where != null && LEFT_OUT.containsMatchIn(where)) return null
        return Found(null, section, parts.last(), kind, default = default, min = min, max = max, choices = choices, perGame = false, label = label, about = about?.trim()?.takeIf { it.isNotEmpty() }, tab = where)
    }

    /** A CVar's part as words (`CheckTracker` is Check Tracker). */
    private fun words(part: String): String = part.replace(Regex("""(?<=[a-z0-9])(?=[A-Z])"""), " ")

    /** A switch's default as the port writes it: 1 or 0. */
    private fun switch(raw: String?): String = when (raw?.trim()) {
        "true", "1" -> "1"
        else -> "0"
    }

    /** A whole number written in the code, or an enum member's value. */
    private fun number(raw: String, members: Map<String, Long>): String? {
        val t = raw.trim().removeSuffix("u").removeSuffix("U")
        Source.literal(t)?.takeIf { it.toDoubleOrNull() != null }?.let { v -> return v.toDouble().let { if (it == Math.floor(it)) it.toLong().toString() else v } }
        val parts = t.split("::")
        return (members[parts.takeLast(2).joinToString("::")] ?: members[parts.last()])?.toString()
    }

    /** A label as Fuse shows it: a slider's value format (`MSAA: %d`) taken out. */
    private fun label(arg: String): String? = Source.string(arg)
        ?.replace(Regex("""\s*[:(]?\s*%[-+ #0]*\d*(?:\.\d+)?[dfisux]\w*\s*\)?"""), "")?.replace("%%", "")
        ?.substringBefore("##")?.trim()?.removeSuffix(":")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Every enum member's value, by its own name where only one enum has it, and by its enum's name
     * (`Colors::LightBlue`): named enums, scoped ones and C's `typedef enum { ... } Name;` alike.
     */
    private fun members(text: String): Map<String, Long> {
        val plain = HashMap<String, MutableSet<Long>>()
        val qualified = HashMap<String, Long>()
        for (m in Regex("""\benum\b(?:\s+(?:class|struct))?\s*(\w*)\s*(?::\s*[\w:]+(?:\s+\w+)?\s*)?\{([^{}]*)\}\s*(\w*)""").findAll(text)) {
            val name = m.groupValues[1].ifEmpty { m.groupValues[3] }
            var next = 0L
            val own = LinkedHashMap<String, Long>()
            val body = m.groupValues[2].lines().filterNot { it.trim().startsWith("#") }.joinToString("\n")
            for (part in body.split(',')) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val member = p.substringBefore('=').trim().takeIf { Regex("^\\w+$").matches(it) } ?: continue
                val raw = p.substringAfter('=', "").trim()
                val value = when {
                    raw.isEmpty() -> next
                    else -> raw.removeSuffix("u").removeSuffix("U").let { v ->
                        v.toLongOrNull() ?: v.takeIf { it.startsWith("0x") || it.startsWith("0X") }?.substring(2)?.toLongOrNull(16)
                            ?: own[v] ?: Regex("""^\(?\s*1\s*<<\s*(\d+)\s*\)?$""").find(v)?.groupValues?.get(1)?.toIntOrNull()?.let { 1L shl it }
                    } ?: break
                }
                own[member] = value
                next = value + 1
            }
            for ((member, value) in own) {
                plain.getOrPut(member) { HashSet() } += value
                if (name.isNotEmpty()) qualified.putIfAbsent("$name::$member", value)
            }
        }
        return plain.filterValues { it.size == 1 }.mapValues { it.value.single() } + qualified
    }

    /** `map<int32_t, const char*> name = { { value, "Label" }, ... }`: each value (as a number) and its label. */
    private fun comboMaps(text: String, members: Map<String, Long>): Map<String, List<Pair<String, String>>> {
        val out = HashMap<String, List<Pair<String, String>>>()
        for (m in Regex("""map\s*<[^>]*>\s+(\w+)\s*=\s*\{""").findAll(text)) {
            // A trailing comma leaves an empty item, which isn't one.
            val items = Source.arguments(text, m.range.last, '{', '}')?.first?.filter { it.isNotBlank() } ?: continue
            val pairs = items.mapNotNull { item ->
                val (parts, _) = Source.arguments(item.trim(), 0, '{', '}') ?: return@mapNotNull null
                val value = parts.getOrNull(0)?.let { number(it, members) } ?: return@mapNotNull null
                val label = parts.getOrNull(1)?.let(Source::string) ?: return@mapNotNull null
                value to label
            }
            if (pairs.isNotEmpty() && pairs.size == items.size) out.putIfAbsent(m.groupValues[1], pairs)
        }
        return out
    }

    /** `const char* name[] = { "A", "B" }` (or a std::array of them): its labels, each chosen by its index. */
    private fun labels(name: String, text: String): List<String>? {
        val m = Regex("""\b${Regex.escape(name)}\s*(?:\[\s*\w*\s*])?\s*=\s*\{""").find(text) ?: return null
        val items = Source.arguments(text, m.range.last, '{', '}')?.first?.filter { it.isNotBlank() } ?: return null
        return items.map { Source.string(it) ?: return null }.takeIf { it.isNotEmpty() }
    }

    /** CMake's `set(NAME "value")`, its `${OTHER}` read, as `#define NAME "value"` lines. */
    fun cmakeDefines(text: String): String {
        val values = LinkedHashMap<String, String>()
        for (m in Regex("""set\s*\(\s*(\w+)\s+"([^"]*)"""").findAll(text)) values.putIfAbsent(m.groupValues[1], m.groupValues[2])
        fun read(v: String, depth: Int = 0): String = if (depth > 8) v else Regex("""\$\{(\w+)}""").replace(v) { values[it.groupValues[1]]?.let { o -> read(o, depth + 1) } ?: it.value }
        return values.entries.filter { (_, v) -> "\${" !in read(v) }.joinToString("\n") { (k, v) -> "#define $k \"${read(v)}\"" }
    }

    /** The port's `#define`s, to read a CVar or a tooltip named through them. */
    class Macros(text: String) {
        private val functions = HashMap<String, Pair<List<String>, String>>()
        private val objects = HashMap<String, String>()

        init {
            for (m in Regex("""^[ \t]*#[ \t]*define[ \t]+(\w+)(\([^)]*\))?[ \t]+(.+)$""", RegexOption.MULTILINE).findAll(text)) {
                val name = m.groupValues[1]
                val body = m.groupValues[3].trim()
                if (m.groupValues[2].isNotEmpty()) {
                    functions.putIfAbsent(name, m.groupValues[2].removeSurrounding("(", ")").split(',').map { it.trim() } to body)
                } else {
                    objects.putIfAbsent(name, body)
                }
            }
        }

        /** What [expr] comes to as text: strings joined, macros expanded; null when any part isn't known. */
        fun expand(expr: String): String? = expand(expr, 0)

        private fun expand(expr: String, depth: Int): String? {
            if (depth > 12) return null
            val out = StringBuilder()
            var i = 0
            val t = expr.trim()
            while (i < t.length) {
                val c = t[i]
                when {
                    c.isWhitespace() -> i++
                    c == '"' -> {
                        var j = i + 1
                        while (j < t.length && t[j] != '"') j += if (t[j] == '\\') 2 else 1
                        out.append(Source.string(t.substring(i, minOf(j + 1, t.length))) ?: return null)
                        i = j + 1
                    }
                    c.isLetter() || c == '_' -> {
                        var j = i
                        while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
                        val name = t.substring(i, j)
                        var k = j
                        while (k < t.length && t[k].isWhitespace()) k++
                        val function = functions[name]
                        if (function != null && k < t.length && t[k] == '(') {
                            val (args, end) = Source.arguments(t, k) ?: return null
                            var body = function.second
                            function.first.zip(args).forEach { (param, arg) -> body = body.replace(Regex("""\b${Regex.escape(param)}\b"""), Regex.escapeReplacement(arg)) }
                            out.append(expand(body, depth + 1) ?: return null)
                            i = end + 1
                        } else {
                            out.append(expand(objects[name] ?: return null, depth + 1) ?: return null)
                            i = j
                        }
                    }
                    else -> return null
                }
            }
            return out.toString()
        }
    }
}
