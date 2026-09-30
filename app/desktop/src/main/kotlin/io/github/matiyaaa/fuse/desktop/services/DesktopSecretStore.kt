package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.Processes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Credentials on Linux. The desktop keyring (Secret Service, through the `secret-tool` CLI from
 * libsecret) is used whenever it answers; otherwise [EncryptedFileSecretStore] keeps them. Values are
 * never logged and never passed on a command line (secret-tool reads them from stdin).
 */
internal class DesktopSecretStore(dataDir: String) : SecretStore {
    private val keyring = SecretToolStore()
    private val file = EncryptedFileSecretStore(File(dataDir))

    override suspend fun get(key: String): String? =
        (if (keyring.available) keyring.lookup(key) else null) ?: file.get(key)

    override suspend fun put(key: String, value: String) {
        if (keyring.available && keyring.store(key, value)) {
            // The keyring has it now; drop any older copy from the fallback file.
            file.remove(key)
        } else {
            file.put(key, value)
        }
    }

    override suspend fun remove(key: String) {
        if (keyring.available) keyring.clear(key)
        file.remove(key)
    }
}

/** Secret Service through `secret-tool` (attributes `application=fuse key=<key>`). */
internal class SecretToolStore {
    private val tool: String? = Processes.which("secret-tool")
    val available: Boolean get() = tool != null

    suspend fun lookup(key: String): String? = withContext(Dispatchers.IO) {
        val t = tool ?: return@withContext null
        val out = Processes.run(listOf(t, "lookup", "application", APP, "key", key), timeoutMs = TIMEOUT_MS) ?: return@withContext null
        if (out.exitCode != 0) null else out.stdout.takeIf { it.isNotEmpty() }
    }

    suspend fun store(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val t = tool ?: return@withContext false
        val out = Processes.run(
            listOf(t, "store", "--label=Fuse $key", "application", APP, "key", key),
            timeoutMs = TIMEOUT_MS,
            stdin = value,
        )
        val ok = out?.exitCode == 0
        if (!ok) Log.info("the desktop keyring did not accept a credential; using Fuse's encrypted file instead")
        ok
    }

    suspend fun clear(key: String): Boolean = withContext(Dispatchers.IO) {
        val t = tool ?: return@withContext false
        Processes.run(listOf(t, "clear", "application", APP, "key", key), timeoutMs = TIMEOUT_MS)?.exitCode == 0
    }

    private companion object {
        const val APP = "fuse"
        // Unlocking a keyring can show a prompt; give the user time, but never hang forever.
        const val TIMEOUT_MS = 20_000L
    }
}

/**
 * Fallback when no keyring is available: an AES-GCM encrypted JSON map in `secrets.bin`, with a
 * random 256-bit key in `secrets.key`, both readable by the user only (0600).
 *
 * This protects against casual reading only (someone opening the file, a backup tool or a file
 * search showing the values). The key sits next to the data, so anyone who can read the user's files
 * can decrypt them. The desktop keyring is used whenever it is available.
 */
internal class EncryptedFileSecretStore(private val dir: File) {
    private val keyFile = File(dir, "secrets.key")
    private val dataFile = File(dir, "secrets.bin")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val random = SecureRandom()

    suspend fun get(key: String): String? = mutex.withLock { withContext(Dispatchers.IO) { load()[key] } }

    suspend fun put(key: String, value: String) = mutex.withLock {
        withContext(Dispatchers.IO) { save(load() + (key to value)) }
    }

    suspend fun remove(key: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!dataFile.exists()) return@withContext
            val current = load()
            if (key in current) save(current - key)
        }
    }

    private fun load(): Map<String, String> {
        if (!dataFile.isFile || !keyFile.isFile) return emptyMap()
        return try {
            val bytes = dataFile.readBytes()
            if (bytes.size < MAGIC.size + IV_BYTES || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return emptyMap()
            val iv = bytes.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyFile.readBytes(), "AES"), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(bytes, MAGIC.size + IV_BYTES, bytes.size - MAGIC.size - IV_BYTES)
            json.decodeFromString(serializer, String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.warn("Fuse's credential file could not be read; credentials need to be entered again")
            emptyMap()
        }
    }

    private fun save(values: Map<String, String>) {
        dir.mkdirs()
        val key = keyOrCreate()
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(json.encodeToString(serializer, values).toByteArray(Charsets.UTF_8))
        val tmp = File(dir, "secrets.bin.tmp")
        writePrivate(tmp, MAGIC + iv + sealed)
        try {
            Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun keyOrCreate(): ByteArray {
        if (keyFile.isFile) {
            val bytes = keyFile.readBytes()
            if (bytes.size == KEY_BYTES) return bytes
        }
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        writePrivate(keyFile, key)
        return key
    }

    /** Writes [bytes] to a file that is created with mode 0600 (never briefly readable by others). */
    private fun writePrivate(file: File, bytes: ByteArray) {
        val path = file.toPath()
        Files.deleteIfExists(path)
        val attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        Files.newByteChannel(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attrs).use { ch ->
            ch.write(java.nio.ByteBuffer.wrap(bytes))
        }
    }

    private companion object {
        val MAGIC = "FUSESEC1".toByteArray(Charsets.US_ASCII)
        const val IV_BYTES = 12
        const val KEY_BYTES = 32
    }
}
