package io.github.matiyaaa.fuse.library

/**
 * String helpers for "/"-separated paths. Pure functions, no filesystem access.
 *
 * Paths on disk always use "/", on Windows too ("C:/Games/x.iso", where the drive is the root).
 * Backslashes are only treated as separators by [resolve], because playlists written on Windows
 * (`Disc 1\Game.cue`) are common inside ROM folders.
 */
object FsPath {
    /** The Windows drive [path] starts with ("C:"), or null. */
    fun drive(path: String): String? =
        if (path.length >= 2 && path[1] == ':' && path[0].isLetter() && (path.length == 2 || path[2] == '/')) path.substring(0, 2) else null

    /** True for "/x" and "C:/x". */
    fun isAbsolute(path: String): Boolean = path.startsWith("/") || drive(path) != null

    /** Joins [parent] and [child] with exactly one "/" between them. */
    fun join(parent: String, child: String): String {
        if (parent.isEmpty()) return child
        if (child.isEmpty()) return parent
        val p = parent.trimEnd('/')
        val c = child.trimStart('/')
        return if (p.isEmpty()) "/$c" else "$p/$c"
    }

    /** Joins every segment in order. */
    fun join(parent: String, vararg children: String): String = children.fold(parent) { acc, c -> join(acc, c) }

    /** Last segment of [path] ("" for the root). */
    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** Everything before the last segment, or null for a single segment or the root. */
    fun parent(path: String): String? {
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return when {
            index < 0 -> null
            index == 0 -> if (trimmed.length > 1) "/" else null
            // "C:/Games" is inside the drive's root, "C:/".
            index == 2 && drive(trimmed) != null -> trimmed.substring(0, 3)
            else -> trimmed.substring(0, index)
        }
    }

    /** Lower-case extension of a file name without the dot, or "" (hidden files like ".bashrc" have none). */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    /** [name] without its extension, keeping names that only start with a dot intact. */
    fun stem(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    /** Collapses duplicate separators, resolves "." and ".." and drops a trailing "/". */
    fun normalize(path: String): String {
        val drive = drive(path)
        val body = if (drive != null) path.substring(2) else path
        val absolute = drive != null || body.startsWith("/")
        val out = ArrayList<String>()
        for (part in body.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.lastIndex) else if (!absolute) out.add("..")
                else -> out.add(part)
            }
        }
        val joined = out.joinToString("/")
        return when {
            drive != null -> "$drive/$joined"
            absolute -> "/$joined"
            else -> joined
        }
    }

    /**
     * Resolves a playlist or cue reference against the directory that contains the playlist.
     * Accepts "/" and "\" separators, "./" and "../" segments, and absolute paths.
     */
    fun resolve(baseDir: String, reference: String): String {
        val ref = reference.trim().replace('\\', '/')
        return if (isAbsolute(ref)) normalize(ref) else normalize(join(baseDir, ref))
    }

    /** True when [path] equals [ancestor] or lies below it. */
    fun isWithin(path: String, ancestor: String): Boolean {
        val p = normalize(path)
        val a = normalize(ancestor)
        if (a == "/") return p.startsWith("/")
        // A drive root ("C:/") already ends with its separator.
        if (a.endsWith("/")) return p.startsWith(a)
        return p == a || p.startsWith("$a/")
    }

    /**
     * Path of [target] relative to directory [from], using ".." where needed. Both must be absolute
     * or both relative.
     */
    fun relativize(from: String, target: String): String {
        val a = normalize(from).split('/').filter { it.isNotEmpty() }
        val b = normalize(target).split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < a.size && common < b.size && a[common] == b[common]) common++
        val ups = List(a.size - common) { ".." }
        val rest = b.drop(common)
        return (ups + rest).joinToString("/").ifEmpty { "." }
    }
}
