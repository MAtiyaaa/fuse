package io.github.matiyaaa.fuse.launch.settings

/**
 * Eden's settings files, as Eden v0.2.1 reads and writes them (src/frontend_common/config.cpp):
 * `qt-config.ini` in its config folder for every game (`Config::Initialize`, the Qt frontend's
 * `qt-config`), and `custom/<title id>.ini` for one game (`ConfigType::PerGameConfig`, its title id
 * as 16 upper-case hex digits), both SimpleIni files without spaces around `=`.
 *
 * A value is never one key. `Config::WriteSettingGeneric` writes `key\default` (whether it is the
 * default) beside `key`, and in a game's file `key\use_global` first, the other two only when it is
 * false. `Config::ReadSettingGeneric` reads them back the same way: a game follows every game's
 * value unless `key\use_global` is false, and a value whose `key\default` is true (or missing) is the
 * default, whatever `key` holds. Each key is changed and recorded on its own (see [writes]), so
 * putting it back puts back exactly what each of the three held.
 */
object EdenConfig {
    /** The Qt frontend's file for every game, in Eden's config folder. */
    const val GLOBAL = "qt-config.ini"

    fun globalFile(config: String): String = "$config/$GLOBAL"

    /** A game's own file, by its title id (`0100F2C0115B6000`). */
    fun gameFile(config: String, titleId: String): String = "$config/custom/${titleId.uppercase()}.ini"

    /**
     * What [file] gives [key] in [section] as Eden reads it: for a game's file ([game]) only when it
     * has its own value, else null (it follows every game's); for the file for every game null when
     * it is the default. A value marked as the default is [default] (or, not known, what `key` holds).
     */
    fun value(file: SettingsText, section: String, key: String, game: Boolean, default: String?): String? {
        fun bool(k: String) = file.values(section, k).lastOrNull()?.let(::simpleIniBool)
        if (game && bool("$key\\use_global") != false) return null
        val written = file.values(section, key).lastOrNull()
        return if (bool("$key\\default") != false) {
            if (game) default ?: written else null
        } else {
            written ?: default.takeIf { game }
        }
    }

    /**
     * The keys one change writes, each with its value (null takes it out): a game's own value is
     * `key\use_global=false`, `key\default` and `key`, and taking it out again takes the three out
     * (a missing `use_global` is true); every game's is `key\default` and `key`.
     */
    fun writes(section: String, key: String, value: String?, game: Boolean, default: String?): List<Triple<String, String, String?>> {
        val isDefault = value?.let { (it == default).toString() }
        return listOfNotNull(
            if (game) Triple(section, "$key\\use_global", value?.let { "false" }) else null,
            Triple(section, "$key\\default", isDefault),
            Triple(section, key, value),
        )
    }

    /** SimpleIni's `GetBoolValue`: `t`, `y`, `1` or `on` is true, `f`, `n`, `0` or `off` false, anything else unknown. */
    private fun simpleIniBool(v: String): Boolean? = when (v.firstOrNull()?.lowercaseChar()) {
        't', 'y', '1' -> true
        'f', 'n', '0' -> false
        'o' -> when (v.getOrNull(1)?.lowercaseChar()) {
            'n' -> true
            'f' -> false
            else -> null
        }
        else -> null
    }
}
