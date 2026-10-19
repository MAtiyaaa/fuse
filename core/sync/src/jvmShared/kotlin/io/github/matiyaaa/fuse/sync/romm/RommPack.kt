package io.github.matiyaaa.fuse.sync.romm

import io.github.matiyaaa.fuse.sync.LocalSlot
import io.github.matiyaaa.fuse.sync.SlotWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A save made of more than one file (a PSP or PS3 save folder, a Switch game's save) as the one file
 * RomM keeps: a zip of its files by their names in the save, packed the same way every time (names
 * in order, one fixed time, stored as they are), so the same save is the same bytes.
 *
 * RomM records a zipped save by its contents, not its bytes (backend/handler/filesystem/
 * assets_handler.py, `hash_zip_contents`, RomM 5.4): the MD5 of `name:md5` for each file, in name
 * order, joined by new lines, folders left out. [contentHash] works that out from the files where
 * they are, so a save here is matched to RomM's without packing it.
 */
object RommPack {
    /** The one time every entry carries: the zip format's own first day, written as it is in any time zone. */
    private val FIXED_TIME: java.time.LocalDateTime = java.time.LocalDateTime.of(1980, 1, 1, 0, 0)

    /** [slot]'s files as RomM hashes a zip of them, or null when it has none. */
    fun contentHash(slot: LocalSlot): String? {
        if (slot.files.isEmpty()) return null
        return combine(slot.files.map { it.path to md5(it.file) })
    }

    /** A zip's files as RomM hashes them ([zip] read, never extracted). */
    fun contentHash(zip: ByteArray): String = combine(entries(zip).map { (name, bytes) -> name to md5(bytes) })

    private fun combine(files: List<Pair<String, String>>): String =
        md5(files.sortedBy { it.first }.joinToString("\n") { (name, hash) -> "$name:$hash" }.encodeToByteArray())

    /** [slot]'s files (and its empty folders) as a zip, the same bytes for the same save. */
    fun pack(slot: LocalSlot): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val names = (slot.files.map { it.path to it.file } + slot.folders.map { "$it/" to null }).sortedBy { it.first }
            for ((name, file) in names) {
                val bytes = file?.readBytes() ?: ByteArray(0)
                val entry = ZipEntry(name)
                entry.timeLocal = FIXED_TIME
                // Stored, not deflated: what a save holds is often packed already, and stored is simplest to read back.
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().also { it.update(bytes) }.value
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A zip's files by name (folders left out), each as its bytes. */
    fun entries(zip: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var total = 0L
        val buf = ByteArray(64 * 1024)
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || e.name.endsWith("/")) continue
                // Read in step with a limit, so a zip that says little and unpacks to much stops early.
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw IllegalStateException("This save is larger than any save Fuse takes from RomM.")
                    bytes.write(buf, 0, n)
                }
                out += e.name to bytes.toByteArray()
            }
        }
        return out
    }

    /** A zip's empty folders, by name. */
    private fun folders(zip: ByteArray): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || e.name.endsWith("/")) out += e.name.trimEnd('/')
            }
        }
        return out
    }

    /**
     * [zip]'s save into [slot], replacing the one there whole ([SlotWriter.lay]): a file with
     * nowhere safe to go stops it before anything is moved. The caller keeps the save that was
     * here first.
     */
    fun unpack(zip: ByteArray, slot: LocalSlot) =
        SlotWriter.lay(slot, entries(zip).map { (name, bytes) -> name to { t: File -> t.writeBytes(bytes) } }, folders(zip))

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun md5(file: File): String = JvmRommSaves.md5(file)

    /** The most a save from RomM may hold, unpacked (it is read into memory). */
    private const val MAX_BYTES = 512L * 1024 * 1024
}
