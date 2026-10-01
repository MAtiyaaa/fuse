package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.PowerShell
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
 * Credentials on the desktop. The system keyring is used whenever it answers: Secret Service through
 * `secret-tool` on Linux, the login Keychain through `security` on macOS. Otherwise, and on Windows,
 * [EncryptedFileSecretStore] keeps them; on Windows its key is sealed with DPAPI for the signed-in
 * user. Values are never logged and never passed on a command line (they go through stdin).
 */
internal class DesktopSecretStore(dataDir: String, os: DesktopOs = DesktopOs.current) : SecretStore {
    private val keyring: Keyring = when (os) {
        DesktopOs.LINUX -> SecretToolStore()
        DesktopOs.MACOS -> KeychainStore()
        DesktopOs.WINDOWS -> Keyring.None
    }
    private val file = EncryptedFileSecretStore(File(dataDir), if (os == DesktopOs.WINDOWS) DpapiKeyProtector else KeyProtector.None)

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

/** A system credential store. */
internal interface Keyring {
    val available: Boolean
    suspend fun lookup(key: String): String?
    suspend fun store(key: String, value: String): Boolean
    suspend fun clear(key: String): Boolean

    object None : Keyring {
        override val available = false
        override suspend fun lookup(key: String): String? = null
        override suspend fun store(key: String, value: String) = false
        override suspend fun clear(key: String) = false
    }
}

/** Secret Service through `secret-tool` (attributes `application=fuse key=<key>`). */
internal class SecretToolStore : Keyring {
    private val tool: String? = Processes.which("secret-tool")
    override val available: Boolean get() = tool != null

    override suspend fun lookup(key: String): String? = withContext(Dispatchers.IO) {
        val t = tool ?: return@withContext null
        val out = Processes.run(listOf(t, "lookup", "application", APP, "key", key), timeoutMs = TIMEOUT_MS) ?: return@withContext null
        if (out.exitCode != 0) null else out.stdout.takeIf { it.isNotEmpty() }
    }

    override suspend fun store(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
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

    override suspend fun clear(key: String): Boolean = withContext(Dispatchers.IO) {
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
internal class EncryptedFileSecretStore(private val dir: File, private val protector: KeyProtector = KeyProtector.None) {
    private val keyFile = File(dir, "secrets.key")
    private val dataFile = File(dir, "secrets.bin")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val random = SecureRandom()

    /** The opened key, kept once read (opening it can take a moment on Windows). */
    @Volatile private var openedKey: ByteArray? = null

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
            val key = readKey() ?: return emptyMap()
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
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

    /** The key from `secrets.key`: plain, or sealed by [protector] (marked with [SEALED]). */
    private fun readKey(): ByteArray? {
        openedKey?.let { return it }
        if (!keyFile.isFile) return null
        val bytes = keyFile.readBytes()
        val key = when {
            bytes.size == KEY_BYTES -> bytes
            bytes.size > SEALED.size && bytes.copyOfRange(0, SEALED.size).contentEquals(SEALED) ->
                protector.open(bytes.copyOfRange(SEALED.size, bytes.size))?.takeIf { it.size == KEY_BYTES }
            else -> null
        }
        openedKey = key
        return key
    }

    private fun keyOrCreate(): ByteArray {
        readKey()?.let { return it }
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        val sealed = protector.seal(key)
        writePrivate(keyFile, if (sealed != null) SEALED + sealed else key)
        openedKey = key
        return key
    }

    /**
     * Writes [bytes] to a file that is created with mode 0600 (never briefly readable by others).
     * Windows has no such modes; files in the user's AppData are already the user's alone.
     */
    private fun writePrivate(file: File, bytes: ByteArray) {
        val path = file.toPath()
        Files.deleteIfExists(path)
        val posix = path.fileSystem.supportedFileAttributeViews().contains("posix")
        val attrs = if (posix) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
        Files.newByteChannel(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), *attrs).use { ch ->
            ch.write(java.nio.ByteBuffer.wrap(bytes))
        }
    }

    private companion object {
        val MAGIC = "FUSESEC1".toByteArray(Charsets.US_ASCII)
        val SEALED = "FUSEKEY1".toByteArray(Charsets.US_ASCII)
        const val IV_BYTES = 12
        const val KEY_BYTES = 32
    }
}

/** Seals the file store's key for the signed-in user, where the system can. */
internal interface KeyProtector {
    fun seal(key: ByteArray): ByteArray?
    fun open(sealed: ByteArray): ByteArray?

    object None : KeyProtector {
        override fun seal(key: ByteArray): ByteArray? = null
        override fun open(sealed: ByteArray): ByteArray? = null
    }
}

/**
 * Windows DPAPI (`ProtectedData`, CurrentUser scope) through Windows PowerShell: only the same user
 * on the same computer can open the key. The bytes go through stdin as base64.
 */
internal object DpapiKeyProtector : KeyProtector {
    private fun run(method: String, input: ByteArray): ByteArray? {
        val script = """
            Add-Type -AssemblyName System.Security
            ${'$'}b = [Convert]::FromBase64String([Console]::In.ReadToEnd().Trim())
            ${'$'}o = [System.Security.Cryptography.ProtectedData]::$method(${'$'}b, ${'$'}null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
            [Console]::Out.Write([Convert]::ToBase64String(${'$'}o))
        """.trimIndent()
        val out = PowerShell.run(script, stdin = java.util.Base64.getEncoder().encodeToString(input), timeoutMs = 20_000) ?: return null
        if (out.exitCode != 0) return null
        return try {
            java.util.Base64.getDecoder().decode(out.stdout.trim())
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun seal(key: ByteArray): ByteArray? = run("Protect", key)

    override fun open(sealed: ByteArray): ByteArray? = run("Unprotect", sealed)
}

/**
 * The macOS login Keychain through `security` (service "Fuse", account = the key). Values are
 * stored base64 encoded and handed over as hex through `security -i`'s stdin, so they never show up
 * in a process list.
 */
internal class KeychainStore : Keyring {
    private val tool: String? = "/usr/bin/security".takeIf { File(it).canExecute() }
    override val available: Boolean get() = tool != null

    override suspend fun lookup(key: String): String? = withContext(Dispatchers.IO) {
        val t = tool ?: return@withContext null
        val out = Processes.run(listOf(t, "find-generic-password", "-s", SERVICE, "-a", key, "-w"), timeoutMs = TIMEOUT_MS) ?: return@withContext null
        if (out.exitCode != 0) return@withContext null
        try {
            String(java.util.Base64.getDecoder().decode(out.stdout.trim()), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun store(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val t = tool ?: return@withContext false
        if (key.any { it == '"' || it == '\\' || it.isWhitespace() }) return@withContext false
        val encoded = java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val hex = encoded.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
        Processes.run(listOf(t, "-i"), timeoutMs = TIMEOUT_MS, stdin = "add-generic-password -U -s $SERVICE -a \"$key\" -X $hex\n")
        // security -i reports success either way; reading it back is the real check.
        val ok = lookup(key) == value
        if (!ok) Log.info("the Keychain did not accept a credential; using Fuse's encrypted file instead")
        ok
    }

    override suspend fun clear(key: String): Boolean = withContext(Dispatchers.IO) {
        val t = tool ?: return@withContext false
        Processes.run(listOf(t, "delete-generic-password", "-s", SERVICE, "-a", key), timeoutMs = TIMEOUT_MS)?.exitCode == 0
    }

    private companion object {
        const val SERVICE = "Fuse"
        const val TIMEOUT_MS = 20_000L
    }
}
