package io.github.matiyaaa.fuse.launch.pc

import io.github.matiyaaa.fuse.launch.IdFiles
import io.github.matiyaaa.fuse.launch.Paths
import io.github.matiyaaa.fuse.model.ShortcutFormat

/** A parsed `.desktop` file (freedesktop or Winlator's export format). */
data class DesktopEntry(
    val name: String?,
    /** Raw `Exec=` value of the `[Desktop Entry]` group, field codes not yet stripped. */
    val exec: String?,
    val icon: String?,
    /** `Path=`: working directory. */
    val path: String?,
    val type: String?,
    /** Every key of the `[Desktop Entry]` group. */
    val entries: Map<String, String>,
    /** Keys of Winlator's `[Extra Data]` group (container_id, execArgs, ...). */
    val extraData: Map<String, String>,
) {
    /** Winlator container id from `[Extra Data]`, when present and numeric. */
    val containerId: Int? get() = extraData["container_id"]?.trim()?.toIntOrNull()

    /** True for Winlator exports: an `[Extra Data]` group or an `Exec` that runs `wine`. */
    val isWinlator: Boolean get() = extraData.isNotEmpty() || exec?.contains("wine ") == true

    /**
     * The Windows program a Winlator shortcut runs: everything after the last `"wine "` in `Exec`,
     * with escaped (doubled) backslashes collapsed to one (Winlator Cmod `Shortcut.java` unescapes them).
     */
    val windowsTarget: String?
        get() {
            val e = exec ?: return null
            val i = e.lastIndexOf("wine ")
            if (i < 0) return null
            return e.substring(i + 5).trim().replace(BACKSLASHES) { "\\" }.ifEmpty { null }
        }

    /** Steam app id when `Exec` opens `steam://rungameid/<id>` (Steam's own desktop shortcuts). */
    val steamAppId: Long?
        get() = exec?.let { STEAM_URL.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    private companion object {
        val STEAM_URL = Regex("""steam://rungameid/(\d+)""")
        val BACKSLASHES = Regex("""\\+""")
    }
}

/** A native Android app reference from an ES-DE `.app` file. */
data class AndroidAppRef(val packageName: String, val activity: String?) {
    /** Fully qualified activity (a leading '.' is relative to the package, as in ES-DE). */
    val activityClass: String? get() = activity?.let { if (it.startsWith('.')) packageName + it else it }
}

/**
 * Parsers for the shortcut and id files PC launchers and frontends exchange. Pure functions over file
 * content; the caller reads the file.
 */
object ShortcutParser {
    private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    private val ACTIVITY = Regex("""\.?[A-Za-z_][A-Za-z0-9_$]*(\.[A-Za-z_][A-Za-z0-9_$]*)*""")

    /**
     * Parses a `.desktop` file. A `[Desktop Entry]` header is required, as in Winlator's parser and
     * ES-DE's shortcut handling; otherwise null. A leading `#!` line and indentation are tolerated.
     */
    fun parseDesktop(text: String): DesktopEntry? {
        val main = LinkedHashMap<String, String>()
        val extra = LinkedHashMap<String, String>()
        var group: String? = null
        var sawHeader = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                group = line.substring(1, line.length - 1)
                if (group == "Desktop Entry") sawHeader = true
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (group) {
                "Desktop Entry" -> if (key !in main) main[key] = value
                "Extra Data" -> extra[key] = value
            }
        }
        if (!sawHeader) return null
        return DesktopEntry(
            name = main["Name"],
            exec = main["Exec"],
            icon = main["Icon"],
            path = main["Path"]?.ifEmpty { null },
            type = main["Type"],
            entries = main,
            extraData = extra,
        )
    }

    /**
     * A numeric id file (`.steam`, `.epic`, `.gog`, `.amazon`, `.pcgame`): the trimmed content must be
     * digits only (ES-DE warns against spaces or extra lines). Null otherwise.
     */
    fun parseNumericId(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty() || !t.all { it in '0'..'9' }) return null
        return t.toLongOrNull()
    }

    /** A title id file (`.psvita`, `.ps3`, `.ps4`): first non-blank line, trimmed, no inner spaces. */
    fun parseTitleId(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.takeIf { ' ' !in it && '\t' !in it }

    /**
     * An ES-DE `.app` file: `package` or `package/activity`. Carriage returns are stripped and the
     * content is split on '/' (ES-DE FileData.cpp).
     */
    fun parseAppFile(text: String): AndroidAppRef? {
        val t = text.replace("\r", "").trim().lineSequence().firstOrNull()?.trim().orEmpty()
        if (t.isEmpty()) return null
        val pkg = t.substringBefore('/').trim()
        val activity = t.substringAfter('/', "").trim().ifEmpty { null }
        if (!PACKAGE.matches(pkg)) return null
        if (activity != null && !ACTIVITY.matches(activity)) return null
        return AndroidAppRef(pkg, activity)
    }

    /**
     * Shortcut format from the file name and, when available, its content. `.desktop` files are
     * Winlator exports when they carry `[Extra Data]` or run `wine`, Steam shortcuts when they open
     * `steam://rungameid/`, and plain freedesktop entries otherwise.
     */
    fun detectFormat(path: String, content: String? = null): ShortcutFormat {
        val ext = Paths.extension(path)
        return when {
            ext == "desktop" -> {
                val entry = content?.let(::parseDesktop)
                when {
                    entry == null -> ShortcutFormat.FREEDESKTOP_DESKTOP
                    entry.isWinlator -> ShortcutFormat.WINLATOR_DESKTOP
                    entry.steamAppId != null -> ShortcutFormat.STEAM_URL
                    else -> ShortcutFormat.FREEDESKTOP_DESKTOP
                }
            }
            ext in IdFiles.storeSources -> ShortcutFormat.GAMENATIVE
            ext == "url" && content?.contains("steam://rungameid/") == true -> ShortcutFormat.STEAM_URL
            else -> ShortcutFormat.UNKNOWN
        }
    }
}
