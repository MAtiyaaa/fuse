package io.github.matiyaaa.fuse.sync

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Stages every replacement before changing the first destination. A failed move restores all
 * replaced and removed files from independent local copies. The input and content store are read
 * only. Shared-memory-card paths remain explicit adapter targets, never inferred archive paths.
 */
internal object SaveImportTransaction {
    @Serializable private data class Entry(val target: String, val stage: String?, val backup: String?, val original: String?, val incoming: String?)
    @Serializable private data class Journal(val revision: String, val root: String, val entries: List<Entry>, val committed: Boolean = false)
    private val json = Json { encodeDefaults = true }

    /** Recover before the device can flush revisions or touch emulator files again. */
    fun recover(journal: File, committed: (String) -> Boolean) {
        if (!journal.exists()) return
        val pending = json.decodeFromString(Journal.serializer(), journal.readText())
        val durable = pending.committed || committed(pending.revision)
        if (!durable) for (entry in pending.entries.asReversed()) {
            val target = File(entry.target)
            rejectLinks(target, File(pending.root))
            require(!target.exists() || target.isFile) { "An interrupted import's destination changed into a directory" }
            val current = target.takeIf { it.isFile }?.let(::hash)
            require(current == null || current == entry.original || current == entry.incoming) {
                "A save changed after an interrupted import. Its recovery copies have been kept"
            }
            if (entry.backup != null && current != entry.original) {
                val backup = File(entry.backup)
                require(backup.isFile && hash(backup) == entry.original) { "An interrupted import's safety copy is unavailable" }
                require(target.parentFile.isDirectory) { "An interrupted import's destination is unavailable" }
                restore(backup, target)
            } else if (entry.backup == null) require(!target.exists() || target.delete()) { "An interrupted import could not be restored" }
        }
        // A committed marker prevents a failed cleanup from reverting a revision already flushed.
        writeAtomically(journal, json.encodeToString(Journal.serializer(), pending.copy(committed = true)).toByteArray())
        for (entry in pending.entries) {
            entry.stage?.let { File(it).delete() }
            entry.backup?.let { File(it).delete() }
        }
        require(journal.delete() || !journal.exists()) { "The import recovery record could not be cleared" }
    }

    fun write(slot: LocalSlot, manifest: SaveManifest, store: ContentStore, journal: File? = null, revision: String = "",
        expectedOriginal: SaveManifest? = null, commit: () -> Unit = {}, beforeMove: (Int) -> Unit = {}) {
        require(slot.available) { "The save destination isn't available" }
        val destinations = manifest.files.map { f ->
            val target = slot.target(f.path) ?: error("A save path has no safe destination")
            rejectLinks(target, slot.root)
            require(!target.isDirectory) { "A save file destination is a directory" }
            // Canonical paths also reject a symlink in a parent directory for root-based saves.
            target to f
        }
        require(destinations.map { it.first.canonicalPath }.distinct().size == destinations.size) { "Duplicate save destinations" }
        val incoming = destinations.mapTo(HashSet()) { it.first.canonicalPath }
        val stale = slot.files.map { it.file }.filter { it.canonicalPath !in incoming && it.isFile }
        stale.forEach { rejectLinks(it, slot.root) }
        val folders = manifest.folders.map { folder ->
            val target = slot.target(folder) ?: error("Unsafe save folder")
            rejectLinks(target, slot.root)
            require(!target.exists() || target.isDirectory) { "A save folder destination is a file" }
            target
        }
        val work = ArrayList<Triple<File, File?, File?>>()
        var success = false
        var recorded = false
        try {
            for ((target, file) in destinations) {
                target.parentFile.mkdirs()
                val stage = File.createTempFile(".fuse-import-", ".part", target.parentFile)
                val backup = if (target.isFile) File.createTempFile(".fuse-import-", ".backup", target.parentFile).also { target.copyTo(it, overwrite = true) } else null
                work += Triple(target, stage, backup)
                store.fileOf(file.hash).copyTo(stage, overwrite = true)
                require(stage.length() == file.size && stage.inputStream().use { SyncCrypto.sha256(it) } == file.hash) { "A save changed while being prepared" }
            }
            for (target in stale) {
                val backup = File.createTempFile(".fuse-import-", ".backup", target.parentFile)
                target.copyTo(backup, overwrite = true)
                work += Triple(target, null, backup)
            }
            if (expectedOriginal != null) {
                val expected = expectedOriginal.files.associate { file ->
                    (slot.target(file.path) ?: error("Unsafe original save path")).canonicalPath to file.hash
                }
                for ((target, _, backup) in work) require(backup?.let(::hash) == expected[target.canonicalPath]) {
                    "The current save changed during import. Inspect the destination again"
                }
            }
            if (journal != null) {
                require(!journal.exists()) { "An interrupted save import needs recovery first" }
                val entries = work.map { (target, stage, backup) -> Entry(target.absolutePath, stage?.absolutePath,
                    backup?.absolutePath, backup?.let(::hash), stage?.let(::hash)) }
                writeAtomically(journal, json.encodeToString(Journal.serializer(), Journal(revision, slot.root.absolutePath, entries)).toByteArray())
                recorded = true
            }
            var moved = 0
            try {
                for ((target, stage, backup) in work) {
                    beforeMove(moved)
                    rejectLinks(target, slot.root)
                    require((target.takeIf { it.isFile }?.let(::hash)) == backup?.let(::hash) &&
                        (!target.exists() || target.isFile)) { "The current save changed during import" }
                    if (stage == null) require(target.delete()) { "The old save could not be replaced" } else move(stage, target)
                    moved++
                }
                for (folder in folders) require(folder.isDirectory || folder.mkdirs()) { "A save folder could not be created" }
                // The durable revision must commit while rollback copies still exist.
                commit()
                success = true
                if (journal != null) runCatching { recover(journal) { true } }
            } catch (e: Exception) {
                if (success) throw e // The revision committed; leave its marker for cleanup on restart.
                for ((target, _, backup) in work.take(moved).asReversed()) {
                    try {
                        rejectLinks(target, slot.root)
                        val current = target.takeIf { it.isFile }?.let(::hash)
                        val imported = destinations.firstOrNull { it.first == target }?.second?.hash
                        require((!target.exists() || target.isFile) &&
                            (current == null || current == imported || current == backup?.let(::hash))) {
                            "A save changed during rollback. Its recovery copies have been kept"
                        }
                        if (backup != null) restore(backup, target) else require(target.delete() || !target.exists())
                    } catch (rollback: Exception) { e.addSuppressed(rollback) }
                }
                if (journal != null && e.suppressed.isEmpty()) journal.delete()
                throw e
            }
        } finally {
            // Keep a backup when restoration itself failed; never discard the last safety copy.
            for ((target, stage, backup) in work) {
                if (recorded && journal?.exists() == true) continue
                stage?.delete()
                if (backup != null) {
                    val restored = runCatching {
                        target.isFile && target.length() == backup.length() &&
                            target.inputStream().use { SyncCrypto.sha256(it) } == backup.inputStream().use { SyncCrypto.sha256(it) }
                    }.getOrDefault(false)
                    if (success || restored) backup.delete()
                }
            }
        }
    }

    private fun hash(file: File): String = file.inputStream().use { SyncCrypto.sha256(it) }

    private fun restore(backup: File, target: File) {
        val stage = File.createTempFile(".fuse-import-", ".restore", target.parentFile)
        try { backup.copyTo(stage, overwrite = true); move(stage, target) } finally { stage.delete() }
    }

    private fun rejectLinks(target: File, root: File) {
        var path: File? = target.absoluteFile
        val boundary = root.absoluteFile
        while (path != null) {
            require(!Files.isSymbolicLink(path.toPath())) { "A save destination is a symbolic link" }
            if (path == boundary) break
            path = path.parentFile
        }
    }

    private fun move(from: File, to: File) {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}
