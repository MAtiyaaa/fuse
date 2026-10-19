package io.github.matiyaaa.fuse.tune

/**
 * The settings of Xenia Canary (the Xbox 360), from its own code: every setting it saves is a
 * `DEFINE_bool(vsync, true, "Enable VSYNC.", "GPU");` (or `DEFINE_int32`, `uint32`, `uint64`,
 * `double`, `string`) somewhere in src/xenia, with its default, its description and the category
 * it is kept under (src/xenia/base/cvar.h). `config::SaveConfig` (src/xenia/config.cc) writes each
 * as `name = value` under `[category]` in `xenia-canary.config.toml`, and `ReadGameConfig` reads any
 * of them from a game's `config/<title id>.config.toml`. `DEFINE_transient_*` ones are only for the
 * command line, and `DEFINE_path` ones are this computer's own folders; neither is offered.
 */
object Xenia {
    /** The sources read: every `.cc` file under src/xenia. */
    val TREE = listOf("src/xenia/**/*.cc")

    private val DEFINE = Regex("""\bDEFINE_(bool|int32|uint32|int64|uint64|double|string)\s*\(""")
    private val INTEGERS = setOf("int32", "uint32", "int64", "uint64")

    fun parse(files: Map<String, String>): List<Found> {
        val out = LinkedHashMap<String, Found>()
        for ((_, text) in files.toSortedMap()) {
            val clean = Source.withoutComments(text)
            for (d in DEFINE.findAll(clean)) {
                val (args, _) = Source.arguments(clean, d.range.last) ?: continue
                if (args.size < 4) continue
                val key = args[0].trim().takeIf { Regex("^\\w+$").matches(it) } ?: continue
                val section = Source.string(args[3]) ?: continue
                val about = text(args[2])
                val raw = args[1].trim()
                val kind = d.groupValues[1]
                val found = when {
                    kind == "bool" -> Found(null, section, key, Kind.BOOL, default = raw.takeIf { it == "true" || it == "false" }, about = about)
                    kind in INTEGERS -> Found(
                        null, section, key, Kind.INT, default = Source.literal(raw)?.takeIf { it.toLongOrNull() != null },
                        min = if (kind.startsWith("u")) 0L else null, about = about,
                    )
                    kind == "double" -> Found(null, section, key, Kind.FLOAT, default = Source.literal(raw)?.takeIf { it.toDoubleOrNull() != null }, about = about)
                    else -> Found(null, section, key, Kind.STRING, default = text(raw), about = about)
                }
                out.putIfAbsent(found.identity, found.copy(tab = section))
            }
        }
        return out.values.toList()
    }

    /** A C string literal, or pieces of one written side by side over several lines, as its text. */
    private fun text(arg: String): String? {
        val pieces = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(arg).toList().map { Source.string("\"${it.groupValues[1]}\"") ?: return null }
        if (pieces.isEmpty()) return null
        // Only string pieces, nothing else between them.
        if (arg.replace(Regex(""""(?:[^"\\]|\\.)*""""), "").isNotBlank()) return null
        return pieces.joinToString("").replace("\\n", "\n")
    }
}
