package io.github.matiyaaa.fuse.sync

import java.io.File

/**
 * Keeps a Switch SD card's emuMMC findable. The emuMMC itself (a hidden partition, or large files)
 * is never touched; what a phone's storage check or a card pulled out unejected tends to lose are
 * the small files beside it that tell Hekate where it is and that it is on: `emuMMC/emummc.ini`
 * and each `emuMMC/<RAWn or SDnn>/raw_based` or `file_based`. Lose them and Hekate says "emuMMC is
 * forced but not enabled" and won't start.
 *
 * Whenever Fuse sees a card whose emuMMC is set up and on, it keeps a copy of those small files
 * ([snapshot]) in its own storage, by the card's volume. Seen again without them ([lost]), they can
 * be put back exactly as they were ([restore]): only missing files, or an `emummc.ini` turned off,
 * are written, and nothing else on the card is.
 */
class EmuMmcGuard(private val store: File) {
    /** The files kept, by their path on the card, for a card seen healthy. */
    private fun keptFor(card: File) = File(store, safe(card.name.ifEmpty { card.path }))

    /** Takes a copy of [card]'s emuMMC settings files when its emuMMC is on; returns whether it did. */
    fun snapshot(card: File): Boolean {
        val files = settingsFiles(card)
        if (files.isEmpty() || !enabled(File(card, INI))) return false
        val kept = keptFor(card)
        val fresh = File(kept.parentFile, kept.name + ".new").apply { deleteRecursively(); mkdirs() }
        for (f in files) {
            val rel = f.relativeTo(card).path.replace(File.separatorChar, '/')
            File(fresh, rel).apply { parentFile.mkdirs() }.writeBytes(f.readBytes())
        }
        kept.deleteRecursively()
        return fresh.renameTo(kept)
    }

    /** The settings files [card] had when last seen healthy that it hasn't now (or an `emummc.ini` turned off). */
    fun lost(card: File): List<String> {
        val kept = keptFor(card)
        if (!kept.isDirectory) return emptyList()
        return kept.walkTopDown().filter { it.isFile }.map { it.relativeTo(kept).path.replace(File.separatorChar, '/') }.filter { rel ->
            val now = File(card, rel)
            !now.isFile || (rel == INI && !enabled(now))
        }.sorted().toList()
    }

    /** Puts [lost] files back as they were kept; the number written. Nothing else on the card changes. */
    fun restore(card: File): Int {
        val kept = keptFor(card)
        var n = 0
        for (rel in lost(card)) {
            val from = File(kept, rel)
            val to = File(card, rel)
            to.parentFile.mkdirs()
            val tmp = File(to.parentFile, ".${to.name}.fuse")
            tmp.writeBytes(from.readBytes())
            if (to.exists() && !to.delete()) { tmp.delete(); error("${to.path} couldn't be replaced") }
            if (!tmp.renameTo(to)) { tmp.delete(); error("${to.path} couldn't be written") }
            n++
        }
        return n
    }

    companion object {
        const val INI = "emuMMC/emummc.ini"
        private const val MAX_BYTES = 64L * 1024

        /** `emuMMC/emummc.ini` and each emuMMC folder's own small files (never its data or its Nintendo folder). */
        fun settingsFiles(card: File): List<File> {
            val root = File(card, "emuMMC").takeIf { it.isDirectory } ?: return emptyList()
            val out = ArrayList<File>()
            File(root, "emummc.ini").takeIf { it.isFile && it.length() <= MAX_BYTES }?.let(out::add)
            for (dir in root.listFiles().orEmpty().filter { it.isDirectory }) {
                for (f in dir.listFiles().orEmpty()) {
                    if (f.isFile && f.length() <= MAX_BYTES && (f.name == "raw_based" || f.name == "file_based")) out += f
                }
            }
            return out
        }

        /** Whether an `emummc.ini` says its emuMMC is on. */
        fun enabled(ini: File): Boolean = runCatching {
            ini.readLines().any { it.trim().replace(" ", "").equals("enabled=1", ignoreCase = true) }
        }.getOrDefault(false)

        private fun safe(name: String) = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
    }
}
