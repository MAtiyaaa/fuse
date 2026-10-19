package io.github.matiyaaa.fuse.tune

/**
 * RetroArch's settings, as its own code declares them, for a game's override file
 * (`config/<core>/<game>.cfg`, which RetroArch reads over `retroarch.cfg` when the game starts):
 *
 * - configuration.c: every key in `populate_settings_bool`, `_int`, `_uint`, `_size`, `_float` and
 *   `_array`, `SETTING_BOOL("video_vsync", &settings->bools.video_vsync, true, DEFAULT_VSYNC, false)`,
 *   its default named in config.def.h (`#define DEFAULT_VSYNC true`, where it is defined once). A
 *   switch is written `true` or `false`, every value quoted (`config_file_set_bool`). Paths and
 *   folders (`populate_settings_path`, `_dir`) are this computer's, not a game's, so they are left out.
 * - menu/menu_setting.c: each `CONFIG_BOOL(list, list_info, &settings->bools.video_vsync,
 *   MENU_ENUM_LABEL_VIDEO_VSYNC, MENU_ENUM_LABEL_VALUE_VIDEO_VSYNC, ...)` names the setting by its
 *   label, its description is the same label's `SUBLABEL`, a number's range is the
 *   `menu_settings_list_current_add_range(list, list_info, min, max, ...)` after it, and its tab
 *   the group it is in (`START_GROUP(..., msg_hash_to_str(MENU_ENUM_LABEL_VALUE_VIDEO_SETTINGS), ...)`).
 * - intl/msg_hash_us.h: what each label says in English (`MSG_HASH(MENU_ENUM_LABEL_VALUE_VIDEO_VSYNC, "VSync")`).
 */
object RetroArch {
    const val CONFIG = "configuration.c"
    const val DEFAULTS = "config.def.h"
    const val MENU = "menu/menu_setting.c"
    const val WORDS = "intl/msg_hash_us.h"
    val FILES = listOf(CONFIG, DEFAULTS, MENU, WORDS)

    private val SETTING = Regex("""\bSETTING_(BOOL|INT|UINT|SIZE|FLOAT|ARRAY)\s*\(""")
    private val MENU_CALL = Regex("""\bCONFIG_(BOOL|INT|UINT|SIZE|FLOAT|STRING|STRING_OPTIONS)\s*\(""")
    private val RANGE = Regex("""menu_settings_list_current_add_range\s*\(""")
    private val GROUP = Regex("""\bSTART_GROUP\s*\(""")
    private val HASH = Regex("""\bMSG_HASH\s*\(""")
    private val DEFINE = Regex("""^\s*#\s*define\s+(\w+)\s+(.+?)\s*$""", RegexOption.MULTILINE)

    /** What a menu entry says of a setting: its name, its description, its range and its tab. */
    private data class Shown(val label: String?, val about: String?, val min: Long?, val max: Long?, val tab: String?)

    fun parse(files: Map<String, String>): List<Found> {
        val config = Source.withoutComments(files[CONFIG] ?: return emptyList())
        val defines = defines(Source.withoutComments(files[DEFAULTS].orEmpty()))
        val words = words(files[WORDS].orEmpty())
        val shown = shown(Source.withoutComments(files[MENU].orEmpty()), words)
        val out = LinkedHashMap<String, Found>()
        for (m in SETTING.findAll(config)) {
            val (args, _) = Source.arguments(config, m.range.last) ?: continue
            val key = args.getOrNull(0)?.let(Source::string) ?: continue
            val field = args.getOrNull(1)?.trim()?.removePrefix("&")?.removePrefix("settings->")?.trim() ?: continue
            val raw = args.getOrNull(3)?.trim()
            val value = raw?.let { Source.literal(it) ?: defines[it]?.let(Source::literal) }
            val kind = when (m.groupValues[1]) {
                "BOOL" -> Kind.BOOL
                "FLOAT" -> Kind.FLOAT
                "ARRAY" -> Kind.STRING
                else -> Kind.INT
            }
            val default = when (kind) {
                Kind.BOOL -> value?.takeIf { it == "true" || it == "false" }
                Kind.INT -> value?.takeIf { it.toLongOrNull() != null }
                Kind.FLOAT -> value?.takeIf { it.toDoubleOrNull() != null }
                else -> if (raw == "NULL" || raw == "\"\"") "" else value
            }
            val s = shown[field]
            val found = Found(
                null, "", key, kind, default = default, min = s?.min, max = s?.max,
                label = s?.label, about = s?.about, tab = s?.tab,
            )
            out.putIfAbsent(found.identity, found)
        }
        return out.values.toList()
    }

    /** `#define NAME value` for names defined once, their value as written. */
    private fun defines(text: String): Map<String, String> {
        val seen = HashMap<String, MutableList<String>>()
        for (m in DEFINE.findAll(text)) seen.getOrPut(m.groupValues[1]) { mutableListOf() } += m.groupValues[2].trim()
        return seen.filterValues { it.size == 1 }.mapValues { it.value.single().removeSurrounding("(", ")").trim() }
    }

    /** Every `MSG_HASH(ID, "text" "more")`: its id to its English text. */
    private fun words(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in HASH.findAll(text)) {
            val (args, _) = Source.arguments(text, m.range.last) ?: continue
            val id = args.getOrNull(0)?.trim() ?: continue
            val joined = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(args.drop(1).joinToString(" ")).joinToString("") { it.groupValues[1] }
            out.putIfAbsent(id, joined.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\"))
        }
        return out
    }

    /** What the menu says of each setting, by its field in `settings` (`bools.video_vsync`). */
    private fun shown(menu: String, words: Map<String, String>): Map<String, Shown> {
        val groups = GROUP.findAll(menu).mapNotNull { g ->
            val (args, _) = Source.arguments(menu, g.range.last) ?: return@mapNotNull null
            val label = Regex("""msg_hash_to_str\s*\(\s*(\w+)\s*\)""").find(args.getOrNull(3).orEmpty())?.groupValues?.get(1)
            g.range.first to label?.let { words[it] }
        }.toList()
        val calls = MENU_CALL.findAll(menu).toList()
        val ranges = RANGE.findAll(menu).map { it.range.first to it.range.last }.toList()
        val out = HashMap<String, Shown>()
        for ((i, c) in calls.withIndex()) {
            val (args, end) = Source.arguments(menu, c.range.last) ?: continue
            val field = args.getOrNull(2)?.trim()?.removePrefix("&")?.removePrefix("settings->")?.trim() ?: continue
            val labelId = args.getOrNull(4)?.trim()
            val sublabelId = args.getOrNull(3)?.trim()?.replace("MENU_ENUM_LABEL_", "MENU_ENUM_SUBLABEL_")
            // The range set right after this entry, before the next one is made.
            val next = calls.getOrNull(i + 1)?.range?.first ?: menu.length
            val range = ranges.firstOrNull { it.first in end until next }?.let { r ->
                Source.arguments(menu, r.second)?.first?.let { a -> a.getOrNull(2)?.let(::number) to a.getOrNull(3)?.let(::number) }
            }
            val tab = groups.lastOrNull { it.first < c.range.first }?.second
            out.putIfAbsent(field, Shown(labelId?.let { words[it] }, sublabelId?.let { words[it] }, range?.first, range?.second, tab))
        }
        return out
    }

    /** A whole number in a range's argument (`0`, `4`, `1.0f` when whole); null for anything worked out. */
    private fun number(arg: String): Long? = Source.literal(arg)?.toDoubleOrNull()?.takeIf { it == kotlin.math.floor(it) && kotlin.math.abs(it) < 1e15 }?.toLong()
}
