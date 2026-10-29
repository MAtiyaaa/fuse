package io.github.matiyaaa.fuse.library.patch

/** One change a patch makes to a file: [bytes] written at [offset]. */
class FileEdit(val offset: Long, val bytes: ByteArray)

/**
 * PlayStation Patch Format (Icarus/Paradox), the patches PlayStation fan translations and fixes come
 * as: records of an offset and the bytes written there, made for a disc image of hundreds of
 * megabytes. A patch is read whole (it is small), while the image is only read where the patch
 * looks, so a patched copy can be made without holding the disc in memory.
 *
 * PPF 2.0 and 3.0 can carry 1024 bytes of the disc they were made for (from its volume descriptor),
 * and 3.0 the bytes each record replaces ("undo data"): both are checked before anything is made, so
 * a patch for another version of the game is refused rather than producing a broken copy.
 */
object Ppf {
    /** A read patch. [block] is the piece of the disc it was made for, found at [blockAt] in the image. */
    class Patch(
        val version: Int,
        val description: String,
        val records: List<Record>,
        /** The size of the image the patch was made for, where it says (PPF 2.0). */
        val imageSize: Long?,
        val block: ByteArray?,
        val blockAt: Long,
    ) {
        /** Every record says what it replaces (PPF 3.0 with undo data). */
        val hasUndo: Boolean get() = records.isNotEmpty() && records.all { it.undo != null }
    }

    /** [bytes] written at [offset], replacing [undo] where the patch says what was there. */
    class Record(val offset: Long, val bytes: ByteArray, val undo: ByteArray?)

    /** What a patch comes to on one image. */
    sealed interface Verdict {
        /** It fits: [edits] make the patched copy. [checked] when the patch confirmed this is the disc it was made for. */
        class Ready(val edits: List<FileEdit>, val checked: Boolean) : Verdict

        /** The image already is what the patch makes. */
        data object AlreadyPatched : Verdict

        /** The patch was made for another disc ([reason] says how that shows). */
        class NotForThisGame(val reason: String) : Verdict
    }

    /** A patch Fuse can't read, or an image it couldn't read; [message] says which, in words for the person. */
    class Problem(message: String) : Exception(message)

    /** Whether [patch] starts as a PPF patch does ("PPF10", "PPF20" or "PPF30"). */
    fun isPpf(patch: ByteArray): Boolean =
        patch.size >= 6 && patch[0] == P && patch[1] == P && patch[2] == F && patch[3].toInt() in '1'.code..'9'.code && patch[4] == '0'.code.toByte()

    fun parse(patch: ByteArray): Patch {
        if (!isPpf(patch)) throw Problem("This isn't a PPF patch.")
        val version = patch[3] - '0'.code.toByte()
        need(patch, HEADER)
        val description = patch.copyOfRange(6, HEADER).decodeToString().substringBefore('\u0000').trim()
        val end = recordsEnd(patch)
        var at: Int
        var offsetBytes = 4
        var undo = false
        var block: ByteArray? = null
        var blockAt = BIN_BLOCK
        var imageSize: Long? = null
        when (version) {
            1 -> at = HEADER
            2 -> {
                need(patch, HEADER + 4 + BLOCK)
                imageSize = le(patch, HEADER, 4)
                block = patch.copyOfRange(HEADER + 4, HEADER + 4 + BLOCK)
                at = HEADER + 4 + BLOCK
            }
            3 -> {
                need(patch, HEADER + 4)
                if (patch[HEADER].toInt() == 1) blockAt = GI_BLOCK
                val blockCheck = patch[HEADER + 1].toInt() == 1
                undo = patch[HEADER + 2].toInt() == 1
                at = HEADER + 4
                if (blockCheck) {
                    need(patch, at + BLOCK)
                    block = patch.copyOfRange(at, at + BLOCK)
                    at += BLOCK
                }
                offsetBytes = 8
            }
            else -> throw Problem("This is a PPF $version.0 patch, a version Fuse doesn't know.")
        }
        val records = ArrayList<Record>()
        while (at < end) {
            if (at + offsetBytes + 1 > end) throw Problem("A record of the patch is cut short.")
            val offset = le(patch, at, offsetBytes)
            val length = patch[at + offsetBytes].toInt() and 0xFF
            at += offsetBytes + 1
            val next = at + length * (if (undo) 2 else 1)
            if (next > end) throw Problem("A record of the patch is cut short.")
            if (offset < 0 || offset > MAX_OFFSET) throw Problem("The patch writes far past the end of any disc.")
            if (length > 0) {
                records += Record(offset, patch.copyOfRange(at, at + length), if (undo) patch.copyOfRange(at + length, next) else null)
            }
            at = next
        }
        if (records.isEmpty()) throw Problem("The patch has no changes in it.")
        return Patch(version, description, records, imageSize, block, blockAt)
    }

    /**
     * What [patch] comes to on an image of [size] bytes that [read] reads (up to the length asked from
     * an offset, fewer at its end, or null when it can't be read). The image is read once for the
     * patch's block and once for each stretch of records near each other, and no more once the answer
     * is known.
     *
     * @throws Problem when the image can't be read.
     */
    inline fun check(patch: Patch, size: Long, read: (offset: Long, length: Int) -> ByteArray?): Verdict {
        patch.block?.let { block ->
            val there = read(patch.blockAt, block.size) ?: throw Problem(UNREADABLE)
            if (!there.contentEquals(block)) return Verdict.NotForThisGame(blockReason(patch, size))
        }
        if (patch.records.any { it.offset + it.bytes.size > size + MAX_GROWTH }) {
            return Verdict.NotForThisGame("The patch writes far past the end of this disc: it was made for another one.")
        }
        val undo = patch.hasUndo
        var patched = true
        var original = true
        for (w in windows(patch.records)) {
            val actual = read(w.start, (w.end - w.start).toInt()) ?: throw Problem(UNREADABLE)
            val flags = compare(w, actual)
            if (flags and PATCHED == 0) patched = false
            if (flags and ORIGINAL == 0) original = false
            // A place neither as the patch found it nor as it leaves it settles it; without undo data,
            // so does the first change still to make.
            if (undo && !original && !patched) return Verdict.NotForThisGame(UNDO_REASON)
            if (!undo && !patched) break
        }
        return when {
            undo && original -> Verdict.Ready(patch.records.map { FileEdit(it.offset, it.bytes) }, checked = true)
            patched -> Verdict.AlreadyPatched
            undo -> Verdict.NotForThisGame(UNDO_REASON)
            else -> Verdict.Ready(patch.records.map { FileEdit(it.offset, it.bytes) }, checked = patch.block != null)
        }
    }

    /** A stretch of the image holding records near each other, read at once; [records] in the patch's order. */
    @PublishedApi
    internal class Window(val start: Long, val end: Long, val records: List<Record>)

    /**
     * The records gathered into stretches of at most [WINDOW] bytes, in the order they sit in the
     * image. Records that overlap stay in one stretch, so each byte's last write is known there.
     */
    @PublishedApi
    internal fun windows(records: List<Record>): List<Window> {
        val sorted = records.withIndex().sortedBy { it.value.offset }
        val out = ArrayList<Window>()
        var group = ArrayList<IndexedValue<Record>>()
        var start = 0L
        var end = 0L
        for (r in sorted) {
            val rEnd = r.value.offset + r.value.bytes.size
            if (group.isNotEmpty() && r.value.offset >= end && rEnd - start > WINDOW) {
                out += Window(start, end, group.sortedBy { it.index }.map { it.value })
                group = ArrayList()
            }
            if (group.isEmpty()) {
                start = r.value.offset
                end = rEnd
            }
            group += r
            end = maxOf(end, rEnd)
        }
        if (group.isNotEmpty()) out += Window(start, end, group.sortedBy { it.index }.map { it.value })
        return out
    }

    /**
     * Whether the image's bytes in [w] ([actual], fewer at its end) already are what the patch makes
     * there ([PATCHED]), and whether they are what its undo data says they were ([ORIGINAL]).
     */
    @PublishedApi
    internal fun compare(w: Window, actual: ByteArray): Int {
        val length = (w.end - w.start).toInt()
        val now = actual.copyOf(length)
        var flags = 0
        val made = now.copyOf()
        for (r in w.records) r.bytes.copyInto(made, (r.offset - w.start).toInt())
        if (actual.size == length && made.contentEquals(now)) flags = flags or PATCHED
        if (w.records.all { it.undo != null }) {
            // Put back in reverse, so each byte ends as the first record touching it found it.
            val before = now.copyOf()
            for (r in w.records.asReversed()) r.undo!!.copyInto(before, (r.offset - w.start).toInt())
            if (before.contentEquals(now)) flags = flags or ORIGINAL
        }
        return flags
    }

    @PublishedApi
    internal fun blockReason(patch: Patch, size: Long): String {
        val sized = patch.imageSize?.takeIf { it > 0 && it != size }
        return "The patch carries a piece of the disc it was made for, and this disc's doesn't match: it was made for another " +
            "version (a region or revision)" + (if (sized != null) ", a disc image of ${grouped(sized)} bytes where this is ${grouped(size)}." else ".")
    }

    /** Where a PPF patch's records end: before the file_id.diz some carry at their end. */
    private fun recordsEnd(patch: ByteArray): Int {
        // The text, then its length in 2 bytes (PPF 3.0) or 4 (PPF 2.0).
        for (lengthBytes in intArrayOf(2, 4)) {
            val mark = patch.size - lengthBytes - END_DIZ.size
            if (mark < HEADER || !patch.has(END_DIZ, mark)) continue
            var at = mark - BEGIN_DIZ.size
            while (at >= HEADER) {
                if (patch.has(BEGIN_DIZ, at)) return at
                at--
            }
            throw Problem("The description at the end of the patch is damaged.")
        }
        return patch.size
    }

    private fun ByteArray.has(tag: ByteArray, at: Int) = at >= 0 && at + tag.size <= size && tag.indices.all { this[at + it] == tag[it] }

    private fun need(patch: ByteArray, size: Int) {
        if (patch.size < size) throw Problem("The patch is cut short.")
    }

    private fun le(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    /** [n] with its digits grouped by thousands. */
    private fun grouped(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

    private const val P = 'P'.code.toByte()
    private const val F = 'F'.code.toByte()
    private val BEGIN_DIZ = "@BEGIN_FILE_ID.DIZ".encodeToByteArray()
    private val END_DIZ = "@END_FILE_ID.DIZ".encodeToByteArray()

    /** The magic, the encoding byte and a 50-byte description. */
    private const val HEADER = 56
    private const val BLOCK = 1024

    /** Where the block is in a .bin of raw 2352-byte sectors, and in a PrimoDVD .gi image. */
    private const val BIN_BLOCK = 0x9320L
    private const val GI_BLOCK = 0x80A0L

    /** Past the largest disc (a dual-layer DVD), any offset is damage. */
    private const val MAX_OFFSET = 16L * 1024 * 1024 * 1024

    @PublishedApi internal const val WINDOW = 1L shl 20

    /** How far past an image's end a patch may grow it before it is plainly for another disc. */
    @PublishedApi internal const val MAX_GROWTH = 64L * 1024 * 1024

    @PublishedApi internal const val PATCHED = 1

    @PublishedApi internal const val ORIGINAL = 2

    @PublishedApi internal const val UNREADABLE = "Fuse couldn't read the game."

    @PublishedApi internal const val UNDO_REASON =
        "The patch says what each place it changes held on the disc it was made for, and this disc holds something else: it was made for another version (a region or revision)."
}
