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
    override fun flatpakApps(): Set<String> {
        val flatpak = Processes.which("flatpak") ?: return emptySet()
        val out = Processes.run(listOf(flatpak, "list", "--app", "--columns=application"), timeoutMs = 8_000) ?: return emptySet()
        if (out.exitCode != 0) return emptySet()
        return LinuxDetector.parseFlatpakList(out.stdout)
    }

    override fun readText(path: String): String? = readSmallText(path)

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
