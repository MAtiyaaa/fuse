package io.github.matiyaaa.fuse.launch.intelligence

import java.io.File

/**
 * [ReadOnlyLook] on the JVM: `java.io.File`'s questions about a path, a program's first bytes (its
 * header, read-only, programs only) and the processor's description (`/proc/cpuinfo` on Linux).
 * Nothing else outside Fuse is opened, and nothing is changed.
 */
class FileLook(override val home: String = System.getProperty("user.home").orEmpty()) : ReadOnlyLook {
    override fun programHeader(path: String): ByteArray? = runCatching {
        val file = File(path)
        // Programs only: one this system would run, or a Windows program by its name.
        if (!file.isFile || !(file.canExecute() || file.name.endsWith(".exe", ignoreCase = true))) return null
        java.io.RandomAccessFile(file, "r").use { f ->
            val bytes = ByteArray(minOf(ReadOnlyLook.PROGRAM_HEADER.toLong(), f.length()).toInt())
            f.readFully(bytes)
            bytes
        }
    }.getOrNull()

    override fun processor(): Processor? = cpu

    /** Read once: the processor doesn't change while Fuse runs. */
    private val cpu: Processor? by lazy { readProcessor() }

    private fun readProcessor(): Processor? {
        val arch = when (System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "arm64"
            "x86", "i386", "i486", "i586", "i686" -> "x86"
            "arm", "armv7l", "armv7" -> "arm"
            else -> return null
        }
        // Linux lists the processor's features; elsewhere they aren't known here, and nothing is guessed.
        val flags = runCatching {
            File("/proc/cpuinfo").takeIf { it.isFile }?.useLines { lines ->
                lines.firstOrNull { it.startsWith("flags") || it.startsWith("Features") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet()
            }
        }.getOrNull()
        return Processor(arch, flags)
    }

    override fun env(name: String): String? = System.getenv(name)
    override fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)
    override fun isDirectory(path: String): Boolean = runCatching { File(path).isDirectory }.getOrDefault(false)
    override fun canExecute(path: String): Boolean = runCatching { File(path).let { it.isFile && it.canExecute() } }.getOrDefault(false)
    override fun modified(path: String): Long? = runCatching { File(path).takeIf { it.exists() }?.lastModified() }.getOrNull()
    override fun names(path: String): List<String> = runCatching { File(path).list()?.sorted() }.getOrNull().orEmpty()
}

/** Fuse's own file for what Intelligence learns, written whole and then moved into place. */
class FileKnowledgeStore(private val file: File) : KnowledgeStore {
    override fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
