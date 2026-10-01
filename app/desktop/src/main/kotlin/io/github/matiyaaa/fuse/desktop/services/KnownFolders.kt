package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.launch.linux.LinuxInstallKind
import io.github.matiyaaa.fuse.model.InstalledEmulator
import java.io.File

/**
 * Where Linux emulation setups keep games and firmware. Only folders that exist are returned, and
 * only configuration files are read (never written).
 */
internal class KnownFolders(private val home: String) {

    /** A folder with the setup it belongs to. */
    data class Found(val path: String, val label: String)

    private fun dir(path: String): String? = File(path).takeIf { it.isDirectory }?.absolutePath

    /** Where Fuse's file picker starts: the home folder, then mounted drives and SD cards, then the whole computer. */
    fun storageRoots(): List<Found> {
        val out = ArrayList<Found>()
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
        val out = LinkedHashSet<String>()
        for (config in configDirs) {
            val cfg = File("$config/retroarch.cfg")
            val configured = if (cfg.isFile) cfgValue(cfg, "system_directory")?.let { expand(it, config) } else null
            (configured?.let(::dir) ?: dir("$config/system"))?.let(out::add)
        }
        return out.toList()
    }

    private fun expand(value: String, configDir: String): String? = when {
        value.isBlank() || value == "default" -> null
        value.startsWith("~/") -> home + value.substring(1)
        value.startsWith(":/") -> configDir + value.substring(1)
        value.startsWith("/") -> value
        else -> null
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
