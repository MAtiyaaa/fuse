package io.github.matiyaaa.fuse.launch.desktop

import io.github.matiyaaa.fuse.launch.Paths
import io.github.matiyaaa.fuse.launch.RetroArchCores
import io.github.matiyaaa.fuse.launch.linux.Glob
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator

/**
 * Finds Windows emulators with pure functions over [HostFiles]. Windows emulators are mostly
 * portable folders, so instead of a fixed path per program (ES-DE's staticpath), every likely parent
 * folder is listed once and its sub-folders searched for the program names: Fuse's emulator folders,
 * the ES-DE, RetroBat, EmuDeck and LaunchBox layouts, Scoop, Chocolatey and winget, Program Files,
 * AppData, each drive's root and Emulators folder, the user folders, Steam libraries, then PATH.
 */
object WindowsDetector {

    /** A located program. */
    data class Found(val kind: DesktopInstallKind, val path: String)

    /** A folder whose own files and whose sub-folders' files are searched ([nested] below each sub-folder when set). */
    data class Root(val path: String, val kind: DesktopInstallKind = DesktopInstallKind.FOLDER, val nested: String? = null)

    /** Folders at a drive's root that never hold emulators, skipped so Windows itself isn't listed. */
    private val SKIPPED = setOf("windows", "users", "programdata", "recovery", "perflogs", "system volume information", "config.msi", "intel", "amd", "nvidia")

    fun roots(files: HostFiles, extraDirs: List<String> = emptyList()): List<Root> {
        val home = files.homeDir.trimEnd('/')
        val local = files.env("LOCALAPPDATA")?.let(::slashes) ?: "$home/AppData/Local"
        val roaming = files.env("APPDATA")?.let(::slashes) ?: "$home/AppData/Roaming"
        val programFiles = listOfNotNull(files.env("ProgramFiles"), files.env("ProgramW6432"), files.env("ProgramFiles(x86)")).map(::slashes)
        val programData = files.env("ProgramData")?.let(::slashes) ?: "C:/ProgramData"
        val scoop = files.env("SCOOP")?.let(::slashes) ?: "$home/scoop"
        val drives = files.drives().map { it.trimEnd('/') + "/" }
        val layouts = { base: String ->
            listOf("${base}Emulators", "${base}Emulation/Emulators", "${base}ES-DE/Emulators", "${base}RetroBat/emulators", "${base}LaunchBox/Emulators")
        }
        return buildList {
            extraDirs.forEach { add(Root(slashes(it).trimEnd('/'))) }
            layouts("$home/").forEach { add(Root(it)) }
            add(Root("$roaming/EmuDeck/Emulators"))
            add(Root("$home/EmuDeck/Emulators"))
            add(Root("$scoop/apps", nested = "current"))
            add(Root("$programData/scoop/apps", nested = "current"))
            add(Root("$programData/chocolatey/lib", nested = "tools"))
            add(Root("$local/Microsoft/WinGet/Packages"))
            add(Root("$local/Microsoft/WinGet/Links"))
            programFiles.forEach { add(Root(it)) }
            add(Root("$local/Programs"))
            add(Root(local))
            add(Root(roaming))
            drives.forEach { d ->
                layouts(d).forEach { add(Root(it)) }
                add(Root("${d}Games"))
                add(Root("${d}tools"))
                add(Root(d))
            }
            listOf("Desktop", "Downloads", "Documents", "Games").forEach { add(Root("$home/$it")) }
            add(Root(home))
            steamLibraries(files).forEach { add(Root("$it/steamapps/common", DesktopInstallKind.STEAM)) }
        }.distinctBy { it.path.lowercase() }
    }

    /** Steam's install folders and every library listed in its libraryfolders.vdf. */
    fun steamLibraries(files: HostFiles): List<String> {
        val steams = (
            listOfNotNull(files.env("ProgramFiles(x86)"), files.env("ProgramFiles")).map { "${slashes(it)}/Steam" } +
                files.drives().map { "${it.trimEnd('/')}/Steam" }
            ).filter { files.isFile("$it/steam.exe") }
        return (steams + steams.flatMap { s -> files.readText("$s/steamapps/libraryfolders.vdf")?.let(::parseLibraryFolders).orEmpty() })
            .map { it.trimEnd('/') }.distinctBy { it.lowercase() }
    }

    /** The `"path"` values of Steam's libraryfolders.vdf, with forward slashes. */
    fun parseLibraryFolders(vdf: String): List<String> =
        Regex("\"path\"\\s+\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(vdf)
            .map { slashes(it.groupValues[1].replace(Regex("\\\\(.)"), "$1")) }.toList()

    /**
     * Every file in [roots] and their sub-folders, by lower-case name, in search order. Each folder is
     * listed once, however many programs are searched.
     */
    class Index(private val files: HostFiles, roots: List<Root>) {
        private val byName = HashMap<String, MutableList<Pair<String, DesktopInstallKind>>>()

        init {
            for (root in roots) {
                if (!files.isDirectory(root.path)) continue
                add(root.path, root.kind)
                for (child in files.list(root.path)) {
                    if (child.lowercase() in SKIPPED || child.startsWith("$") || child.startsWith(".")) continue
                    val dir = "${root.path.trimEnd('/')}/$child" + (root.nested?.let { "/$it" } ?: "")
                    if (files.isDirectory(dir)) add(dir, root.kind)
                }
            }
        }

        private fun add(dir: String, kind: DesktopInstallKind) {
            for (name in files.list(dir)) byName.getOrPut(name.lowercase()) { ArrayList() } += "${dir.trimEnd('/')}/$name" to kind
        }

        /** Where [name] is, first match first. */
        fun find(name: String): List<Pair<String, DesktopInstallKind>> = byName[name.lowercase()].orEmpty()
    }

    /** Where [def] is installed, or null. */
    fun locate(files: HostFiles, def: DesktopEmulatorDef, index: Index): Found? {
        val find = def.find
        for (program in find.programs) {
            index.find(program).firstOrNull { (path, _) -> fits(find, folderNames(path)) && files.isFile(path) }
                ?.let { (path, kind) -> return Found(kind, path) }
        }
        for (program in find.programs) {
            files.pathDirectories().map { "${slashes(it).trimEnd('/')}/$program" }.firstOrNull(files::isFile)?.let { return Found(DesktopInstallKind.PATH, it) }
        }
        return null
    }

    /** The program's folder and the one above it ("apps/dosbox-staging/current" for Scoop). */
    private fun folderNames(path: String): List<String> {
        val folder = Paths.parent(path)
        return listOf(Paths.fileName(folder), Paths.fileName(Paths.parent(folder))).filter { it.isNotEmpty() && !it.endsWith(":") }
    }

    /** True when a program in [folders] may be [find]'s: one matches its folder globs and none its excludes. */
    private fun fits(find: DesktopFind, folders: List<String>): Boolean =
        (find.folders.isEmpty() || folders.any { f -> find.folders.any { Glob.matches(it, f) } }) &&
            folders.none { f -> find.excludes.any { Glob.matches(it, f) } }

    /**
     * Every installed emulator in [defs], plus the built-in programs and shortcuts adapter run through
     * Windows PowerShell. [located] are programs the user pointed Fuse at, by emulator id; they win.
     */
    fun detect(
        files: HostFiles,
        defs: List<DesktopEmulatorDef> = WindowsCatalog.defs,
        extraDirs: List<String> = emptyList(),
        located: Map<String, String> = emptyMap(),
    ): List<InstalledEmulator> {
        val index = Index(files, roots(files, extraDirs))
        val found = defs.mapNotNull { def ->
            val chosen = located[def.def.id]?.let(::slashes)?.takeIf(files::isFile)?.let { Found(DesktopInstallKind.LOCATED, it) }
            (chosen ?: locate(files, def, index))?.let { toInstalled(def, it) }
        }
        val root = files.env("SystemRoot")?.let(::slashes) ?: "C:/Windows"
        val powershell = "$root/System32/WindowsPowerShell/v1.0/powershell.exe".takeIf(files::isFile)
        val shortcuts = InstalledEmulator(
            id = WindowsShortcutAdapter.id,
            name = WindowsShortcutAdapter.name,
            host = Host.WINDOWS,
            appId = powershell ?: "powershell.exe",
            platforms = WindowsShortcutAdapter.platforms,
            detectedVia = DesktopInstallKind.BUILT_IN.label,
        )
        return found + shortcuts
    }

    fun toInstalled(def: DesktopEmulatorDef, found: Found): InstalledEmulator = InstalledEmulator(
        id = EmulatorId(def.def.id),
        name = def.def.name,
        host = Host.WINDOWS,
        appId = found.path,
        platforms = def.def.platforms,
        detectedVia = found.kind.label,
    )

    /** RetroArch's cores: the `cores` folder next to retroarch.exe (installer, portable and Steam), then AppData. */
    fun retroArchCoreDirs(files: HostFiles, installed: InstalledEmulator): List<String> {
        val roaming = files.env("APPDATA")?.let(::slashes) ?: "${files.homeDir.trimEnd('/')}/AppData/Roaming"
        return listOf("${Paths.parent(installed.appId).trimEnd('/')}/cores", "$roaming/RetroArch/cores")
    }

    /** Absolute path of [core]'s library for [installed], or null when it is not installed. */
    fun findRetroArchCore(files: HostFiles, installed: InstalledEmulator, core: String): String? =
        retroArchCoreDirs(files, installed).map { "$it/${RetroArchCores.coreFile(Host.WINDOWS, core)}" }.firstOrNull(files::isFile)

    /** [path] with forward slashes, as Fuse keeps paths. */
    fun slashes(path: String): String = path.replace('\\', '/')
}
