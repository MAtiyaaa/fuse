package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * Fuse Ports: games rebuilt to run natively (source ports), described as data. Each port is a
 * program its own project publishes, and the game's own files, which the person has from their own
 * copy of the game: Fuse installs the program into its own folder and links the person's files in,
 * never copying or changing them. The index is `ports/index.json` in Fuse's repository; one comes
 * inside Fuse, and a newer one is used only when its Ed25519 signature verifies (see
 * INTEGRATIONS.md, Fuse Ports).
 */
@Serializable
data class PortsIndex(
    /** The index's format; Fuse reads [FORMAT] and refuses a newer one rather than guess. */
    val format: Int,
    /** Grows with every published index, so an older one never replaces a newer one. */
    val revision: Long,
    /** When it was made (ISO 8601), as said; never used to decide anything. */
    val generated: String? = null,
    val ports: List<Port>,
    /** Ports published as Android apps that Fuse checked against their own source ([AndroidPort.checked]). */
    val android: List<AndroidPort> = emptyList(),
) {
    companion object {
        const val FORMAT = 1
    }
}

/**
 * One port: the program ([builds], one per system and processor), what it needs from the person's
 * copy of the game ([data]) and how it is started ([args], with the placeholders in [PortTokens]).
 * [source] cites where each fact comes from in the port's own code or documentation.
 */
@Serializable
data class Port(
    /** Fuse's id for the port: lower case letters, digits and dashes. */
    val id: String,
    /** The port's own name ("DevilutionX"). */
    val name: String,
    /** The game it plays ("Diablo"). */
    val game: String,
    val about: String,
    val homepage: String,
    /** The port's licence, as its project names it. */
    val licence: String? = null,
    /** The version [builds] are of, as the project tags it. */
    val version: String,
    val builds: List<PortBuild>,
    val data: List<PortData> = emptyList(),
    /** The program's arguments, with [PortTokens] placeholders. */
    val args: List<String> = emptyList(),
    /** Variables it is started with, their values with [PortTokens] placeholders (`APP_FOLDER_PATH` = `{home}`). */
    val env: Map<String, String> = emptyMap(),
    /** Where the port keeps its saves, relative to its home ([PortTokens.HOME]); null when it keeps none there. */
    val saves: String? = null,
    /** How the nightly check sees it start ([PortCheck]); null when it isn't checked. */
    val check: PortCheck? = null,
    /** What to know before playing, in the port's own terms. */
    val notes: List<String> = emptyList(),
    val source: String,
    /** Its own settings file, which Fuse Tune shows (read from the port's code as the family [id]); null when it doesn't. */
    val settings: PortSettings? = null,
    /** The original game's box art: an HTTPS picture, shown before the port's game is in the library. */
    val cover: String? = null,
    /** Where [cover] comes from ("Wikipedia"). */
    val coverCredit: String? = null,
)

/**
 * Where a port keeps its own settings: [file], under its home or its copy (`{home}/config/diablo.ini`,
 * with [PortTokens.HOME] or [PortTokens.INSTALL] first), and how it is written ("ini").
 */
@Serializable
data class PortSettings(val file: String, val format: String = "ini")

/**
 * One build of a port: its file at [url] (HTTPS, [size] bytes, [sha256] the lower-case hex digest it
 * must match), what kind of file it is, and the program inside it to start ([run], a path in the
 * unpacked build with `/`). [verified] is the day the nightly check last saw this exact file
 * install and start, and is only believed from a signed index.
 */
@Serializable
data class PortBuild(
    /** "linux", "windows" or "macos". */
    val os: String,
    /** "x86_64" or "arm64". */
    val arch: String = "x86_64",
    val url: String,
    val size: Long,
    val sha256: String,
    val kind: PortFileKind,
    val run: String,
    /**
     * How it runs: [PortRunner.NATIVE] on its own system, or [PortRunner.UMU] (a Windows build on
     * Linux, through umu-launcher and Proton).
     */
    val runner: PortRunner = PortRunner.NATIVE,
    val verified: String? = null,
)

@Serializable
enum class PortFileKind {
    /** An AppImage, kept as it is and made runnable. */
    APPIMAGE,

    /** A zip, unpacked. */
    ZIP,

    /** A gzip-compressed tar, unpacked with each file's own permissions. */
    TAR_GZ,

    /** An xz-compressed tar, unpacked with each file's own permissions. */
    TAR_XZ,

    /** A zip holding one gzip-compressed tar (as a project's build artifacts wrap one), the tar unpacked as [TAR_GZ]. */
    ZIP_TAR_GZ,

    /** One program file, kept as it is and made runnable. */
    FILE,
}

@Serializable
enum class PortRunner { NATIVE, UMU }

/**
 * Something the port needs from the person's copy of the game: a folder holding [needs], each a
 * list of names any one of which will do (paths inside the folder, with `/`). [exact] when the port
 * reads the names exactly as written, letter case and all; otherwise case is ignored, as the port
 * itself ignores it. With an [overlay], the folder's contents are linked into Fuse's copy;
 * otherwise the folder is handed to the port by its path (`{data:<id>}` in [Port.args]). A
 * recompilation needs one ROM instead ([rom]), and [needs] is empty.
 */
@Serializable
data class PortData(
    val id: String,
    /** What to choose, in a word or two ("Diablo's game files"). */
    val name: String,
    /** Where the person finds it (the CD, the GOG or Steam copy). */
    val about: String,
    val needs: List<List<String>>,
    val exact: Boolean = false,
    val overlay: PortOverlay? = null,
    val rom: PortRom? = null,
)

/**
 * The one ROM a recompilation is made from, of the library's [system] ("n64"): known by the
 * XXH3-64 hash of its big-endian form ([xxh3], 16 hex digits, as N64: Recompiled's runtime checks
 * it), and put where the port looks for it ([place], relative to its home): a link to the person's
 * file when it is already in that form, else a copy turned into it. The person's file never changes.
 */
@Serializable
data class PortRom(val system: String, val xxh3: String, val place: String)

/**
 * The person's folder linked into Fuse's copy at [into] (relative; empty for its top), entry by
 * entry, so the port finds the game's files beside its own program. [own] are the paths the port
 * writes: none is ever linked to the person's folder, so nothing the port writes reaches it. An own
 * folder is a link to the same path in the port's home instead (so saves outlive an update), and
 * an own file is left for the port to make in its copy.
 */
@Serializable
data class PortOverlay(val into: String = "", val own: List<String> = emptyList())

/**
 * How the nightly check sees a port start without the game's files: run with [args], it prints
 * something matching [expect] (a regular expression) and ends; or, with [stay], it is still running
 * after that many seconds, or ended by itself without crashing. [linux] and [windows] are that
 * system's own way where it differs (a Windows build that prints nothing to a console).
 */
@Serializable
data class PortCheck(
    val args: List<String> = emptyList(),
    val expect: String? = null,
    val stay: Int? = null,
    val linux: PortCheck? = null,
    val windows: PortCheck? = null,
)

/** The placeholders [Port.args] may use. */
object PortTokens {
    /** Fuse's copy of the port (its unpacked build), replaced by an update. */
    const val INSTALL = "{install}"

    /** The port's home in Fuse: its saves and settings, kept from one version to the next. */
    const val HOME = "{home}"

    /** The person's folder for the [PortData] with that id: `{data:game}`. */
    const val DATA_PREFIX = "{data:"
}
