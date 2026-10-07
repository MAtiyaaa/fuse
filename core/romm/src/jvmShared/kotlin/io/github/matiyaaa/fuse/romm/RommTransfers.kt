package io.github.matiyaaa.fuse.romm

import io.github.matiyaaa.fuse.transfer.ExpectedHash
import io.github.matiyaaa.fuse.transfer.RangedDownload
import io.github.matiyaaa.fuse.transfer.TransferFailure
import io.github.matiyaaa.fuse.transfer.TransferFiles
import io.github.matiyaaa.fuse.transfer.TransferHandler
import io.github.matiyaaa.fuse.transfer.TransferInterrupted
import io.github.matiyaaa.fuse.transfer.TransferIo
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferPhase
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Downloads RomM games, their content and BIOS through Fuse's transfers, each file on its own (so
 * each carries on by range after a drop), checked by size and hash, and put in place only whole.
 */
class RommDownloadHandler(private val http: HttpClient, private val host: RommTransferHost) : TransferHandler {
    override val source = ROMM_SOURCE

    override suspend fun run(item: TransferItem, io: TransferIo) {
        val job = jobJson.decodeFromString(RommDownloadJob.serializer(), item.payload)
        val client = host.client(job.server) ?: throw TransferFailure("Fuse RomM is signed out. Sign in again in Settings, Addons, Fuse RomM.", retryable = false)
        val place = item.place ?: throw TransferFailure("Fuse RomM doesn't know where this goes.", retryable = false)
        val root = File(io.resolve(place))
        if (job.firmware != null) return firmware(client, job.firmware, root, io)

        val total = job.files.sumOf { it.file.sizeBytes }
        // Room for what is still to come, with a little to spare.
        val left = job.files.sumOf { f -> val dest = File(root, f.relative); if (dest.isFile && dest.length() == f.file.sizeBytes) 0L else f.file.sizeBytes - partOf(root, job, f).length() }
        val free = TransferFiles.freeSpace(root)
        if (free in 1 until left + SPARE) throw TransferFailure("There isn't room for this on ${place.volume?.label ?: "that drive"}: it needs ${gb(left)} and ${gb(free)} is free.")

        var done = 0L
        val placed = ArrayList<String>()
        for (f in job.files) {
            val dest = File(root, f.relative)
            val finalDest = if (job.newFolder) File(staging(root), f.relative) else dest
            if (dest.isFile && dest.length() == f.file.sizeBytes || finalDest.isFile && finalDest.length() == f.file.sizeBytes && job.newFolder) {
                done += f.file.sizeBytes
                io.progress(done, total)
                placed += dest.path
                continue
            }
            val part = partOf(root, job, f)
            RangedDownload.fetch(http, { client.fileUrl(client.base(), job.romId, f.file) }, part, f.file.sizeBytes.takeIf { it > 0 }, io, doneBefore = done, totalOverall = total) {
                client.authorize(this)
            }
            io.phase(TransferPhase.VERIFYING)
            TransferFiles.verify(part, f.file.sizeBytes.takeIf { it > 0 }, f.file.sha1?.let { ExpectedHash("sha1", it) } ?: f.file.md5?.let { ExpectedHash("md5", it) })
            io.phase(TransferPhase.PLACING)
            TransferFiles.place(part, finalDest)
            done += f.file.sizeBytes
            io.progress(done, total)
            placed += dest.path
        }
        if (job.newFolder) {
            io.phase(TransferPhase.PLACING)
            // The game's folder appears whole, in one step.
            val staged = staging(root)
            if (root.exists() && (root.list()?.isNotEmpty() == true)) throw TransferFailure("A folder named ${root.name} is already there, so it was left alone.", retryable = false)
            root.delete()
            try {
                Files.move(staged.toPath(), root.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), root.toPath())
            }
        }
        io.phase(TransferPhase.FINISHING)
        host.landed(job, item, placed)
    }

    private suspend fun firmware(client: RommClient, fw: RommFirmware, dest: File, io: TransferIo) {
        // A BIOS the person already has is theirs: never replaced.
        if (dest.exists()) throw TransferFailure("${dest.name} is already there, so it was left as it is.", retryable = false)
        val part = TransferFiles.partFor(dest)
        RangedDownload.fetch(http, { client.firmwareUrl(client.base(), fw) }, part, fw.sizeBytes.takeIf { it > 0 }, io) { client.authorize(this) }
        io.phase(TransferPhase.VERIFYING)
        TransferFiles.verify(part, fw.sizeBytes.takeIf { it > 0 }, fw.sha1?.let { ExpectedHash("sha1", it) } ?: fw.md5?.let { ExpectedHash("md5", it) })
        io.phase(TransferPhase.PLACING)
        TransferFiles.place(part, dest)
    }

    override suspend fun discard(item: TransferItem, io: TransferIo) {
        val job = runCatching { jobJson.decodeFromString(RommDownloadJob.serializer(), item.payload) }.getOrNull() ?: return
        val place = item.place ?: return
        val root = runCatching { File(io.resolve(place)) }.getOrNull() ?: return
        if (job.firmware != null) {
            TransferFiles.partFor(root).delete()
            return
        }
        for (f in job.files) partOf(root, job, f).delete()
        if (job.newFolder) staging(root).deleteRecursively()
    }

    private fun staging(root: File) = File(root.parentFile, ".${root.name}.fuse-download")

    private fun partOf(root: File, job: RommDownloadJob, f: RommDownloadFile): File {
        val dest = if (job.newFolder) File(staging(root), f.relative) else File(root, f.relative)
        return TransferFiles.partFor(dest)
    }

    private fun gb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "%.0f MB".format(bytes / 1048576.0)

    companion object {
        /** Left free on a drive after a download, so it isn't filled to the last byte. */
        const val SPARE = 64L * 1024 * 1024
    }
}

/**
 * Sends Fuse's games to RomM in chunks (carrying on from the last chunk after a drop or a restart),
 * leaving out files RomM already has exactly (by hash, never by name alone), then asks RomM to scan
 * when the sign-in allows it.
 */
class RommUploadHandler(private val host: RommTransferHost) : TransferHandler {
    override val source = ROMM_UPLOAD_SOURCE

    override suspend fun run(item: TransferItem, io: TransferIo) {
        var job = jobJson.decodeFromString(RommUploadJob.serializer(), item.payload)
        val client = host.client(job.server) ?: throw TransferFailure("Fuse RomM is signed out. Sign in again in Settings, Addons, Fuse RomM.", retryable = false)
        if (!client.may(RommScopes.ROMS_WRITE)) throw TransferFailure(UPLOAD_PERMISSION, retryable = false)
        if (!client.capabilities.chunkedUploads && client.capabilities.version.isNotEmpty()) {
            throw TransferFailure("This RomM server (${client.capabilities.version}) is too old to take uploads from Fuse. Update RomM, then try again.", retryable = false)
        }
        val total = job.files.sumOf { it.sizeBytes }
        var done = job.files.take(job.current).sumOf { it.sizeBytes } + job.chunksDone.toLong() * RommClient.CHUNK_BYTES
        io.progress(done.coerceAtMost(total), total)
        while (job.current < job.files.size) {
            val f = job.files[job.current]
            val file = File(f.path)
            if (!file.isFile) throw TransferFailure("${f.name} isn't there any more, so the upload stopped.", retryable = false)
            if (job.uploadId == null) {
                // RomM already has exactly this file: never sent twice.
                io.phase(TransferPhase.VERIFYING)
                val md5 = TransferFiles.digest(file, "md5")
                if (host.mirror.byMd5(md5).any { it.platformId == job.platformId }) {
                    job = job.copy(current = job.current + 1, chunksDone = 0, skipped = job.skipped + f.name)
                    done += f.sizeBytes
                    io.progress(done, total)
                    io.note(job.encode())
                    continue
                }
                // Content for a game RomM doesn't have yet waits for the game to be there first.
                val target = if (f.folder.isNotEmpty() && job.romId == null) waitForGame(client, job, io) else job.romId
                if (target != null && target != job.romId) job = job.copy(romId = target)
                val chunks = if (f.sizeBytes == 0L) 0 else ((f.sizeBytes + RommClient.CHUNK_BYTES - 1) / RommClient.CHUNK_BYTES).toInt()
                val id = guarded { client.startUpload(job.platformId, f.name, f.sizeBytes, chunks, romId = if (f.folder.isNotEmpty() || job.romId != null) job.romId else null, folder = f.folder) }
                job = job.copy(uploadId = id, chunksDone = 0)
                io.note(job.encode())
            }
            io.phase(TransferPhase.TRANSFERRING)
            val chunks = if (f.sizeBytes == 0L) 0 else ((f.sizeBytes + RommClient.CHUNK_BYTES - 1) / RommClient.CHUNK_BYTES).toInt()
            for (i in job.chunksDone until chunks) {
                val offset = i.toLong() * RommClient.CHUNK_BYTES
                val length = minOf(RommClient.CHUNK_BYTES.toLong(), f.sizeBytes - offset).toInt()
                val bytes = TransferFiles.readChunk(file, offset, length)
                io.throttle(length)
                guarded { client.uploadChunk(job.uploadId!!, i, bytes) }
                job = job.copy(chunksDone = i + 1)
                done += length
                io.progress(done, total)
                io.note(job.encode())
            }
            io.phase(TransferPhase.FINISHING)
            if (!finish(client, job, f)) {
                // RomM no longer knows this upload and hasn't got the file: it starts again from the beginning.
                job = job.copy(uploadId = null, chunksDone = 0)
                io.note(job.encode())
                done = job.files.take(job.current).sumOf { it.sizeBytes }
                io.progress(done, total)
                throw TransferInterrupted("RomM lost ${f.name} before it was put together. Fuse sends it again.")
            }
            job = job.copy(current = job.current + 1, uploadId = null, chunksDone = 0)
            io.note(job.encode())
        }
        if (job.scanAfter && client.may(RommScopes.TASKS_RUN) && client.capabilities.tokenScans) {
            runCatching { client.scan(listOf(job.platformId)) }
        }
        host.uploaded(job, item, job.romId)
    }

    /**
     * The game RomM made from the first file, once it has scanned it: looked for by its file name on
     * its system, a while at most, then the rest of the upload goes into it.
     */
    private suspend fun waitForGame(client: RommClient, job: RommUploadJob, io: TransferIo): Long {
        val lead = job.files.first { it.folder.isEmpty() }.name
        io.phase(TransferPhase.FINISHING)
        if (client.may(RommScopes.TASKS_RUN) && client.capabilities.tokenScans) runCatching { client.scan(listOf(job.platformId)) }
        repeat(40) {
            val found = runCatching { client.findByFileName(job.platformId, lead) }.getOrNull()
            if (found != null) return found.id
            delay(15_000)
        }
        throw TransferInterrupted("Waiting for RomM to add $lead before sending its other files.")
    }

    /**
     * Asks RomM to put [f] together. A large game takes RomM a while: when it doesn't answer in time,
     * the file is waited for (never sent twice), and an upload RomM no longer knows (its answer was
     * lost) counts as done once the game is there. False when RomM lost the upload without the file.
     */
    private suspend fun finish(client: RommClient, job: RommUploadJob, f: RommUploadFile): Boolean {
        try {
            client.completeUpload(job.uploadId!!)
            return true
        } catch (e: RommException) {
            when {
                e.code == RommClient.SLOW -> {
                    // Every piece arrived; RomM is still writing the file. Content inside a game can't be
                    // looked up by name, so its arrival is trusted.
                    if (f.folder.isNotEmpty() || landed(client, job, f, LANDING_WAIT_MS)) return true
                    throw TransferInterrupted("RomM is still putting ${f.name} together.", e)
                }
                e.status == 404 -> return f.folder.isEmpty() && landed(client, job, f, 0)
                else -> guarded<Unit> { throw e }
            }
        }
        return true
    }

    /** Whether RomM has [f] on its system, looking again for up to [waitMs]. */
    private suspend fun landed(client: RommClient, job: RommUploadJob, f: RommUploadFile, waitMs: Long): Boolean {
        val until = System.currentTimeMillis() + waitMs
        while (true) {
            if (runCatching { client.findByFileName(job.platformId, f.name) }.getOrNull() != null) return true
            if (System.currentTimeMillis() >= until) return false
            delay(LANDING_POLL_MS)
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: RommException) {
        when {
            // Slow (a big piece on a slow connection) or away: waits and carries on from the piece it was on.
            e.code == RommClient.SLOW || e.code == "offline" || e.status >= 500 || e.status == 429 -> throw TransferInterrupted(e.message ?: "RomM went away", e)
            e.forbidden -> throw TransferFailure(UPLOAD_PERMISSION, retryable = false)
            else -> throw TransferFailure(e.message ?: "RomM refused the upload.", retryable = e.status == 409)
        }
    }

    override suspend fun discard(item: TransferItem, io: TransferIo) {
        val job = runCatching { jobJson.decodeFromString(RommUploadJob.serializer(), item.payload) }.getOrNull() ?: return
        val id = job.uploadId ?: return
        host.client(job.server)?.cancelUpload(id)
    }

    companion object {
        const val UPLOAD_PERMISSION = "Fuse's RomM sign-in can only read. To upload, pair Fuse again in Settings, Addons, Fuse RomM and allow uploads."

        /** How long a finished upload RomM is still writing is waited for, and how often it is looked for. */
        internal var LANDING_WAIT_MS = 10 * 60_000L
        internal var LANDING_POLL_MS = 10_000L
    }
}


actual fun rommHandlers(http: HttpClient, host: RommTransferHost): List<TransferHandler> = listOf(RommDownloadHandler(http, host), RommUploadHandler(host))
