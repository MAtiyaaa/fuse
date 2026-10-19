package io.github.matiyaaa.fuse.launch.settings

/**
 * Azahar's settings files, as Azahar 2126.2 reads and writes them through Qt
 * (src/citra_qt/configuration/config.cpp): `qt-config.ini` in its config folder for every game
 * (`QtConfig`'s default name) and `custom/<title id>.ini` for one game (`ConfigurePerGame`, the
 * title id as 16 upper-case hex digits), both `QSettings::IniFormat`, written without spaces
 * around `=`.
 *
 * `QtConfig::WriteBasicSetting` writes `key\default` beside `key` only in the file for every game,
 * and `WriteGlobalSetting` writes `key\use_global` first in a game's file, then the value only when
 * it is false. Reading (`ReadBasicSetting`, `ReadGlobalSetting`): a game follows every game's value
 * unless `key\use_global` is false; a value whose `key\default` is true is the default, and one
 * without it is what `key` holds. Each key is changed and recorded on its own (see [writes]).
 */
object AzaharConfig {
    /** Qt's file for every game, in Azahar's config folder. */
    const val GLOBAL = "qt-config.ini"

    fun globalFile(config: String): String = "$config/$GLOBAL"

    /** A game's own file, by its title id (`0004000000055D00`). */
    fun gameFile(config: String, titleId: String): String = "$config/custom/${titleId.uppercase()}.ini"

    /**
     * What [file] gives [key] in [section] as Azahar reads it: for a game's file ([game]) only when
     * it has its own value, else null; for the file for every game null when it is the default.
     */
    fun value(file: SettingsText, section: String, key: String, game: Boolean, default: String?): String? {
        fun bool(k: String) = file.values(section, k).lastOrNull()?.let(::qtBool)
        if (game && bool("$key\\use_global") != false) return null
        val written = file.values(section, key).lastOrNull()
        return when {
            bool("$key\\default") == true -> if (game) default ?: written else null
            game -> written ?: default
            else -> written
        }
    }

    /**
     * The keys one change writes, each with its value (null takes it out): a game's own value is
     * `key\use_global=false` and `key`; every game's is `key\default` and `key`.
     */
    fun writes(section: String, key: String, value: String?, game: Boolean, default: String?): List<Triple<String, String, String?>> =
        if (game) {
            listOf(Triple(section, "$key\\use_global", value?.let { "false" }), Triple(section, key, value))
        } else {
            listOf(Triple(section, "$key\\default", value?.let { (it == default).toString() }), Triple(section, key, value))
        }

    /** `QVariant::toBool` of a string: false for an empty one, "0" or "false" (any case), true otherwise. */
    private fun qtBool(v: String): Boolean = !(v.isEmpty() || v == "0" || v.equals("false", ignoreCase = true))
}
