package io.github.matiyaaa.fuse.launch.desktop

import io.github.matiyaaa.fuse.launch.Paths
import io.github.matiyaaa.fuse.launch.RetroArchCores
import io.github.matiyaaa.fuse.launch.linux.Glob
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator

/**
 * Finds macOS emulators with pure functions over [HostFiles]: app bundles in the Applications
 * folders (and the folders inside them, so /Applications/Emulators works), Downloads and the
 * Desktop, then Homebrew, MacPorts and PATH programs. A bundle runs through the program its
 * Info.plist names (`CFBundleExecutable`), as ES-DE's macos find rules do.
 */
object MacDetector {

    data class Found(val kind: DesktopInstallKind, val path: String)

    /** Folders searched for app bundles, with how deep folders that aren't bundles are opened. */
    fun bundleRoots(files: HostFiles, extraDirs: List<String> = emptyList()): List<Pair<String, Int>> {
        val home = files.homeDir.trimEnd('/')
        return (
            extraDirs.map { it.trimEnd('/') to 1 } +
                listOf("/Applications" to 2, "$home/Applications" to 2, "$home/Emulation" to 2, "$home/Downloads" to 1, "$home/Desktop" to 1)
            ).distinctBy { it.first }
    }

    /** Folders searched for command line programs: Homebrew (Apple silicon, Intel), MacPorts, then PATH. */
    fun programDirs(files: HostFiles): List<String> =
        (listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin") + files.pathDirectories().map { it.trimEnd('/') }).distinct()

    /** Every app bundle under [roots], by name, in search order. */
    fun bundles(files: HostFiles, roots: List<Pair<String, Int>>): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: String, depth: Int) {
            for (name in files.list(dir).sorted()) {
                if (name.startsWith(".")) continue
                val path = "$dir/$name"
                when {
                    name.endsWith(".app", ignoreCase = true) -> out += path
                    depth > 1 && files.isDirectory(path) -> walk(path, depth - 1)
                }
            }
        }
        roots.forEach { (dir, depth) -> if (files.isDirectory(dir)) walk(dir, depth) }
        return out
    }

    /**
     * The program inside [bundle]: `Contents/MacOS/<CFBundleExecutable>` from an XML Info.plist, else
     * the one named like the bundle, else the only one there.
     */
    fun bundleExecutable(files: HostFiles, bundle: String): String? {
        val macos = "$bundle/Contents/MacOS"
        files.readText("$bundle/Contents/Info.plist")?.let(::plistExecutable)?.let { exe ->
            "$macos/$exe".takeIf(files::isFile)?.let { return it }
        }
        val entries = files.list(macos).filter { !it.startsWith(".") && files.isFile("$macos/$it") }
        val named = Paths.baseName(bundle)
        val pick = entries.firstOrNull { it.equals(named, ignoreCase = true) } ?: entries.singleOrNull()
        return pick?.let { "$macos/$it" }
    }

    /** `CFBundleExecutable` from an XML property list (binary lists return null). */
    fun plistExecutable(plist: String): String? =
        Regex("<key>\\s*CFBundleExecutable\\s*</key>\\s*<string>([^<]+)</string>").find(plist)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    /** Where [def] is installed, or null. [bundles] and [dirs] are passed in so they are read once. */
    fun locate(files: HostFiles, def: DesktopEmulatorDef, bundles: List<String>, dirs: List<String>): Found? {
        val find = def.find
        for (glob in find.bundles) {
            val bundle = bundles.firstOrNull { b ->
                val name = Paths.fileName(b)
                Glob.matches(glob, name) && find.excludes.none { Glob.matches(it, name) }
            } ?: continue
            bundleExecutable(files, bundle)?.let { return Found(DesktopInstallKind.APP, it) }
        }
        for (program in find.programs) {
            dirs.map { "$it/$program" }.firstOrNull(files::isFile)?.let { return Found(DesktopInstallKind.PATH, it) }
        }
        return null
    }

    /**
     * The program to run for a path the user picked: a bundle's program, or the file itself. Null
     * when it is neither.
     */
    fun resolvePicked(files: HostFiles, path: String): String? {
        val p = path.trimEnd('/')
        return when {
            p.endsWith(".app", ignoreCase = true) -> bundleExecutable(files, p)
            files.isFile(p) -> p
            else -> null
        }
    }

    /**
     * Every installed emulator in [defs], plus the built-in apps and scripts adapter. [located] are
     * programs or bundles the user pointed Fuse at, by emulator id; they win.
     */
    fun detect(
        files: HostFiles,
        defs: List<DesktopEmulatorDef> = MacCatalog.defs,
        extraDirs: List<String> = emptyList(),
        located: Map<String, String> = emptyMap(),
    ): List<InstalledEmulator> {
        val bundles = bundles(files, bundleRoots(files, extraDirs))
        val dirs = programDirs(files)
        val found = defs.mapNotNull { def ->
            val chosen = located[def.def.id]?.let { resolvePicked(files, it) }?.let { Found(DesktopInstallKind.LOCATED, it) }
            val hit = chosen ?: locate(files, def, bundles, dirs) ?: return@mapNotNull null
            toInstalled(def, def.find.opener?.let { hit.copy(path = it) } ?: hit)
        }
        val open = InstalledEmulator(
            id = MacOpenAdapter.id,
            name = MacOpenAdapter.name,
            host = Host.MACOS,
            appId = "/usr/bin/open",
            platforms = MacOpenAdapter.platforms,
            detectedVia = DesktopInstallKind.BUILT_IN.label,
        )
        return found + open
    }

    fun toInstalled(def: DesktopEmulatorDef, found: Found): InstalledEmulator = InstalledEmulator(
        id = EmulatorId(def.def.id),
        name = def.def.name,
        host = Host.MACOS,
        appId = found.path,
        platforms = def.def.platforms,
        detectedVia = found.kind.label,
    )

    /** RetroArch's cores (ES-DE's macos core path): Application Support, then inside the app. */
    fun retroArchCoreDirs(files: HostFiles, installed: InstalledEmulator): List<String> {
        val contents = Paths.parent(Paths.parent(installed.appId))
        return listOfNotNull(
            "${files.homeDir.trimEnd('/')}/Library/Application Support/RetroArch/cores",
            "$contents/Resources/cores".takeIf { contents.endsWith("/Contents") },
        )
    }

    fun findRetroArchCore(files: HostFiles, installed: InstalledEmulator, core: String): String? =
        retroArchCoreDirs(files, installed).map { "$it/${RetroArchCores.coreFile(Host.MACOS, core)}" }.firstOrNull(files::isFile)
}
