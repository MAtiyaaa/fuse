package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.fusePath
import java.io.File

/**
 * Fuse's own folders. On Linux they follow the XDG base directory spec; on Windows they are in
 * AppData (or a FuseData folder next to a portable Fuse.exe); on macOS in the user's Library. These
 * are the only places Fuse writes to on its own; anything else is a place the user picked. Paths use
 * forward slashes on every system.
 */
class FuseDirs(
    val home: String,
    /** Image cache, generated playlists, downloaded updates (`$XDG_CACHE_HOME/fuse`). */
    val cache: String,
    /** The database, custom media and the fallback secret file (`$XDG_DATA_HOME/fuse`). */
    val data: String,
    /** Window mode and other desktop-only preferences (`$XDG_CONFIG_HOME/fuse`). */
    val config: String,
    /** `$XDG_CONFIG_HOME`, for `autostart/` (Linux). */
    val xdgConfigHome: String,
    /** `$XDG_DATA_HOME`, for `applications/` and `icons/` (Linux). */
    val xdgDataHome: String,
    /** True when everything lives in a FuseData folder next to a portable Fuse.exe. */
    val portable: Boolean = false,
) {
    val database: String get() = "$data/fuse.db"
    val customMedia: String get() = "$data/media/custom"

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv(), os: DesktopOs = DesktopOs.current): FuseDirs = when (os) {
            DesktopOs.LINUX -> linux(env)
            DesktopOs.WINDOWS -> windows(env, System.getProperty("jpackage.app-path"))
            DesktopOs.MACOS -> macos(System.getProperty("user.home"))
        }

        internal fun linux(env: Map<String, String>): FuseDirs {
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

        /**
         * `%LOCALAPPDATA%/Fuse` for the database and cache (large, never roamed) and `%APPDATA%/Fuse`
         * for settings. A FuseData folder next to Fuse.exe (the portable zip has one) holds everything
         * instead, so Fuse can live on a USB drive.
         */
        internal fun windows(env: Map<String, String>, launcher: String?): FuseDirs {
            // Windows values use backslashes; Fuse keeps forward slashes.
            fun slashes(path: String) = path.replace('\\', '/').trimEnd('/')
            val home = slashes(env["USERPROFILE"] ?: System.getProperty("user.home"))
            val portable = launcher?.let { File(it).absoluteFile.parentFile }?.let { File(it, "FuseData") }?.takeIf { it.isDirectory }
            if (portable != null) {
                val root = portable.fusePath.trimEnd('/')
                return FuseDirs(home, "$root/cache", "$root/data", "$root/config", "$root/config", "$root/data", portable = true)
            }
            val local = env["LOCALAPPDATA"]?.let(::slashes) ?: "$home/AppData/Local"
            val roaming = env["APPDATA"]?.let(::slashes) ?: "$home/AppData/Roaming"
            return FuseDirs(home, "$local/Fuse/Cache", "$local/Fuse", "$roaming/Fuse", roaming, local)
        }

        /** `~/Library/Application Support/Fuse` and `~/Library/Caches/Fuse`. */
        internal fun macos(userHome: String): FuseDirs {
            val home = userHome.trimEnd('/').ifEmpty { "/" }
            val support = "$home/Library/Application Support"
            return FuseDirs(home, "$home/Library/Caches/Fuse", "$support/Fuse", "$support/Fuse/Config", "$home/Library/Preferences", support)
        }
    }

    /**
     * Erase Fuse, when it was asked for last time: Fuse's own folders go (its database, settings,
     * art, caches and Fuse Sync), never anything outside them. Game files are never in them.
     */
    fun eraseIfAsked() {
        val marker = File(home, io.github.matiyaaa.fuse.desktop.platform.ERASE_MARKER)
        if (!marker.isFile) return
        for (path in listOf(data, cache, config).distinct()) {
            val dir = File(path)
            // Only Fuse's own folder: never a home or system folder by mistake.
            if (dir.name.equals("fuse", ignoreCase = true) || dir.parentFile?.name.equals("fuse", ignoreCase = true) || portable) dir.deleteRecursively()
        }
        marker.delete()
        Log.info("Fuse was erased; starting as new")
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
