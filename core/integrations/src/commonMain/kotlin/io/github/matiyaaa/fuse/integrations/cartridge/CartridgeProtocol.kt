package io.github.matiyaaa.fuse.integrations.cartridge

import io.github.matiyaaa.fuse.integrations.FlexBoolean
import io.github.matiyaaa.fuse.integrations.FlexDouble
import io.github.matiyaaa.fuse.integrations.FlexInt
import io.github.matiyaaa.fuse.integrations.FlexLong
import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.github.matiyaaa.fuse.integrations.UrlCoding
import io.github.matiyaaa.fuse.integrations.github.RepoRef
import io.github.matiyaaa.fuse.integrations.github.SemVer
import io.github.matiyaaa.fuse.model.CartridgeDownload
import io.github.matiyaaa.fuse.model.CartridgeGame
import io.github.matiyaaa.fuse.model.CartridgeQueueItem
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.QueueState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Fuse <-> Cartridge bridge, as pure functions shared by Android and Linux: deep links into
 * Cartridge, the contract of Cartridge's read-only status provider (Android) and status file
 * (Linux), and mappers from both to [CartridgeStatus]. Nothing here carries server addresses,
 * tokens or passwords; Cartridge owns the RomM credentials.
 */
object CartridgeProtocol {

    /** Cartridge's Android package. */
    const val PACKAGE_NAME = "io.github.abdu2304.cartridge"

    /** First Cartridge version with deep links and the status provider. */
    const val MIN_BRIDGE_VERSION = "0.9.10"

    /** Bridge protocol version of Fuse's links (the `v` link parameter; links are the same in protocol 2). */
    const val PROTOCOL_VERSION = 1

    /** First protocol with the queue game by game and the downloaded games with RomM's details. */
    const val GAMES_PROTOCOL = 2

    const val SCHEME = "cartridge"

    /** Release source for "Install Cartridge" (publishes Cartridge-android.apk and Cartridge-x86_64.AppImage). */
    val RELEASE_REPO = RepoRef("MAtiyaaa", "cartridge")

    /** Upstream origin of Cartridge, for users who prefer its builds. */
    val UPSTREAM_REPO = RepoRef("abdu2304", "cartridge")

    const val ANDROID_ASSET_NAME = "Cartridge-android.apk"
    const val LINUX_ASSET_NAME = "Cartridge-x86_64.AppImage"

    // Status provider contract (Android ContentProvider).
    const val STATUS_AUTHORITY = "io.github.abdu2304.cartridge.status"
    const val READ_STATUS_PERMISSION = "io.github.abdu2304.cartridge.permission.READ_STATUS"
    const val PATH_STATUS = "/status"
    const val PATH_RECENT = "/recent"
    const val PATH_QUEUE = "/queue"
    const val PATH_GAMES = "/games"
    const val STATUS_URI = "content://$STATUS_AUTHORITY$PATH_STATUS"
    const val RECENT_URI = "content://$STATUS_AUTHORITY$PATH_RECENT"
    const val QUEUE_URI = "content://$STATUS_AUTHORITY$PATH_QUEUE"
    const val GAMES_URI = "content://$STATUS_AUTHORITY$PATH_GAMES"

    /** Largest status file Fuse reads (with protocol 2 it holds every downloaded game's details). */
    const val MAX_STATUS_FILE_BYTES = 16 * 1024 * 1024

    /** Columns of the single `/status` row. */
    object StatusColumns {
        /** int, currently 1. */
        const val PROTOCOL = "protocol"
        /** text, Cartridge's version name. */
        const val VERSION = "version"
        /** int 0/1, or null when unknown. */
        const val CONNECTED = "connected"
        const val ACTIVE_DOWNLOADS = "active_downloads"
        const val QUEUED_DOWNLOADS = "queued_downloads"
        /** real 0..1, or null. */
        const val PROGRESS = "progress"
        /** text or null. */
        const val CURRENT_TITLE = "current_title"
        /** text (platform slug) or null. */
        const val CURRENT_PLATFORM = "current_platform"
        /** int, epoch millis. */
        const val LIBRARY_CHANGED_AT = "library_changed_at"
        /** int, epoch millis. */
        const val UPDATED_AT = "updated_at"

        val ALL = listOf(
            PROTOCOL, VERSION, CONNECTED, ACTIVE_DOWNLOADS, QUEUED_DOWNLOADS, PROGRESS,
            CURRENT_TITLE, CURRENT_PLATFORM, LIBRARY_CHANGED_AT, UPDATED_AT,
        )
    }

    /** Columns of the `/recent` rows (finished downloads, newest first). */
    object RecentColumns {
        /** int. */
        const val ROM_ID = "rom_id"
        const val TITLE = "title"
        const val PLATFORM_SLUG = "platform_slug"
        /** text, local path of the downloaded game. */
        const val PATH = "path"
        /** int, epoch millis. */
        const val FINISHED_AT = "finished_at"

        val ALL = listOf(ROM_ID, TITLE, PLATFORM_SLUG, PATH, FINISHED_AT)
    }

    /** Columns of the `/queue` rows (protocol 2), in the Downloads page's order. */
    object QueueColumns {
        const val ROM_ID = "rom_id"
        const val TITLE = "title"
        const val PLATFORM_SLUG = "platform_slug"
        /** text: downloading, queued, paused, failed or done. */
        const val STATE = "state"
        const val RECEIVED = "received"
        /** int or null while unknown. */
        const val TOTAL = "total"
        const val POSITION = "position"
    }

    /** Columns of the `/games` rows (protocol 2): downloaded games with RomM's details. */
    object GameColumns {
        const val ROM_ID = "rom_id"
        const val PATH = "path"
        const val TITLE = "title"
        const val PLATFORM_SLUG = "platform_slug"
        const val SUMMARY = "summary"
        const val YEAR = "year"
        /** JSON array text. */
        const val GENRES = "genres"
        const val DEVELOPER = "developer"
        const val PUBLISHER = "publisher"
        /** int 0..100 or null. */
        const val RATING = "rating"
        const val PLAYERS = "players"
        /** JSON array text (RomM's franchises). */
        const val SERIES = "series"
        /** Content URIs of pictures the provider serves, or null. */
        const val COVER = "cover"
        const val LOGO = "logo"
        const val SCREENSHOT = "screenshot"
        const val UPDATED_AT = "updated_at"
    }

    /** True when [version] of Cartridge supports the bridge. */
    fun supportsBridge(version: String?): Boolean = version != null && SemVer.atLeast(version, MIN_BRIDGE_VERSION)

    // Deep links.

    /** `cartridge://<route>...` always ending with `from=fuse&v=1`. */
    fun deepLink(route: CartridgeRoute): String {
        val path: String = when (route) {
            CartridgeRoute.Home -> "home"
            CartridgeRoute.Library -> "library"
            CartridgeRoute.Downloads -> "downloads"
            CartridgeRoute.Consoles -> "consoles"
            CartridgeRoute.Settings -> "settings"
            CartridgeRoute.Sync -> "sync"
            is CartridgeRoute.Platform -> "platform/${UrlCoding.encode(route.slug)}"
            is CartridgeRoute.Game -> "game/${route.romId}"
            is CartridgeRoute.Bios -> "bios/${UrlCoding.encode(route.platformSlug)}"
            is CartridgeRoute.Search -> "search"
        }
        val params: List<Pair<String, String>> = when (route) {
            is CartridgeRoute.Search -> listOfNotNull("q" to route.query, route.platformSlug?.let { "platform" to it })
            else -> emptyList()
        }
        val query = (params + listOf("from" to "fuse", "v" to PROTOCOL_VERSION.toString()))
            .joinToString("&") { (k, v) -> "$k=${UrlCoding.encode(v)}" }
        return "$SCHEME://$path?$query"
    }

    /** Parses a `cartridge://` link back into a route, or null when it is not one Fuse knows. */
    fun parse(link: String): CartridgeRoute? {
        val prefix = "$SCHEME://"
        if (!link.startsWith(prefix, ignoreCase = true)) return null
        val rest = link.substring(prefix.length).substringBefore('#')
        val location = rest.substringBefore('?')
        val query = if ('?' in rest) parseQuery(rest.substringAfter('?')) else emptyMap()
        val segments = location.split('/').filter { it.isNotEmpty() }.map { UrlCoding.decode(it) }
        val head = segments.firstOrNull()?.lowercase() ?: return null
        val arg = segments.getOrNull(1)
        return when (head) {
            "home" -> CartridgeRoute.Home
            "library" -> CartridgeRoute.Library
            "downloads" -> CartridgeRoute.Downloads
            "consoles" -> CartridgeRoute.Consoles
            "settings" -> CartridgeRoute.Settings
            "sync" -> CartridgeRoute.Sync
            "platform" -> arg?.takeIf { it.isNotBlank() }?.let { CartridgeRoute.Platform(it) }
            "game" -> arg?.toLongOrNull()?.let { CartridgeRoute.Game(it) }
            "bios" -> arg?.takeIf { it.isNotBlank() }?.let { CartridgeRoute.Bios(it) }
            "search" -> CartridgeRoute.Search(query["q"].orEmpty(), query["platform"]?.takeIf { it.isNotBlank() })
            else -> null
        }
    }

    /** Query parameters of a link ("from", "v" included). */
    fun parseQuery(query: String): Map<String, String> = query.split('&')
        .filter { it.isNotEmpty() }
        .associate { part ->
            UrlCoding.decode(part.substringBefore('='), plusAsSpace = true) to
                UrlCoding.decode(part.substringAfter('=', ""), plusAsSpace = true)
        }

    // Status mapping.

    /**
     * Maps the `/status` row (as read from the cursor into a map) plus the `/recent` rows to the
     * model. [installedVersion] is the package's version name, used when the row has none.
     */
    fun statusFromRow(
        row: Map<String, Any?>,
        recent: List<Map<String, Any?>> = emptyList(),
        installedVersion: String? = null,
        checkedAt: Long = 0,
        queue: List<Map<String, Any?>> = emptyList(),
    ): CartridgeStatus {
        val protocol = row[StatusColumns.PROTOCOL].asLong()
        return CartridgeStatus(
            protocol = protocol?.toInt()?.coerceAtLeast(0) ?: 0,
            queue = queue.mapNotNull(::queueItemFromRow),
            installed = true,
            version = row[StatusColumns.VERSION].asText() ?: installedVersion,
            bridge = protocol != null && protocol >= 1,
            connected = row[StatusColumns.CONNECTED].asBoolean(),
            activeDownloads = row[StatusColumns.ACTIVE_DOWNLOADS].asLong()?.toInt()?.coerceAtLeast(0) ?: 0,
            queuedDownloads = row[StatusColumns.QUEUED_DOWNLOADS].asLong()?.toInt()?.coerceAtLeast(0) ?: 0,
            progress = row[StatusColumns.PROGRESS].asDouble()?.toFloat()?.coerceIn(0f, 1f),
            currentTitle = row[StatusColumns.CURRENT_TITLE].asText(),
            currentPlatform = row[StatusColumns.CURRENT_PLATFORM].asText(),
            libraryChangedAt = row[StatusColumns.LIBRARY_CHANGED_AT].asLong() ?: 0,
            recent = recent.mapNotNull(::downloadFromRow),
            checkedAt = checkedAt,
        )
    }

    /** Maps one `/recent` row; null when it has no rom id or title. */
    fun downloadFromRow(row: Map<String, Any?>): CartridgeDownload? {
        val romId = row[RecentColumns.ROM_ID].asLong() ?: return null
        val title = row[RecentColumns.TITLE].asText() ?: return null
        return CartridgeDownload(
            romId = romId,
            title = title,
            platformSlug = row[RecentColumns.PLATFORM_SLUG].asText().orEmpty(),
            path = row[RecentColumns.PATH].asText(),
            finishedAt = row[RecentColumns.FINISHED_AT].asLong() ?: 0,
        )
    }

    /** True when a status [row] says Cartridge has the queue and games tables. */
    fun hasGames(row: Map<String, Any?>): Boolean = (row[StatusColumns.PROTOCOL].asLong() ?: 0) >= GAMES_PROTOCOL

    /**
     * Maps one `/queue` row. Finished downloads (`done`) are left out (they are in the recent
     * list), and so are states this version of Fuse doesn't know.
     */
    fun queueItemFromRow(row: Map<String, Any?>): CartridgeQueueItem? = queueItem(
        romId = row[QueueColumns.ROM_ID].asLong(),
        title = row[QueueColumns.TITLE].asText(),
        platformSlug = row[QueueColumns.PLATFORM_SLUG].asText(),
        state = row[QueueColumns.STATE].asText(),
        received = row[QueueColumns.RECEIVED].asLong(),
        total = row[QueueColumns.TOTAL].asLong(),
    )

    private fun queueItem(romId: Long?, title: String?, platformSlug: String?, state: String?, received: Long?, total: Long?): CartridgeQueueItem? {
        val id = romId ?: return null
        val name = title?.takeIf { it.isNotBlank() } ?: return null
        val queueState = when (state?.trim()?.lowercase()) {
            "downloading" -> QueueState.DOWNLOADING
            "queued" -> QueueState.QUEUED
            "paused" -> QueueState.PAUSED
            "failed" -> QueueState.FAILED
            else -> return null
        }
        return CartridgeQueueItem(
            romId = id,
            title = name,
            platformSlug = platformSlug.orEmpty(),
            state = queueState,
            received = (received ?: 0).coerceAtLeast(0),
            total = total?.takeIf { it > 0 },
        )
    }

    /** Maps one `/games` row; null when it has no rom id or title. */
    fun gameFromRow(row: Map<String, Any?>): CartridgeGame? {
        val romId = row[GameColumns.ROM_ID].asLong() ?: return null
        val title = row[GameColumns.TITLE].asText() ?: return null
        return CartridgeGame(
            romId = romId,
            path = row[GameColumns.PATH].asText(),
            title = title,
            platformSlug = row[GameColumns.PLATFORM_SLUG].asText().orEmpty(),
            summary = row[GameColumns.SUMMARY].asText()?.take(MAX_SUMMARY),
            year = row[GameColumns.YEAR].asLong()?.toInt()?.takeIf { it in 1950..2100 },
            genres = textList(row[GameColumns.GENRES]),
            developer = row[GameColumns.DEVELOPER].asText(),
            publisher = row[GameColumns.PUBLISHER].asText(),
            rating = row[GameColumns.RATING].asLong()?.toInt()?.coerceIn(0, 100),
            players = row[GameColumns.PLAYERS].asText(),
            series = textList(row[GameColumns.SERIES]),
            cover = row[GameColumns.COVER].asText(),
            logo = row[GameColumns.LOGO].asText(),
            screenshot = row[GameColumns.SCREENSHOT].asText(),
            updatedAt = row[GameColumns.UPDATED_AT].asLong() ?: 0,
        )
    }

    /** A JSON array of names as Android's provider sends it (text), or a list already parsed. */
    private fun textList(value: Any?): List<String> {
        val items: List<String?> = when (value) {
            null -> emptyList()
            is List<*> -> value.map { it?.toString() }
            is String -> {
                val array = try {
                    FuseHttp.json.parseToJsonElement(value) as? JsonArray
                } catch (e: IllegalArgumentException) {
                    null
                }
                array?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }.orEmpty()
            }
            else -> emptyList()
        }
        return items.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct().take(MAX_NAMES)
    }

    private const val MAX_SUMMARY = 8000
    private const val MAX_NAMES = 12

    /** Status when Cartridge is installed but too old (or not answering) for the bridge. */
    fun installedWithoutBridge(version: String?, checkedAt: Long = 0): CartridgeStatus =
        CartridgeStatus(installed = true, version = version, bridge = false, checkedAt = checkedAt)

    // Linux status file.

    /**
     * `$XDG_STATE_HOME/cartridge/status.json`, or `$HOME/.local/state/cartridge/status.json` when
     * XDG_STATE_HOME is unset or not absolute. The app passes the environment values in.
     */
    fun statusFilePath(xdgStateHome: String?, home: String?): String? {
        val base = xdgStateHome?.takeIf { it.startsWith("/") }
            ?: home?.takeIf { it.isNotBlank() }?.let { it.trimEnd('/') + "/.local/state" }
            ?: return null
        return base.trimEnd('/') + "/cartridge/status.json"
    }

    /** The status file's snapshot and, from protocol 2, the downloaded games (null before). */
    data class Snapshot(val status: CartridgeStatus, val games: List<CartridgeGame>?)

    /** Parses the Linux status file; null when it is not valid JSON of the expected shape. */
    fun parseStatusFile(json: String, installedVersion: String? = null, checkedAt: Long = 0): CartridgeStatus? =
        parseSnapshot(json, installedVersion, checkedAt)?.status

    /** Parses the Linux status file with its games; null when it is not valid JSON of the expected shape. */
    fun parseSnapshot(json: String, installedVersion: String? = null, checkedAt: Long = 0): Snapshot? {
        val file = try {
            FuseHttp.json.decodeFromString(CartridgeStatusFile.serializer(), json)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val protocol = file.protocol ?: return null
        val games = if (protocol >= GAMES_PROTOCOL) {
            file.games.orEmpty().mapNotNull { g ->
                gameFromRow(
                    mapOf(
                        GameColumns.ROM_ID to g.romId, GameColumns.PATH to g.path, GameColumns.TITLE to g.title,
                        GameColumns.PLATFORM_SLUG to g.platformSlug, GameColumns.SUMMARY to g.summary,
                        GameColumns.YEAR to g.year, GameColumns.GENRES to g.genres, GameColumns.DEVELOPER to g.developer,
                        GameColumns.PUBLISHER to g.publisher, GameColumns.RATING to g.rating, GameColumns.PLAYERS to g.players,
                        GameColumns.SERIES to g.series, GameColumns.COVER to g.cover, GameColumns.LOGO to g.logo,
                        GameColumns.SCREENSHOT to g.screenshot, GameColumns.UPDATED_AT to g.updatedAt,
                    ),
                )
            }
        } else {
            null
        }
        val status = CartridgeStatus(
            protocol = protocol.coerceAtLeast(0),
            queue = file.queue.orEmpty().mapNotNull { q -> queueItem(q.romId, q.title, q.platformSlug, q.state, q.received, q.total) },
            installed = true,
            version = file.version ?: installedVersion,
            bridge = protocol >= 1,
            connected = file.connected,
            activeDownloads = (file.activeDownloads ?: 0).coerceAtLeast(0),
            queuedDownloads = (file.queuedDownloads ?: 0).coerceAtLeast(0),
            progress = file.progress?.toFloat()?.coerceIn(0f, 1f),
            currentTitle = file.currentTitle,
            currentPlatform = file.currentPlatform,
            libraryChangedAt = file.libraryChangedAt ?: 0,
            recent = file.recent.mapNotNull { r ->
                val id = r.romId ?: return@mapNotNull null
                val title = r.title ?: return@mapNotNull null
                CartridgeDownload(id, title, r.platformSlug.orEmpty(), r.path, r.finishedAt ?: 0)
            },
            checkedAt = checkedAt,
        )
        return Snapshot(status, games)
    }

    private fun Any?.asLong(): Long? = when (this) {
        null -> null
        is Long -> this
        is Int -> toLong()
        is Short -> toLong()
        is Byte -> toLong()
        is Double -> if (isNaN()) null else toLong()
        is Float -> if (isNaN()) null else toLong()
        is Boolean -> if (this) 1 else 0
        is String -> trim().toLongOrNull() ?: trim().toDoubleOrNull()?.toLong()
        else -> null
    }

    private fun Any?.asDouble(): Double? = when (this) {
        null -> null
        is Number -> toDouble().takeIf { !it.isNaN() }
        is String -> trim().toDoubleOrNull()
        else -> null
    }

    private fun Any?.asBoolean(): Boolean? = when (this) {
        null -> null
        is Boolean -> this
        is Number -> toLong() != 0L
        is String -> when (trim().lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
        else -> null
    }

    private fun Any?.asText(): String? = when (this) {
        null -> null
        is String -> takeIf { it.isNotBlank() }
        else -> toString()
    }
}

@Serializable
internal data class CartridgeRecentEntry(
    @Serializable(with = FlexLong::class) val romId: Long? = null,
    val title: String? = null,
    val platformSlug: String? = null,
    val path: String? = null,
    @Serializable(with = FlexLong::class) val finishedAt: Long? = null,
)

@Serializable
internal data class CartridgeStatusFile(
    @Serializable(with = FlexInt::class) val protocol: Int? = null,
    val version: String? = null,
    @Serializable(with = FlexBoolean::class) val connected: Boolean? = null,
    @Serializable(with = FlexInt::class) val activeDownloads: Int? = null,
    @Serializable(with = FlexInt::class) val queuedDownloads: Int? = null,
    @Serializable(with = FlexDouble::class) val progress: Double? = null,
    val currentTitle: String? = null,
    val currentPlatform: String? = null,
    @Serializable(with = FlexLong::class) val libraryChangedAt: Long? = null,
    @Serializable(with = FlexLong::class) val updatedAt: Long? = null,
    val recent: List<CartridgeRecentEntry> = emptyList(),
    val queue: List<CartridgeQueueEntry>? = null,
    val games: List<CartridgeGameEntry>? = null,
)

@Serializable
internal data class CartridgeQueueEntry(
    @Serializable(with = FlexLong::class) val romId: Long? = null,
    val title: String? = null,
    val platformSlug: String? = null,
    val state: String? = null,
    @Serializable(with = FlexLong::class) val received: Long? = null,
    @Serializable(with = FlexLong::class) val total: Long? = null,
    @Serializable(with = FlexInt::class) val position: Int? = null,
)

@Serializable
internal data class CartridgeGameEntry(
    @Serializable(with = FlexLong::class) val romId: Long? = null,
    val path: String? = null,
    val title: String? = null,
    val platformSlug: String? = null,
    val summary: String? = null,
    @Serializable(with = FlexInt::class) val year: Int? = null,
    val genres: List<String?>? = null,
    val developer: String? = null,
    val publisher: String? = null,
    @Serializable(with = FlexInt::class) val rating: Int? = null,
    val players: String? = null,
    val series: List<String?>? = null,
    val cover: String? = null,
    val logo: String? = null,
    val screenshot: String? = null,
    @Serializable(with = FlexLong::class) val updatedAt: Long? = null,
)
