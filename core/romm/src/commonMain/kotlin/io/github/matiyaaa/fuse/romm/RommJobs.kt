package io.github.matiyaaa.fuse.romm

import io.github.matiyaaa.fuse.transfer.TransferHandler
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The source name Fuse RomM's transfers carry. */
const val ROMM_SOURCE = "romm"

/** One file of a download: RomM's file and where it goes inside the transfer's place. */
@Serializable
data class RommDownloadFile(val file: RommFile, val relative: String)

/**
 * A Fuse RomM download, kept with its transfer: a game's files (all, or the missing discs, an
 * update, DLC), or one BIOS file. [newFolder] is true when the game's folder is made by this download
 * (its files are gathered beside it first and the folder appears whole, so a scan never sees half a game).
 */
@Serializable
data class RommDownloadJob(
    val server: String,
    val romId: Long = 0,
    val files: List<RommDownloadFile> = emptyList(),
    val firmware: RommFirmware? = null,
    val newFolder: Boolean = false,
    /** Fuse's platform id, for the scan once it lands. */
    val platform: String = "",
    /** For firmware an emulator installs: how, said once the file is here. */
    val install: String? = null,
)

/** One local file of an upload, and the folder it goes in inside the game on RomM ("" for the game itself). */
@Serializable
data class RommUploadFile(val path: String, val name: String, val folder: String = "", val sizeBytes: Long = 0)

/**
 * A Fuse RomM upload, kept with its transfer so it carries on after a restart: which file it is on,
 * RomM's id for that file's upload and how many chunks are in.
 */
@Serializable
data class RommUploadJob(
    val server: String,
    val platformId: Long,
    val files: List<RommUploadFile>,
    /** The game on RomM the files go into, when it is known (adding an update to a game RomM has). */
    val romId: Long? = null,
    val current: Int = 0,
    val uploadId: String? = null,
    val chunksDone: Int = 0,
    val scanAfter: Boolean = true,
    /** Files left out because RomM already has exactly them (by hash). */
    val skipped: List<String> = emptyList(),
)

/** What Fuse RomM's transfers need from the app: the client in use, the mirror, and what to do once something lands. */
interface RommTransferHost {
    /** The client for [server], signed in; null when Fuse RomM is off or signed out. */
    fun client(server: String): RommClient?
    val mirror: RommMirror

    /** A download finished: Fuse looks at the game's folder, links it to RomM and fills its details. */
    suspend fun landed(job: RommDownloadJob, item: TransferItem, paths: List<String>)

    /** An upload finished. */
    suspend fun uploaded(job: RommUploadJob, item: TransferItem, romId: Long?)
}

internal val jobJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun RommDownloadJob.encode(): String = jobJson.encodeToString(RommDownloadJob.serializer(), this)
fun RommUploadJob.encode(): String = jobJson.encodeToString(RommUploadJob.serializer(), this)

/** Uploads have their own source name, so they have their own handler. */
const val ROMM_UPLOAD_SOURCE = "romm-upload"

/** Fuse RomM's two transfer handlers (downloads, uploads), for Fuse's transfers. */
expect fun rommHandlers(http: HttpClient, host: RommTransferHost): List<TransferHandler>
