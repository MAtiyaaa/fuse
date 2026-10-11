package io.github.matiyaaa.fuse.launch.intelligence

import java.io.File

/**
 * [ReadOnlyLook] on the JVM: `java.io.File`'s questions about a path and nothing else. No stream is
 * ever opened on anything outside Fuse, so no file's contents are read and nothing is changed.
 */
class FileLook(override val home: String = System.getProperty("user.home").orEmpty()) : ReadOnlyLook {
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
