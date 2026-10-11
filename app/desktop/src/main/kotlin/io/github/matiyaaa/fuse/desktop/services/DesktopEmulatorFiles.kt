package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.launch.patches.DuckStationHome
import io.github.matiyaaa.fuse.launch.patches.EnhancementRules
import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.patches.Pcsx2Home
import io.github.matiyaaa.fuse.launch.patches.PlannedFile
import io.github.matiyaaa.fuse.launch.patches.PpssppHome
import io.github.matiyaaa.fuse.launch.patches.RetroArchCfg
import io.github.matiyaaa.fuse.launch.patches.RetroArchHome
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles
import io.github.matiyaaa.fuse.ui.shell.store.InstallerResult
import io.github.matiyaaa.fuse.ui.shell.store.InstallerRun
import io.github.matiyaaa.fuse.ui.shell.store.SourceForm
import io.github.matiyaaa.fuse.ui.shell.store.SourceListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    /** Where each restoration point keeps the files a person's own additions replaced (see [install]). */
    private val restorePoints: File = File(backups.parentFile, "restore-points"),
    /** Where files fetched from a mod site wait to be looked through (see [fetch]). */
    private val fetched: File = File(backups.parentFile, "fetched-mods"),
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
            cheats = folder("Cheats", "cheats"),
            patchesZip = zipCandidates(appRoot, flatpak).firstOrNull { it.isFile && it.canRead() }?.absolutePath?.replace('\\', '/'),
            textures = folder("Textures", "textures").also { roots += File(it).absolutePath },
        )
    }

    /**
     * DuckStation's folders (src/core/core.cpp, SetDataRoot; settings.cpp, EmuFolders::LoadConfig):
     * the program's folder when portable.txt or settings.ini is beside it; otherwise Documents\DuckStation
     * on Windows when an older install made it, else %LOCALAPPDATA%\DuckStation; $XDG_CONFIG_HOME/duckstation
     * on Linux when that is set (always so inside its Flatpak), else ~/.local/share/duckstation; and
     * ~/Library/Application Support/DuckStation on macOS. settings.ini's `[Folders]` can move the cheats,
     * patches and game settings folders. The bundled cheats.zip and patches.zip are in its resources
     * folder, beside the program (inside the bundle on macOS, unreadable inside a running AppImage).
     */
    override suspend fun duckstation(installed: InstalledEmulator): DuckStationHome? = withContext(Dispatchers.IO) {
        val flatpak = installed.detectedVia == "Flatpak"
        val program = if (flatpak) null else File(installed.appId).takeIf { it.isAbsolute }
        // DuckStation's AppRoot is the folder of the program itself, inside the bundle on macOS.
        val programDir = program?.let { if (it.path.endsWith(".app")) File(it, "Contents/MacOS") else it.parentFile }
        val portable = programDir?.takeIf { File(it, "portable.txt").isFile || File(it, "settings.ini").isFile }
        val data = portable ?: when (os) {
            DesktopOs.WINDOWS -> listOfNotNull(env["USERPROFILE"]?.let { File(it, "Documents/DuckStation") }, env["OneDrive"]?.let { File(it, "Documents/DuckStation") })
                .firstOrNull { it.isDirectory } ?: env["LOCALAPPDATA"]?.let { File(it, "DuckStation") }
            DesktopOs.MACOS -> File(home, "Library/Application Support/DuckStation")
            DesktopOs.LINUX -> if (flatpak) {
                listOf(File(home, ".var/app/$DUCKSTATION_FLATPAK/config/duckstation"), File(home, ".var/app/$DUCKSTATION_FLATPAK/data/duckstation"))
                    .firstOrNull { File(it, "settings.ini").isFile }
            } else {
                env["XDG_CONFIG_HOME"]?.takeIf { File(it).isAbsolute }?.let { File(it, "duckstation") } ?: File(home, ".local/share/duckstation")
            }
        } ?: return@withContext null
        val ini = File(data, "settings.ini")
        // Until DuckStation has been set up once there is nothing of its to change.
        if (!ini.isFile) return@withContext null
        val settings = runCatching { IniText(ini.readText()) }.getOrNull()
        fun folder(key: String, default: String): String {
            val value = settings?.values("Folders", key)?.lastOrNull()?.takeIf { it.isNotBlank() } ?: default
            val f = File(value)
            return (if (f.isAbsolute) f else File(data, value)).absolutePath.replace('\\', '/')
        }
        val resources = buildList {
            if (flatpak) {
                for (base in listOf("/var/lib/flatpak/app", "$home/.local/share/flatpak/app")) add(File(base, "$DUCKSTATION_FLATPAK/current/active/files/bin/resources"))
            }
            program?.let { p -> p.path.indexOf(".app").takeIf { it > 0 }?.let { add(File(p.path.substring(0, it + 4), "Contents/Resources")) } }
            programDir?.let { add(File(it, "resources")) }
        }
        fun zip(name: String) = resources.map { File(it, name) }.firstOrNull { it.isFile && it.canRead() }?.absolutePath?.replace('\\', '/')
        roots += data.absolutePath
        DuckStationHome(
            dataRoot = data.absolutePath.replace('\\', '/'),
            gameSettings = folder("GameSettings", "gamesettings"),
            cheats = folder("Cheats", "cheats"),
            patches = folder("Patches", "patches"),
            cheatsZip = zip("cheats.zip"),
            patchesZip = zip("patches.zip"),
            textures = folder("Textures", "textures").also { roots += File(it).absolutePath },
        )
    }

    /**
     * PPSSPP's memory stick (UI/NativeApp.cpp, NativeInit; Windows/main.cpp, InitMemstickDirectory):
     * on Windows the folder installed.txt beside the program names (Documents\PPSSPP when it is empty),
     * else `memstick` beside the program, else Documents\PPSSPP; on Linux $XDG_CONFIG_HOME/ppsspp or
     * ~/.config/ppsspp (inside ~/.var/app for the Flatpak); on macOS ~/.config/ppsspp unless another
     * folder was chosen in PPSSPP. It counts once it holds a `PSP` folder.
     */
    override suspend fun ppsspp(installed: InstalledEmulator): PpssppHome? = withContext(Dispatchers.IO) {
        val flatpak = installed.detectedVia == "Flatpak"
        val program = if (flatpak) null else File(installed.appId).takeIf { it.isAbsolute }
        val documents = env["USERPROFILE"]?.let { File(it, "Documents/PPSSPP") }
        val candidates = when (os) {
            DesktopOs.WINDOWS -> buildList {
                val dir = program?.parentFile
                val installedTxt = dir?.let { File(it, "installed.txt") }?.takeIf { it.isFile }
                if (installedTxt != null) {
                    val named = runCatching { installedTxt.readLines().firstOrNull()?.removePrefix("\uFEFF")?.trim() }.getOrNull()
                    if (!named.isNullOrEmpty()) add(File(named))
                } else {
                    dir?.let { add(File(it, "memstick")) }
                }
                documents?.let(::add)
                env["OneDrive"]?.let { add(File(it, "Documents/PPSSPP")) }
            }
            DesktopOs.MACOS -> listOf(File(home, ".config/ppsspp"))
            DesktopOs.LINUX -> if (flatpak) {
                listOf(File(home, ".var/app/org.ppsspp.PPSSPP/config/ppsspp"))
            } else {
                listOf(env["XDG_CONFIG_HOME"]?.takeIf { File(it).isAbsolute }?.let { File(it, "ppsspp") } ?: File(home, ".config/ppsspp"))
            }
        }
        val stick = candidates.firstOrNull { File(it, "PSP").isDirectory } ?: return@withContext null
        roots += stick.absolutePath
        PpssppHome(stick.absolutePath.replace('\\', '/'))
    }

    /**
     * RetroArch's settings and cheats (frontend/drivers/platform_unix.c, platform_win32.c and
     * platform_darwin.m): its retroarch.cfg beside the program or in %APPDATA%\RetroArch on Windows, in
     * ~/Library/Application Support/RetroArch/config on macOS, and in $XDG_CONFIG_HOME/retroarch (or
     * ~/.config/retroarch, inside ~/.var/app for the Flatpak, or the Snap's) on Linux. Its cheats
     * folder (`cheat_database_path`) and config folder for overrides (`rgui_config_directory`) are
     * read from it, with RetroArch's defaults: `cheats` and `config` beside the config on Linux and
     * beside the program on Windows, `cht` and `config` in Application Support on macOS.
     */
    override suspend fun retroArch(installed: InstalledEmulator): RetroArchHome? = withContext(Dispatchers.IO) {
        val flatpak = installed.detectedVia == "Flatpak"
        val program = if (flatpak) null else File(installed.appId).takeIf { it.isAbsolute }
        val programDir = program?.takeIf { !it.path.endsWith(".app") }?.parentFile
        val xdg = env["XDG_CONFIG_HOME"]?.takeIf { File(it).isAbsolute } ?: "$home/.config"
        val macData = File(home, "Library/Application Support/RetroArch")
        val candidates = when (os) {
            DesktopOs.WINDOWS -> listOfNotNull(programDir?.let { File(it, "retroarch.cfg") }, env["APPDATA"]?.let { File(it, "RetroArch/retroarch.cfg") })
            DesktopOs.MACOS -> listOf(File(macData, "config/retroarch.cfg"))
            DesktopOs.LINUX -> if (flatpak) {
                listOf(File(home, ".var/app/org.libretro.RetroArch/config/retroarch/retroarch.cfg"))
            } else {
                listOf(File(xdg, "retroarch/retroarch.cfg"), File(home, "snap/retroarch/current/.config/retroarch/retroarch.cfg"))
            }
        }
        // Until RetroArch has been started once there is nothing of its to change.
        val config = candidates.firstOrNull { it.isFile } ?: return@withContext null
        val cfg = runCatching { RetroArchCfg.parse(config.readText()) }.getOrDefault(emptyMap())
        val base = (if (os == DesktopOs.WINDOWS) programDir ?: config.parentFile else config.parentFile).absolutePath.replace('\\', '/')
        val (cheatsDefault, configDefault) = when (os) {
            DesktopOs.MACOS -> macData.absolutePath.let { "$it/cht" to "$it/config" }
            else -> "$base/cheats" to "$base/config"
        }
        val programPath = programDir?.absolutePath?.replace('\\', '/')
        val homePath = home.replace('\\', '/')
        val cheats = RetroArchCfg.folder(cfg["cheat_database_path"], cheatsDefault.replace('\\', '/'), homePath, programPath)
        val configDir = RetroArchCfg.folder(cfg["rgui_config_directory"], configDefault.replace('\\', '/'), homePath, programPath)
        roots += File(cheats).absolutePath
        roots += File(configDir).absolutePath
        RetroArchHome(config.absolutePath.replace('\\', '/'), cheats, configDir)
    }

    override suspend fun remove(path: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(path).absoluteFile
        val inside = roots.any { root -> target.normalize().path.startsWith(File(root).normalize().path + File.separator) }
        if (!inside || !target.isFile) return@withContext false
        try {
            Files.delete(target.toPath())
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
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

    override suspend fun modHome(query: io.github.matiyaaa.fuse.sync.SaveQuery): Result<io.github.matiyaaa.fuse.sync.ModHome> = withContext(Dispatchers.IO) {
        val host = when (os) { DesktopOs.WINDOWS -> "WINDOWS"; DesktopOs.MACOS -> "MACOS"; DesktopOs.LINUX -> "LINUX" }
        val found = io.github.matiyaaa.fuse.sync.ModFolders.locate(query, io.github.matiyaaa.fuse.sync.FileSaveEnvironment(host, home) { env[it] })
        found.onSuccess { h ->
            roots += File(h.folder).absolutePath
            h.graphicsMods?.let { roots += File(it).absolutePath }
            h.settingsFile?.let { roots += File(it).absoluteFile.parentFile.absolutePath }
        }
    }

    override suspend fun rpcs3GameFiles(installed: InstalledEmulator, launchPath: String, serial: String?): String? = withContext(Dispatchers.IO) {
        val launch = File(launchPath)
        // A game folder: PS3_GAME/USRDIR/EBOOT.BIN, or the folder itself.
        val inFolder = generateSequence(if (launch.isDirectory) launch else launch.parentFile) { it.parentFile }.take(4)
            .firstNotNullOfOrNull { d ->
                when {
                    d.name.equals("USRDIR", ignoreCase = true) && d.isDirectory -> d
                    else -> listOf("PS3_GAME/USRDIR", "USRDIR").map { File(d, it) }.firstOrNull { it.isDirectory }
                }
            }
        val usrdir = inFolder ?: serial?.uppercase()?.takeIf { PS3_SERIAL.matches(it) }?.let { id ->
            rpcs3Storage(installed).map { File(it, "game/$id/USRDIR") }.firstOrNull { it.isDirectory }
        } ?: return@withContext null
        roots += usrdir.absolutePath
        usrdir.absolutePath.replace('\\', '/')
    }

    override suspend fun shadPs4Mods(launchPath: String): Pair<String, Set<String>>? = withContext(Dispatchers.IO) {
        val launch = File(launchPath)
        val game = (if (launch.isDirectory) launch else launch.parentFile)?.takeIf { d -> d.list()?.any { it.equals("eboot.bin", ignoreCase = true) } == true }
            ?: return@withContext null
        val top = game.list()?.toSet().orEmpty()
        val mods = File(game.parentFile, game.name + "-mods")
        roots += mods.absolutePath
        mods.absolutePath.replace('\\', '/') to top
    }

    /**
     * Vita3K's pref path (vita3k/app/src/app_init.cpp init_paths): `pref-path` in its config.yml, else
     * `portable/fs` beside the program when that is there, %APPDATA%\\Vita3K\\Vita3K on Windows,
     * ~/Library/Application Support/Vita3K/Vita3K (or its `fs` folder) on macOS, and
     * $XDG_DATA_HOME/Vita3K/Vita3K (or ~/.local/share/Vita3K/Vita3K) on Linux, or a `portable`
     * folder next to its AppImage.
     */
    override suspend fun vita3kStorage(installed: InstalledEmulator): List<String> = withContext(Dispatchers.IO) {
        val program = File(installed.appId).takeIf { it.isAbsolute }
        val dataRoots = buildList {
            when (os) {
                // A `portable` folder beside the program holds everything; otherwise ux0 is in SDL's
                // preference folder, %APPDATA%\Vita3K\Vita3K (the program's folder keeps only its config).
                DesktopOs.WINDOWS -> {
                    program?.parentFile?.let { add(File(it, "portable/fs")) }
                    env["APPDATA"]?.let { add(File(it, "Vita3K/Vita3K")) }
                    program?.parentFile?.let(::add)
                }
                DesktopOs.MACOS -> {
                    program?.let { appRoot(it) }?.let { add(File(it, "portable/fs")) }
                    add(File(home, "Library/Application Support/Vita3K/Vita3K"))
                    // Newer installs keep ux0 in an `fs` folder there.
                    add(File(home, "Library/Application Support/Vita3K/Vita3K/fs"))
                }
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
            // Windows keeps config.yml beside the program; a portable install inside `portable`.
            if (os == DesktopOs.WINDOWS) program?.parentFile?.let { add(File(it, "config.yml")); add(File(it, "portable/config.yml")) }
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


    override suspend fun inspect(source: String): SourceListing? = withContext(Dispatchers.IO) {
        val f = File(source)
        try {
            when {
                f.isDirectory -> {
                    val root = f.toPath()
                    val entries = ArrayList<String>()
                    var bytes = 0L
                    var truncated = false
                    Files.walk(root).use { stream ->
                        for (p in stream) {
                            if (p == root) continue
                            // A link could reach anywhere: only the folder's own files are taken.
                            if (Files.isSymbolicLink(p) || !Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                            if (entries.size >= EnhancementRules.MAX_ENTRIES) { truncated = true; break }
                            entries += root.relativize(p).toString().replace('\\', '/')
                            bytes += Files.size(p)
                        }
                    }
                    SourceListing(f.name, SourceForm.FOLDER, entries.sorted(), bytes, truncated)
                }
                f.isFile && f.name.endsWith(".zip", ignoreCase = true) -> ZipFile(f).use { z ->
                    val entries = ArrayList<String>()
                    var bytes = 0L
                    var truncated = false
                    for (e in z.entries()) {
                        if (e.isDirectory) continue
                        if (entries.size >= EnhancementRules.MAX_ENTRIES) { truncated = true; break }
                        entries += e.name
                        bytes += e.size.coerceAtLeast(0)
                    }
                    SourceListing(f.name, SourceForm.ARCHIVE, entries, bytes, truncated)
                }
                f.isFile -> SourceListing(f.name, SourceForm.FILE, listOf(f.name), f.length())
                else -> null
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: java.io.UncheckedIOException) {
            null
        }
    }

    override suspend fun sourceText(source: String, entry: String): String? = withContext(Dispatchers.IO) {
        try {
            open(File(source), entry)?.use { it.readNBytes(MAX_TEXT + 1) }?.takeIf { it.size <= MAX_TEXT }?.let { bytes ->
                // Text, not a picture or a program: no NUL bytes in it.
                if (bytes.any { it == 0.toByte() }) null else bytes.decodeToString()
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /** A stream of [entry] in [source]: a file below a folder (not through a link), an archive's entry, or the file itself. */
    private fun open(source: File, entry: String): java.io.InputStream? {
        if (EnhancementRules.unsafe(entry)) return null
        return when {
            source.isDirectory -> {
                val file = File(source, entry)
                val inside = file.canonicalFile.path.startsWith(source.canonicalFile.path + File.separator)
                if (!inside || Files.isSymbolicLink(file.toPath()) || !file.isFile) null else file.inputStream()
            }
            source.isFile && source.name.endsWith(".zip", ignoreCase = true) -> {
                val zip = ZipFile(source)
                val e = zip.getEntry(entry)
                if (e == null || e.isDirectory) {
                    zip.close()
                    null
                } else {
                    // The archive closes with its entry's stream.
                    object : java.io.FilterInputStream(zip.getInputStream(e)) {
                        override fun close() {
                            try { super.close() } finally { zip.close() }
                        }
                    }
                }
            }
            source.isFile && entry == source.name -> source.inputStream()
            else -> null
        }
    }

    /** True when [target] is inside one of the emulator folders Fuse has found, compared as the system resolves them. */
    private fun allowed(target: File, also: Collection<String> = emptyList()): Boolean = rootOf(target, also) != null

    /** The folder Fuse found that [target] is inside (of those found this run, and [also]), resolved as the system does; null when none. */
    private fun rootOf(target: File, also: Collection<String> = emptyList()): String? {
        val path = runCatching { target.canonicalFile.path }.getOrNull() ?: return null
        return (roots.toList() + also).firstNotNullOfOrNull { root ->
            val r = runCatching { File(root).canonicalFile.path }.getOrNull() ?: return@firstNotNullOfOrNull null
            r.takeIf { path.startsWith(r + File.separator) }
        }
    }

    override suspend fun install(source: String?, files: List<PlannedFile>, texts: Map<String, String>, restorePoint: String): Boolean = withContext(Dispatchers.IO) {
        val id = restorePoint.filter { it.isLetterOrDigit() || it == '-' }
        if (id.isEmpty() || id != restorePoint) return@withContext false
        val destinations = files.map { File(it.to).absoluteFile } + texts.keys.map { File(it).absoluteFile }
        if (destinations.isEmpty() || destinations.distinctBy { it.path }.size != destinations.size) return@withContext false
        if (!destinations.all { allowed(it) }) return@withContext false
        if (files.isNotEmpty() && source == null) return@withContext false
        val point = File(restorePoints, id)
        if (point.exists()) return@withContext false
        try {
            point.mkdirs()
            val manifest = StringBuilder()
            // What is there now: copied aside, or noted as new, with the folders that will be made.
            val made = LinkedHashSet<String>()
            for ((i, dest) in destinations.withIndex()) {
                var parent = dest.parentFile
                val missing = ArrayList<File>()
                while (parent != null && !parent.exists()) {
                    missing += parent
                    parent = parent.parentFile
                }
                missing.reversed().forEach { if (made.add(it.path)) manifest.append("D\t").append(it.path).append('\n') }
                if (dest.exists()) {
                    if (!dest.isFile) throw IOException("${dest.path} is a folder")
                    Files.copy(dest.toPath(), File(point, "$i").toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                    manifest.append("R\t").append(dest.path).append('\t').append(i).append('\n')
                } else {
                    manifest.append("N\t").append(dest.path).append('\n')
                }
            }
            // The folders that let these in, so they can be put back after Fuse restarts, before it has looked for them again.
            destinations.mapNotNull { rootOf(it) }.distinct().forEach { manifest.append("A\t").append(it).append('\n') }
            File(point, MANIFEST).writeText(manifest.toString())
            val from = source?.let(::File)
            for (planned in files) {
                val dest = File(planned.to).absoluteFile
                dest.parentFile.mkdirs()
                val tmp = File(dest.parentFile, ".${dest.name}.fuse.tmp")
                val stream = if (planned.from.isEmpty()) from!!.inputStream() else open(from!!, planned.from)
                (stream ?: throw IOException("${planned.from} is not in ${from.name}")).use { input ->
                    Files.copy(input, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                move(tmp, dest)
            }
            for ((path, text) in texts) {
                val dest = File(path).absoluteFile
                dest.parentFile.mkdirs()
                val tmp = File(dest.parentFile, ".${dest.name}.fuse.tmp")
                tmp.writeText(text)
                move(tmp, dest)
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            putBack(point, emptySet())
            throw e
        } catch (e: Exception) {
            putBack(point, emptySet())
            false
        }
    }

    override suspend fun undo(restorePoint: String, keep: Set<String>): Boolean = withContext(Dispatchers.IO) {
        val id = restorePoint.filter { it.isLetterOrDigit() || it == '-' }
        if (id.isEmpty() || id != restorePoint) return@withContext false
        val point = File(restorePoints, id)
        if (!File(point, MANIFEST).isFile) return@withContext false
        putBack(point, keep.map { File(it).absoluteFile.path }.toSet())
    }

    /** Puts back everything [point]'s manifest lists, except [keep], then removes it. */
    private fun putBack(point: File, keep: Set<String>): Boolean {
        val manifest = File(point, MANIFEST)
        if (!manifest.isFile) {
            point.deleteRecursively()
            return true
        }
        var ok = true
        val lines = manifest.readLines().map { it.split('\t') }
        val recorded = lines.filter { it[0] == "A" }.mapNotNull { it.getOrNull(1) }
        for (p in lines) {
            if (p[0] == "A") continue
            val dest = File(p.getOrNull(1) ?: continue)
            if (dest.path in keep || !allowed(dest, recorded)) continue
            when (p[0]) {
                "N" -> if (dest.isFile && !dest.delete()) ok = false
                "R" -> {
                    val copy = File(point, p.getOrNull(2) ?: continue)
                    if (!copy.isFile) { ok = false; continue }
                    try {
                        val tmp = File(dest.parentFile, ".${dest.name}.fuse.tmp")
                        Files.copy(copy.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        move(tmp, dest)
                    } catch (e: IOException) {
                        ok = false
                    }
                }
            }
        }
        // Folders Fuse made, deepest first, once nothing is left in them.
        for (p in lines.filter { it[0] == "D" }.reversed()) {
            val dir = File(p.getOrNull(1) ?: continue)
            // A folder Fuse made can be the very one it was let into (a shadPS4 game's -mods folder).
            val made = allowed(dir, recorded) || recorded.any { runCatching { File(it).canonicalPath == dir.canonicalPath }.getOrDefault(false) }
            if (dir.isDirectory && made && dir.list().isNullOrEmpty()) dir.delete()
        }
        if (ok) point.deleteRecursively()
        return ok
    }

    private fun move(tmp: File, dest: File) {
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }


    override suspend fun fetch(url: String, name: String, size: Long?, md5: String?, maxBytes: Long, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "download" }.take(120)
        val dir = File(fetched, System.nanoTime().toString(36)).apply { mkdirs() }
        val target = File(dir, safe)
        try {
            val client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .build()
            val request = java.net.http.HttpRequest.newBuilder(java.net.URI(url))
                .header("User-Agent", "Fuse")
                .timeout(java.time.Duration.ofMinutes(30))
                .GET().build()
            val response = runInterruptible { client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream()) }
            // Redirects may only go on to secure addresses.
            if (response.statusCode() !in 200..299 || response.uri().scheme != "https") {
                response.body().close()
                dir.deleteRecursively()
                return@withContext null
            }
            val total = size?.takeIf { it > 0 } ?: response.headers().firstValueAsLong("content-length").orElse(-1)
            val digest = java.security.MessageDigest.getInstance("MD5")
            var read = 0L
            response.body().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                        if (read > maxBytes) throw IOException("larger than allowed")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val sum = digest.digest().joinToString("") { "%02x".format(it) }
            if ((md5 != null && !sum.equals(md5, ignoreCase = true)) || (size != null && size > 0 && read != size)) {
                dir.deleteRecursively()
                return@withContext null
            }
            target.absolutePath.replace('\\', '/')
        } catch (e: kotlinx.coroutines.CancellationException) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            null
        }
    }

    override suspend fun clearFetched() = withContext(Dispatchers.IO) {
        fetched.deleteRecursively()
        Unit
    }

    private companion object {
        const val MAX_TEXT = 1024 * 1024
        const val MANIFEST = "manifest.tsv"
        private val PS3_SERIAL = Regex("^[A-Z]{4}[0-9]{5}$")
        const val MAX_TAIL = 16 * 1024
        const val STOP_GRACE_MS = 800L
        const val DUCKSTATION_FLATPAK = "org.duckstation.DuckStation"
        val VFS_HDD0 = Regex("^\\s*/dev_hdd0/\\s*:\\s*(.+?)\\s*$", RegexOption.MULTILINE)
        val PREF_PATH = Regex("^pref-path:\\s*(.+)$", RegexOption.MULTILINE)
    }
}
