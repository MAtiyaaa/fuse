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
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import kotlinx.serialization.Serializable

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

    /** Bridge protocol version Fuse speaks (the `protocol` column / field and the `v` link parameter). */
    const val PROTOCOL_VERSION = 1

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
    const val STATUS_URI = "content://$STATUS_AUTHORITY$PATH_STATUS"
    const val RECENT_URI = "content://$STATUS_AUTHORITY$PATH_RECENT"

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
    ): CartridgeStatus {
        val protocol = row[StatusColumns.PROTOCOL].asLong()
        return CartridgeStatus(
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

    /** Parses the Linux status file; null when it is not valid JSON of the expected shape. */
    fun parseStatusFile(json: String, installedVersion: String? = null, checkedAt: Long = 0): CartridgeStatus? {
        val file = try {
            FuseHttp.json.decodeFromString(CartridgeStatusFile.serializer(), json)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val protocol = file.protocol ?: return null
        return CartridgeStatus(
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
)
