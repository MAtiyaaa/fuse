package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.desktop.BuildInfo
import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.integrations.github.ReleasePlatform
import io.github.matiyaaa.fuse.integrations.github.ResumableDownload
import io.github.matiyaaa.fuse.model.ReleaseAsset
import io.github.matiyaaa.fuse.ui.shell.store.PreviousRelease
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseInstaller
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
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

    /** A helper installs the update once Fuse closes and starts it again (Windows): a restart only closes Fuse. */
    @Volatile
    var helperRestarts: Boolean = false
}

/**
 * Installs AppImages (Fuse updates and Cartridge) for the current user: downloads into
 * `~/.cache/fuse/updates/<name>.part`, carrying on an interrupted download where it stopped
 * ([ResumableDownload]), and checks the published sha256 digest.
 *
 * A Fuse update replaces the AppImage Fuse runs from, at the same path, so shortcuts, the login
 * entry and Steam's Non-Steam entry keep working: the running copy goes on from the file it has
 * open, the restart starts the new one, the one before is kept beside it as `.<name>.previous` (one
 * step back, with its version in `.<name>.previous.info`), and the numbered copies older updates
 * left beside it are removed. Without a running AppImage (an installed or development run) it goes
 * to `~/Applications/Fuse.AppImage` the same way. The new version is on trial until it has been up a
 * while ([UpdateTrial]); [goBack] returns to the one before at any time.
 *
 * Other AppImages (Cartridge) are placed beside it and never replace a file: an identical one counts
 * as installed, a different one gets a numbered name.
 */
internal class DesktopReleaseInstaller(
    private val dirs: FuseDirs,
    private val http: HttpClient,
    /** The AppImage Fuse runs from, if it does. */
    private val running: () -> String? = { System.getenv("APPIMAGE") },
    /** This Fuse's version and the library form it reads, kept with the copy an update replaces. */
    private val version: String = BuildInfo.VERSION,
    private val schema: Long = FuseDatabase.Schema.version,
    private val now: () -> Long = System::currentTimeMillis,
) : ReleaseInstaller {
    override val platform: ReleasePlatform = ReleasePlatform.LINUX_X86_64
    private val trial = UpdateTrial(File(dirs.data, UpdateTrial.FILE))

    /** The version an update replaced here, while its AppImage is kept beside this one. */
    override fun previous(): PreviousRelease? {
        val target = running()?.let(::File)?.takeIf { it.isFile } ?: return null
        if (!UpdateTrial.previousOf(target).isFile) return null
        val info = PreviousFuse.read(UpdateTrial.infoOf(target)) ?: return null
        // Its library is the copy made before this version updated it (0: not known, so say it may).
        return PreviousRelease(info.version, libraryGoesBack = info.schema < schema, since = info.replacedAt)
    }

    /**
     * Swaps this AppImage and the one kept before it, so the restart starts that one; this one is
     * kept in its place, to come back to the same way.
     */
    override suspend fun goBack(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val target = running()?.let(::File)?.takeIf { it.isFile } ?: error("Fuse isn't running from an AppImage it can go back from.")
            val previous = UpdateTrial.previousOf(target)
            if (!previous.isFile || PreviousFuse.read(UpdateTrial.infoOf(target)) == null) error("The version before this one isn't kept any more.")
            val swap = File(target.parentFile, ".${target.name}.swap")
            // The running copy keeps its open file; only names move.
            Files.move(target.toPath(), swap.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(previous.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(swap.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                throw e
            }
            Files.move(swap.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
            PreviousFuse(version, schema, now()).write(UpdateTrial.infoOf(target))
            trial.end()
            UpdateHandoff.installedFuseAppImage = target.path
            true
        }
    }

    /** What Fuse says once at start about an update: one it went back from, or the library brought back. */
    override fun takeStartupNote(): String? {
        val note = File(dirs.data, UpdateTrial.NOTE)
        val text = runCatching { note.readText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        note.delete()
        // What a restart of Steam from inside it did, left by its helper (see SteamApplyMain).
        val steamNote = File(dirs.data, io.github.matiyaaa.fuse.desktop.platform.SteamApplyMain.NOTE)
        val steam = runCatching { steamNote.readText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        steamNote.delete()
        return listOfNotNull(text, takeLibraryNote(), steam).joinToString(" ").ifEmpty { null }
    }

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
            val sha256 = ResumableDownload.fetch(http, asset.url, part, asset.sizeBytes, "${asset.url} ${asset.sizeBytes} ${asset.digest.orEmpty()}", onProgress)
            asset.digest?.let { expected ->
                val (algo, hex) = expected.split(':', limit = 2).let { if (it.size == 2) it[0] to it[1] else "" to "" }
                if (algo.equals("sha256", ignoreCase = true) && !hex.equals(sha256, ignoreCase = true)) {
                    ResumableDownload.forget(part)
                    error("The download of $name did not match its published checksum. Nothing was installed.")
                }
            }
            val fuse = name.lowercase(Locale.ROOT).startsWith("fuse")
            val target = if (fuse) replaceFuse(part, VERSION_IN_NAME.find(name)?.groupValues?.get(1)) else place(part, name, sha256)
            ResumableDownload.finished(part)
            if (fuse) UpdateHandoff.installedFuseAppImage = target.path
            Log.info("installed $name to ${target.parent}")
            onProgress(1f)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Puts the verified Fuse download at the path Fuse runs from (or `~/Applications/Fuse.AppImage`),
     * keeping the one before as `.<name>.previous` with what it is, putting [newVersion] on trial,
     * and clearing the versioned copies earlier updates left beside it. Returns where it went.
     */
    internal fun replaceFuse(part: File, newVersion: String? = null): File {
        val current = running()?.let(::File)?.takeIf { it.isFile && it.parentFile?.canWrite() == true }
        val target = current ?: File(File(dirs.home, "Applications"), "Fuse.AppImage")
        val folder = target.parentFile
        folder.mkdirs()
        if (!folder.isDirectory || !folder.canWrite()) throw IOException("Can't write to ${folder.path}.")
        // Copied next to the target first (the cache may be another file system), so the swap is a rename.
        val staging = File(folder, ".${target.name}.part")
        val previous = UpdateTrial.previousOf(target)
        val info = UpdateTrial.infoOf(target)
        val replacing = target.isFile
        try {
            Files.move(part.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
            makeExecutable(staging)
            if (target.exists()) {
                // The running copy keeps its open file; only its name moves aside.
                Files.move(target.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                // Put the old one back, so Fuse is never left without its AppImage.
                if (previous.exists() && !target.exists()) Files.move(previous.toPath(), target.toPath())
                throw e
            }
        } catch (e: IOException) {
            staging.delete()
            throw e
        }
        if (replacing) {
            // What the copy kept is, for going back to it; and the new one is on trial until it has been up a while.
            PreviousFuse(version, schema, now()).write(info)
            trial.begin(target.path, newVersion.orEmpty(), version)
        } else {
            // Nothing was replaced here: a copy left from before isn't this one's to go back to.
            previous.delete()
            info.delete()
            trial.end()
        }
        // Copies earlier updates placed beside it ("Fuse-0.3.6.2-x86_64.AppImage", "...-2.AppImage").
        folder.listFiles()?.filter { it.isFile && it != target && OLD_COPY.matches(it.name) }?.forEach { old ->
            if (!old.delete()) Log.warn("could not remove ${old.name}")
        }
        return target
    }

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
        // A file system without POSIX permissions (Windows, a FAT drive) has only the owner's flag.
        if (!file.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
            file.setExecutable(true)
            return
        }
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

    private companion object {
        /** A Fuse AppImage an earlier update placed under its versioned name. */
        val OLD_COPY = Regex("""Fuse-\d+(\.\d+){2,3}-x86_64(-\d+)?\.AppImage""")

        /** The version a Fuse release's AppImage names (`Fuse-0.4.3-x86_64.AppImage`). */
        val VERSION_IN_NAME = Regex("""^Fuse-(\d+(?:\.\d+){1,3})-""", RegexOption.IGNORE_CASE)
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() && '/' !in name && '\\' !in name && !name.startsWith('.') && name.length <= 200 && name.none { it.code < 32 }
}

/**
 * Windows and macOS: Fuse doesn't replace itself there. The update shows the version, and getting it
 * opens the release page, where the installer (or disk image) is.
 */
internal class PageReleaseInstaller(override val platform: ReleasePlatform) : ReleaseInstaller {
    // An older Fuse installed over a newer one brings back the library from before the newer one updated it.
    override fun takeStartupNote(): String? = takeLibraryNote()

    override val inPlace: Boolean get() = false

    override suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit): Result<Unit> =
        Result.failure(UnsupportedOperationException("Get the new version from its release page."))

    companion object {
        /** The build this Mac or PC takes. */
        fun platformFor(os: DesktopOs): ReleasePlatform = when (os) {
            DesktopOs.WINDOWS -> ReleasePlatform.WINDOWS_X64
            DesktopOs.MACOS -> if (System.getProperty("os.arch").orEmpty().let { it == "aarch64" || it == "arm64" }) ReleasePlatform.MACOS_ARM64 else ReleasePlatform.MACOS_X64
            DesktopOs.LINUX -> ReleasePlatform.LINUX_X86_64
        }
    }
}

/** What happened when the library a newer Fuse had updated was opened here (DesktopDatabase), once. */
private fun takeLibraryNote(): String? =
    io.github.matiyaaa.fuse.data.db.DesktopDatabase.restoredNote.also { io.github.matiyaaa.fuse.data.db.DesktopDatabase.restoredNote = null }
