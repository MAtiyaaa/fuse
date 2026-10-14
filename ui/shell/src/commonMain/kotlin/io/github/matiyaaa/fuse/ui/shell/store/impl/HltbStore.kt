package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.IdentityRepository
import io.github.matiyaaa.fuse.integrations.hltb.HltbGame
import io.github.matiyaaa.fuse.integrations.hltb.HltbResult
import io.github.matiyaaa.fuse.integrations.hltb.HowLongToBeat
import io.github.matiyaaa.fuse.library.identity.Ids
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.HltbView
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * How long each game takes to beat, from HowLongToBeat, for its page. Asked only when a game's page
 * opens, never for the whole library, and kept a month by the game's title whatever the copy (Fuse
 * Identity's work level), so every region and copy of a game shares one answer. A person's choice
 * ("Not this game") is kept with the game and always wins.
 */
internal class HltbStore(private val ctx: StoreContext) {
    private val client by lazy { HowLongToBeat(ctx.services.http, ctx::now) }
    private val json = Json { ignoreUnknownKeys = true }
    private val identity get() = ctx.data.identity

    suspend fun forGame(id: GameId): HltbView? {
        if (!ctx.settings.value.scraping.howLongToBeat) return null
        val game = ctx.data.games.get(id) ?: return null
        if (game.appId != null && game.platformId.value == "android") return null
        val chosen = chosenId(id)
        if (chosen == NONE) return null
        if (chosen != null) {
            gameById(chosen, game)?.let { return HltbView(it, chosen = true) }
            return null
        }
        val key = workKey(game)
        val cached = identity.hltb(key)
        if (cached != null && fresh(cached.fetchedAt, cached.hltbId == 0L)) {
            return decode(cached.json).firstOrNull()?.takeIf { it.hasTimes }?.let { HltbView(it, chosen = false) }
        }
        val found = when (val r = client.search(searchTitle(game))) {
            is HltbResult.Found -> r.games
            // Asked too often, offline or changed: what was known before, else nothing for now.
            is HltbResult.Unavailable -> return cached?.let { c -> decode(c.json).firstOrNull()?.takeIf { it.hasTimes }?.let { HltbView(it, false) } }
        }
        val picked = HowLongToBeat.pick(found, titles(game), platformNames(game), game.metadata.releaseYear)
        identity.putHltb(key, picked?.id ?: 0L, encode(listOfNotNull(picked)))
        return picked?.takeIf { it.hasTimes }?.let { HltbView(it, chosen = false) }
    }

    suspend fun choices(id: GameId): List<HltbGame> {
        val game = ctx.data.games.get(id) ?: return emptyList()
        return (client.search(searchTitle(game)) as? HltbResult.Found)?.games.orEmpty()
    }

    suspend fun choose(id: GameId, picked: HltbGame?) {
        identity.replace(id, IdentityRepository.HLTB, listOf(picked?.id?.toString() ?: NONE.toString()), "person")
        if (picked != null) identity.putHltb(gameKey(picked.id), picked.id, encode(listOf(picked)))
    }

    private suspend fun chosenId(id: GameId): Long? =
        identity.forGame(id).firstOrNull { it.kind == IdentityRepository.HLTB }?.value?.toLongOrNull()

    /** A chosen game's times: kept a month, then asked again by its name. */
    private suspend fun gameById(hltbId: Long, game: Game): HltbGame? {
        val cached = identity.hltb(gameKey(hltbId))
        if (cached != null && fresh(cached.fetchedAt, false)) return decode(cached.json).firstOrNull()
        val known = cached?.let { decode(it.json).firstOrNull() }
        val again = (client.search(known?.name ?: searchTitle(game)) as? HltbResult.Found)?.games?.firstOrNull { it.id == hltbId }
        if (again != null) identity.putHltb(gameKey(hltbId), hltbId, encode(listOf(again)))
        return again ?: known
    }

    private fun fresh(at: Long, notFound: Boolean): Boolean = ctx.now() - at < if (notFound) MISS_MS else KEEP_MS

    /** The game's own names, best first: the trusted metadata title, then its file's name cleaned. */
    private fun titles(game: Game): List<String> =
        listOfNotNull(game.titles.metadata, game.titles.cleaned, DisplayNameCleaner.clean(game.titles.original), game.titles.original).distinct()

    private fun searchTitle(game: Game): String = titles(game).first()

    private fun platformNames(game: Game): List<String> =
        listOfNotNull(ctx.platform(game.platformId)?.name, ctx.platform(game.platformId)?.shortName)

    private fun workKey(game: Game): String = "${Ids.platform(game.platformId.value)}:${Ids.workTitle(searchTitle(game))}"

    private fun gameKey(hltbId: Long) = "game:$hltbId"

    private fun encode(games: List<HltbGame>): String = json.encodeToString(ListSerializer(HltbGame.serializer()), games)

    private fun decode(text: String): List<HltbGame> = runCatching { json.decodeFromString(ListSerializer(HltbGame.serializer()), text) }.getOrDefault(emptyList())

    companion object {
        /** The person said none of HowLongToBeat's games is this one. */
        private const val NONE = 0L
        private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
        private const val MISS_MS = 7L * 24 * 60 * 60 * 1000
    }
}
