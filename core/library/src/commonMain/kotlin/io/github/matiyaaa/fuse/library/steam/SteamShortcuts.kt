package io.github.matiyaaa.fuse.library.steam

/**
 * Steam's binary KeyValues, the format of `userdata/<user>/config/shortcuts.vdf`, where Steam keeps
 * its non-Steam games. A value is a string, a 32-bit number or a nested block; every block keeps its
 * entries in order, so a file read and written again is byte for byte the same.
 */
object BinaryVdf {
    sealed interface Value
    data class Str(val value: String) : Value
    data class Int32(val value: Int, val wireType: Byte = 2) : Value
    data class Float32(val value: Float) : Value
    data class Int64(val value: Long, val wireType: Byte = 7) : Value
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
    private const val FLOAT: Byte = 3
    private const val POINTER: Byte = 4
    private const val COLOR: Byte = 6
    private const val UINT64: Byte = 7
    private const val END: Byte = 8
    private const val INT64: Byte = 10

    /** The document, or null when it isn't binary KeyValues. An empty input is rejected; callers explicitly handle a new file. */
    fun parse(bytes: ByteArray): Block? {
        var i = 0
        fun cstring(): String {
            val start = i
            while (i < bytes.size && bytes[i] != 0.toByte()) i++
            require(i < bytes.size) { "unterminated string" }
            val s = bytes.copyOfRange(start, i).decodeToString(throwOnInvalidSequence = true)
            i++
            return s
        }
        fun int32(): Int {
            if (i + 4 > bytes.size) throw IllegalArgumentException("truncated")
            val v = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt() and 0xFF) shl 24)
            i += 4
            return v
        }
        fun block(depth: Int = 0): Block {
            require(depth <= 64) { "nested too deeply" }
            val out = Block()
            while (i < bytes.size) {
                val type = bytes[i++]
                if (type == END) return out
                val key = cstring()
                when (type) {
                    BLOCK -> out.entries += key to block(depth + 1)
                    STRING -> out.entries += key to Str(cstring())
                    INT, POINTER, COLOR -> out.entries += key to Int32(int32(), type)
                    FLOAT -> out.entries += key to Float32(Float.fromBits(int32()))
                    UINT64, INT64 -> {
                        val low = int32().toLong() and 0xFFFFFFFFL
                        val high = int32().toLong()
                        out.entries += key to Int64(low or (high shl 32), type)
                    }
                    else -> throw IllegalArgumentException("type $type")
                }
            }
            throw IllegalArgumentException("unterminated block")
        }
        return try {
            block().also { require(i == bytes.size) { "trailing bytes" } }
        } catch (e: Exception) {
            null
        }
    }

    fun write(root: Block): ByteArray {
        val out = ArrayList<Byte>()
        fun cstring(s: String) {
            out.addAll(s.encodeToByteArray().toList())
            out += 0
        }
        fun int32(v: Int) {
            out += (v and 0xFF).toByte()
            out += (v shr 8 and 0xFF).toByte()
            out += (v shr 16 and 0xFF).toByte()
            out += (v shr 24 and 0xFF).toByte()
        }
        fun block(b: Block) {
            for ((key, value) in b.entries) {
                when (value) {
                    is Block -> { out += BLOCK; cstring(key); block(value); out += END }
                    is Str -> { out += STRING; cstring(key); cstring(value.value) }
                    is Int32 -> { out += value.wireType; cstring(key); int32(value.value) }
                    is Float32 -> { out += FLOAT; cstring(key); int32(value.value.toRawBits()) }
                    is Int64 -> { out += value.wireType; cstring(key); int32(value.value.toInt()); int32((value.value ushr 32).toInt()) }
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

    /**
     * [existing] (the file's bytes, or null for a new file) with [shortcut] in it; null when the file
     * can't be read. Idempotent: an entry already there for the same program, or one [owned] says is
     * Fuse's (its program moved, a new AppImage), is brought up to date in place rather than added
     * again. What the person changed on it stays theirs: its name, launch options, icon and the
     * Steam collections it is in. Other entries are never touched.
     */
    fun add(existing: ByteArray?, shortcut: SteamShortcut, owned: ((exe: String, name: String) -> Boolean)? = null): ByteArray? {
        val root = if (existing == null || existing.isEmpty()) BinaryVdf.Block() else BinaryVdf.parse(existing) ?: return null
        val value = root["shortcuts"]
        if (value != null && value !is BinaryVdf.Block) return null
        val list = value as? BinaryVdf.Block ?: BinaryVdf.Block().also { root["shortcuts"] = it }
        val blocks = list.entries.map { it.second }.filterIsInstance<BinaryVdf.Block>()
        val same = blocks.firstOrNull { e -> (e["Exe"] as? BinaryVdf.Str)?.value == shortcut.exe }
            ?: owned?.let { o ->
                blocks.firstOrNull { e ->
                    o((e["Exe"] as? BinaryVdf.Str)?.value.orEmpty().trim('"'), (e["AppName"] as? BinaryVdf.Str)?.value.orEmpty())
                }
            }
        val fresh = same == null
        val entry = same ?: BinaryVdf.Block().also { b ->
            val next = (list.entries.mapNotNull { it.first.toIntOrNull() }.maxOrNull() ?: -1) + 1
            list.entries += next.toString() to b
        }
        fun keep(key: String, value: String) {
            val now = (entry[key] as? BinaryVdf.Str)?.value
            if (fresh || now.isNullOrEmpty()) entry[key] = BinaryVdf.Str(value)
        }
        entry["appid"] = entry["appid"] ?: BinaryVdf.Int32(appId(shortcut.exe, shortcut.name))
        keep("AppName", shortcut.name)
        entry["Exe"] = BinaryVdf.Str(shortcut.exe)
        entry["StartDir"] = BinaryVdf.Str(shortcut.startDir)
        keep("icon", shortcut.icon)
        entry["ShortcutPath"] = entry["ShortcutPath"] ?: BinaryVdf.Str("")
        if (fresh || entry["LaunchOptions"] == null) entry["LaunchOptions"] = BinaryVdf.Str(shortcut.launchOptions)
        entry["IsHidden"] = entry["IsHidden"] ?: BinaryVdf.Int32(0)
        entry["AllowDesktopConfig"] = entry["AllowDesktopConfig"] ?: BinaryVdf.Int32(1)
        entry["AllowOverlay"] = entry["AllowOverlay"] ?: BinaryVdf.Int32(1)
        entry["OpenVR"] = entry["OpenVR"] ?: BinaryVdf.Int32(0)
        entry["Devkit"] = entry["Devkit"] ?: BinaryVdf.Int32(0)
        entry["DevkitGameID"] = entry["DevkitGameID"] ?: BinaryVdf.Str("")
        entry["DevkitOverrideAppID"] = entry["DevkitOverrideAppID"] ?: BinaryVdf.Int32(0)
        entry["LastPlayTime"] = entry["LastPlayTime"] ?: BinaryVdf.Int32(0)
        entry["FlatpakAppID"] = entry["FlatpakAppID"] ?: BinaryVdf.Str("")
        // The person's collections stay; Fuse's own tags join them once.
        val had = (entry["tags"] as? BinaryVdf.Block)?.entries?.mapNotNull { (it.second as? BinaryVdf.Str)?.value }.orEmpty()
        val tags = BinaryVdf.Block()
        (had + shortcut.tags.filter { it !in had }).forEachIndexed { i, t -> tags.entries += i.toString() to BinaryVdf.Str(t) }
        entry["tags"] = tags
        return BinaryVdf.write(root)
    }

    /**
     * [existing] without the entries [owned] picks (by program, quotes taken off, and name), the rest
     * numbered again in order as Steam numbers them. Null when the file can't be read; the same bytes
     * when nothing was Fuse's.
     */
    fun remove(existing: ByteArray?, owned: (exe: String, name: String) -> Boolean): ByteArray? {
        if (existing == null || existing.isEmpty()) return existing
        val root = BinaryVdf.parse(existing) ?: return null
        val list = root["shortcuts"] as? BinaryVdf.Block ?: return existing
        val kept = list.entries.filterNot { (_, v) ->
            val e = v as? BinaryVdf.Block ?: return@filterNot false
            owned((e["Exe"] as? BinaryVdf.Str)?.value.orEmpty().trim('"'), (e["AppName"] as? BinaryVdf.Str)?.value.orEmpty())
        }
        if (kept.size == list.entries.size) return existing
        list.entries.clear()
        kept.forEachIndexed { i, (_, v) -> list.entries += i.toString() to v }
        return BinaryVdf.write(root)
    }

    /** Rebuild only entries positively owned by Fuse. A name match alone never establishes ownership. */
    fun rebuild(existing: ByteArray?, shortcut: SteamShortcut, owned: (String, String) -> Boolean): ByteArray? {
        val root = if (existing == null || existing.isEmpty()) BinaryVdf.Block() else BinaryVdf.parse(existing) ?: return null
        val value = root["shortcuts"]
        if (value != null && value !is BinaryVdf.Block) return null
        val list = value as? BinaryVdf.Block ?: BinaryVdf.Block().also { root["shortcuts"] = it }
        var retained: BinaryVdf.Block? = null
        list.entries.removeAll { (_, value) ->
            val e = value as? BinaryVdf.Block ?: return@removeAll false
            val exe = (e["Exe"] as? BinaryVdf.Str)?.value.orEmpty().trim('"')
            val name = (e["AppName"] as? BinaryVdf.Str)?.value.orEmpty()
            if (!owned(exe, name)) false else {
                if (retained == null) retained = e
                true
            }
        }
        retained?.let { e ->
            e["Exe"] = BinaryVdf.Str(shortcut.exe)
            val index = (list.entries.mapNotNull { it.first.toIntOrNull() }.maxOrNull() ?: -1) + 1
            list.entries += index.toString() to e
        }
        return add(BinaryVdf.write(root), shortcut)
    }

    /**
     * The ids (as Steam names art files: unsigned) of the shortcuts in [bytes] that [match] picks by
     * their program (quotes taken off) and name. A shortcut keeps the id it was given; one without
     * gets the id Steam works out for it.
     */
    fun idsOf(bytes: ByteArray?, match: (exe: String, name: String) -> Boolean): List<Long> {
        val root = bytes?.let(BinaryVdf::parse) ?: return emptyList()
        val list = root["shortcuts"] as? BinaryVdf.Block ?: return emptyList()
        return list.entries.mapNotNull { (_, v) ->
            val e = v as? BinaryVdf.Block ?: return@mapNotNull null
            val exe = (e["Exe"] as? BinaryVdf.Str)?.value ?: return@mapNotNull null
            val name = (e["AppName"] as? BinaryVdf.Str)?.value.orEmpty()
            if (!match(exe.trim('"'), name)) return@mapNotNull null
            val id = (e["appid"] as? BinaryVdf.Int32)?.value ?: appId(exe, name)
            id.toLong() and 0xFFFFFFFFL
        }.distinct()
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
