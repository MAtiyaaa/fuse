package io.github.matiyaaa.fuse.jellyfin

import io.github.matiyaaa.fuse.transfer.DriveMissing
import io.github.matiyaaa.fuse.transfer.RangedDownload
import io.github.matiyaaa.fuse.transfer.TransferFailure
import io.github.matiyaaa.fuse.transfer.TransferFiles
import io.github.matiyaaa.fuse.transfer.TransferHandler
import io.github.matiyaaa.fuse.transfer.TransferInterrupted
import io.github.matiyaaa.fuse.transfer.TransferIo
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferPhase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps a film or episode on this device: the original file as the server has it (so nothing is
 * converted and every track stays), carried on from where it stopped when the connection drops,
 * checked against its size and moved into place whole. The subtitles the server keeps beside the
 * video come along as files of their own, and its pictures too, so it shows as itself offline.
 */
class JellyfinDownloadHandler(private val http: HttpClient, private val host: OfflineHost) : TransferHandler {
    override val source = JELLYFIN_SOURCE

    override suspend fun run(item: TransferItem, io: TransferIo) {
        val job = decodeJellyfinJob(item.payload) ?: throw TransferFailure("Fuse can't read what this download was.", retryable = false)
        val place = item.place ?: throw TransferFailure("Fuse doesn't know where this goes.", retryable = false)
        val folder = File(io.resolve(place))
        val video = File(folder, job.fileName)
        val size = job.meta.sizeBytes.takeIf { it > 0 }
        val part = TransferFiles.partFor(video)
        if (!(video.isFile && size != null && video.length() == size)) {
            withContext(Dispatchers.IO) { folder.mkdirs() }
            val left = (size ?: 0) - (if (part.isFile) part.length() else 0)
            val free = TransferFiles.freeSpace(folder)
            if (size != null && free in 1 until left + SPARE) {
                throw TransferFailure("There isn't room for this on ${place.volume?.label ?: "that drive"}: it needs ${gb(left)} and ${gb(free)} is free.")
            }
            // The token is the same on every route; the address is asked again on each try.
            val auth = authorizationFor(job.server)
            RangedDownload.fetch(http, { downloadUrl(session(job.server).first, job.itemId) }, part, size, io) {
                header(HttpHeaders.Authorization, auth)
            }
            io.phase(TransferPhase.VERIFYING)
            TransferFiles.verify(part, size, null)
            io.phase(TransferPhase.PLACING)
            TransferFiles.place(part, video)
        }
        io.phase(TransferPhase.FINISHING)
        val (base, account) = runCatching { session(job.server) }.getOrNull() ?: (null to null)
        var meta = job.meta
        if (base != null && account != null) {
            // Small and optional: a subtitle or picture that doesn't come never fails the download.
            val subs = meta.subtitles.map { s ->
                if (s.embeddedOrder >= 0 || s.ext.isEmpty() || meta.mediaSourceId == null) return@map s
                val name = OfflinePlan.subtitleName(job.fileName, s)
                val ok = small(subtitleUrl(base, job.itemId, meta.mediaSourceId!!, s.index, s.ext), File(folder, name), host.authorization(account.token))
                if (ok) s.copy(file = name) else s
            }
            meta = meta.copy(
                subtitles = subs,
                poster = art(base, meta.posterArt, 600, folder, OfflinePlan.artName(job.fileName, "poster")),
                backdrop = art(base, meta.backdropArt, 1920, folder, OfflinePlan.artName(job.fileName, "backdrop")),
                logo = art(base, meta.logoArt, 800, folder, OfflinePlan.artName(job.fileName, "logo")),
                thumb = art(base, meta.thumbArt, 960, folder, OfflinePlan.artName(job.fileName, "thumb")),
            )
        }
        host.landed(job, item, video.path, meta)
    }

    override suspend fun discard(item: TransferItem, io: TransferIo) {
        val job = decodeJellyfinJob(item.payload) ?: return
        val place = item.place ?: return
        val folder = runCatching { File(io.resolve(place)) }.getOrNull() ?: return
        withContext(Dispatchers.IO) {
            TransferFiles.partFor(File(folder, job.fileName)).delete()
            // The folder Fuse made for it goes too, when nothing else is in it.
            if (folder.list()?.isEmpty() == true) folder.delete()
        }
    }

    private suspend fun session(server: String): Pair<String, Account> = try {
        host.session(server)
    } catch (c: CancellationException) {
        throw c
    } catch (e: JellyfinException) {
        when (e.kind) {
            JellyfinException.Kind.AUTH -> throw TransferFailure("Jellyfin signed this device out. Sign in again in Settings, Addons, Jellyfin.", retryable = false)
            else -> throw TransferInterrupted(e.message ?: "Jellyfin can't be reached", e)
        }
    }

    private suspend fun authorizationFor(server: String): String = host.authorization(session(server).second.token)

    private suspend fun small(url: String, to: File, auth: String): Boolean = withContext(Dispatchers.IO) {
        if (to.isFile && to.length() > 0) return@withContext true
        runCatching {
            val resp = http.get(url) { header(HttpHeaders.Authorization, auth) }
            if (!resp.status.isSuccess()) return@runCatching false
            val bytes = resp.bodyAsBytes()
            if (bytes.isEmpty()) return@runCatching false
            val tmp = File(to.parentFile, ".${to.name}.fuse-part")
            tmp.writeBytes(bytes)
            tmp.renameTo(to) || run { tmp.delete(); false }
        }.getOrDefault(false)
    }

    private suspend fun art(base: String, ref: OfflineArtRef?, width: Int, folder: File, name: String): String? {
        val art = ref?.toArt() ?: return null
        val url = buildString {
            append(base.trimEnd('/')).append("/Items/").append(art.itemId).append("/Images/").append(art.kind.jellyfin)
            if (art.kind == ArtKind.BACKDROP) append('/').append(art.index)
            append("?tag=").append(art.tag).append("&fillWidth=").append(width).append("&quality=90")
        }
        // Pictures need no sign-in, so no token rides along.
        return name.takeIf { small(url, File(folder, name), "") }
    }

    private fun downloadUrl(base: String, id: String) = "${base.trimEnd('/')}/Items/$id/Download"

    private fun subtitleUrl(base: String, id: String, ms: String, index: Int, ext: String) = "${base.trimEnd('/')}/Videos/$id/$ms/Subtitles/$index/0/Stream.$ext"

    private fun gb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "${bytes / (1L shl 20)} MB"

    private companion object {
        const val SPARE = 64L * 1024 * 1024
    }
}

/**
 * Moves a kept film or episode to another drive: every file copied to the new folder beside a
 * partial name, checked by size, then named, and only then is the old copy removed, so a drive
 * pulled half way leaves the film where it was. It stays the same download throughout: its row
 * follows it, resume point and all.
 */
class OfflineMoveHandler(private val host: OfflineHost) : TransferHandler {
    override val source = OFFLINE_MOVE_SOURCE

    override suspend fun run(item: TransferItem, io: TransferIo) {
        val job = decodeMoveJob(item.payload) ?: throw TransferFailure("Fuse can't read what this move was.", retryable = false)
        val toPlace = item.place ?: throw TransferFailure("Fuse doesn't know where this goes.", retryable = false)
        val from = File(io.resolve(job.from))
        val to = File(io.resolve(toPlace))
        if (from.canonicalPath == to.canonicalPath) {
            host.moved(job, to.path, toPlace)
            return
        }
        withContext(Dispatchers.IO) {
            to.mkdirs()
            val sources = job.files.map { File(from, it) }.filter { it.isFile }
            if (sources.isEmpty()) throw TransferFailure("The files to move aren't there any more.", retryable = false)
            val total = sources.sumOf { it.length() }
            val free = TransferFiles.freeSpace(to)
            if (free in 1 until total) throw TransferFailure("There isn't room on ${toPlace.volume?.label ?: "that drive"} for this.")
            var done = 0L
            io.phase(TransferPhase.TRANSFERRING)
            for (src in sources) {
                if (!src.exists()) throw DriveMissing(job.from.volume?.label ?: "The drive it was on")
                val dest = File(to, src.name)
                if (dest.isFile && dest.length() == src.length()) {
                    done += src.length()
                    io.progress(done, total)
                    continue
                }
                val part = TransferFiles.partFor(dest)
                try {
                    FileInputStream(src).use { input ->
                        FileOutputStream(part).use { out ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                io.progress(done, total)
                            }
                            out.fd.sync()
                        }
                    }
                } catch (e: IOException) {
                    if (!from.exists()) throw DriveMissing(job.from.volume?.label ?: "The drive it was on")
                    if (!to.exists()) throw DriveMissing(toPlace.volume?.label ?: "The drive it goes to")
                    throw TransferInterrupted(e.message ?: "Copying failed", e)
                }
                io.phase(TransferPhase.VERIFYING)
                TransferFiles.verify(part, src.length(), null)
                TransferFiles.place(part, dest, replace = true)
                io.phase(TransferPhase.TRANSFERRING)
            }
        }
        io.phase(TransferPhase.FINISHING)
        host.moved(job, to.path, toPlace)
        // Only now, with the new copy whole and known, does the old one go.
        withContext(Dispatchers.IO) {
            for (name in job.files) File(from, name).delete()
            if (from.list()?.isEmpty() == true) from.delete()
            from.parentFile?.let { season -> if (season.list()?.isEmpty() == true) season.delete() }
        }
    }

    override suspend fun discard(item: TransferItem, io: TransferIo) {
        val job = decodeMoveJob(item.payload) ?: return
        val place = item.place ?: return
        val to = runCatching { File(io.resolve(place)) }.getOrNull() ?: return
        withContext(Dispatchers.IO) { for (name in job.files) TransferFiles.partFor(File(to, name)).delete() }
    }
}

actual fun offlineHandlers(http: HttpClient, host: OfflineHost): List<TransferHandler> =
    listOf(JellyfinDownloadHandler(http, host), OfflineMoveHandler(host))
