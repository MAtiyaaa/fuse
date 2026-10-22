package io.github.matiyaaa.fuse.tune

/**
 * The settings of the Retro Engine decompilations (RSDKv3's Sonic CD, RSDKv4's Sonic 1 and 2), from
 * their own code: with no settings.ini yet, `InitUserdata` in Userdata.cpp writes every key with its
 * default, `ini.SetBool("Window", "VSync", Engine.vsync = false)`, its section, key and the value it
 * gives (the last `=`'s right, read through the `#define`s: `DEFAULT_FULLSCREEN`). `SetBool` is a
 * switch (written `true` or `false`, Ini.cpp), `SetInteger` a number, `SetFloat` a decimal and
 * `SetString` text. The keyboard's and controllers' button numbers are left out (each is a key or
 * button code the game's own menu sets); a stick's dead zone is kept.
 */
object Rsdk {
    fun files(dir: String) = listOf("$dir/Userdata.cpp", "$dir/RetroEngine.hpp")

    private val SET = Regex("""\bini\s*\.\s*Set(Bool|Integer|Float|String)\s*\(""")
    private val DEFINE = Regex("""^\s*#\s*define\s+(\w+)\s+(\S+)""", RegexOption.MULTILINE)

    fun parse(files: Map<String, String>): List<Found> {
        val code = files.filterKeys { it.endsWith("Userdata.cpp") }.values.firstOrNull()?.let(Source::withoutComments) ?: return emptyList()
        val defines = files.values.flatMap { DEFINE.findAll(Source.withoutComments(it)).map { m -> m.groupValues[1] to m.groupValues[2] }.toList() }.toMap()
        val out = LinkedHashMap<String, Found>()
        for (m in SET.findAll(code)) {
            val (args, _) = Source.arguments(code, m.range.last) ?: continue
            val section = args.getOrNull(0)?.let(Source::string) ?: continue
            val key = args.getOrNull(1)?.let(Source::string) ?: continue
            if ((section.startsWith("Keyboard") || section.startsWith("Controller")) && "Deadzone" !in key) continue
            val raw = args.getOrNull(2)?.substringAfterLast('=')?.trim()?.removePrefix("(char *)")?.trim()
            val value = raw?.let { Source.literal(it) ?: defines[it]?.let(Source::literal) }
            val found = when (m.groupValues[1]) {
                "Bool" -> Found(null, section, key, Kind.BOOL, default = value?.takeIf { it == "true" || it == "false" }, perGame = false)
                "Integer" -> Found(null, section, key, Kind.INT, default = value?.takeIf { it.toLongOrNull() != null }, perGame = false)
                "Float" -> Found(null, section, key, Kind.FLOAT, default = value?.takeIf { it.toDoubleOrNull() != null }, perGame = false)
                else -> Found(null, section, key, Kind.STRING, default = raw?.let(Source::string), perGame = false)
            }
            out.putIfAbsent(found.identity, found)
        }
        return out.values.toList()
    }
}
