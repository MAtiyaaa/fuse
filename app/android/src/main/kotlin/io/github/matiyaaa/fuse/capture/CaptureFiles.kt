package io.github.matiyaaa.fuse.capture

/** Where captures go and how big a recording is: plain rules, kept apart so they can be tested. */
internal object CaptureFiles {
    /** Screenshots, under the shared storage's Pictures folder. */
    const val PICTURES = "Pictures/Fuse"

    /** Recordings, under the shared storage's Movies folder. */
    const val MOVIES = "Movies/Fuse"

    /**
     * The recording's size: the screen scaled down (never up) to fit 1920 x 1080 either way round,
     * with even sides, which every video encoder needs.
     */
    fun videoSize(width: Int, height: Int, maxLong: Int = 1920, maxShort: Int = 1080): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return maxLong to maxShort
        val long = maxOf(width, height).toDouble()
        val short = minOf(width, height).toDouble()
        val scale = minOf(1.0, maxLong / long, maxShort / short)
        fun even(v: Double) = ((v.toInt() / 2) * 2).coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }

    /** Rounds a side down to a multiple of 16, for encoders that only take such sizes. */
    fun align16(v: Int): Int = ((v / 16) * 16).coerceAtLeast(16)

    /** About 0.13 bits per pixel at 30 frames a second: 8 Mbit/s at 1080p. */
    fun bitrate(width: Int, height: Int): Int = (width.toLong() * height * 30 * 13 / 100).coerceIn(2_000_000, 12_000_000).toInt()

    /** [name] without characters a file system or gallery could trip on. */
    fun safeName(name: String): String = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "-").trim().ifEmpty { "Fuse" }
}
