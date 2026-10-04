package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.fusePath
import io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssetKind
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.ui.shell.store.DesktopInstaller
import io.github.matiyaaa.fuse.ui.shell.store.DownloadSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Puts programs the Store fetched where Fuse looks for emulators, as their developers published
 * them: an AppImage, made runnable, in ~/Applications (Linux); a portable zip unpacked into its own
 * folder in ~/Emulators (Windows); an app copied out of its disk image or zip into ~/Applications
 * (macOS, with the system's own ditto and hdiutil so the app stays signed and whole). No installer
 * is run and nothing is repackaged. Only paths below [folder] are ever replaced or removed.
 */
internal class DesktopStoreInstaller(
    private val os: DesktopOs,
    private val home: File,
    private val downloads: File,
) : DesktopInstaller {
    override val host: Host = os.host
    override val arch: String = System.getProperty("os.arch").orEmpty().lowercase().let { if ("aarch64" in it || "arm" in it) "arm64" else "x86_64" }

    private val root: File = when (os) {
        DesktopOs.WINDOWS -> File(home, "Emulators")
        else -> File(home, "Applications")
    }
    override val folder: String = root.fusePath

    override suspend fun newDownload(fileName: String): DownloadSink? = withContext(Dispatchers.IO) {
        if (!downloads.isDirectory && !downloads.mkdirs()) return@withContext null
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val file = File(downloads, "${System.nanoTime()}-$safe")
        FileSink(file)
    }

    override fun freeBytes(): Long? = downloads.let { d -> generateSequence(d) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace?.takeIf { it > 0 } }

    override suspend fun install(name: String, file: String, fileName: String, kind: DesktopAssetKind, previous: String?): String = withContext(Dispatchers.IO) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Fuse couldn't make $folder.")
        val download = File(file)
        val placed = when (kind) {
            DesktopAssetKind.APPIMAGE -> placeAppImage(download, fileName)
            DesktopAssetKind.ZIP -> when (os) {
                DesktopOs.LINUX -> appImageFromZip(download)
                DesktopOs.WINDOWS -> unpackWindows(download, name)
                DesktopOs.MACOS -> appFromZip(download)
            }
            DesktopAssetKind.DMG -> appFromDiskImage(download)
        }
        // An update under a new name ("Emu-1.2.AppImage" after "Emu-1.1.AppImage") leaves no old copy behind.
        if (previous != null && previous != placed.fusePath) {
            val old = File(previous)
            if (inside(old) && topOf(old) != topOf(placed)) topOf(old).deleteRecursively()
        }
        placed.fusePath
    }

    override suspend fun remove(path: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!inside(f)) return@withContext false
        val top = topOf(f)
        !top.exists() || top.deleteRecursively()
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) { File(path).exists() }

    override fun launch(path: String): Boolean = runCatching {
        val f = File(path)
        val command = when {
            os == DesktopOs.MACOS && f.name.endsWith(".app") -> listOf("open", "-a", f.absolutePath)
            else -> listOf(f.absolutePath)
        }
        ProcessBuilder(command).directory(f.parentFile).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        true
    }.getOrDefault(false)

    // Linux

    private fun placeAppImage(download: File, fileName: String): File {
        val target = File(root, File(fileName).name)
        Files.move(download.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (!target.setExecutable(true, false)) throw IOException("Fuse couldn't make ${target.name} runnable.")
        return target
    }

    private fun appImageFromZip(download: File): File = ZipFile(download).use { zip ->
        val entry = zip.entries().asSequence().filter { !it.isDirectory && it.name.lowercase().endsWith(".appimage") }.minByOrNull { it.name.length }
            ?: throw IOException("The zip has no AppImage in it.")
        val target = File(root, File(entry.name).name)
        val part = File(root, ".${target.name}.part")
        zip.getInputStream(entry).use { input -> part.outputStream().use { input.copyTo(it) } }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (!target.setExecutable(true, false)) throw IOException("Fuse couldn't make ${target.name} runnable.")
        target
    }

    // Windows

    /** Unpacks into ~/Emulators/<name>, whole or not at all, and returns its program. */
    private fun unpackWindows(download: File, name: String): File {
        val dirName = name.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "Program" }
        val target = File(root, dirName)
        val staging = File(root, ".$dirName.part").apply { deleteRecursively() }
        unzip(download, staging)
        // A zip holding one folder (most do) is that folder.
        val content = staging.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } ?: staging
        val program = programIn(content, name) ?: run {
            staging.deleteRecursively()
            throw IOException("The download has no program Fuse can start.")
        }
        val relative = content.toPath().relativize(program.toPath())
        target.deleteRecursively()
        Files.move(content.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        staging.deleteRecursively()
        return target.toPath().resolve(relative).toFile()
    }

    /** The program to start: the .exe named like the app, else the largest one near the top. */
    private fun programIn(dir: File, name: String): File? {
        val exes = dir.walkTopDown().maxDepth(2).filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
            .filterNot { n -> SKIPPED_EXES.any { it in n.name.lowercase() } }.toList()
        val key = name.lowercase().filter { it.isLetterOrDigit() }
        return exes.firstOrNull { it.nameWithoutExtension.lowercase().filter { c -> c.isLetterOrDigit() }.startsWith(key) } ?: exes.maxByOrNull { it.length() }
    }

    private fun unzip(zipFile: File, into: File) {
        into.mkdirs()
        val base = into.canonicalFile.toPath()
        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                val out = File(into, entry.name).canonicalFile
                // Nothing may land outside the folder ("../" in a name).
                if (!out.toPath().startsWith(base)) throw IOException("The zip names a place outside its folder, so Fuse stopped.")
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    // macOS

    private fun appFromZip(download: File): File {
        val staging = Files.createTempDirectory("fuse-store").toFile()
        try {
            // ditto keeps what an app needs: its links, permissions and signature.
            run("ditto", "-x", "-k", download.absolutePath, staging.absolutePath)
            val app = findApp(staging) ?: throw IOException("The zip has no app in it.")
            return copyApp(app)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun appFromDiskImage(download: File): File {
        val mount = Files.createTempDirectory("fuse-dmg").toFile()
        try {
            run("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.absolutePath, download.absolutePath)
            try {
                val app = findApp(mount) ?: throw IOException("The disk image has no app in it.")
                return copyApp(app)
            } finally {
                runCatching { run("hdiutil", "detach", "-quiet", mount.absolutePath) }
            }
        } finally {
            mount.delete()
        }
    }

    private fun findApp(dir: File): File? =
        dir.walkTopDown().maxDepth(3).firstOrNull { it.isDirectory && it.name.endsWith(".app") && !it.absolutePath.contains(".app/") }

    private fun copyApp(app: File): File {
        val target = File(root, app.name)
        val part = File(root, ".${app.name}.part").apply { deleteRecursively() }
        run("ditto", app.absolutePath, part.absolutePath)
        target.deleteRecursively()
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    private fun run(vararg command: String) {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        p.inputStream.use { it.readBytes() }
        if (!p.waitFor(5, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            throw IOException("${command.first()} took too long.")
        }
        if (p.exitValue() != 0) throw IOException("${command.first()} couldn't finish (${p.exitValue()}).")
    }

    // Safety

    private fun inside(f: File): Boolean = runCatching {
        val base = root.canonicalFile.toPath()
        val p = f.absoluteFile.toPath().normalize()
        p.startsWith(base) && p != base
    }.getOrDefault(false)

    /** The entry directly in [root] that holds [f]: what an install put there, and what removing takes. */
    private fun topOf(f: File): File {
        val base = root.absoluteFile.toPath().normalize()
        val p = f.absoluteFile.toPath().normalize()
        return if (p.startsWith(base) && p.nameCount > base.nameCount) base.resolve(p.getName(base.nameCount)).toFile() else f
    }

    private class FileSink(private val file: File) : DownloadSink {
        private val out = FileOutputStream(file)
        private val digest = MessageDigest.getInstance("SHA-256")
        override val path: String = file.absolutePath

        override suspend fun write(bytes: ByteArray, count: Int) = withContext(Dispatchers.IO) {
            out.write(bytes, 0, count)
            digest.update(bytes, 0, count)
        }

        override suspend fun finish(): String = withContext(Dispatchers.IO) {
            out.close()
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        override suspend fun discard() {
            withContext(Dispatchers.IO) {
                runCatching { out.close() }
                file.delete()
            }
        }
    }

    private companion object {
        /** Helpers that ship beside a program and are never it. */
        val SKIPPED_EXES = listOf("unins", "updater", "crashpad", "crash_handler", "vc_redist", "dxsetup", "7z")
    }
}
