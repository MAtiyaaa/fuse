package io.github.matiyaaa.fuse.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import io.github.matiyaaa.fuse.data.settings.SecretStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys and passwords, encrypted with an AES-256-GCM key that never leaves the Android Keystore.
 * Ciphertext and IV are stored base64 in the private `fuse_secrets` preferences file, never in the
 * database. The secret's name is bound as associated data, so a value cannot be moved to another
 * name. Values and ciphertext are never logged.
 */
class KeystoreSecretStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SecretStore {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val mutex = Mutex()

    override suspend fun get(key: String): String? = withContext(io) {
        mutex.withLock {
            val stored = prefs.getString(key, null) ?: return@withLock null
            decrypt(key, stored)
        }
    }

    override suspend fun put(key: String, value: String) = withContext(io) {
        mutex.withLock {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val stored = VERSION + ":" + b64(cipher.iv) + ":" + b64(ciphertext)
            // Not the KTX edit {}: it drops commit()'s result, and a failed save must not look saved.
            @SuppressLint("UseKtx")
            val saved = prefs.edit().putString(key, stored).commit()
            if (!saved) throw IOException("Could not save the credential")
        }
    }

    override suspend fun remove(key: String) = withContext(io) {
        mutex.withLock { prefs.edit(commit = true) { remove(key) } }
    }

    override suspend fun has(key: String): Boolean = get(key) != null

    private fun decrypt(key: String, stored: String): String? {
        val parts = stored.split(':')
        if (parts.size != 3 || parts[0] != VERSION) return null
        return try {
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // The key was lost (restored to another device, lock screen reset): the value is unreadable.
            Log.w(TAG, "A stored credential could not be decrypted (${e.javaClass.simpleName})")
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val TAG = "FuseSecrets"
        const val FILE = "fuse_secrets"
        const val ALIAS = "fuse.secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION = "v1"
    }
}
