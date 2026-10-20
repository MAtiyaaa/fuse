package io.github.matiyaaa.fuse.launch.extras

/**
 * Firmware Fuse found, copied into each emulator's own firmware folder so every emulator here
 * starts with it, whichever folder the person keeps it in. Never over a file already there, and
 * only files the firmware check accepted. The folders, from each emulator's source:
 *
 * - DuckStation (src/core/settings.cpp, `EmuFolders::LoadConfig`): `[BIOS] SearchDirectory` in its
 *   `settings.ini`, `bios` below its data folder unless set, a relative path read from the data folder.
 * - PCSX2 (pcsx2/Pcsx2Config.cpp, `EmuFolders::LoadConfig`): `[Folders] Bios` in `inis/PCSX2.ini`,
 *   the same way.
 * - RetroArch: its `system` folder (`system_directory`), with each file by the name libretro's cores
 *   read, which is the name Fuse's firmware check looks for. Dreamcast's go in its `dc` folder.
 */
object BiosPlacement {
    /** One emulator's firmware folder, for the systems whose firmware it reads there. */
    data class Target(val emulator: String, val folder: String, val platforms: Set<String>)

    /** Below RetroArch's system folder, for the systems whose cores read their firmware from a folder of its own. */
    private val RETROARCH_SUBFOLDER = mapOf("dc" to "dc")

    /** DuckStation's firmware folder: [settingsIni]'s `[BIOS] SearchDirectory`, else `bios`, below [dataRoot]. */
    fun duckStation(dataRoot: String, settingsIni: String?): String = resolve(dataRoot, iniValue(settingsIni, "BIOS", "SearchDirectory") ?: "bios")

    /** PCSX2's firmware folder: [pcsx2Ini]'s `[Folders] Bios`, else `bios`, below [dataRoot]. */
    fun pcsx2(dataRoot: String, pcsx2Ini: String?): String = resolve(dataRoot, iniValue(pcsx2Ini, "Folders", "Bios") ?: "bios")

    /** Where [platform]'s firmware goes in RetroArch's [system] folder. */
    fun retroArch(system: String, platform: String): String = system.trimEnd('/', '\\').let { base -> RETROARCH_SUBFOLDER[platform]?.let { "$base/$it" } ?: base }

    /**
     * The copies to make: each of [found] (paths of accepted firmware files) into [folder], unless a
     * file of that name is already among [present] (names in the folder, any case), or it is already
     * the file in that folder. Each as (from, to).
     */
    fun copies(found: List<String>, folder: String, present: Set<String>): List<Pair<String, String>> {
        val here = present.map { it.lowercase() }.toSet()
        val dir = folder.replace('\\', '/').trimEnd('/')
        return found.distinctBy { name(it).lowercase() }.mapNotNull { path ->
            val n = name(path)
            val parent = path.replace('\\', '/').substringBeforeLast('/', "")
            if (n.lowercase() in here || parent.equals(dir, ignoreCase = false)) null else path to "$dir/$n"
        }
    }

    private fun name(path: String) = path.replace('\\', '/').substringAfterLast('/')

    private fun resolve(root: String, value: String): String {
        val v = value.trim().trim('"')
        val absolute = v.startsWith("/") || v.startsWith("\\") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(v)
        return if (absolute) v else "${root.trimEnd('/', '\\')}/${v.removePrefix("./").removePrefix(".\\").ifEmpty { "bios" }}"
    }

    /** The value of [key] in [section] of an INI text, as the emulators' own settings readers take it. */
    private fun iniValue(text: String?, section: String, key: String): String? {
        if (text == null) return null
        var inSection = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                inSection = line.substring(1, line.length - 1).trim().equals(section, ignoreCase = true)
                continue
            }
            if (!inSection) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            if (line.substring(0, eq).trim().equals(key, ignoreCase = true)) return line.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }
}
