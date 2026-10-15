package io.github.matiyaaa.fuse.link

import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/** [LinkInbox] in a folder of Fuse's own: each delivery a folder in [dir], older ones cleared away after a day. */
class FileLinkInbox(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) : LinkInbox {
    private val random = SecureRandom()
    private val used = HashMap<String, Pair<Int, Long>>()

    @Synchronized
    override fun begin(): String {
        dir.mkdirs()
        // Deliveries from earlier, read or abandoned, go after a day.
        dir.listFiles()?.filter { clock() - it.lastModified() > DAY_MS }?.forEach { it.deleteRecursively() }
        val id = ByteArray(9).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        File(dir, id).mkdirs()
        used[id] = 0 to 0L
        return id
    }

    @Synchronized
    override fun open(delivery: String, path: String, size: Long): LinkSink? {
        val root = folderOf(delivery) ?: return null
        val (files, bytes) = used[delivery] ?: return null
        if (size < 0 || size > MAX_FILE || files + 1 > MAX_FILES || bytes + size > MAX_BYTES) return null
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 16 || parts.any { it == "." || it == ".." || it.length > 255 || it.any { c -> c < ' ' } }) return null
        val target = parts.fold(root) { d, p -> File(d, p) }
        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator) || target.exists()) return null
        target.parentFile.mkdirs()
        used[delivery] = (files + 1) to (bytes + size)
        val out = FileOutputStream(target)
        return object : LinkSink {
            var written = 0L
            override fun write(bytes: ByteArray, count: Int) {
                written += count
                require(written <= size) { "More than the file's size" }
                out.write(bytes, 0, count)
            }
            override fun close(): Boolean {
                runCatching { out.close() }
                val whole = written == size
                if (!whole) target.delete()
                return whole
            }
        }
    }

    @Synchronized
    override fun finish(delivery: String): String? {
        val root = folderOf(delivery) ?: return null
        used.remove(delivery)
        val top = root.listFiles().orEmpty().filterNot { it.name.startsWith(".") }
        if (top.isEmpty()) {
            root.deleteRecursively()
            return null
        }
        val one = top.singleOrNull()
        return when {
            one != null && (one.isDirectory || one.extension.equals("zip", true)) -> one.absolutePath
            else -> root.absolutePath
        }
    }

    private fun folderOf(delivery: String): File? =
        delivery.takeIf { it.length == 18 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }?.let { File(dir, it) }?.takeIf { it.isDirectory }

    companion object {
        const val MAX_FILE = 4L * 1024 * 1024 * 1024
        const val MAX_BYTES = 16L * 1024 * 1024 * 1024
        const val MAX_FILES = 40_000
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
