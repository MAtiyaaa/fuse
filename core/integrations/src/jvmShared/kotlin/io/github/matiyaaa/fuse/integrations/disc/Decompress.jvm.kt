package io.github.matiyaaa.fuse.integrations.disc

import java.io.ByteArrayInputStream
import java.util.zip.Inflater

internal actual object Decompress {
    actual fun rawDeflate(src: ByteArray, srcOffset: Int, srcLength: Int, dest: ByteArray, destOffset: Int, destLength: Int): Boolean =
        inflate(true, src, srcOffset, srcLength, dest, destOffset, destLength)

    actual fun zlib(src: ByteArray, srcOffset: Int, srcLength: Int, dest: ByteArray, destOffset: Int, destLength: Int): Boolean =
        inflate(false, src, srcOffset, srcLength, dest, destOffset, destLength)

    private fun inflate(raw: Boolean, src: ByteArray, srcOffset: Int, srcLength: Int, dest: ByteArray, destOffset: Int, destLength: Int): Boolean {
        val inflater = Inflater(raw)
        return try {
            // A raw stream may need one byte more than it holds to see its end (as zlib asks).
            if (raw) inflater.setInput(src.copyOfRange(srcOffset, srcOffset + srcLength) + 0) else inflater.setInput(src, srcOffset, srcLength)
            var done = 0
            while (done < destLength) {
                val n = inflater.inflate(dest, destOffset + done, destLength - done)
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                done += n
            }
            done == destLength
        } catch (e: java.util.zip.DataFormatException) {
            false
        } finally {
            inflater.end()
        }
    }

    actual fun lzma(src: ByteArray, srcOffset: Int, srcLength: Int, props: Int, dictionarySize: Int, dest: ByteArray, destOffset: Int, destLength: Int): Boolean = try {
        org.tukaani.xz.LZMAInputStream(
            ByteArrayInputStream(src, srcOffset, srcLength),
            destLength.toLong(),
            props.toByte(),
            maxOf(dictionarySize, 4096),
        ).use { input ->
            var done = 0
            while (done < destLength) {
                val n = input.read(dest, destOffset + done, destLength - done)
                if (n <= 0) break
                done += n
            }
            done == destLength
        }
    } catch (e: java.io.IOException) {
        false
    }

    actual fun lzma2(src: ByteArray, srcOffset: Int, srcLength: Int, dictionarySize: Int, dest: ByteArray, destOffset: Int, destLength: Int): Boolean = try {
        org.tukaani.xz.LZMA2InputStream(ByteArrayInputStream(src, srcOffset, srcLength), dictionarySize.coerceIn(4096, 1 shl 30)).use { input ->
            var done = 0
            while (done < destLength) {
                val n = input.read(dest, destOffset + done, destLength - done)
                if (n <= 0) break
                done += n
            }
            done == destLength
        }
    } catch (e: java.io.IOException) {
        false
    }

    actual fun zstd(src: ByteArray, srcOffset: Int, srcLength: Int, dest: ByteArray, destOffset: Int, destLength: Int): Boolean = try {
        io.airlift.compress.zstd.ZstdDecompressor().decompress(src, srcOffset, srcLength, dest, destOffset, destLength) == destLength
    } catch (e: RuntimeException) {
        false
    } catch (e: LinkageError) {
        // Its fast path isn't on every system (it uses sun.misc.Unsafe): such images are matched by name.
        false
    }
}
