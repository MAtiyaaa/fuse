package io.github.matiyaaa.fuse.tune

import java.math.BigDecimal
import java.math.MathContext

/**
 * The settings of Supermodel (Sega Model 3), from its own code:
 *
 * - `DefaultConfig()` in Src/OSD/SDL/Main.cpp: each `config.Set("VSync", true, "Video")` with its
 *   default and the group its settings screen shows it in, a number's range (`config.Set(
 *   "Supersampling", 1, "Video", 1, 8)`) and a choice's values (`config.Set("UpscaleMode", 2,
 *   "Video", 0, 0, { 0,1,2,3 })`) with the names `SetLabels("UpscaleMode", { "None (sharp pixels)",
 *   ... })` gives them. Defaults named in Src/Inputs/InputSystem.h (`#define
 *   DEFAULT_JOY_DEADZONE 3`) are read from it, and the settings made in a loop over a list
 *   (`InputJoy<axis>DeadZone` for each of `joyAxisIds[]`) once for each.
 * - Its `-help` text (`Help()`), for what each setting does: the command line's options name the
 *   setting they set (`{ "-vsync", { "VSync", true } }`, `cmd_line.config.Set("Supersampling", val)`
 *   under `-ss`), and the help line of the option that turns a switch on describes it.
 *
 * Supermodel.ini keeps them under `[ Global ]`, and any of them under a game's own `[ <rom set> ]`,
 * which it reads over every game's. It writes a value as C++ prints it (`<<`): a switch `1` or `0`
 * (it reads `true` and `false` too), a number with six significant digits (`57.5242`). Read as a
 * Linux build compiles it; this computer's own files and folders (`ROMDirectory`, a shader's file,
 * none with a default of its own) are left out.
 */
object Supermodel {
    const val MAIN = "Src/OSD/SDL/Main.cpp"
    const val INPUTS = "Src/Inputs/InputSystem.h"
    val FILES = listOf(MAIN, INPUTS)

    private val SET = Regex("""\bconfig\s*\.\s*Set\s*(?:<\s*([\w:]+(?:\s+[\w:]+)*)\s*>)?\s*\(""")
    private val LABELS = Regex("""\bSetLabels\s*\(\s*"(\w+)"\s*,\s*\{([^}]*)\}\s*\)""")
    private val STRING = Regex(""""((?:[^"\\]|\\.)*)"""")
    private val DEFINE = Regex("""^\s*#\s*define\s+(\w+)\s+(-?\d+)\s*$""", RegexOption.MULTILINE)
    private val LIST = Regex("""\bstatic\s+const\s+char\s*\*\s*(\w+)\s*\[\s*]\s*=\s*\{([^}]*)\}""")
    private val LOOP = Regex("""\bfor\s*\(\s*const\s+char\s*\*\s*(\w+)\s*:\s*(\w+)\s*\)""")
    private val JOINED = Regex("""^std::string\s*\(\s*"(\w*)"\s*\)\s*\+\s*(\w+)\s*\+\s*"(\w*)"$""")
    private val CAST = Regex("""^(?:int|unsigned|float|bool)\s*\((.*)\)$""")

    fun parse(files: Map<String, String>): List<Found> {
        val main = Source.select(Source.withoutComments(files[MAIN] ?: return emptyList())) { false }
        val start = Regex("""Util::Config::Node\s+DefaultConfig\s*\(\s*\)\s*\{""").find(main) ?: return emptyList()
        val (body, _) = Source.arguments(main, start.range.last, '{', '}')?.let { (parts, end) -> main.substring(start.range.last, end) to parts } ?: return emptyList()
        val defines = files[INPUTS]?.let { DEFINE.findAll(it).associate { m -> m.groupValues[1] to m.groupValues[2] } }.orEmpty()
        val lists = LIST.findAll(body).associate { m -> m.groupValues[1] to STRING.findAll(m.groupValues[2]).map { it.groupValues[1] }.toList() }
        val labels = LABELS.findAll(body).associate { m -> m.groupValues[1] to STRING.findAll(m.groupValues[2]).map { it.groupValues[1] }.toList() }
        val about = help(main)
        val out = LinkedHashMap<String, Found>()
        for (m in SET.findAll(body)) {
            val (args, _) = Source.arguments(body, m.range.last) ?: continue
            val group = args.getOrNull(2)?.let(Source::string) ?: continue
            val keys = keys(args[0].trim(), body.substring(0, m.range.first), lists) ?: continue
            val raw = args[1].trim().let { CAST.matchEntire(it)?.groupValues?.get(1)?.trim() ?: it }.let { defines[it] ?: it }
            val explicit = m.groupValues[1]
            val values = args.getOrNull(5)?.trim()?.takeIf { it.startsWith("{") }?.removePrefix("{")?.removeSuffix("}")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { Source.literal(it) }
                ?.takeIf { v -> v.isNotEmpty() && v.all { it != null } }?.map { it!! }
            val min = args.getOrNull(3)?.let(Source::literal)
            val max = args.getOrNull(4)?.let(Source::literal)
            val ranged = min != null && max != null && min != max
            for (key in keys) {
                val names = labels[key]
                val float = explicit == "float" || (explicit.isEmpty() && Regex("""^-?\d*\.\d*[fF]?$""").matches(raw))
                val found = when {
                    raw == "true" || raw == "false" -> Found(null, "Global", key, Kind.BOOL, default = raw)
                    explicit == "std::string" || Source.string(raw) != null -> {
                        val default = Source.string(raw) ?: continue
                        when {
                            values != null -> Found(null, "Global", key, Kind.ENUM, default = default, choices = values.mapIndexed { i, v -> Choice(v, names?.getOrNull(i)) })
                            // A file of this computer's, with nothing set until someone picks one.
                            default.isEmpty() -> continue
                            else -> Found(null, "Global", key, Kind.STRING, default = default)
                        }
                    }
                    float -> {
                        val default = Source.literal(raw)?.let(::printed) ?: continue
                        if (values != null) {
                            Found(null, "Global", key, Kind.ENUM, default = default, choices = values.mapIndexed { i, v -> Choice(printed(v), names?.getOrNull(i)) })
                        } else Found(null, "Global", key, Kind.FLOAT, default = default)
                    }
                    else -> {
                        val default = Source.literal(raw)?.takeIf { it.toLongOrNull() != null } ?: continue
                        if (values != null) {
                            Found(null, "Global", key, Kind.ENUM, default = default, choices = values.mapIndexed { i, v -> Choice(v, names?.getOrNull(i)) })
                        } else {
                            Found(null, "Global", key, Kind.INT, default = default, min = min?.toLongOrNull()?.takeIf { ranged }, max = max?.toLongOrNull()?.takeIf { ranged })
                        }
                    }
                }
                out.putIfAbsent(found.identity, found.copy(tab = group, about = about[key]))
            }
        }
        return out.values.toList()
    }

    /**
     * The keys a `config.Set` sets: its own, or one for each name in the list a loop just before it
     * goes over (`std::string("InputJoy") + axis + "DeadZone"`).
     */
    private fun keys(arg: String, before: String, lists: Map<String, List<String>>): List<String>? {
        Source.string(arg)?.let { return listOf(it) }
        val joined = JOINED.matchEntire(arg) ?: return null
        val loop = LOOP.findAll(before).lastOrNull()?.takeIf { it.groupValues[1] == joined.groupValues[2] } ?: return null
        val names = lists[loop.groupValues[2]] ?: return null
        return names.map { joined.groupValues[1] + it + joined.groupValues[3] }
    }

    /** A number as C++ prints a float (`<<`): six significant digits, no trailing zeros. */
    private fun printed(literal: String): String =
        BigDecimal(literal.trimEnd('.').ifEmpty { "0" }.toFloat().toDouble()).round(MathContext(6)).stripTrailingZeros().toPlainString()

    /** What `-help` says of each setting, by the command line's options that set it. */
    private fun help(main: String): Map<String, String> {
        val start = Regex("""\bstatic\s+void\s+Help\s*\(\s*(?:void)?\s*\)\s*\{""").find(main) ?: return emptyMap()
        val (_, end) = Source.arguments(main, start.range.last, '{', '}') ?: return emptyMap()
        val lines = LinkedHashMap<String, StringBuilder>()
        var last: StringBuilder? = null
        for (m in Regex("""\b(?:puts|printf)\s*\(\s*"((?:[^"\\]|\\.)*)"""").findAll(main.substring(start.range.last, end))) {
            val line = Source.string("\"${m.groupValues[1]}\"").orEmpty().replace("\\n", "\n").trimEnd('\n')
            val option = Regex("""^ {2}(-[\w-]+)(?:=\S*)?\s{2,}(\S.*)$""").matchEntire(line)
            val more = Regex("""^ {20,}(\S.*)$""").matchEntire(line)
            when {
                option != null -> last = StringBuilder(option.groupValues[2]).also { lines[option.groupValues[1]] = it }
                more != null && last != null -> last.append(' ').append(more.groupValues[1])
                else -> last = null
            }
        }
        val text = lines.mapValues { (_, b) ->
            b.toString().replace(Regex("""\s*\[Default(?::[^\]]*)?]"""), "").replace(Regex("""\s*%s"""), "").trim().trimEnd('.').let { "$it." }
        }
        // The options that set a setting: those in its two tables, then those handled one by one.
        val setBy = LinkedHashMap<String, String>()
        Regex("""\{\s*"(-[\w-]+)"\s*,\s*\{\s*"(\w+)"\s*,\s*true\s*}\s*}""").findAll(main).forEach { setBy.putIfAbsent(it.groupValues[2], it.groupValues[1]) }
        Regex("""\{\s*"(-[\w-]+)"\s*,\s*"(\w+)"\s*}""").findAll(main).forEach { setBy.putIfAbsent(it.groupValues[2], it.groupValues[1]) }
        val branches = Regex("""\barg\s*==\s*"(-[\w-]+)"""").findAll(main).toList()
        branches.forEachIndexed { i, b ->
            val until = branches.getOrNull(i + 1)?.range?.first ?: main.length
            Regex("""\bcmd_line\s*\.\s*config\s*\.\s*Set\s*\(\s*"(\w+)"""").findAll(main.substring(b.range.last, until)).forEach { setBy.putIfAbsent(it.groupValues[1], b.groupValues[1]) }
        }
        return setBy.mapNotNull { (key, option) -> text[option]?.let { key to it } }.toMap()
    }
}
