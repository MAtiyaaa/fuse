package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.launch.linux.LinuxDetector
import io.github.matiyaaa.fuse.launch.linux.LinuxEnvironment
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/** [LinuxEnvironment] over the real file system and `$PATH`. Blocking; call from an IO thread. */
class SystemLinuxEnvironment(override val homeDir: String) : LinuxEnvironment {

    override fun pathDirectories(): List<String> =
        (System.getenv("PATH") ?: "").split(':').filter { it.startsWith("/") }.distinct()

    override fun isExecutable(path: String): Boolean = Processes.isExecutable(path)

    override fun exists(path: String): Boolean = try {
        Files.exists(Paths.get(path))
    } catch (e: Exception) {
        false
    }

    override fun listFiles(dir: String): List<String> = File(dir).list()?.toList() ?: emptyList()

    /** Installed Flatpak apps; empty when flatpak is missing or does not answer within 8 s. */
    override fun flatpakApps(): Set<String> = flatpakListing().first

    override fun flatpakVersions(): Map<String, String> = flatpakListing().second

    /** One `flatpak list` a moment, for both the apps and their versions (detection asks for each). */
    @Volatile private var listing: Triple<Long, Set<String>, Map<String, String>>? = null

    private fun flatpakListing(): Pair<Set<String>, Map<String, String>> {
        listing?.takeIf { System.currentTimeMillis() - it.first < 2_000 }?.let { return it.second to it.third }
        val flatpak = Processes.which("flatpak") ?: return emptySet<String>() to emptyMap()
        val out = Processes.run(listOf(flatpak, "list", "--app", "--columns=application,version"), timeoutMs = 8_000)
        if (out == null || out.exitCode != 0) return emptySet<String>() to emptyMap()
        val apps = LinuxDetector.parseFlatpakList(out.stdout)
        val versions = LinuxDetector.parseFlatpakVersions(out.stdout)
        listing = Triple(System.currentTimeMillis(), apps, versions)
        return apps to versions
    }

    override fun readText(path: String): String? = readSmallText(path)

    /** What each program printed for its version, by path and the time it was last changed: each is run once. */
    private val printed = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun versionOutput(path: String, flag: String): String? {
        val key = "$path\u0000${File(path).lastModified()}\u0000$flag"
        printed[key]?.let { return it.ifEmpty { null } }
        val out = Processes.run(listOf(path, flag), timeoutMs = 4_000, maxOutput = 16 * 1024, withErrors = true)
        val text = out?.takeIf { it.exitCode == 0 }?.stdout?.trim().orEmpty()
        printed[key] = text
        return text.ifEmpty { null }
    }

    override fun readBytes(path: String, offset: Long, length: Int): ByteArray? = try {
        java.io.RandomAccessFile(path, "r").use { f ->
            if (offset < 0 || offset >= f.length()) return null
            f.seek(offset)
            val buf = ByteArray(minOf(length.toLong(), f.length() - offset).toInt())
            f.readFully(buf)
            buf
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    companion object {
        /** Up to [maxBytes] of a UTF-8 text file, or null. */
        fun readSmallText(path: String, maxBytes: Int = 256 * 1024): String? = try {
            val p = Paths.get(path)
            if (!Files.isRegularFile(p)) null
            else Files.newInputStream(p).use { String(it.readNBytes(maxBytes), StandardCharsets.UTF_8) }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: java.nio.file.InvalidPathException) {
            null
        }
    }
}
