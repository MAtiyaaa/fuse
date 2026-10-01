package io.github.matiyaaa.fuse.launch.desktop

import io.github.matiyaaa.fuse.launch.linux.LinuxEmulatorDef

/**
 * The few file system and environment facts the Windows and macOS detectors need. The desktop app
 * implements it with real I/O; tests use a fake. Paths use forward slashes ("C:/Emulators/x.exe").
 */
interface HostFiles {
    /** The user's home folder. */
    val homeDir: String

    /** An environment variable, or null. */
    fun env(name: String): String?

    /** Folders listed in PATH, in order. */
    fun pathDirectories(): List<String>

    fun isFile(path: String): Boolean

    fun isDirectory(path: String): Boolean

    /** Names of the entries directly inside [dir]; empty when it is missing or unreadable. */
    fun list(dir: String): List<String>

    /** Text of a small file (an Info.plist, Steam's library list), or null. */
    fun readText(path: String): String?

    /** Windows drive roots ("C:/", "D:/"); empty elsewhere. */
    fun drives(): List<String> = emptyList()
}

/**
 * Where to look for a program on Windows or macOS (ES-DE's windows and macos find rules).
 *
 * @param programs file names: Windows executables ("PPSSPPWindows64.exe"), or programs on macOS's
 *   PATH and Homebrew folders ("mednafen").
 * @param bundles macOS app bundle name globs ("PPSSPP*.app").
 * @param folders globs one of which the program's folder must match, for names that other programs
 *   share (DOSBox Staging's "dosbox.exe").
 * @param excludes folder or bundle name globs that must not match (so "azahar*" skips AzaharPlus).
 * @param opener when set, finding the program only proves it is installed and this runs instead
 *   (macOS `open` for Steam links).
 */
data class DesktopFind(
    val programs: List<String> = emptyList(),
    val bundles: List<String> = emptyList(),
    val folders: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val opener: String? = null,
)

/** One Windows or macOS emulator: the shared command definition and where to find it. */
data class DesktopEmulatorDef(val def: LinuxEmulatorDef, val find: DesktopFind)

/** How a Windows or macOS program was found. [label] is what [io.github.matiyaaa.fuse.model.InstalledEmulator.detectedVia] holds. */
enum class DesktopInstallKind(val label: String) {
    PATH("PATH"),
    FOLDER("Folder"),
    APP("App"),
    STEAM("Steam"),
    LOCATED("Located"),
    BUILT_IN("Built in"),
    ;

    companion object {
        fun of(detectedVia: String): DesktopInstallKind? = entries.firstOrNull { it.label == detectedVia }
    }
}
