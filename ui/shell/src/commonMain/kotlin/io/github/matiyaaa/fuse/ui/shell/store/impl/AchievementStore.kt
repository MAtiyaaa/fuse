package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.ktor.client.request.get
import kotlinx.serialization.builtins.serializer
import io.ktor.client.statement.bodyAsText

import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.match.TitleNormalizer
import io.github.matiyaaa.fuse.integrations.retroachievements.RaCachePolicy
import io.github.matiyaaa.fuse.integrations.retroachievements.RaCompletionEntry
import io.github.matiyaaa.fuse.integrations.retroachievements.RaCredentials
import io.github.matiyaaa.fuse.integrations.retroachievements.RaDiscFiles
import io.github.matiyaaa.fuse.integrations.retroachievements.RaGameListEntry
import io.github.matiyaaa.fuse.integrations.retroachievements.RaGameMatcher
import io.github.matiyaaa.fuse.integrations.retroachievements.RaHasher
import io.github.matiyaaa.fuse.integrations.retroachievements.RaMedia
import io.github.matiyaaa.fuse.integrations.retroachievements.RetroAchievementsClient
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.AchievementUser
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.RecentAchievement
import io.github.matiyaaa.fuse.ui.shell.store.AchievementOps
import io.github.matiyaaa.fuse.ui.shell.store.AchievementsFeed
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * RetroAchievements, display only. Every response is cached in the database for the time
 * [RaCachePolicy] allows; cached data shows instantly and is refreshed in the background. The web API
 * key lives in the secret store and is never logged.
 */
internal class DefaultAchievementOps(
    private val ctx: StoreContext,
    private val credentials: DefaultCredentialOps,
) : AchievementOps {
    private val cache = ctx.data.cache
    private val lock = Mutex()
    private var refreshJob: Job? = null

    private val feedState = MutableStateFlow<AchievementsFeed?>(null)
    override val feed: StateFlow<AchievementsFeed?> = feedState
    private val configuredState = MutableStateFlow(false)
    override val configured: StateFlow<Boolean> = configuredState

    private suspend fun client(): RetroAchievementsClient? {
        val user = credentials.get(SecretKeys.RA_USERNAME) ?: return null
        val key = credentials.get(SecretKeys.RA_API_KEY) ?: return null
        return RetroAchievementsClient(ctx.services.http, RaCredentials(user, key))
    }

    /** Loads cached data (no network) and whether an account is connected. */
    suspend fun load() {
        configuredState.value = credentials.get(SecretKeys.RA_USERNAME) != null && credentials.get(SecretKeys.RA_API_KEY) != null
        if (!configuredState.value) return
        val user = cachedAny(KEY_USER, AchievementUser.serializer())
        if (user != null) feedState.value = buildFeed(user, cachedAny(KEY_RECENT, RecentList).orEmpty(), cachedAny(KEY_COMPLETION, CompletionList).orEmpty())
    }

    override fun refresh(force: Boolean) {
        if (refreshJob?.isActive == true) return
        refreshJob = ctx.scope.launch { lock.withLock { refreshNow(force) } }
    }

    private suspend fun refreshNow(force: Boolean) {
        val client = client() ?: run {
            feedState.value = null
            configuredState.value = false
            return
        }
        configuredState.value = true
        val user = fetch(KEY_USER, AchievementUser.serializer(), RaCachePolicy.SUMMARY, force) { client.achievementUser() } ?: return
        val recent = fetch(KEY_RECENT, RecentList, RaCachePolicy.RECENT_ACHIEVEMENTS, force) {
            client.recentAchievements(minutes = RECENT_WINDOW_MINUTES)
        }.orEmpty()
        val completion = fetch(KEY_COMPLETION, CompletionList, RaCachePolicy.COMPLETION_PROGRESS, force) {
            client.allCompletionProgress(maxPages = 5)
        }.orEmpty()
        feedState.value = buildFeed(user, recent, completion)
    }

    private suspend fun buildFeed(user: AchievementUser, recent: List<RecentAchievement>, completion: List<RaCompletionEntry>): AchievementsFeed {
        val local = ctx.data.games.idsForRetroAchievements(recent.map { it.achievement.gameId }.distinct())
        val recentLinked = recent.map { r -> r.copy(localGameId = r.localGameId ?: local[r.achievement.gameId]?.firstOrNull()) }
        val inProgress = completion
            .filter { it.numAwarded in 1 until it.maxPossible }
            .sortedByDescending { it.mostRecentAwardedDate.orEmpty() }
            .take(10)
            .map { it.toState(user.fetchedAt) }
        val mastered = completion
            .filter { it.highestAwardKind == "mastered" }
            .sortedByDescending { it.highestAwardDate.orEmpty() }
            .take(10)
            .map { it.toState(user.fetchedAt) }
        return AchievementsFeed(user, recentLinked, inProgress, mastered)
    }

    /** Cached progress for [game] first, then fresh progress when an account is connected. */
    fun gameState(game: Game): Flow<AchievementState?> = flow {
        val raId = game.links.retroAchievementsGameId
        val cached = raId?.let { cachedAny("$KEY_GAME$it", AchievementState.serializer()) }
        emit(cached)
        val fresh = if (configuredState.value) forGame(game) else null
        if (fresh != null) emit(fresh)
        // Without RetroAchievements for it, what its own platform keeps here: Steam's achievements, RPCS3's trophies.
        else if (cached == null) emit(runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { local(game) } }.getOrNull())
    }

    private val localReader by lazy { io.github.matiyaaa.fuse.library.achievements.LocalAchievements(ctx.services.fs) }

    /** [game]'s achievements from what this device keeps for it, without an account; null when there are none. */
    private suspend fun local(game: Game): AchievementState? {
        val set = when {
            game.platformId.value == "ps3" -> {
                val folders = listOfNotNull(game.location.path, io.github.matiyaaa.fuse.library.FsPath.parent(game.location.path))
                localReader.ps3Trophies(ctx.services.emulators.rpcs3DevHdd0(ctx.installed.value), folders, game.tags.serial, game.displayTitle)
            }
            game.platformId.value == "ps4" -> {
                val users = ctx.services.emulators.shadPs4UserDirs(ctx.installed.value)
                if (users.isEmpty()) null else {
                    // The game's own npbind.dat names its trophy set; the folder is the game's, or holds its eboot.
                    val fs = ctx.services.fs
                    val folders = listOfNotNull(game.location.path, io.github.matiyaaa.fuse.library.FsPath.parent(game.location.path))
                    val ids = folders.firstNotNullOfOrNull { f ->
                        val path = io.github.matiyaaa.fuse.library.FsPath.join(f, "sce_sys", "npbind.dat")
                        val size = runCatching { fs.stat(path) }.getOrNull()?.takeIf { !it.isDirectory && it.sizeBytes in 1..65_536 }?.sizeBytes ?: return@firstNotNullOfOrNull null
                        runCatching { fs.readBytes(path, 0, size.toInt()) }.getOrNull()?.let(localReader::npCommIds)?.takeIf { it.isNotEmpty() }
                    }.orEmpty()
                    localReader.ps4Trophies(users, ids, game.displayTitle)
                }
            }
            game.platformId.value == "psvita" -> {
                val files = ctx.services.emulatorFiles
                val prefs = if (files == null) emptyList() else ctx.installed.value.filter { it.id.value.endsWith(".vita3k") }
                    .flatMap { runCatching { files.vita3kStorage(it) }.getOrDefault(emptyList()) }.distinct()
                if (prefs.isEmpty()) null else {
                    // The trophy sets the game itself ships (sce_sys/trophy/<NPWR id>), where its folder is at hand.
                    val folders = listOfNotNull(game.location.path, io.github.matiyaaa.fuse.library.FsPath.parent(game.location.path))
                    val ids = folders.flatMap { f ->
                        runCatching { ctx.services.fs.list(io.github.matiyaaa.fuse.library.FsPath.join(f, "sce_sys", "trophy")) }.getOrDefault(emptyList())
                            .filter { it.isDirectory }.map { it.name }
                    }.distinct()
                    localReader.vitaTrophies(prefs, game.tags.serial, ids)
                }
            }
            game.platformId.value == "xbox360" -> {
                // The game's title id, from what Fuse read for it or the [4D5307E6] tag in its file name; never its name.
                val titleId = game.tags.serial?.uppercase()?.takeIf { X360_TITLE.matches(it) }
                    ?: X360_TAG.find(game.location.path)?.groupValues?.get(1)?.uppercase()
                val roots = ctx.services.emulators.xeniaContentRoots(ctx.installed.value)
                if (titleId == null || roots.isEmpty()) null else localReader.xbox360Achievements(roots, titleId)
            }
            else -> steamAppId(game)?.let { appId ->
                val roots = runCatching { ctx.services.locations.steamRoots().roots }.getOrDefault(emptyList())
                localReader.steam(roots, appId, steamAccount(roots))
            }
        } ?: return null
        // How rare each is, where Steam publishes it (public, nothing about the person sent).
        val rarity = if (set.source == io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.STEAM) steamAppId(game)?.let { steamRarity(it) }.orEmpty() else emptyMap()
        val items = set.items
        // RPCS3's trophies earned here join the game's history, which Fuse One carries to the
        // person's other devices; trophies earned on those are said where they were earned.
        val elsewhere = if (set.source == io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.PS3_TROPHIES) trophyHistory(game, items) else emptyMap()
        val earned = items.count { it.unlockedAt != null }
        val points = items.sumOf { it.points }
        val source = when (set.source) {
            io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.STEAM -> io.github.matiyaaa.fuse.model.AchievementSource.STEAM
            io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.PS3_TROPHIES -> io.github.matiyaaa.fuse.model.AchievementSource.TROPHIES
            io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.PS4_TROPHIES -> io.github.matiyaaa.fuse.model.AchievementSource.SHADPS4
            io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.VITA_TROPHIES -> io.github.matiyaaa.fuse.model.AchievementSource.VITA3K
            io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.XBOX360 -> io.github.matiyaaa.fuse.model.AchievementSource.XENIA
        }
        return AchievementState(
            raGameId = 0,
            title = set.title ?: game.displayTitle,
            consoleName = ctx.platformName(game.platformId),
            iconUrl = set.icon,
            total = items.size,
            earned = earned,
            earnedHardcore = 0,
            points = points,
            pointsEarned = items.filter { it.unlockedAt != null }.sumOf { it.points },
            highestAward = if (earned == items.size && items.isNotEmpty()) "mastered" else null,
            achievements = items.mapIndexed { i, a ->
                // A hidden one stays a mystery until it is unlocked, as on its own platform.
                val secret = a.hidden && a.unlockedAt == null
                io.github.matiyaaa.fuse.model.Achievement(
                    id = i.toLong(),
                    gameId = 0,
                    title = if (secret) "Hidden ${if (source.noun == "Trophies") "trophy" else "achievement"}" else a.name,
                    description = (if (secret) "Keep playing to find out." else a.description) +
                        // Earned on another device: said, never shown as earned here.
                        (if (a.unlockedAt == null) elsewhere[a.id]?.let { " Earned on ${it.deviceName.ifBlank { "another device" }}." }.orEmpty() else ""),
                    points = a.points,
                    // A secret one's picture would give it away, so it has none until it is earned.
                    badgeUrl = if (secret) "" else a.icon.orEmpty(),
                    badgeLockedUrl = if (secret) "" else (a.iconLocked ?: a.icon).orEmpty(),
                    earnedAt = a.unlockedAt,
                    earnedHardcoreAt = null,
                    type = a.grade,
                    displayOrder = i,
                    rarity = rarity[a.id],
                )
            },
            fetchedAt = ctx.now(),
            source = source,
        )
    }

    /**
     * Notes the trophies [items] says were earned here in [game]'s history (once each, the first
     * time) and answers the ones earned on another of the person's devices, by trophy id.
     */
    private suspend fun trophyHistory(game: Game, items: List<io.github.matiyaaa.fuse.library.achievements.LocalAchievement>): Map<String, io.github.matiyaaa.fuse.sync.TrophyUnlock> {
        val sync = ctx.settings.value.sync
        val device = sync.deviceId.ifBlank { "this-device" }
        val here = items.filter { it.unlockedAt != null }.map { a ->
            io.github.matiyaaa.fuse.sync.TrophyUnlock("ps3", a.id, a.name, a.unlockedAt!!, device, sync.deviceName, "rpcs3")
        }
        runCatching { TrophyHistory.add(ctx.data, game.id.value, here) }
        return runCatching { TrophyHistory.read(ctx.data, game.id.value) }.getOrDefault(emptyList())
            .filter { it.source == "ps3" && it.device != device }
            .associateBy { it.id }
    }

    /**
     * The Steam account whose achievements this device shows: the one chosen in Settings, else the
     * one Steam remembers as most recent. Null when Steam remembers none here (then the newest stats
     * Steam keeps are read).
     */
    private suspend fun steamAccount(roots: List<String>): String? {
        ctx.settings.value.steam.achievementsAccount.takeIf { it.isNotBlank() }?.let { return it }
        val accounts = runCatching { io.github.matiyaaa.fuse.library.steam.SteamAccounts(ctx.services.fs).read(roots) }.getOrDefault(emptyList())
        return (accounts.firstOrNull { it.mostRecent } ?: accounts.singleOrNull())?.steamId
    }

    /** Each achievement's share of players for Steam app [appId], kept for a day; empty when Steam can't be reached. */
    private suspend fun steamRarity(appId: Long): Map<String, Float> {
        val key = "$KEY_RARITY$appId"
        val serializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Float.serializer())
        runCatching { cache.getOrNull(NS, key, ctx.now()) }.getOrNull()?.let { cached ->
            runCatching { kotlinx.serialization.json.Json.decodeFromString(serializer, cached) }.getOrNull()?.let { return it }
        }
        val fresh = runCatching {
            val resp = ctx.services.http.get(io.github.matiyaaa.fuse.integrations.steam.SteamGlobalAchievements.url(appId))
            if (resp.status.value in 200..299) io.github.matiyaaa.fuse.integrations.steam.SteamGlobalAchievements.parse(resp.bodyAsText()) else null
        }.getOrNull() ?: return emptyMap()
        runCatching { cache.put(NS, key, kotlinx.serialization.json.Json.encodeToString(serializer, fresh), ctx.now(), RARITY_TTL_MS) }
        return fresh
    }

    /** The Steam app [game] is: its link, else the app id in its .steam shortcut (Fuse's own, or ES-DE's). */
    private suspend fun steamAppId(game: Game): Long? {
        game.links.steamAppId?.let { return it }
        if (!game.location.path.endsWith(".steam", ignoreCase = true) && game.platformId.value != "steam") return null
        val text = runCatching { ctx.services.fs.readText(game.location.path, 4096) }.getOrNull() ?: return null
        return STEAM_ID.find(text)?.groupValues?.get(1)?.toLongOrNull()
    }

    override suspend fun forGame(game: GameId): AchievementState? {
        val stored = ctx.data.games.get(game) ?: return null
        return forGame(stored)
    }

    private suspend fun forGame(game: Game): AchievementState? {
        val client = client() ?: return null
        val raId = game.links.retroAchievementsGameId ?: identify(client, game) ?: return null
        val state = fetch("$KEY_GAME$raId", AchievementState.serializer(), RaCachePolicy.GAME_PROGRESS, force = false) {
            client.gameProgress(raId)
        } ?: return null
        val named = runCatching { cache.getOrNull(NS, "$KEY_NAMED${game.id.value}", now = 0L) }.getOrNull() != null
        return if (named) state.copy(matchedByName = true) else state
    }

    /**
     * Finds the RetroAchievements game for a game: by hashing its ROM the way rcheevos does (inside
     * a .zip too, and the data track of a disc: a .cue's first file, an .m3u's first disc), else by
     * its exact name on that console when the hash is unknown or the image can't be hashed (CHD,
     * CSO and the like). A game that found nothing isn't looked for again for a day.
     */
    private suspend fun identify(client: RetroAchievementsClient, game: Game): Long? {
        val consoleId = ctx.platform(game.platformId)?.retroAchievementsConsoleId ?: return null
        val missKey = "$KEY_MISS${game.id.value}"
        if (runCatching { cache.getOrNull(NS, missKey, ctx.now()) }.getOrNull() != null) return null
        val hash = hashOf(consoleId, game)
        val list = fetch("$KEY_LIST$consoleId", GameList, RaCachePolicy.GAME_LIST, force = false) {
            client.gameList(consoleId, onlyWithAchievements = true, withHashes = true)
        } ?: return null
        val byHash = hash?.let { RaGameMatcher(list).gameIdFor(it) }
        val raId = byHash ?: byName(list, game)
        if (raId == null) {
            cache.put(NS, missKey, "1", ctx.now(), MISS_TTL_MS)
            return null
        }
        if (byHash == null) cache.put(NS, "$KEY_NAMED${game.id.value}", "1", ctx.now(), ttlMs = null)
        ctx.data.games.updateLinks(game.id) { it.copy(retroAchievementsGameId = raId) }
        return raId
    }

    /** The rcheevos hash of [game]'s ROM, or null when it can't be hashed. */
    private suspend fun hashOf(consoleId: Int, game: Game): String? {
        val path = RaDiscFiles.dataFile(game.location.launchPath) { ctx.services.fs.readText(it) }
        val source = openZippedRom(path) ?: openByteSource(path)
        return try {
            RaHasher.hash(consoleId, path, source).md5OrNull
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            source?.close()
        }
    }

    /**
     * The game on RetroAchievements with this game's very name (ignoring case, punctuation, tags and
     * a leading or trailing article). Hacks, homebrew, prototypes and subsets are never picked.
     */
    private fun byName(list: List<RaGameListEntry>, game: Game): Long? {
        val names = listOfNotNull(game.titles.custom, game.titles.metadata, game.titles.cleaned, game.displayTitle, DisplayNameCleaner.clean(game.titles.original))
            .map { TitleNormalizer.normalize(it) }.filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return null
        return list.firstOrNull { entry ->
            val title = entry.title.trim()
            !title.startsWith("~") && !title.contains("[Subset", ignoreCase = true) &&
                title.split(" | ").any { TitleNormalizer.normalize(it) in names }
        }?.id
    }

    override suspend fun connect(username: String, apiKey: String): Result<Unit> {
        val user = username.trim()
        val key = apiKey.trim()
        if (user.isEmpty() || key.isEmpty()) return Result.failure(IllegalArgumentException("Enter your username and web API key."))
        return when (val r = RetroAchievementsClient(ctx.services.http, RaCredentials(user, key)).verifyCredentials()) {
            is ApiResult.Success -> {
                credentials.put(SecretKeys.RA_USERNAME, user)
                credentials.put(SecretKeys.RA_API_KEY, key)
                cache.clear(NS)
                cache.put(NS, KEY_USER, r.value, AchievementUser.serializer(), ctx.now(), RaCachePolicy.SUMMARY.inWholeMilliseconds)
                configuredState.value = true
                refresh(force = true)
                Result.success(Unit)
            }
            is ApiResult.AuthError -> Result.failure(IllegalStateException("RetroAchievements didn't accept that username and web API key."))
            is ApiResult.Failure -> Result.failure(IllegalStateException("Couldn't reach RetroAchievements: ${r.message}"))
        }
    }

    override suspend fun disconnect() {
        credentials.remove(SecretKeys.RA_USERNAME)
        credentials.remove(SecretKeys.RA_API_KEY)
        cache.clear(NS)
        configuredState.value = false
        feedState.value = null
    }

    /** Returns fresh cached data, or fetches and caches it; on failure falls back to stale cache. */
    private suspend fun <T> fetch(
        key: String,
        serializer: KSerializer<T>,
        ttl: Duration,
        force: Boolean,
        load: suspend () -> ApiResult<T>,
    ): T? {
        val now = ctx.now()
        if (!force) cache.get(NS, key, serializer, now)?.let { return it }
        return when (val r = load()) {
            is ApiResult.Success -> {
                cache.put(NS, key, r.value, serializer, now, ttl.inWholeMilliseconds)
                r.value
            }
            is ApiResult.Failure -> cachedAny(key, serializer)
        }
    }

    /** Cached value regardless of age (shown while offline or rate limited). */
    private suspend fun <T> cachedAny(key: String, serializer: KSerializer<T>): T? =
        runCatching { cache.get(NS, key, serializer, now = 0L) }.getOrNull()

    private fun RaCompletionEntry.toState(fetchedAt: Long) = AchievementState(
        raGameId = gameId,
        title = title,
        consoleName = consoleName,
        iconUrl = RaMedia.url(imageIcon),
        total = maxPossible,
        earned = numAwarded,
        earnedHardcore = numAwardedHardcore,
        points = 0,
        pointsEarned = 0,
        highestAward = highestAwardKind,
        fetchedAt = fetchedAt,
    )

    private companion object {
        private val STEAM_ID = Regex("(?:rungameid/|^\\s*)(\\d{1,10})")

        /** An Xbox 360 title id, and the way it is tagged in a file name ("Halo 3 [4D5307E6].iso"). */
        private val X360_TITLE = Regex("[0-9A-Fa-f]{8}")
        private val X360_TAG = Regex("\\[([0-9A-Fa-f]{8})]")
        const val NS = "retroachievements"
        const val KEY_USER = "user"
        const val KEY_RECENT = "recent"
        const val KEY_COMPLETION = "completion"
        const val KEY_GAME = "game:"
        const val KEY_LIST = "list:"
        /** A game matched by its name rather than its ROM's hash. */
        const val KEY_NAMED = "named:"
        /** A game nothing was found for, so it isn't hashed again on every visit. */
        const val KEY_MISS = "miss:"
        const val KEY_RARITY = "steam-rarity:"
        const val RARITY_TTL_MS = 24 * 60 * 60 * 1000L
        const val MISS_TTL_MS = 24L * 60 * 60 * 1000
        const val RECENT_WINDOW_MINUTES = 60 * 24 * 14
        val RecentList = ListSerializer(RecentAchievement.serializer())
        val CompletionList = ListSerializer(RaCompletionEntry.serializer())
        val GameList = ListSerializer(RaGameListEntry.serializer())
    }
}
