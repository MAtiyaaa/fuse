package io.github.matiyaaa.fuse.services

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.integrations.github.ReleasePlatform
import io.github.matiyaaa.fuse.model.ReleaseAsset
import io.github.matiyaaa.fuse.storage.toHex
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseInstaller
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads an APK from a GitHub release into `cache/updates`, checks its `sha256:` digest when
 * GitHub published one, and hands it to the system installer through a [PackageInstaller] session.
 * Android always shows its own confirmation ([InstallResultReceiver] opens it); nothing is ever
 * installed silently.
 */
class AndroidReleaseInstaller(
    context: Context,
    private val http: HttpClient,
    private val activities: ActivityHolder,
) : ReleaseInstaller {
    private val appContext = context.applicationContext
    override val platform: ReleasePlatform = ReleasePlatform.ANDROID

    override suspend fun download(asset: ReleaseAsset, onProgress: (Float) -> Unit): Result<String> {
        val expected = asset.digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.lowercase()
        val dir = File(appContext.cacheDir, "updates")
        val file = File(dir, safeName(asset.name))
        return try {
            withContext(Dispatchers.IO) {
                if (!dir.isDirectory && !dir.mkdirs()) throw InstallException("Fuse couldn't prepare its download folder.")
                // Only the update being downloaded is kept.
                dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
                val actual = download(asset, file, onProgress)
                if (expected != null && expected != actual) {
                    file.delete()
                    throw InstallException("The download didn't match the checksum GitHub published, so it was deleted. Try again.")
                }
            }
            Result.success(file.absolutePath)
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: InstallException) {
            file.delete()
            Result.failure(e)
        } catch (e: IOException) {
            file.delete()
            Result.failure(InstallException("The download failed. Check the connection and try again."))
        }
    }

    override suspend fun applyUpdate(downloaded: String): Result<Boolean> {
        val file = File(downloaded)
        if (!file.isFile) return Result.failure(InstallException("The download is gone. Download the update again."))
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            withContext(Dispatchers.Main) {
                activities.startFirst(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${appContext.packageName}".toUri()),
                    Intent(Settings.ACTION_SECURITY_SETTINGS),
                )
            }
            return Result.failure(InstallException("Allow Fuse to install apps in the screen that just opened, then choose Restart and update again."))
        }
        return try {
            withContext(Dispatchers.IO) { commit(file, keep = true) }
            // Android asks to confirm, installs, and starts Fuse again (as the Home app, at once).
            Result.success(false)
        } catch (e: RuntimeException) {
            Result.failure(InstallException("Android couldn't start the installation."))
        } catch (e: IOException) {
            Result.failure(InstallException("Android couldn't start the installation."))
        }
    }

    /**
     * Hands an APK already on the device (one the user picked) to the system installer. The file is
     * the user's, so it is never deleted. Asks for "install unknown apps" first when needed.
     */
    suspend fun installLocal(file: File): Result<Unit> {
        if (!file.isFile) return Result.failure(InstallException("${file.name} isn't there any more."))
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            withContext(Dispatchers.Main) {
                activities.startFirst(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${appContext.packageName}".toUri()),
                    Intent(Settings.ACTION_SECURITY_SETTINGS),
                )
            }
            return Result.failure(InstallException("Allow Fuse to install apps in the screen that just opened, then pick the APK again."))
        }
        return try {
            withContext(Dispatchers.IO) { commit(file, keep = true) }
            Result.success(Unit)
        } catch (e: RuntimeException) {
            Result.failure(InstallException("Android couldn't start the installation."))
        } catch (e: IOException) {
            Result.failure(InstallException("Fuse couldn't read ${file.name}."))
        }
    }

    override suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit): Result<Unit> {
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            withContext(Dispatchers.Main) {
                activities.startFirst(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${appContext.packageName}".toUri()),
                    Intent(Settings.ACTION_SECURITY_SETTINGS),
                )
            }
            return Result.failure(
                InstallException("Allow Fuse to install apps in the screen that just opened, then try again."),
            )
        }
        val expected = asset.digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.lowercase()
        val dir = File(appContext.cacheDir, "updates")
        val file = File(dir, safeName(asset.name))
        return try {
            withContext(Dispatchers.IO) {
                if (!dir.isDirectory && !dir.mkdirs()) throw InstallException("Fuse couldn't prepare its download folder.")
                val actual = download(asset, file, onProgress)
                if (expected != null && expected != actual) {
                    file.delete()
                    throw InstallException("The download didn't match the checksum GitHub published, so it was deleted. Try again.")
                }
                commit(file)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: InstallException) {
            file.delete()
            Result.failure(e)
        } catch (e: IOException) {
            file.delete()
            Result.failure(InstallException("The download failed. Check the connection and try again."))
        } catch (e: RuntimeException) {
            file.delete()
            Result.failure(InstallException("Android couldn't start the installation."))
        }
    }

    /** Streams [asset] into [file] and returns the lower-case hex SHA-256 of what was written. */
    private suspend fun download(asset: ReleaseAsset, file: File, onProgress: (Float) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        http.prepareGet(asset.url) {
            timeout {
                requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS
                socketTimeoutMillis = 60_000
            }
        }.execute { response ->
            if (!response.status.isSuccess()) throw InstallException("GitHub answered ${response.status.value} for the download.")
            val total = response.contentLength()?.takeIf { it > 0 } ?: asset.sizeBytes.takeIf { it > 0 }
            var written = 0L
            var lastReported = -1
            onProgress(0f)
            response.bodyAsChannel().toInputStream().use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        written += n
                        if (total != null) {
                            val percent = (written * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent / 100f)
                            }
                        }
                        kotlin.coroutines.coroutineContext.ensureActive()
                    }
                    output.fd.sync()
                }
            }
            if (total != null && written != total) throw InstallException("The download was incomplete. Try again.")
            onProgress(1f)
        }
        return digest.digest().toHex()
    }

    private fun commit(file: File, keep: Boolean = false) {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Always ask the user, even for Fuse's own updates.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val callback = Intent(appContext, InstallResultReceiver::class.java).setPackage(appContext.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(appContext, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (ignored: RuntimeException) {
                // Already gone.
            }
            throw e
        } finally {
            // The session holds its own copy now. A Fuse update keeps its download, so a dismissed
            // confirmation can be tried again; the next download replaces it.
            if (!keep) file.delete()
        }
    }

    private fun safeName(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.isLetterOrDigit() || it in "._-" }
            .trimStart('.')
        return cleaned.ifEmpty { "update.apk" }
    }

    class InstallException(message: String) : Exception(message)

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 60L * 60 * 1000
    }
}
