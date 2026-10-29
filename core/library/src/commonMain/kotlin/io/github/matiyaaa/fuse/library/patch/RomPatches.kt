package io.github.matiyaaa.fuse.library.patch

/** The patch formats Fuse applies: each read the way its own specification lays it out. */
enum class PatchFormat(val label: String) {
    /** International Patching System: records of offset and bytes, with no record of the game it fits. */
    IPS("IPS"),

    /** Universal Patching System (byuu): XOR runs, with the game's and the result's CRC32. */
    UPS("UPS"),

    /** Beat Patching System (byuu): copy commands, with the game's and the result's CRC32. */
    BPS("BPS"),

    /** VCDIFF (RFC 3284) as xdelta3 writes it: copies and adds, with an Adler-32 of each part of the result. */
    XDELTA("xdelta"),

    /** PlayStation Patch Format ([Ppf]): records of offset and bytes, some with a piece of the disc and what each record replaces. */
    PPF("PPF"),
}

/** What applying a patch to a game came to. */
sealed interface PatchOutcome {
    /**
     * The patched game, [bytes]. [checked] when the patch says which game it is for and this was it
     * (UPS and BPS); IPS patches carry no such record, so theirs is never checked. [headerRemoved]
     * when the game had a copier header (512 bytes some SNES dumps start with) that the patch
     * expected gone: the result is without it.
     */
    class Patched(val bytes: ByteArray, val format: PatchFormat, val checked: Boolean, val headerRemoved: Boolean = false) : PatchOutcome

    /** The patch is for another version of the game (or another game): its CRC32 isn't this one's. */
    data class WrongGame(val format: PatchFormat, val expected: Long, val actual: Long) : PatchOutcome

    /** The game already is what the patch makes. */
    data class AlreadyPatched(val format: PatchFormat) : PatchOutcome

    /** The patch isn't one Fuse can read, or is damaged ([reason] says how). */
    data class Unreadable(val reason: String) : PatchOutcome

    /** The patch's own check of what it makes failed on this game: it was made for another version ([reason] says so). */
    data class NotForThisGame(val format: PatchFormat, val reason: String) : PatchOutcome
}

/**
 * Applies IPS, UPS, BPS, xdelta and PPF patches to a game's bytes, never to its file: the original stays as it
 * is, and the result is the caller's to keep as a copy. UPS and BPS are checked against the CRC32
 * of the game they were made for and of the result they make, and their own; a patch for another
 * version of the game is refused rather than producing a broken copy.
 */
object RomPatches {
    /** The patch's format by its first bytes, or null. */
    fun formatOf(patch: ByteArray): PatchFormat? = when {
        patch.startsWith("PATCH") -> PatchFormat.IPS
        patch.startsWith("UPS1") -> PatchFormat.UPS
        patch.startsWith("BPS1") -> PatchFormat.BPS
        Vcdiff.isVcdiff(patch) -> PatchFormat.XDELTA
        Ppf.isPpf(patch) -> PatchFormat.PPF
        else -> null
    }

    /** File extensions of patches Fuse applies. */
    val EXTENSIONS = setOf("ips", "ups", "bps", "xdelta", "vcdiff", "ppf")

    fun apply(source: ByteArray, patch: ByteArray): PatchOutcome = try {
        when (formatOf(patch)) {
            PatchFormat.IPS -> ips(source, patch)
            PatchFormat.UPS -> checkedWithHeader(source) { ups(it, patch) }
            PatchFormat.BPS -> checkedWithHeader(source) { bps(it, patch) }
            PatchFormat.XDELTA -> xdelta(source, patch)
            PatchFormat.PPF -> ppf(source, patch)
            null -> PatchOutcome.Unreadable("This isn't an IPS, UPS, BPS, xdelta or PPF patch.")
        }
    } catch (e: Damaged) {
        PatchOutcome.Unreadable(e.message ?: "The patch is damaged.")
    }

    /**
     * A checked patch that doesn't fit a game with a copier header (512 bytes before a SNES game) is
     * tried on the game without it, as patches are made for the headerless dump.
     */
    private fun checkedWithHeader(source: ByteArray, run: (ByteArray) -> PatchOutcome): PatchOutcome {
        val first = run(source)
        if (first !is PatchOutcome.WrongGame || source.size % 1024 != COPIER_HEADER) return first
        val second = run(source.copyOfRange(COPIER_HEADER, source.size))
        return if (second is PatchOutcome.Patched) PatchOutcome.Patched(second.bytes, second.format, second.checked, headerRemoved = true) else first
    }

    // ---------------------------------------------------------------------------------- xdelta

    /**
     * An xdelta patch ([Vcdiff]). It names no game, but each part of its result carries an
     * Adler-32 that a patch for another version fails; that is tried again without a copier header.
     */
    private fun xdelta(source: ByteArray, patch: ByteArray): PatchOutcome {
        fun run(on: ByteArray, headerRemoved: Boolean): PatchOutcome = try {
            Vcdiff.apply(on, patch, MAX_SIZE).let { PatchOutcome.Patched(it.bytes, PatchFormat.XDELTA, it.checked, headerRemoved) }
        } catch (e: Vcdiff.Problem) {
            if (e.notForThisGame) PatchOutcome.NotForThisGame(PatchFormat.XDELTA, e.message.orEmpty()) else PatchOutcome.Unreadable(e.message.orEmpty())
        }
        val first = run(source, headerRemoved = false)
        if (first !is PatchOutcome.NotForThisGame || source.size % 1024 != COPIER_HEADER) return first
        return run(source.copyOfRange(COPIER_HEADER, source.size), headerRemoved = true).takeIf { it is PatchOutcome.Patched } ?: first
    }

    // ------------------------------------------------------------------------------------- PPF

    /** A PPF patch on a game held in memory; a disc image too large for that is patched with [Ppf] on a copy of its file. */
    private fun ppf(source: ByteArray, patch: ByteArray): PatchOutcome = try {
        val parsed = Ppf.parse(patch)
        when (val v = Ppf.check(parsed, source.size.toLong()) { at, length -> source.copyOfRange(minOf(at, source.size.toLong()).toInt(), minOf(at + length, source.size.toLong()).toInt()) }) {
            is Ppf.Verdict.Ready -> {
                val size = maxOf(source.size.toLong(), v.edits.maxOf { it.offset + it.bytes.size })
                if (size > MAX_SIZE) throw Damaged("The patch makes a game larger than Fuse patches.")
                val out = source.copyOf(size.toInt())
                for (e in v.edits) e.bytes.copyInto(out, e.offset.toInt())
                PatchOutcome.Patched(out, PatchFormat.PPF, v.checked)
            }
            Ppf.Verdict.AlreadyPatched -> PatchOutcome.AlreadyPatched(PatchFormat.PPF)
            is Ppf.Verdict.NotForThisGame -> PatchOutcome.NotForThisGame(PatchFormat.PPF, v.reason)
        }
    } catch (e: Ppf.Problem) {
        PatchOutcome.Unreadable(e.message.orEmpty())
    }

    // ------------------------------------------------------------------------------------- IPS

    private fun ips(source: ByteArray, patch: ByteArray): PatchOutcome {
        var out = source.copyOf()
        var at = 5
        fun ensure(size: Int) {
            if (size > out.size) out = out.copyOf(size)
        }
        while (true) {
            if (at + 3 > patch.size) throw Damaged("The patch ends before its end marker.")
            if (patch[at] == 'E'.code.toByte() && patch[at + 1] == 'O'.code.toByte() && patch[at + 2] == 'F'.code.toByte()) {
                at += 3
                break
            }
            val offset = be(patch, at, 3)
            at += 3
            if (at + 2 > patch.size) throw Damaged("A record of the patch is cut short.")
            val size = be(patch, at, 2)
            at += 2
            if (size == 0) {
                // Run-length: a count, then one byte repeated.
                if (at + 3 > patch.size) throw Damaged("A record of the patch is cut short.")
                val count = be(patch, at, 2)
                val value = patch[at + 2]
                at += 3
                ensure(offset + count)
                out.fill(value, offset, offset + count)
            } else {
                if (at + size > patch.size) throw Damaged("A record of the patch is cut short.")
                ensure(offset + size)
                patch.copyInto(out, offset, at, at + size)
                at += size
            }
            if (out.size > MAX_SIZE) throw Damaged("The patch makes a game larger than Fuse patches.")
        }
        // Some IPS patches end with the size the game is cut to.
        if (at + 3 == patch.size) out = out.copyOf(be(patch, at, 3))
        return PatchOutcome.Patched(out, PatchFormat.IPS, checked = false)
    }

    // ------------------------------------------------------------------------------------- UPS

    private fun ups(source: ByteArray, patch: ByteArray): PatchOutcome {
        if (patch.size < 4 + FOOTER) throw Damaged("The patch is too short.")
        checkOwn(patch)
        val r = Reader(patch, 4, patch.size - FOOTER)
        val sourceSize = r.varint()
        val targetSize = r.varint()
        val sourceCrc = le32(patch, patch.size - 12)
        val targetCrc = le32(patch, patch.size - 8)
        val actual = crc32(source)
        if (actual == targetCrc && source.size.toLong() == targetSize && actual != sourceCrc) return PatchOutcome.AlreadyPatched(PatchFormat.UPS)
        if (actual != sourceCrc || source.size.toLong() != sourceSize) return PatchOutcome.WrongGame(PatchFormat.UPS, sourceCrc, actual)
        if (targetSize > MAX_SIZE) throw Damaged("The patch makes a game larger than Fuse patches.")
        val out = source.copyOf(targetSize.toInt())
        var pos = 0L
        while (!r.done()) {
            pos += r.varint()
            while (true) {
                if (r.done()) throw Damaged("A run of the patch is cut short.")
                val x = r.byte()
                if (pos < out.size) out[pos.toInt()] = ((if (pos < source.size) source[pos.toInt()].toInt() else 0) xor x).toByte()
                pos++
                if (x == 0) break
            }
        }
        if (crc32(out) != targetCrc) throw Damaged("The patched game isn't what the patch says it makes.")
        return PatchOutcome.Patched(out, PatchFormat.UPS, checked = true)
    }

    // ------------------------------------------------------------------------------------- BPS

    private fun bps(source: ByteArray, patch: ByteArray): PatchOutcome {
        if (patch.size < 4 + FOOTER) throw Damaged("The patch is too short.")
        checkOwn(patch)
        val r = Reader(patch, 4, patch.size - FOOTER)
        val sourceSize = r.varint()
        val targetSize = r.varint()
        r.skip(r.varint())
        val sourceCrc = le32(patch, patch.size - 12)
        val targetCrc = le32(patch, patch.size - 8)
        val actual = crc32(source)
        if (actual == targetCrc && source.size.toLong() == targetSize && actual != sourceCrc) return PatchOutcome.AlreadyPatched(PatchFormat.BPS)
        if (actual != sourceCrc || source.size.toLong() != sourceSize) return PatchOutcome.WrongGame(PatchFormat.BPS, sourceCrc, actual)
        if (targetSize > MAX_SIZE) throw Damaged("The patch makes a game larger than Fuse patches.")
        val out = ByteArray(targetSize.toInt())
        var outAt = 0
        var sourceRel = 0L
        var targetRel = 0L
        while (!r.done()) {
            val data = r.varint()
            val length = ((data shr 2) + 1).toInt()
            if (outAt + length > out.size) throw Damaged("The patch writes past the end of the game it makes.")
            when ((data and 3).toInt()) {
                SOURCE_READ -> {
                    if (outAt + length > source.size) throw Damaged("The patch reads past the end of the game.")
                    source.copyInto(out, outAt, outAt, outAt + length)
                    outAt += length
                }
                TARGET_READ -> {
                    repeat(length) { out[outAt++] = r.byte().toByte() }
                }
                SOURCE_COPY -> {
                    val d = r.varint()
                    sourceRel += (if (d and 1L == 1L) -1 else 1) * (d shr 1)
                    if (sourceRel < 0 || sourceRel + length > source.size) throw Damaged("The patch copies from outside the game.")
                    source.copyInto(out, outAt, sourceRel.toInt(), sourceRel.toInt() + length)
                    outAt += length
                    sourceRel += length
                }
                else -> {
                    val d = r.varint()
                    targetRel += (if (d and 1L == 1L) -1 else 1) * (d shr 1)
                    if (targetRel < 0 || targetRel >= outAt) throw Damaged("The patch copies from outside what it made.")
                    // Byte by byte: the copy may read what it is writing (a repeating run).
                    repeat(length) { out[outAt++] = out[(targetRel++).toInt()] }
                }
            }
        }
        if (outAt != out.size || crc32(out) != targetCrc) throw Damaged("The patched game isn't what the patch says it makes.")
        return PatchOutcome.Patched(out, PatchFormat.BPS, checked = true)
    }

    // ---------------------------------------------------------------------------------- shared

    /** The patch's own CRC32, over everything before it: a damaged download is never applied. */
    private fun checkOwn(patch: ByteArray) {
        val expected = le32(patch, patch.size - 4)
        if (crc32(patch, 0, patch.size - 4) != expected) throw Damaged("The patch is damaged (its own checksum doesn't match).")
    }

    private class Damaged(message: String) : Exception(message)

    /** UPS's and BPS's numbers: seven bits a byte, the last byte marked by its top bit. */
    private class Reader(val b: ByteArray, var at: Int, val end: Int) {
        fun done() = at >= end
        fun byte(): Int {
            if (at >= end) throw Damaged("The patch is cut short.")
            return b[at++].toInt() and 0xFF
        }
        fun skip(n: Long) {
            if (n < 0 || at + n > end) throw Damaged("The patch is cut short.")
            at += n.toInt()
        }
        fun varint(): Long {
            var data = 0L
            var shift = 1L
            while (true) {
                val x = byte()
                data += (x and 0x7F) * shift
                if (x and 0x80 != 0) return data
                shift = shift shl 7
                data += shift
                if (shift > (1L shl 49)) throw Damaged("A number in the patch is too large.")
            }
        }
    }

    private fun ByteArray.startsWith(tag: String) = size >= tag.length && tag.indices.all { this[it] == tag[it].code.toByte() }

    private fun be(b: ByteArray, at: Int, n: Int): Int {
        var v = 0
        for (i in 0 until n) v = (v shl 8) or (b[at + i].toInt() and 0xFF)
        return v
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private val TABLE = LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) { c = if (c and 1L != 0L) 0xEDB88320L xor (c ushr 1) else c ushr 1 }
        c
    }

    /** CRC-32 (IEEE), as UPS and BPS record it. */
    fun crc32(b: ByteArray, from: Int = 0, to: Int = b.size): Long {
        var c = 0xFFFFFFFFL
        for (i in from until to) c = TABLE[((c xor b[i].toLong()) and 0xFF).toInt()] xor (c ushr 8)
        return c xor 0xFFFFFFFFL
    }

    private const val FOOTER = 12
    private const val COPIER_HEADER = 512
    private const val SOURCE_READ = 0
    private const val TARGET_READ = 1
    private const val SOURCE_COPY = 2

    /** The largest game Fuse patches (patches are for cartridge games, well under this). */
    const val MAX_SIZE = 256L * 1024 * 1024
}
