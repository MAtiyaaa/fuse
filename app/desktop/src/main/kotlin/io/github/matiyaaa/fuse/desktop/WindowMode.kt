package io.github.matiyaaa.fuse.desktop

import java.io.File
import java.util.Properties

/** How Fuse's window sits on the screen. */
enum class WindowMode(val flag: String, val key: String) {
    /** Covers the whole screen, panels included. The default, like a console. */
    FULLSCREEN("--fullscreen", "fullscreen"),

    /** A maximized window without decorations; panels stay visible. */
    BORDERLESS("--borderless", "borderless"),

    /** An ordinary decorated window. */
    WINDOWED("--windowed", "windowed"),
    ;

    companion object {
        /** The last window flag on the command line, or null. Other arguments are ignored. */
        fun fromArgs(args: List<String>): WindowMode? = args.lastOrNull { a -> entries.any { it.flag == a } }
            ?.let { a -> entries.first { it.flag == a } }

        fun fromKey(key: String?): WindowMode? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The window mode, kept in `~/.config/fuse/window.properties` (a desktop-only preference the shared
 * settings don't model). [mode] is what to start in; [base] is what leaving fullscreen goes back to.
 */
class WindowPrefs(private val file: File) {
    data class Saved(val mode: WindowMode, val base: WindowMode)

    fun load(): Saved? = try {
        if (!file.isFile) null
        else {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            val mode = WindowMode.fromKey(p.getProperty("mode")) ?: return null
            val base = WindowMode.fromKey(p.getProperty("base"))?.takeIf { it != WindowMode.FULLSCREEN } ?: WindowMode.BORDERLESS
            Saved(mode, base)
        }
    } catch (e: Exception) {
        null
    }

    fun save(saved: Saved) {
        try {
            file.parentFile?.mkdirs()
            val p = Properties()
            p.setProperty("mode", saved.mode.key)
            p.setProperty("base", saved.base.key)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.outputStream().use { p.store(it, "Fuse window mode") }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.warn("could not save the window mode", e)
        }
    }
}
