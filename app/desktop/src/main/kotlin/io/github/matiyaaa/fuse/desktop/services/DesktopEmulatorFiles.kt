package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.patches.Pcsx2Home
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.zip.ZipFile

/**
 * PCSX2's folders on a computer, worked out the way PCSX2 itself does (pcsx2/Pcsx2Config.cpp,
 * EmuFolders::SetDataDirectory and SetResourcesDirectory): portable mode when `portable.ini` or
 * `portable.txt` sits next to the program, otherwise Documents\PCSX2 on Windows, $XDG_CONFIG_HOME
 * or ~/.config/PCSX2 on Linux (inside ~/.var/app for the Flatpak), and ~/Library/Application
 * Support/PCSX2 on macOS. Folders moved in PCSX2.ini's `[Folders]` are followed.
 */
class DesktopEmulatorFiles(
    private val os: DesktopOs,
    private val env: Map<String, String> = System.getenv(),
    private val home: String = System.getProperty("user.home"),
    /** Where copies of files are kept before Fuse first changes them. */
    private val backups: File,
) : EmulatorFiles {
    private val roots: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override suspend fun pcsx2(installed: InstalledEmulator): Pcsx2Home? = withContext(Dispatchers.IO) {
        val flatpak = installed.detectedVia == "Flatpak"
        val program = if (flatpak) null else File(installed.appId).takeIf { it.isAbsolute }
        val appRoot = program?.let { appRoot(it) }
        val data = dataRoot(appRoot, flatpak) ?: return@withContext null
        val ini = File(data, "inis/PCSX2.ini")
        // Until PCSX2 has been set up once there is nothing of its to change.
        if (!ini.isFile) return@withContext null
        val settings = runCatching { IniText(ini.readText()) }.getOrNull()
        fun folder(key: String, default: String): String {
            val value = settings?.values("Folders", key)?.lastOrNull()?.takeIf { it.isNotBlank() } ?: default
            val f = File(value)
            return (if (f.isAbsolute) f else File(data, value)).absolutePath.replace('\\', '/')
        }
        roots += data.absolutePath
        Pcsx2Home(
            dataRoot = data.absolutePath.replace('\\', '/'),
            gameSettings = folder("GameSettings", "gamesettings"),
            patches = folder("Patches", "patches"),
            patchesZip = zipCandidates(appRoot, flatpak).firstOrNull { it.isFile && it.canRead() }?.absolutePath?.replace('\\', '/'),
        )
    }

    /** The folder PCSX2 calls AppRoot: the program's folder, or the folder holding a macOS app bundle. */
    private fun appRoot(program: File): File? {
        val path = program.absolutePath
        val bundle = path.indexOf(".app/").takeIf { it > 0 }?.let { File(path.substring(0, it + 4)) }
            ?: program.takeIf { path.endsWith(".app") }
        return bundle?.parentFile ?: program.parentFile
    }

    private fun dataRoot(appRoot: File?, flatpak: Boolean): File? {
        if (appRoot != null) {
            val txt = File(appRoot, "portable.txt")
            if (File(appRoot, "portable.ini").isFile) return appRoot
            if (txt.isFile) {
                val custom = runCatching { txt.readText().trim() }.getOrDefault("")
                if (custom.isEmpty()) return appRoot
                val f = File(custom)
                return if (f.isAbsolute) f else File(appRoot, custom)
            }
        }
        return when (os) {
            DesktopOs.WINDOWS -> listOfNotNull(env["USERPROFILE"]?.let { File(it, "Documents") }, env["OneDrive"]?.let { File(it, "Documents") })
                .map { File(it, "PCSX2") }.firstOrNull { File(it, "inis/PCSX2.ini").isFile }
            DesktopOs.MACOS -> File(home, "Library/Application Support/PCSX2")
            DesktopOs.LINUX -> if (flatpak) {
                File(home, ".var/app/net.pcsx2.PCSX2/config/PCSX2")
            } else {
                env["XDG_CONFIG_HOME"]?.takeIf { it.startsWith("/") }?.let { File(it, "PCSX2") } ?: File(home, ".config/PCSX2")
            }
        }
    }

    private fun zipCandidates(appRoot: File?, flatpak: Boolean): List<File> = buildList {
        if (flatpak) {
            for (base in listOf("/var/lib/flatpak/app", "$home/.local/share/flatpak/app")) {
                val files = File(base, "net.pcsx2.PCSX2/current/active/files")
                add(File(files, "share/PCSX2/resources/patches.zip"))
                add(File(files, "bin/resources/patches.zip"))
            }
        }
        if (appRoot != null) {
            add(File(appRoot, "resources/patches.zip"))
            add(File(appRoot, "../share/PCSX2/resources/patches.zip"))
            appRoot.listFiles { f -> f.name.endsWith(".app") }?.forEach { add(File(it, "Contents/Resources/patches.zip")) }
        }
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
        } catch (e: SecurityException) {
            null
        }
    }

    override suspend fun write(path: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(path).absoluteFile
        val inside = roots.any { root -> target.normalize().path.startsWith(File(root).normalize().path + File.separator) }
        if (!inside) return@withContext false
        try {
            // A copy of the file as it was before Fuse first changed it, kept with Fuse's own data.
            if (target.isFile) {
                val copy = File(backups, target.name)
                if (!copy.exists()) {
                    backups.mkdirs()
                    Files.copy(target.toPath(), copy.toPath())
                }
            }
            target.parentFile.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.fuse.tmp")
            tmp.writeText(text)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
