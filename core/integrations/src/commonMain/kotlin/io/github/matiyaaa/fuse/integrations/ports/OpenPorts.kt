package io.github.matiyaaa.fuse.integrations.ports

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.model.AndroidPort
import io.github.matiyaaa.fuse.model.AndroidPortKind
import io.github.matiyaaa.fuse.model.AndroidPortStatus
import io.github.matiyaaa.fuse.model.AndroidPorts
import io.ktor.client.HttpClient
import io.ktor.client.request.url
import kotlinx.serialization.Serializable

/**
 * Open Ports Games' catalogue (openportsgames.com, its data under CC BY 4.0), read for the ports it
 * lists for Android: the feed at [OpenPortsClient.FEED_URL] (schema `openportsgames/catalog`), or
 * the snapshot of it inside Fuse (`ports/android/openports.json`, made by `tools/ports/openports.py`,
 * the same form). Only what a listing says is taken: each becomes an unchecked [AndroidPort]. Left
 * out are entries for other systems, tools (launchers), ones whose source is gone, and any with a
 * warning (one that ships the game's own files, rather than playing the person's copy).
 */
object OpenPorts {
    const val SCHEMA = "openportsgames/catalog"

    /** The newest version of the catalogue's form this Fuse reads. */
    const val VERSION = 2

    /** Credit for the listing, as its licence asks. */
    const val CREDIT = "Listed by Open Ports Games (openportsgames.com), CC BY 4.0"

    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,79}$")

    /** A catalogue read: its Android ports and when it was made (as it says). */
    data class Listing(val ports: List<AndroidPort>, val generated: String?)

    /** The Android ports in [text], or why it couldn't be read. */
    fun read(text: String): Result<Listing> {
        val feed = runCatching { FuseHttp.json.decodeFromString(Feed.serializer(), text) }.getOrElse {
            return Result.failure(IllegalArgumentException("Open Ports Games' list couldn't be read."))
        }
        if (feed.schema != SCHEMA) return Result.failure(IllegalArgumentException("Open Ports Games' list isn't the catalogue Fuse reads."))
        if (feed.version > VERSION) return Result.failure(IllegalArgumentException("Open Ports Games' list is in a newer form than this Fuse reads."))
        val seen = HashSet<String>()
        val ports = feed.ports.mapNotNull { p -> p.toPort()?.takeIf { seen.add(it.id) } }
        return Result.success(Listing(ports, feed.generatedAt))
    }

    private fun FeedPort.toPort(): AndroidPort? {
        if (ANDROID !in platforms || kind != "game") return null
        if (sourceDead == true || !warning.isNullOrBlank()) return null
        if (!ID.matches(id) || title.isBlank() || game.isBlank()) return null
        val repo = sources.firstOrNull { it.startsWith("https://") } ?: return null
        return AndroidPort(
            id = id,
            name = plain(title),
            game = plain(game),
            systems = originalSystems.mapNotNull { SYSTEMS[it] }.distinct(),
            kind = KINDS[portType] ?: AndroidPortKind.SOURCE_PORT,
            status = STATUSES[status] ?: AndroidPortStatus.BETA,
            repo = repo,
            licence = license?.spdx?.takeIf { it.isNotBlank() && it != "NOASSERTION" },
            about = notes?.let(::plain)?.takeIf { it.isNotEmpty() },
            listedVerified = verified,
        )
    }

    private const val ANDROID = "android"

    /**
     * A listing's text as Fuse shows text: a dash between words becomes a comma, one between
     * numbers a hyphen, and characters that take no room are left out.
     */
    fun plain(text: String): String = text
        .replace(Regex("\\s+[\u2014\u2013]\\s+"), ", ")
        .replace(Regex("(?<=\\d)[\u2014\u2013](?=\\d)"), "-")
        .replace(Regex("[\u2014\u2013]"), ", ")
        .replace(Regex("[\u200B-\u200D\u2060\uFEFF]"), "")
        .trim()

    /**
     * Open Ports Games' names for a game's original system, to the library's systems (Fuse's ids).
     * Names with none of Fuse's ("Other", "Unknown", "Atari") are left out: such a port is listed,
     * but matches no game.
     */
    val SYSTEMS: Map<String, String> = mapOf(
        "Amiga" to "amiga",
        "Arcade" to "arcade",
        "Commodore 64" to "c64",
        "Dreamcast" to "dc",
        "Game Boy" to "gb",
        "Game Boy Advance" to "gba",
        "Game Boy Color" to "gbc",
        "Game Gear" to "gamegear",
        "GameCube" to "ngc",
        "MS-DOS" to "dos",
        "Macintosh" to "mac",
        "Namco System 22" to "arcade",
        "Neo Geo" to "neogeo",
        "Nintendo 3DS" to "3ds",
        "Nintendo 64" to "n64",
        "Nintendo DS" to "nds",
        "Nintendo Entertainment System" to "nes",
        "Nintendo Switch" to "switch",
        "PC-98" to "pc98",
        "PlayStation" to "psx",
        "PlayStation 2" to "ps2",
        "PlayStation 3" to "ps3",
        "PlayStation 4" to "ps4",
        "PlayStation Portable" to "psp",
        "Sega 32X" to "sega32",
        "Sega CD / Mega-CD" to "segacd",
        "Sega Master System" to "sms",
        "Sega Mega Drive / Genesis" to "genesis",
        "Sega Saturn" to "saturn",
        "Super Nintendo" to "snes",
        "Virtual Boy" to "virtualboy",
        "Wii" to "wii",
        "Wii U" to "wiiu",
        "Windows" to "win",
        "Xbox" to "xbox",
        "Xbox 360" to "xbox360",
        "ZX Spectrum" to "zxs",
    )

    private val KINDS = mapOf(
        "recompilation" to AndroidPortKind.RECOMPILATION,
        "decompilation" to AndroidPortKind.DECOMPILATION,
        "source-port" to AndroidPortKind.SOURCE_PORT,
        "runtime-port" to AndroidPortKind.RUNTIME_PORT,
        "reimplementation" to AndroidPortKind.REIMPLEMENTATION,
    )

    private val STATUSES = mapOf(
        "stable" to AndroidPortStatus.STABLE,
        "beta" to AndroidPortStatus.BETA,
        "alpha" to AndroidPortStatus.ALPHA,
    )

    @Serializable
    private data class Feed(
        val schema: String = "",
        val version: Int = 0,
        val generatedAt: String? = null,
        val ports: List<FeedPort> = emptyList(),
    )

    @Serializable
    private data class FeedPort(
        val id: String = "",
        val kind: String = "game",
        val title: String = "",
        val game: String = "",
        val portType: String? = null,
        val platforms: List<String> = emptyList(),
        val status: String? = null,
        val sources: List<String> = emptyList(),
        val license: FeedLicence? = null,
        val verified: Boolean = false,
        val notes: String? = null,
        val originalSystems: List<String> = emptyList(),
        val sourceDead: Boolean? = null,
        val warning: String? = null,
    )

    @Serializable
    private data class FeedLicence(val spdx: String? = null)
}

/**
 * Checks Fuse's own Android entries ([io.github.matiyaaa.fuse.model.PortsIndex.android]) as
 * [PortsIndexReader] checks the rest: why [port] can't be offered, or null when it can.
 */
fun androidPortProblem(port: AndroidPort): String? {
    if (!Regex("^[a-z0-9][a-z0-9-]{0,79}$").matches(port.id)) return "its id isn't letters, digits and dashes"
    if (port.name.isBlank() || port.game.isBlank()) return "it has no name or game"
    if (AndroidPorts.githubRepo(port.repo) == null) return "its project isn't a GitHub repository over HTTPS"
    port.packageName?.let { if (!PACKAGE.matches(it)) return "its package isn't an Android package name" }
    port.asset?.let { a -> if (runCatching { Regex(a) }.isFailure) return "which APK it is can't be read" }
    if (port.roms.any { !Regex("^[0-9a-f]{16}$").matches(it) }) return "a ROM it is made for isn't said by its hash"
    if (port.systems.any { !Regex("^[a-z0-9]{1,20}$").matches(it) }) return "a system it plays is named oddly"
    return null
}

private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

/** Fetches Open Ports Games' catalogue, for the Android ports it lists. Never called before Android ports are opened. */
class OpenPortsClient(http: HttpClient, private val feedUrl: String = FEED_URL) {
    private val api = ProviderHttp(http, "Open Ports Games", limiter = null, secrets = { emptyList() })

    suspend fun fetch(): ApiResult<OpenPorts.Listing> {
        val raw = when (val r = api.execute { url(feedUrl) }) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return r
        }
        api.failureFor(raw)?.let { return it }
        val listing = OpenPorts.read(raw.body).getOrElse { return ApiResult.InvalidResponse(it.message ?: "Open Ports Games' list couldn't be read.") }
        return ApiResult.Success(listing)
    }

    companion object {
        const val FEED_URL = "https://openportsgames.com/api/ports.json"
    }
}
