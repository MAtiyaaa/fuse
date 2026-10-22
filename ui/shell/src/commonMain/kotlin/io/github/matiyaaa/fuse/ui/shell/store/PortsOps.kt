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
     * The port [id]'s game for its files [dataId] on RomM or the household's other devices, for
     * when this device hasn't got it: brought here with [bringGame], and then read by the port.
     */
    suspend fun copiesElsewhere(id: String, dataId: String): List<PortGameCopy> = emptyList()

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

    /** Recomps the person installed themselves on this computer (a Flatpak, an AppImage, a program). */
    suspend fun yourRecomps(): List<FoundRecomp> = emptyList()

    /** The person's own recomps that play [game]: made from its very ROM (by its hash), or of the same game by name. */
    suspend fun yourRecompsFor(game: GameId): List<FoundRecomp> = emptyList()

    /**
     * Play starts [game] with the person's own [recomp] from now on: one entry, started as they
     * installed it, its saves kept in step with every device's copy of the same recomp. Null when
     * done, else why not.
     */
    suspend fun playWithYours(game: GameId, recomp: FoundRecomp): String? = "Recomps are played from Fuse on a computer."

    /** The recomp Play starts [game] with (Fuse's copy or the person's own), with its folder; null when it starts with an emulator. */
    suspend fun recompFor(game: GameId): RecompPlace? = null

    /** The mods and texture packs in [place]'s mods folder. */
    suspend fun recompMods(place: RecompPlace): List<RecompMod> = emptyList()

    /** Adds the mods in [file] (a mod, a texture pack or a zip of them); null when added, else why not. */
    suspend fun addRecompMod(place: RecompPlace, file: String): String? = "Mods are added from Fuse on a computer."

    /** Takes the mod [name] out of [place]'s mods folder. */
    suspend fun removeRecompMod(place: RecompPlace, name: String): Boolean = false

    /** [place]'s graphics settings with the choice each has now (null when its file doesn't say). */
    suspend fun recompGraphics(place: RecompPlace): List<Pair<io.github.matiyaaa.fuse.launch.ports.RecompSetting, io.github.matiyaaa.fuse.launch.ports.RecompChoice?>> = emptyList()

    /** Sets one graphics setting in [place]'s own file; false when the file couldn't be read or written. */
    suspend fun setRecompGraphic(place: RecompPlace, key: String, choice: io.github.matiyaaa.fuse.launch.ports.RecompChoice): Boolean = false

    /** The controls profiles Fuse keeps for [place]'s recomp, by name. */
    suspend fun recompProfiles(place: RecompPlace): List<String> = emptyList()

    /** Keeps [place]'s controls as they are now as the profile [name]; null when kept, else why not. */
    suspend fun saveRecompProfile(place: RecompPlace, name: String): String? = "Profiles are kept from Fuse on a computer."

    /** Puts the profile [name]'s controls in place for [place]; null when done, else why not. */
    suspend fun useRecompProfile(place: RecompPlace, name: String): String? = "Profiles are kept from Fuse on a computer."

    /** Forgets the profile [name]. */
    suspend fun removeRecompProfile(place: RecompPlace, name: String): Boolean = false

    /** [place]'s recomp's newest release, from its project; null when it couldn't be asked. */
    suspend fun recompRelease(place: RecompPlace): RecompRelease? = null

    /** Updates the person's own Flatpak of [place]'s recomp; null when done, else why not. */
    suspend fun updateYourRecomp(place: RecompPlace): String? = "Only a Flatpak is updated from Fuse."

    // Android: ports published as apps, attached to the games they play.

    /** True where ports are Android apps (on Android): Ports shows [android]. */
    val androidApps: Boolean get() = false

    /** The Android ports, each as it stands here; empty where ports aren't apps (a computer). */
    val android: StateFlow<AndroidPortsState> get() = NO_ANDROID_PORTS

    /** The Android ports that play [game], installed or not, best first, with the one Play starts it with. For Play With. */
    suspend fun androidPortsFor(game: GameId): List<AndroidPortOffer> = emptyList()

    /**
     * Installs the Android port [id] from its project's releases (Android asks the person), then
     * attaches it to [forGame], or to its one game in the library, and plays that game with it.
     */
    fun installAndroid(id: String, forGame: GameId? = null) = Unit

    /** Asks Android to uninstall the port [id]'s app (Android asks the person). */
    fun uninstallAndroid(id: String) = Unit

    /** Opens the port [id]'s app. */
    fun openAndroid(id: String): Boolean = false

    /** The installed app (package/activity) Play starts [game] with: a recomp or port attached to it; null: its emulator. */
    fun androidPlayWith(game: GameId): String? = null

    /** Plays [game] with the attached app [appId] from now on; null: with its emulator again. */
    suspend fun setAndroidPlayWith(game: GameId, appId: String?) = Unit

    /**
     * Attaches the installed app [appId] to the library [game] as a recomp or port of it (its Type
     * becomes Recomp or Port), and plays the game with it from now on. Null when done, else why not.
     */
    suspend fun attach(appId: String, game: GameId): String? = "Ports are attached to games on Android."

    /** Detaches the app [appId] from its game; Play goes back to the emulator where it was the choice. */
    suspend fun detach(appId: String) = Unit

    /** Attaches the app [packageName] to [game] once Android has installed it (at once when it is). */
    suspend fun attachOnceInstalled(packageName: String, game: GameId) = Unit

    /**
     * The library games an app ([packageName], named [label]) may be a recomp or port of, for "What
     * game is this?": the port it is, when Fuse knows it, and the games it plays first; then the
     * games most like its name, or like [query] when the person typed one.
     */
    suspend fun gamesForApp(packageName: String, label: String, query: String? = null): AppPortGuess = AppPortGuess()

    /** What the APK at [path] is, when it is a port Fuse knows (read before it is installed). */
    suspend fun apkPort(path: String): ApkPort? = null

    /**
     * Brings [copy], the port [id]'s game on RomM or another of the household's devices, here, and
     * attaches the port to it once it is in (once the port is installed, when it isn't yet). Null
     * when it was asked for, else why not.
     */
    suspend fun bringGame(id: String, copy: PortGameCopy): String? = "Ports are attached to games on Android."

    object None : PortsOps {
        override val state: StateFlow<PortsState> = MutableStateFlow(PortsState())
    }
}

private val NO_ANDROID_PORTS: StateFlow<AndroidPortsState> = MutableStateFlow(AndroidPortsState())

/** How a library game was found to be a port's: by its ROM's hash, by its name alone, or because the person attached the app to it. */
enum class PortMatch { ROM, NAME, ATTACHED }

/**
 * The Android ports as Ports shows them: Fuse's own checked entries beside Open Ports Games'
 * listings ([listedAt]: the catalogue's date), each as it stands here.
 */
@Immutable
data class AndroidPortsState(
    val ports: List<AndroidPortView> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    /** Why the last look for newer lists failed; null when it worked or wasn't made. */
    val problem: String? = null,
    val listedAt: String? = null,
) {
    fun port(id: String): AndroidPortView? = ports.firstOrNull { it.port.id == id }
}

/**
 * One Android port here: its app when installed ([packageName], [appId] its launcher entry and
 * [installedVersion]), the install in progress or failed, the library games it plays ([games]) and
 * the ones it is attached to ([attachedTo]).
 */
@Immutable
data class AndroidPortView(
    val port: io.github.matiyaaa.fuse.model.AndroidPort,
    val packageName: String? = null,
    val appId: String? = null,
    val installedVersion: String? = null,
    val job: PortJob? = null,
    val games: List<PortGameChoice> = emptyList(),
    val attachedTo: List<PortGameChoice> = emptyList(),
    /** Box art for its game found by its name, when [port] has none of its own. */
    val foundCover: String? = null,
    /** Its game where this device hasn't got it (only while it has none here): on RomM, or on the household's other devices. */
    val elsewhere: List<PortGameCopy> = emptyList(),
    /** The copy of its game Fuse is bringing here, to attach it once it is in. */
    val bringing: PortGameCopy? = null,
) {
    val installed: Boolean get() = appId != null

    /** The box art of its game: its own, else one found by its name. */
    val cover: String? get() = port.cover ?: foundCover

    /** Its game is on this device. */
    val here: Boolean get() = games.isNotEmpty() || attachedTo.isNotEmpty()
}

/**
 * A port's game where this device hasn't got it: on RomM, or on another of the household's devices
 * (Fuse One). Fuse can bring it here from there.
 */
@Immutable
data class PortGameCopy(
    /** The game as Fuse shows it: RomM's id for it, or the household's. */
    val id: GameId,
    val title: String,
    val platform: io.github.matiyaaa.fuse.model.PlatformId,
    /** On RomM, or on another device ([CopyPlace.DEVICE]). */
    val place: CopyPlace,
    /** Where it is, as the person reads it: "RomM", or the device's name. */
    val from: String,
    /** RomM's id for it, for a copy on RomM. */
    val romId: Long? = null,
    val sizeBytes: Long = 0,
    /** A device that has it is on now (always for RomM). */
    val online: Boolean = true,
    /** Its art, as Fuse draws the game. */
    val art: Art = Art.None,
)

/** An Android port for one game, as Play With offers it: how it is the game's, and whether Play starts the game with it. */
@Immutable
data class AndroidPortOffer(val view: AndroidPortView, val match: PortMatch, val chosen: Boolean)

/** What an installed app may be a port of: the [port] Fuse knows it as, and the library's likeliest [games], best first. */
@Immutable
data class AppPortGuess(val port: io.github.matiyaaa.fuse.model.AndroidPort? = null, val games: List<PortGameChoice> = emptyList())

/** A library game offered for "What game is this?"; [plays]: the known port plays it. */
@Immutable
data class PortGameChoice(val id: GameId, val title: String, val system: String, val plays: Boolean)

/** An APK that is a port Fuse knows: its package and the library games it plays. */
@Immutable
data class ApkPort(val port: io.github.matiyaaa.fuse.model.AndroidPort, val packageName: String, val games: List<PortGameChoice>)

/** The lists of Android ports inside Fuse: its own (`ports/index.json`) and Open Ports Games' snapshot. */
interface AndroidPortLists {
    /** Fuse's list of ports, whose `android` entries are its checked Android ports; null when it isn't there. */
    fun bundledIndex(): String?

    /** Open Ports Games' catalogue as Fuse carries it (`ports/android/openports.json`); null when it isn't there. */
    fun bundledListing(): String?
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
 * its build here was made with ([builtWith], null before it is recompiled), what the person should
 * know first ([warning]: a processor without an extension the game's code needs), and whether its
 * build takes a copy of the dump's files ([copies]: where the dump can't be kept read-only for it).
 */
@Immutable
data class RecompileOffer(val whyNot: String? = null, val builtWith: String? = null, val warning: String? = null, val copies: Boolean = false)

/** A game of the library whose [folder] holds a port's game files. */
@Immutable
data class PortCopy(val game: GameId, val title: String, val system: String, val folder: String)

/** An install in progress, or one that failed. */
sealed interface PortJob {
    data class Downloading(val progress: Float?, val bytes: Long, val total: Long?) : PortJob
    data object Unpacking : PortJob
    data object SettingUp : PortJob

    /** Android is installing an app: [waitingTurn] while another install has the system's installer. */
    data class Installing(val waitingTurn: Boolean = false) : PortJob
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
     * Makes [path], the program a build's list names, runnable where a system asks for it (an
     * archive made on Windows keeps no Unix permissions); false when it couldn't.
     */
    suspend fun makeRunnable(path: String): Boolean = true

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

    /** True when anything is at [path] (a file, a folder or a link, which isn't followed). */
    suspend fun exists(path: String): Boolean

    /**
     * A program that starts another with a folder of the person's read-only for it alone (bubblewrap's
     * `bwrap`, on Linux), when it is installed; null where there is none.
     */
    suspend fun readOnlyRunner(): String? = null

    /**
     * [from] (one of the person's files or folders, read where links lead) copied to [to] in Fuse's
     * folder, which must not be there yet; nothing is ever written to [from]. Null when copied, else
     * why not (not enough room said with the sizes); nothing is left at [to] when it wasn't.
     */
    suspend fun copy(from: String, to: String): String? = "Fuse can't copy files on this device."

    /** [path] with its links resolved, as the system finds it; [path] itself when it can't be. */
    suspend fun realPath(path: String): String = path
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
 * nothing took its place, and goes only when something did (the new copy, which went in whole).
 * When it can't go back (a file in use), it stays aside for the next time: it may be the only copy.
 */
suspend fun PortFiles.recover(into: String) {
    val aside = "$into.old"
    if (!exists(aside)) return
    if (exists(into)) remove(aside) else move(aside, into)
}
