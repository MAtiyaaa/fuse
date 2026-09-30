package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeMatch
import io.github.matiyaaa.fuse.model.CartridgeGame
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

/**
 * RomM's details and pictures for the games Cartridge downloaded (bridge protocol 2).
 *
 * Each Cartridge game is matched to a Fuse game by its path, linked to its RomM entry, and given
 * RomM's details: they fill empty fields and replace scraped ones, but details the user edited are
 * only ever filled. The cover and logo replace scraped art (never the user's picks or art from the
 * game's folder); a screenshot is only added when the game has none. A game is only written again
 * when Cartridge says its row changed, so a later "Fill everything" isn't undone on every sync.
 */
internal class CartridgeDetails(private val ctx: StoreContext) {
    private val lock = Mutex()

    /** What a sync found: games matched to Fuse games, and games Fuse hasn't indexed (yet). */
    data class Outcome(val matched: Int, val unmatched: Int, val updated: Int)

    suspend fun sync(games: List<CartridgeGame>, applyDetails: Boolean): Outcome = lock.withLock {
        if (games.isEmpty()) return@withLock Outcome(0, 0, 0)
        val match = CartridgeMatch(ctx.data.games.paths())
        var matched = 0
        var unmatched = 0
        var updated = 0
        for (g in games) {
            val id = g.path?.let(match::find)
            if (id == null) {
                unmatched++
                continue
            }
            matched++
            linkRom(id, g.romId)
            if (!applyDetails) continue
            val stamp = "\"${g.romId}:${g.updatedAt}:${g.cover.orEmpty()}:${g.logo.orEmpty()}\""
            val key = "g${id.value}"
            if (ctx.data.cache.entry(NS, key)?.valueJson == stamp) continue
            if (!applyTo(id, g)) continue
            ctx.data.cache.put(NS, key, stamp, ctx.now(), ttlMs = null)
            updated++
        }
        Outcome(matched, unmatched, updated)
    }

    /** Links a game to its RomM entry so "Open in Cartridge" can jump straight to it. */
    private suspend fun linkRom(id: GameId, romId: Long) {
        if (romId <= 0) return
        val current = ctx.data.games.get(id)?.links ?: return
        if (current.rommRomId != romId) ctx.data.games.updateLinks(id) { it.copy(rommRomId = romId) }
    }

    private suspend fun applyTo(id: GameId, g: CartridgeGame): Boolean {
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
        val owner = MediaOwner.OfGame(id)
        val current = ctx.data.media.get(owner)
        for ((kind, model) in listOf(MediaKind.BOXART to g.cover, MediaKind.LOGO to g.logo, MediaKind.SCREENSHOT to g.screenshot)) {
            if (model.isNullOrBlank()) continue
            val existing = current.all(kind)
            // The user's picks and art from the game's own folder always stay.
            if (existing.any { it.source == MediaSource.USER || it.source == MediaSource.LOCAL_FOLDER }) continue
            if (existing.any { it.source == MediaSource.ROMM && it.model == model }) continue
            val mode = when {
                existing.isEmpty() -> MediaFillMode.FILL_MISSING
                // One screenshot from RomM never replaces a scraped set.
                kind == MediaKind.SCREENSHOT && existing.none { it.source == MediaSource.ROMM } -> continue
                else -> MediaFillMode.REPLACE_SELECTED
            }
            ctx.data.media.putScraped(owner, listOf(MediaItem(kind, MediaSource.ROMM, localPath = model)), mode, setOf(kind))
        }
        return true
    }

    private companion object {
        const val NS = "cartridge.romm"
    }
}
