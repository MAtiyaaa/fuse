package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What a recovery package holds, written first inside it and checked against everything after. */
@Serializable
data class RecoveryManifest(
    val format: Int = RecoveryPackage.FORMAT,
    val hostId: String,
    val hostName: String,
    val createdAt: Long,
    val fuseVersion: String = "",
    val seq: Long,
    /** The household's generation of hosts when it was made; a host restored from it takes a newer one. */
    val generation: Long,
    val profiles: Int,
    val devices: Int,
    val revisions: Int,
    /** Each household file by its path, with its SHA-256. */
    val files: Map<String, String>,
    /** The saves' files, by their SHA-256 (each named for it). */
    val objects: List<String>,
    val bytes: Long = 0,
)

/** A recovery package read whole and found to be what its manifest says: [files] and the [objects] folder its files went to. */
class OpenedPackage(val manifest: RecoveryManifest, val files: Map<String, ByteArray>, val objects: File)

/**
 * The whole household in one file to keep offline (another drive, a USB stick), sealed with a
 * password, for when the host computer is gone or replaced: everything the host keeps but its
 * files, and every file its saves use. It is AES-256-GCM in sealed pieces of a megabyte, the key
 * stretched from the password with PBKDF2 (a random salt in its header), each piece numbered and the
 * last one marked, so a piece changed, moved, dropped or cut off is found. Restoring reads it all
 * and checks every file against the manifest before anything changes.
 */
object RecoveryPackage {
    const val FORMAT = 1
    const val EXTENSION = "fuserecovery"
    private const val MAGIC = "FUSE-RECOVERY"
    private const val CHUNK = 1 shl 20
    private const val ITERATIONS = 310_000
    private const val MANIFEST = "manifest.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The shortest password a package takes. */
    const val MIN_PASSWORD = RecoveryJob.MIN_PASSWORD

    @Serializable
    private data class Header(
        val format: Int,
        val salt: String,
        val iterations: Int,
        val prefix: String,
        val createdAt: Long,
        val hostName: String,
        /** Made from the key alone, so a wrong password is told apart from a damaged package. */
        val check: String = "",
    )

    private fun check(key: ByteArray): String = SyncCrypto.hmac(key, "fuse-recovery-password-check")

    /** Writes the household [store] keeps to [out], sealed with [password]. Returns its manifest. */
    fun write(
        store: HostStore,
        out: File,
        password: String,
        fuseVersion: String = "",
        clock: () -> Long = System::currentTimeMillis,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): RecoveryManifest {
        require(password.length >= MIN_PASSWORD) { "The password needs at least $MIN_PASSWORD characters." }
        val files = store.replicaFiles()
        val objects = store.referencedObjects().sorted()
        val missing = objects.filter { !store.content.has(it) }
        check(missing.isEmpty()) { "${missing.size} of the saves' files are missing from the host, so the package wouldn't be whole." }
        val status = store.status(0, "")
        val manifest = RecoveryManifest(
            hostId = store.identity.hostId, hostName = store.name, createdAt = clock(), fuseVersion = fuseVersion, seq = store.seq(),
            generation = store.leadership.current.generation, profiles = status.profiles.size, devices = status.devices.count { !it.revoked },
            revisions = status.revisionCount, files = files.mapValues { SyncCrypto.sha256(it.value) }, objects = objects,
            bytes = files.values.sumOf { it.size.toLong() } + objects.sumOf { store.content.fileOf(it).length() },
        )
        val salt = SyncCrypto.token(16)
        val prefix = SyncCrypto.token(4)
        val key = SyncCrypto.stretch(password, salt, ITERATIONS)
        val header = Header(FORMAT, salt, ITERATIONS, prefix, manifest.createdAt, manifest.hostName, check(key))
        val headerLine = (MAGIC + "\n" + json.encodeToString(Header.serializer(), header) + "\n").toByteArray()
        val tmp = File(out.parentFile, ".${out.name}.tmp")
        try {
            tmp.outputStream().buffered().use { raw ->
                raw.write(headerLine)
                SealedOut(raw, key, SyncCrypto.decode(prefix), headerLine).use { sealed ->
                    ZipOutputStream(sealed).use { zip ->
                        zip.putNextEntry(ZipEntry(MANIFEST))
                        zip.write(json.encodeToString(RecoveryManifest.serializer(), manifest).toByteArray())
                        zip.closeEntry()
                        val total = (files.size + objects.size).toLong()
                        var done = 0L
                        for ((path, bytes) in files) {
                            zip.putNextEntry(ZipEntry("household/$path"))
                            zip.write(bytes)
                            zip.closeEntry()
                            onProgress(++done, total)
                        }
                        for (h in objects) {
                            zip.putNextEntry(ZipEntry("objects/$h"))
                            store.content.fileOf(h).inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            onProgress(++done, total)
                        }
                    }
                }
            }
            if (!tmp.renameTo(out)) {
                out.delete()
                check(tmp.renameTo(out)) { "Couldn't write ${out.path}" }
            }
        } finally {
            tmp.delete()
        }
        return manifest
    }

    /** The name and date in [file]'s header (readable without the password); null when it isn't a recovery package. */
    fun peek(file: File): Pair<String, Long>? = runCatching {
        file.inputStream().buffered().use { input -> readHeader(input).let { (h, _) -> h.hostName to h.createdAt } }
    }.getOrNull()

    /**
     * Reads [file] whole with [password], each of the saves' files into [objectsDir], and checks
     * everything against its manifest. Throws [WrongPassword] for a password that doesn't open it
     * and [Damaged] for a package changed or cut off since it was made.
     */
    fun open(file: File, password: String, objectsDir: File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): OpenedPackage {
        file.inputStream().buffered().use { input ->
            val (header, headerLine) = runCatching { readHeader(input) }.getOrElse { throw Damaged("This isn't a Fuse recovery package.") }
            if (header.format > FORMAT) throw Damaged("This package was made by a newer Fuse. Update Fuse to restore it.")
            val key = SyncCrypto.stretch(password, header.salt, header.iterations)
            if (!SyncCrypto.constantEquals(check(key), header.check)) throw WrongPassword()
            objectsDir.mkdirs()
            var manifest: RecoveryManifest? = null
            val files = HashMap<String, ByteArray>()
            val seen = HashSet<String>()
            val sealed = SealedIn(input, key, SyncCrypto.decode(header.prefix), headerLine)
            ZipInputStream(sealed).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    if (unsafe(name)) throw Damaged("The package holds a path it shouldn't ($name).")
                    when {
                        name == MANIFEST -> manifest = json.decodeFromString(RecoveryManifest.serializer(), zip.readBytes().decodeToString())
                        name.startsWith("household/") -> {
                            val m = manifest ?: throw Damaged("The package's contents come before its list of them.")
                            val path = name.removePrefix("household/")
                            val bytes = zip.readBytes()
                            if (m.files[path] != SyncCrypto.sha256(bytes)) throw Damaged("$path isn't as it was when the package was made.")
                            files[path] = bytes
                            onProgress((files.size + seen.size).toLong(), (m.files.size + m.objects.size).toLong())
                        }
                        name.startsWith("objects/") -> {
                            val h = name.removePrefix("objects/")
                            val target = File(objectsDir, h)
                            val digest = MessageDigest.getInstance("SHA-256")
                            target.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(buf)
                                    if (n < 0) break
                                    digest.update(buf, 0, n)
                                    out.write(buf, 0, n)
                                }
                            }
                            val got = digest.digest().joinToString("") { "%02x".format(it) }
                            if (got != h) throw Damaged("A save's file isn't as it was when the package was made.")
                            seen += h
                            manifest?.let { m -> onProgress((files.size + seen.size).toLong(), (m.files.size + m.objects.size).toLong()) }
                        }
                        else -> throw Damaged("The package holds something it shouldn't ($name).")
                    }
                }
                // Read on to its marked last piece, so a package cut off after its contents is found too.
                val rest = ByteArray(8192)
                while (sealed.read(rest, 0, rest.size) >= 0) Unit
            }
            val m = manifest ?: throw Damaged("The package has no list of what it holds.")
            if (m.files.keys != files.keys) throw Damaged("${(m.files.keys - files.keys).size} of the household's files are missing from the package.")
            if (m.objects.toSet() != seen) throw Damaged("${(m.objects.toSet() - seen).size} of the saves' files are missing from the package.")
            return OpenedPackage(m, files, objectsDir)
        }
    }

    /** A path that could leave the folder it is put in. */
    private fun unsafe(path: String): Boolean =
        path.isEmpty() || path.startsWith("/") || '\\' in path || ':' in path || path.split('/').any { it == ".." || it == "." }

    private fun readHeader(input: InputStream): Pair<Header, ByteArray> {
        fun line(): String {
            val b = java.io.ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) throw EOFException()
                if (c == '\n'.code) break
                b.write(c)
                if (b.size() > 4096) throw IOException("Header too long")
            }
            return b.toString(Charsets.UTF_8)
        }
        val magic = line()
        if (magic != MAGIC) throw IOException("Not a recovery package")
        val text = line()
        val header = json.decodeFromString(Header.serializer(), text)
        return header to (magic + "\n" + text + "\n").toByteArray()
    }

    /** The password doesn't open the package. */
    class WrongPassword : IOException("That password doesn't open this package.")

    /** The package was changed or cut off since it was made, or isn't one. */
    class Damaged(message: String) : IOException(message)

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray =
        ByteArray(12).also { n -> prefix.copyInto(n, 0, 0, 4); for (i in 0 until 8) n[4 + i] = (counter ushr (8 * (7 - i))).toByte() }

    private fun aad(header: ByteArray, counter: Long, last: Boolean): ByteArray =
        header + ByteArray(9).also { a -> for (i in 0 until 8) a[i] = (counter ushr (8 * (7 - i))).toByte(); a[8] = if (last) 1 else 0 }

    /** Seals what is written in numbered pieces, the last marked as last on [close]. */
    private class SealedOut(out: OutputStream, private val key: ByteArray, private val prefix: ByteArray, private val header: ByteArray) : FilterOutputStream(out) {
        private val buf = ByteArray(CHUNK)
        private var len = 0
        private var counter = 0L
        private val data = DataOutputStream(out)

        override fun write(b: Int) {
            if (len == CHUNK) seal(false)
            buf[len++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, n: Int) {
            var o = off
            var left = n
            while (left > 0) {
                if (len == CHUNK) seal(false)
                val take = minOf(left, CHUNK - len)
                b.copyInto(buf, len, o, o + take)
                len += take; o += take; left -= take
            }
        }

        private fun seal(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, counter)))
            c.updateAAD(aad(header, counter, last))
            val sealed = c.doFinal(buf, 0, len)
            data.writeByte(if (last) 1 else 0)
            data.writeInt(sealed.size)
            data.write(sealed)
            counter++
            len = 0
        }

        private var closed = false

        override fun flush() = data.flush()

        override fun close() {
            // Closed once: the zip closes it, and so does the block that made it.
            if (closed) return
            closed = true
            seal(true)
            data.flush()
            super.close()
        }
    }

    /** Opens [SealedOut]'s pieces in order, refusing any changed, moved or missing, and a stream cut off before its last. */
    private class SealedIn(input: InputStream, private val key: ByteArray, private val prefix: ByteArray, private val header: ByteArray) : InputStream() {
        private val data = DataInputStream(input)
        private var buf = ByteArray(0)
        private var pos = 0
        private var counter = 0L
        private var ended = false

        private fun next(): Boolean {
            if (ended) return false
            val last = try { data.readByte().toInt() == 1 } catch (e: EOFException) { throw Damaged("The package is cut off.") }
            val size = try { data.readInt() } catch (e: EOFException) { throw Damaged("The package is cut off.") }
            if (size !in 16..(CHUNK + 16)) throw Damaged("The package is damaged.")
            val sealed = try { ByteArray(size).also { data.readFully(it) } } catch (e: EOFException) { throw Damaged("The package is cut off.") }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, counter)))
            c.updateAAD(aad(header, counter, last))
            // The password was checked against the header already: a piece that won't open was changed.
            buf = try { c.doFinal(sealed) } catch (e: AEADBadTagException) { throw Damaged("The package was changed since it was made.") }
            pos = 0
            counter++
            if (last) ended = true
            return true
        }

        override fun read(): Int {
            while (pos >= buf.size) if (!next()) return -1
            return buf[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= buf.size) if (!next()) return -1
            val n = minOf(len, buf.size - pos)
            buf.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
    }
}
