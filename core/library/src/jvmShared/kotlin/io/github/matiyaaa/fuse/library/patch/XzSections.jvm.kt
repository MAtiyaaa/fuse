package io.github.matiyaaa.fuse.library.patch

import org.tukaani.xz.MemoryLimitException
import org.tukaani.xz.XZInputStream
import java.io.IOException
import java.io.InputStream

internal actual fun lzmaSections(): SectionStream = XzSectionStream()

/**
 * One section kind's .xz stream, read with xz-java as each window's bytes come. The decoder asks
 * for input only as far as the output it is asked for, so after a window's own size it has read
 * that window's bytes and no further; anything it left is a damaged patch.
 */
private class XzSectionStream : SectionStream {
    private val feed = Feed()
    private var xz: XZInputStream? = null

    override fun next(input: ByteArray, size: Int): ByteArray {
        feed.add(input)
        val out = ByteArray(size)
        try {
            val stream = xz ?: XZInputStream(feed, MEMORY_LIMIT_KB).also { xz = it }
            var done = 0
            while (done < size) {
                val n = stream.read(out, done, size - done)
                if (n <= 0) throw Vcdiff.Problem("The patch is damaged (a compressed part ends early).")
                done += n
            }
        } catch (e: MemoryLimitException) {
            throw Vcdiff.Problem("This patch was compressed for more memory than Fuse gives a patch.")
        } catch (e: IOException) {
            throw Vcdiff.Problem("The patch is damaged (a compressed part doesn't read).")
        } catch (e: RuntimeException) {
            // The decoder's own checks failing some other way on bytes that aren't what they say.
            throw Vcdiff.Problem("The patch is damaged (a compressed part doesn't read).")
        }
        if (feed.left() != 0) throw Vcdiff.Problem("The patch is damaged (a compressed part has bytes left over).")
        return out
    }

    /** The windows' bytes in order, ending where the patch so far ends. */
    private class Feed : InputStream() {
        private val parts = ArrayDeque<ByteArray>()
        private var at = 0

        fun add(b: ByteArray) {
            if (b.isNotEmpty()) parts.addLast(b)
        }

        fun left(): Int = parts.sumOf { it.size } - at

        override fun read(): Int {
            val part = parts.firstOrNull() ?: return -1
            val b = part[at++].toInt() and 0xFF
            if (at == part.size) {
                parts.removeFirst()
                at = 0
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val part = parts.firstOrNull() ?: return -1
            val n = minOf(len, part.size - at)
            part.copyInto(b, off, at, at + n)
            at += n
            if (at == part.size) {
                parts.removeFirst()
                at = 0
            }
            return n
        }

        override fun available(): Int = left()
    }

    private companion object {
        /** xdelta3's highest level (-9) asks for a 64 MB dictionary: that and xz-java's own needs, per section. */
        const val MEMORY_LIMIT_KB = 72 * 1024
    }
}
