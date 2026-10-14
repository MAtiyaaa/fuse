package io.github.matiyaaa.fuse.integrations.disc

/**
 * The general-purpose decompressors compressed disc images use (CHD's codecs, CSO, GCZ, RVZ), from
 * the platform: raw deflate, LZMA and Zstandard. Each fills [dest] from [destOffset] with exactly
 * [destLength] bytes and returns false when the data doesn't decode to that.
 */
internal expect object Decompress {
    fun rawDeflate(src: ByteArray, srcOffset: Int, srcLength: Int, dest: ByteArray, destOffset: Int, destLength: Int): Boolean

    /** Deflate with a zlib header (GCZ and CSO blocks write it so). */
    fun zlib(src: ByteArray, srcOffset: Int, srcLength: Int, dest: ByteArray, destOffset: Int, destLength: Int): Boolean

    /** Raw LZMA (no header) with [props] (lc, lp, pb as one byte) and a dictionary of at least [dictionarySize]. */
    fun lzma(src: ByteArray, srcOffset: Int, srcLength: Int, props: Int, dictionarySize: Int, dest: ByteArray, destOffset: Int, destLength: Int): Boolean

    /** Raw LZMA2 chunks with a dictionary of at least [dictionarySize]. */
    fun lzma2(src: ByteArray, srcOffset: Int, srcLength: Int, dictionarySize: Int, dest: ByteArray, destOffset: Int, destLength: Int): Boolean

    /** A Zstandard frame. */
    fun zstd(src: ByteArray, srcOffset: Int, srcLength: Int, dest: ByteArray, destOffset: Int, destLength: Int): Boolean
}
