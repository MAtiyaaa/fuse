package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.gamebanana.GameBanana
import io.github.matiyaaa.fuse.integrations.gamebanana.GbFile
import io.github.matiyaaa.fuse.integrations.gamebanana.GbGame
import io.github.matiyaaa.fuse.integrations.gamebanana.GbMod
import io.github.matiyaaa.fuse.launch.patches.EnhancementKind
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.EnhancementPreview
import io.github.matiyaaa.fuse.ui.shell.store.ModSiteGames
import io.github.matiyaaa.fuse.ui.shell.store.ModSiteMod
import io.github.matiyaaa.fuse.ui.shell.store.ModSiteMods
import io.github.matiyaaa.fuse.ui.shell.store.ModSitePage
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Mods from GameBanana, read through its public API. Which game there a library game is, is the
 * person's choice from a search (never guessed from a similar name), and is remembered. A mod is
 * offered for adding only when its category is one Fuse adds for the game's emulator (textures,
 * cheats, patches) and its file passed GameBanana's own checks and is a `.zip`; the file is fetched
 * into Fuse's own folder, checked against GameBanana's size and checksum, and then goes through the
 * same preview and restoration point as a person's own files ([NativeEnhancements]).
 */
internal class ModSites(
    private val ctx: StoreContext,
    private val enhancements: NativeEnhancements,
) {
    private val data = ctx.data
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun fetchText(url: String): String? = try {
        kotlinx.coroutines.withTimeout(20_000) {
            val response = ctx.services.http.get(url)
            if (!response.status.isSuccess()) null else response.bodyAsText()
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** [url]'s answer, kept for [ttlMs] so browsing back and forth doesn't ask GameBanana again. */
    private suspend fun cached(url: String, ttlMs: Long): String? {
        data.cache.get(CACHE, url, String.serializer(), ctx.now())?.let { return it }
        val body = fetchText(url) ?: return null
        data.cache.put(CACHE, url, body, String.serializer(), ctx.now(), ttlMs)
        return body
    }

    suspend fun linked(id: GameId): GbGame? = data.owned.get(LINKS, id.value.toString()).firstOrNull()?.let {
        try { json.decodeFromString(GbGame.serializer(), it) } catch (e: Exception) { null }
    }

    suspend fun link(id: GameId, game: GbGame?) {
        data.owned.set(LINKS, id.value.toString(), setOfNotNull(game?.let { json.encodeToString(GbGame.serializer(), it) }))
    }

    suspend fun games(id: GameId, query: String?): ModSiteGames {
        val game = data.games.get(id) ?: return ModSiteGames(null, emptyList(), query.orEmpty(), "This game is no longer in the library.")
        val q = query?.trim()?.takeIf { it.isNotEmpty() } ?: DisplayNameCleaner.clean(game.titles.display)
        val body = cached(GameBanana.searchGamesUrl(q), DAY_MS) ?: return ModSiteGames(linked(id), emptyList(), q, "GameBanana couldn't be reached. Check the connection and try again.")
        val found = GameBanana.parseGames(body) ?: return ModSiteGames(linked(id), emptyList(), q, "GameBanana sent something Fuse couldn't read.")
        // The same name first, then the rest as GameBanana ordered them.
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val sorted = found.sortedByDescending { key(it.name) == key(q) }
        return ModSiteGames(linked(id), sorted, q)
    }

    /** The kind Fuse would add [mod] as for a game that takes [kinds]: from the category its makers filed it in. */
    private fun kindOf(mod: GbMod, kinds: List<EnhancementKind>): EnhancementKind? {
        val c = mod.category?.lowercase() ?: return null
        val kind = when {
            "texture" in c || "retexture" in c || "hd pack" in c -> EnhancementKind.TEXTURES
            "cheat" in c -> EnhancementKind.CHEATS
            "patch" in c || "widescreen" in c || "fps" in c -> if (EnhancementKind.PATCHES in kinds) EnhancementKind.PATCHES else EnhancementKind.CHEATS
            else -> null
        }
        return kind?.takeIf { it in kinds }
    }

    suspend fun mods(id: GameId, page: Int): ModSiteMods {
        val game = linked(id) ?: return ModSiteMods.Unavailable("Which game is this on GameBanana?", "Choose it first, so Fuse lists mods made for this game only.", needsGame = true)
        val body = cached(GameBanana.modsUrl(game.id, page), HOUR_MS) ?: return ModSiteMods.Unavailable("GameBanana couldn't be reached", "Check the connection and try again.")
        val list = GameBanana.parseMods(body) ?: return ModSiteMods.Unavailable("GameBanana sent something Fuse couldn't read", "Try again later.")
        val kinds = enhancements.list(id).kinds
        val shown = page.toLong() * GameBanana.PER_PAGE
        return ModSiteMods.Ready(game, list.mods.map { ModSiteMod(it, kindOf(it, kinds)) }, page, list.total, more = shown < list.total)
    }

    suspend fun page(id: GameId, modId: Long): ModSitePage {
        val body = cached(GameBanana.modUrl(modId), HOUR_MS) ?: return ModSitePage.Unavailable("GameBanana couldn't be reached", "Check the connection and try again.")
        val page = GameBanana.parseMod(body) ?: return ModSitePage.Unavailable("This mod isn't available", "GameBanana doesn't show it any more.")
        return ModSitePage.Ready(page, kindOf(page.mod, enhancements.list(id).kinds))
    }

    suspend fun fetch(id: GameId, modId: Long, fileId: Long, onProgress: (Float) -> Unit): EnhancementPreview {
        val files = ctx.services.emulatorFiles ?: return EnhancementPreview.Unavailable("Not on this device", "Mods can be added from Fuse on a computer.")
        val ready = page(id, modId) as? ModSitePage.Ready ?: return EnhancementPreview.Unavailable("This mod isn't available", "GameBanana doesn't show it any more.")
        val kind = ready.kind ?: return EnhancementPreview.Unavailable(
            "Fuse doesn't add this kind of mod",
            "It is filed under ${ready.page.mod.category ?: "no category"} on GameBanana. Fuse adds textures, cheats and patches; for the rest, follow the mod's own instructions.",
        )
        val file: GbFile = ready.page.files.firstOrNull { it.id == fileId } ?: return EnhancementPreview.Unavailable("That file is gone", "Open the mod again to see its files now.")
        file.notFetchable?.let { return EnhancementPreview.Unavailable("Fuse won't fetch this file", "${file.name}: $it.") }
        // One fetched file at a time: whatever an earlier preview left behind goes first.
        files.clearFetched()
        val path = files.fetch(file.downloadUrl, file.name, file.size, file.md5, GbFile.MAX_BYTES, onProgress)
            ?: return EnhancementPreview.Unavailable(
                "Couldn't fetch ${file.name}",
                "It didn't arrive whole, or wasn't what GameBanana says it is (its size and checksum didn't match), so Fuse kept nothing of it.",
            )
        return enhancements.plan(id, kind, path, ready.page.mod.name, "GameBanana", ready.page.mod.url, file.version ?: ready.page.mod.version)
    }

    private companion object {
        const val CACHE = "gamebanana"
        const val LINKS = "modsites.gamebanana"
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
