package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.desktop.system.fusePath
import io.github.matiyaaa.fuse.library.steam.ProtonTool
import io.github.matiyaaa.fuse.library.steam.SteamLibraryReader
import io.github.matiyaaa.fuse.library.steam.SteamProton
import io.github.matiyaaa.fuse.model.PortFileKind
import io.github.matiyaaa.fuse.ui.shell.store.DownloadSink
import io.github.matiyaaa.fuse.ui.shell.store.PortFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Fuse Ports on a computer: its folder of ports in Fuse's own data folder, the list carried inside
 * this build, and the few things done to files there. Builds are unpacked by Fuse (nothing a port
 * publishes is run to install it), never outside their folder, and without links of their own;
 * the person's files are linked in and never copied; removing never follows a link out of Fuse's
 * folder, so the person's files can't be touched by it.
 */
internal class DesktopPortFiles(
    dataDir: File,
    private val downloads: File,
    private val system: DesktopOs = DesktopOs.current,
    /** Where umu-launcher's umu-run is looked for; the PATH and ~/.local/bin by default. */
    private val findUmu: () -> String? = ::umuOnThisComputer,
    /** The home folder Steam's Protons are looked for from. */
    private val home: String = System.getProperty("user.home").orEmpty(),
    /** Where bubblewrap's bwrap is looked for; the PATH by default. */
    private val findBwrap: () -> String? = { Processes.which("bwrap") },
    /** Bytes free where [copy] writes; the disk's own by default. */
    private val free: () -> Long? = { null },
) : PortFiles {
    private val root = File(dataDir, "ports")
    override val folder: String = root.absoluteFile.fusePath

    override val os: String = when (system) {
        DesktopOs.LINUX -> "linux"
        DesktopOs.WINDOWS -> "windows"
        DesktopOs.MACOS -> "macos"
    }

    override val arch: String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "x86_64"
    }

    override fun bundledIndex(): String? = DesktopPortFiles::class.java.getResourceAsStream("/ports/index.json")?.use { it.readBytes().decodeToString() }

    override fun umuRun(): String? = if (system == DesktopOs.LINUX) findUmu() else null

    override suspend fun protonPath(): String? = if (system != DesktopOs.LINUX) null else withContext(Dispatchers.IO) { protonOnThisComputer(home) }

    override suspend fun newDownload(name: String): DownloadSink? = withContext(Dispatchers.IO) {
        if (!downloads.isDirectory && !downloads.mkdirs()) return@withContext null
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        Sink(File(downloads, "${System.nanoTime()}-$safe"))
    }

    override fun freeBytes(): Long? = generateSequence(root) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace?.takeIf { it > 0 }

    override suspend fun unpack(archive: String, kind: PortFileKind, name: String, into: String) = withContext(Dispatchers.IO) {
        val from = File(archive)
        val to = File(into)
        if (to.exists()) throw IOException("Fuse's folder for it wasn't empty.")
        if (!to.mkdirs()) throw IOException("Fuse couldn't make its folder for it.")
        when (kind) {
            PortFileKind.APPIMAGE, PortFileKind.FILE -> {
                val safe = File(name).name.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: throw IOException("The download has no name Fuse can keep.")
                val out = File(to, safe)
                Files.copy(from.toPath(), out.toPath())
                if (system != DesktopOs.WINDOWS && !out.setExecutable(true, false)) throw IOException("Fuse couldn't make $safe runnable.")
            }
            PortFileKind.ZIP -> unzip(from, to)
            PortFileKind.TAR_GZ -> LinuxArchives.tar(from, to, xz = false)
            PortFileKind.TAR_XZ -> LinuxArchives.tar(from, to, xz = true)
            PortFileKind.ZIP_TAR_GZ -> {
                // The zip's one tarball taken out beside the download, unpacked as a tarball is, then gone.
                val inner = File(from.parentFile, "${from.name}.tar.gz")
                try {
                    ZipFile.builder().setFile(from).get().use { z ->
                        val entries = z.entries.toList().filter { !it.isDirectory }
                        val tar = entries.singleOrNull()?.takeIf { it.name.endsWith(".tar.gz") || it.name.endsWith(".tgz") }
                            ?: throw IOException("The zip doesn't hold one tarball, as the list says it does.")
                        z.getInputStream(tar).use { input -> inner.outputStream().use { input.copyTo(it) } }
                    }
                    LinuxArchives.tar(inner, to, xz = false)
                } finally {
                    inner.delete()
                }
            }
        }
    }

    /** A zip unpacked as it is: each file keeps the runnable bit its zip gives it, and nothing lands outside [into]. */
    private fun unzip(zip: File, into: File) {
        val base = into.canonicalFile.toPath()
        ZipFile.builder().setFile(zip).get().use { z ->
            var count = 0
            for (entry in z.entries) {
                if (++count > MAX_ENTRIES) throw IOException("The zip has too many files.")
                val out = File(into, entry.name).canonicalFile
                if (!out.toPath().startsWith(base)) throw IOException("The zip names a place outside its folder, so Fuse stopped.")
                // A link in a zip is left out: a port's folder never needs one pointing elsewhere.
                if (entry.isUnixSymlink) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                z.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                if (system != DesktopOs.WINDOWS && entry.unixMode and 0b001_001_001 != 0) out.setExecutable(true, false)
            }
        }
    }

    override suspend fun link(target: String, at: String): String? = withContext(Dispatchers.IO) {
        val to = File(target).toPath()
        val link = File(at).toPath()
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) return@withContext "Something is already at ${link.fileName} in Fuse's copy."
        link.parent?.let { Files.createDirectories(it) }
        try {
            Files.createSymbolicLink(link, to)
            null
        } catch (e: IOException) {
            windowsLink(to, link)
        } catch (e: UnsupportedOperationException) {
            windowsLink(to, link)
        }
    }

    /**
     * Windows makes symbolic links only in Developer Mode or for an administrator: a folder is
     * joined in with a junction instead (any account may make one), a file on the same drive with a
     * hard link (the same file, read where it is, never a copy). Null when one worked, else why not.
     */
    private fun windowsLink(target: Path, link: Path): String? {
        if (system != DesktopOs.WINDOWS) return "Fuse couldn't link ${target.fileName} into its copy."
        return if (Files.isDirectory(target)) {
            val r = Processes.run(listOf("cmd", "/c", "mklink", "/J", link.toString(), target.toString()), timeoutMs = 15_000, withErrors = true)
            if (r?.exitCode == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS)) null else "Windows wouldn't join ${target.fileName} into Fuse's copy."
        } else {
            runCatching { Files.createLink(link, target) }.fold(
                onSuccess = { null },
                onFailure = { "Windows lets Fuse link ${target.fileName} only in Developer Mode, or with your game on the drive Fuse is on. Turn on Developer Mode in Windows Settings, System, For Developers, then choose the folder again." },
            )
        }
    }

    override suspend fun makeFolder(path: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(path)
        f.isDirectory || f.mkdirs()
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(path)
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, ".${target.name}.part")
            part.writeBytes(bytes)
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess
    }

    override suspend fun writeText(path: String, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(path)
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, ".${target.name}.part")
            part.writeText(text)
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess
    }

    override suspend fun isLink(path: String): Boolean = withContext(Dispatchers.IO) { linkAt(File(path).toPath()) }

    /** A symbolic link, or on Windows a junction (which Java sees as neither file nor folder). */
    private fun linkAt(p: Path): Boolean = runCatching {
        Files.isSymbolicLink(p) || Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
    }.getOrDefault(false)

    override suspend fun remove(path: String): Boolean = withContext(Dispatchers.IO) {
        val p = File(path).toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return@withContext true
        if (!inside(p)) return@withContext false
        runCatching {
            if (linkAt(p) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(p)
            } else {
                // Links are visited as files and removed themselves: what they point at is never entered.
                Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                        if (linkAt(dir) && dir != p) { Files.delete(dir); FileVisitResult.SKIP_SUBTREE } else FileVisitResult.CONTINUE

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { Files.delete(dir); return FileVisitResult.CONTINUE }
                })
            }
        }.isSuccess
    }

    override suspend fun move(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        val a = File(from).toPath()
        val b = File(to).toPath()
        if (Files.exists(b, LinkOption.NOFOLLOW_LINKS) || !inside(a) || !inside(b)) return@withContext false
        runCatching { Files.move(a, b, StandardCopyOption.ATOMIC_MOVE) }.recoverCatching { Files.move(a, b) }.isSuccess
    }

    override suspend fun readOnlyRunner(): String? = if (system == DesktopOs.LINUX) withContext(Dispatchers.IO) { findBwrap() } else null

    override suspend fun realPath(path: String): String = withContext(Dispatchers.IO) {
        runCatching { File(path).toPath().toRealPath().toString().replace('\\', '/') }.getOrDefault(path)
    }

    override suspend fun copy(from: String, to: String): String? = withContext(Dispatchers.IO) {
        val source = File(from).toPath()
        val target = File(to).toPath()
        if (!inside(target)) return@withContext "Fuse copies only into its own folder."
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@withContext "Something is already at ${target.fileName} in Fuse's copy."
        if (!Files.exists(source)) return@withContext "${source.fileName} isn't there to copy."
        try {
            val size = sizeOf(source)
            val room = free() ?: freeBytes()
            if (room != null && size + COPY_MARGIN > room) {
                return@withContext "Copying ${source.fileName} needs ${gigabytes(size + COPY_MARGIN)} free, and ${gigabytes(room)} is."
            }
            copyTree(source, target)
            null
        } catch (e: IOException) {
            remove(to)
            "Fuse couldn't copy ${source.fileName}: ${e.message ?: "the disk said no"}."
        }
    }

    /** Everything below [p] (or [p] itself), read where links lead. */
    private fun sizeOf(p: Path): Long {
        if (!Files.isDirectory(p)) return Files.size(p)
        var total = 0L
        Files.walkFileTree(p, setOf(java.nio.file.FileVisitOption.FOLLOW_LINKS), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { total += attrs.size(); return FileVisitResult.CONTINUE }
        })
        return total
    }

    /** [from] copied to [to] as plain files and folders: what a link leads to is copied, never the link. */
    private fun copyTree(from: Path, to: Path) {
        if (!Files.isDirectory(from)) {
            to.parent?.let { Files.createDirectories(it) }
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES)
            return
        }
        Files.walkFileTree(from, setOf(java.nio.file.FileVisitOption.FOLLOW_LINKS), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun gigabytes(bytes: Long) = "%.1f GB".format(java.util.Locale.ROOT, bytes / 1_000_000_000.0)

    /** Only paths in Fuse's own folder of ports are ever removed or moved. */
    private fun inside(p: Path): Boolean {
        val base = root.absoluteFile.toPath().normalize()
        val q = p.toAbsolutePath().normalize()
        return q.startsWith(base) && q != base
    }

    private class Sink(private val file: File) : DownloadSink {
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

    companion object {
        private const val MAX_ENTRIES = 100_000

        /** Room kept free beside a copy, so the disk is never filled to the last byte. */
        private const val COPY_MARGIN = 1_000_000_000L

        /**
         * The Proton a Windows build gets here ([SteamProton]): from each Steam install on this
         * computer, Steam Play's default in its `config/config.vdf`, the tools in its
         * `compatibilitytools.d`, and Valve's Protons in each of its libraries' `steamapps/common`.
         * Only a folder with its `proton` script counts.
         */
        fun protonOnThisComputer(home: String): String? {
            val roots = KnownFolders(home, DesktopOs.LINUX).steamPlaces().first
            val default = roots.firstNotNullOfOrNull { r -> File(r, "config/config.vdf").takeIf { it.isFile }?.let { f -> runCatching { SteamProton.defaultTool(f.readText()) }.getOrNull() } }
            val tools = ArrayList<ProtonTool>()
            for (root in roots) {
                File(root, "compatibilitytools.d").listFiles()?.sortedBy { it.name }?.forEach { d ->
                    File(d, "compatibilitytool.vdf").takeIf { it.isFile }?.let { f -> runCatching { tools += SteamProton.declared(f.readText(), d.absoluteFile.fusePath) } }
                }
            }
            val libraries = (roots + roots.flatMap { r ->
                File(r, "steamapps/libraryfolders.vdf").takeIf { it.isFile }?.let { f -> runCatching { SteamLibraryReader.libraryPaths(f.readText()) }.getOrNull() }.orEmpty()
            }).distinct()
            for (library in libraries) {
                File(library, "steamapps/common").listFiles()?.sortedBy { it.name }?.forEach { d -> SteamProton.valve(d.name, d.absoluteFile.fusePath)?.let(tools::add) }
            }
            return SteamProton.choose(tools.filter { File(it.folder, "proton").isFile }.distinctBy { it.folder }, default)?.folder
        }

        /** umu-launcher's umu-run: on the PATH, or in ~/.local/bin where a user install puts it (its README). */
        fun umuOnThisComputer(): String? = Processes.which("umu-run")
            ?: File(System.getProperty("user.home").orEmpty(), ".local/bin/umu-run").takeIf { it.canExecute() }?.absoluteFile?.fusePath
    }
}
