package io.github.matiyaaa.fuse.launch.settings

/**
 * Where RetroArch 1.22.2 keeps a libretro core's options (runloop.c): a game's own in
 * `<config folder>/<core's library name>/<content>.opt` (`validate_game_options`), a folder's in
 * `<folder name>.opt` beside it (`validate_folder_options`, the name of the folder the content is
 * in), the core's for every game in `<core>/<core>.opt` (`validate_per_core_options`), and, with
 * `global_core_options` on (or while the core's own file isn't made yet), `core_options_path` or
 * `retroarch-core-options.cfg` beside retroarch.cfg (`runloop_init_core_options_path`). Each is
 * `key = "value"` ([CfgText]). The config folder is `rgui_config_directory`.
 */
object CoreOptionFiles {
    /** Fuse Tune's family for [core]'s options, by its file's name (`snes9x` is `core.snes9x`). */
    fun family(core: String): String = "core.$core"

    /** A game's own options, by its content's file name without the extension. */
    fun gameFile(config: String, library: String, content: String): String =
        "$config/$library/${io.github.matiyaaa.fuse.launch.patches.RetroArchCheats.contentName(content)}.opt"

    /** The options of every game in [content]'s folder; null when it names no folder. */
    fun folderFile(config: String, library: String, content: String): String? =
        content.replace('\\', '/').substringBeforeLast('/', "").substringAfterLast('/').takeIf { it.isNotEmpty() }?.let { "$config/$library/$it.opt" }

    /** The core's options for every game. */
    fun coreFile(config: String, library: String): String = "$config/$library/$library.opt"

    /**
     * Every core's options in one file: [coreOptionsPath] (`core_options_path`) when it is a path
     * of its own, else `retroarch-core-options.cfg` in [retroarchCfg]'s folder.
     */
    fun globalFile(coreOptionsPath: String?, retroarchCfg: String): String =
        coreOptionsPath?.trim()?.takeIf { it.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(it) }?.replace('\\', '/')
            ?: "${retroarchCfg.replace('\\', '/').substringBeforeLast('/')}/retroarch-core-options.cfg"
}
