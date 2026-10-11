package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The household kept whole, now and then, in a folder of the person's choosing (best another drive):
 * a snapshot of everything the host keeps but its files, beside every file the saves use, each
 * kept once for all the snapshots by its SHA-256. A snapshot is made a day after the last; it counts
 * as weekly a week after the last weekly and monthly a month after the last monthly, and the newest
 * [keepDaily], [keepWeekly] and [keepMonthly] of each are kept. Files no snapshot uses any more go;
 * one a snapshot still uses never does. Restoring makes a snapshot of the household as it is first,
 * so a restore can itself be undone.
 */
class HouseholdBackups(
    val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keepDaily: Int = 7,
    private val keepWeekly: Int = 4,
    private val keepMonthly: Int = 6,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val snapshotsDir = File(dir, "snapshots")
    private val objects = ContentStore(File(dir, "objects"))
    private val lock = Any()

    /** Every snapshot kept, newest first. */
    fun list(): List<HouseholdSnapshot> = snapshotsDir.listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { d -> runCatching { json.decodeFromString(HouseholdSnapshot.serializer(), File(d, MANIFEST).readText()) }.getOrNull() }
        .sortedByDescending { it.at }

    /** Whether a snapshot is due: a day since the newest. */
    fun due(): Boolean = list().firstOrNull()?.let { clock() - it.at >= DAY_MS - SLACK_MS } ?: true

    /**
     * Keeps the household as [store] has it now, as [kinds] ("manual" by a person; empty for the
     * schedule, which works out daily, weekly and monthly itself). Returns the snapshot.
     */
    fun snapshot(store: HostStore, kinds: List<String> = emptyList()): HouseholdSnapshot = synchronized(lock) {
        val now = clock()
        val all = list()
        val scheduled = kinds.isEmpty()
        val tags = if (!scheduled) kinds else buildList {
            add(DAILY)
            if (all.firstOrNull { WEEKLY in it.kinds }?.let { now - it.at >= WEEK_MS - SLACK_MS } != false) add(WEEKLY)
            if (all.firstOrNull { MONTHLY in it.kinds }?.let { now - it.at >= MONTH_MS - SLACK_MS } != false) add(MONTHLY)
        }
        val files = store.replicaFiles()
        val hashes = store.referencedObjects().sorted()
        // The saves' files first, so a snapshot never names one that isn't kept.
        var bytes = 0L
        for (h in hashes) {
            if (!objects.has(h) && store.content.has(h)) store.content.fileOf(h).inputStream().use { objects.put(it, expected = h) }
            if (objects.has(h)) bytes += objects.fileOf(h).length()
        }
        var name = NAME.format(Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))
        var n = 2
        while (File(snapshotsDir, name).exists()) name = NAME.format(Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC)) + "-" + n++
        val target = File(snapshotsDir, name)
        val tmp = File(snapshotsDir, ".$name.tmp")
        tmp.deleteRecursively()
        for ((path, content) in files) {
            File(tmp, "household/$path").apply { parentFile.mkdirs() }.writeBytes(content)
            bytes += content.size
        }
        val status = store.status(0, "")
        val snap = HouseholdSnapshot(
            name, now, tags, store.seq(), store.leadership.current.generation,
            status.profiles.size, status.devices.count { !it.revoked }, status.revisionCount, hashes, bytes,
        )
        File(tmp, MANIFEST).writeText(json.encodeToString(HouseholdSnapshot.serializer(), snap))
        // Whole or not at all: the folder appears only once everything is in it.
        check(tmp.renameTo(target)) { "Couldn't keep the snapshot in ${dir.path}" }
        prune()
        snap
    }

    /** Lets go of snapshots no kind of keeping wants any more, then of files no snapshot uses. */
    private fun prune() {
        val all = list()
        val keep = HashSet<String>()
        fun newest(kind: String, n: Int) = all.filter { kind in it.kinds }.take(n).forEach { keep += it.name }
        newest(DAILY, keepDaily)
        newest(WEEKLY, keepWeekly)
        newest(MONTHLY, keepMonthly)
        newest(MANUAL, KEEP_MANUAL)
        newest(BEFORE_RESTORE, KEEP_MANUAL)
        for (s in all) if (s.name !in keep) File(snapshotsDir, s.name).deleteRecursively()
        val used = list().flatMap { it.objects }.toSet()
        objects.collect(used)
    }

    /**
     * Puts the household back as [name] kept it, in [store] (the host's): the household as it is now
     * kept first as a snapshot of its own, the saves' files the snapshot needs put back in the
     * host's store, then everything else. Devices paired after the snapshot have to connect again.
     */
    fun restore(name: String, store: HostStore): HouseholdSnapshot = synchronized(lock) {
        val snap = list().firstOrNull { it.name == name } ?: error("No snapshot called $name")
        check(store.leadership.isLeader) { "Only the household's host is restored" }
        val missing = snap.objects.filter { !objects.has(it) }
        check(missing.isEmpty()) { "${missing.size} of the snapshot's files are missing from ${dir.path}" }
        snapshot(store, listOf(BEFORE_RESTORE))
        for (h in snap.objects) if (!store.content.has(h)) objects.fileOf(h).inputStream().use { store.content.put(it, expected = h) }
        val root = File(File(snapshotsDir, name), "household")
        val files = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path.replace('\\', '/') to it.readBytes() }
        store.restoreFrom(files)
        snap
    }

    companion object {
        const val DAILY = "daily"
        const val WEEKLY = "weekly"
        const val MONTHLY = "monthly"
        const val MANUAL = "manual"
        const val BEFORE_RESTORE = "before-restore"
        private const val KEEP_MANUAL = 5
        private const val MANIFEST = "snapshot.json"
        const val DAY_MS = 24 * 60 * 60_000L
        const val WEEK_MS = 7 * DAY_MS
        const val MONTH_MS = 30 * DAY_MS

        /** A little early still counts, so a snapshot a minute short of a day isn't put off a whole day. */
        private const val SLACK_MS = 60 * 60_000L
        private val NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    }
}
