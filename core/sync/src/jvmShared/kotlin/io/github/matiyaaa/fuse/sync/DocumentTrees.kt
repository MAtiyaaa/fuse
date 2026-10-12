package io.github.matiyaaa.fuse.sync

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A folder the person granted Fuse through the system's folder picker, which isn't a path on the
 * device (Android's Storage Access Framework: another app's documents provider, such as Eden's
 * `<package>.user`, or a folder on a card). Fuse reaches it only through what the provider offers:
 * listing, reading, writing a whole file, and, where the provider supports it, renaming.
 *
 * Paths inside it are relative, with `/`, "" being the granted folder itself.
 */
interface DocumentTree {
    /** The entries of the folder at [path], or null when there is no such folder. */
    fun list(path: String): List<TreeEntry>?

    /** The file at [path] to read, or null when there isn't one. */
    fun open(path: String): InputStream?

    /** Writes the file at [path] from scratch (making its folders), or null when the provider refuses. */
    fun create(path: String): OutputStream?

    /** Renames the file at [path] within its folder; false when the provider can't (then nothing changed). */
    fun rename(path: String, newName: String): Boolean

    /** Removes the file at [path]; true when it is gone. */
    fun delete(path: String): Boolean
}

data class TreeEntry(val name: String, val directory: Boolean, val size: Long = 0, val modified: Long = 0)

/**
 * Where a granted folder appears among save paths: `tree:<its uri>` followed by a path inside it,
 * so save adapters build on it exactly as on a folder (`tree:content://…/tree/…/user/save/…`).
 */
object TreePaths {
    const val PREFIX = "tree:"
    // A tree link has its document id percent-encoded, so it holds no `/` after `/tree/<id>`.
    private val TREE = Regex("^tree:(content://[^/]+/tree/[^/]+)(?:/(.*))?$")

    fun isTree(path: String?): Boolean = path?.startsWith(PREFIX) == true

    /** The granted folder's link and the path inside it, or null for an ordinary path. */
    fun split(path: String): Pair<String, String>? = TREE.matchEntire(path.trimEnd('/'))?.let { it.groupValues[1] to it.groupValues[2] }

    fun of(uri: String, inside: String = ""): String = PREFIX + uri + if (inside.isEmpty()) "" else "/$inside"
}

/** [base], with granted folders (`tree:` paths) read through their [trees] instead of the file system. */
class TreeSaveEnvironment(private val base: SaveEnvironment, private val trees: (String) -> DocumentTree?) : SaveEnvironment by base {
    private fun at(path: String): Pair<DocumentTree, String>? = TreePaths.split(path)?.let { (uri, inside) -> trees(uri)?.let { it to inside } }

    private fun entry(tree: DocumentTree, inside: String): TreeEntry? {
        if (inside.isEmpty()) return TreeEntry("", directory = tree.list("") != null)
        return tree.list(inside.substringBeforeLast('/', ""))?.firstOrNull { it.name == inside.substringAfterLast('/') }
    }

    override fun exists(path: String): Boolean =
        if (TreePaths.isTree(path)) at(path)?.let { (t, p) -> entry(t, p) != null } == true else base.exists(path)

    override fun isDirectory(path: String): Boolean =
        if (TreePaths.isTree(path)) at(path)?.let { (t, p) -> t.list(p) != null } == true else base.isDirectory(path)

    override fun list(path: String): List<String> =
        if (TreePaths.isTree(path)) at(path)?.let { (t, p) -> t.list(p)?.map { it.name }?.sorted() }.orEmpty() else base.list(path)

    override fun readText(path: String, limit: Int): String? {
        if (!TreePaths.isTree(path)) return base.readText(path, limit)
        val (t, p) = at(path) ?: return null
        return runCatching { t.open(p)?.use { String(it.readNBytes(limit)) } }.getOrNull()
    }

    override fun readBytes(path: String, offset: Long, length: Int): ByteArray? {
        if (!TreePaths.isTree(path)) return base.readBytes(path, offset, length)
        val (t, p) = at(path) ?: return null
        return runCatching { t.open(p)?.use { s -> s.skipNBytes(offset); s.readNBytes(length) } }.getOrNull()
    }

    override fun modified(path: String): Long? {
        if (!TreePaths.isTree(path)) return base.modified(path)
        val (t, p) = at(path) ?: return null
        val e = entry(t, p) ?: return null
        if (!e.directory) return e.modified
        fun newest(dir: String): Long = t.list(dir).orEmpty().maxOfOrNull { c -> if (c.directory) newest(if (dir.isEmpty()) c.name else "$dir/${c.name}") else c.modified } ?: 0L
        return newest(p)
    }
}

/**
 * A save that lives in a granted folder, worked on through a copy in Fuse's own storage. Fuse Sync
 * reads and writes saves as files; a provider offers only whole-file reads and writes. So the save
 * is copied in ([pull]) whenever Fuse looks at it, and every change Fuse makes goes back out
 * ([push]) as one recoverable transaction before Fuse counts it as done:
 *
 * 1. Each changed file is written beside the original (`name.fuse-part`) and read back: its bytes
 *    must match exactly.
 * 2. A journal of what is about to change is written.
 * 3. Each original is kept (renamed to `name.fuse-old` where the provider can rename, else read
 *    into Fuse's storage), then the new file takes its name (renamed, or written over it).
 * 4. Files the save no longer has are removed, each kept the same way first.
 * 5. Only then are the kept originals removed and the journal cleared.
 *
 * Anything failing puts every original back. A crash part way is put back the same way the next
 * time any save is touched ([recover]). A file the emulator changed meanwhile is never overwritten:
 * the push refuses, and nothing changes.
 */
class TreeMirror(private val dir: File, private val trees: (String) -> DocumentTree?) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val journal = File(dir, "tree-transaction.json")

    @Serializable private data class Step(val uri: String, val path: String, val kept: String?, val keptLocal: String?, val incoming: String?)
    @Serializable private data class Journal(val steps: List<Step>, val done: Boolean = false)

    /** One part of a save: a folder (or single file) in a granted folder, and its copy here. */
    class Part(val uri: String, val inside: String, val local: File, val single: Boolean) {
        /** What each file in the folder held when it was copied in (path to hash): what a push may replace. */
        internal val pulled = HashMap<String, String>()
    }

    /** [spot] with its granted folders swapped for copies here, fresh from the folders; null when none are granted ones. */
    fun pull(spot: SaveSpot): Pair<SaveSpot, List<Part>>? {
        val involved = listOfNotNull(spot.root) + spot.files.map { it.path } + spot.mounts.values
        if (involved.none(TreePaths::isTree)) return null
        recover()
        val parts = ArrayList<Part>()
        fun localFor(path: String, single: Boolean): String {
            if (!TreePaths.isTree(path)) return path
            val (uri, inside) = TreePaths.split(path) ?: error("A granted folder's link isn't readable")
            val local = File(File(dir, "mirror/" + SyncCrypto.sha256(uri.byteInputStream()).take(16)), inside.ifEmpty { "_root" })
            parts += Part(uri, inside, local, single)
            return local.path
        }
        val mirrored = spot.copy(
            root = spot.root?.let { localFor(it, single = false) },
            files = spot.files.map { SpotFile(it.name, localFor(it.path, single = true)) },
            mounts = spot.mounts.mapValues { localFor(it.value, single = false) },
        )
        for (part in parts) copyIn(part)
        return mirrored to parts
    }

    private fun copyIn(part: Part) {
        val tree = trees(part.uri) ?: error("Fuse no longer has access to that folder. Choose it again in Settings, Save folders.")
        part.local.deleteRecursively()
        part.pulled.clear()
        if (part.single) {
            tree.open(part.inside)?.use { input -> part.local.apply { parentFile.mkdirs() }.outputStream().use { input.copyTo(it) } }
            if (part.local.isFile) part.pulled[part.inside] = part.local.inputStream().use { SyncCrypto.sha256(it) }
            return
        }
        fun walk(inside: String, local: File) {
            val entries = tree.list(inside) ?: return
            local.mkdirs()
            for (e in entries) {
                if (e.name.endsWith(PART) || e.name.endsWith(OLD)) continue
                val child = if (inside.isEmpty()) e.name else "$inside/${e.name}"
                if (e.directory) walk(child, File(local, e.name))
                else tree.open(child)?.use { input ->
                    val f = File(local, e.name)
                    f.outputStream().use { input.copyTo(it) }
                    part.pulled[child] = f.inputStream().use { SyncCrypto.sha256(it) }
                }
            }
        }
        walk(part.inside, part.local)
    }

    /**
     * Puts the copies' current state back into their granted folders, as one transaction (see the
     * class). [expected] is what each folder held when it was copied in: a file changed there since is
     * never overwritten.
     */
    fun push(parts: List<Part>) {
        recover()
        val steps = ArrayList<Step>()
        val plans = parts.map { part -> part to plan(part) }
        // Nothing the emulator wrote since Fuse copied the folder in is ever replaced.
        for ((part, plan) in plans) {
            val tree = trees(part.uri) ?: error("Fuse no longer has access to that folder. Choose it again in Settings, Save folders.")
            for (path in plan.write.map { it.first } + plan.remove) {
                val now = tree.open(path)?.use { SyncCrypto.sha256(it) }
                require(now == part.pulled[path]) { "The emulator changed its save while Fuse was working on it. Nothing was changed; close the game and try again." }
            }
        }
        try {
            // Stage and verify every new file before anything existing changes.
            for ((part, plan) in plans) {
                val tree = trees(part.uri) ?: error("Fuse no longer has access to that folder. Choose it again in Settings, Save folders.")
                for ((path, file) in plan.write) {
                    val stage = "$path$PART"
                    (tree.create(stage) ?: error("The folder refused a new file")).use { out -> file.inputStream().use { it.copyTo(out) } }
                    val back = tree.open(stage)?.use { SyncCrypto.sha256(it) }
                    require(back == file.inputStream().use { SyncCrypto.sha256(it) }) { "A file read back from the folder didn't match what was written" }
                    steps += Step(part.uri, path, kept = null, keptLocal = null, incoming = stage)
                }
                for (path in plan.remove) steps += Step(part.uri, path, kept = null, keptLocal = null, incoming = null)
            }
            // Keep every original, then put each new file in its place.
            val kept = steps.map { step ->
                val tree = trees(step.uri)!!
                val exists = tree.open(step.path)?.use { true } == true
                if (!exists) return@map step
                val old = "${step.path}$OLD"
                if (tree.rename(step.path, old.substringAfterLast('/'))) step.copy(kept = old)
                else {
                    val local = File(dir, "kept/" + SyncCrypto.token(12)).apply { parentFile.mkdirs() }
                    tree.open(step.path)!!.use { input -> local.outputStream().use { input.copyTo(it) } }
                    step.copy(keptLocal = local.path)
                }
            }
            writeJournal(Journal(kept))
            for (step in kept) {
                val tree = trees(step.uri)!!
                if (step.incoming == null) {
                    if (step.kept == null) require(tree.delete(step.path) || tree.open(step.path) == null) { "A file couldn't be removed from the folder" }
                    continue
                }
                if (step.kept != null || tree.open(step.path)?.use { true } != true) {
                    if (!tree.rename(step.incoming, step.path.substringAfterLast('/'))) copyWithin(tree, step.incoming, step.path)
                } else {
                    // Kept in Fuse's storage (no renames here): written over in place.
                    copyWithin(tree, step.incoming, step.path)
                }
            }
            writeJournal(Journal(kept, done = true))
            cleanUp(kept)
        } catch (e: Exception) {
            runCatching { recover() }.onFailure(e::addSuppressed)
            for (step in steps) step.incoming?.let { stage -> trees(step.uri)?.delete(stage) }
            throw e
        }
    }

    /** What changed in [part]'s copy against its folder: files to write and files to remove. */
    private class Plan(val write: List<Pair<String, File>>, val remove: List<String>)

    private fun plan(part: Part): Plan {
        val tree = trees(part.uri) ?: error("Fuse no longer has access to that folder. Choose it again in Settings, Save folders.")
        if (part.single) {
            if (!part.local.isFile) return Plan(emptyList(), listOfNotNull(part.inside.takeIf { tree.open(it)?.use { true } == true }))
            val same = tree.open(part.inside)?.use { SyncCrypto.sha256(it) } == part.local.inputStream().use { SyncCrypto.sha256(it) }
            return Plan(if (same) emptyList() else listOf(part.inside to part.local), emptyList())
        }
        // Hidden files are never a save's (and Fuse's own safety copies sit in the copy as hidden files).
        val here = if (part.local.isDirectory) part.local.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.associateBy {
            (if (part.inside.isEmpty()) "" else part.inside + "/") + it.relativeTo(part.local).path.replace(File.separatorChar, '/')
        } else emptyMap()
        val there = HashMap<String, Boolean>()
        fun walk(inside: String) {
            for (e in tree.list(inside).orEmpty()) {
                if (e.name.endsWith(PART) || e.name.endsWith(OLD) || e.name.startsWith(".")) continue
                val child = if (inside.isEmpty()) e.name else "$inside/${e.name}"
                if (e.directory) walk(child) else there[child] = true
            }
        }
        walk(part.inside)
        val write = here.filter { (path, file) -> tree.open(path)?.use { SyncCrypto.sha256(it) } != file.inputStream().use { SyncCrypto.sha256(it) } }.toList()
        return Plan(write, there.keys.filter { it !in here })
    }

    private fun copyWithin(tree: DocumentTree, from: String, to: String) {
        val bytes = tree.open(from)?.use { it.readBytes() } ?: error("A staged file disappeared from the folder")
        (tree.create(to) ?: error("The folder refused a file")).use { it.write(bytes) }
        tree.delete(from)
    }

    /** Finishes or undoes a push a crash interrupted. */
    fun recover() {
        if (!journal.isFile) return
        val j = runCatching { json.decodeFromString(Journal.serializer(), journal.readText()) }.getOrNull() ?: return run { journal.delete() }
        if (!j.done) for (step in j.steps.asReversed()) {
            val tree = trees(step.uri) ?: error("Fuse no longer has access to a folder it was changing. Choose it again in Settings, Save folders.")
            when {
                step.kept != null && tree.open(step.kept)?.use { true } == true -> {
                    tree.delete(step.path)
                    if (!tree.rename(step.kept, step.path.substringAfterLast('/'))) copyWithin(tree, step.kept, step.path)
                }
                step.keptLocal != null && File(step.keptLocal).isFile ->
                    (tree.create(step.path) ?: error("The folder refused a file")).use { out -> File(step.keptLocal).inputStream().use { it.copyTo(out) } }
                step.kept == null && step.keptLocal == null && step.incoming != null -> tree.delete(step.path)
            }
            step.incoming?.let { tree.delete(it) }
        }
        cleanUp(j.steps)
    }

    private fun cleanUp(steps: List<Step>) {
        for (step in steps) {
            val tree = trees(step.uri) ?: continue
            step.kept?.let { tree.delete(it) }
            step.incoming?.let { if (tree.open(it)?.use { true } == true) tree.delete(it) }
            step.keptLocal?.let { File(it).delete() }
        }
        journal.delete()
    }

    private fun writeJournal(j: Journal) {
        dir.mkdirs()
        val tmp = File(dir, "tree-transaction.json.tmp")
        tmp.writeText(json.encodeToString(Journal.serializer(), j))
        if (!tmp.renameTo(journal)) { journal.delete(); require(tmp.renameTo(journal)) { "The folder change couldn't be recorded" } }
    }

    companion object {
        const val PART = ".fuse-part"
        const val OLD = ".fuse-old"
    }
}
