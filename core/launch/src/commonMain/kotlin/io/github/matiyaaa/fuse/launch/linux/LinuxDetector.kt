package io.github.matiyaaa.fuse.launch.linux

import io.github.matiyaaa.fuse.launch.RetroArchCores
import io.github.matiyaaa.fuse.launch.pc.DesktopEntry
import io.github.matiyaaa.fuse.launch.pc.ShortcutParser
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator

/**
 * Finds installed Linux emulators with pure functions over a [LinuxEnvironment]. Search order per
 * emulator, like ES-DE's find rules: program on `$PATH`, then Flatpak, then AppImages and portable
 * folders in [searchDirs]. No EmuDeck layout is assumed.
 */
object LinuxDetector {
    /** Home-relative folders searched for AppImages and portable builds (ES-DE's staticpath folders plus ~/Downloads). */
    val DEFAULT_SEARCH_DIRS: List<String> = listOf(
        "Applications", ".local/bin", "AppImages", "Downloads", ".local/share/applications", "bin",
    )

    /** A located program. */
    data class Found(val kind: LinuxInstallKind, val appId: String)

    /** User-provided folders first, then the defaults under the home folder. */
    fun searchDirs(env: LinuxEnvironment, extraDirs: List<String> = emptyList()): List<String> =
        (extraDirs.map { it.trimEnd('/') } + DEFAULT_SEARCH_DIRS.map { "${env.homeDir.trimEnd('/')}/$it" }).distinct()

    /** Application ids from `flatpak list --app --columns=application` (tolerates a header or extra columns). */
    fun parseFlatpakList(output: String): Set<String> = output.lineSequence()
        .map { it.trim().split(Regex("\\s+")).first() }
        .filter { it.count { c -> c == '.' } >= 2 && it.none { c -> c == '/' } }
        .toSet()

    /** Absolute path of [binary] on `$PATH`, or null. */
    fun findOnPath(env: LinuxEnvironment, binary: String): String? =
        env.pathDirectories().map { "${it.trimEnd('/')}/$binary" }.firstOrNull(env::isExecutable)

    /**
     * AppImages in [dirs] whose names say nothing, by what they update from: path to release name
     * ([AppImageInfo]). Read once per detection, and only for files no emulator's globs already know.
     */
    fun identifyAppImages(env: LinuxEnvironment, dirs: List<String>, defs: List<LinuxEmulatorDef> = LinuxCatalog.defs): Map<String, String> {
        val globs = defs.flatMap { it.detection.appImageGlobs }
        val out = LinkedHashMap<String, String>()
        for (dir in dirs) for (name in env.listFiles(dir)) {
            if (globs.any { Glob.matches(it, name) }) continue
            val lower = name.lowercase()
            // AppImages keep their extension, or none at all once renamed; nothing else is opened.
            if (!lower.endsWith(".appimage") && '.' in name.removePrefix(".")) continue
            val path = "$dir/$name"
            if (!env.isExecutable(path) && !lower.endsWith(".appimage")) continue
            AppImageInfo.releaseName(env, path)?.let { out[path] = it }
        }
        return out
    }

    /** Where [def] is installed, or null. [flatpaks] and [dirs] are passed in so they are read once. */
    fun locate(
        env: LinuxEnvironment,
        def: LinuxEmulatorDef,
        flatpaks: Set<String>,
        dirs: List<String>,
        identified: Map<String, String> = emptyMap(),
    ): Found? {
        val d = def.detection
        val home = env.homeDir.trimEnd('/')
        if (d.requiredFiles.isNotEmpty() && d.requiredFiles.none { env.exists("$home/$it") }) return null
        d.binaries.firstNotNullOfOrNull { findOnPath(env, it) }?.let { return Found(LinuxInstallKind.PATH, it) }
        d.flatpakIds.firstOrNull { it in flatpaks }?.let { return Found(LinuxInstallKind.FLATPAK, it) }
        for (dir in dirs) {
            if (d.appImageGlobs.isNotEmpty()) {
                // Newest-looking name first when several versions sit side by side.
                env.listFiles(dir).sortedDescending().firstOrNull { name ->
                    d.appImageGlobs.any { Glob.matches(it, name) } && d.appImageExcludes.none { Glob.matches(it, name) }
                }?.let { return Found(LinuxInstallKind.APPIMAGE, "$dir/$it") }
            }
            d.dirBinaries.firstOrNull { env.isExecutable("$dir/$it") }?.let { return Found(LinuxInstallKind.FOLDER, "$dir/$it") }
        }
        // A renamed AppImage, known by the release it updates from.
        if (d.appImageGlobs.isNotEmpty()) {
            identified.entries.firstOrNull { (_, release) ->
                d.appImageGlobs.any { Glob.matches(it, release) } && d.appImageExcludes.none { Glob.matches(it, release) }
            }?.let { return Found(LinuxInstallKind.APPIMAGE, it.key) }
        }
        return null
    }

    /**
     * Every installed emulator in [defs], plus the built-in `.desktop` adapter (using `gio launch` when
     * gio is on `$PATH`).
     */
    fun detect(
        env: LinuxEnvironment,
        defs: List<LinuxEmulatorDef> = LinuxCatalog.defs,
        extraDirs: List<String> = emptyList(),
    ): List<InstalledEmulator> {
        val flatpaks = env.flatpakApps()
        val dirs = searchDirs(env, extraDirs)
        val identified = identifyAppImages(env, dirs, defs)
        val found = defs.mapNotNull { def -> locate(env, def, flatpaks, dirs, identified)?.let { toInstalled(def, it) } }
        val gio = findOnPath(env, "gio")
        val desktop = InstalledEmulator(
            id = DesktopShortcutAdapter.id,
            name = DesktopShortcutAdapter.name,
            host = Host.LINUX,
            appId = gio ?: "builtin",
            platforms = DesktopShortcutAdapter.platforms,
            detectedVia = if (gio != null) LinuxInstallKind.PATH.label else LinuxInstallKind.BUILT_IN.label,
        )
        return found + desktop
    }

    fun toInstalled(def: LinuxEmulatorDef, found: Found): InstalledEmulator = InstalledEmulator(
        id = EmulatorId(def.id),
        name = def.name,
        host = Host.LINUX,
        appId = found.appId,
        platforms = def.platforms,
        detectedVia = found.kind.label,
    )

    /**
     * Folders a RetroArch install loads cores from, in ES-DE's core-path order: a Flatpak only sees its
     * own config folder; other installs use the portable AppImage home, the user config, then system
     * libretro folders.
     */
    fun retroArchCoreDirs(env: LinuxEnvironment, installed: InstalledEmulator): List<String> {
        val h = env.homeDir.trimEnd('/')
        val kind = LinuxInstallKind.of(installed.detectedVia)
        if (kind == LinuxInstallKind.FLATPAK) return listOf("$h/.var/app/${installed.appId}/config/retroarch/cores")
        val appImageHome = if (kind == LinuxInstallKind.APPIMAGE) listOf("${installed.appId}.home/.config/retroarch/cores") else emptyList()
        return appImageHome + listOf(
            "$h/.config/retroarch/cores",
            "$h/.config/retroarch/libretro",
            "$h/snap/retroarch/common/.config/retroarch/cores",
            "$h/snap/retroarch/current/.config/retroarch/cores",
            "/usr/lib/x86_64-linux-gnu/libretro",
            "/usr/lib64/libretro",
            "/usr/lib/libretro",
            "/run/current-system/sw/lib/retroarch/cores",
            "$h/.nix-profile/lib/retroarch/cores",
        )
    }

    /** Absolute path of [core]'s library for [installed], or null when it is not installed. */
    fun findRetroArchCore(env: LinuxEnvironment, installed: InstalledEmulator, core: String): String? =
        retroArchCoreDirs(env, installed).map { "$it/${RetroArchCores.linuxCoreFile(core)}" }.firstOrNull(env::exists)

    /** Reads and parses a `.desktop` file through [env]. */
    fun desktopEntry(env: LinuxEnvironment, path: String): DesktopEntry? = env.readText(path)?.let(ShortcutParser::parseDesktop)
}
