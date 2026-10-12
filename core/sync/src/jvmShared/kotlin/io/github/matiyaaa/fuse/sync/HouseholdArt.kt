package io.github.matiyaaa.fuse.sync

import java.io.File

/**
 * Artwork kept once for the whole household: when one device fetched a cover from a provider it
 * gives it to the host, and every other device asks the host before the provider, so five devices
 * wanting the same cover fetch it from the internet once. Each picture is kept by the SHA-256 of the
 * address it came from; at most [maxBytes] are kept, the ones asked for longest ago going first.
 * Only paired devices reach it (every call is signed), and it is no part of the household's copies
 * or backups: it is a cache, fetched again whenever it isn't here.
 */
class HouseholdArt(val dir: File, private val maxBytes: Long = MAX_BYTES, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    /** The picture kept for [key], marked as asked for now; null when there is none. */
    fun get(key: String): ByteArray? {
        if (!SavePath.isHash(key)) return null
        val f = File(dir, key)
        if (!f.isFile) return null
        f.setLastModified(clock())
        return runCatching { f.readBytes() }.getOrNull()
    }

    /** Keeps [bytes] for [key] (a picture at most [MAX_PICTURE]); false when it isn't one Fuse keeps. */
    fun put(key: String, bytes: ByteArray): Boolean {
        if (!SavePath.isHash(key) || bytes.isEmpty() || bytes.size > MAX_PICTURE || !picture(bytes)) return false
        synchronized(lock) {
            dir.mkdirs()
            val tmp = File(dir, ".$key.tmp")
            tmp.writeBytes(bytes)
            val file = File(dir, key)
            if (!tmp.renameTo(file)) { tmp.delete(); return false }
            file.setLastModified(clock())
            trim()
        }
        return true
    }

    /** Lets the pictures asked for longest ago go until what is kept fits [maxBytes]. */
    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_PICTURE = 16 * 1024 * 1024

        /** PNG, JPEG, GIF, WebP, AVIF/HEIF and SVG, by their first bytes: nothing else is kept. */
        fun picture(b: ByteArray): Boolean {
            fun at(i: Int, vararg v: Int) = b.size >= i + v.size && v.indices.all { (b[i + it].toInt() and 0xFF) == v[it] }
            return at(0, 0x89, 0x50, 0x4E, 0x47) || at(0, 0xFF, 0xD8, 0xFF) || at(0, 0x47, 0x49, 0x46, 0x38) ||
                (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) || at(4, 0x66, 0x74, 0x79, 0x70) ||
                b.copyOfRange(0, minOf(b.size, 512)).decodeToString().trimStart().let { it.startsWith("<svg") || it.startsWith("<?xml") }
        }
    }
}
