package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.desktop.system.fusePath
import io.github.matiyaaa.fuse.ui.shell.platform.PickedFile
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
 * The desktop needs no storage permission. Pickers use the system's own dialog: zenity on GNOME-like
 * desktops and kdialog on KDE (else Swing's file chooser) on Linux, the native file dialog on
 * Windows and macOS. Picked images and songs are copied into Fuse's data folder; the user's file is
 * never moved or changed. Paths come back with forward slashes.
 */
internal class DesktopStorage(private val dirs: FuseDirs, private val host: DialogHost) : StorageAccess {
    private val _state = MutableStateFlow(StorageState.NOT_NEEDED)
    override val state: StateFlow<StorageState> = _state.asStateFlow()

    override fun request() = Unit
    override fun refresh() = Unit

    override suspend fun pickFolder(title: String): String? = host.withDialog {
        pick(title, filter = null)?.takeIf { File(it).isDirectory }
    }

    override suspend fun pickImage(title: String): String? {
        val picked = host.withDialog { pick(title, IMAGES) } ?: return null
        return withContext(Dispatchers.IO) { copyIntoMedia(File(picked)) }
    }

    override suspend fun pickAudio(title: String): PickedFile? {
        val picked = host.withDialog { pick(title, AUDIO) } ?: return null
        return withContext(Dispatchers.IO) { copyMusic(File(picked)) }
    }

    /**
     * Saves [bytes] as [name] where the user picks in the system's save dialog. The file is written
     * to a temporary name first and moved into place, so a half-written file never appears.
     */
    suspend fun saveFile(name: String, bytes: ByteArray): String? {
        val target = host.withDialog { pickSave(name) } ?: return null
        return withContext(Dispatchers.IO) {
            val file = File(target)
            val temp = File(file.absoluteFile.parentFile, ".${file.name}.part")
            try {
                temp.writeBytes(bytes)
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                file.absoluteFile.fusePath
            } catch (e: Exception) {
                Log.warn("could not save $name", e)
                // A half-written copy is never left beside the person's files.
                temp.delete()
                null
            }
        }
    }

    /** A file with one of [extensions] the user picks, at most [maxBytes]. */
    suspend fun openFile(title: String, label: String, extensions: List<String>, maxBytes: Long): io.github.matiyaaa.fuse.ui.shell.platform.OpenedFile? {
        val picked = host.withDialog { pick(title, Filter(label, extensions)) } ?: return null
        return withContext(Dispatchers.IO) {
            val file = File(picked)
            if (!file.isFile || file.length() > maxBytes) return@withContext null
            runCatching { io.github.matiyaaa.fuse.ui.shell.platform.OpenedFile(file.name, file.readBytes()) }.getOrNull()
        }
    }

    /** The save dialog: zenity or kdialog on Linux, the system's own on Windows and macOS, else Swing's. */
    private suspend fun pickSave(name: String): String? {
        val suggested = File(dirs.home, name).path
        if (DesktopOs.current == DesktopOs.LINUX) {
            val result = withContext(Dispatchers.IO) {
                val zenity = Processes.which("zenity")
                val kdialog = Processes.which("kdialog")
                val preferKde = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").contains("KDE", ignoreCase = true)
                val argv = when {
                    kdialog != null && (preferKde || zenity == null) -> listOf(kdialog, "--title", "Save", "--getsavefilename", suggested)
                    zenity != null -> listOf(zenity, "--file-selection", "--save", "--confirm-overwrite", "--title=Save", "--filename=$suggested")
                    else -> null
                } ?: return@withContext NoTool
                val out = Processes.run(argv, timeoutMs = 30 * 60 * 1000L) ?: return@withContext NoTool
                if (out.exitCode != 0) null else out.stdout.trim().lineSequence().firstOrNull()?.takeIf { it.startsWith("/") }
            }
            if (result !== NoTool) return result as String?
        } else {
            return suspendCancellableCoroutine { cont ->
                SwingUtilities.invokeLater {
                    val path = try {
                        val dialog = java.awt.FileDialog(host.parent as? java.awt.Frame, "Save", java.awt.FileDialog.SAVE).apply {
                            directory = dirs.home
                            file = name
                            isVisible = true
                        }
                        dialog.file?.let { File(dialog.directory, it).absolutePath }
                    } catch (e: Exception) {
                        Log.warn("save dialog failed", e)
                        null
                    }
                    if (cont.isActive) cont.resume(path)
                }
            }
        }
        return suspendCancellableCoroutine { cont ->
            SwingUtilities.invokeLater {
                val path = try {
                    val chooser = JFileChooser(dirs.home).apply { selectedFile = File(suggested) }
                    if (chooser.showSaveDialog(host.parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
                } catch (e: Exception) {
                    null
                }
                if (cont.isActive) cont.resume(path)
            }
        }
    }

    /** File types a picker offers: a label and extensions. */
    private class Filter(val label: String, val extensions: List<String>)

    /** Result of a native dialog: a path, or null for "cancelled"; [NoTool] when neither tool exists. */
    private object NoTool

    /** Swing is only the fallback when no native tool exists; a cancelled native dialog stays cancelled. [filter] null picks a folder. */
    private suspend fun pick(title: String, filter: Filter?): String? {
        if (DesktopOs.current != DesktopOs.LINUX) return systemPick(title, filter)
        val result = withContext(Dispatchers.IO) { runNative(title, filter) }
        return if (result === NoTool) swingPick(title, filter) else result as String?
    }

    /**
     * Windows and macOS: the system's own open dialog (AWT's FileDialog). macOS picks folders with
     * it too; Windows has no folder mode there, so its folders use Swing's chooser in the system look.
     */
    private suspend fun systemPick(title: String, filter: Filter?): String? {
        if (filter == null && DesktopOs.isWindows) return swingPick(title, null, systemLook = true)
        return suspendCancellableCoroutine { cont ->
            SwingUtilities.invokeLater {
                val path = try {
                    val owner = host.parent as? java.awt.Frame
                    if (filter == null) System.setProperty("apple.awt.fileDialogForDirectories", "true")
                    val dialog = java.awt.FileDialog(owner, title, java.awt.FileDialog.LOAD).apply {
                        directory = dirs.home
                        if (filter != null) {
                            setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase(Locale.ROOT) in filter.extensions }
                            // Windows ignores the filter above; a pattern in the name field narrows the list there.
                            if (DesktopOs.isWindows) file = filter.extensions.joinToString(";") { "*.$it" }
                        }
                        isVisible = true
                    }
                    val chosen = dialog.file?.let { File(dialog.directory, it) }
                    chosen?.absoluteFile?.fusePath
                } catch (e: Exception) {
                    Log.warn("file dialog failed", e)
                    null
                } finally {
                    System.clearProperty("apple.awt.fileDialogForDirectories")
                }
                if (cont.isActive) cont.resume(path)
            }
        }
    }

    private fun runNative(title: String, filter: Filter?): Any? {
        val home = dirs.home
        val zenity = Processes.which("zenity")
        val kdialog = Processes.which("kdialog")
        val preferKde = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").contains("KDE", ignoreCase = true)
        val patterns = filter?.extensions?.joinToString(" ") { "*.$it" }
        val argv: List<String> = when {
            kdialog != null && (preferKde || zenity == null) ->
                if (filter == null) listOf(kdialog, "--title", title, "--getexistingdirectory", home)
                else listOf(kdialog, "--title", title, "--getopenfilename", home, "${filter.label} ($patterns)")
            zenity != null ->
                if (filter == null) listOf(zenity, "--file-selection", "--directory", "--title=$title", "--filename=$home/")
                else listOf(zenity, "--file-selection", "--title=$title", "--filename=$home/", "--file-filter=${filter.label} | $patterns")
            else -> return NoTool
        }
        // The dialog waits for the user; allow a long time but not forever.
        val out = Processes.run(argv, timeoutMs = 30 * 60 * 1000L) ?: return NoTool
        if (out.exitCode != 0) return null
        return out.stdout.trim().lineSequence().firstOrNull()?.takeIf { it.startsWith("/") }
    }

    private suspend fun swingPick(title: String, filter: Filter?, systemLook: Boolean = false): String? = suspendCancellableCoroutine { cont ->
        SwingUtilities.invokeLater {
            val path = try {
                if (systemLook) {
                    try {
                        javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
                    } catch (e: Exception) {
                        // The default look still works.
                    }
                }
                val chooser = JFileChooser(dirs.home).apply {
                    dialogTitle = title
                    fileSelectionMode = if (filter == null) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
                    if (filter != null) fileFilter = FileNameExtensionFilter(filter.label, *filter.extensions.toTypedArray())
                }
                if (chooser.showOpenDialog(host.parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absoluteFile?.fusePath else null
            } catch (e: Exception) {
                Log.warn("file chooser failed", e)
                null
            }
            if (cont.isActive) cont.resume(path)
        }
    }

    /** Copies a picked song into Fuse's `music` folder, replacing the previous one. */
    private fun copyMusic(source: File): PickedFile? {
        if (!source.isFile || source.extension.lowercase(Locale.ROOT) !in AUDIO.extensions) return null
        val folder = File(dirs.data, "music").apply { mkdirs() }
        val target = File(folder, "${System.currentTimeMillis()}.${source.extension.lowercase(Locale.ROOT)}")
        return try {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            folder.listFiles()?.filter { it != target }?.forEach { it.delete() }
            PickedFile(target.fusePath, source.nameWithoutExtension)
        } catch (e: Exception) {
            Log.warn("could not copy the picked song", e)
            null
        }
    }

    /** Copies a picked image into Fuse's `media/custom` folder and returns the copy's path. */
    private fun copyIntoMedia(source: File): String? {
        if (!source.isFile || source.extension.lowercase(Locale.ROOT) !in IMAGES.extensions) return null
        val folder = File(dirs.customMedia).apply { mkdirs() }
        val safe = source.name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(80)
        val target = File(folder, "${System.currentTimeMillis()}-$safe")
        return try {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            target.fusePath
        } catch (e: Exception) {
            Log.warn("could not copy the picked image", e)
            null
        }
    }

    private companion object {
        val IMAGES = Filter("Images", listOf("png", "jpg", "jpeg", "webp", "gif", "bmp"))

        /** What [DesktopMenuMusic] can play. */
        val AUDIO = Filter("Music", listOf("mp3", "wav"))
    }
}
