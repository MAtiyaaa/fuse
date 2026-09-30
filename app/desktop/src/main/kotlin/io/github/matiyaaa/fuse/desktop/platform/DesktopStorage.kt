package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.ui.shell.platform.StorageAccess
import io.github.matiyaaa.fuse.ui.shell.platform.StorageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume

/** What the pickers need from the window: a parent for Swing dialogs and a way out of fullscreen. */
interface DialogHost {
    val parent: java.awt.Window?

    /** Runs [block] with the window out of exclusive fullscreen, so system dialogs can appear above it. */
    suspend fun <T> withDialog(block: suspend () -> T): T
}

/**
 * Linux needs no storage permission. Pickers use the desktop's own dialog (zenity on GNOME-like
 * desktops, kdialog on KDE) and fall back to Swing's file chooser. Picked images are copied into
 * Fuse's data folder; the user's file is never moved or changed.
 */
internal class DesktopStorage(private val dirs: FuseDirs, private val host: DialogHost) : StorageAccess {
    private val _state = MutableStateFlow(StorageState.NOT_NEEDED)
    override val state: StateFlow<StorageState> = _state.asStateFlow()

    override fun request() = Unit
    override fun refresh() = Unit

    override suspend fun pickFolder(title: String): String? = host.withDialog {
        pick(title, directory = true)?.takeIf { File(it).isDirectory }
    }

    override suspend fun pickImage(title: String): String? {
        val picked = host.withDialog { pick(title, directory = false) } ?: return null
        return withContext(Dispatchers.IO) { copyIntoMedia(File(picked)) }
    }

    /** Result of a native dialog: a path, or null for "cancelled"; [NoTool] when neither tool exists. */
    private object NoTool

    /** Swing is only the fallback when no native tool exists; a cancelled native dialog stays cancelled. */
    private suspend fun pick(title: String, directory: Boolean): String? {
        val result = withContext(Dispatchers.IO) { runNative(title, directory) }
        return if (result === NoTool) swingPick(title, directory) else result as String?
    }

    private fun runNative(title: String, directory: Boolean): Any? {
        val home = dirs.home
        val zenity = Processes.which("zenity")
        val kdialog = Processes.which("kdialog")
        val preferKde = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").contains("KDE", ignoreCase = true)
        val argv: List<String> = when {
            kdialog != null && (preferKde || zenity == null) ->
                if (directory) listOf(kdialog, "--title", title, "--getexistingdirectory", home)
                else listOf(kdialog, "--title", title, "--getopenfilename", home, "Images (*.png *.jpg *.jpeg *.webp *.gif *.bmp)")
            zenity != null ->
                if (directory) listOf(zenity, "--file-selection", "--directory", "--title=$title", "--filename=$home/")
                else listOf(zenity, "--file-selection", "--title=$title", "--filename=$home/", "--file-filter=Images | *.png *.jpg *.jpeg *.webp *.gif *.bmp")
            else -> return NoTool
        }
        // The dialog waits for the user; allow a long time but not forever.
        val out = Processes.run(argv, timeoutMs = 30 * 60 * 1000L) ?: return NoTool
        if (out.exitCode != 0) return null
        return out.stdout.trim().lineSequence().firstOrNull()?.takeIf { it.startsWith("/") }
    }

    private suspend fun swingPick(title: String, directory: Boolean): String? = suspendCancellableCoroutine { cont ->
        SwingUtilities.invokeLater {
            val path = try {
                val chooser = JFileChooser(dirs.home).apply {
                    dialogTitle = title
                    fileSelectionMode = if (directory) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
                    if (!directory) fileFilter = FileNameExtensionFilter("Images", *IMAGE_EXTENSIONS.toTypedArray())
                }
                if (chooser.showOpenDialog(host.parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
            } catch (e: Exception) {
                Log.warn("file chooser failed", e)
                null
            }
            if (cont.isActive) cont.resume(path)
        }
    }

    /** Copies a picked image into `~/.local/share/fuse/media/custom/` and returns the copy's path. */
    private fun copyIntoMedia(source: File): String? {
        if (!source.isFile || source.extension.lowercase(Locale.ROOT) !in IMAGE_EXTENSIONS) return null
        val folder = File(dirs.customMedia).apply { mkdirs() }
        val safe = source.name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(80)
        val target = File(folder, "${System.currentTimeMillis()}-$safe")
        return try {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            target.path
        } catch (e: Exception) {
            Log.warn("could not copy the picked image", e)
            null
        }
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }
}
