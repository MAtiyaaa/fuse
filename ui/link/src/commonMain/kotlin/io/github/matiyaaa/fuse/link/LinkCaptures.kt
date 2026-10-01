package io.github.matiyaaa.fuse.link

import kotlin.time.Instant
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A screenshot or recording on the device that Phone Link can list and send. */
data class LinkCapture(
    /** The device's own name for it (a MediaStore id, a path). It never leaves the device. */
    val key: String,
    /** The file's name, "Fuse 2026-10-01 13-45-02.png". */
    val name: String,
    val video: Boolean,
    val mime: String,
    val size: Long,
    /** When it was taken, epoch milliseconds. */
    val takenAt: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
)

/** A capture opened for reading. Its calls block, so they are made off the main thread; the reader closes it. */
interface CaptureReader : AutoCloseable {
    /** The whole file's length in bytes. */
    val length: Long

    /** Moves to [position] bytes from the start. */
    fun seek(position: Long)

    /** Reads up to [max] bytes into [buffer]; the number read, or -1 at the end. */
    fun read(buffer: ByteArray, max: Int): Int
}

/**
 * The device's screenshots and recordings, for Phone Link (Android: Pictures/Fuse and Movies/Fuse).
 * Read only: Phone Link never deletes or changes a capture.
 */
interface LinkCaptures {
    /** Every capture, in any order. */
    suspend fun list(): List<LinkCapture>

    /** A small JPEG of [key] for the phone's grid; null when there is none. */
    suspend fun thumbnail(key: String): ByteArray?

    /** Opens [key] for reading; null when it's gone. */
    suspend fun open(key: String): CaptureReader?

    /** Says when captures may have been added or removed. */
    val changes: Flow<Unit>
}

/**
 * Phone Link's side of the captures: opaque ids for the phone (a key the device uses is never
 * accepted from it), short-lived download links, and a cap on files sent at once so a game keeps
 * the storage.
 */
internal class CaptureShare(private val captures: LinkCaptures, private val clock: () -> Long) {
    private val keyById = BoundedMap<String, String>(20_000)
    private val idByKey = BoundedMap<String, String>(20_000)
    private val known = BoundedMap<String, LinkCapture>(20_000)
    private val downloads = BoundedMap<String, Download>(256)
    private val streams = Semaphore(MAX_STREAMS)

    private class Download(val keys: List<String>, val expiresAt: Long)

    @OptIn(FlowPreview::class)
    val changes: Flow<Unit> = captures.changes.debounce(500)

    suspend fun list(): JsonObject {
        val items = captures.list().sortedByDescending { it.takenAt }
        return buildJsonObject {
            put("available", true)
            putJsonArray("items") { items.forEach { add(item(it)) } }
        }
    }

    private fun item(c: LinkCapture): JsonObject {
        known[c.key] = c
        val id = idFor(c.key)
        return buildJsonObject {
            put("id", id)
            put("name", c.name)
            put("video", c.video)
            put("mime", c.mime)
            put("size", c.size)
            put("takenAt", c.takenAt)
            put("width", c.width)
            put("height", c.height)
            put("durationMs", c.durationMs)
            put("thumb", "/api/captures/$id/thumb")
            put("url", "/api/captures/$id")
        }
    }

    private fun idFor(key: String): String = idByKey[key] ?: token(12).also {
        idByKey[key] = it
        keyById[it] = key
    }

    /** The capture behind an id this server handed out. */
    fun capture(id: String): LinkCapture? = if (id.length in 8..40) keyById[id]?.let { known[it] } else null

    suspend fun thumbnail(id: String): ByteArray? = capture(id)?.let { captures.thumbnail(it.key) }

    suspend fun open(capture: LinkCapture): CaptureReader? = captures.open(capture.key)

    /** A link for downloading [ids] (one file, or a zip of several) that works for [DOWNLOAD_MS]. */
    fun mintDownload(ids: List<String>): Minted? {
        val chosen = ids.distinct().map { capture(it) ?: return null }
        if (chosen.isEmpty()) return null
        val token = token(16)
        downloads[token] = Download(chosen.map { it.key }, clock() + DOWNLOAD_MS)
        val name = if (chosen.size == 1) chosen.single().name else zipName()
        return Minted("/api/download/$token", name, chosen.sumOf { it.size }, chosen.size)
    }

    data class Minted(val url: String, val name: String, val size: Long, val count: Int)

    /** What a download link names, while it is still good. */
    fun download(token: String): List<LinkCapture>? {
        if (token.length !in 16..48) return null
        val d = downloads[token] ?: return null
        if (clock() > d.expiresAt) return null
        return d.keys.map { known[it] ?: return null }
    }

    /** Runs [block] once fewer than [MAX_STREAMS] files are being sent. */
    suspend fun <T> sending(block: suspend () -> T): T = streams.withPermit { block() }

    /** "Fuse captures 2026-10-01.zip" */
    fun zipName(): String {
        val date = Instant.fromEpochMilliseconds(clock()).toLocalDateTime(TimeZone.currentSystemDefault()).date
        return "Fuse captures $date.zip"
    }

    /** Names for the files in a zip: as they are, with " (2)" before the extension for a repeat. */
    fun zipEntryNames(items: List<LinkCapture>): List<String> {
        val used = mutableSetOf<String>()
        return items.map { c ->
            var name = c.name
            var n = 2
            while (!used.add(name.lowercase())) {
                val dot = c.name.lastIndexOf('.')
                name = if (dot > 0) "${c.name.substring(0, dot)} ($n)${c.name.substring(dot)}" else "${c.name} ($n)"
                n++
            }
            name
        }
    }

    private fun token(bytes: Int) = base64(secureRandom(bytes)).replace('+', '-').replace('/', '_').trimEnd('=')

    companion object {
        /** Files sent at the same time; more wait their turn. */
        const val MAX_STREAMS = 6

        /** Most captures in one download. */
        const val MAX_DOWNLOAD = 500

        /** How long a download link works. */
        const val DOWNLOAD_MS = 10 * 60_000L
    }
}

/** One byte range of a file (HTTP Range), or none. */
internal sealed interface ByteRange {
    /** No usable range: the whole file. */
    data object Whole : ByteRange

    data class Part(val first: Long, val last: Long) : ByteRange {
        val count: Long get() = last - first + 1
    }

    /** A range that starts past the end: 416. */
    data object Unsatisfiable : ByteRange

    companion object {
        /**
         * Reads a Range header for a file of [length] bytes. Only a single range is honoured; several
         * ranges or one that can't be read get the whole file, which HTTP allows.
         */
        fun parse(header: String?, length: Long): ByteRange {
            val h = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) } ?: return Whole
            val spec = h.substring(6).trim()
            if (',' in spec) return Whole
            val dash = spec.indexOf('-')
            if (dash < 0) return Whole
            val from = spec.substring(0, dash).trim()
            val to = spec.substring(dash + 1).trim()
            if (from.isEmpty()) {
                // The last n bytes.
                val suffix = to.toLongOrNull() ?: return Whole
                if (suffix <= 0 || length <= 0) return Unsatisfiable
                return Part(maxOf(0, length - suffix), length - 1)
            }
            val first = from.toLongOrNull()?.takeIf { it >= 0 } ?: return Whole
            if (first >= length) return Unsatisfiable
            val last = if (to.isEmpty()) length - 1 else (to.toLongOrNull() ?: return Whole)
            if (last < first) return Whole
            return Part(first, minOf(last, length - 1))
        }
    }
}
