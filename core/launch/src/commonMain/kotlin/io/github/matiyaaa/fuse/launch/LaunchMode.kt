package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId

/** The shape of a [LaunchTarget], for matching it to a [LaunchMode]. */
enum class TargetKind { FILE, DIRECTORY, TITLE_ID, APP }

/** File, playlist and shortcut targets are files; the rest map one to one. */
val LaunchTarget.kind: TargetKind
    get() = when (this) {
        is LaunchTarget.File, is LaunchTarget.Playlist, is LaunchTarget.Shortcut -> TargetKind.FILE
        is LaunchTarget.Directory -> TargetKind.DIRECTORY
        is LaunchTarget.TitleId -> TargetKind.TITLE_ID
        is LaunchTarget.App -> TargetKind.APP
    }

/** The file or folder path a target refers to, or null for title ids and apps. */
val LaunchTarget.path: String?
    get() = when (this) {
        is LaunchTarget.File -> path
        is LaunchTarget.Directory -> path
        is LaunchTarget.Playlist -> path
        is LaunchTarget.Shortcut -> path
        is LaunchTarget.TitleId, is LaunchTarget.App -> null
    }

/**
 * One way an emulator accepts a game (ES-DE models these as alternative `<command>` entries, for example
 * aPS3e "Directory", "ISO" and "Game Serial").
 *
 * @param extensions lower-case extensions without dot for [TargetKind.FILE]; null accepts any file.
 * @param platforms restricts the mode to some platforms (MD.emu uses a different ROM hand-off for Sega CD).
 * @param idFile the file's content (in [LaunchOptions.injectedText]) is the id: it fills `{INJECT}` and `{SERIAL}`.
 * @param idPattern when set, the id (`{SERIAL}` or `{INJECT}`) must match it fully.
 */
data class LaunchMode<out S>(
    val label: String,
    val kind: TargetKind,
    val spec: S,
    val extensions: Set<String>? = null,
    val platforms: Set<PlatformId>? = null,
    val idFile: Boolean = false,
    val idPattern: Regex? = null,
)

/** Picks the mode for [target]: an explicit extension match wins over a mode that takes any file. */
fun <S> List<LaunchMode<S>>.select(target: LaunchTarget, platform: PlatformId): LaunchMode<S>? {
    val kind = target.kind
    val candidates = filter { it.kind == kind && (it.platforms == null || platform in it.platforms) }
    if (kind != TargetKind.FILE) return candidates.firstOrNull()
    val ext = Paths.extension(target.path.orEmpty())
    return candidates.firstOrNull { it.extensions != null && ext in it.extensions }
        ?: candidates.firstOrNull { it.extensions == null }
}

/** Tiny path helpers for commonMain (no java.io). Paths use '/'. */
object Paths {
    fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** Lower-case extension without dot, or "" when there is none. */
    fun extension(path: String): String {
        val name = fileName(path)
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    }

    /** File name without its extension. */
    fun baseName(path: String): String {
        val name = fileName(path)
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")

    /** True when [child] is strictly inside [dir]. */
    fun isInside(child: String, dir: String): Boolean {
        val d = dir.trimEnd('/')
        return child.length > d.length + 1 && child.startsWith("$d/")
    }

    /** Replaces a leading `~` with [home] when known. */
    fun expandHome(path: String, home: String?): String = when {
        home == null -> path
        path == "~" -> home
        path.startsWith("~/") -> home.trimEnd('/') + path.substring(1)
        else -> path
    }
}
