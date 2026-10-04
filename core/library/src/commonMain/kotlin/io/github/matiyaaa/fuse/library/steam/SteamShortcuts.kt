package io.github.matiyaaa.fuse.library.steam

/**
 * Steam's binary KeyValues, the format of `userdata/<user>/config/shortcuts.vdf`, where Steam keeps
 * its non-Steam games. A value is a string, a 32-bit number or a nested block; every block keeps its
 * entries in order, so a file read and written again is byte for byte the same.
 */
object BinaryVdf {
    sealed interface Value
    data class Str(val value: String) : Value
    data class Int32(val value: Int) : Value
    data class Block(val entries: MutableList<Pair<String, Value>> = mutableListOf()) : Value {
        operator fun get(key: String): Value? = entries.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second
        operator fun set(key: String, value: Value) {
            val i = entries.indexOfFirst { it.first.equals(key, ignoreCase = true) }
            if (i >= 0) entries[i] = entries[i].first to value else entries += key to value
        }
    }

    private const val BLOCK: Byte = 0
    private const val STRING: Byte = 1
    private const val INT: Byte = 2
    private const val END: Byte = 8

    /** The document, or null when it isn't binary KeyValues. An empty input is an empty document. */
    fun parse(bytes: ByteArray): Block? {
        var i = 0
        fun cstring(): String {
            val start = i
            while (i < bytes.size && bytes[i] != 0.toByte()) i++
            val s = bytes.copyOfRange(start, i).decodeToString()
            i++
            return s
        }
        fun block(): Block {
            val out = Block()
            while (i < bytes.size) {
                val type = bytes[i++]
                if (type == END) return out
                val key = cstring()
                when (type) {
                    BLOCK -> out.entries += key to block()
                    STRING -> out.entries += key to Str(cstring())
                    INT -> {
                        if (i + 4 > bytes.size) throw IllegalArgumentException("truncated")
                        val v = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt() and 0xFF) shl 24)
                        i += 4
                        out.entries += key to Int32(v)
                    }
                    else -> throw IllegalArgumentException("type $type")
                }
            }
            return out
        }
        return try {
            block()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun write(root: Block): ByteArray {
        val out = ArrayList<Byte>()
        fun cstring(s: String) {
            out.addAll(s.encodeToByteArray().toList())
            out += 0
        }
        fun block(b: Block) {
            for ((key, value) in b.entries) {
                when (value) {
                    is Block -> { out += BLOCK; cstring(key); block(value); out += END }
                    is Str -> { out += STRING; cstring(key); cstring(value.value) }
                    is Int32 -> {
                        out += INT
                        cstring(key)
                        val v = value.value
                        out += (v and 0xFF).toByte()
                        out += (v shr 8 and 0xFF).toByte()
                        out += (v shr 16 and 0xFF).toByte()
                        out += (v shr 24 and 0xFF).toByte()
                    }
                }
            }
        }
        block(root)
        out += END
        return out.toByteArray()
    }
}

/** A non-Steam game for Steam's library: what it is called, what runs, and how. */
data class SteamShortcut(
    val name: String,
    /** The program, quoted as Steam quotes it. */
    val exe: String,
    val startDir: String,
    val icon: String = "",
    val launchOptions: String = "",
    val tags: List<String> = emptyList(),
)

/** Adds a non-Steam game to `shortcuts.vdf`, or updates the one already there for the same program. */
object SteamShortcuts {
    /**
     * Steam's id for a non-Steam game: the CRC-32 of its quoted program and its name, with the top
     * bit set (the number Steam itself shows and uses for its art folders).
     */
    fun appId(exe: String, name: String): Int = (crc32((exe + name).encodeToByteArray()) or 0x80000000L).toInt()

    /** [existing] (the file's bytes, or null for a new file) with [shortcut] in it; null when the file can't be read. */
    fun add(existing: ByteArray?, shortcut: SteamShortcut): ByteArray? {
        val root = if (existing == null || existing.isEmpty()) BinaryVdf.Block() else BinaryVdf.parse(existing) ?: return null
        val list = root["shortcuts"] as? BinaryVdf.Block ?: BinaryVdf.Block().also { root["shortcuts"] = it }
        val same = list.entries.map { it.second }.filterIsInstance<BinaryVdf.Block>().firstOrNull { e ->
            (e["Exe"] as? BinaryVdf.Str)?.value == shortcut.exe || (e["AppName"] as? BinaryVdf.Str)?.value == shortcut.name
        }
        val entry = same ?: BinaryVdf.Block().also { b ->
            val next = (list.entries.mapNotNull { it.first.toIntOrNull() }.maxOrNull() ?: -1) + 1
            list.entries += next.toString() to b
        }
        entry["appid"] = BinaryVdf.Int32(appId(shortcut.exe, shortcut.name))
        entry["AppName"] = BinaryVdf.Str(shortcut.name)
        entry["Exe"] = BinaryVdf.Str(shortcut.exe)
        entry["StartDir"] = BinaryVdf.Str(shortcut.startDir)
        entry["icon"] = BinaryVdf.Str(shortcut.icon)
        entry["ShortcutPath"] = entry["ShortcutPath"] ?: BinaryVdf.Str("")
        entry["LaunchOptions"] = BinaryVdf.Str(shortcut.launchOptions)
        entry["IsHidden"] = entry["IsHidden"] ?: BinaryVdf.Int32(0)
        entry["AllowDesktopConfig"] = entry["AllowDesktopConfig"] ?: BinaryVdf.Int32(1)
        entry["AllowOverlay"] = entry["AllowOverlay"] ?: BinaryVdf.Int32(1)
        entry["OpenVR"] = entry["OpenVR"] ?: BinaryVdf.Int32(0)
        entry["Devkit"] = entry["Devkit"] ?: BinaryVdf.Int32(0)
        entry["DevkitGameID"] = entry["DevkitGameID"] ?: BinaryVdf.Str("")
        entry["DevkitOverrideAppID"] = entry["DevkitOverrideAppID"] ?: BinaryVdf.Int32(0)
        entry["LastPlayTime"] = entry["LastPlayTime"] ?: BinaryVdf.Int32(0)
        entry["FlatpakAppID"] = entry["FlatpakAppID"] ?: BinaryVdf.Str("")
        val tags = BinaryVdf.Block()
        shortcut.tags.forEachIndexed { i, t -> tags.entries += i.toString() to BinaryVdf.Str(t) }
        entry["tags"] = tags
        return BinaryVdf.write(root)
    }

    /** Whether [bytes] already hold a shortcut that runs [exe]. */
    fun contains(bytes: ByteArray?, exe: String): Boolean {
        val root = bytes?.let(BinaryVdf::parse) ?: return false
        val list = root["shortcuts"] as? BinaryVdf.Block ?: return false
        return list.entries.any { (_, v) -> ((v as? BinaryVdf.Block)?.get("Exe") as? BinaryVdf.Str)?.value == exe }
    }

    private val table = LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) { c = if (c and 1L != 0L) 0xEDB88320L xor (c ushr 1) else c ushr 1 }
        c
    }

    private fun crc32(bytes: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (b in bytes) crc = table[((crc xor b.toLong()) and 0xFF).toInt()] xor (crc ushr 8)
        return crc xor 0xFFFFFFFFL
    }
}
