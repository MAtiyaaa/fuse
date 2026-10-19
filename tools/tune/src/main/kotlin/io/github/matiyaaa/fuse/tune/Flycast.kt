package io.github.matiyaaa.fuse.tune

/**
 * The settings of Flycast (the Dreamcast and its arcade boards), from its own code:
 *
 * - core/cfg/option.cpp: each one, `Option<bool> VSync("rend.vsync", true);`, its key, default
 *   and the `emu.cfg` section it is kept in (`config` unless named: `OptionString
 *   AudioBackend("backend", "auto", "audio")`). One declared `Option<T, false>` is for every game
 *   only; any other a game can have (core/cfg/option.h: `Option::load` reads it from the game's own
 *   section, `[<game id>]`, as `<section>.<key>`, else as every game has it). Read as a desktop
 *   build for a PC compiles it.
 * - core/ui/settings_*.cpp: what its settings screens call each and say about it
 *   (`OptionCheckbox(T("VSync"), config::VSync, T("Synchronizes the frame rate..."))`), a
 *   slider's or arrows' range (`OptionSlider(T("Horizontal Stretching"), config::ScreenStretching,
 *   100, 250, ...)`) and a choice's values (`OptionRadioButton(T("Normal"), config::AutoSkipFrame, 1,
 *   ...)`), and the tab each screen is.
 *
 * - core/cfg/option.h: the options of a class of their own (`AudioVolumeOption AudioVolume`, its
 *   `Option<int>("aica.Volume", 100)`), and the renderer (`RendererOption RendererType`,
 *   `pvr.rend`), one of core/types.h's `RenderType`s: the graphics API its screen offers (`OpenGL`,
 *   `Vulkan`) and, for an `_OIT` one, its per-pixel sorting. DirectX is built only for Windows.
 *
 * Its switches are written `yes` and `no` (core/cfg/ini.h `IniFile::set`).
 */
object Flycast {
    const val OPTIONS = "core/cfg/option.cpp"
    const val HEADER = "core/cfg/option.h"
    const val TYPES = "core/types.h"
    const val VIDEO = "core/ui/settings_video.cpp"
    val SCREENS = listOf("video", "audio", "general", "network", "controls").map { "core/ui/settings_$it.cpp" }
    val FILES = listOf(OPTIONS, HEADER, TYPES) + SCREENS

    private val DECLARATION = Regex("""\b(?:(Option)\s*<\s*([\w:]+)\s*(?:,\s*(true|false)\s*)?>|(OptionString))\s*(\w+)?\s*\(""")
    private val UI = Regex("""\b(OptionCheckbox|OptionSlider|OptionArrowButtons|OptionComboBox|OptionRadioButton)\s*\(""")
    private val INTEGERS = setOf("int", "int64_t", "u32", "u64", "s32", "unsigned")
    private val CLASS = Regex("""\bclass\s+(\w+)\s*:\s*public\s+Option\s*<\s*(\w+)\s*>""")
    private val FORMAT = Regex("""%[-+ #0]*\d*(?:\.\d+)?[dfisu]""")

    /** What a screen says of one option: its name, help, range, and the choices radio buttons give it. */
    private class Words(var label: String? = null, var about: String? = null, var min: Long? = null, var max: Long? = null, var tab: String? = null) {
        val choices = LinkedHashMap<String, String>()
    }

    fun parse(files: Map<String, String>): List<Found> {
        // A desktop build: not Android, not libretro's, x86 rather than ARM.
        val options = Source.select(Source.withoutComments(files[OPTIONS] ?: return emptyList())) { false }
        val words = HashMap<String, Words>()
        for (path in SCREENS) {
            val text = Source.withoutComments(files[path] ?: continue)
            val tab = path.substringAfter("settings_").substringBefore(".cpp").replaceFirstChar { it.uppercase() }
            for (m in UI.findAll(text)) {
                val (args, _) = Source.arguments(text, m.range.last) ?: continue
                val name = args.getOrNull(1)?.trim()?.removePrefix("config::")?.takeIf { Regex("^\\w+$").matches(it) } ?: continue
                val w = words.getOrPut(name) { Words(tab = tab) }
                val label = tr(args[0])
                when (m.groupValues[1]) {
                    "OptionRadioButton" -> {
                        val value = Source.literal(args.getOrNull(2).orEmpty()) ?: continue
                        if (label != null) w.choices[value] = label
                    }
                    else -> {
                        w.label = w.label ?: label
                        // Its help: the text after the name and option that isn't a number's format (`"%d%%"`).
                        w.about = w.about ?: args.drop(2).mapNotNull(::tr).firstOrNull { !FORMAT.containsMatchIn(it) }
                        if (m.groupValues[1] == "OptionSlider" || m.groupValues[1] == "OptionArrowButtons") {
                            w.min = Source.literal(args.getOrNull(2).orEmpty())?.toLongOrNull()
                            w.max = Source.literal(args.getOrNull(3).orEmpty())?.toLongOrNull()
                        }
                    }
                }
            }
        }
        val out = LinkedHashMap<String, Found>()
        for (d in DECLARATION.findAll(options)) {
            val (args, _) = Source.arguments(options, d.range.last) ?: continue
            val key = args.getOrNull(0)?.let(Source::string)?.takeIf { it.isNotEmpty() } ?: continue
            val type = if (d.groupValues[4].isNotEmpty()) "std::string" else d.groupValues[2]
            val perGame = d.groupValues[3] != "false"
            val section = args.getOrNull(2)?.let(Source::string) ?: "config"
            val raw = args.getOrNull(1)?.trim()
            val w = d.groupValues[5].takeIf { it.isNotEmpty() }?.let(words::get)
            val found = when {
                type == "bool" -> Found(null, section, key, Kind.BOOL, default = raw?.takeIf { it == "true" || it == "false" } ?: "false".takeIf { raw == null }, perGame = perGame)
                type in INTEGERS -> {
                    val default = if (raw == null) "0" else Source.literal(raw)?.takeIf { it.toLongOrNull() != null }
                    val choices = w?.choices?.takeIf { it.isNotEmpty() }
                    if (choices != null) {
                        Found(null, section, key, Kind.ENUM, default = default, choices = choices.map { (v, l) -> Choice(v, l) }, perGame = perGame)
                    } else {
                        Found(null, section, key, Kind.INT, default = default, min = w?.min, max = w?.max, perGame = perGame)
                    }
                }
                type == "float" || type == "double" -> Found(
                    null, section, key, Kind.FLOAT,
                    default = if (raw == null) "0" else Source.literal(raw)?.let { if (it.endsWith(".")) it + "0" else it }, perGame = perGame,
                )
                type == "std::string" -> Found(null, section, key, Kind.STRING, default = if (raw == null) "" else Source.string(raw), perGame = perGame)
                else -> continue
            }
            out.putIfAbsent(found.identity, found.copy(label = w?.label, about = w?.about, tab = w?.tab ?: "Other"))
        }
        // Those of a class of their own, as a desktop build for Linux (OpenGL and Vulkan) has them.
        val header = Source.select(Source.withoutComments(files[HEADER].orEmpty())) { it == "USE_OPENGL" || it == "USE_VULKAN" }
        for (c in CLASS.findAll(header)) {
            val (name, type) = c.destructured
            val made = Regex("""\bOption\s*<\s*${Regex.escape(type)}\s*>\s*\(""").find(header, c.range.last) ?: continue
            val (args, _) = Source.arguments(header, made.range.last) ?: continue
            val key = args.getOrNull(0)?.let(Source::string)?.takeIf { it.isNotEmpty() } ?: continue
            val section = args.getOrNull(2)?.let(Source::string) ?: "config"
            val w = Regex("""\bextern\s+${Regex.escape(name)}\s+(\w+)\s*;""").find(header)?.groupValues?.get(1)?.let(words::get)
            val raw = args.getOrNull(1)?.trim()
            val found = when {
                type in INTEGERS -> Found(null, section, key, Kind.INT, default = raw?.let(Source::literal)?.takeIf { it.toLongOrNull() != null }, min = w?.min, max = w?.max)
                    .copy(label = w?.label, about = w?.about, tab = w?.tab ?: "Other")
                type == "RenderType" -> renderer(files, section, key, raw) ?: continue
                else -> continue
            }
            out.putIfAbsent(found.identity, found)
        }
        return out.values.toList()
    }

    /**
     * The renderer: each of core/types.h's `RenderType`s (`OpenGL = 0`, `OpenGL_OIT = 3`), called
     * as the video screen calls its graphics API (`ImGui::RadioButton("Vulkan", &renderApi,
     * Vulkan)`) and, for an `_OIT` one, its per-pixel sorting (`T("Per Pixel")`).
     */
    private fun renderer(files: Map<String, String>, section: String, key: String, raw: String?): Found? {
        val types = Source.withoutComments(files[TYPES] ?: return null)
        val members = Regex("""enum\s+class\s+RenderType\s*\{([^}]*)}""").find(types)?.groupValues?.get(1)
            ?.let { body -> Regex("""(\w+)\s*=\s*(\d+)""").findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList() } ?: return null
        // Every API's name, as a build for other than a Mac calls it.
        val video = Source.select(Source.withoutComments(files[VIDEO].orEmpty())) { it != "__APPLE__" }
        val apis = Regex("""RadioButton\s*\(\s*"([^"]+)"\s*,\s*&renderApi\s*,\s*(\w+)\s*\)""").findAll(video).associate { it.groupValues[2] to it.groupValues[1] }
        val perPixel = Regex("""RadioButton\s*\(\s*T\s*\(\s*"([^"]+)"\s*\)\s*,\s*&renderer\s*,\s*2\s*\)""").find(video)?.groupValues?.get(1)
        val heading = video.indexOf("&renderApi").takeIf { it >= 0 }?.let { at -> Regex("""\bheader\s*\(\s*T\s*\(\s*"([^"]+)"""").findAll(video.substring(0, at)).lastOrNull()?.groupValues?.get(1) }
        val choices = members.map { (name, value) ->
            val api = name.removeSuffix("_OIT")
            val label = apis[api]?.let { if (name != api && perPixel != null) "$it, $perPixel" else it }
            Choice(value, label, setOf("windows").takeIf { api.startsWith("DirectX") })
        }
        val default = raw?.substringAfterLast("::")?.let { d -> members.firstOrNull { it.first == d }?.second }
        return Found(null, section, key, Kind.ENUM, default = default, choices = choices, label = heading, tab = "Video")
    }

    /** The text of `T("...")` (Flycast's translated string), or of a plain string. */
    private fun tr(arg: String): String? {
        val t = arg.trim()
        val inner = if (t.startsWith("T(") && t.endsWith(")")) t.removePrefix("T(").removeSuffix(")") else t
        val pieces = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(inner).toList()
        if (pieces.isEmpty() || inner.replace(Regex(""""(?:[^"\\]|\\.)*""""), "").isNotBlank()) return null
        return pieces.joinToString("") { Source.string("\"${it.groupValues[1]}\"").orEmpty() }
    }
}
