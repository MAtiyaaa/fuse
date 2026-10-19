package io.github.matiyaaa.fuse.tune

/**
 * The settings of melonDS (Nintendo DS and DSi), from its own code: melonDS.toml as its Qt
 * frontend (src/frontend/qt_sdl) reads it.
 *
 * - Config.cpp: the defaults of those that have one (`DefaultInts`: `{"3D.GL.ScaleFactor", 1}`,
 *   `DefaultBools`, `DefaultStrings`, `DefaultDoubles`) and a number's range (`IntRanges`:
 *   `{"3D.GL.ScaleFactor", {1, 16}}`). Any other is 0, off or empty until set (`Table::GetInt`
 *   and the rest give `FindDefault`'s 0). A name under `Instance*` is each instance's own.
 * - Every file of the frontend: each setting it reads (`cfg.GetBool("Emu.DirectBoot")`), by the
 *   table it reads it from: every instance's (`emuInstance->getGlobalConfig()`), the first
 *   instance's (`getLocalConfig()`, kept under `[Instance0]`) or a table in one of those
 *   (`localCfg.GetTable("Firmware")`, a window's `GetTable("Window"+..., "Window0")`).
 * - The settings dialogs: a setting shown in a widget (`ui->chkDirectBoot->setChecked(...)`) is
 *   called what its .ui file calls the widget (a check box's text, the label beside it), with its
 *   help (`whatsThis`, `toolTip`), the tab it is on, and the choices of a list
 *   (`ui->cbxConsoleType->addItem("DS")`, each its place in the list).
 *
 * melonDS has no settings of a game's own: every one is for every game. This computer's own files,
 * folders and devices (`DS.BIOS9Path`, `LastROMFolder`, `Mic.Device`), the window's place and the
 * keys and buttons (held as codes) aren't offered.
 */
object MelonDs {
    const val DIR = "src/frontend/qt_sdl"
    val TREE = listOf("$DIR/*.cpp", "$DIR/*.h", "$DIR/*.ui", "src/frontend/*.h")

    private val LISTS = Regex("""\bDefaultList<\s*(int|bool|std::string|double)\s*>\s*(\w+)\s*=\s*\{""")
    private val RANGES = Regex("""\bRangeList\s+\w+\s*=\s*\{""")
    private val READ = Regex("""\b(\w+)\s*(?:\.|->)\s*Get(Int|Bool|String|QString|Double|Int64)\s*\(\s*"([\w.]+)"\s*\)""")
    private val ROOT = Regex("""\b(\w+)\s*(?:\(|=)\s*(?:\w+\s*->\s*|Config::)?(getGlobalConfig\s*\(\s*\)|getLocalConfig\s*\(\s*\)|GetGlobalTable\s*\(\s*\)|GetLocalTable\s*\([^()]*\))(?!\s*\.\s*GetTable)""")
    private val MEMBER = Regex("""\b(\w+)\s*\(\s*\w+\s*->\s*(globalCfg|localCfg)\s*\)""")
    private val TABLE = Regex("""\b(\w+)\s*(?:\(|=)\s*(\w+)\s*(?:\.|->)\s*GetTable\s*\(\s*([^,()]+(?:\([^()]*\))?[^,()]*)\s*(?:,\s*"([\w.]+)"\s*)?\)""")
    private val RESET = Regex("""\b(?:Config::Table|auto)\s*&?\s*(\w+)\s*=""")
    private val VALUE = Regex("""\b(\w+)\s*=\s*(\w+)\s*(?:\.|->)\s*Get(?:Int|Bool|String|QString|Double|Int64)\s*\(\s*"([\w.]+)"\s*\)""")
    private val USE = Regex("""\bui\s*->\s*(\w+)\s*->\s*(setChecked|setValue|setCurrentIndex|setText)\s*\(\s*(\w+)\s*(?:-\s*(\d+)|!=\s*0)?\s*\)""")
    private val GROUP_ADD = Regex("""\b(\w+)\s*->\s*addButton\s*\(\s*ui\s*->\s*(\w+)\s*,\s*(\w+)\s*\)""")
    private val GROUP_SET = Regex("""\b(\w+)\s*->\s*button\s*\(\s*(\w+)\s*\)\s*->\s*setChecked""")
    private val WIDGET = Regex("""\bui\s*->\s*(\w+)\s*->\s*(setChecked|setValue|setCurrentIndex|setText)\s*\(\s*(\w+)\s*(?:\.|->)\s*Get\w+\s*\(\s*"([\w.]+)"\s*\)""")
    private val ADD = Regex("""\bui\s*->\s*(\w+)\s*->\s*addItem\s*\(([^;]*)\)\s*;""")

    /** Where a table variable reads: every instance's settings or the first instance's, and the table's own path in it. */
    private data class Scope(val local: Boolean, val prefix: String)

    /** What a .ui file says of a widget: its text, help and tab, and the label beside it. */
    private class Widget(var text: String? = null, var about: String? = null, var tab: String? = null, var label: String? = null, val items: List<String> = emptyList())

    fun parse(files: Map<String, String>): List<Found> {
        val config = Source.withoutComments(files["$DIR/Config.cpp"] ?: return emptyList())
        // As a desktop build with the JIT compiles it, not a Mac's.
        val cfg = Source.select(config) { it == "JIT_ENABLED" }
        val defaults = HashMap<String, Pair<String, String>>()
        for (m in LISTS.findAll(cfg)) {
            val (rows, _) = Source.arguments(cfg, m.range.last, '{', '}') ?: continue
            for (row in rows) {
                val (args, _) = Source.arguments(row.trim(), 0, '{', '}') ?: continue
                val path = args.getOrNull(0)?.let(Source::string) ?: continue
                val value = args.getOrNull(1)?.trim() ?: continue
                defaults[path] = m.groupValues[1] to value
            }
        }
        val ranges = HashMap<String, Pair<Long?, Long?>>()
        // Named numbers: constants, and enums as a build with OpenGL has them (src/frontend/ScreenLayout.h, EmuInstance.h).
        val constants = Source.constants(cfg) + files.filterKeys { it.endsWith(".h") }.values
            .flatMap { enums(Source.select(Source.withoutComments(it)) { d -> d == "OGLRENDERER_ENABLED" || d == "JIT_ENABLED" }).entries }.associate { it.key to it.value }
        for (m in RANGES.findAll(cfg)) {
            val (rows, _) = Source.arguments(cfg, m.range.last, '{', '}') ?: continue
            for (row in rows) {
                val (args, _) = Source.arguments(row.trim(), 0, '{', '}') ?: continue
                val path = args.getOrNull(0)?.let(Source::string) ?: continue
                val (bounds, _) = Source.arguments(args.getOrNull(1)?.trim() ?: continue, 0, '{', '}') ?: continue
                ranges[path] = bounds.getOrNull(0)?.let { number(it, constants) } to bounds.getOrNull(1)?.let { number(it, constants) }
            }
        }

        // Every setting each file reads, by the table it reads it from.
        val kinds = LinkedHashMap<String, String>()
        // The widget each is shown in, how, and what its list's places are less than its values (`setCurrentIndex(oldGLScale-1)`).
        val widgets = HashMap<String, Triple<String, String, Int>>()
        // Those chosen with a group of buttons: the group's buttons, each with its value's name.
        val groupOf = HashMap<String, String>()
        val buttons = HashMap<String, MutableList<Pair<String, String>>>()
        val ui = files.filterKeys { it.endsWith(".ui") }.values.flatMap { widgetsOf(it).entries }.associate { it.key to it.value }
        for ((_, raw) in files.filterKeys { it.endsWith(".cpp") }.toSortedMap()) {
            val text = Source.select(Source.withoutComments(raw)) { it == "JIT_ENABLED" || it == "GDBSTUB_ENABLED" }
            val scopes = HashMap<String, Scope?>()
            scopes["globalCfg"] = Scope(false, "")
            scopes["localCfg"] = Scope(true, "")
            fun place(scope: Scope, key: String) = (if (scope.local) "Instance0." else "") + scope.prefix + key
            // Bindings and reads in the order they come: a name is bound again in each function.
            val held = HashMap<String, String>()
            val events = listOf(RESET, ROOT, MEMBER, TABLE, READ, VALUE, WIDGET, USE, GROUP_ADD, GROUP_SET)
                .flatMap { r -> r.findAll(text).map { Triple(it.range.first, r, it) } }.sortedBy { it.first }
            for ((_, r, m) in events) {
                when (r) {
                    // A table name bound to something else: what it was bound to before no longer holds.
                    RESET -> scopes[m.groupValues[1]] = null
                    ROOT -> {
                        val what = m.groupValues[2]
                        scopes[m.groupValues[1]] = Scope(local = what.startsWith("getLocalConfig") || (what.startsWith("GetLocalTable") && "-1" !in what), "")
                    }
                    MEMBER -> scopes[m.groupValues[1]] = Scope(m.groupValues[2] == "localCfg", "")
                    TABLE -> {
                        val outer = scopes[m.groupValues[2]]
                        // A table by its name, or a window's by the one it starts as (`"Window"+..., "Window0"`).
                        val name = Source.string(m.groupValues[3].trim()) ?: m.groupValues[4].takeIf { it.isNotEmpty() }
                        scopes[m.groupValues[1]] = if (outer != null && name != null) Scope(outer.local, outer.prefix + name + ".") else null
                    }
                    READ -> scopes[m.groupValues[1]]?.let { kinds.putIfAbsent(place(it, m.groupValues[3]), m.groupValues[2]) }
                    WIDGET -> scopes[m.groupValues[3]]?.let { widgets.putIfAbsent(place(it, m.groupValues[4]), Triple(m.groupValues[1], m.groupValues[2], 0)) }
                    // Read into a name first, then shown: `oldVSync = cfg.GetBool("Screen.VSync")`, `ui->cbVSync->setChecked(oldVSync != 0)`.
                    VALUE -> scopes[m.groupValues[2]]?.let { held[m.groupValues[1]] = place(it, m.groupValues[3]) }
                    USE -> held[m.groupValues[3]]?.let { widgets.putIfAbsent(it, Triple(m.groupValues[1], m.groupValues[2], m.groupValues[4].toIntOrNull() ?: 0)) }
                    GROUP_ADD -> buttons.getOrPut(m.groupValues[1]) { mutableListOf() } += m.groupValues[2] to m.groupValues[3]
                    GROUP_SET -> held[m.groupValues[2]]?.let { groupOf.putIfAbsent(it, m.groupValues[1]) }
                }
            }
        }
        // A list's choices, where every one is a plain name (not one made in a loop).
        val items = HashMap<String, MutableList<String?>>()
        for ((_, raw) in files.filterKeys { it.endsWith(".cpp") }) {
            for (m in ADD.findAll(Source.withoutComments(raw))) items.getOrPut(m.groupValues[1]) { mutableListOf() } += Source.string(m.groupValues[2].trim())
        }
        // Each default's own name: Instance0 for `Instance*`, the first window for `Window*`.
        fun named(path: String) = path.replace("Instance*", "Instance0").replace("Window*", "Window0")
        val defaultOf = defaults.mapKeys { named(it.key) }
        val rangeOf = ranges.mapKeys { named(it.key) }
        for ((path, d) in defaultOf) {
            kinds.putIfAbsent(path, when (d.first) { "int" -> "Int"; "bool" -> "Bool"; "double" -> "Double"; else -> "String" })
        }

        val out = ArrayList<Found>()
        for ((path, getter) in kinds) {
            if (!offered(path)) continue
            val section = path.substringBeforeLast('.', "")
            val key = path.substringAfterLast('.')
            val given = defaultOf[path]?.second
            val (widget, how, offset) = widgets[path] ?: Triple(null, null, 0)
            val w = widget?.let(ui::get)
            val label = w?.text?.takeIf { how == "setChecked" } ?: w?.label
            val tab = w?.tab ?: section.substringAfter("Instance0.").substringBefore('.').ifEmpty { "General" }.let { if (it == "Instance0") "General" else it }
            val listed = if (how != "setCurrentIndex") null else {
                widget?.let(items::get)?.takeIf { l -> l.isNotEmpty() && l.all { it != null } }?.map { it!! } ?: w?.items?.takeIf { it.isNotEmpty() }
            }
            // A list's choices are its places (plus what they are less by); a group's, its buttons' values.
            val choices = listed?.mapIndexed { i, c -> Choice((i + offset).toString(), c) }
                ?: groupOf[path]?.let(buttons::get)?.mapNotNull { (button, value) -> number(value, constants)?.let { Choice(it.toString(), ui[button]?.text) } }?.takeIf { it.isNotEmpty() }
            val found = when (getter) {
                "Bool" -> Found(null, section, key, Kind.BOOL, default = given ?: "false")
                "Int", "Int64" -> {
                    val default = given?.let { number(it, constants)?.toString() } ?: "0".takeIf { given == null } ?: continue
                    if (choices != null) {
                        Found(null, section, key, Kind.ENUM, default = default, choices = choices)
                    } else {
                        val (min, max) = rangeOf[path] ?: (null to null)
                        Found(null, section, key, Kind.INT, default = default, min = min, max = max)
                    }
                }
                "Double" -> Found(null, section, key, Kind.FLOAT, default = given?.let(Source::literal)?.let { if ('.' in it) it else "$it.0" } ?: "0.0")
                else -> Found(null, section, key, Kind.STRING, default = given?.let(Source::string) ?: "")
            }
            out += found.copy(perGame = false, label = label, about = w?.about, tab = tab)
        }
        return out
    }

    /** Whether Fuse offers [path]: not a file, folder or device of this computer's, the window's place, or the keys and buttons. */
    private fun offered(path: String): Boolean {
        val key = path.substringAfterLast('.')
        val tables = path.split('.').dropLast(1)
        return !(key.endsWith("Path") || key.endsWith("Folder") || key.endsWith("Device") || key.endsWith("DeviceName") ||
            key.startsWith("Recent") || key in setOf("Geometry", "Width", "Height", "Enabled", "JoystickID", "Keyboard", "Joystick") ||
            tables.any { it == "Keyboard" || it == "Joystick" })
    }

    /** A number in the code: a literal, a named constant, or one less than one (`renderer3D_Max-1`). */
    private fun number(text: String, constants: Map<String, String>): Long? {
        val t = text.trim()
        Source.literal(t)?.toLongOrNull()?.let { return it }
        constants[t]?.let { Source.literal(it)?.toLongOrNull() }?.let { return it }
        val less = Regex("""^(\w+)\s*-\s*(\d+)$""").matchEntire(t) ?: return null
        val base = constants[less.groupValues[1]]?.let { Source.literal(it)?.toLongOrNull() } ?: return null
        return base - less.groupValues[2].toLong()
    }

    /** A .ui file's widgets by name: what each says, its help, its tab and the label beside it in its grid. */
    private fun widgetsOf(xml: String): Map<String, Widget> {
        val out = HashMap<String, Widget>()
        val start = Regex("""<widget class="(\w+)" name="(\w+)"\s*>""")
        val tab = Regex("""<attribute name="title">\s*<string>([^<]*)</string>""")
        val item = Regex("""<item row="(\d+)" column="(\d+)"""")
        var currentTab: String? = null
        var lastLabel: Triple<Int, Int, String>? = null
        var row: Pair<Int, Int>? = null
        val marks = (start.findAll(xml).map { it.range.first to it } + tab.findAll(xml).map { it.range.first to it } + item.findAll(xml).map { it.range.first to it }).sortedBy { it.first }
        for ((_, m) in marks) {
            when {
                m.value.startsWith("<attribute") -> currentTab = unescape(m.groupValues[1])
                m.value.startsWith("<item") -> row = m.groupValues[1].toInt() to m.groupValues[2].toInt()
                else -> {
                    val (cls, name) = m.destructured
                    // Its own properties: up to its first child widget or its end.
                    val end = listOf(xml.indexOf("<widget", m.range.last), xml.indexOf("</widget>", m.range.last)).filter { it >= 0 }.minOrNull() ?: xml.length
                    val body = xml.substring(m.range.last, end)
                    fun prop(p: String) = Regex("""<property name="$p">\s*<string[^>]*>([^<]*)</string>""").find(body)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotBlank() }
                    // A list's own items, in order.
                    val listed = Regex("""<item>\s*<property name="text">\s*<string[^>]*>([^<]*)</string>""").findAll(body).map { unescape(it.groupValues[1]) }.toList()
                    val w = Widget(text = prop("text"), about = prop("whatsThis") ?: prop("toolTip"), tab = currentTab, items = listed)
                    val here = row
                    if (cls == "QLabel") {
                        lastLabel = here?.let { Triple(it.first, it.second, w.text.orEmpty().trimEnd(':').trim()) }
                    } else {
                        val l = lastLabel
                        if (here != null && l != null && l.first == here.first && l.second == here.second - 1 && l.third.isNotEmpty()) w.label = l.third
                    }
                    row = null
                    out.putIfAbsent(name, w)
                }
            }
        }
        return out
    }

    /** Each enum's names with their numbers (`renderer3D_Software = 0, renderer3D_OpenGL, ...`), one after another. */
    private fun enums(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in Regex("""\benum\s+(?:class\s+)?\w*\s*(?::\s*\w+\s*)?\{([^}]*)}""").findAll(text)) {
            var next = 0L
            for (part in m.groupValues[1].split(',')) {
                val name = part.substringBefore('=').trim().takeIf { Regex("^\\w+$").matches(it) } ?: continue
                val given = part.substringAfter('=', "").trim().takeIf { it.isNotEmpty() }
                next = given?.let { Source.literal(it)?.toLongOrNull() ?: out[it]?.toLongOrNull() } ?: next
                out[name] = next.toString()
                next++
            }
        }
        return out
    }

    /** A .ui file's text: its escapes read and any HTML in it taken out. */
    private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}
