package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.system.DirectoryWatcher
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.ui.shell.store.CartridgeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Cartridge on Linux: its status file (`$XDG_STATE_HOME/cartridge/status.json`) for live status, a
 * few file checks for "is it installed", and `xdg-open cartridge://...` for deep links.
 */
internal class DesktopCartridgeBridge(private val dirs: FuseDirs) : CartridgeBridge {
    private val statusFile: String? = CartridgeProtocol.statusFilePath(System.getenv("XDG_STATE_HOME"), dirs.home)

    override suspend fun read(): CartridgeStatus = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val text = statusFile?.let { SystemLinuxEnvironment.readSmallText(it) }
            text?.let { CartridgeProtocol.parseStatusFile(it, installedVersion = null, checkedAt = now) }
                ?: if (isInstalled()) CartridgeProtocol.installedWithoutBridge(version = null, checkedAt = now)
                else CartridgeStatus(installed = false, checkedAt = now)
        } catch (e: Exception) {
            CartridgeStatus(installed = false, checkedAt = now)
        }
    }

    /** Status file, an AppImage in the usual folders, `cartridge` on PATH, or a desktop entry. */
    private fun isInstalled(): Boolean {
        if (statusFile != null && File(statusFile).isFile) return true
        val appImageDirs = listOfNotNull(
            "${dirs.home}/Applications", "${dirs.home}/.local/bin", "${dirs.home}/Downloads", "${dirs.home}/AppImages",
            System.getenv("APPIMAGE")?.let { File(it).parent },
        )
        for (dir in appImageDirs) {
            val names = File(dir).list() ?: continue
            if (names.any { it.lowercase(Locale.ROOT).let { n -> n.startsWith("cartridge") && n.endsWith(".appimage") } }) return true
        }
        if (Processes.which("cartridge") != null) return true
        val apps = File("${dirs.xdgDataHome}/applications").listFiles() ?: return false
        return apps.any { f ->
            f.name.endsWith(".desktop") && (
                "cartridge" in f.name.lowercase(Locale.ROOT) ||
                    SystemLinuxEnvironment.readSmallText(f.path, 16 * 1024)?.let { t ->
                        Regex("""(?m)^(Name|Exec)=.*cartridge""", RegexOption.IGNORE_CASE).containsMatchIn(t)
                    } == true
                )
        }
    }

    /**
     * Opens [link] with the desktop's handler for `cartridge://`. When no handler is registered in the
     * usual mime files, xdg-open is still tried and its answer awaited briefly, so a missing handler
     * reports false instead of pretending.
     */
    override fun open(route: CartridgeRoute, link: String): Boolean {
        if (!link.startsWith("${CartridgeProtocol.SCHEME}://")) return false
        val xdgOpen = Processes.which("xdg-open") ?: return false
        if (hasRegisteredHandler()) return Processes.spawn(listOf(xdgOpen, link)) != null
        val process = Processes.spawn(listOf(xdgOpen, link)) ?: return false
        if (!process.waitFor(1_500, TimeUnit.MILLISECONDS)) return true
        return process.exitValue() == 0
    }

    private fun hasRegisteredHandler(): Boolean {
        val mime = "x-scheme-handler/${CartridgeProtocol.SCHEME}"
        val files = listOf(
            "${dirs.xdgConfigHome}/mimeapps.list",
            "${dirs.xdgDataHome}/applications/mimeapps.list",
            "${dirs.xdgDataHome}/applications/mimeinfo.cache",
            "/usr/share/applications/mimeinfo.cache",
            "/usr/local/share/applications/mimeinfo.cache",
            "/var/lib/flatpak/exports/share/applications/mimeinfo.cache",
        )
        return files.any { f -> SystemLinuxEnvironment.readSmallText(f, 1 shl 20)?.contains(mime) == true }
    }

    /** Watches the status file's folder (never creating it); [onChange] runs on the UI thread. */
    override fun watch(onChange: () -> Unit): AutoCloseable? {
        val file = statusFile ?: return null
        val name = File(file).name
        val watcher = DirectoryWatcher("fuse-cartridge-watch", debounceMs = 400, fileFilter = { it == name }) {
            EventQueue.invokeLater(onChange)
        }
        watcher.watch(listOf(File(file).parent))
        return watcher
    }
}
