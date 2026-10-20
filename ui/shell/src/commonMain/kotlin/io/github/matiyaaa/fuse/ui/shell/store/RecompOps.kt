package io.github.matiyaaa.fuse.ui.shell.store

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.launch.ports.Recomp

/**
 * A recomp the person installed themselves (not through Fuse Ports), found where its own builds
 * go: [how] in a word ("Flatpak", "AppImage", "Program"), [path] the program (or the Flatpak's
 * id), how it starts ([argv] in [cwd], the folder its assets are read from), [folder] where it
 * keeps its saves, mods and settings, and its [version] where that can be told.
 */
@Immutable
data class FoundRecomp(
    /** [Recomp.id]. */
    val id: String,
    val how: String,
    val path: String,
    val argv: List<String>,
    val cwd: String,
    val folder: String,
    val version: String? = null,
)

/**
 * The recomp that plays a game here, and the [folder] it keeps its files in: Fuse's own copy's
 * home, or the person's own install's folder ([yours]). [version] is the one here, when known.
 */
@Immutable
data class RecompPlace(val recomp: Recomp, val folder: String, val yours: FoundRecomp?, val version: String?)

/** A mod or texture pack in a recomp's mods folder. */
@Immutable
data class RecompMod(val name: String, val texturePack: Boolean, val size: Long)

/** A recomp's newest release ([tag], its page at [url]); [newer] when it is newer than the one here, null when that can't be told. */
@Immutable
data class RecompRelease(val tag: String, val url: String, val newer: Boolean?)

/**
 * What recomps need from a computer: finding the ones the person installed, and reading and
 * writing only in a recomp's own folders (its saves, mods and settings) and Fuse's own folder of
 * controls profiles ([profiles]). Paths use `/`.
 */
interface RecompFiles {
    /** Fuse's own folder of the controls profiles it keeps, one folder per recomp. */
    val profiles: String

    /** The recomps installed on this computer by the person. */
    suspend fun find(): List<FoundRecomp>

    /** [path]'s text, when it is a small file in a recomp's folder or Fuse's profiles. */
    suspend fun readText(path: String): String?

    /** Writes [text] to [path] (a recomp's settings, or a profile), under a temporary name first; false when it couldn't or the place isn't one of these. */
    suspend fun writeText(path: String, text: String): Boolean

    /** The files directly in [folder] (name and size), or null when it can't be read. */
    suspend fun list(folder: String): List<Pair<String, Long>>?

    /**
     * Puts the mods in [file] into [mods]: the file itself when it is a mod or texture pack, else
     * every mod and texture pack inside it (a zip), each by its own name. The names put in, or why not.
     */
    suspend fun addMods(file: String, mods: String): Result<List<String>>

    /** Removes [path], a file in a recomp's mods folder or one of Fuse's profiles. */
    suspend fun remove(path: String): Boolean

    /** Copies [from] to [to] (a profile in or out), replacing what is there. */
    suspend fun copy(from: String, to: String): Boolean

    /** Updates the Flatpak [id] as the person installed it (theirs or the system's); null when updated, else why not. */
    suspend fun updateFlatpak(id: String): String? = "Flatpak isn't on this computer."
}
