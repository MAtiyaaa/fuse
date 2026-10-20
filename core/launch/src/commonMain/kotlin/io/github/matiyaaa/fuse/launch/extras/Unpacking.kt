package io.github.matiyaaa.fuse.launch.extras

import io.github.matiyaaa.fuse.model.Platform

/**
 * Archives (zip, 7z, rar) that land with a downloaded game, unpacked when the system's emulators
 * can't read them. Fuse's own catalog says which file types each system's games come in
 * ([Platform.extensions]): a system whose games may be a zip or 7z (cartridge systems, whose
 * emulators open archives themselves, and arcade sets, which must stay zipped) keeps them as they
 * are. A disc system ([DISCS]) is unpacked even where RetroArch's cores could open the archive:
 * its standalone emulators (DuckStation, PCSX2, Dolphin and the rest) read only the disc's own
 * files, which RetroArch reads too. The files go in a folder named for the archive, and the archive
 * goes once that is whole.
 */
object Unpacking {
    val ARCHIVES = setOf("zip", "7z", "rar")

    /** The largest archive Fuse unpacks by itself. */
    const val MAX_BYTES = 64L * 1024 * 1024 * 1024

    /** The archive kind of [path] ("zip", "7z", "rar"), or null. */
    fun kind(path: String): String? = path.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase().takeIf { it in ARCHIVES }

    /** Systems whose games are discs, by Fuse's platform id. */
    val DISCS = setOf(
        "psx", "ps2", "ps3", "psp", "ngc", "wii", "wiiu", "dc", "saturn", "segacd", "neo-geo-cd", "turbografx-cd", "pc-fx",
        "xbox", "xbox360", "3do", "amiga-cd32", "commodore-cdtv", "atari-jaguar-cd",
    )

    /** [platform]'s emulators need [path] (an archive) unpacked (see [Unpacking]). */
    fun needed(platform: Platform, path: String): Boolean {
        val kind = kind(path) ?: return false
        return platform.id.value in DISCS || kind !in platform.extensions
    }

    /** The folder an archive at [path] unpacks into: beside it, by its name without the extension. */
    fun folder(path: String): String {
        val p = path.replace('\\', '/')
        val parent = p.substringBeforeLast('/', "")
        val stem = p.substringAfterLast('/').substringBeforeLast('.').trim().ifEmpty { "Unpacked" }
        return if (parent.isEmpty()) stem else "$parent/$stem"
    }

    /** An entry of an archive that is safe to write below the folder: no climbing out, no absolute path, no drive. */
    fun safe(entry: String): Boolean {
        val e = entry.replace('\\', '/')
        if (e.isEmpty() || e.startsWith("/") || e.contains(':')) return false
        return e.split('/').none { it == ".." }
    }
}
