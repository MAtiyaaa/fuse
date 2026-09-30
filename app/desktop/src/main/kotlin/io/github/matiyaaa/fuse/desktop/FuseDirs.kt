package io.github.matiyaaa.fuse.desktop

import java.io.File

/**
 * Fuse's own folders, following the XDG base directory spec. These are the only places Fuse writes
 * to on its own; anything else is a place the user picked.
 */
class FuseDirs(
    val home: String,
    /** `$XDG_CACHE_HOME/fuse`: image cache, generated playlists, downloaded updates. */
    val cache: String,
    /** `$XDG_DATA_HOME/fuse`: the database, custom media and the fallback secret file. */
    val data: String,
    /** `$XDG_CONFIG_HOME/fuse`: window mode and other desktop-only preferences. */
    val config: String,
    /** `$XDG_CONFIG_HOME`, for `autostart/`. */
    val xdgConfigHome: String,
    /** `$XDG_DATA_HOME`, for `applications/` and `icons/`. */
    val xdgDataHome: String,
) {
    val database: String get() = "$data/fuse.db"
    val customMedia: String get() = "$data/media/custom"

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): FuseDirs {
            val home = (env["HOME"]?.takeIf { it.startsWith("/") } ?: System.getProperty("user.home")).trimEnd('/').ifEmpty { "/" }
            fun xdg(name: String, fallback: String): String =
                env[name]?.takeIf { it.startsWith("/") }?.trimEnd('/') ?: "$home/$fallback"
            val cacheHome = xdg("XDG_CACHE_HOME", ".cache")
            val dataHome = xdg("XDG_DATA_HOME", ".local/share")
            val configHome = xdg("XDG_CONFIG_HOME", ".config")
            return FuseDirs(
                home = home,
                cache = "$cacheHome/fuse",
                data = "$dataHome/fuse",
                config = "$configHome/fuse",
                xdgConfigHome = configHome,
                xdgDataHome = dataHome,
            )
        }
    }

    /** Creates Fuse's own folders (never anything outside them). */
    fun ensure() {
        listOf(cache, data, config).forEach { File(it).mkdirs() }
    }
}

/** Minimal stderr logging. Callers never pass secrets, tokens or file contents. */
internal object Log {
    fun info(message: String) = System.err.println("[fuse] $message")
    fun warn(message: String, error: Throwable? = null) =
        System.err.println("[fuse] warning: $message" + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
}
