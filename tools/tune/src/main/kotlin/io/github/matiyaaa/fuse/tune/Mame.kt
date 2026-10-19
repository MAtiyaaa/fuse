package io.github.matiyaaa.fuse.tune

/**
 * The options of MAME, from its own code: the option tables (`s_option_entries[]`) of
 * src/emu/emuopts.cpp, src/osd/modules/lib/osdobj_common.cpp and src/osd/sdl/sdlopts.cpp (the
 * build for Linux and macOS), each `{ OPTION_SKIP_GAMEINFO, "0", core_options::option_type::BOOLEAN,
 * "skip information screen at startup" }` with its name from the matching header's `#define`, its
 * default, kind and description, and the header it is listed under (`option_type::HEADER`), which
 * `core_options::output_ini` writes as a comment above them. A system's own `<short name>.ini` can
 * hold any of them. Paths (`PATH`, `MULTIPATH`) are this computer's own and commands aren't
 * settings, so neither is offered; switches are written `1` and `0`.
 */
object Mame {
    val FILES = listOf(
        "src/emu/emuopts.cpp", "src/emu/emuopts.h",
        "src/osd/modules/lib/osdobj_common.cpp", "src/osd/modules/lib/osdobj_common.h",
        "src/osd/sdl/sdlopts.cpp", "src/osd/sdl/sdlopts.h",
    )

    private val TABLE = Regex("""\boptions_entry\s+[\w:]*\s*\[\s*\]\s*=\s*\{""")
    private val DEFINE = Regex("""^\s*#\s*define\s+(\w+)\s+"([^"]*)"""", RegexOption.MULTILINE)

    fun parse(files: Map<String, String>): List<Found> {
        val defines = files.filterKeys { it.endsWith(".h") }.values.flatMap { DEFINE.findAll(it).map { m -> m.groupValues[1] to m.groupValues[2] }.toList() }.toMap()
        val out = LinkedHashMap<String, Found>()
        for (path in FILES.filter { it.endsWith(".cpp") }) {
            // As a Linux build has it: an X11 one, not Windows' or macOS's own parts.
            val text = Source.select(Source.withoutComments(files[path] ?: continue)) { it == "SDLMAME_X11" || it == "SDLMAME_LINUX" || it == "SDLMAME_UNIX" }
            for (t in TABLE.findAll(text)) {
                val (rows, _) = Source.arguments(text, t.range.last, '{', '}') ?: continue
                var header: String? = null
                for (row in rows) {
                    val r = row.trim()
                    if (!r.startsWith("{")) continue
                    val (args, _) = Source.arguments(r, 0, '{', '}') ?: continue
                    if (args.size < 4) continue
                    val type = args[2].trim().substringAfterLast("::")
                    if (type == "HEADER") {
                        header = Source.string(args[3])?.let(::titled)
                        continue
                    }
                    // The name before any `;alias`: a macro, with the aliases as a string piece after it.
                    val name = args[0].trim().substringBefore(' ').let { defines[it] ?: Source.string(it) } ?: continue
                    val raw = args[1].trim()
                    val default = if (raw == "nullptr") null else Source.string(raw) ?: defines[raw]
                    val about = Source.string(args[3])
                    val found = when (type) {
                        "BOOLEAN" -> Found(null, "", name, Kind.BOOL, default = default?.let { if (it == "1") "true" else "false" }, about = about, tab = header)
                        "INTEGER" -> Found(null, "", name, Kind.INT, default = default?.takeIf { it.toLongOrNull() != null }, about = about, tab = header)
                        "FLOAT" -> Found(null, "", name, Kind.FLOAT, default = default?.takeIf { it.toDoubleOrNull() != null }, about = about, tab = header)
                        // A string with no default is a command's argument (the system's name), not a setting.
                        "STRING" -> if (default == null) continue else Found(null, "", name, Kind.STRING, default = default, about = about, tab = header)
                        else -> continue
                    }
                    out.putIfAbsent(found.identity, found)
                }
            }
        }
        return out.values.toList()
    }

    /** A header as words: `CORE STATE/PLAYBACK OPTIONS` is Core State/Playback Options. */
    private fun titled(s: String): String = Regex("""[A-Za-z]+""").replace(s.lowercase()) { m ->
        if (m.value in setOf("sdl", "osd", "opengl", "bgfx", "hlsl", "ui", "http", "midi")) m.value.uppercase() else m.value.replaceFirstChar { it.uppercase() }
    }
}
