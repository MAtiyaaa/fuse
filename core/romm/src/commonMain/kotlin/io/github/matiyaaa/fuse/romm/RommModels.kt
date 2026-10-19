package io.github.matiyaaa.fuse.romm

import io.github.matiyaaa.fuse.model.ContentKind
import kotlinx.serialization.Serializable

/**
 * What Fuse can ask of a RomM server. Read from its version and from what it answers, never assumed
 * from the version alone: RomM adds endpoints between releases, and a server can be a development build.
 */
@Serializable
data class RommCapabilities(
    /** RomM's version string ("4.4.1", "development"). */
    val version: String = "",
    /** The device pairing flow: Fuse shows a code and a QR, the person approves it in RomM (`/api/auth/device`). */
    val deviceAuth: Boolean = false,
    /** A pairing code made for a client token in RomM's web interface can be exchanged for it (`/api/client-tokens/exchange`). */
    val pairCodes: Boolean = false,
    /** Uploads in chunks, which can carry on after a drop (`/api/roms/upload/start`). */
    val chunkedUploads: Boolean = false,
    /**
     * A scan can be started with a token, not only from a web session: `POST /api/tasks/scan`, or
     * from RomM 5.3 (which removed that) its library scan task, `POST /api/tasks/run/scan_library`.
     */
    val tokenScans: Boolean = false,
    /** Scans start as RomM 5.3's task (`/api/tasks/run/scan_library`) rather than `/api/tasks/scan`. */
    val scanTask: Boolean = false,
    /** `updated_after` on the list of games, for bringing in only what changed. */
    val incremental: Boolean = false,
    /** RomM's save sync: devices and their saves (`/api/devices`, RomM 5). */
    val saveSync: Boolean = false,
    /** Every game's id in one call (`/api/roms/identifiers`), for noticing removals cheaply. */
    val identifiers: Boolean = false,
    /** The person's own marks on a game, hidden among them (`PUT /api/roms/{id}/props`). */
    val userProps: Boolean = false,
    /** Games added to and taken from a collection one by one (`POST`/`DELETE /api/collections/{id}/roms`, RomM 5.4). */
    val collectionRoms: Boolean = false,
    /** Play sessions from a client (`POST /api/play-sessions`). */
    val playSessions: Boolean = false,
    /** A task followed while it runs, a scan's counts among it (`GET /api/tasks/{task_id}`). */
    val taskStatus: Boolean = false,
    /** The person's own notes on a game, read, made and changed (`/api/roms/{id}/notes`, RomM 5.4). */
    val notes: Boolean = false,
) {
    val major: Int get() = version.removePrefix("v").substringBefore('.').toIntOrNull() ?: if (version == "development") 99 else 0
}

/** What RomM lets a token do. Fuse asks for the fewest: reading, unless the person uploads. */
object RommScopes {
    const val ME_READ = "me.read"
    const val ROMS_READ = "roms.read"
    const val ROMS_WRITE = "roms.write"
    const val ROMS_USER_READ = "roms.user.read"
    const val PLATFORMS_READ = "platforms.read"
    const val FIRMWARE_READ = "firmware.read"
    const val COLLECTIONS_READ = "collections.read"
    const val ROMS_USER_WRITE = "roms.user.write"
    const val COLLECTIONS_WRITE = "collections.write"
    const val ASSETS_READ = "assets.read"
    const val ASSETS_WRITE = "assets.write"
    const val DEVICES_READ = "devices.read"
    const val DEVICES_WRITE = "devices.write"
    const val TASKS_RUN = "tasks.run"

    /** Browsing, downloading games and BIOS, collections. */
    val READ: List<String> = listOf(ME_READ, ROMS_READ, ROMS_USER_READ, PLATFORMS_READ, FIRMWARE_READ, COLLECTIONS_READ)

    /** Also sending games to RomM. */
    val UPLOAD: List<String> = READ + ROMS_WRITE

    /** Also asking RomM to scan after an upload. */
    val UPLOAD_AND_SCAN: List<String> = UPLOAD + TASKS_RUN

    /**
     * RomM's save sync: the person's saves (assets) and this device's place in RomM's list of
     * devices. Asked for with every pairing, so save sync can be turned on without pairing again.
     */
    val SAVES: List<String> = listOf(ASSETS_READ, ASSETS_WRITE, DEVICES_READ, DEVICES_WRITE)

    /**
     * Sending the person's changes to RomM: favourites (RomM's favourites collection), games hidden
     * and played. Asked for with every pairing, so sending them can be turned on without pairing again.
     */
    val CHANGES: List<String> = listOf(ROMS_USER_WRITE, COLLECTIONS_WRITE)
}

/**
 * A Cloudflare Access service token for the RomM server behind it: its two headers
 * (`CF-Access-Client-Id`, `CF-Access-Client-Secret`, as Cloudflare documents service tokens) go
 * with every request to [host] only, never to another address. Kept only in Fuse's secret store.
 */
@Serializable
data class RommAccess(val host: String, val clientId: String, val clientSecret: String) {
    /** Whether a request to [url] carries the token: its host is [host], over https. */
    fun covers(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) return false
        val h = u.substringAfter("://").substringBefore('/').substringBefore('?').substringBeforeLast(':').lowercase()
        return host.isNotBlank() && h == host.trim().lowercase()
    }

    /** Never the token itself, wherever it is printed. */
    override fun toString(): String = "RommAccess(host=$host, clientId=(kept), clientSecret=(kept))"

    companion object {
        const val ID_HEADER = "CF-Access-Client-Id"
        const val SECRET_HEADER = "CF-Access-Client-Secret"

        /** The host of [address] ("https://romm.example.com:443/" is "romm.example.com"), or "". */
        fun hostOf(address: String): String =
            address.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBeforeLast(':').lowercase()
    }
}

/**
 * A task on the server as it runs ([RommClient.task]): RomM's job status (`queued`, `started`,
 * `finished`, `failed`, ...) and, for a scan, its counts so far.
 */
data class RommTask(
    val id: String,
    val status: String,
    val scan: RommScanStats? = null,
) {
    /** Over, however it went. */
    val done: Boolean get() = status in setOf("finished", "failed", "stopped", "canceled", "cancelled")
    val failed: Boolean get() = status == "failed" || status == "stopped" || status == "canceled" || status == "cancelled"
}

/** A scan's counts so far, as RomM keeps them (`scan_stats`). */
data class RommScanStats(
    val totalPlatforms: Int = 0,
    val scannedPlatforms: Int = 0,
    val totalRoms: Int = 0,
    val scannedRoms: Int = 0,
    val newRoms: Int = 0,
    val identifiedRoms: Int = 0,
)

/** How Fuse signs in to RomM. Kept only in Fuse's secret store. */
@Serializable
sealed interface RommCredential {
    /** A RomM client API token (preferred: long-lived, scoped, revocable in RomM). */
    @Serializable
    @kotlinx.serialization.SerialName("token")
    data class Token(val token: String, val scopes: List<String> = emptyList(), val deviceId: String? = null) : RommCredential

    /** A username and password, for servers without client tokens. */
    @Serializable
    @kotlinx.serialization.SerialName("password")
    data class Password(val username: String, val password: String) : RommCredential
}

/** A system on the server. */
@Serializable
data class RommPlatform(
    val id: Long,
    val slug: String,
    val fsSlug: String,
    val name: String,
    val romCount: Int = 0,
    val sizeBytes: Long = 0,
)

/** One file of a game on the server, as RomM lists it. [path] is its folder inside the game ("" at the top, "dlc", "update/1.04"). */
@Serializable
data class RommFile(
    val id: Long,
    val name: String,
    val path: String = "",
    val sizeBytes: Long = 0,
    val md5: String? = null,
    val sha1: String? = null,
    val crc: String? = null,
    /** RomM's category for it ("game", "dlc", "update", "patch", "manual"...), when it has one. */
    val category: String? = null,
) {
    /** The kind of content it is to Fuse: RomM's category, else the folder it sits in, else the game itself. */
    val kind: ContentKind get() = category?.let { c -> ContentKind.entries.firstOrNull { it.slug == c.lowercase() } }
        ?: path.split('/').firstOrNull()?.let { ContentKind.ofFolder(it) }
        ?: ContentKind.GAME
}

/**
 * A game on the server, slimmed to what Fuse keeps in its mirror. Pictures are RomM's own paths
 * (fetched with Fuse's sign-in and kept in Fuse's art), never another site's address.
 */
@Serializable
data class RommRom(
    val id: Long,
    val platformId: Long,
    val platformSlug: String,
    val name: String,
    val fsName: String,
    val sizeBytes: Long = 0,
    val md5: String? = null,
    val sha1: String? = null,
    val crc: String? = null,
    /** A console's own id for it (a Switch or 3DS title id, a PlayStation serial). */
    val titleId: String? = null,
    val regions: List<String> = emptyList(),
    val revision: String? = null,
    val year: Int? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val developer: String? = null,
    val cover: String? = null,
    val logo: String? = null,
    val screenshot: String? = null,
    val files: List<RommFile> = emptyList(),
    /** More than one file (a folder on the server, a multi-disc set). */
    val multi: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** The files that make the game playable (not manuals, soundtracks or screenshots). */
    val playable: List<RommFile> get() = files.filter { it.kind.holdsGameData }
}

/** A BIOS or firmware file the server keeps for a system. */
@Serializable
data class RommFirmware(
    val id: Long,
    val platformId: Long,
    val fileName: String,
    val sizeBytes: Long = 0,
    val md5: String? = null,
    val sha1: String? = null,
    val crc: String? = null,
    /** RomM checked it against known good dumps. */
    val verified: Boolean = false,
)

/** A collection on the server: the person's own, or a smart one. */
@Serializable
data class RommCollection(
    val id: String,
    val name: String,
    val smart: Boolean,
    val romIds: List<Long>,
    val cover: String? = null,
    /** RomM's favourites collection (`is_favorite`): the heart on a game in RomM. */
    val favorite: Boolean = false,
)

/** A play session of a game on RomM ([RommClient.playSessions]): epoch milliseconds. */
@Serializable
data class RommPlayed(val romId: Long, val startedAt: Long, val endedAt: Long)

/** A note on a game on RomM ([RommClient.notes]): its author's [username], kept to them unless [isPublic]. */
data class RommNote(val id: Long, val title: String, val content: String, val isPublic: Boolean, val username: String)

/** A page of games and how many there are in all. */
data class RommPage(val items: List<RommRom>, val total: Int?)

/** A pairing started from Fuse: what the person types or scans in RomM, and how Fuse waits for it. */
@Serializable
data class RommDeviceCode(
    val deviceCode: String,
    val userCode: String,
    /** The page in RomM's web interface to approve it on, with the code filled in (for the QR). */
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

/** Where a pairing stands while Fuse waits for the person to approve it. */
sealed interface PairingState {
    data object Waiting : PairingState
    data object SlowDown : PairingState
    data class Approved(val credential: RommCredential.Token) : PairingState
    data object Denied : PairingState
    data object Expired : PairingState
}

/** Something RomM said no to, with words for the person; [status] is the HTTP status. */
class RommException(message: String, val status: Int = 0, val code: String = "", cause: Throwable? = null) : Exception(message, cause) {
    /** The server is there but this account (or token) may not do this. */
    val forbidden: Boolean get() = status == 401 || status == 403
}

/** A save state on RomM: one state file of a game, made by an emulator (and, for RetroArch, a core). */
@Serializable
data class RommState(
    val id: Long,
    val romId: Long,
    val fileName: String,
    val sizeBytes: Long,
    val contentHash: String?,
    val emulator: String?,
    val updatedAt: String,
) {
    /** Its file name without the " [date]" RomM may add to it, to match a slot's file here. */
    val baseName: String get() = fileName.replace(Regex(" \\[[^\\]]*\\](?=\\.[^.]*$|$)"), "")
}

/**
 * One of the signed-in person's saves on RomM: one file for one game, in a [slot] (Fuse uses
 * "autosave"), with RomM's MD5 of its content ([contentHash]) and when it was last changed
 * ([updatedAt], ISO 8601, which sorts by time).
 */
data class RommSave(
    val id: Long,
    val romId: Long,
    val fileName: String,
    val sizeBytes: Long,
    val contentHash: String?,
    val slot: String?,
    val emulator: String?,
    val updatedAt: String,
    val originDevice: String?,
    val originDeviceName: String?,
)
