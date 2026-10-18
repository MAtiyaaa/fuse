package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.Secret
import io.github.matiyaaa.fuse.integrations.nexus.NexusFile
import io.github.matiyaaa.fuse.integrations.nexus.NexusGame
import io.github.matiyaaa.fuse.integrations.nexus.NexusList
import io.github.matiyaaa.fuse.integrations.nexus.NexusMod
import io.github.matiyaaa.fuse.integrations.nexus.NexusMods
import io.github.matiyaaa.fuse.launch.patches.EnhancementKind
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.EnhancementPreview
import io.github.matiyaaa.fuse.ui.shell.store.NexusAccount
import io.github.matiyaaa.fuse.ui.shell.store.NexusGames
import io.github.matiyaaa.fuse.ui.shell.store.NexusModList
import io.github.matiyaaa.fuse.ui.shell.store.NexusPage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Mods from Nexus Mods, with the person's own key (kept on this device only, never shared or
 * printed). Which game there a library game is, is the person's choice from Nexus Mods' own list
 * (never guessed), and is remembered. A file is fetched only when it is a current `.zip`: straight
 * away for a Premium account, else with the website's own link for it, as Nexus Mods asks; then it
 * goes through the same preview and restoration point as a person's own files ([NativeEnhancements]).
 * Nexus Mods' answers are kept a while ([data.cache]), so browsing doesn't spend its daily limit.
 */
internal class NexusSite(
    private val ctx: StoreContext,
    private val enhancements: NativeEnhancements,
) {
    private val data = ctx.data
    private val json = Json { ignoreUnknownKeys = true }

    /** The client for the key on this device, or null when there is none. */
    private suspend fun client(): NexusMods? {
        val key = runCatching { ctx.services.secrets.get(SecretKeys.NEXUS_API_KEY) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return NexusMods(ctx.services.http, Secret(key), ctx.services.appVersion)
    }

    /** Whose key it is, asked again only when the key changed (validating doesn't count against Nexus Mods' limits). */
    @kotlin.concurrent.Volatile private var known: Pair<Int, NexusAccount.Ready>? = null

    suspend fun account(): NexusAccount {
        val key = runCatching { ctx.services.secrets.get(SecretKeys.NEXUS_API_KEY) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return NexusAccount.NoKey
        known?.takeIf { it.first == key.hashCode() }?.let { return it.second }
        return when (val r = NexusMods(ctx.services.http, Secret(key), ctx.services.appVersion).validate()) {
            is ApiResult.Success -> NexusAccount.Ready(r.value.name, r.value.premium).also { known = key.hashCode() to it }
            is ApiResult.AuthError -> NexusAccount.Problem("Nexus Mods didn't accept the key. Copy it again from your account's API Access page.")
            is ApiResult.Failure -> NexusAccount.Problem("Nexus Mods couldn't be reached. ${r.message}")
        }
    }

    suspend fun linked(id: GameId): NexusGame? = data.owned.get(LINKS, id.value.toString()).firstOrNull()?.let {
        runCatching { json.decodeFromString(NexusGame.serializer(), it) }.getOrNull()
    }

    suspend fun link(id: GameId, game: NexusGame?) {
        data.owned.set(LINKS, id.value.toString(), setOfNotNull(game?.let { json.encodeToString(NexusGame.serializer(), it) }))
    }

    /** Nexus Mods' list of games, kept a week. */
    private suspend fun allGames(api: NexusMods): ApiResult<List<NexusGame>> {
        val serializer = ListSerializer(NexusGame.serializer())
        data.cache.get(CACHE, "games", serializer, ctx.now())?.let { return ApiResult.Success(it) }
        return api.games().also { r -> if (r is ApiResult.Success) data.cache.put(CACHE, "games", r.value, serializer, ctx.now(), WEEK_MS) }
    }

    suspend fun games(id: GameId, query: String?): NexusGames {
        val game = data.games.get(id) ?: return NexusGames(null, emptyList(), query.orEmpty(), "This game is no longer in the library.")
        val q = query?.trim()?.takeIf { it.isNotEmpty() } ?: DisplayNameCleaner.clean(game.titles.display)
        val api = client() ?: return NexusGames(linked(id), emptyList(), q, "Add your Nexus Mods key in Settings, Accounts first.")
        return when (val all = allGames(api)) {
            is ApiResult.Success -> NexusGames(linked(id), NexusMods.matching(all.value, q), q)
            is ApiResult.Failure -> NexusGames(linked(id), emptyList(), q, all.message)
        }
    }

    /** The kind Fuse adds a Nexus Mods mod as for [id]: Nexus Mods files mods by each game's own categories, so as a mod, else textures. */
    private suspend fun kindFor(id: GameId): EnhancementKind? {
        val kinds = enhancements.list(id).kinds
        return listOf(EnhancementKind.MODS, EnhancementKind.TEXTURES).firstOrNull { it in kinds }
    }

    suspend fun mods(id: GameId): NexusModList {
        val api = client() ?: return NexusModList.Unavailable("Add your Nexus Mods key", "Nexus Mods lists mods for your account's own key. Add it in Settings, Accounts, Nexus Mods.", needsKey = true)
        val game = linked(id) ?: return NexusModList.Unavailable("Which game is this on Nexus Mods?", "Choose it first, so Fuse lists mods made for this game only.", needsGame = true)
        val premium = (account() as? NexusAccount.Ready)?.premium == true
        val lists = LinkedHashMap<NexusList, List<NexusMod>>()
        for (list in NexusList.entries) {
            val serializer = ListSerializer(NexusMod.serializer())
            val cacheKey = "${game.domain}/${list.path}"
            val mods = data.cache.get(CACHE, cacheKey, serializer, ctx.now())
                ?: when (val r = api.list(game.domain, list)) {
                    is ApiResult.Success -> r.value.also { data.cache.put(CACHE, cacheKey, it, serializer, ctx.now(), HOUR_MS) }
                    is ApiResult.RateLimited -> return NexusModList.Unavailable("Nexus Mods' limit is used up", "Your account's requests for today are spent. Try again later.")
                    is ApiResult.Failure -> return NexusModList.Unavailable("Nexus Mods couldn't be reached", r.message)
                }
            lists[list] = mods.filter { it.available && !it.adult }
        }
        return NexusModList.Ready(game, lists, premium)
    }

    suspend fun page(id: GameId, game: String, modId: Long): NexusPage {
        val api = client() ?: return NexusPage.Unavailable("Add your Nexus Mods key", "Add it in Settings, Accounts, Nexus Mods.")
        val mod = when (val r = api.mod(game, modId)) {
            is ApiResult.Success -> r.value
            is ApiResult.HttpError -> return NexusPage.Unavailable("This mod isn't available", "Nexus Mods doesn't show it (it may be hidden or removed).")
            is ApiResult.Failure -> return NexusPage.Unavailable("Nexus Mods couldn't be reached", r.message)
        }
        if (!mod.available) return NexusPage.Unavailable("This mod isn't available", "Nexus Mods doesn't offer it now.")
        val files = when (val r = api.files(game, modId)) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return NexusPage.Unavailable("Nexus Mods couldn't be reached", r.message)
        }
        val premium = (account() as? NexusAccount.Ready)?.premium == true
        return NexusPage.Ready(mod, files, kindFor(id), premium)
    }

    suspend fun fetch(id: GameId, game: String, modId: Long, fileId: Long, nxm: String?, onProgress: (Float) -> Unit): EnhancementPreview {
        val files = ctx.services.emulatorFiles ?: return EnhancementPreview.Unavailable("Not on this device", "Mods can be added from Fuse on a computer.")
        val api = client() ?: return EnhancementPreview.Unavailable("Add your Nexus Mods key", "Add it in Settings, Accounts, Nexus Mods.")
        val ready = page(id, game, modId) as? NexusPage.Ready ?: return EnhancementPreview.Unavailable("This mod isn't available", "Nexus Mods doesn't show it now.")
        val kind = ready.kind ?: return EnhancementPreview.Unavailable("Fuse doesn't add mods for this game's emulator", "Follow the mod's own instructions from its page.")
        val file: NexusFile = ready.files.firstOrNull { it.id == fileId } ?: return EnhancementPreview.Unavailable("That file is gone", "Open the mod again to see its files now.")
        file.notFetchable?.let { return EnhancementPreview.Unavailable("Fuse won't fetch this file", "${file.name}: $it.") }
        val link = nxm?.let {
            NexusMods.nxmOf(it)?.takeIf { l -> l.game == game.lowercase() && l.mod == modId && l.file == fileId }
                ?: return EnhancementPreview.Unavailable("That link is for another file", "Copy the Mod Manager Download link of ${file.name} itself, from the mod's Files tab.")
        }
        val links = when (val r = api.downloadLinks(game, modId, fileId, link)) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return EnhancementPreview.Unavailable("Nexus Mods didn't give a download", r.message)
        }
        files.clearFetched()
        val path = files.fetch(links.first().uri, file.fileName, null, null, NexusFile.MAX_KB * 1024, onProgress)
            ?: return EnhancementPreview.Unavailable("Couldn't fetch ${file.name}", "It didn't arrive whole, so Fuse kept nothing of it.")
        return enhancements.plan(id, kind, path, ready.mod.name ?: file.name, "Nexus Mods", ready.mod.url, file.version ?: ready.mod.version)
    }

    private companion object {
        const val CACHE = "nexusmods"
        const val LINKS = "modsites.nexus"
        const val HOUR_MS = 60 * 60 * 1000L
        const val WEEK_MS = 7 * 24 * HOUR_MS
    }
}
