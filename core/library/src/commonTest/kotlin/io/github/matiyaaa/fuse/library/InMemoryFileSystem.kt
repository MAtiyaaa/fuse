package io.github.matiyaaa.fuse.library

import io.github.matiyaaa.fuse.model.FolderStateStore

/**
 * A [FuseFileSystem] held in memory for tests. Paths are absolute and "/"-separated.
 *
 * Like a real file system, creating or removing an entry updates the modification time of the entry
 * and of its direct parent only, using a clock that advances on every change. Symlinks are
 * followed when listing and resolved by [canonical], so loops can be built.
 */
class InMemoryFileSystem : FuseFileSystem {
    private sealed class Node(var mtime: Long)
    private class Dir(mtime: Long) : Node(mtime) {
        val children = LinkedHashMap<String, Node>()
    }
    private class File(mtime: Long, val size: Long, val content: String?, val md5: String?, val bytes: ByteArray? = null) : Node(mtime)
    private class Link(mtime: Long, val target: String) : Node(mtime)

    private val root = Dir(0)
    private val unreadable = HashSet<String>()

    /** Advances on every change; the next change gets this time. */
    var clock: Long = 1_000
        private set

    /** When false, [md5] returns null like a file system that cannot hash. */
    var md5Supported: Boolean = true

    /** How many times [list] was called, to check that quick scans skip work. */
    var listCalls: Int = 0
        private set

    private fun tick(): Long = ++clock

    /** Creates [path] and any missing parents. */
    fun dir(path: String): InMemoryFileSystem {
        mkdirs(FsPath.normalize(path))
        return this
    }

    /** Creates or replaces a file (parents are created). */
    fun file(path: String, size: Long = 1, content: String? = null, md5: String? = null): InMemoryFileSystem {
        val p = FsPath.normalize(path)
        val parent = mkdirs(FsPath.parent(p) ?: "/")
        val now = tick()
        parent.children[FsPath.name(p)] = File(now, if (content != null && size == 1L) content.length.toLong() else size, content, md5)
        parent.mtime = now
        return this
    }

    /** Creates or replaces a file holding [data] (read back by [readBytes]). */
    fun bytes(path: String, data: ByteArray): InMemoryFileSystem {
        val p = FsPath.normalize(path)
        val parent = mkdirs(FsPath.parent(p) ?: "/")
        val now = tick()
        parent.children[FsPath.name(p)] = File(now, data.size.toLong(), null, null, data)
        parent.mtime = now
        return this
    }

    /** Removes the entry at [path] (and everything in it). */
    fun remove(path: String): InMemoryFileSystem {
        val p = FsPath.normalize(path)
        val parent = resolve(FsPath.parent(p) ?: "/")?.first as? Dir ?: return this
        if (parent.children.remove(FsPath.name(p)) != null) parent.mtime = tick()
        return this
    }

    /** Creates a symbolic link at [path] pointing to the absolute [target]. */
    fun symlink(path: String, target: String): InMemoryFileSystem {
        val p = FsPath.normalize(path)
        val parent = mkdirs(FsPath.parent(p) ?: "/")
        val now = tick()
        parent.children[FsPath.name(p)] = Link(now, FsPath.normalize(target))
        parent.mtime = now
        return this
    }

    /** Makes listing [path] fail with [FsAccessException], like Android/data on Android 11+. */
    fun makeUnreadable(path: String): InMemoryFileSystem {
        unreadable += FsPath.normalize(path)
        return this
    }

    /** Current modification time of the entry at [path]. */
    fun mtime(path: String): Long = (resolve(FsPath.normalize(path))?.first ?: error("No such path: $path")).mtime

    private fun mkdirs(path: String): Dir {
        var current = root
        for (segment in segments(path)) {
            val next = current.children[segment]
            current = when (next) {
                is Dir -> next
                null -> Dir(tick()).also { current.children[segment] = it; current.mtime = it.mtime }
                is Link -> resolve(next.target)?.first as? Dir ?: error("Link $segment is not a directory")
                is File -> error("$segment is a file")
            }
        }
        return current
    }

    private fun segments(path: String) = FsPath.normalize(path).split('/').filter { it.isNotEmpty() }

    /** Follows every link; returns the node and its real path, or null (missing or link cycle). */
    private fun resolve(path: String, hops: Int = 0): Pair<Node, String>? {
        if (hops > 40) return null
        var node: Node = root
        var real = "/"
        for (segment in segments(path)) {
            val dir = node as? Dir ?: return null
            var child = dir.children[segment] ?: return null
            var childPath = FsPath.join(real, segment)
            if (child is Link) {
                val target = resolve(child.target, hops + 1) ?: return null
                child = target.first
                childPath = target.second
            }
            node = child
            real = childPath
        }
        return node to real
    }

    /** The node at [path] without following a link in the last segment. */
    private fun lstat(path: String): Node? {
        val parent = FsPath.parent(FsPath.normalize(path)) ?: return root
        val dir = resolve(parent)?.first as? Dir ?: return null
        return dir.children[FsPath.name(path)]
    }

    private fun entry(path: String, node: Node): FsEntry {
        val link = lstat(path) is Link
        val target = if (node is Link) resolve(node.target)?.first else node
        return FsEntry(
            name = FsPath.name(path),
            path = FsPath.normalize(path),
            isDirectory = target is Dir,
            sizeBytes = (target as? File)?.size ?: 0,
            modifiedAt = (target ?: node).mtime,
            isSymlink = link,
        )
    }

    override suspend fun list(path: String): List<FsEntry> {
        listCalls++
        val p = FsPath.normalize(path)
        val (node, real) = resolve(p) ?: return emptyList()
        if (node !is Dir) return emptyList()
        if (p in unreadable || real in unreadable) throw FsAccessException(p)
        return node.children.map { (name, child) -> entry(FsPath.join(p, name), child) }
    }

    override suspend fun stat(path: String): FsEntry? {
        val p = FsPath.normalize(path)
        val (node, _) = resolve(p) ?: return null
        return entry(p, node)
    }

    override suspend fun readText(path: String, maxBytes: Int): String? {
        val file = resolve(FsPath.normalize(path))?.first as? File ?: return null
        return (file.content ?: file.bytes?.decodeToString() ?: "").take(maxBytes)
    }

    override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray? {
        val file = resolve(FsPath.normalize(path))?.first as? File ?: return null
        val data = file.bytes ?: file.content?.encodeToByteArray() ?: return null
        if (offset < 0 || offset > data.size) return null
        return data.copyOfRange(offset.toInt(), minOf(data.size.toLong(), offset + length).toInt())
    }

    override suspend fun md5(path: String): String? {
        if (!md5Supported) return null
        return (resolve(FsPath.normalize(path))?.first as? File)?.md5
    }

    override suspend fun canonical(path: String): String? = resolve(FsPath.normalize(path))?.second
}

/** A [FolderStateStore] backed by a map. */
class InMemoryFolderStateStore : FolderStateStore {
    val values = LinkedHashMap<String, Long>()

    override suspend fun lastModified(path: String): Long? = values[path]

    override suspend fun remember(path: String, modifiedAt: Long) {
        values[path] = modifiedAt
    }

    override suspend fun forget(pathPrefix: String) {
        values.keys.removeAll { it == pathPrefix || it.startsWith("$pathPrefix/") }
    }
}
