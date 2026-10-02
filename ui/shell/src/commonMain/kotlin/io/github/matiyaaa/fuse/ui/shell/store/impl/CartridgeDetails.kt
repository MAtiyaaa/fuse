package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeMatch
import io.github.matiyaaa.fuse.integrations.cartridge.RommGuard
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.CartridgeGame
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.MetadataSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * RomM's details and pictures for the games Cartridge downloaded (bridge protocol 2).
 *
 * Each Cartridge game is matched to a Fuse game by its path, then checked ([RommGuard]): RomM's
 * system and name have to fit the game the path points at, or nothing about it is written (not the
 * name, the details, the art or the RomM link). A path alone once let another game's name replace a
 * game's own when Cartridge's rom id came to mean another rom.
 *
 * Accepted games are linked to their RomM entry and given RomM's details: they fill empty fields and
 * replace scraped ones, but details the user edited are only ever filled. What a game had before
 * RomM's details first arrived is kept, so they can be undone ([repair]). Pictures are read from
 * Cartridge and copied into Fuse's own folder; one that can't be read is never recorded, so it can't
 * hold a slot with nothing to show. The cover and logo replace scraped art (never the user's picks or
 * art from the game's folder); a screenshot is only added when the game has none. A game is only
 * written again when Cartridge says its row changed, so a later "Fill everything" isn't undone on
 * every sync.
 */
internal class CartridgeDetails(private val ctx: StoreContext) {
    private val lock = Mutex()

    /** What a sync found: games matched to Fuse games, games Fuse hasn't indexed (yet), and the rest. */
    data class Outcome(val matched: Int, val unmatched: Int, val updated: Int, val turnedDown: Int = 0, val undone: Int = 0)

    /** What a game had before RomM's details first reached it. */
    @Serializable
    private data class Before(val title: String? = null, val metadata: GameMetadata? = null, val media: List<MediaItem> = emptyList())

    suspend fun sync(games: List<CartridgeGame>, applyDetails: Boolean): Outcome = lock.withLock {
        if (games.isEmpty()) return@withLock Outcome(0, 0, 0)
        val match = CartridgeMatch(ctx.data.games.paths())
        var matched = 0
        var unmatched = 0
        var updated = 0
        var turnedDown = 0
        var undone = 0
        for (g in games) {
            val id = g.path?.let(match::find)
            val game = id?.let { ctx.data.games.get(it) }
            if (id == null || game == null) {
                unmatched++
                continue
            }
            if (!fits(game, g.title, g.platformSlug)) {
                // Another game's details: never written, and undone where an earlier version wrote them.
                turnedDown++
                if (undo(game)) undone++
                continue
            }
            matched++
            linkRom(id, g.romId)
            if (!applyDetails) continue
            val stamp = "\"${g.romId}:${g.updatedAt}:${g.cover.orEmpty()}:${g.logo.orEmpty()}\""
            val key = key(id)
            if (ctx.data.cache.entry(NS, key)?.valueJson == stamp) continue
            if (!applyTo(game, g)) continue
            ctx.data.cache.put(NS, key, stamp, ctx.now(), ttlMs = null)
            updated++
        }
        Outcome(matched, unmatched, updated, turnedDown, undone)
    }

    /** Whether RomM's [title] on [slug] is this game (see [RommGuard]). */
    fun fits(game: Game, title: String, slug: String): Boolean =
        RommGuard.accepts(game.platformId, ownTitles(game), title, slug) { ctx.platforms.resolveFolder(it)?.id }

    /**
     * Checks every game holding RomM's details against its own names and undoes those that are
     * another game's. Returns how many were put back.
     */
    suspend fun repair(): Int = lock.withLock {
        var undone = 0
        for (id in ctx.data.games.withMetadataFrom(MetadataSource.ROMM)) {
            val game = ctx.data.games.get(id) ?: continue
            val title = game.titles.metadata ?: continue
            // The slug isn't kept; the name alone tells another game apart.
            if (fits(game, title, slug = "")) continue
            if (undo(game)) undone++
        }
        undone
    }

    /**
     * Puts back what [game] had before RomM's details reached it: its name and details, and the art
     * RomM replaced. Without a record of it, RomM's name, details and pictures are just removed and a
     * later fill finds the right ones. False when RomM's details never reached it.
     */
    private suspend fun undo(game: Game): Boolean {
        val id = game.id
        val fromRomm = game.metadata.source == MetadataSource.ROMM
        val stamped = ctx.data.cache.entry(NS, key(id)) != null
        val owner = MediaOwner.OfGame(id)
        val rommArt = ctx.data.media.get(owner).items.any { it.source == MediaSource.ROMM }
        if (!fromRomm && !stamped && !rommArt && game.links.rommRomId == null) return false
        val before = ctx.data.cache.entry(BEFORE, key(id))?.valueJson?.let { runCatching { json.decodeFromString(Before.serializer(), it) }.getOrNull() }
        if (fromRomm) ctx.data.games.replaceMetadata(id, before?.metadata, before?.title)
        ctx.data.media.removeSource(owner, MediaSource.ROMM)
        before?.media?.takeIf { it.isNotEmpty() }?.let { ctx.data.media.putScraped(owner, it, MediaFillMode.FILL_MISSING) }
        if (game.links.rommRomId != null) ctx.data.games.updateLinks(id) { it.copy(rommRomId = null) }
        ctx.data.cache.remove(NS, key(id))
        ctx.data.cache.remove(BEFORE, key(id))
        return fromRomm || rommArt
    }

    /** Links a game to its RomM entry so "Open in Cartridge" can jump straight to it. */
    private suspend fun linkRom(id: GameId, romId: Long) {
        if (romId <= 0) return
        val current = ctx.data.games.get(id)?.links ?: return
        if (current.rommRomId != romId) ctx.data.games.updateLinks(id) { it.copy(rommRomId = romId) }
    }

    private suspend fun applyTo(game: Game, g: CartridgeGame): Boolean {
        val id = game.id
        val owner = MediaOwner.OfGame(id)
        val current = ctx.data.media.get(owner)
        remember(game, current.items)
        val metadata = GameMetadata(
            description = g.summary?.trim()?.takeIf(String::isNotEmpty),
            releaseYear = g.year,
            developer = g.developer,
            publisher = g.publisher,
            genres = g.genres,
            franchise = g.series.firstOrNull(),
            players = g.players,
            rating = g.rating,
            source = MetadataSource.ROMM,
        )
        if (!ctx.data.games.applyMetadata(id, metadata, titleFromMetadata = g.title, onlyFillEmpty = false)) return false
        for ((kind, source) in listOf(MediaKind.BOXART to g.cover, MediaKind.LOGO to g.logo, MediaKind.SCREENSHOT to g.screenshot)) {
            if (source.isNullOrBlank()) continue
            val existing = current.all(kind)
            // The user's picks and art from the game's own folder always stay.
            if (existing.any { it.source == MediaSource.USER || it.source == MediaSource.LOCAL_FOLDER }) continue
            val mode = when {
                existing.isEmpty() -> MediaFillMode.FILL_MISSING
                // One screenshot from RomM never replaces a scraped set.
                kind == MediaKind.SCREENSHOT && existing.none { it.source == MediaSource.ROMM } -> continue
                else -> MediaFillMode.REPLACE_SELECTED
            }
            val kept = keep(id, kind, source) ?: continue
            if (existing.any { it.source == MediaSource.ROMM && it.localPath == kept }) continue
            ctx.data.media.putScraped(owner, listOf(MediaItem(kind, MediaSource.ROMM, localPath = kept)), mode, setOf(kind))
        }
        return true
    }

    /** Records what [game] has before RomM's details first reach it (once). */
    private suspend fun remember(game: Game, media: List<MediaItem>) {
        val key = key(game.id)
        if (ctx.data.cache.entry(BEFORE, key) != null) return
        val before = Before(
            title = game.titles.metadata,
            metadata = game.metadata.takeIf { it.source != MetadataSource.ROMM },
            media = media.filter { it.source != MediaSource.USER && it.source != MediaSource.ROMM && it.kind in ROMM_KINDS },
        )
        ctx.data.cache.put(BEFORE, key, json.encodeToString(Before.serializer(), before), ctx.now(), ttlMs = null)
    }

    /**
     * Copies a picture Cartridge hands over (a content URI on Android, a file on Linux) into Fuse's own
     * folder and returns where it is, or null when it can't be read or isn't a picture.
     */
    private suspend fun keep(id: GameId, kind: MediaKind, source: String): String? {
        val bytes = ctx.services.readFile(source, MAX_PICTURE) ?: return null
        val ext = pictureExtension(bytes) ?: return null
        val name = "romm/${id.value}/${kind.name.lowercase()}-${bytes.contentHashCode().toUInt().toString(16)}.$ext"
        return ctx.services.keepFile(name, bytes)
    }

    private fun ownTitles(game: Game): List<String> = listOfNotNull(
        game.titles.original,
        game.titles.cleaned,
        game.titles.custom,
        FsPath.stem(FsPath.name(game.location.path)),
    ).distinct()

    private companion object {
        const val NS = "cartridge.romm"
        const val BEFORE = "cartridge.romm.before"
        const val MAX_PICTURE = 12 * 1024 * 1024
        val ROMM_KINDS = setOf(MediaKind.BOXART, MediaKind.LOGO, MediaKind.SCREENSHOT)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        fun key(id: GameId) = "g${id.value}"
    }
}

/** The file extension for picture bytes (PNG, JPEG, WebP, GIF), or null for anything else. */
internal fun pictureExtension(b: ByteArray): String? = when {
    b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "png"
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpg"
    b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "webp"
    b.size >= 4 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "gif"
    else -> null
}
