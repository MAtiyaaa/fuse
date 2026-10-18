package io.github.matiyaaa.fuse.sync

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A save laid into its [LocalSlot] whole, replacing the one there. */
internal object SlotWriter {
    /**
     * [files] (each a name in the save and what writes it) into [slot]: every file written beside
     * where it goes and then moved over it, and once all are in, the files of the save it replaced
     * that the new one hasn't got taken away, with the folders they leave empty. [folders] are the
     * new save's empty folders. A file with nowhere safe to go (outside the save's folders) stops it
     * before anything is moved. The caller keeps the save that was here first.
     */
    fun lay(slot: LocalSlot, files: List<Pair<String, (File) -> Unit>>, folders: List<String> = emptyList()) {
        val staged = ArrayList<Pair<File, File>>()
        try {
            for ((name, write) in files) {
                val target = slot.target(name) ?: throw IllegalStateException("A file in the save has nowhere safe to go: $name")
                target.parentFile.mkdirs()
                val tmp = File(target.parentFile, ".${target.name}.fuse-in")
                write(tmp)
                staged += tmp to target
            }
            for (folder in folders) slot.target(folder)?.mkdirs()
            for ((tmp, target) in staged) move(tmp, target)
        } finally {
            staged.forEach { (tmp, _) -> tmp.delete() }
        }
        val keep = files.mapNotNullTo(HashSet()) { slot.target(it.first)?.canonicalFile }
        val stale = slot.files.map { it.file }.filter { it.canonicalFile !in keep }
        stale.forEach { it.delete() }
        prune(slot, stale)
        slot.sink?.invoke()
    }

    /** The folders [gone] leave empty, from each one's own up to (never including) the save's own folder. */
    private fun prune(slot: LocalSlot, gone: List<File>) {
        for (f in gone) {
            val boundary = slot.boundaryOf(f).canonicalFile
            var dir = f.parentFile?.canonicalFile
            while (dir != null && dir != boundary && dir.path.startsWith(boundary.path + File.separator)) {
                if (!dir.isDirectory || dir.list()?.isNotEmpty() != false || !dir.delete()) break
                dir = dir.parentFile
            }
        }
    }

    fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** An [OldSave] brought into use: copied to where the emulator reads it now, the one there kept aside first. */
object OldSaveMove {
    /**
     * [old]'s save into its folder in use, [keptIn] holding what was there before (when there was
     * anything); the old folder is left as it is. Returns where the replaced save was kept, or null.
     */
    fun bringIn(game: GameKey, old: OldSave, keptIn: File): File? {
        val from = Slots.of(game, old.spot)
        check(from.files.isNotEmpty()) { "That folder has no save of it any more." }
        val to = Slots.of(game, old.inUse)
        val kept = if (to.files.isEmpty()) null else keptIn.also { dir ->
            for (f in to.files) f.file.copyTo(File(dir, f.path).also { it.parentFile.mkdirs() }, overwrite = true)
        }
        SlotWriter.lay(to, from.files.map { f -> f.path to { t: File -> f.file.copyTo(t, overwrite = true); Unit } }, from.folders)
        return kept
    }
}
