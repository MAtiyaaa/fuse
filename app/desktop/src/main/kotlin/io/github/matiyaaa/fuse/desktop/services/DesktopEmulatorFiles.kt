package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.patches.Pcsx2Home
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles
import io.github.matiyaaa.fuse.ui.shell.store.InstallerResult
import io.github.matiyaaa.fuse.ui.shell.store.InstallerRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
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
    /** Where licences are copied under the name an emulator wants (see [stage]). */
    private val staging: File = File(backups.parentFile, "staged-licences"),
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

    /**
     * RPCS3's config folder (rpcs3/Utilities/File.cpp fs::get_config_dir): the program's own folder
     * on Windows, ~/Library/Application Support/rpcs3 on macOS, $XDG_CONFIG_HOME/rpcs3 or
     * ~/.config/rpcs3 on Linux (inside ~/.var/app for the Flatpak). `dev_hdd0` is
     * `$(EmulatorDir)dev_hdd0/` unless `vfs.yml` moves it.
     */
    override suspend fun rpcs3Storage(installed: InstalledEmulator): List<String> = withContext(Dispatchers.IO) {
        val flatpak = installed.detectedVia == "Flatpak"
        val program = if (flatpak) null else File(installed.appId).takeIf { it.isAbsolute }
        val configs = buildList {
            when (os) {
                DesktopOs.WINDOWS -> program?.parentFile?.let(::add)
                DesktopOs.MACOS -> add(File(home, "Library/Application Support/rpcs3"))
                DesktopOs.LINUX -> {
                    if (flatpak) add(File(home, ".var/app/net.rpcs3.RPCS3/config/rpcs3"))
                    add(env["XDG_CONFIG_HOME"]?.takeIf { it.startsWith("/") }?.let { File(it, "rpcs3") } ?: File(home, ".config/rpcs3"))
                    if (!flatpak) add(File(home, ".var/app/net.rpcs3.RPCS3/config/rpcs3"))
                }
            }
        }
        configs.distinct().filter { it.isDirectory }.map { config ->
            val moved = listOf(File(config, "config/vfs.yml"), File(config, "vfs.yml")).firstNotNullOfOrNull { vfs ->
                runCatching { vfs.readText() }.getOrNull()?.let { VFS_HDD0.find(it)?.groupValues?.get(1) }
            }
            val dir = moved?.trim()?.trim('"', '\'')?.replace("\$(EmulatorDir)", config.absolutePath.replace('\\', '/') + "/")
                ?: (config.absolutePath.replace('\\', '/') + "/dev_hdd0")
            dir.replace('\\', '/').trimEnd('/')
        }.distinct()
    }

    /**
     * Vita3K's pref path (vita3k/app/src/app_init.cpp init_paths): `pref-path` in its config.yml, else
     * the program's folder on Windows, ~/Library/Application Support/Vita3K/Vita3K on macOS, and
     * $XDG_DATA_HOME/Vita3K/Vita3K (or ~/.local/share/Vita3K/Vita3K) on Linux, or a `portable`
     * folder next to its AppImage.
     */
    override suspend fun vita3kStorage(installed: InstalledEmulator): List<String> = withContext(Dispatchers.IO) {
        val program = File(installed.appId).takeIf { it.isAbsolute }
        val dataRoots = buildList {
            when (os) {
                DesktopOs.WINDOWS -> program?.parentFile?.let(::add)
                DesktopOs.MACOS -> add(File(home, "Library/Application Support/Vita3K/Vita3K"))
                DesktopOs.LINUX -> {
                    program?.parentFile?.let { add(File(it, "portable/fs")); add(File(it, "Vita3K/portable/fs")) }
                    env["XDG_DATA_HOME"]?.takeIf { it.startsWith("/") }?.let { add(File(it, "Vita3K/Vita3K")) }
                    add(File(home, ".local/share/Vita3K/Vita3K"))
                    add(File(home, ".local/share/Vita3K"))
                }
            }
        }
        val configs = buildList {
            dataRoots.forEach { add(File(it, "config.yml")) }
            if (os == DesktopOs.LINUX) add(File(env["XDG_CONFIG_HOME"]?.takeIf { it.startsWith("/") } ?: "$home/.config", "Vita3K/config.yml"))
        }
        val fromConfig = configs.mapNotNull { c ->
            runCatching { c.readText() }.getOrNull()?.let { PREF_PATH.find(it)?.groupValues?.get(1)?.trim()?.trim('"', '\'')?.takeIf(String::isNotEmpty) }
        }
        (fromConfig + dataRoots.map { it.absolutePath }).map { it.replace('\\', '/').trimEnd('/') }.distinct()
    }

    /**
     * Azahar's SD card (src/common/common_paths.h, file_util.cpp): `sdmc` in its user folder, which
     * is a `user` folder next to the program when there is one (portable), else %APPDATA%\\Azahar on
     * Windows, ~/Library/Application Support/Azahar on macOS, and $XDG_DATA_HOME/azahar-emu (or
     * ~/.local/share/azahar-emu, inside ~/.var/app for the Flatpak) on Linux. A custom SD card set in
     * its settings (`use_custom_storage`, `sdmc_directory` in qt-config.ini) comes first.
     */
    override suspend fun azaharStorage(installed: InstalledEmulator): List<String> = withContext(Dispatchers.IO) {
        val flatpak = installed.detectedVia == "Flatpak"
        val program = if (flatpak) null else File(installed.appId).takeIf { it.isAbsolute }
        val portable = program?.let { appRoot(it) }?.let { File(it, "user") }?.takeIf { it.isDirectory }
        val user = portable ?: when (os) {
            DesktopOs.WINDOWS -> env["APPDATA"]?.let { File(it, "Azahar") }
            DesktopOs.MACOS -> File(home, "Library/Application Support/Azahar")
            DesktopOs.LINUX -> if (flatpak) {
                File(home, ".var/app/org.azahar_emu.Azahar/data/azahar-emu")
            } else {
                env["XDG_DATA_HOME"]?.takeIf { it.startsWith("/") }?.let { File(it, "azahar-emu") } ?: File(home, ".local/share/azahar-emu")
            }
        }
        val configs = listOfNotNull(
            user?.let { File(it, "config/qt-config.ini") },
            if (os == DesktopOs.LINUX && portable == null) {
                if (flatpak) File(home, ".var/app/org.azahar_emu.Azahar/config/azahar-emu/qt-config.ini")
                else File(env["XDG_CONFIG_HOME"]?.takeIf { it.startsWith("/") } ?: "$home/.config", "azahar-emu/qt-config.ini")
            } else {
                null
            },
        )
        val custom = configs.firstNotNullOfOrNull { c ->
            val ini = runCatching { IniText(c.readText()) }.getOrNull() ?: return@firstNotNullOfOrNull null
            val on = ini.values("Data%20Storage", "use_custom_storage").lastOrNull() ?: ini.values("Data Storage", "use_custom_storage").lastOrNull()
            val dir = ini.values("Data%20Storage", "sdmc_directory").lastOrNull() ?: ini.values("Data Storage", "sdmc_directory").lastOrNull()
            dir?.takeIf { on == "true" && it.isNotBlank() }
        }
        (listOfNotNull(custom) + listOfNotNull(user?.let { File(it, "sdmc").absolutePath }))
            .map { it.replace('\\', '/').trimEnd('/') }.distinct()
    }

    override suspend fun runInstaller(run: InstallerRun, onOutput: (String) -> Unit): InstallerResult = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(run.argv).redirectErrorStream(true).also { io.github.matiyaaa.fuse.desktop.system.Processes.hostEnvironment(it.environment()) }
        run.workingDir?.let { builder.directory(File(it)) }
        val environment = builder.environment()
        // Fuse's own AppImage variables would make the emulator load Fuse's libraries, not its own.
        for (k in listOf("LD_PRELOAD", "LD_LIBRARY_PATH", "APPDIR", "APPIMAGE", "ARGV0", "OWD")) environment.remove(k)
        environment.putAll(run.env)
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return@withContext InstallerResult(null, e.message ?: "", started = false)
        }
        val tail = StringBuilder()
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        synchronized(tail) {
                            tail.append(line).append('\n')
                            if (tail.length > MAX_TAIL) tail.delete(0, tail.length - MAX_TAIL)
                        }
                        onOutput(line)
                        if (run.stopWhen?.containsMatchIn(line) == true && stopped.compareAndSet(false, true)) {
                            // Let it finish writing what it just said it installed, then close it.
                            Thread.sleep(STOP_GRACE_MS)
                            process.destroy()
                        }
                    }
                }
            } catch (e: IOException) {
                // The program closed its output.
            }
        }.apply { isDaemon = true; start() }
        try {
            val finished = runInterruptible { process.waitFor(run.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            if (!finished) {
                process.destroy()
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            }
            reader.join(2000)
            val output = synchronized(tail) { tail.toString() }
            InstallerResult(if (finished && !stopped.get()) process.exitValue() else null, output, timedOut = !finished)
        } catch (e: kotlinx.coroutines.CancellationException) {
            process.destroy()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            throw e
        }
    }

    override suspend fun stage(source: String, name: String): String? = withContext(Dispatchers.IO) {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        try {
            staging.mkdirs()
            val to = File(staging, safe)
            Files.copy(File(source).toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            to.absolutePath.replace('\\', '/')
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    override suspend fun clearStaged() = withContext(Dispatchers.IO) {
        staging.listFiles()?.forEach { it.delete() }
        Unit
    }

    private companion object {
        const val MAX_TEXT = 1024 * 1024
        const val MAX_TAIL = 16 * 1024
        const val STOP_GRACE_MS = 800L
        val VFS_HDD0 = Regex("^\\s*/dev_hdd0/\\s*:\\s*(.+?)\\s*$", RegexOption.MULTILINE)
        val PREF_PATH = Regex("^pref-path:\\s*(.+)$", RegexOption.MULTILINE)
    }
}
