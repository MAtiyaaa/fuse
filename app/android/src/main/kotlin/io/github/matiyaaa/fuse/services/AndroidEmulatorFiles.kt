package io.github.matiyaaa.fuse.services

import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.patches.Pcsx2Home
import io.github.matiyaaa.fuse.launch.patches.PpssppHome
import io.github.matiyaaa.fuse.launch.patches.RetroArchCfg
import io.github.matiyaaa.fuse.launch.patches.RetroArchHome
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.sync.SaveEnvironment
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorFileCopies
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The emulators' own files Fuse changes on Android: those in the device's shared storage, which
 * Fuse reaches with All Files Access. They are found as Fuse finds the same emulators' saves:
 * PPSSPP's memory stick (the folder with PSP in it, `/storage/emulated/0` unless the person chose
 * another), ARMSX2's data folder (the one chosen in it, with its `inis/PCSX2.ini`), and RetroArch's
 * folder when it keeps it in shared storage (`/storage/emulated/0/RetroArch`). An emulator that
 * keeps its files in its own private folder (`Android/data`: DuckStation, Eden, Dolphin, NetherSX2,
 * RetroArch from the Play Store) can't be reached by another app, so Fuse has none of its files.
 *
 * Writes go only inside the folders found here (and to RetroArch's own `retroarch.cfg`), each file
 * kept as it was before Fuse first changed it ([backups], at its whole path: [EmulatorFileCopies])
 * and replaced through a temporary file beside it.
 */
internal class AndroidEmulatorFiles(
    private val env: SaveEnvironment,
    private val backups: File,
) : EmulatorFiles {
    /** The folders found, the only ones written to. */
    private val roots = CopyOnWriteArraySet<String>()

    /** Single files found outside those folders that may be written too: RetroArch's settings beside its folders. */
    private val singles = CopyOnWriteArraySet<String>()

    override suspend fun pcsx2(installed: InstalledEmulator): Pcsx2Home? = withContext(Dispatchers.IO) {
        val data = io.github.matiyaaa.fuse.sync.pcsx2DataFolder(env, null)?.let(::File) ?: return@withContext null
        val ini = File(data, "inis/PCSX2.ini")
        // Until it has been set up once there is nothing of its to change.
        if (!ini.isFile) return@withContext null
        val settings = runCatching { IniText(ini.readText()) }.getOrNull()
        fun folder(key: String, default: String): String {
            val value = settings?.values("Folders", key)?.lastOrNull()?.takeIf { it.isNotBlank() } ?: default
            return (File(value).takeIf { it.isAbsolute } ?: File(data, value)).absolutePath
        }
        val home = Pcsx2Home(
            dataRoot = data.absolutePath,
            gameSettings = folder("GameSettings", "gamesettings"),
            patches = folder("Patches", "patches"),
            cheats = folder("Cheats", "cheats"),
            patchesZip = null,
            textures = folder("Textures", "textures"),
        )
        // Its data folder, and the folders its own settings moved elsewhere (a cheats folder on the SD card).
        roots += data.absolutePath
        roots += listOf(home.gameSettings, home.patches, home.cheats, home.textures)
        home
    }

    override suspend fun ppsspp(installed: InstalledEmulator): PpssppHome? = withContext(Dispatchers.IO) {
        val stick = io.github.matiyaaa.fuse.sync.ppssppMemstick(env, null)?.let(::File) ?: return@withContext null
        // Until PPSSPP has written its settings there is nothing of its to change.
        if (!File(stick, "PSP/SYSTEM/ppsspp.ini").isFile) return@withContext null
        roots += File(stick, "PSP").absolutePath
        PpssppHome(stick.absolutePath)
    }

    override suspend fun retroArch(installed: InstalledEmulator): RetroArchHome? = withContext(Dispatchers.IO) {
        val config = io.github.matiyaaa.fuse.sync.retroArchConfigFile(env, null)?.let(::File)?.takeIf { it.isFile } ?: return@withContext null
        val cfg = runCatching { RetroArchCfg.parse(config.readText()) }.getOrDefault(emptyMap())
        val base = config.parentFile.absolutePath
        val cheats = RetroArchCfg.folder(cfg["cheat_database_path"], "$base/cheats", env.home, null)
        val configDir = RetroArchCfg.folder(cfg["rgui_config_directory"], "$base/config", env.home, null)
        roots += File(cheats).absolutePath
        roots += File(configDir).absolutePath
        // Its own settings (and sign-in) are this one file beside those folders, not in them.
        singles += config.absolutePath
        RetroArchHome(config.absolutePath, cheats, configDir)
    }

    override suspend fun zipText(zip: String, entry: String): String? = withContext(Dispatchers.IO) {
        try {
            ZipFile(zip).use { z ->
                val e = z.getEntry(entry) ?: return@use null
                if (e.size > MAX_TEXT) return@use null
                z.getInputStream(e).use { it.readNBytes(MAX_TEXT).decodeToString() }
            }
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun write(path: String, text: String): Boolean = write(path, text, keepCopy = true)

    override suspend fun writePrivate(path: String, text: String): Boolean = write(path, text, keepCopy = false)

    override suspend fun remove(path: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(path).absoluteFile
        // Only ever a file in the folders found: never one of the single settings files themselves.
        inFolders(target) && target.isFile && target.delete()
    }

    private fun inFolders(target: File): Boolean = roots.any { root -> target.normalize().path.startsWith(File(root).normalize().path + File.separator) }

    private fun inside(target: File): Boolean = inFolders(target) || singles.any { File(it).normalize().path == target.normalize().path }

    private suspend fun write(path: String, text: String, keepCopy: Boolean): Boolean = withContext(Dispatchers.IO) {
        val target = File(path).absoluteFile
        if (!inside(target)) return@withContext false
        try {
            // A copy of the file as it was before Fuse first changed it, kept with Fuse's own data.
            if (keepCopy && target.isFile) {
                val copy = File(backups, EmulatorFileCopies.below(target.normalize().path))
                if (!copy.exists()) {
                    copy.parentFile?.mkdirs()
                    target.copyTo(copy)
                }
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.fuse.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                // Some storage can't replace a file by renaming over it: written in place instead.
                target.writeText(text)
                tmp.delete()
            }
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private companion object {
        const val MAX_TEXT = 1024 * 1024
    }
}
