package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsPath

/** Parsers for the small text files that tie disc images together. They only read text. */
object Playlists {
    private val cueFile = Regex("^\\s*FILE\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val gdiTrack = Regex("^\\s*\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+(\"[^\"]+\"|\\S+)\\s+-?\\d+\\s*$")

    /**
     * Entries of an .m3u playlist resolved against [m3uDir]: one path per non-empty line, lines
     * starting with `#` are comments (extended M3U directives included). Relative paths, "./",
     * "../", absolute paths and Windows backslashes are accepted.
     */
    fun parseM3u(text: String, m3uDir: String): List<String> = lines(text)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { FsPath.resolve(m3uDir, it) }

    /**
     * Files referenced by `FILE` lines of a cue sheet, resolved against [cueDir].
     * Handles `FILE "Track 01.bin" BINARY` and unquoted `FILE track01.bin BINARY`.
     */
    fun parseCue(text: String, cueDir: String): List<String> = lines(text).mapNotNull { line ->
        val body = cueFile.matchEntire(line)?.groupValues?.get(1) ?: return@mapNotNull null
        val name = if (body.startsWith("\"")) {
            body.substring(1).substringBefore('"')
        } else {
            // The last word is the file type (BINARY, WAVE, MP3...).
            body.substringBeforeLast(' ', body).trim()
        }
        name.takeIf { it.isNotBlank() }?.let { FsPath.resolve(cueDir, it) }
    }

    /** Track files of a Dreamcast .gdi descriptor (first line is the track count). */
    fun parseGdi(text: String, gdiDir: String): List<String> = lines(text).mapNotNull { line ->
        val raw = gdiTrack.matchEntire(line)?.groupValues?.get(1) ?: return@mapNotNull null
        FsPath.resolve(gdiDir, raw.trim('"'))
    }

    /**
     * [text] of a cue sheet with its first `FILE` line naming [name] instead, in the folder of the
     * file it named (a sheet for a patched copy of the data track), or null when it names none.
     */
    fun repointCue(text: String, name: String): String? = repoint(text, { cueFile.matchEntire(it) != null }, 0) { line ->
        val body = cueFile.matchEntire(line)!!.groupValues[1]
        val (ref, type) = if (body.startsWith("\"")) {
            body.substring(1).substringBefore('"') to body.substring(1).substringAfter('"').trim()
        } else {
            body.substringBeforeLast(' ', body).trim() to body.substringAfterLast(' ', "").trim()
        }
        "FILE \"${besideRef(ref, name)}\"" + (if (type.isEmpty()) "" else " $type")
    }

    /**
     * [text] of an .m3u playlist with its entry [index] (0 for the first, comments not counted) naming
     * [name] instead, in the folder of the file it named, or null when it has no such entry.
     */
    fun repointM3u(text: String, index: Int, name: String): String? =
        repoint(text, { it.isNotEmpty() && !it.startsWith("#") }, index) { line -> besideRef(line, name) }

    /** Rewrites the [index]th line [counts] (trimmed) with [rewrite], keeping its indent and line ending. */
    private fun repoint(text: String, counts: (String) -> Boolean, index: Int, rewrite: (String) -> String): String? {
        val bom = text.startsWith("\uFEFF")
        val raw = text.removePrefix("\uFEFF").split('\n').toMutableList()
        var seen = 0
        for ((i, line) in raw.withIndex()) {
            val trimmed = line.trim().trimEnd('\r')
            if (!counts(trimmed)) continue
            if (seen++ != index) continue
            raw[i] = line.takeWhile { it == ' ' || it == '\t' } + rewrite(trimmed) + (if (line.endsWith("\r")) "\r" else "")
            return (if (bom) "\uFEFF" else "") + raw.joinToString("\n")
        }
        return null
    }

    /** [ref] (a path as a sheet wrote it) with its last part [name]. */
    private fun besideRef(ref: String, name: String): String = ref.substring(0, maxOf(ref.lastIndexOf('/'), ref.lastIndexOf('\\')) + 1) + name

    private fun lines(text: String): List<String> =
        text.removePrefix("﻿").split('\n').map { it.trim().trimEnd('\r') }
}

/**
 * Renders .m3u playlists for multi-disc games. Fuse never writes into ROM folders: the caller
 * decides where the playlist lives (normally Fuse's cache) and passes that folder as `relativeTo`.
 */
object M3uWriter {
    /**
     * One line per disc, in order, ending with a newline. With [relativeTo] (the folder the playlist
     * will be saved in) paths are written relative to it; otherwise they stay absolute.
     *
     * @throws IllegalArgumentException when [discPaths] is empty or a path contains a line break.
     */
    fun render(discPaths: List<String>, relativeTo: String? = null): String {
        require(discPaths.isNotEmpty()) { "A playlist needs at least one disc" }
        return buildString {
            for (path in discPaths) {
                require('\n' !in path && '\r' !in path) { "Disc path contains a line break: $path" }
                append(if (relativeTo != null) FsPath.relativize(relativeTo, path) else FsPath.normalize(path))
                append('\n')
            }
        }
    }
}
