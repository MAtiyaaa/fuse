package io.github.matiyaaa.fuse.launch.linux

import io.github.matiyaaa.fuse.launch.Confidence
import io.github.matiyaaa.fuse.launch.LaunchMode
import io.github.matiyaaa.fuse.launch.TitleIdMode
import io.github.matiyaaa.fuse.model.AdapterCapabilities
import io.github.matiyaaa.fuse.model.FolderSupport
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Arguments after the executable, as templates. Tokens: `{ROM}`, `{ROMDIR}`, `{BASENAME}`,
 * `{CORE}`, `{CORE_PATH}`, `{SERIAL}`, `{INJECT}`, `{EMUDIR}` (see [io.github.matiyaaa.fuse.launch.LaunchTokens]).
 * Every flag comes from ES-DE's Linux es_systems.xml unless the entry's source says otherwise.
 *
 * @param workingDir optional template (ES-DE `%STARTDIR%`); a leading `~` is expanded when the home
 *   directory is known ([io.github.matiyaaa.fuse.launch.LaunchOptions.homeDir]).
 */
data class LinuxCommandSpec(val args: List<String>, val workingDir: String? = null)

/**
 * Where to look for a program, from ES-DE's Linux es_find_rules.xml (`systempath` and `staticpath`).
 * No EmuDeck layout is assumed.
 *
 * @param binaries names searched on `$PATH`.
 * @param flatpakIds Flatpak application ids, run as `flatpak run [--command=...] <id>`.
 * @param flatpakCommand `--command=` for Flatpaks whose main program is not the emulator (Mednaffe ships Mednafen).
 * @param appImageGlobs case-insensitive file name globs searched in [LinuxDetector.searchDirs].
 * @param appImageExcludes globs that must not match (so "azahar*" does not pick up AzaharPlus).
 * @param dirBinaries relative paths such as "Cemu/Cemu" searched inside [LinuxDetector.searchDirs].
 * @param requiredFiles home-relative files of which one must exist (RetroArch on Steam: its app manifest).
 */
data class LinuxDetection(
    val binaries: List<String> = emptyList(),
    val flatpakIds: List<String> = emptyList(),
    val flatpakCommand: String? = null,
    val appImageGlobs: List<String> = emptyList(),
    val appImageExcludes: List<String> = emptyList(),
    val dirBinaries: List<String> = emptyList(),
    val requiredFiles: List<String> = emptyList(),
)

/** One Linux emulator or launcher, as data. [LinuxCommandAdapter] turns it into an adapter. */
data class LinuxEmulatorDef(
    val id: String,
    val name: String,
    val platforms: Set<PlatformId>,
    val detection: LinuxDetection,
    val modes: List<LaunchMode<LinuxCommandSpec>>,
    val source: String,
    val confidence: Confidence,
    val homepage: String? = null,
    val capabilities: AdapterCapabilities = AdapterCapabilities(folders = FolderSupport.RESOLVES_FILE),
    val limitations: List<String> = emptyList(),
    val titleIdMode: TitleIdMode = TitleIdMode.NONE,
    val openAppOnlyReason: String? = null,
    val usesRetroArchCores: Boolean = false,
    val installHint: String? = null,
)

/** How a Linux program was found. [label] is what [io.github.matiyaaa.fuse.model.InstalledEmulator.detectedVia] holds. */
enum class LinuxInstallKind(val label: String) {
    PATH("PATH"),
    FLATPAK("Flatpak"),
    APPIMAGE("AppImage"),
    FOLDER("Folder"),
    BUILT_IN("Built in"),
    ;

    companion object {
        fun of(detectedVia: String): LinuxInstallKind? = entries.firstOrNull { it.label == detectedVia }
    }
}
