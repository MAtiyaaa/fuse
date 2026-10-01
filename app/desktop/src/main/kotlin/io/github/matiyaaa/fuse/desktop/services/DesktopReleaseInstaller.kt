package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.integrations.github.ReleasePlatform
import io.github.matiyaaa.fuse.model.ReleaseAsset
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseInstaller
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Locale

/** The most recent Fuse AppImage this session installed; restart starts it instead of the old one. */
internal object UpdateHandoff {
    @Volatile
    var installedFuseAppImage: String? = null
}

/**
 * Installs AppImages (Fuse updates and Cartridge) for the current user: downloads into
 * `~/.cache/fuse/updates/<name>.part`, checks the published sha256 digest, then places the file next
 * to the running AppImage (or in `~/Applications`) and marks it executable. An existing file is never
 * replaced or deleted: an identical one counts as installed, a different one gets a numbered name.
 */
internal class DesktopReleaseInstaller(private val dirs: FuseDirs, private val http: HttpClient) : ReleaseInstaller {
    override val platform: ReleasePlatform = ReleasePlatform.LINUX_X86_64

    /** Downloads, verifies and places the new AppImage; [applyUpdate] then restarts into it. */
    override suspend fun download(asset: ReleaseAsset, onProgress: (Float) -> Unit): Result<String> =
        install(asset, onProgress).mapCatching {
            UpdateHandoff.installedFuseAppImage ?: error("The new AppImage could not be placed.")
        }

    override suspend fun applyUpdate(downloaded: String): Result<Boolean> =
        if (File(downloaded).canExecute()) Result.success(true) else Result.failure(IOException("The new AppImage is gone. Download the update again."))

    override suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val name = asset.name
            require(isSafeName(name) && name.lowercase(Locale.ROOT).endsWith(".appimage")) { "$name is not an AppImage." }
            val updates = File(dirs.cache, "updates").apply { mkdirs() }
            val part = File(updates, "$name.part")
            val sha256 = download(asset, part, onProgress)
            asset.digest?.let { expected ->
                val (algo, hex) = expected.split(':', limit = 2).let { if (it.size == 2) it[0] to it[1] else "" to "" }
                if (algo.equals("sha256", ignoreCase = true) && !hex.equals(sha256, ignoreCase = true)) {
                    part.delete()
                    error("The download of $name did not match its published checksum. Nothing was installed.")
                }
            }
            val target = place(part, name, sha256)
            if (name.lowercase(Locale.ROOT).startsWith("fuse")) UpdateHandoff.installedFuseAppImage = target.path
            Log.info("installed $name to ${target.parent}")
            onProgress(1f)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Streams [asset] into [part] and returns its sha256 as lower-case hex. */
    private suspend fun download(asset: ReleaseAsset, part: File, onProgress: (Float) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        http.prepareGet(asset.url) {
            // Large files: no overall deadline, but a stalled connection still times out.
            timeout {
                requestTimeoutMillis = 60 * 60 * 1000L
                socketTimeoutMillis = 60_000L
            }
        }.execute { response ->
            if (!response.status.isSuccess()) throw IOException("Download failed (HTTP ${response.status.value}).")
            val total = response.contentLength()?.takeIf { it > 0 } ?: asset.sizeBytes.takeIf { it > 0 }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(128 * 1024)
            var done = 0L
            var lastReported = -1
            part.outputStream().use { out ->
                while (true) {
                    ensureActiveHere()
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    if (total != null) {
                        val pct = (done * 100 / total).toInt().coerceIn(0, 99)
                        if (pct != lastReported) {
                            lastReported = pct
                            onProgress(pct / 100f)
                        }
                    }
                }
            }
            if (total != null && response.contentLength() != null && done != total) {
                throw IOException("The download ended early. Nothing was installed.")
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun ensureActiveHere() = kotlin.coroutines.coroutineContext.ensureActive()

    /** Moves the verified download to its install folder without overwriting anything. */
    private fun place(part: File, name: String, sha256: String): File {
        val folder = installFolder()
        folder.mkdirs()
        if (!folder.isDirectory || !folder.canWrite()) throw IOException("Can't write to ${folder.path}.")
        val stem = name.substring(0, name.length - ".AppImage".length)
        val ext = name.substring(stem.length)
        var target = File(folder, name)
        var n = 2
        while (target.exists()) {
            if (target.isFile && sha256Of(target) == sha256) {
                part.delete()
                makeExecutable(target)
                return target
            }
            target = File(folder, "$stem-$n$ext")
            n++
        }
        // Copy next to the target first (the cache may be another file system), then rename atomically.
        val staging = File(folder, ".${target.name}.part")
        try {
            Files.move(part.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
            makeExecutable(staging)
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(staging.toPath(), target.toPath())
            }
        } catch (e: IOException) {
            // Only Fuse's own temporary file is removed; the target was never touched.
            staging.delete()
            throw e
        }
        return target
    }

    /** Next to the running AppImage when it is writable, else `~/Applications`. */
    private fun installFolder(): File {
        System.getenv("APPIMAGE")?.let { File(it).parentFile }?.takeIf { it.isDirectory && it.canWrite() }?.let { return it }
        return File(dirs.home, "Applications")
    }

    private fun makeExecutable(file: File) {
        val perms = Files.getPosixFilePermissions(file.toPath()).toMutableSet()
        perms += PosixFilePermission.OWNER_EXECUTE
        if (PosixFilePermission.GROUP_READ in perms) perms += PosixFilePermission.GROUP_EXECUTE
        if (PosixFilePermission.OTHERS_READ in perms) perms += PosixFilePermission.OTHERS_EXECUTE
        Files.setPosixFilePermissions(file.toPath(), perms)
    }

    private fun sha256Of(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: IOException) {
        null
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() && '/' !in name && '\\' !in name && !name.startsWith('.') && name.length <= 200 && name.none { it.code < 32 }
}
