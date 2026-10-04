package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.fusePath
import io.github.matiyaaa.fuse.launch.linux.LinuxInstallKind
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.InstalledEmulator
import java.io.File

/**
 * Where emulation setups keep games and firmware: ES-DE, EmuDeck and RetroDECK on Linux; ES-DE,
 * EmuDeck, RetroBat and LaunchBox on Windows; ES-DE on macOS. Only folders that exist are returned
 * (with forward slashes), and only configuration files are read (never written).
 */
internal class KnownFolders(private val home: String, private val os: DesktopOs = DesktopOs.current) {

    /** A folder with the setup it belongs to. */
    data class Found(val path: String, val label: String)

    private fun dir(path: String): String? = File(path).takeIf { it.isDirectory }?.absoluteFile?.fusePath

    /** Windows drive roots ("C:/", "D:/") that are there right now. */
    private fun drives(): List<String> = File.listRoots().orEmpty().filter { it.isDirectory }.map { it.fusePath.trimEnd('/') + "/" }

    /** A drive's name: its volume label, else "Drive D:". */
    private fun driveLabel(root: String): String {
        val letter = root.trimEnd('/')
        val label = try {
            java.nio.file.Files.getFileStore(java.nio.file.Paths.get(root)).name()
        } catch (e: Exception) {
            null
        }
        return if (label.isNullOrBlank()) "Drive $letter" else "$label ($letter)"
    }

    /** Where Fuse's file picker starts: the home folder, then mounted drives and SD cards, then the whole computer. */
    fun storageRoots(): List<Found> {
        val out = ArrayList<Found>()
        when (os) {
            DesktopOs.WINDOWS -> {
                dir(home)?.let { out += Found(it, "Home") }
                drives().forEach { out += Found(it, driveLabel(it)) }
                return out
            }
            DesktopOs.MACOS -> {
                dir(home)?.let { out += Found(it, "Home") }
                File("/Volumes").listFiles()?.filter { it.isDirectory && !it.isHidden && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                    ?.sortedBy { it.name.lowercase() }?.forEach { out += Found(it.fusePath, it.name) }
                out += Found("/", "This Mac")
                return out
            }
            DesktopOs.LINUX -> Unit
        }
        dir(home)?.let { out += Found(it, "Home") }
        val user = File(home).name
        for (base in listOf("/run/media/$user", "/media/$user", "/media", "/mnt")) {
            File(base).listFiles()?.filter { it.isDirectory && !it.isHidden }?.sortedBy { it.name.lowercase() }?.forEach { d ->
                if (out.none { it.path == d.absolutePath } && d.absolutePath != "/media/$user") out += Found(d.absolutePath, d.name)
            }
        }
        out += Found("/", "This computer")
        return out
    }

    /** ROM roots: ES-DE, EmuDeck, RetroDECK, plus the same layouts on SD cards and removable drives. */
    fun romRoots(): List<Found> {
        val out = ArrayList<Found>()
        fun add(path: String?, label: String) {
            val d = path?.let(::dir) ?: return
            if (out.none { it.path == d }) out += Found(d, label)
        }
        when (os) {
            DesktopOs.WINDOWS -> {
                // ES-DE's default is %USERPROFILE%\ROMs; EmuDeck and RetroBat keep theirs at a drive's root.
                add("$home/ROMs", "ROMs (ES-DE)")
                add("$home/Emulation/roms", "EmuDeck")
                for (d in drives()) {
                    val name = d.trimEnd('/')
                    add("${d}Emulation/roms", "EmuDeck on $name")
                    add("${d}RetroBat/roms", "RetroBat on $name")
                    add("${d}ROMs", "ROMs on $name")
                    add("${d}LaunchBox/Games", "LaunchBox on $name")
                }
                return out
            }
            DesktopOs.MACOS -> {
                add("$home/ROMs", "ROMs (ES-DE)")
                add("$home/Emulation/roms", "Emulation")
                File("/Volumes").listFiles()?.filter { it.isDirectory }?.forEach { v ->
                    add("${v.fusePath}/ROMs", "ROMs on ${v.name}")
                    add("${v.fusePath}/Emulation/roms", "Emulation on ${v.name}")
                }
                return out
            }
            DesktopOs.LINUX -> Unit
        }
        // ES-DE's default on Linux is ~/ROMs; the lower-case variants are common hand-made layouts.
        add("$home/ROMs", "ROMs (ES-DE)")
        add("$home/Roms", "Roms")
        add("$home/roms", "roms")
        emuDeckSetting("romsPath")?.let { add(it, "EmuDeck") }
        add("$home/Emulation/roms", "EmuDeck")
        retroDeckSetting(listOf("roms_folder", "roms_path"))?.let { add(it, "RetroDECK") }
        add("$home/retrodeck/roms", "RetroDECK")
        for (mount in removableMounts()) {
            add("$mount/Emulation/roms", "EmuDeck on ${File(mount).name}")
            add("$mount/retrodeck/roms", "RetroDECK on ${File(mount).name}")
            add("$mount/ROMs", "ROMs on ${File(mount).name}")
        }
        return out
    }

    /** Firmware roots of the same setups, plus `~/BIOS`. */
    fun biosRoots(): List<String> {
        val out = LinkedHashSet<String>()
        fun add(path: String?) {
            path?.let(::dir)?.let(out::add)
        }
        if (os != DesktopOs.LINUX) {
            add("$home/BIOS")
            add("$home/Emulation/bios")
            if (os == DesktopOs.WINDOWS) {
                for (d in drives()) {
                    add("${d}Emulation/bios")
                    add("${d}RetroBat/bios")
                }
            }
            return out.toList()
        }
        emuDeckSetting("biosPath")?.let(::add)
        add("$home/Emulation/bios")
        retroDeckSetting(listOf("bios_folder", "bios_path"))?.let(::add)
        add("$home/retrodeck/bios")
        add("$home/BIOS")
        for (mount in removableMounts()) {
            add("$mount/Emulation/bios")
            add("$mount/retrodeck/bios")
        }
        return out.toList()
    }

    /**
     * RetroArch `system/` folders: the `system_directory` from each retroarch.cfg Fuse can read, or the
     * default `system` folder next to it. [installed] adds the portable folder of an AppImage install.
     */
    fun retroArchSystemDirs(installed: List<InstalledEmulator> = emptyList()): List<String> {
        when (os) {
            DesktopOs.WINDOWS -> {
                // retroarch.cfg sits next to retroarch.exe; ":\system" there means its own system folder.
                val configDirs = LinkedHashSet<String>()
                installed.filter { it.id.value == "windows.retroarch" }.forEach { configDirs += File(it.appId).parentFile.fusePath }
                System.getenv("APPDATA")?.let { configDirs += "${File(it).fusePath}/RetroArch" }
                return systemDirs(configDirs)
            }
            DesktopOs.MACOS -> return systemDirs(listOf("$home/Library/Application Support/RetroArch"), cfgFolder = "config")
            DesktopOs.LINUX -> Unit
        }
        val configDirs = LinkedHashSet<String>()
        configDirs += "$home/.config/retroarch"
        configDirs += "$home/.var/app/org.libretro.RetroArch/config/retroarch"
        configDirs += "$home/snap/retroarch/current/.config/retroarch"
        for (e in installed) {
            if (!e.id.value.startsWith("linux.retroarch")) continue
            when (LinuxInstallKind.of(e.detectedVia)) {
                LinuxInstallKind.FLATPAK -> configDirs += "$home/.var/app/${e.appId}/config/retroarch"
                LinuxInstallKind.APPIMAGE -> configDirs += "${e.appId}.home/.config/retroarch"
                else -> Unit
            }
        }
        return systemDirs(configDirs)
    }

    /** Each RetroArch's `system_directory` from its retroarch.cfg (in [cfgFolder] when set), else its `system` folder. */
    private fun systemDirs(configDirs: Collection<String>, cfgFolder: String? = null): List<String> {
        val out = LinkedHashSet<String>()
        for (config in configDirs) {
            val cfg = File(cfgFolder?.let { "$config/$it/retroarch.cfg" } ?: "$config/retroarch.cfg")
            val configured = if (cfg.isFile) cfgValue(cfg, "system_directory")?.let { expand(it, config) } else null
            (configured?.let(::dir) ?: dir("$config/system"))?.let(out::add)
        }
        return out.toList()
    }

    private fun expand(value: String, configDir: String): String? {
        val v = value.replace('\\', '/')
        return when {
            v.isBlank() || v == "default" -> null
            v.startsWith("~/") -> home + v.substring(1)
            v.startsWith(":/") -> configDir + v.substring(1)
            FsPath.isAbsolute(v) -> v
            else -> null
        }
    }

    /** `key = "value"` from a RetroArch config file. */
    private fun cfgValue(file: File, key: String): String? {
        val text = SystemLinuxEnvironment.readSmallText(file.path, 1 shl 20) ?: return null
        val re = Regex("""^\s*${Regex.escape(key)}\s*=\s*"?([^"\r\n]*)"?\s*$""", RegexOption.MULTILINE)
        return re.find(text)?.groupValues?.get(1)?.trim()
    }

    /** A plain `name="value"` from EmuDeck's settings.sh (values with shell expansions are skipped). */
    private fun emuDeckSetting(name: String): String? {
        val text = SystemLinuxEnvironment.readSmallText("$home/.config/EmuDeck/settings.sh") ?: return null
        val re = Regex("""^\s*(?:export\s+)?${Regex.escape(name)}=["']?([^"'\r\n]*)["']?\s*$""", RegexOption.MULTILINE)
        val value = re.find(text)?.groupValues?.get(1)?.trim() ?: return null
        return value.takeIf { it.startsWith("/") && '$' !in it }
    }

    /** A folder setting from RetroDECK's config (older `key=value` cfg or newer JSON). */
    private fun retroDeckSetting(keys: List<String>): String? {
        val base = "$home/.var/app/net.retrodeck.retrodeck/config/retrodeck"
        for (file in listOf("$base/retrodeck.cfg", "$base/retrodeck.json")) {
            val text = SystemLinuxEnvironment.readSmallText(file) ?: continue
            for (key in keys) {
                val re = Regex(""""?${Regex.escape(key)}"?\s*[:=]\s*"?(/[^",\r\n]*)"?""")
                re.find(text)?.groupValues?.get(1)?.trim()?.takeIf { '$' !in it }?.let { return it }
            }
        }
        return null
    }

    /** Mounted removable drives: `/run/media/<user>/<label>`, `/run/media/<label>` (Steam Deck), `/media/<user>/<label>`. */
    /**
     * Steam's installs: its usual folders on each system (native, Flatpak and Snap on Linux; the
     * registry's SteamPath and Program Files on Windows), and the drives to search for more libraries.
     */
    fun steamPlaces(): Pair<List<String>, List<String>> {
        val roots = LinkedHashSet<String>()
        fun add(path: String?) {
            path?.let(::dir)?.let(roots::add)
        }
        val drives = ArrayList<String>()
        when (os) {
            DesktopOs.WINDOWS -> {
                add(windowsSteamPath())
                for (d in drives()) {
                    add("${d}Program Files (x86)/Steam")
                    add("${d}Program Files/Steam")
                    drives += d.trimEnd('/')
                }
            }
            DesktopOs.MACOS -> {
                add("$home/Library/Application Support/Steam")
                File("/Volumes").listFiles()?.filter { it.isDirectory && !it.isHidden }?.forEach { drives += it.fusePath }
            }
            DesktopOs.LINUX -> {
                add("$home/.local/share/Steam")
                add("$home/.steam/steam")
                add("$home/.steam/root")
                add("$home/.var/app/com.valvesoftware.Steam/.local/share/Steam")
                add("$home/snap/steam/common/.local/share/Steam")
                drives += removableMounts()
                File("/mnt").listFiles()?.filter { it.isDirectory }?.forEach { drives += it.path }
                drives += home
            }
        }
        return roots.toList() to drives.distinct()
    }

    /** Where Steam's registry entry says it is installed, on Windows. */
    private fun windowsSteamPath(): String? = try {
        val out = io.github.matiyaaa.fuse.desktop.system.Processes.run(
            listOf("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath"), timeoutMs = 3_000,
        )?.takeIf { it.exitCode == 0 }?.stdout
        out?.lineSequence()?.firstOrNull { "SteamPath" in it }?.substringAfter("REG_SZ", "")?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun removableMounts(): List<String> {
        val out = ArrayList<String>()
        for (root in listOf("/run/media", "/media")) {
            val level1 = File(root).listFiles()?.filter { it.isDirectory } ?: continue
            for (a in level1) {
                out += a.path
                a.listFiles()?.filter { it.isDirectory }?.forEach { out += it.path }
            }
        }
        return out
    }
}
