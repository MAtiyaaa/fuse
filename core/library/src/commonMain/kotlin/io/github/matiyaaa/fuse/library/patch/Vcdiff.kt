package io.github.matiyaaa.fuse.library.patch

/**
 * VCDIFF (RFC 3284), the format xdelta3 writes its patches in, read as the RFC lays it out with
 * xdelta3's own additions as its decoder reads them (`xdelta3-decode.h`, `xd3_decode_input`):
 *
 * - The header: `D6 C3 C4 00`, the header indicator, then (when its bit 0, VCD_SECONDARY, is set)
 *   the secondary compressor's id, (bit 1, VCD_CODETABLE) a code table of its own, and (bit 2,
 *   VCD_APPHEADER, xdelta3's) application data: its length and its bytes, skipped here.
 * - Each window: its indicator (VCD_SOURCE 1, VCD_TARGET 2, and xdelta3's VCD_ADLER32 4), the
 *   segment it copies from (length and position) for VCD_SOURCE or VCD_TARGET, the encoding's
 *   length, the target window's length, the delta indicator, the data, instruction and address
 *   sections' lengths, then (VCD_ADLER32) the Adler-32 of the window's result as four big-endian
 *   bytes, then the three sections.
 *
 * Instructions follow the RFC's default code table (section 5.6) and its address cache of four
 * near and three same slots (section 5.3).
 *
 * Sections xdelta3 compressed again (the delta indicator's bits, VCD_DATACOMP 1, VCD_INSTCOMP 2,
 * VCD_ADDRCOMP 4) are read when the compressor is its LZMA (id 2), which xdelta3 uses by default
 * when built with it (`xdelta3-main.h`, `main_set_secondary_flags`). As `xdelta3-second.h`
 * (`xd3_decode_secondary`) and `xdelta3-lzma.h` read them: the section starts with its decoded
 * size, then the next part of one .xz stream (LZMA2, no check) per kind of section that runs
 * through every window, each window's part ending at a flush; a window's section under 10 bytes is
 * never compressed. Its DJW and FGK (ids 1 and 16), and a code table of the patch's own (which
 * xdelta3 itself no longer reads), are refused, never guessed at. Pure: bytes in, bytes out.
 */
internal object Vcdiff {
    /** True when [patch] starts as a VCDIFF file does. */
    fun isVcdiff(patch: ByteArray): Boolean =
        patch.size >= 4 && (patch[0].toInt() and 0xFF) == 0xD6 && (patch[1].toInt() and 0xFF) == 0xC3 &&
            (patch[2].toInt() and 0xFF) == 0xC4 && patch[3].toInt() == 0

    /** What decoding came to: the result, and whether every window carried a checksum it matched. */
    class Result(val bytes: ByteArray, val checked: Boolean)

    /** Thrown for a patch Fuse can't apply; [notForThisGame] when its own check of the result failed. */
    class Problem(message: String, val notForThisGame: Boolean = false) : Exception(message)

    /** [patch] applied to [source]; at most [maxSize] bytes are made. */
    fun apply(source: ByteArray, patch: ByteArray, maxSize: Long): Result {
        if (!isVcdiff(patch)) throw Problem("This isn't a VCDIFF (xdelta) patch.")
        val r = Reader(patch, 4)
        val header = r.byte()
        if (header and 0x7.inv() != 0) throw Problem("The patch's header says things Fuse doesn't know.")
        val compressor = if (header and VCD_SECONDARY != 0) r.byte() else null
        if (header and VCD_CODETABLE != 0) throw Problem("The patch brings a code table of its own, which Fuse (like xdelta itself) doesn't read.")
        if (header and VCD_APPHEADER != 0) r.skip(r.int())

        val out = Output(maxSize)
        var checked = true
        var windows = 0
        // One stream per kind of section (data, instructions, addresses), made when first needed.
        val streams = arrayOfNulls<SectionStream>(3)
        while (!r.done()) {
            val win = r.byte()
            if (win and 0x7.inv() != 0) throw Problem("A part of the patch says things Fuse doesn't know.")
            val fromSource = win and VCD_SOURCE != 0
            val fromTarget = win and VCD_TARGET != 0
            if (fromSource && fromTarget) throw Problem("The patch is damaged (a part copies from both).")
            var segmentLength = 0
            var segmentAt = 0L
            if (fromSource || fromTarget) {
                segmentLength = r.int()
                segmentAt = r.long()
                val limit = if (fromSource) source.size.toLong() else out.size.toLong()
                if (segmentAt + segmentLength > limit) throw Problem(if (fromSource) "The patch reaches past the end of this game: it was made for another version." else "The patch is damaged (it copies from past what it has made).", notForThisGame = fromSource)
            }
            val encodingLength = r.int()
            val encodingStart = r.at
            val targetLength = r.int()
            if (out.size + targetLength.toLong() > maxSize) throw Problem("The patch makes a game larger than Fuse patches.")
            val delta = r.byte()
            if (delta and 0x7.inv() != 0) throw Problem("A part of the patch says things Fuse doesn't know.")
            if (delta != 0 && compressor == null) throw Problem("The patch is damaged (a part says it is compressed, and the patch names no compressor).")
            if (delta != 0 && compressor != LZMA_ID) throw Problem(SECONDARY)
            val dataLength = r.int()
            val instLength = r.int()
            val addrLength = r.int()
            val adler = if (win and VCD_ADLER32 != 0) r.u32() else null
            if (adler == null) checked = false
            if (r.at - encodingStart + dataLength.toLong() + instLength + addrLength != encodingLength.toLong()) {
                throw Problem("The patch is damaged (a part's length doesn't add up).")
            }
            // A section as it is in the patch, or as its compressor gives it back.
            fun section(kind: Int, length: Int): Reader {
                val raw = Reader(patch, r.at, r.at + length).also { r.skip(length) }
                if (delta and (1 shl kind) == 0) return raw
                val size = raw.long()
                // No section makes more than a few bytes for each byte of the window (an address is at most nine).
                if (size <= 0 || size > 16L * targetLength + 64 || size > maxSize) throw Problem("The patch is damaged (a compressed part's size is wrong).")
                val stream = streams[kind] ?: lzmaSections().also { streams[kind] = it }
                val bytes = stream.next(patch.copyOfRange(raw.at, raw.end), size.toInt())
                return Reader(bytes, 0)
            }
            val data = section(DATA, dataLength)
            val inst = section(INST, instLength)
            val addr = section(ADDR, addrLength)

            // The segment it copies from, then the window as it is made: one address space (section 5.1).
            val segment: (Int) -> Byte = when {
                fromSource -> { i -> source[(segmentAt + i).toInt()] }
                fromTarget -> { i -> out.at((segmentAt + i).toInt()) }
                else -> { _ -> throw Problem("The patch is damaged (it copies with nothing to copy from).") }
            }
            val windowStart = out.size
            val cache = AddressCache()
            while (!inst.done()) {
                val code = inst.byte()
                for (half in 0..1) {
                    val type = TABLE_TYPE[half][code]
                    if (type == NOOP) continue
                    val size = TABLE_SIZE[half][code].let { if (it == 0) inst.int() else it }
                    val made = out.size - windowStart
                    if (made + size.toLong() > targetLength) throw Problem("The patch is damaged (a part makes more than it says).")
                    when (type) {
                        ADD -> repeat(size) { out.add(data.raw()) }
                        RUN -> data.raw().let { b -> repeat(size) { out.add(b) } }
                        else -> {
                            val here = segmentLength.toLong() + made
                            val from = cache.decode(type - COPY, here, addr)
                            if (from >= here) throw Problem("The patch is damaged (it copies from ahead of itself).")
                            for (i in 0 until size) {
                                val p = from + i
                                out.add(if (p < segmentLength) segment(p.toInt()) else out.at((windowStart + (p - segmentLength)).toInt()))
                            }
                        }
                    }
                }
            }
            if (out.size - windowStart != targetLength) throw Problem("The patch is damaged (a part makes less than it says).")
            if (!data.done() || !addr.done()) throw Problem("The patch is damaged (a part has bytes left over).")
            if (adler != null && adler32(out.buffer, windowStart, out.size) != adler) {
                throw Problem("This patch was made for another version of the game: what it makes doesn't match its own check.", notForThisGame = true)
            }
            windows++
        }
        if (windows == 0) throw Problem("The patch is empty.")
        return Result(out.bytes(), checked)
    }

    /** Adler-32 (RFC 1950) of [b] from [from] to [to], as xdelta3 checks each window. */
    fun adler32(b: ByteArray, from: Int, to: Int): Long {
        var a = 1L
        var s = 0L
        for (i in from until to) {
            a = (a + (b[i].toInt() and 0xFF)) % 65521
            s = (s + a) % 65521
        }
        return (s shl 16) or a
    }

    /** The RFC's address cache: four recent addresses, and 768 by their remainder (section 5.3). */
    private class AddressCache {
        private val near = LongArray(NEAR)
        private var next = 0
        private val same = LongArray(SAME * 256)

        fun decode(mode: Int, here: Long, addr: Reader): Long {
            val a = when {
                mode == 0 -> addr.long()
                mode == 1 -> here - addr.long()
                mode - 2 < NEAR -> near[mode - 2] + addr.long()
                mode - 2 - NEAR < SAME -> same[(mode - 2 - NEAR) * 256 + addr.byte()]
                else -> throw Problem("The patch is damaged (an address mode Fuse doesn't know).")
            }
            if (a < 0) throw Problem("The patch is damaged (an address before the start).")
            near[next] = a
            next = (next + 1) % NEAR
            same[(a % (SAME * 256)).toInt()] = a
            return a
        }
    }

    /** The made game, growing as windows are decoded. */
    private class Output(private val max: Long) {
        var buffer = ByteArray(64 * 1024)
        var size = 0
        fun add(b: Byte) {
            if (size == buffer.size) {
                if (size >= max) throw Problem("The patch makes a game larger than Fuse patches.")
                buffer = buffer.copyOf(minOf(max, size.toLong() * 2).toInt())
            }
            buffer[size++] = b
        }
        fun at(i: Int): Byte = buffer[i]
        fun bytes(): ByteArray = buffer.copyOf(size)
    }

    /** RFC 3284's integers: seven bits a byte, most significant first, the top bit set on all but the last. */
    private class Reader(val b: ByteArray, var at: Int, val end: Int = b.size) {
        fun done() = at >= end
        fun byte(): Int {
            if (at >= end) throw Problem("The patch is cut short.")
            return b[at++].toInt() and 0xFF
        }
        fun raw(): Byte {
            if (at >= end) throw Problem("The patch is cut short.")
            return b[at++]
        }
        fun skip(n: Int) {
            if (n < 0 || at.toLong() + n > end) throw Problem("The patch is cut short.")
            at += n
        }
        fun long(): Long {
            var v = 0L
            repeat(9) {
                val x = byte()
                v = (v shl 7) or (x and 0x7F).toLong()
                if (x and 0x80 == 0) return v
            }
            throw Problem("A number in the patch is too large.")
        }
        fun int(): Int = long().also { if (it > Int.MAX_VALUE) throw Problem("A number in the patch is too large.") }.toInt()
        fun u32(): Long = (0 until 4).fold(0L) { v, _ -> (v shl 8) or byte().toLong() }
    }

    private const val VCD_SECONDARY = 1
    private const val VCD_CODETABLE = 2
    private const val VCD_APPHEADER = 4
    private const val VCD_SOURCE = 1
    private const val VCD_TARGET = 2
    private const val VCD_ADLER32 = 4
    private const val NEAR = 4
    private const val SAME = 3

    /** The delta indicator's bit for each kind of section is 1 shl its kind. */
    private const val DATA = 0
    private const val INST = 1
    private const val ADDR = 2

    /** xdelta3's id for its LZMA (`VCD_LZMA_ID`); DJW is 1 and FGK 16. */
    private const val LZMA_ID = 2

    private const val SECONDARY = "This xdelta patch is compressed again with xdelta's own DJW or FGK, which Fuse doesn't read. " +
        "Made again with xdelta3 -S lzma or -S none, it applies here."

    private const val NOOP = 0
    private const val ADD = 1
    private const val RUN = 2
    /** COPY in address mode m is COPY + m. */
    private const val COPY = 3

    /**
     * The default code table, built as RFC 3284 section 5.6 describes it: [0] RUN, [1..18] ADD of
     * sizes 0 and 1 to 17, [19..162] COPY of sizes 0 and 4 to 18 in each of the nine modes,
     * [163..234] ADD of 1 to 4 then COPY of 4 to 6 in modes 0 to 5, [235..246] ADD of 1 to 4 then
     * COPY of 4 in modes 6 to 8, [247..255] COPY of 4 then ADD of 1 in each mode. A size of 0 is
     * read from the instructions.
     */
    private val TABLE_TYPE = Array(2) { IntArray(256) }
    private val TABLE_SIZE = Array(2) { IntArray(256) }

    init {
        var i = 0
        fun put(t1: Int, s1: Int, t2: Int = NOOP, s2: Int = 0) {
            TABLE_TYPE[0][i] = t1; TABLE_SIZE[0][i] = s1; TABLE_TYPE[1][i] = t2; TABLE_SIZE[1][i] = s2
            i++
        }
        put(RUN, 0)
        put(ADD, 0)
        for (s in 1..17) put(ADD, s)
        for (m in 0..8) {
            put(COPY + m, 0)
            for (s in 4..18) put(COPY + m, s)
        }
        for (m in 0..5) for (a in 1..4) for (c in 4..6) put(ADD, a, COPY + m, c)
        for (m in 6..8) for (a in 1..4) put(ADD, a, COPY + m, 4)
        for (m in 0..8) put(COPY + m, 4, ADD, 1)
        check(i == 256)
    }

    /** The table as (type, size, type, size) for [code], for tests against xdelta3's own. */
    internal fun entry(code: Int): IntArray = intArrayOf(TABLE_TYPE[0][code], TABLE_SIZE[0][code], TABLE_TYPE[1][code], TABLE_SIZE[1][code])
}

/** One kind of section's run of compressed parts, one stream through every window. */
internal interface SectionStream {
    /** The next window's part ([input]) decoded: exactly [size] bytes, with every byte of [input] used, or [Vcdiff.Problem]. */
    fun next(input: ByteArray, size: Int): ByteArray
}

/** A stream for xdelta3's LZMA sections (.xz, LZMA2, no check). */
internal expect fun lzmaSections(): SectionStream
