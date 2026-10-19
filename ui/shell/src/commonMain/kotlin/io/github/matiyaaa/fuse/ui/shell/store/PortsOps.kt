package io.github.matiyaaa.fuse.ui.shell.store

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.Port
import io.github.matiyaaa.fuse.model.PortBuild
import io.github.matiyaaa.fuse.model.PortFileKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fuse Ports, in Addons on a computer: games rebuilt to run natively, each the program its own
 * project publishes, installed into Fuse's own folder and played with the person's own copy of the
 * game, whose files are handed over by their folder or linked in, never copied or changed. Once set
 * up, a port is a game of the library (the Ports system), so Play, Steam entries and saves work as
 * for every other game.
 */
interface PortsOps {
    /** True where ports can be installed (a computer). */
    val supported: Boolean get() = false

    val state: StateFlow<PortsState>

    /** Ports is shown: reads the list it has, then a newer signed one when Fuse carries the key for it. */
    fun open() = Unit

    /** Looks for a newer signed list now. */
    fun refresh() = Unit

    /** Downloads [id]'s build for this computer, checks it, and puts it in Fuse's folder (replacing an older one). */
    fun install(id: String) = Unit

    /** Stops [id]'s download, or forgets its failure. */
    fun cancel(id: String) = Unit

    /**
     * The person's [folder] for [id]'s game files [dataId]: checked for what the port needs, then
     * kept, and the port set up with it. Null when it was taken, else why not (what the folder lacks).
     */
    suspend fun chooseFolder(id: String, dataId: String, folder: String): String? = "Ports aren't available here."

    /**
     * Takes [id]'s copy out of Fuse's folder, with its links and its game in the library; its home
     * (saves, settings) stays, so installing it again finds them.
     */
    suspend fun remove(id: String): Boolean = false

    /**
     * The person's copies of [id]'s game already in the library whose folder has what [dataId]
     * needs, found by the game's name and then checked file by file; the best first.
     */
    suspend fun copiesInLibrary(id: String, dataId: String): List<PortCopy> = emptyList()

    /** [copy]'s folder for [id]'s game files [dataId], as [chooseFolder] takes one, remembered as that game's. */
    suspend fun useCopy(id: String, dataId: String, copy: PortCopy): String? = chooseFolder(id, dataId, copy.folder)

    /**
     * The ports set up here that play [game]: one whose files were taken from it, or one of the
     * same game by name. For Play With on the game's page.
     */
    suspend fun portsFor(game: GameId): List<PortView> = emptyList()

    /** The port Play starts [game] with instead of its emulator, or none ([portId] null). */
    suspend fun setPlayWith(game: GameId, portId: String?) = Unit

    /** The port Play starts [game] with, when the person chose one. */
    fun playWith(game: GameId): String? = null

    /** The library game Play starts for [game]: its port's, when one was chosen and is ready; else null. */
    suspend fun playTarget(game: GameId): GameId? = null

    /**
     * What recompiling [game] with AnyPS5 would do here ([io.github.matiyaaa.fuse.launch.ports.AnyPs5Recompile]),
     * or null when it isn't a PlayStation 5 dump.
     */
    suspend fun recompileOffer(game: GameId): RecompileOffer? = null

    /**
     * Recompiles [game] with AnyPS5 into Fuse's folder (installing AnyPS5 first, or its newer
     * release), adds the build to the library and has Play start it. [onStep] hears each step, in
     * words. Null when done, else why not.
     */
    suspend fun recompile(game: GameId, onStep: (String) -> Unit = {}): String? = "Games are recompiled from Fuse on a computer."

    /** Takes [game]'s recompiled build out (its saves stay in its home); Play goes back to its emulator. */
    suspend fun removeRecompiled(game: GameId): Boolean = false

    object None : PortsOps {
        override val state: StateFlow<PortsState> = MutableStateFlow(PortsState())
    }
}

/** Everything Ports shows. */
@Immutable
data class PortsState(
    val ports: List<PortView> = emptyList(),
    /** The list's own revision, and whether it is one Fuse believed by its signature (rather than the one inside Fuse). */
    val revision: Long = 0,
    val signed: Boolean = false,
    /** False while Fuse carries no key to check a newer list with: the one inside Fuse is used, and nothing shows as verified. */
    val canCheck: Boolean = false,
    val refreshing: Boolean = false,
    /** Why the last look for a newer list failed; null when it worked or wasn't made. */
    val problem: String? = null,
    /** The list read at least once. */
    val loaded: Boolean = false,
    /** Where Fuse keeps its ports. */
    val folder: String? = null,
) {
    fun port(id: String): PortView? = ports.firstOrNull { it.port.id == id }
}

/**
 * One port as it stands here: the [build] this computer runs (or [whyNot]), what is installed, the
 * job in progress, the folders chosen and what each still lacks, and its game in the library once
 * it is set up. [verified] only when a signed list says this exact build passed the nightly check.
 */
@Immutable
data class PortView(
    val port: Port,
    val build: PortBuild?,
    val whyNot: String?,
    /** The version installed here, when one is; [build] is what installing now would bring. */
    val installedVersion: String? = null,
    val job: PortJob? = null,
    val verified: String? = null,
    val folders: Map<String, String> = emptyMap(),
    /** By data id: what the chosen folder lacks, or "Not chosen". */
    val missing: Map<String, String> = emptyMap(),
    val game: GameId? = null,
    /** The SHA-256 of the build installed here, to tell an update from the same file. */
    val installedSha: String? = null,
) {
    val installed: Boolean get() = installedVersion != null
    val update: Boolean get() = installedSha != null && build != null && installedSha != build.sha256
    val ready: Boolean get() = installed && missing.isEmpty() && game != null
}

/**
 * Recompiling a PlayStation 5 game with AnyPS5 here: why it can't be ([whyNot]), the AnyPS5 release
 * its build here was made with ([builtWith], null before it is recompiled), and what the person
 * should know first ([warning]: a processor without an extension the game's code needs).
 */
@Immutable
data class RecompileOffer(val whyNot: String? = null, val builtWith: String? = null, val warning: String? = null)

/** A game of the library whose [folder] holds a port's game files. */
@Immutable
data class PortCopy(val game: GameId, val title: String, val system: String, val folder: String)

/** An install in progress, or one that failed. */
sealed interface PortJob {
    data class Downloading(val progress: Float?, val bytes: Long, val total: Long?) : PortJob
    data object Unpacking : PortJob
    data object SettingUp : PortJob
    data class Failed(val message: String) : PortJob

    val active: Boolean get() = this !is Failed
}

/**
 * What Fuse Ports needs from a computer: its own folder of ports, the list inside this build of
 * Fuse, downloads, unpacking, links to the person's folders, and umu-launcher where it is
 * installed. Paths use `/`.
 */
interface PortFiles {
    /** Fuse's own folder of ports. */
    val folder: String

    /** This computer as the list names systems: "linux", "windows" or "macos". */
    val os: String

    /** Its processor: "x86_64" or "arm64". */
    val arch: String

    /** The list of ports inside this build of Fuse (its `ports/index.json`), or null. */
    fun bundledIndex(): String?

    /** umu-launcher's `umu-run`, when it is installed (Linux). */
    fun umuRun(): String?

    /**
     * The Proton folder a Windows build gets through umu-launcher: the compatibility tool the person
     * chose for every game in Steam, else the newest installed ([io.github.matiyaaa.fuse.library.steam.SteamProton]);
     * null where there is none, and umu-launcher uses its own.
     */
    suspend fun protonPath(): String? = null

    /** A new file to download into, below Fuse's own cache. */
    suspend fun newDownload(name: String): DownloadSink?

    /** Bytes free where ports go, or null when unknown. */
    fun freeBytes(): Long?

    /**
     * Unpacks [archive] (a [kind]) into [into], a new empty folder: an AppImage or a single program
     * goes in as [name] and is made runnable; an archive keeps its own files' permissions. Nothing
     * may land outside [into]. Throws an exception saying why it couldn't.
     */
    suspend fun unpack(archive: String, kind: PortFileKind, name: String, into: String)

    /**
     * Links [target] (one of the person's files or folders) at [at], in Fuse's folder. Null when
     * linked, else why it couldn't be (Windows lets programs link files only in Developer Mode).
     */
    suspend fun link(target: String, at: String): String?

    /** Makes the folder [path] and its parents; false when it couldn't. */
    suspend fun makeFolder(path: String): Boolean

    /** Writes [text] to [path], under a temporary name first; false when it couldn't. */
    suspend fun writeText(path: String, text: String): Boolean

    /** [bytes] written to [path] (a recompilation's ROM turned into the form it reads), replacing what was there. */
    suspend fun writeBytes(path: String, bytes: ByteArray): Boolean = false

    /** True when [path] is a link Fuse made (not a file or folder of its own). */
    suspend fun isLink(path: String): Boolean

    /** Removes [path] (a file, a link or a folder of Fuse's), never following a link out of it. */
    suspend fun remove(path: String): Boolean

    /** Moves [from] to [to], replacing nothing: false when [to] is there or it couldn't. */
    suspend fun move(from: String, to: String): Boolean
}

/**
 * [stage] put at [into] in place of what is there, which waits aside as `[into].old` until the new one
 * is in place and goes back when it couldn't be: a working copy is never taken out before its
 * replacement is in. False when [stage] didn't go in (what was there is where it was).
 */
suspend fun PortFiles.replace(stage: String, into: String): Boolean {
    val aside = "$into.old"
    // What a replacing Fuse stopped in the middle of: put back, or gone once its replacement is in.
    recover(into)
    // Nothing was there, or it couldn't be moved (a file in use): then the new one can't go in over it.
    val moved = move(into, aside)
    if (move(stage, into)) {
        if (moved) remove(aside)
        return true
    }
    if (moved) move(aside, into)
    return false
}

/**
 * After Fuse stopped in the middle of [replace]: the copy waiting aside goes back to [into] when
 * nothing took its place, and goes when something did.
 */
suspend fun PortFiles.recover(into: String) {
    val aside = "$into.old"
    if (!move(aside, into)) remove(aside)
}
