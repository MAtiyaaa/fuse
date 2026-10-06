package io.github.matiyaaa.fuse.romm

import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.db.Romm_rom
import io.github.matiyaaa.fuse.data.ioDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/** What a mirror sync did. */
data class MirrorResult(val added: Int, val changed: Int, val removed: Int, val total: Int, val full: Boolean)

/** How far a sync has come, for the person: what it is on, and counts when known. */
data class MirrorProgress(val label: String, val done: Int, val total: Int?)

/**
 * Fuse RomM's copy of a server's library, in Fuse's own database, so it opens at once, works
 * offline and scales to very large libraries: games are read and written a page at a time (never
 * the whole answer in memory), and only what changed since last time is asked for where the server
 * can. A game the server stopped listing stays, marked gone, never deleted: a server that is down
 * or half-scanned never empties the mirror.
 */
class RommMirror(
    private val db: FuseDatabase,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) {
    private val q get() = db.rommQueries
    private val filesSerializer = ListSerializer(RommFile.serializer())

    /** When the mirror last caught up with [server] (server time of the newest change seen), or 0. */
    suspend fun syncedUpTo(server: String): Long = withContext(dispatcher) {
        db.kvCacheQueries.get(NS, "upTo:$server").executeAsOneOrNull()?.value_json?.toLongOrNull() ?: 0L
    }

    /** When the mirror last finished a sync with [server], on this device's clock, or 0. */
    suspend fun syncedAt(server: String): Long = withContext(dispatcher) {
        db.kvCacheQueries.get(NS, "upTo:$server").executeAsOneOrNull()?.fetched_at ?: 0L
    }

    /**
     * Brings the mirror up to date with the server. [full] reads every game (the first time, or when
     * asked); otherwise only games changed since last time. New games after the first sync are marked
     * new, for "New in Your Library".
     */
    suspend fun sync(client: RommClient, server: String, full: Boolean = false, pageSize: Int = 250, progress: (MirrorProgress) -> Unit = {}): MirrorResult = withContext(dispatcher) {
        val since = syncedUpTo(server)
        // A read of the whole library that stopped part way (the app closed, the server went away, a
        // page too slow) carries on from the page it reached, never from the start again.
        val resume = cursor(server)
        val started = resume?.second ?: clock()
        val firstEver = since == 0L
        val incremental = resume == null && !full && since > 0 && client.capabilities.incremental
        progress(MirrorProgress("Systems", 0, null))
        val platforms = client.platforms()
        db.transaction {
            for (p in platforms) q.upsertPlatform(server, p.id, p.slug, p.fsSlug, p.name, p.romCount.toLong(), p.sizeBytes)
            if (platforms.isNotEmpty()) q.markPlatformsGone(server, platforms.map { it.id })
        }
        var added = 0
        var changed = 0
        var newest = since
        var offset = resume?.first ?: 0
        var total: Int? = null
        var size = pageSize
        while (true) {
            val page = try {
                client.roms(offset, size, updatedAfter = if (incremental) since - CLOCK_SKEW_MS else null)
            } catch (e: RommException) {
                // A server slow to put a page together gets asked for smaller ones.
                if (e.code == RommClient.SLOW && size > MIN_PAGE) { size = maxOf(MIN_PAGE, size / 2); continue }
                throw e
            }
            total = page.total ?: total
            if (page.items.isEmpty()) break
            db.transaction {
                for (r in page.items) {
                    val before = q.rom(server, r.id).executeAsOneOrNull()
                    write(server, r, started, markNew = !firstEver && before == null, keepFiles = before?.files_json)
                    if (before != null) changed++ else added++
                    if (r.updatedAt > newest) newest = r.updatedAt
                }
                if (!incremental) putCursor(server, offset + page.items.size, started)
            }
            offset += page.items.size
            progress(MirrorProgress("Games", offset, total))
            if (page.items.size < size || (total != null && offset >= total)) break
        }
        // Games that went: the server's own list of ids where it has one, else (a full read) anything not seen.
        var removed = 0
        val ids = if (client.capabilities.identifiers) runCatching { client.romIds() }.getOrNull() else null
        if (ids != null) {
            val there = ids.toHashSet()
            val gone = q.romIds(server).executeAsList().filter { it !in there }
            if (ids.isNotEmpty() || total == 0) {
                gone.chunked(500).forEach { q.markRomsGone(server, it) }
                removed = gone.size
            }
        } else if (!incremental && offset > 0) {
            val before = q.romCount(server).executeAsOne()
            q.markRomsGoneBefore(server, started)
            removed = (before - q.romCount(server).executeAsOne()).toInt()
        }
        progress(MirrorProgress("BIOS and firmware", 0, null))
        runCatching { client.firmware() }.onSuccess { fw ->
            db.transaction {
                q.clearFirmware(server)
                for (f in fw) q.upsertFirmware(server, f.id, f.platformId, f.fileName, f.sizeBytes, f.md5, f.sha1, f.crc, if (f.verified) 1 else 0)
            }
        }
        progress(MirrorProgress("Collections", 0, null))
        runCatching { client.collections() }.onSuccess { cs ->
            db.transaction {
                q.clearCollections(server)
                for (c in cs) q.upsertCollection(server, c.id, c.name, if (c.smart) 1 else 0, c.romIds.joinToString(","), c.cover)
            }
        }
        db.transaction {
            db.kvCacheQueries.put(NS, "upTo:$server", maxOf(newest, since).toString(), clock(), null)
            db.kvCacheQueries.delete(NS, "cursor:$server")
        }
        MirrorResult(added, changed, removed, q.romCount(server).executeAsOne().toInt(), full = !incremental)
    }

    /** Where an unfinished read of the whole library got to (games done, when it started), if one did. */
    private fun cursor(server: String): Pair<Int, Long>? {
        val v = db.kvCacheQueries.get(NS, "cursor:$server").executeAsOneOrNull()?.value_json ?: return null
        val done = v.substringBefore(':').toIntOrNull() ?: return null
        val at = v.substringAfter(':').toLongOrNull() ?: return null
        return done to at
    }

    private fun putCursor(server: String, done: Int, started: Long) =
        db.kvCacheQueries.put(NS, "cursor:$server", "$done:$started", clock(), null)

    /**
     * [romId] with its files, brought from the server the first time they are needed (the library
     * is read without them) and kept from then on. Null when the game isn't known; the game as kept
     * when the server can't be asked.
     */
    suspend fun withFiles(client: RommClient?, server: String, romId: Long): RommRom? {
        val kept = rom(server, romId) ?: return null
        if (kept.files.isNotEmpty() || client == null) return kept
        val fresh = runCatching { client.rom(romId) }.getOrNull() ?: return kept
        put(server, fresh)
        return rom(server, romId) ?: fresh
    }

    private fun write(server: String, r: RommRom, now: Long, markNew: Boolean, keepFiles: String? = null) {
        // A page of the library comes without files: the ones already brought for this game stay.
        val files = if (r.files.isEmpty() && keepFiles != null) keepFiles else RommParse.json.encodeToString(filesSerializer, r.files)
        q.insertRomIfAbsent(
            server, r.id, r.platformId, r.platformSlug, r.name, sortName(r.name), r.fsName, r.sizeBytes, r.md5, r.sha1, r.crc,
            r.titleId, r.regions.joinToString(SEP), r.revision, r.year?.toLong(), r.summary, r.genres.joinToString(SEP), r.developer,
            r.cover, r.logo, r.screenshot, files, if (r.multi) 1 else 0, r.createdAt, r.updatedAt, now, if (markNew) 1 else 0, now,
        )
        q.updateRom(
            platformId = r.platformId, platformSlug = r.platformSlug, name = r.name, sortName = sortName(r.name), fsName = r.fsName,
            sizeBytes = r.sizeBytes, md5 = r.md5, sha1 = r.sha1, crc = r.crc, titleId = r.titleId, regions = r.regions.joinToString(SEP),
            revision = r.revision, year = r.year?.toLong(), summary = r.summary, genres = r.genres.joinToString(SEP), developer = r.developer,
            cover = r.cover, logo = r.logo, screenshot = r.screenshot, filesJson = files, multi = if (r.multi) 1 else 0,
            createdAt = r.createdAt, updatedAt = r.updatedAt, checkedAt = now, server = server, id = r.id,
        )
    }

    /** Keeps one game as the server now has it (after a download or an upload). */
    suspend fun put(server: String, rom: RommRom) = withContext(dispatcher) { write(server, rom, clock(), markNew = false) }

    // ------------------------------------------------------------------ reading

    suspend fun platforms(server: String): List<RommPlatform> = withContext(dispatcher) {
        val counts = q.platformCounts(server).executeAsList().associate { it.platform_slug to (it.games to (it.bytes ?: 0L)) }
        q.platforms(server).executeAsList().filter { it.on_server == 1L }.map { p ->
            val (games, bytes) = counts[p.slug] ?: (0L to 0L)
            RommPlatform(p.id, p.slug, p.fs_slug, p.name, games.toInt(), bytes)
        }.filter { it.romCount > 0 }
    }

    suspend fun rom(server: String, id: Long): RommRom? = withContext(dispatcher) { q.rom(server, id).executeAsOneOrNull()?.let(::toRom) }

    suspend fun roms(server: String, ids: List<Long>): List<RommRom> = withContext(dispatcher) {
        ids.chunked(500).flatMap { q.romsWithIds(server, it).executeAsList().map(::toRom) }
    }

    suspend fun onPlatform(server: String, slug: String, limit: Int = Int.MAX_VALUE, offset: Int = 0): List<RommRom> = withContext(dispatcher) {
        q.romsOnPlatform(server, slug, limit.toLong(), offset.toLong()).executeAsList().map(::toRom)
    }

    suspend fun all(server: String): List<RommRom> = withContext(dispatcher) { q.allRoms(server).executeAsList().map(::toRom) }

    suspend fun recent(server: String, limit: Int = 40): List<RommRom> = withContext(dispatcher) { q.recentRoms(server, limit.toLong()).executeAsList().map(::toRom) }

    suspend fun newGames(server: String, limit: Int = 200): List<RommRom> = withContext(dispatcher) { q.newRoms(server, limit.toLong()).executeAsList().map(::toRom) }

    /** The person saw what is new: it stops being marked so. */
    suspend fun seenNew(server: String) = withContext(dispatcher) { q.clearNew(server) }

    suspend fun search(server: String, text: String, limit: Int = 100): List<RommRom> = withContext(dispatcher) {
        val pattern = "%" + text.trim().replace("%", "").replace("_", "") + "%"
        q.searchRoms(server, pattern, limit.toLong()).executeAsList().map(::toRom)
    }

    suspend fun byMd5(md5: String): List<RommRom> = withContext(dispatcher) { q.romsByMd5(md5.lowercase()).executeAsList().map(::toRom) }

    suspend fun count(server: String): Int = withContext(dispatcher) { q.romCount(server).executeAsOne().toInt() }

    suspend fun firmware(server: String): List<RommFirmware> = withContext(dispatcher) {
        q.firmware(server).executeAsList().map { RommFirmware(it.id, it.platform_id, it.file_name, it.size_bytes, it.md5, it.sha1, it.crc, it.verified == 1L) }
    }

    suspend fun collections(server: String): List<RommCollection> = withContext(dispatcher) {
        q.collections(server).executeAsList().map { c -> RommCollection(c.id, c.name, c.smart == 1L, c.rom_ids.split(',').mapNotNull { it.toLongOrNull() }, c.cover) }
    }

    /** Forgets everything about [server] (the person removed it, not just turned Fuse RomM off). */
    suspend fun forget(server: String) = withContext(dispatcher) {
        db.transaction {
            q.forgetRoms(server)
            q.forgetPlatforms(server)
            q.clearFirmware(server)
            q.clearCollections(server)
            db.kvCacheQueries.delete(NS, "upTo:$server")
        }
    }

    private fun toRom(r: Romm_rom): RommRom = RommRom(
        id = r.id,
        platformId = r.platform_id,
        platformSlug = r.platform_slug,
        name = r.name,
        fsName = r.fs_name,
        sizeBytes = r.size_bytes,
        md5 = r.md5,
        sha1 = r.sha1,
        crc = r.crc,
        titleId = r.title_id,
        regions = r.regions.split(SEP).filter { it.isNotEmpty() },
        revision = r.revision,
        year = r.year?.toInt(),
        summary = r.summary,
        genres = r.genres.split(SEP).filter { it.isNotEmpty() },
        developer = r.developer,
        cover = r.cover,
        logo = r.logo,
        screenshot = r.screenshot,
        files = runCatching { RommParse.json.decodeFromString(filesSerializer, r.files_json) }.getOrDefault(emptyList()),
        multi = r.multi == 1L,
        createdAt = r.created_at,
        updatedAt = r.updated_at,
    )

    companion object {
        private const val NS = "romm"
        private const val SEP = "|"

        /** A server's clock and this device's can disagree: changes are asked for a little before the last seen. */
        private const val CLOCK_SKEW_MS = 5 * 60_000L

        /** The smallest page asked for when the server is slow to answer bigger ones. */
        private const val MIN_PAGE = 25

        /** The name a game sorts by: leading articles aside, case aside. */
        fun sortName(name: String): String {
            val n = name.trim().lowercase()
            for (a in listOf("the ", "a ", "an ")) if (n.startsWith(a) && n.length > a.length + 1) return n.removePrefix(a)
            return n
        }
    }
}
