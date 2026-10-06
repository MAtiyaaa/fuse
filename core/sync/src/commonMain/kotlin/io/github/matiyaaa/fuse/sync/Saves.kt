package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.Serializable

/** What kind of game data a set of files is. Each kind has its own revisions and its own rules. */
@Serializable
enum class SaveKind(val label: String) {
    /** In-game saves: battery saves, save files, a memory card's blocks for this game. */
    SAVE("Save"),

    /** Save states: the emulator's snapshot of the whole machine. Tied to its emulator and core. */
    STATE("Save state"),

    /** A memory card shared by every game on it (PlayStation 2, GameCube). */
    MEMORY_CARD("Memory card"),
}

/**
 * One file of a save, by its place in the save (never a device path) and its content: the
 * SHA-256 of its bytes and its size. Places are relative, use `/` and never `..`; see [SavePath].
 */
@Serializable
data class SaveFile(val path: String, val hash: String, val size: Long)

/**
 * A save at one moment: its files, and the [format] they are in (`retroarch.srm`, `duckstation.mcd`,
 * `ppsspp.savedata`...), which says which emulators can use it. Two manifests with the same files
 * are the same save, wherever and whenever they were made.
 */
@Serializable
data class SaveManifest(
    val format: String,
    val files: List<SaveFile>,
    /**
     * Empty folders inside a folder save (paths as [SaveFile.path]), so the save comes back with
     * its whole structure. Not part of [fingerprint]: an empty folder alone is never a new save.
     * Left out of the JSON when there are none, which is all an older host or device ever sees.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val folders: List<String> = emptyList(),
) {
    val size: Long get() = files.sumOf { it.size }

    /** A digest of the whole save, independent of file order: equal saves have equal fingerprints. */
    val fingerprint: String get() = files.sortedBy { it.path }.joinToString("|") { "${it.path}=${it.hash}" }.let { "$format#$it" }

    companion object {
        val EMPTY = SaveManifest("", emptyList())
    }
}

/** Why a revision was kept. Milestones and kept-for-safety copies are never cleaned up by age. */
@Serializable
enum class RevisionReason {
    /** Saved by playing. */
    PLAYED,

    /** The losing side of a conflict, kept so nothing is ever lost. */
    CONFLICT_COPY,

    /** What was on a device before an older revision was restored over it. */
    BEFORE_RESTORE,

    /**
     * Pinned by the person, as hosts before 0.3.4 kept it (the reason itself changed). Newer hosts
     * keep the reason and set [SaveRevision.pinned] instead.
     */
    MILESTONE,
}

/**
 * One version of one game's save for one profile. [parent] is the revision it was made from, so a
 * revision made from an older one than the newest is a conflict, whatever the clocks say.
 */
@Serializable
data class SaveRevision(
    val id: String,
    val profile: String,
    val game: String,
    val kind: SaveKind,
    val parent: String?,
    val device: String,
    val deviceName: String,
    val at: Hlc,
    val manifest: SaveManifest,
    /** The profile's play time on this game when it was saved, for telling saves apart. */
    val playSeconds: Long = 0,
    val reason: RevisionReason = RevisionReason.PLAYED,
    /** The game's name as the saving device showed it, for the Hub. */
    val title: String = "",
    /** Kept for good by the person ("Keep this save"): never cleaned up, whatever its [reason]. */
    val pinned: Boolean = false,
) {
    val size: Long get() = manifest.size

    /**
     * Whether this revision may be a slot's newest. Copies kept for safety (the other side of a
     * conflict, what was there before a restore) are history only: they never come down by
     * themselves, whenever they were made.
     */
    val canBeNewest: Boolean get() = reason == RevisionReason.PLAYED || reason == RevisionReason.MILESTONE

    /** Kept for good: pinned now, or by an older host that marked it a milestone. */
    val kept: Boolean get() = pinned || reason == RevisionReason.MILESTONE
}

/** Where a slot's history stands on one side: the newest revision, by id, or none. */
@Serializable
data class SlotHead(val game: String, val kind: SaveKind, val revision: String?)

/** What to do with one save on one device, given what it last synced and what the host has. */
sealed interface SyncDecision {
    /** Both sides have the same save. */
    data object UpToDate : SyncDecision

    /** Only this device changed it: its save goes up. */
    data object Upload : SyncDecision

    /** Only the host has something newer: it comes down. */
    data object Download : SyncDecision

    /**
     * Both changed it since they last agreed. Nothing is decided for the person: both are kept and
     * they choose, with the device names, times and play time to go by.
     */
    data object Conflict : SyncDecision
}

/**
 * Decides what happens to a save. [base] is the revision this device last synced (its local save
 * was made from it); [localChanged] says whether the local files differ from [base]; [hostHead] is
 * the host's newest revision. Clocks never decide: two devices that both changed a save since they
 * last agreed are a conflict even if one clock says it is much later.
 */
object SyncRules {
    fun decide(base: String?, localChanged: Boolean, hostHead: String?, sameContent: Boolean = false): SyncDecision = when {
        sameContent -> SyncDecision.UpToDate
        hostHead == base && !localChanged -> SyncDecision.UpToDate
        hostHead == base -> SyncDecision.Upload
        !localChanged -> SyncDecision.Download
        // The host moved on and this device changed its copy too.
        else -> SyncDecision.Conflict
    }
}

/**
 * Which revisions to keep, oldest first out: the newest [recent] of each slot, one a day for
 * [days] days, one a week for [weeks] weeks, and every milestone and conflict copy whatever its
 * age (those are only ever removed by the person). Files are stored by content, so revisions that
 * share files cost nothing extra.
 */
@Serializable
data class Retention(val recent: Int = 10, val days: Int = 14, val weeks: Int = 8) {
    fun keep(revisions: List<SaveRevision>, now: Long): Set<String> {
        val byNewest = revisions.sortedByDescending { it.at }
        val kept = LinkedHashSet<String>()
        byNewest.take(recent).forEach { kept += it.id }
        byNewest.filter { it.reason != RevisionReason.PLAYED || it.pinned }.forEach { kept += it.id }
        val day = 86_400_000L
        val seenDays = HashSet<Long>()
        val seenWeeks = HashSet<Long>()
        for (r in byNewest) {
            val age = now - r.at.millis
            val d = r.at.millis / day
            if (age <= days * day && seenDays.add(d)) kept += r.id
            val w = r.at.millis / (7 * day)
            if (age <= weeks * 7 * day && seenWeeks.add(w)) kept += r.id
        }
        // The newest is always kept, and so is every revision another kept one was made from
        // within the recent window (so a history reads as a chain).
        byNewest.firstOrNull()?.let { kept += it.id }
        return kept
    }
}

/**
 * Places inside a save, checked: relative, forward slashes, no `..`, no empty or dot parts, no
 * drive letters or backslashes, not too long. A client can never name a place outside the save.
 */
object SavePath {
    private val PART = Regex("^[^/\\\\:*?\"<>|\\u0000-\\u001f]{1,128}$")

    fun isSafe(path: String): Boolean {
        if (path.isEmpty() || path.length > 512 || path.startsWith("/") || path.contains('\\')) return false
        return path.split('/').all { it != "." && it != ".." && PART.matches(it) }
    }

    /** True for a SHA-256 written as 64 lower-case hex digits: the only names the content store takes. */
    fun isHash(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }
}
