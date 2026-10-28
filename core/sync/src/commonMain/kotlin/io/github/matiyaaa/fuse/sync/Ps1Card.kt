package io.github.matiyaaa.fuse.sync

/**
 * A PlayStation memory card, as the console lays it out and every emulator keeps it (DuckStation's
 * `.mcd`, RetroArch's 128 KB `.srm`): sixteen blocks of 8 KB. Block 0 is the directory, a frame of
 * 128 bytes for each of the other fifteen saying whether it is free or which save it holds (and the
 * next block of a save longer than one); the rest hold the saves themselves.
 *
 * Saves people keep outside a card are read too: one save on its own (`.mcs` from MemcardRex and
 * PSXGameEdit, `.psv` from a PS3, `.psx`/`.mcb`/`.pda` from an Action Replay or GameShark), and whole
 * cards in a wrapper (`.gme` from a DexDrive, `.vgs`/`.mem` from Connectix VGS). Layouts follow
 * the formats as MemcardRex documents and writes them (ps1card.cs).
 */
object Ps1Card {
    /** The format of a PlayStation save on its own, as an import reads it: it goes onto a card. */
    const val SINGLE = "psx.single"

    /** The formats of a card a save on its own can go onto: a game's own card, or one shared. */
    val CARDS = setOf("psx.mcd", "psx.sharedcard")

    const val SIZE = 128 * 1024
    const val BLOCK = 8 * 1024
    const val FRAME = 128
    private const val BLOCKS = 15

    private const val FIRST: Int = 0x51
    private const val MIDDLE: Int = 0x52
    private const val LAST: Int = 0x53
    private const val FREE: Int = 0xA0

    /** One save: its name on the card (`BASLUS-00594MGS`) and its blocks, 8 KB each. */
    class Save(val name: String, val data: ByteArray) {
        val blocks: Int get() = data.size / BLOCK
    }

    /** A card with nothing on it, formatted as the console formats one. */
    fun blank(): ByteArray {
        val card = ByteArray(SIZE)
        card[0] = 'M'.code.toByte()
        card[1] = 'C'.code.toByte()
        checksum(card, 0)
        for (i in 1..BLOCKS) {
            val at = i * FRAME
            card[at] = FREE.toByte()
            card[at + 8] = 0xFF.toByte()
            card[at + 9] = 0xFF.toByte()
            checksum(card, at)
        }
        // The broken sector list: none broken.
        for (i in 16..35) {
            val at = i * FRAME
            for (b in 0..3) card[at + b] = 0xFF.toByte()
            checksum(card, at)
        }
        // The write test frame is a copy of the first.
        card.copyInto(card, 63 * FRAME, 0, FRAME)
        return card
    }

    /** Whether [card] is a formatted card: the right size, with "MC" at its start. */
    fun isCard(card: ByteArray): Boolean = card.size == SIZE && card[0] == 'M'.code.toByte() && card[1] == 'C'.code.toByte()

    /**
     * The card inside a whole card's file named [name]: as it is (raw), after a DexDrive's header
     * (`.gme`, 3904 bytes) or a Connectix VGS one (`.vgs`, `.mem`, 64 bytes). Null when it isn't a card.
     */
    fun cardIn(name: String, bytes: ByteArray): ByteArray? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val header = when (ext) {
            "gme" -> 3904
            "vgs", "mem" -> 64
            else -> 0
        }
        if (bytes.size < header + SIZE) return null
        return bytes.copyOfRange(header, header + SIZE).takeIf(::isCard)
    }

    /**
     * The save in a single save's file, told by what it holds rather than its name (an import may
     * have renamed it): a PS3's `.psv` (`\0VSP`, a header to 0x84, its name at 0x64), MemcardRex's
     * `.mcs` (its directory frame, then its blocks) or an Action Replay's `.psx`/`.mcb`/`.pda` (54
     * bytes, its name first). Every save's first block starts with its title frame, "SC". Null when
     * it is none of them.
     */
    fun saveIn(bytes: ByteArray): Save? {
        fun titled(at: Int) = bytes.size > at + 1 && bytes[at] == 'S'.code.toByte() && bytes[at + 1] == 'C'.code.toByte()
        val (header, saveName) = when {
            bytes.size > 4 && bytes.decodeToString(1, 4) == "VSP" && titled(0x84) -> 0x84 to text(bytes, 0x64, 20)
            (bytes.getOrNull(0)?.toInt()?.and(0xFF)) == FIRST && titled(FRAME) -> FRAME to text(bytes, 10, 20)
            titled(54) -> 54 to text(bytes, 0, 20)
            else -> return null
        }
        val data = bytes.copyOfRange(header, bytes.size)
        if (data.size % BLOCK != 0 || data.size / BLOCK !in 1..BLOCKS || saveName.isBlank()) return null
        return Save(saveName, data)
    }

    /** A single save's own file: its directory frame, then its blocks, as MemcardRex writes `.mcs`. */
    fun mcs(save: Save): ByteArray {
        val out = ByteArray(FRAME + save.data.size)
        out[0] = FIRST.toByte()
        le32(out, 4, save.data.size)
        out[8] = 0xFF.toByte()
        out[9] = 0xFF.toByte()
        save.name.encodeToByteArray().take(20).forEachIndexed { k, c -> out[10 + k] = c }
        checksum(out, 0)
        save.data.copyInto(out, FRAME)
        return out
    }

    /** The saves on [card], each with all its blocks. */
    fun saves(card: ByteArray): List<Save> {
        if (!isCard(card)) return emptyList()
        val out = ArrayList<Save>()
        for (i in 1..BLOCKS) {
            val at = i * FRAME
            if ((card[at].toInt() and 0xFF) != FIRST) continue
            val blocks = chain(card, i) ?: continue
            val data = ByteArray(blocks.size * BLOCK)
            blocks.forEachIndexed { n, b -> card.copyInto(data, n * BLOCK, b * BLOCK, (b + 1) * BLOCK) }
            out += Save(text(card, at + 10, 20), data)
        }
        return out
    }

    /**
     * [card] with [save] on it: in place of a save of the same name, else in free blocks. Null when
     * there aren't enough free blocks. The card given is left as it was.
     */
    fun write(card: ByteArray, save: Save): ByteArray? {
        if (!isCard(card) || save.blocks !in 1..BLOCKS) return null
        val out = card.copyOf()
        // A save of the same name goes first, so its blocks are free for the new one.
        for (i in 1..BLOCKS) {
            val at = i * FRAME
            if ((out[at].toInt() and 0xFF) == FIRST && text(out, at + 10, 20) == save.name) chain(out, i)?.forEach { free(out, it) }
        }
        val free = (1..BLOCKS).filter { b -> (out[b * FRAME].toInt() and 0xFF) == FREE || (out[b * FRAME].toInt() and 0xF0) == 0xA0 }
        if (free.size < save.blocks) return null
        val used = free.take(save.blocks)
        used.forEachIndexed { n, b ->
            val at = b * FRAME
            out.fill(0, at, at + FRAME)
            out[at] = (if (n == 0) FIRST else if (n == used.lastIndex) LAST else MIDDLE).toByte()
            if (n == 0) le32(out, at + 4, save.blocks * BLOCK)
            val next = used.getOrNull(n + 1)?.let { it - 1 } ?: 0xFFFF
            out[at + 8] = next.toByte()
            out[at + 9] = (next ushr 8).toByte()
            if (n == 0) save.name.encodeToByteArray().take(20).forEachIndexed { k, c -> out[at + 10 + k] = c }
            checksum(out, at)
            save.data.copyInto(out, b * BLOCK, n * BLOCK, (n + 1) * BLOCK)
        }
        return out
    }

    /** The blocks of the save starting at [first], in order; null when the directory's links are broken. */
    private fun chain(card: ByteArray, first: Int): List<Int>? {
        val out = ArrayList<Int>()
        var b = first
        while (true) {
            if (b !in 1..BLOCKS || b in out) return null
            out += b
            val at = b * FRAME
            val next = (card[at + 8].toInt() and 0xFF) or ((card[at + 9].toInt() and 0xFF) shl 8)
            if (next == 0xFFFF) return out
            b = next + 1
        }
    }

    private fun free(card: ByteArray, block: Int) {
        val at = block * FRAME
        card.fill(0, at, at + FRAME)
        card[at] = FREE.toByte()
        card[at + 8] = 0xFF.toByte()
        card[at + 9] = 0xFF.toByte()
        checksum(card, at)
    }

    private fun checksum(card: ByteArray, frame: Int) {
        var x = 0
        for (i in 0 until FRAME - 1) x = x xor (card[frame + i].toInt() and 0xFF)
        card[frame + FRAME - 1] = x.toByte()
    }

    private fun le32(b: ByteArray, at: Int, v: Int) {
        for (i in 0..3) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun text(b: ByteArray, at: Int, max: Int): String {
        val end = (at until minOf(at + max, b.size)).firstOrNull { b[it] == 0.toByte() } ?: minOf(at + max, b.size)
        return b.decodeToString(at, end).trim()
    }
}
