package io.github.matiyaaa.fuse.tune

/**
 * The settings of Azahar (Citra's line for the Nintendo 3DS), from its own code:
 *
 * - src/common/settings.h: `SwitchableSetting<s32, true> cpu_clock_percentage{100, 5, 400,
 *   Keys::cpu_clock_percentage};`, each with its default, a ranged one's minimum and maximum, and its
 *   key (CMakeModules/GenerateSettingKeys.cmake makes each `Keys::` name the string of itself), and
 *   the enums they take (`enum class GraphicsAPI { Software = 0, OpenGL = 1, Vulkan = 2 }`), written
 *   as each member's number (`QtConfig::WriteBasicSetting`).
 * - src/citra_qt/configuration/config.cpp (`QtConfig`, or `Config` in older builds of the family): the group each is kept under (`qt_config->beginGroup(
 *   QStringLiteral("Renderer"))` before `ReadGlobalSetting(Settings::values.use_hw_shader)`), and
 *   which a game's own file holds: only those read with `ReadGlobalSetting` in the groups
 *   `QtConfig::ReadValues` reads for a game too (not those inside its `if (global)`).
 *
 * - What its settings screens call each (its Android app's, the one with words for each setting in
 *   one place): src/android/.../settings/ui/SettingsFragmentPresenter.kt (`SwitchSetting(
 *   BooleanSetting.NEW_3DS, R.string.new_3ds, R.string.new_3ds_description, ...)`, a choice's
 *   names and values as `R.array.regionNames, R.array.regionValues`), the setting each names by its
 *   key (model/BooleanSetting.kt and the rest: `NEW_3DS(SettingKeys.is_new_3ds(), ...)`), and the
 *   words themselves (res/values/strings.xml and arrays.xml).
 *
 * Its files are Qt's own ini (`QSettings::IniFormat`): a group with a space is written with it as
 * `%20` (`[Data%20Storage]`), and a value keeps `key\default` beside it in the file for every game
 * and `key\use_global` in a game's own file.
 */
object Azahar {
    const val SETTINGS = "src/common/settings.h"
    private const val ANDROID = "src/android/app/src/main"
    const val STRINGS = "$ANDROID/res/values/strings.xml"
    const val ARRAYS = "$ANDROID/res/values/arrays.xml"

    /**
     * Where a member of the family keeps these: its Qt frontend's folder (`citra_qt`, Lime3DS's
     * `lime_qt`, Mandarine's `mandarine_qt`) and its Android app's package folder.
     */
    class Layout(qt: String, android: String) {
        val config = "src/$qt/configuration/config.cpp"
        val screens = "$ANDROID/java/$android/features/settings/ui/SettingsFragmentPresenter.kt"
        val models = listOf("BooleanSetting", "IntSetting", "FloatSetting", "StringSetting").map { "$ANDROID/java/$android/features/settings/model/$it.kt" }
        val files = listOf(SETTINGS, config, screens, STRINGS, ARRAYS) + models
        fun parse(files: Map<String, String>): List<Found> = parse(files, this)
    }

    /** Azahar's, and AzaharPlus's (a fork that keeps Azahar's folders in its code). */
    val AZAHAR = Layout("citra_qt", "org/citra/citra_emu")
    val LIME3DS = Layout("lime_qt", "io/github/lime3ds/android")
    val MANDARINE = Layout("mandarine_qt", "io/github/mandarine3ds/mandarine")

    val CONFIG = AZAHAR.config
    val SCREENS = AZAHAR.screens
    val MODELS = AZAHAR.models
    val FILES = AZAHAR.files

    /** A setting on a screen: its enum's name and member (`BooleanSetting.NEW_3DS`), its words' names, and a choice's names and values. */
    private val ON_SCREEN = Regex(
        """\b(?:SwitchSetting|SingleChoiceSetting|SliderSetting|StringInputSetting|StringSingleChoiceSetting)\s*\(\s*(\w+Setting\.\w+)\s*,\s*R\.string\.(\w+)\s*,\s*(?:R\.string\.(\w+)|0)(?:\s*,\s*R\.array\.(\w+)\s*,\s*R\.array\.(\w+))?""",
    )
    private val MODEL = Regex("""^\s*(\w+)\s*\(\s*SettingKeys\.(\w+)\s*\(\s*\)""", RegexOption.MULTILINE)

    private val DECLARATION = Regex("""\b(SwitchableSetting|Setting)\s*<\s*([\w:<>]+?)\s*(?:,\s*(true|false)\s*)?>\s*(\w+)\s*\{""")
    private val READ = Regex("""\b(ReadGlobalSetting|ReadBasicSetting)\s*\(\s*Settings::values\.(\w+)\s*\)""")
    /** A group begun (its name when it is a literal, else empty: kept for the count, never a section) or ended. */
    private val GROUP = Regex("""beginGroup\s*\((?:\s*QStringLiteral\s*\(\s*"([^"]*)"\s*\))?[^;]*;|endGroup\s*\(\s*\)""")
    private val FUNCTION = Regex("""\bvoid\s+(?:Qt)?Config::(Read\w+Values)\s*\(\s*\)\s*\{""")
    private val INTEGERS = setOf("u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "int", "unsigned", "long", "size_t")
    private val DECIMALS = setOf("f32", "f64", "float", "double")

    /** Where a setting is read: its group and whether a game's own file can hold it. */
    private data class Read(val group: String, val perGame: Boolean)

    fun parse(files: Map<String, String>): List<Found> = parse(files, AZAHAR)

    fun parse(files: Map<String, String>, layout: Layout): List<Found> {
        val settings = Source.withoutComments(files[SETTINGS] ?: return emptyList())
        val reads = reads(Source.withoutComments(files[layout.config] ?: return emptyList()))
        val enums = Source.enums(settings)
        val out = LinkedHashMap<String, Found>()
        for (d in DECLARATION.findAll(settings)) {
            val (args, _) = Source.arguments(settings, d.range.last, '{', '}') ?: continue
            val keyAt = args.indexOfLast { it.trim().startsWith("Keys::") || Source.string(it) != null }.takeIf { it >= 1 } ?: continue
            val key = args[keyAt].trim().removePrefix("Keys::").let { Source.string(it) ?: it }
            val member = d.groupValues[4]
            val read = reads[member] ?: continue
            val type = d.groupValues[2].substringAfterLast("::")
            val raw = args[0].trim()
            val ranged = keyAt >= 3
            val perGame = d.groupValues[1] == "SwitchableSetting" && read.perGame
            // QSettings writes a group with a space as `%20` (QSettingsPrivate::iniEscapedKey).
            val section = read.group.replace(" ", "%20")
            val tab = read.group
            val found = when {
                type == "bool" -> Found(null, section, key, Kind.BOOL, default = raw.takeIf { it == "true" || it == "false" }, perGame = perGame, tab = tab)
                type in INTEGERS -> Found(
                    null, section, key, Kind.INT, default = Source.literal(raw)?.takeIf { it.toLongOrNull() != null },
                    min = if (ranged) Source.literal(args[1])?.toLongOrNull() else null, max = if (ranged) Source.literal(args[2])?.toLongOrNull() else null,
                    perGame = perGame, tab = tab,
                )
                type in DECIMALS -> Found(null, section, key, Kind.FLOAT, default = Source.literal(raw)?.takeIf { it.toDoubleOrNull() != null }, perGame = perGame, tab = tab)
                type == "string" -> Found(null, section, key, Kind.STRING, default = Source.string(raw), perGame = perGame, tab = tab)
                enums.containsKey(type) -> {
                    val members = enums.getValue(type)
                    val default = raw.substringAfterLast("::").let { m -> members.firstOrNull { it.first == m }?.second?.toString() }
                    Found(null, section, key, Kind.ENUM, default = default, choices = members.map { (name, value) -> Choice(value.toString(), name) }, perGame = perGame, tab = tab)
                }
                else -> continue
            }
            out.putIfAbsent(found.identity, found)
        }
        return withWords(out.values.toList(), files, layout)
    }

    /** [found] with what Azahar's settings screens call each setting and its choices, where they name it. */
    private fun withWords(found: List<Found>, files: Map<String, String>, layout: Layout): List<Found> {
        val screens = files[layout.screens] ?: return found
        val strings = AndroidResources.strings(files[STRINGS].orEmpty())
        val arrays = AndroidResources.arrays(files[ARRAYS].orEmpty(), strings)
        // `BooleanSetting.NEW_3DS` to its key, from each model's own enum.
        val keys = layout.models.flatMap { path ->
            val kind = path.substringAfterLast('/').removeSuffix(".kt")
            MODEL.findAll(files[path].orEmpty()).map { "$kind.${it.groupValues[1]}" to it.groupValues[2] }.toList()
        }.toMap()
        data class Words(val label: String?, val about: String?, val choices: Map<String, String>)
        val words = HashMap<String, Words>()
        for (m in ON_SCREEN.findAll(screens)) {
            val key = keys[m.groupValues[1]] ?: continue
            val names = arrays[m.groupValues[4]].orEmpty()
            val values = arrays[m.groupValues[5]].orEmpty()
            val choices = if (names.size == values.size) values.zip(names).toMap() else emptyMap()
            words.putIfAbsent(key, Words(strings[m.groupValues[2]], m.groupValues[3].takeIf { it.isNotEmpty() }?.let(strings::get), choices))
        }
        return found.map { f ->
            val w = words[f.key] ?: return@map f
            f.copy(
                label = w.label ?: f.label,
                about = w.about ?: f.about,
                choices = f.choices.map { c -> w.choices[c.value]?.let { c.copy(label = it) } ?: c },
            )
        }
    }

    /**
     * Each member `QtConfig` reads, with its group and whether a game's file holds it: read with
     * `ReadGlobalSetting`, outside `if (global)`, in a function `ReadValues` calls for a game too.
     */
    private fun reads(config: String): Map<String, Read> {
        val forGames = forGames(config)
        val out = HashMap<String, Read>()
        for (f in FUNCTION.findAll(config)) {
            val name = f.groupValues[1]
            if (name == "ReadValues") continue
            val (body, _) = block(config, f.range.last) ?: continue
            val groups = ArrayList<String>()
            val globalOnly = globalBlocks(body)
            val events = (GROUP.findAll(body).map { it.range.first to it } + READ.findAll(body).map { it.range.first to it }).sortedBy { it.first }
            for ((at, m) in events) {
                when {
                    m.value.startsWith("beginGroup") -> groups += m.groupValues[1]
                    m.value.startsWith("endGroup") -> if (groups.isNotEmpty()) groups.removeAt(groups.lastIndex)
                    // Only a setting kept directly in a group, as an ini section of its own.
                    groups.size == 1 && groups.single().isNotEmpty() -> {
                        val game = m.groupValues[1] == "ReadGlobalSetting" && name in forGames && globalOnly.none { at in it }
                        out.putIfAbsent(m.groupValues[2], Read(groups.single(), game))
                    }
                }
            }
        }
        return out
    }

    /** The functions `ReadValues` calls for a game's own file too: those after its `if (global)` block. */
    private fun forGames(config: String): Set<String> {
        val start = Regex("""void\s+(?:Qt)?Config::ReadValues\s*\(\s*\)\s*\{""").find(config) ?: return emptySet()
        val (body, _) = block(config, start.range.last) ?: return emptySet()
        val global = globalBlocks(body)
        return Regex("""\b(Read\w+Values)\s*\(\s*\)""").findAll(body).filter { m -> global.none { m.range.first in it } }.map { it.groupValues[1] }.toSet()
    }

    /** Where each `if (global) { ... }` block of [body] is. */
    private fun globalBlocks(body: String): List<IntRange> = Regex("""if\s*\(\s*global\s*\)\s*\{""").findAll(body).mapNotNull { m ->
        block(body, m.range.last)?.let { (_, end) -> m.range.first..end }
    }.toList()

    /** The text inside the brace at [open] and where its closing brace is. */
    private fun block(text: String, open: Int): Pair<String, Int>? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, i) to i
                '"' -> { i++; while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1 }
            }
            i++
        }
        return null
    }
}
