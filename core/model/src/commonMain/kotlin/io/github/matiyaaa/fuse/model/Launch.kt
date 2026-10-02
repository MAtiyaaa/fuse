package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/** Which operating system an adapter runs on. */
@Serializable
enum class Host {
    ANDROID,
    LINUX,
    WINDOWS,
    MACOS,
    ;

    /** Windows, macOS and Linux: emulators are programs Fuse starts, and there is no app list. */
    val isDesktop: Boolean get() = this != ANDROID
}

/**
 * What an emulator is actually given. Adapters turn a [Game] into one of these, and then into a
 * platform-specific [LaunchPlan].
 */
@Serializable
sealed interface LaunchTarget {
    @Serializable
    data class File(val path: String) : LaunchTarget

    /** A folder the emulator understands as a game (for example an extracted PS3 title). */
    @Serializable
    data class Directory(val path: String) : LaunchTarget

    /** A disc playlist. [generated] playlists live in Fuse's cache, never in the ROM folder. */
    @Serializable
    data class Playlist(val path: String, val generated: Boolean) : LaunchTarget

    /** A title id such as PCSB00245 for emulators that boot installed titles by id. */
    @Serializable
    data class TitleId(val id: String) : LaunchTarget

    /** An exported frontend shortcut (Winlator .desktop, GameNative/GameHub shortcut files). */
    @Serializable
    data class Shortcut(val path: String, val format: ShortcutFormat) : LaunchTarget

    /** An Android app (Apps section, Android games) or a Linux .desktop application. */
    @Serializable
    data class App(val id: String) : LaunchTarget
}

@Serializable
enum class ShortcutFormat { WINLATOR_DESKTOP, FREEDESKTOP_DESKTOP, STEAM_URL, GAMENATIVE, GAMEHUB, UNKNOWN }

/** The result of asking an adapter how to start a game. Nothing is started until the UI runs it. */
@Serializable
sealed interface LaunchPlan {
    val emulatorId: EmulatorId

    /** Start an Android activity. Built by the Android app from this description. */
    @Serializable
    data class AndroidIntent(
        override val emulatorId: EmulatorId,
        val packageName: String,
        val activity: String?,
        val action: String?,
        val category: String?,
        /** Intent data, with tokens already resolved except {URI}/{SAF}/{PROVIDER}, which need Android. */
        val data: String?,
        val stringExtras: Map<String, String> = emptyMap(),
        val arrayExtras: Map<String, List<String>> = emptyMap(),
        val boolExtras: Map<String, Boolean> = emptyMap(),
        val clearTask: Boolean = false,
        val clearTop: Boolean = false,
        /** The file or folder the tokens refer to. */
        val target: LaunchTarget,
    ) : LaunchPlan

    /** Run a process (Linux). */
    @Serializable
    data class Command(
        override val emulatorId: EmulatorId,
        val argv: List<String>,
        val workingDir: String? = null,
        val env: Map<String, String> = emptyMap(),
        val target: LaunchTarget,
    ) : LaunchPlan

    /**
     * The emulator has no documented way to start a specific game from outside. Fuse opens the app
     * and says so, instead of failing silently or pretending.
     */
    @Serializable
    data class OpenAppOnly(
        override val emulatorId: EmulatorId,
        val appId: String,
        val reason: String,
    ) : LaunchPlan

    /** Nothing can start this game with this emulator. */
    @Serializable
    data class Unsupported(
        override val emulatorId: EmulatorId,
        val reason: String,
    ) : LaunchPlan
}

/** How well an adapter handles a folder game. */
@Serializable
enum class FolderSupport {
    /** The emulator takes the folder itself. */
    DIRECTORY,
    /** The adapter can find the right file inside the folder. */
    RESOLVES_FILE,
    /** Folders can't be launched. */
    NONE,
}

/** What an emulator can do with a game's DLC, updates and other child content. */
@Serializable
enum class ContentSupport {
    /** The emulator finds installed content on its own. */
    AUTOMATIC,
    /** Content paths can be passed when launching. */
    LAUNCH_ARGUMENTS,
    /** The user installs content inside the emulator first; Fuse shows where it is. */
    INSTALL_IN_EMULATOR,
    /** No external way is known. */
    UNSUPPORTED,
    /** Not relevant for this platform. */
    NOT_APPLICABLE,
}

/** Tri-state for things only some devices or emulators report. */
@Serializable
enum class Support { YES, NO, UNKNOWN }

@Serializable
data class AdapterCapabilities(
    val folders: FolderSupport = FolderSupport.NONE,
    val dlc: ContentSupport = ContentSupport.NOT_APPLICABLE,
    val updates: ContentSupport = ContentSupport.NOT_APPLICABLE,
    val playlists: Boolean = false,
    val titleIdLaunch: Boolean = false,
    val secondaryDisplay: Support = Support.UNKNOWN,
    val openSettings: Boolean = false,
)

/** One installed emulator/launcher that Fuse found. */
@Serializable
data class InstalledEmulator(
    val id: EmulatorId,
    val name: String,
    val host: Host,
    /** Android package or Linux executable/Flatpak id actually found. */
    val appId: String,
    val version: String? = null,
    val platforms: Set<PlatformId>,
    /** How it was found, for Settings, Systems and emulators (for example "Flatpak", "PATH", "AppImage"). */
    val detectedVia: String,
    /** True when this is a fork/rename matched by family rather than an exact known package. */
    val isFamilyMatch: Boolean = false,
)
