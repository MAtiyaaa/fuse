package io.github.matiyaaa.fuse.launch.patches

/**
 * A YAML document as plain values: maps in file order (string keys), lists and strings. Every
 * scalar stays text (RPCS3's app versions such as `01.00` must not become numbers); anchors and
 * aliases are followed. Null when it isn't YAML.
 */
expect object YamlTree {
    fun parse(text: String): Any?
}

/**
 * One way a patch in RPCS3's patch files applies to a game: the executable it is for ([hash],
 * `PPU-<sha1>`), its name, and the title, serial and app version it is listed under there ("All"
 * where the file says so).
 */
data class Rpcs3PatchEntry(
    val hash: String,
    val description: String,
    val title: String,
    val serial: String,
    val version: String,
    val author: String? = null,
    val notes: String? = null,
    val group: String? = null,
)

/**
 * RPCS3's patches, as its patch engine reads them (rpcs3/Utilities/bin_patch.cpp): `patches/patch.yml`
 * (downloaded by its Patch Manager), `patches/imported_patch.yml` and `patches/<SERIAL>_patch.yml`, each
 * a map of executable hashes to patch names, each listing the games it is for under `Games` as title,
 * serial and app versions. RPCS3 applies a patch when `patch_config.yml` has `Enabled: true` for that
 * hash, name, title, serial and version, and the game's executable has that hash, so turning a patch on
 * for every hash it lists for this game's serial is exactly what its Patch Manager does.
 */
object Rpcs3Patches {
    /** The patch engine version RPCS3 reads; files of any other are skipped whole, as RPCS3 does. */
    const val ENGINE_VERSION = "1.2"
    const val ALL = "All"

    /** Patch files RPCS3 reads for [serial], in its order. */
    fun files(serial: String): List<String> = listOf("patch.yml", "imported_patch.yml", "${serial}_patch.yml")

    /** `patch_config.yml`: in RPCS3's `config` folder on Windows, beside its other settings elsewhere. */
    fun configPath(configDir: String, windows: Boolean): String =
        if (windows) "$configDir/config/patch_config.yml" else "$configDir/patch_config.yml"

    /**
     * Every way a patch in [text] applies to [serial] at [appVersion] (any version it lists when that
     * is unknown): the serial or "All", the version or "All", as bin_patch.cpp matches them.
     */
    fun forGame(text: String, serial: String, appVersion: String?): List<Rpcs3PatchEntry> {
        val root = YamlTree.parse(text) as? Map<*, *> ?: return emptyList()
        if ((root["Version"] as? String)?.trim() != ENGINE_VERSION) return emptyList()
        val out = ArrayList<Rpcs3PatchEntry>()
        for ((hashKey, patches) in root) {
            val hash = hashKey as? String ?: continue
            if (hash == "Version" || hash == "Anchors" || patches !is Map<*, *>) continue
            for ((descKey, info) in patches) {
                val description = descKey as? String ?: continue
                if (info !is Map<*, *>) continue
                val games = info["Games"] as? Map<*, *> ?: continue
                fun text(key: String) = (info[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                for ((titleKey, serials) in games) {
                    val title = titleKey as? String ?: continue
                    if (serials !is Map<*, *>) continue
                    val found = when {
                        serials.containsKey(serial) -> serial
                        serials.containsKey(ALL) && title == ALL -> ALL
                        else -> continue
                    }
                    val versions = (serials[found] as? List<*>)?.mapNotNull { it as? String } ?: continue
                    val chosen = when {
                        appVersion == null -> versions
                        appVersion in versions -> listOf(appVersion)
                        ALL in versions -> listOf(ALL)
                        else -> emptyList()
                    }
                    chosen.forEach { v ->
                        out += Rpcs3PatchEntry(hash, description, title, found, v, text("Author"), text("Notes"), text("Group"))
                    }
                }
            }
        }
        return out
    }
}

/**
 * RPCS3's `patch_config.yml`: hash, patch name, title, serial, app version, then `Enabled` (and a
 * patch's `Configurable Values`), as patch_engine::save_config writes it. Fuse sets or clears
 * `Enabled` for the entries it is asked to and writes every other entry back as it was read.
 */
class Rpcs3PatchConfig(text: String?) {
    private val root: LinkedHashMap<String, Any?> = copy(text?.let { YamlTree.parse(it) } as? Map<*, *>) ?: LinkedHashMap()

    val isEmpty: Boolean get() = root.isEmpty()

    fun enabled(e: Rpcs3PatchEntry): Boolean {
        val node = path(e).fold<String, Any?>(root) { at, key -> (at as? Map<*, *>)?.get(key) }
        // A bare `true` under the version is RPCS3's older form, still read.
        val value = if (node is Map<*, *>) node["Enabled"] else node
        return (value as? String)?.trim()?.lowercase() in setOf("true", "yes", "on", "y")
    }

    /** Turns [e] on, or off by clearing what says so (and the empty levels that leaves). */
    fun set(e: Rpcs3PatchEntry, on: Boolean) {
        val keys = path(e)
        if (on) {
            var at: LinkedHashMap<String, Any?> = root
            for (key in keys) {
                @Suppress("UNCHECKED_CAST")
                val next = at[key] as? LinkedHashMap<String, Any?> ?: LinkedHashMap<String, Any?>().also { at[key] = it }
                at = next
            }
            at["Enabled"] = "true"
            return
        }
        val trail = ArrayList<LinkedHashMap<String, Any?>>()
        var at: Any? = root
        for (key in keys.dropLast(1)) {
            @Suppress("UNCHECKED_CAST")
            val map = at as? LinkedHashMap<String, Any?> ?: return
            trail += map
            at = map[key]
        }
        @Suppress("UNCHECKED_CAST")
        val parent = at as? LinkedHashMap<String, Any?> ?: return
        trail += parent
        val last = keys.last()
        when (val node = parent[last]) {
            is LinkedHashMap<*, *> -> {
                node.remove("Enabled")
                if (node.isEmpty()) parent.remove(last)
            }
            null -> return
            else -> parent.remove(last)
        }
        // Prune the levels left empty, deepest first.
        for (i in trail.indices.reversed()) {
            if (i == 0) break
            if (trail[i].isEmpty()) trail[i - 1].remove(keys[i - 1])
        }
    }

    /** The file as RPCS3 reads it: block maps, two spaces per level. */
    override fun toString(): String {
        if (root.isEmpty()) return "{}\n"
        val sb = StringBuilder()
        fun emit(map: Map<String, Any?>, depth: Int) {
            for ((k, v) in map) {
                sb.append("  ".repeat(depth)).append(scalar(k)).append(':')
                when (v) {
                    is Map<*, *> -> {
                        if (v.isEmpty()) sb.append(" {}\n") else {
                            sb.append('\n')
                            @Suppress("UNCHECKED_CAST")
                            emit(v as Map<String, Any?>, depth + 1)
                        }
                    }
                    is List<*> -> sb.append(" [").append(v.joinToString(", ") { scalar(it?.toString() ?: "") }).append("]\n")
                    null -> sb.append(" ~\n")
                    else -> sb.append(' ').append(scalar(v.toString())).append('\n')
                }
            }
        }
        emit(root, 0)
        return sb.toString()
    }

    private fun path(e: Rpcs3PatchEntry) = listOf(e.hash, e.description, e.title, e.serial, e.version)

    private companion object {
        private val PLAIN = Regex("^[A-Za-z0-9_][A-Za-z0-9_.\\-]*$")

        fun scalar(s: String): String = if (PLAIN.matches(s) && s.lowercase() !in setOf("null", "~")) s else buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append("\\x").append(c.code.toString(16).padStart(2, '0'))
                else -> append(c)
            }
            append('"')
        }

        fun copy(map: Map<*, *>?): LinkedHashMap<String, Any?>? {
            map ?: return null
            val out = LinkedHashMap<String, Any?>()
            for ((k, v) in map) {
                out[k?.toString() ?: continue] = when (v) {
                    is Map<*, *> -> copy(v)
                    is List<*> -> v.map { it?.toString() }
                    else -> v?.toString()
                }
            }
            return out
        }
    }
}
