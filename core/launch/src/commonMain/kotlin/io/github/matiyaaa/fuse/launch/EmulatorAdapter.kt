package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.AdapterCapabilities
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId
import kotlinx.serialization.Serializable

/** How sure Fuse is that an adapter's launch description matches the real emulator. */
@Serializable
enum class Confidence {
    /** Present in ES-DE's MIT-licensed es_find_rules.xml / es_systems.xml (see [Sources.ESDE]). */
    VERIFIED_ESDE,

    /** Confirmed in the emulator's own source, manifest or vendor documentation. */
    VERIFIED_SOURCE,

    /** Only community configs or source mirrors describe it. Usable, but marked in Settings. */
    COMMUNITY,

    /** Not confirmed anywhere. Such adapters only open the app ([LaunchPlan.OpenAppOnly]). */
    UNVERIFIED,
}

/** Whether an adapter can, or must, start a game by its title id instead of a file. */
@Serializable
enum class TitleIdMode {
    /** Files only. */
    NONE,

    /** Title ids work (id files such as `.ps3`, or when the user asks for it), files are preferred. */
    OPTIONAL,

    /** A title id found in the file name beats the file. */
    PREFERRED,

    /** Only installed titles can start, by title id (Vita3K, BachataS4). */
    REQUIRED,
}

/** Per-launch inputs that don't live on the [Game]. */
data class LaunchOptions(
    /** RetroArch core name without suffix, for example "mgba". Null means the platform's default core. */
    val core: String? = null,
    /** Linux: absolute path of the RetroArch core file, found by [io.github.matiyaaa.fuse.launch.linux.LinuxDetector.findRetroArchCore]. */
    val corePath: String? = null,
    /** Android display to start the activity on (ActivityOptions.setLaunchDisplayId), when chosen. */
    val displayId: Int? = null,
    /** True when the user asked for the secondary screen. */
    val secondaryDisplay: Boolean = false,
    /** Path of a disc playlist Fuse generated in its cache for this launch. */
    val generatedPlaylistPath: String? = null,
    /**
     * Content of a small id file (`.steam`, `.psvita`, `.ps3`, `.ps4`, `.app`, `.scummvm`, `.desktop`),
     * read by the caller (at most [IdFiles.MAX_BYTES]). See [IdFiles.needsContent].
     */
    val injectedText: String? = null,
    /** Linux: the user's home directory, used to expand `~` in default paths. */
    val homeDir: String? = null,
)

/** Everything an adapter needs to describe one launch. [target] is already resolved by [LaunchResolver]. */
data class LaunchRequest(
    val game: Game,
    val installed: InstalledEmulator,
    val target: LaunchTarget,
    val options: LaunchOptions = LaunchOptions(),
)

/**
 * One way of starting games: an Android emulator/launcher or a Linux program. Adapters are data
 * driven ([io.github.matiyaaa.fuse.launch.android.AndroidEmulatorDef],
 * [io.github.matiyaaa.fuse.launch.linux.LinuxEmulatorDef]); they never start anything themselves and
 * never change emulator configuration. They only describe a [LaunchPlan].
 */
interface EmulatorAdapter {
    val id: EmulatorId
    val name: String
    val host: Host

    /** Platforms this adapter can run, as canonical Fuse ids (RomM slugs). */
    val platforms: Set<PlatformId>
    val capabilities: AdapterCapabilities

    /** Human readable caveats, shown in Settings -> Emulators. */
    val limitations: List<String>

    /** Where the launch description comes from (ES-DE commit, source file, vendor doc). */
    val source: String
    val confidence: Confidence

    /** Where to get it (official site or repository), for "Get emulator". */
    val homepage: String?

    /** How this adapter uses title ids. */
    val titleIdMode: TitleIdMode get() = TitleIdMode.NONE

    /** Lower-case extensions (no dot) of id files whose content this adapter needs in [LaunchOptions.injectedText]. */
    val idFileExtensions: Set<String> get() = emptySet()

    /** Always available without detection (native Android apps, Linux `.desktop` shortcuts). */
    val builtIn: Boolean get() = false

    /**
     * Opens shortcuts and programs of their own (a `.desktop`, `.lnk` or `.app`), not a system's
     * games: it only counts as a system's emulator where [EmulatorPriority] names it (Steam and PC
     * games), never for a console just because it accepts any system's shortcuts.
     */
    val shortcutsOnly: Boolean get() = false

    /** True when this adapter can never start a specific game and only opens the app. */
    val opensAppOnly: Boolean get() = false

    /** Whether [target] is something this adapter has a launch mode for. */
    fun accepts(target: LaunchTarget, platform: PlatformId): Boolean = true

    /** Emulator-specific wording for where to install [kind] content (for example "Install to NAND"). */
    fun installHint(kind: ContentKind): String? = null

    /** File types it installs from its command line (`pkg`), empty when it can't. */
    val packageExtensions: Set<String> get() = emptySet()

    /** True when installing a package needs its licence key (Vita3K's zRIF). */
    val packageNeedsKey: Boolean get() = false

    /**
     * The command that installs the package at [path] in [installed], with [key] when it needs one;
     * null when it can't install that file. Nothing is run here.
     */
    fun packageInstall(installed: InstalledEmulator, path: String, key: String? = null): LaunchPlan.Command? = null

    /** Describes how to start [LaunchRequest.game]. Pure: no I/O, nothing is launched. */
    fun plan(request: LaunchRequest): LaunchPlan
}

/** Source strings shared by many adapters. */
object Sources {
    /** ES-DE (MIT) commit whose es_find_rules.xml / es_systems.xml the ES-DE entries were checked against. */
    const val ESDE_COMMIT = "24428b95"
    const val ESDE = "ES-DE es_find_rules.xml / es_systems.xml master $ESDE_COMMIT (MIT)"
    const val ESDE_ANDROID = "ES-DE resources/systems/android es_find_rules.xml + es_systems.xml master $ESDE_COMMIT (MIT)"
    const val ESDE_LINUX = "ES-DE resources/systems/linux es_find_rules.xml + es_systems.xml master $ESDE_COMMIT (MIT)"
    const val ESDE_WINDOWS = "ES-DE resources/systems/windows es_find_rules.xml + es_systems.xml master, October 2026 (MIT)"
    const val ESDE_MACOS = "ES-DE resources/systems/macos es_find_rules.xml + es_systems.xml master, October 2026 (MIT)"
    const val RESEARCH = "docs/research/emulators.md"
}
