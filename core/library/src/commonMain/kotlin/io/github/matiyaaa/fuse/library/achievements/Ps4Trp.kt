package io.github.matiyaaa.fuse.library.achievements

/**
 * A PS4 game's trophy file (`sce_sys/trophy/trophy00.trp`), read as shadPS4 v.0.20.0 reads it
 * (`src/core/file_format/trp.h` and `trp.cpp`): a big-endian header of 96 bytes (magic `DCA24D00`,
 * version, size, then the entries' count and size), entries of 64 bytes (a name of 32, offset,
 * length and a flag: 0 for a PNG, 3 for an encrypted configuration), and the configuration
 * `TROP.ESFM` decrypted with AES-128-CBC: its first 16 bytes are the IV, and its key is the set's id
 * (NPWR, padded to 16 bytes) encrypted with AES-128-CBC under the person's own trophy key and a zero
 * IV (`DecryptEFSM`). What follows the last `>` is padding. Pure: bytes in, text out.
 */
object Ps4Trp {
    const val HEADER = 96
    private const val MAGIC = 0xDCA24D00L
    private const val ENTRY = 64
    private const val ENCRYPTED = 3L
    const val CONF = "TROP.ESFM"

    /** One entry: its name, where its bytes are, how many, and what it is. */
    data class Entry(val name: String, val offset: Long, val length: Long, val flag: Long)

    /** The entries the file's first bytes ([header], then its table) list, or null when it isn't a trophy file. */
    fun entryCount(header: ByteArray): Pair<Int, Int>? {
        if (header.size < HEADER || u32(header, 0) != MAGIC) return null
        val count = u32(header, 16).toInt()
        val size = u32(header, 20).toInt()
        if (count !in 1..MAX_ENTRIES || size < ENTRY || size > 1024) return null
        return count to size
    }

    fun entries(table: ByteArray, count: Int, size: Int): List<Entry> = (0 until count).mapNotNull { i ->
        val at = i * size
        if (at + ENTRY > table.size) return@mapNotNull null
        val name = table.copyOfRange(at, at + 32).takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
        Entry(name, u64(table, at + 32), u64(table, at + 40), u32(table, at + 48))
    }

    /** The configuration's entry among [entries], when it is there and encrypted. */
    fun conf(entries: List<Entry>): Entry? = entries.firstOrNull { it.name == CONF && it.flag == ENCRYPTED && it.length > 16 && (it.length - 16) % 16 == 0L }

    /**
     * TROP.XML from the configuration's bytes ([esfm]: IV, then the encrypted text), with the
     * person's trophy [key] (16 bytes) for the set [npCommId]; null when it doesn't decrypt to XML.
     */
    fun decrypt(esfm: ByteArray, key: ByteArray, npCommId: String): String? {
        if (key.size != 16 || esfm.size <= 16 || (esfm.size - 16) % 16 != 0) return null
        val id = npCommId.encodeToByteArray().copyOf(16)
        val trpKey = TrophyAes.cbc(key, ByteArray(16), id, encrypt = true) ?: return null
        val plain = TrophyAes.cbc(trpKey, esfm.copyOfRange(0, 16), esfm.copyOfRange(16, esfm.size), encrypt = false) ?: return null
        val end = plain.indexOfLast { it == '>'.code.toByte() }
        if (end < 0) return null
        val text = plain.copyOfRange(0, end + 1).decodeToString()
        // A wrong key decrypts to noise: only a trophy configuration counts.
        return text.takeIf { "<trophyconf" in it }
    }

    /** A 16-byte key written as 32 hexadecimal digits, or null. */
    fun key(hex: String?): ByteArray? {
        val h = hex?.trim()?.takeIf { it.length == 32 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } } ?: return null
        return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun u32(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { v, i -> (v shl 8) or (b[at + i].toLong() and 0xFF) }
    private fun u64(b: ByteArray, at: Int): Long = (0 until 8).fold(0L) { v, i -> (v shl 8) or (b[at + i].toLong() and 0xFF) }

    private const val MAX_ENTRIES = 4096
}

/** AES-128 in CBC mode without padding, both ways; null when it can't be done here. */
internal expect object TrophyAes {
    fun cbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray?
}
