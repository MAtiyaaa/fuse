package io.github.matiyaaa.fuse.tune

/**
 * PCSX2's settings, from its own code. pcsx2/Pcsx2Config.cpp reads and writes them in its
 * `LoadSave` functions: `SettingsWrapSection("EmuCore/GS")` names the section the entries after it
 * are in, and each `SettingsWrapEntry(var)`, `SettingsWrapBitBool(var)` and their `Ex(var, "Key")`
 * forms is one key (the name given, else the field's own, as its macros stringify it).
 * `SettingsWrapBitBool` is a switch; `SettingsWrapEnumEx(var, "Key", Names)` an enum written as the
 * strings of its `Names[]` array; `SettingsWrapIntEnumEx` an enum written as its number, with its
 * members from the enum's declaration in pcsx2/Config.h, where an entry's field is also declared
 * with its type (`GSRendererType Renderer = GSRendererType::Auto;`).
 *
 * A default is what the options' own constructor gives the field (`Pcsx2Config::GSOptions::GSOptions()`
 * for the keys `Pcsx2Config::GSOptions::LoadSave` writes, with the functions it calls, such as
 * `DisableAll()`), else what Config.h gives it (a constant it names read there:
 * `DEFAULT_UPSCALE_MULTIPLIER`); a switch kept in bits the constructor clears
 * (`bitset = 0`) and never sets is off. PCSX2 reads a game's own settings over its EmuCore sections.
 */
object Pcsx2 {
    const val CONFIG = "pcsx2/Pcsx2Config.cpp"
    const val HEADER = "pcsx2/Config.h"
    val FILES = listOf(CONFIG, HEADER)

    private val WRAP = Regex("""\bSettingsWrap(Section|Entry|EntryEx|BitBool|BitBoolEx|Bitfield|BitfieldEx|IntEnumEx|EnumEx|ParsedEnum)\s*\(""")
    private val LOAD_SAVE = Regex("""\bvoid\s+([\w:]+)::LoadSave\w*\s*\(""")

    /** What a constructor (and the functions it calls) gives each field, and whether it clears the bits. */
    private class Defaults(val values: Map<String, String>, val bitsCleared: Boolean)

    fun parse(files: Map<String, String>): List<Found> {
        val text = Source.withoutComments(files[CONFIG] ?: return emptyList())
        val header = Source.withoutComments(files[HEADER].orEmpty())
        val enums = Source.enums(header)
        val constants = Source.constants(header)
        val names = HashMap<String, List<Choices.Entry>>()
        // Sized or not: `AspectRatioNames[(size_t)AspectRatioType::MaxCount + 1] = {`.
        for (m in Regex("""(\w+)\s*\[[^\]\n]*]\s*=\s*\{""").findAll(text)) {
            val end = text.indexOf("};", m.range.last).takeIf { it > 0 } ?: continue
            names[m.groupValues[1]] = Choices.entries(text.substring(m.range.last + 1, end))
        }
        val owners = LOAD_SAVE.findAll(text).map { it.range.first to it.groupValues[1] }.toList()
        val defaults = HashMap<String, Defaults?>()
        var section: String? = null
        val out = LinkedHashMap<String, Found>()
        for (m in WRAP.findAll(text)) {
            val (args, _) = Source.arguments(text, m.range.last) ?: continue
            val wrap = m.groupValues[1]
            if (wrap == "Section") {
                section = args.firstOrNull()?.let(Source::string)
                continue
            }
            val s = section ?: continue
            val variable = args.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val key = if (wrap.endsWith("Ex") || wrap == "ParsedEnum") args.getOrNull(1)?.let(Source::string) ?: continue else variable
            val field = variable.substringAfterLast('.')
            val declared = declaration(header, field)
            val owner = owners.lastOrNull { it.first < m.range.first }?.second
            val made = owner?.let { o -> defaults.getOrPut(o) { constructed(text, o) } }
            // The constructor's value first, then the header's.
            val raw = Source.resolve(made?.values?.get(variable) ?: declared?.second, constants)
            val found = when (wrap) {
                "BitBool", "BitBoolEx" -> Found(null, s, key, Kind.BOOL, raw?.let(Source::literal) ?: "false".takeIf { made?.bitsCleared == true })
                "Bitfield", "BitfieldEx" -> Found(null, s, key, Kind.INT, raw?.let(Source::literal))
                "EnumEx" -> {
                    val array = args.getOrNull(2)?.trim()?.substringAfterLast("::")?.let(names::get)
                    // Its names kept somewhere this doesn't read: text.
                    if (array == null) Found(null, s, key, Kind.STRING) else Found(
                        null, s, key, Kind.ENUM, choices = array.mapNotNull { e -> e.value?.let { Choice(it, hosts = e.hosts) } },
                        default = raw?.let { d -> enumIndexName(enums, declared?.first ?: d.substringBeforeLast("::"), d, array) },
                    )
                }
                "IntEnumEx" -> {
                    val type = declared?.first ?: raw?.takeIf { "::" in it }?.substringBeforeLast("::")
                    val members = type?.substringAfterLast("::")?.trim()?.let(enums::get).orEmpty()
                    val chosen = raw?.substringAfterLast("::")?.trim()
                    Found(null, s, key, Kind.ENUM, default = members.firstOrNull { it.first == chosen }?.second?.toString() ?: raw?.let(Source::literal), choices = members.map { Choice(it.second.toString(), it.first) })
                        .let { if (it.choices.isEmpty()) it.copy(kind = Kind.INT) else it }
                }
                // Read through its own parse and name functions: text, its values not listed in one place.
                "ParsedEnum" -> Found(null, s, key, Kind.STRING)
                else -> when (declared?.first?.removePrefix("std::")) {
                    "bool" -> Found(null, s, key, Kind.BOOL, raw?.let(Source::literal))
                    "float", "double" -> Found(null, s, key, Kind.FLOAT, raw?.let(Source::literal))
                    "string" -> Found(null, s, key, Kind.STRING, raw?.let(Source::string))
                    "int", "u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "uint", "int8_t", "int16_t", "int32_t", "uint8_t", "uint16_t", "uint32_t" ->
                        Found(null, s, key, Kind.INT, raw?.let(Source::literal))
                    else -> Found(null, s, key, Kind.STRING)
                }
            }
            val withGame = found.copy(perGame = s.startsWith("EmuCore"))
            out.putIfAbsent(withGame.identity, withGame)
        }
        return out.values.toList()
    }

    /** What [owner]'s constructor (`Owner::Name::Name()`) and the functions of its own it calls assign. */
    private fun constructed(text: String, owner: String): Defaults? {
        val name = owner.substringAfterLast("::")
        val body = body(text, "$owner::$name") ?: return null
        val values = LinkedHashMap<String, String>()
        var cleared = false
        fun take(code: String, depth: Int) {
            for (line in code.lines()) {
                val t = line.trim()
                Regex("""^(\w+)\s*\(\s*\)\s*;$""").find(t)?.let { call ->
                    if (depth < 2) body(text, "$owner::${call.groupValues[1]}")?.let { take(it, depth + 1) }
                }
                val a = Regex("""^([\w.]+)(\[\d+])?\s*=\s*([^;]+);$""").find(t) ?: continue
                val target = a.groupValues[1]
                if (target == "bitset" || target == "bitsets") cleared = true else values[target] = a.groupValues[3].trim()
            }
        }
        take(body, 0)
        return Defaults(values, cleared)
    }

    /** The body of the function [qualified] (`Pcsx2Config::GSOptions::GSOptions`), between its braces. */
    private fun body(text: String, qualified: String): String? {
        val m = Regex("""(?m)^[\w:&<>* ]*?\b${Regex.escape(qualified)}\s*\(\s*\)\s*(?::[^{]*)?\{""").find(text) ?: return null
        var depth = 0
        for (i in m.range.last until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(m.range.last + 1, i)
            }
        }
        return null
    }

    /** A field's declared type and default in pcsx2/Config.h (`float Zoom = 100.0f;`), when it is declared plainly. */
    private fun declaration(header: String, field: String): Pair<String, String?>? {
        val m = Regex("""(?m)^\s*(?:static\s+)?(?:constexpr\s+)?([\w:<>]+)\s+$field\s*(?:=\s*([^;,{]+?))?\s*;""").find(header) ?: return null
        return m.groupValues[1] to m.groupValues[2].takeIf { it.isNotBlank() }
    }

    /** An enum default written by name (`AspectRatioType::RAuto4_3_3_2`): the name the array has at its position. */
    private fun enumIndexName(enums: Map<String, List<Pair<String, Long>>>, type: String, default: String, names: List<Choices.Entry>): String? {
        val members = enums[type.substringAfterLast("::").trim()] ?: return null
        val at = members.indexOfFirst { it.first == default.substringAfterLast("::").trim() }
        return names.getOrNull(at)?.value
    }
}
