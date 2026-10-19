package io.github.matiyaaa.fuse.tune

/**
 * Dolphin's settings, from its own code: every `const Info<T> NAME{{System::X, "Section", "Key"},
 * default};` in Source/Core/Core/Config/GraphicsSettings.cpp (System::GFX, kept in GFX.ini) and
 * MainSettings.cpp (System::Main, Dolphin.ini); the members of the enums they use, from
 * Source/Core/VideoCommon/VideoConfig.h and Source/Core/Core/PowerPC/PowerPC.h, which Dolphin
 * writes as their numbers; and which sections a game's own file can hold, from the table in
 * Source/Core/Core/ConfigLoaders/GameConfigLoader.cpp (`{"Video_Settings", {Config::System::GFX,
 * "Settings"}}`).
 */
object Dolphin {
    const val GFX = "Source/Core/Core/Config/GraphicsSettings.cpp"
    const val MAIN = "Source/Core/Core/Config/MainSettings.cpp"
    const val GAME = "Source/Core/Core/ConfigLoaders/GameConfigLoader.cpp"
    val ENUMS = listOf("Source/Core/VideoCommon/VideoConfig.h", "Source/Core/Core/PowerPC/PowerPC.h")
    val FILES = listOf(GFX, MAIN, GAME) + ENUMS

    private val INFO = Regex("""const\s+Info<\s*([\w:<>, ]+?)\s*>\s+\w+\s*\{\s*\{\s*System::(\w+)\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\}\s*,\s*([\s\S]*?)\}\s*;""")
    private val GAME_SECTION = Regex("""\{\s*"(\w+)"\s*,\s*\{\s*Config::System::(\w+)\s*,\s*"(\w+)"\s*\}\s*\}""")

    fun parse(files: Map<String, String>): List<Found> {
        val enums = ENUMS.mapNotNull { files[it] }.fold(LinkedHashMap<String, List<Pair<String, Long>>>()) { m, t -> m.apply { putAll(Source.enums(t)) } }
        // A game's file section for each of Dolphin's own sections, by system.
        val gameSections = HashMap<Pair<String, String>, String>()
        files[GAME]?.let { t -> GAME_SECTION.findAll(Source.withoutComments(t)).forEach { m -> gameSections.putIfAbsent(m.groupValues[2] to m.groupValues[3], m.groupValues[1]) } }
        val out = ArrayList<Found>()
        for (path in listOf(GFX, MAIN)) {
            val text = Source.withoutComments(files[path] ?: continue)
            for (m in INFO.findAll(text)) {
                val (type, system, section, key, rawDefault) = m.destructured
                if (key.isEmpty()) continue
                val file = when (system) {
                    "GFX" -> "GFX"
                    "Main" -> null
                    else -> continue
                }
                val default = rawDefault.trim()
                val found = when (val t = type.trim().removePrefix("std::")) {
                    "bool" -> Found(file, section, key, Kind.BOOL, Source.literal(default)?.let(::dolphinBool))
                    "int", "u32", "s32", "u16", "s16", "u8", "s8", "u64", "s64" -> Found(file, section, key, Kind.INT, Source.literal(default))
                    "float", "double" -> Found(file, section, key, Kind.FLOAT, Source.literal(default))
                    "string" -> Found(file, section, key, Kind.STRING, Source.string(default) ?: default.takeIf { it == "\"\"" }?.let { "" })
                    else -> {
                        // An enum: Dolphin writes its number; the members are its choices.
                        val name = t.substringAfterLast("::")
                        val members = enums[name].orEmpty()
                        val chosen = default.substringAfterLast("::").trim()
                        Found(
                            file, section, key, Kind.ENUM,
                            default = members.firstOrNull { it.first == chosen }?.second?.toString(),
                            choices = members.map { Choice(it.second.toString(), it.first) },
                        )
                    }
                }
                val game = gameSections[system to section]
                out += found.copy(perGame = game != null, gameSection = game?.takeIf { it != section })
            }
        }
        return out
    }

    /** Dolphin writes switches as `True` and `False`. */
    private fun dolphinBool(v: String) = if (v == "true") "True" else "False"
}
