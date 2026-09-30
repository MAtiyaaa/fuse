package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.DirectoryWatcher
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.launch.pc.DesktopEntry
import io.github.matiyaaa.fuse.launch.pc.ShortcutParser
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.ui.shell.store.AppsProvider
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** One launchable `.desktop` application. */
internal data class DesktopApp(val id: String, val path: String, val entry: AppEntry, val icon: String?)

/**
 * Reads freedesktop `.desktop` applications: the user's, Flatpak exports and the system's, in the
 * spec's precedence order (the first file with a given desktop file id wins).
 */
internal class DesktopFileIndex(private val dirs: FuseDirs) {
    @Volatile
    private var byId: Map<String, DesktopApp> = emptyMap()

    val all: Collection<DesktopApp> get() = byId.values

    fun get(id: String): DesktopApp? = byId[id]

    /** Path of the desktop file with this id, scanning if it was not seen yet. */
    fun pathFor(id: String): String? = byId[id]?.path ?: applicationDirs().firstNotNullOfOrNull { dir ->
        File(dir, id.replace('-', '/')).takeIf { it.isFile }?.path ?: File(dir, id).takeIf { it.isFile }?.path
    }

    fun applicationDirs(): List<String> {
        val dataDirs = (System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share")
            .split(':').filter { it.startsWith("/") }.map { it.trimEnd('/') }
        return (
            listOf(
                "${dirs.xdgDataHome}/applications",
                "${dirs.xdgDataHome}/flatpak/exports/share/applications",
                "/var/lib/flatpak/exports/share/applications",
            ) + dataDirs.map { "$it/applications" }
            ).distinct()
    }

    private fun iconBases(): List<String> {
        val dataDirs = (System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share")
            .split(':').filter { it.startsWith("/") }.map { it.trimEnd('/') }
        return (
            listOf(
                "${dirs.xdgDataHome}/icons",
                "${dirs.home}/.icons",
                "${dirs.xdgDataHome}/flatpak/exports/share/icons",
                "/var/lib/flatpak/exports/share/icons",
            ) + dataDirs.map { "$it/icons" }
            ).distinct()
    }

    /** Rebuilds the index. Blocking; call from an IO thread. */
    fun rescan(): List<DesktopApp> {
        val icons = IconIndex(iconBases())
        val seen = LinkedHashMap<String, DesktopApp>()
        val skipped = HashSet<String>()
        val desktops = currentDesktops()
        val selfPaths = listOfNotNull(System.getenv("APPIMAGE"), System.getProperty("jpackage.app-path"))
        for (dir in applicationDirs()) {
            val root = File(dir)
            if (!root.isDirectory) continue
            root.walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".desktop") }.forEach { file ->
                val id = file.relativeTo(root).path.replace('/', '-')
                if (id in seen || id in skipped) return@forEach
                val app = read(file, id, icons, desktops, selfPaths)
                // A hidden entry in a higher-priority folder hides the same id below it (spec).
                if (app == null) skipped += id else seen[id] = app
            }
        }
        byId = seen
        return seen.values.sortedBy { it.entry.label.lowercase(Locale.ROOT) }
    }

    private fun read(file: File, id: String, icons: IconIndex, desktops: Set<String>, selfPaths: List<String>): DesktopApp? {
        val text = SystemLinuxEnvironment.readSmallText(file.path) ?: return null
        val entry = ShortcutParser.parseDesktop(text) ?: return null
        val e = entry.entries
        if (entry.type != "Application") return null
        if (e.flag("NoDisplay") || e.flag("Hidden") || e.flag("Terminal")) return null
        val exec = entry.exec?.takeIf { it.isNotBlank() } ?: return null
        e["TryExec"]?.takeIf { it.isNotBlank() }?.let { if (Processes.which(it) == null) return null }
        e["OnlyShowIn"]?.let { v -> if (desktops.isNotEmpty() && v.list().none { it in desktops }) return null }
        e["NotShowIn"]?.let { v -> if (v.list().any { it in desktops }) return null }
        if (isFuse(id, e, exec, selfPaths)) return null
        val label = localized(entry, "Name") ?: return null
        val categories = e["Categories"].orEmpty().list()
        val app = AppEntry(
            id = id,
            label = label,
            packageName = id,
            isGame = "Game" in categories,
            installedAt = file.lastModified().takeIf { it > 0 },
        )
        return DesktopApp(id, file.path, app, entry.icon?.let(icons::resolve))
    }

    private fun isFuse(id: String, e: Map<String, String>, exec: String, selfPaths: List<String>): Boolean =
        id == "fuse.desktop" || id == "io.github.matiyaaa.fuse.desktop" ||
            e["StartupWMClass"] == "fuse" && e["Name"] == "Fuse" ||
            selfPaths.any { it.isNotEmpty() && it in exec }

    private fun localized(entry: DesktopEntry, key: String): String? {
        val lang = (System.getenv("LC_ALL") ?: System.getenv("LC_MESSAGES") ?: System.getenv("LANG"))
            ?.substringBefore('.')?.substringBefore('@')?.takeIf { it.isNotBlank() && it != "C" && it != "POSIX" }
        val candidates = listOfNotNull(lang?.let { "$key[$it]" }, lang?.substringBefore('_')?.let { "$key[$it]" }, key)
        return candidates.firstNotNullOfOrNull { entry.entries[it]?.trim()?.takeIf(String::isNotEmpty) }
    }

    private fun currentDesktops(): Set<String> =
        (System.getenv("XDG_CURRENT_DESKTOP") ?: "").split(':').filter { it.isNotBlank() }.toSet()

    private fun Map<String, String>.flag(key: String) = this[key]?.trim()?.equals("true", ignoreCase = true) == true
    private fun String.list() = split(';').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Finds an icon file for an `Icon=` value in the hicolor theme (the fallback every app installs
 * into), then in `/usr/share/pixmaps`. PNG and SVG only; the largest raster size is preferred.
 */
internal class IconIndex(bases: List<String>) {
    private val found = HashMap<String, String>()

    init {
        val sizes = listOf("256x256", "512x512", "192x192", "128x128", "scalable", "96x96", "64x64", "48x48")
        for (size in sizes) {
            for (base in bases) {
                File("$base/hicolor/$size/apps").listFiles()?.forEach { f ->
                    val ext = f.extension.lowercase(Locale.ROOT)
                    if (ext == "png" || ext == "svg") found.putIfAbsent(f.nameWithoutExtension, f.path)
                }
            }
        }
        File("/usr/share/pixmaps").listFiles()?.forEach { f ->
            val ext = f.extension.lowercase(Locale.ROOT)
            if (ext == "png" || ext == "svg") found.putIfAbsent(f.nameWithoutExtension, f.path)
        }
    }

    fun resolve(icon: String): String? {
        val value = icon.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("/")) {
            val f = File(value)
            val ext = f.extension.lowercase(Locale.ROOT)
            return f.path.takeIf { f.isFile && (ext == "png" || ext == "svg" || ext == "jpg" || ext == "jpeg") }
        }
        val name = if (value.endsWith(".png") || value.endsWith(".svg")) value.substringBeforeLast('.') else value
        return found[name]
    }
}

/** The Apps section on Linux: `.desktop` applications, refreshed when their folders change. */
internal class DesktopAppsProvider(
    private val index: DesktopFileIndex,
    private val launcher: DesktopLauncher,
    scope: CoroutineScope,
) : AppsProvider, AutoCloseable {
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    override val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val watcher = DirectoryWatcher("fuse-apps-watch", debounceMs = 800) { refresh() }

    init {
        scope.launch(Dispatchers.IO) {
            for (r in requests) {
                try {
                    _apps.value = index.rescan().map { it.entry }
                } catch (e: Exception) {
                    Log.warn("could not read desktop applications", e)
                }
            }
        }
        refresh()
        watcher.watch(index.applicationDirs())
    }

    override fun iconModel(entry: AppEntry): Any? = index.get(entry.id)?.icon

    override fun refresh() {
        requests.trySend(Unit)
    }

    override suspend fun launch(entry: AppEntry): RunResult {
        val app = index.get(entry.id) ?: return RunResult.NotInstalled
        if (!File(app.path).isFile) return RunResult.NotInstalled
        return launcher.launchDesktopFile(app.path)
    }

    /** There is no common "app info" screen on Linux desktops, so this does nothing. */
    override fun openInfo(entry: AppEntry) = Unit

    override fun close() {
        watcher.close()
        requests.close()
    }
}
