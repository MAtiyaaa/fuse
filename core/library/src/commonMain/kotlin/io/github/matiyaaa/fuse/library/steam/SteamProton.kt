package io.github.matiyaaa.fuse.library.steam

/** A Proton on this computer: the name Steam knows it by, its folder, and its version's numbers (none for Experimental and Hotfix). */
data class ProtonTool(val name: String, val folder: String, val version: List<Int>)

/**
 * Which Proton a Windows program Fuse starts outside Steam gets (umu-launcher takes it as
 * `PROTONPATH`, "a path to a Proton directory": umu-launcher's README, Usage): the compatibility
 * tool the person chose for every game in Steam, else the newest one installed. Reads only.
 *
 * - The person's choice is Steam Play's tool "for all other titles", which Steam keeps in
 *   `config/config.vdf` as `InstallConfigStore/Software/Valve/Steam/CompatToolMapping`, entry `0`,
 *   its `name` the tool's own.
 * - A tool someone added (GE-Proton and the like) is a folder in `compatibilitytools.d` whose
 *   `compatibilitytool.vdf` names it: `compatibilitytools/compat_tools/<name>` with its
 *   `install_path` (relative to that file), as ValveSoftware/Proton's
 *   `compatibilitytool.vdf.template` writes it.
 * - Valve's own Protons are Steam apps in a library's `steamapps/common` (`Proton 9.0 (Beta)`,
 *   `Proton - Experimental`, `Proton Hotfix`), each with its `proton` script beside it; Steam names
 *   them `proton_9` (a minor version other than 0 joined on: `proton_63` for 6.3),
 *   `proton_experimental` and `proton_hotfix` in that mapping.
 */
object SteamProton {
    private val VALVE = Regex("""^Proton (\d+)\.(\d+)\b""")

    /** The tool Steam Play uses for every other title; null when the person set none. */
    fun defaultTool(configVdf: String): String? {
        var node: Any? = Vdf.parse(configVdf)
        for (key in listOf("installconfigstore", "software", "valve", "steam", "compattoolmapping", "0")) node = (node as? Map<*, *>)?.get(key)
        return ((node as? Map<*, *>)?.get("name") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The tools the `compatibilitytool.vdf` in [folder] declares, each in its own folder. */
    fun declared(vdf: String, folder: String): List<ProtonTool> {
        val tools = ((Vdf.parse(vdf)["compatibilitytools"] as? Map<*, *>)?.get("compat_tools") as? Map<*, *>) ?: return emptyList()
        return tools.mapNotNull { (key, value) ->
            val name = (key as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = ((value as? Map<*, *>)?.get("install_path") as? String)?.replace('\\', '/') ?: "."
            val dir = if (path.startsWith("/")) path else if (path == "." || path.isEmpty()) folder else "${folder.trimEnd('/')}/${path.removePrefix("./")}"
            ProtonTool(name, dir.trimEnd('/'), numbers(name))
        }
    }

    /** One of Valve's Protons by its folder's name in `steamapps/common`; null for any other folder. */
    fun valve(folderName: String, folder: String): ProtonTool? {
        VALVE.find(folderName)?.let { m ->
            val (major, minor) = m.destructured
            return ProtonTool("proton_" + major + if (minor == "0") "" else minor, folder, listOf(major.toInt(), minor.toInt()))
        }
        return when (folderName) {
            "Proton - Experimental" -> ProtonTool("proton_experimental", folder, emptyList())
            "Proton Hotfix" -> ProtonTool("proton_hotfix", folder, emptyList())
            else -> null
        }
    }

    /** The person's own choice when it is one of [tools], else the newest with a version (Experimental and Hotfix only when chosen). */
    fun choose(tools: List<ProtonTool>, default: String?): ProtonTool? =
        tools.firstOrNull { default != null && it.name.equals(default, ignoreCase = true) }
            ?: tools.filter { it.version.isNotEmpty() }.maxWithOrNull(Comparator { a, b ->
                (0 until maxOf(a.version.size, b.version.size)).firstNotNullOfOrNull { i -> (a.version.getOrElse(i) { -1 }).compareTo(b.version.getOrElse(i) { -1 }).takeIf { it != 0 } } ?: 0
            })

    private fun numbers(name: String): List<Int> = Regex("""\d+""").findAll(name).mapNotNull { it.value.toIntOrNull() }.toList()
}
