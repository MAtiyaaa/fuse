package io.github.matiyaaa.fuse.sync

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Files kept by what they contain: each under the SHA-256 of its bytes (`ab/cdef...`). The same
 * bytes are kept once however many saves, revisions or profiles share them, so keeping history
 * costs only what changed. A file is written to a temporary name, its hash checked against the
 * name it claims, then moved into place in one step: an interrupted or corrupt write never
 * replaces anything and never appears under a hash it doesn't have.
 */
class ContentStore(val root: File) {
    private val temp = File(root, "tmp")

    init {
        root.mkdirs()
        temp.mkdirs()
        // Writes a crash interrupted are never trusted: they start again.
        temp.listFiles()?.forEach { it.delete() }
    }

    fun fileOf(hash: String): File {
        require(SavePath.isHash(hash)) { "Not a content hash" }
        return File(File(root, hash.substring(0, 2)), hash.substring(2))
    }

    fun has(hash: String): Boolean = SavePath.isHash(hash) && fileOf(hash).isFile

    fun open(hash: String): InputStream? = if (has(hash)) fileOf(hash).inputStream() else null

    fun size(hash: String): Long = if (has(hash)) fileOf(hash).length() else -1

    /**
     * Stores [input] as [expected] (when known): returns the hash it has, or throws
     * [IntegrityException] when it isn't [expected] (nothing is kept then). [maxBytes] limits
     * what is read.
     */
    fun put(input: InputStream, expected: String? = null, maxBytes: Long = Long.MAX_VALUE): String {
        if (expected != null) require(SavePath.isHash(expected)) { "Not a content hash" }
        val tmp = File.createTempFile("incoming", ".part", temp)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            DigestOutputStream(tmp.outputStream().buffered(), md).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IntegrityException("larger than allowed")
                    out.write(buf, 0, n)
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            if (expected != null && hash != expected) throw IntegrityException("content doesn't match its hash")
            val dest = fileOf(hash)
            if (dest.isFile) return hash
            dest.parentFile.mkdirs()
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return hash
        } finally {
            tmp.delete()
        }
    }

    fun put(bytes: ByteArray): String = put(bytes.inputStream())

    /** Writes [hash]'s bytes to [out]. */
    fun copyTo(hash: String, out: OutputStream): Boolean {
        val f = if (has(hash)) fileOf(hash) else return false
        f.inputStream().use { it.copyTo(out) }
        return true
    }

    /** Re-reads [hash] and checks it still is what its name says (a disk can rot). */
    fun verify(hash: String): Boolean = has(hash) && fileOf(hash).inputStream().use { SyncCrypto.sha256(it) } == hash

    /** Every hash kept. */
    fun all(): Sequence<String> = (root.listFiles() ?: emptyArray()).asSequence()
        .filter { it.isDirectory && it.name.length == 2 }
        .flatMap { d -> (d.listFiles() ?: emptyArray()).asSequence().map { d.name + it.name } }
        .filter(SavePath::isHash)

    /** Removes every file not in [referenced]; returns how many bytes that freed. */
    fun collect(referenced: Set<String>): Long {
        var freed = 0L
        for (h in all().toList()) {
            if (h in referenced) continue
            val f = fileOf(h)
            freed += f.length()
            f.delete()
        }
        return freed
    }

    fun totalBytes(): Long = all().sumOf { fileOf(it).length() }
}

/** Bytes that aren't what they claim to be. Nothing is stored or replaced when this is thrown. */
class IntegrityException(message: String) : Exception(message)

/** Writes [bytes] to [file] through a temporary file and one move: readers see the old or the new, never half. */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "." + file.name + ".tmp")
    tmp.outputStream().use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    try {
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
