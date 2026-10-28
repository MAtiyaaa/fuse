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
import io.github.matiyaaa.fuse.integrations.retroachievements.RaFileHasher
import io.github.matiyaaa.fuse.integrations.disc.DiscFiles
import io.github.matiyaaa.fuse.integrations.retroachievements.RaMedia
import io.github.matiyaaa.fuse.integrations.retroachievements.RetroAchievementsClient
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.AchievementUser
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.RecentAchievement
import io.github.matiyaaa.fuse.ui.shell.store.AchievementOps
import io.github.matiyaaa.fuse.ui.shell.store.AchievementsFeed
import io.github.matiyaaa.fuse.ui.shell.store.LocalGameAchievements
import io.github.matiyaaa.fuse.ui.shell.store.PlayUnlocks
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    private val playState = MutableStateFlow(PlayUnlocks())
    override val playing: StateFlow<PlayUnlocks> = playState

    /** How many things on screen show unlocks as they come ([showLive]). */
    private val live = MutableStateFlow(0)

    override fun showLive(): () -> Unit {
        live.update { it + 1 }
        var done = false
        return {
            if (!done) {
                done = true
                live.update { it - 1 }
            }
        }
    }

    /**
     * Follows the game being played ([StoreContext.playing]): its set once it starts; then, while
     * something shows unlocks as they come, RetroAchievements' word on new ones every [WATCH_MS]
     * (one small request, and only while the device is awake); and once more as it closes, for its
     * recap. A game without a set Fuse already knows, or one already mastered, asks nothing: a game
     * has the device, so no ROM is hashed and no list fetched while it runs.
     */
    fun followPlay() {
        ctx.scope.launch {
            var current: Pair<GameId, Long>? = null
            var follow: Job? = null
            ctx.playing.collect { game ->
                val ended = current
                if (ended != null && ended.first != game) {
                    follow?.cancel()
                    current = null
                    launch { wrapUp(ended.first) }
                }
                if (game != null && current?.first != game) {
                    val since = ctx.now()
                    current = game to since
                    playState.value = PlayUnlocks(game, since)
                    follow = launch { follow(game) }
                }
            }
        }
    }

    /** RetroAchievements and the running game's set there, when both are known. */
    private suspend fun watchable(game: GameId): Pair<RetroAchievementsClient, Long>? {
        val client = client() ?: return null
        val raId = ctx.data.games.get(game)?.links?.retroAchievementsGameId ?: return null
        return client to raId
    }

    private suspend fun follow(game: GameId) {
        val (client, raId) = watchable(game) ?: return
        val set = fetch("$KEY_GAME$raId", AchievementState.serializer(), RaCachePolicy.GAME_PROGRESS, force = false) { client.gameProgress(raId) }
        playState.update { if (it.game == game) it.copy(set = set) else it }
        if (set == null || set.total == 0 || set.earned >= set.total) return
        val shown = combine(live, ctx.services.awake) { n, awake -> n > 0 && awake }
        while (true) {
            shown.first { it }
            delay(WATCH_MS)
            if (live.value > 0 && ctx.services.awake.value) notice(client, raId, game)
        }
    }

    /** The game closed: asked once more, so its recap has every unlock, then marked ended. */
    private suspend fun wrapUp(game: GameId) {
        val before = playState.value.takeIf { it.game == game } ?: return
        val mastered = before.set?.let { it.total == 0 || it.earned >= it.total } == true
        if (!mastered) watchable(game)?.let { (client, raId) -> notice(client, raId, game) }
        playState.update { if (it.game == game) it.copy(endedAt = ctx.now()) else it }
        // The Achievements page and Home show them too.
        if (playState.value.unlocks.isNotEmpty()) refresh(force = true)
    }

    /** Asks RetroAchievements what was unlocked since [game] started, and adds what is new. */
    private suspend fun notice(client: RetroAchievementsClient, raId: Long, game: GameId) {
        val since = playState.value.takeIf { it.game == game }?.since ?: return
        val minutes = ((ctx.now() - since) / 60_000 + 3).coerceIn(3, MAX_RECENT_MINUTES)
        val recent = (client.recentAchievements(minutes = minutes.toInt()) as? ApiResult.Success)?.value ?: return
        val before = playState.value
        playState.update { if (it.game == game) it.with(recent, raId) else it }
        val after = playState.value
        if (after.game == game && after.unlocks.size > before.unlocks.size) {
            // The game's page shows them at once, not after its cache runs out.
            after.set?.let { runCatching { cache.put(userNs(), "$KEY_GAME$raId", it, AchievementState.serializer(), ctx.now(), RaCachePolicy.GAME_PROGRESS.inWholeMilliseconds) } }
        }
    }

    /** The Fuse profile in use, whose RetroAchievements account is shown; null without profiles. */
    private fun profile(): String? = ctx.settings.value.sync.activeProfile.takeIf { it.isNotBlank() }

    /** [key] as one profile's own ("ra.username@<profile>"). */
    private fun own(key: String, profile: String) = "$key@$profile"

    /**
     * The account shown now: the profile's own when it connected one, none when it disconnected
     * its own, else the device's (the one connected before profiles had their own, shared by them).
     */
    private suspend fun account(): Pair<String, String>? {
        profile()?.let { p ->
            val user = credentials.get(own(SecretKeys.RA_USERNAME, p))
            if (user != null) {
                if (user == NONE) return null
                return credentials.get(own(SecretKeys.RA_API_KEY, p))?.let { user to it }
            }
        }
        val user = credentials.get(SecretKeys.RA_USERNAME) ?: return null
        val key = credentials.get(SecretKeys.RA_API_KEY) ?: return null
        return user to key
    }

    /** Where this account's own answers are cached: apart for each account, so a switch never shows another person's. */
    private suspend fun userNs(): String = account()?.first?.lowercase()?.let { "$NS.user.$it" } ?: NS

    private suspend fun client(): RetroAchievementsClient? {
        val (user, key) = account() ?: return null
        return RetroAchievementsClient(ctx.services.http, RaCredentials(user, key))
    }

    /** The emulators' own RetroAchievements sign-ins, for the account in use. */
    private val signIns = RaEmulatorSignIn(ctx, credentials, account = { account()?.first }, profile = ::profile)

    override suspend fun emulatorSignIns() = signIns.list()

    override suspend fun signInEmulators(password: String) = signIns.signIn(password)

    override suspend fun signOutEmulators() = signIns.signOut()

    /**
     * The profile in use changed: its own account (or the device's), from the cache, then refreshed;
     * and the emulators Fuse signed in are signed in as this profile's account.
     */
    fun profileChanged() {
        feedState.value = null
        ctx.scope.launch {
            load()
            if (configuredState.value) refresh(force = false)
            runCatching { signIns.follow() }
        }
    }

    /** Loads cached data (no network) and whether an account is connected. */
    suspend fun load() {
        configuredState.value = account() != null
        if (!configuredState.value) return
        val user = cachedAny(KEY_USER, AchievementUser.serializer())
        if (user != null) feedState.value = buildFeed(user, cachedAny(KEY_RECENT, RecentList).orEmpty(), cachedAny(KEY_COMPLETION, CompletionList).orEmpty())
    }

    override fun refresh(force: Boolean) {
        refreshLocal()
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
        return AchievementsFeed(
            user, recentLinked, inProgress, mastered,
            earnedTotal = completion.sumOf { it.numAwarded },
            masteredTotal = completion.count { it.highestAwardKind == "mastered" },
            lastUnlock = completion.mapNotNull { c -> c.mostRecentAwardedDate?.let(::raTime)?.let { c.gameId to it } }.toMap(),
        )
    }

    /** Cached progress for [game] first, then fresh progress when an account is connected. */
    fun gameState(game: Game): Flow<AchievementState?> = flow {
        val raId = game.links.retroAchievementsGameId
        val cached = raId?.let { cachedAny("$KEY_GAME$it", AchievementState.serializer()) }
        emit(cached)
        val fresh = if (configuredState.value) forGame(game) else null
        if (fresh != null) emit(fresh)
        // Without RetroAchievements for it, what its own platform keeps here: Steam's achievements, RPCS3's trophies.
        else if (cached == null) emit(runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { local(game, page = true) } }.getOrNull())
    }

    private val localReader by lazy { io.github.matiyaaa.fuse.library.achievements.LocalAchievements(ctx.services.fs) }

    /**
     * [game]'s achievements from what this device keeps for it, without an account; null when there
     * are none. For its [page], the trophies earned on the person's other devices are looked for too.
     */
    private suspend fun local(game: Game, page: Boolean = false): AchievementState? {
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
                        // Not set out by shadPS4 yet: the game's own trophy file, with the person's key.
                        ?: localReader.ps4TrophiesFromGame(folders, users, ids)
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
        val elsewhere = historyKey(set.source)?.let { (key, emulator) -> trophyHistory(game, items, key, emulator, page) }.orEmpty()
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
     * Shares a game's trophies through RomM, for a game from RomM ([DefaultRommOps.shareTrophies]):
     * the person's note on it read into the game's history, and what was earned here added to it.
     * The second value says something was earned here just now.
     */
    var trophyShare: (suspend (io.github.matiyaaa.fuse.model.GameId, Boolean) -> Unit)? = null

    /**
     * Notes the trophies [items] says were earned here in [game]'s history (once each, the first
     * time) and answers the ones earned on another of the person's devices, by trophy id. On its
     * [page], or when something new was earned, RomM's note of them is read and added to as well,
     * waited for a moment at most (it carries on after).
     */
    private suspend fun trophyHistory(
        game: Game,
        items: List<io.github.matiyaaa.fuse.library.achievements.LocalAchievement>,
        key: String,
        emulator: String,
        page: Boolean,
    ): Map<String, io.github.matiyaaa.fuse.sync.TrophyUnlock> {
        val sync = ctx.settings.value.sync
        val device = ThisDevice.id(ctx)
        val here = items.filter { it.unlockedAt != null }.map { a ->
            io.github.matiyaaa.fuse.sync.TrophyUnlock(key, a.id, a.name, a.unlockedAt!!, device, sync.deviceName, emulator)
        }
        val news = runCatching { TrophyHistory.add(ctx.data, game.id.value, here) }.getOrDefault(false)
        val share = trophyShare
        if (share != null && (page || news)) {
            val going = ctx.scope.async { runCatching { share(game.id, news) } }
            withTimeoutOrNull(SHARE_WAIT_MS) { going.await() }
        }
        val mine = ThisDevice.ids(ctx)
        return runCatching { TrophyHistory.read(ctx.data, game.id.value) }.getOrDefault(emptyList())
            .filter { it.source == key && it.device !in mine }
            .associateBy { it.id }
    }

    /**
     * How a trophy set's unlocks are kept in a game's history, which Fuse One carries to the person's
     * other devices: its kind and the emulator that keeps it. Steam keeps its own across computers.
     */
    private fun historyKey(source: io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource): Pair<String, String>? = when (source) {
        io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.PS3_TROPHIES -> "ps3" to "rpcs3"
        io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.PS4_TROPHIES -> "ps4" to "shadps4"
        io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.VITA_TROPHIES -> "psvita" to "vita3k"
        io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.XBOX360 -> "xbox360" to "xenia"
        io.github.matiyaaa.fuse.library.achievements.LocalAchievementSource.STEAM -> null
    }

    private val localState = MutableStateFlow<List<LocalGameAchievements>?>(null)
    override val local: StateFlow<List<LocalGameAchievements>?> = localState
    private var localJob: Job? = null

    /**
     * Every set this device keeps for the library's games (RPCS3, shadPS4, Vita3K and Xenia's
     * trophies, Steam's achievements), for the Achievements page beside RetroAchievements. Read off
     * the main thread; only the games of those systems are looked at.
     */
    private fun refreshLocal() {
        if (localJob?.isActive == true) return
        localJob = ctx.scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val found = ArrayList<LocalGameAchievements>()
            for (platform in LOCAL_PLATFORMS) {
                val ids = runCatching { ctx.data.games.inPlatform(io.github.matiyaaa.fuse.model.PlatformId(platform)) }.getOrDefault(emptyList()).map { it.first }
                if (ids.isEmpty()) continue
                for (game in runCatching { ctx.data.games.getAll(ids) }.getOrDefault(emptyList())) {
                    val state = runCatching { local(game) }.getOrNull() ?: continue
                    if (state.total > 0) found += LocalGameAchievements(game.id, state)
                }
            }
            localState.value = found
        }
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

    /**
     * The rcheevos hash of [game]'s ROM, or null when it can't be hashed. Kept with the file's size
     * and change time (Fuse Identity), so it is worked out once; for consoles whose hash is the whole
     * file's MD5, that MD5 is kept too, for RomM and the rest of Fuse.
     */
    private suspend fun hashOf(consoleId: Int, game: Game): String? {
        val path = RaDiscFiles.dataFile(game.location.launchPath) { ctx.services.fs.readText(it) }
        val stat = runCatching { ctx.services.fs.stat(path) }.getOrNull()
        val stamp = stat?.let { "file:${it.sizeBytes}:${it.modifiedAt}" }
        val identity = ctx.data.identity
        if (stamp != null) {
            runCatching { identity.forGame(game.id) }.getOrNull()
                ?.firstOrNull { it.kind == io.github.matiyaaa.fuse.data.repo.IdentityRepository.RA && it.source == stamp }
                ?.let { return it.value }
        }
        val launch = game.location.launchPath
        val zipped = launch.endsWith(".zip", ignoreCase = true)
        val result = if (consoleId in RaFileHasher.discConsoles && !zipped) {
            discHash(consoleId, launch)
        } else {
            val source = openZippedRom(path) ?: openByteSource(path)
            try {
                RaHasher.hash(consoleId, path, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                source?.close()
            }
        }
        val hashed = result as? io.github.matiyaaa.fuse.integrations.retroachievements.RaHashResult.Hashed ?: return null
        if (stamp != null) runCatching {
            identity.replace(game.id, io.github.matiyaaa.fuse.data.repo.IdentityRepository.RA, listOf(hashed.md5), stamp)
            val wholeFile = hashed.method == io.github.matiyaaa.fuse.integrations.retroachievements.RaHashMethod.WHOLE_FILE && !RaHasher.isCompressed(path) &&
                !path.endsWith(".zip", ignoreCase = true) && (stat?.sizeBytes ?: Long.MAX_VALUE) <= RaHasher.MAX_BUFFER_BYTES
            if (wholeFile) identity.replace(game.id, io.github.matiyaaa.fuse.data.repo.IdentityRepository.MD5, listOf(hashed.md5), "ra")
        }
        return hashed.md5
    }

    /**
     * A disc game's hash through [RaFileHasher]: cue sheets, .gdi, CHD, CSO and ZSO, GameCube GCZ, WIA
     * and RVZ, 3DS dumps. Every file it opens is closed again; a 3DS dump that is still encrypted is
     * read with the person's own Azahar or Citra keys when they have them.
     */
    private suspend fun discHash(consoleId: Int, launch: String): io.github.matiyaaa.fuse.integrations.retroachievements.RaHashResult? {
        val opened = ArrayList<ClosableByteSource>()
        val files = object : DiscFiles {
            override suspend fun open(path: String): io.github.matiyaaa.fuse.integrations.ByteSource? =
                openByteSource(path)?.also { opened += it }

            override suspend fun readText(path: String): String? =
                runCatching { ctx.services.fs.readText(path) }.getOrNull()
        }
        return try {
            RaFileHasher.hash(consoleId, launch, files) { threeDsKeys() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    /** The person's own 3DS keys from Azahar or Citra (sysdata/aes_keys.txt), or null. */
    private suspend fun threeDsKeys(): String? {
        val home = ctx.services.emulators.homeDir?.replace('\\', '/')?.trimEnd('/') ?: return null
        val roots = when (ctx.host) {
            Host.LINUX -> listOf(
                "$home/.local/share/azahar-emu", "$home/.var/app/org.azahar_emu.Azahar/data/azahar-emu",
                "$home/.local/share/citra-emu", "$home/.var/app/org.citra_emu.citra/data/citra-emu",
            )
            Host.WINDOWS -> listOf("$home/AppData/Roaming/Azahar", "$home/AppData/Roaming/Citra")
            Host.MACOS -> listOf("$home/Library/Application Support/Azahar", "$home/Library/Application Support/Citra")
            Host.ANDROID -> emptyList()
        }
        for (root in roots) {
            runCatching { ctx.services.fs.readText("$root/sysdata/aes_keys.txt") }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
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
                // The profile in use gets it as its own; the first account on a device is the
                // device's too, for profiles that haven't one of their own.
                val p = profile()
                if (p != null) {
                    credentials.put(own(SecretKeys.RA_USERNAME, p), user)
                    credentials.put(own(SecretKeys.RA_API_KEY, p), key)
                }
                if (p == null || credentials.get(SecretKeys.RA_USERNAME) == null) {
                    credentials.put(SecretKeys.RA_USERNAME, user)
                    credentials.put(SecretKeys.RA_API_KEY, key)
                }
                val ns = userNs()
                cache.clear(ns)
                cache.put(ns, KEY_USER, r.value, AchievementUser.serializer(), ctx.now(), RaCachePolicy.SUMMARY.inWholeMilliseconds)
                configuredState.value = true
                refresh(force = true)
                Result.success(Unit)
            }
            is ApiResult.AuthError -> Result.failure(IllegalStateException("RetroAchievements didn't accept that username and web API key."))
            is ApiResult.Failure -> Result.failure(IllegalStateException("Couldn't reach RetroAchievements: ${r.message}"))
        }
    }

    override suspend fun disconnect() {
        runCatching { cache.clear(userNs()) }
        val p = profile()
        if (p != null) {
            // This profile only: it has no account from now on, and the others keep theirs.
            credentials.put(own(SecretKeys.RA_USERNAME, p), NONE)
            credentials.remove(own(SecretKeys.RA_API_KEY, p))
            credentials.remove(own(SecretKeys.RA_TOKEN, p))
        } else {
            credentials.remove(SecretKeys.RA_USERNAME)
            credentials.remove(SecretKeys.RA_API_KEY)
            credentials.remove(SecretKeys.RA_TOKEN)
            cache.clear(NS)
        }
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
        val ns = userNs()
        if (!force) cache.get(ns, key, serializer, now)?.let { return it }
        return when (val r = load()) {
            is ApiResult.Success -> {
                cache.put(ns, key, r.value, serializer, now, ttl.inWholeMilliseconds)
                r.value
            }
            is ApiResult.Failure -> cachedAny(key, serializer)
        }
    }

    override suspend fun week(): io.github.matiyaaa.fuse.ui.shell.store.WeekAchievement? {
        val client = client() ?: return null
        val w = fetch(KEY_WEEK, io.github.matiyaaa.fuse.integrations.retroachievements.RaAchievementOfTheWeek.serializer(), WEEK_TTL, force = false) { client.achievementOfTheWeek() }
            ?: return null
        val raGame = w.game.id.takeIf { it > 0 } ?: return null
        // The set, for its badge and whether the person has it (cached as the game page's is).
        val set = fetch("$KEY_GAME$raGame", AchievementState.serializer(), RaCachePolicy.GAME_PROGRESS, force = false) { client.gameProgress(raGame) }
        val a = set?.achievements?.firstOrNull { it.id == w.achievement.id }
        val local = runCatching { ctx.data.games.idsForRetroAchievements(listOf(raGame))[raGame]?.firstOrNull() }.getOrNull()
        return io.github.matiyaaa.fuse.ui.shell.store.WeekAchievement(
            title = w.achievement.title,
            description = w.achievement.description,
            points = w.achievement.points,
            game = w.game.title,
            console = w.console.title,
            badgeUrl = a?.let { if (it.earned) it.badgeUrl else it.badgeLockedUrl },
            gameIconUrl = set?.iconUrl,
            unlocks = w.unlocksCount,
            players = w.totalPlayers,
            earned = a?.earned == true,
            localGame = local,
        )
    }

    override suspend fun following(): List<io.github.matiyaaa.fuse.ui.shell.store.FollowedPlayer> {
        val client = client() ?: return emptyList()
        val page = fetch(KEY_FOLLOWING, io.github.matiyaaa.fuse.integrations.retroachievements.RaFollowing.serializer(), FOLLOWING_TTL, force = false) {
            client.following(count = FOLLOWED_SHOWN)
        } ?: return emptyList()
        // What each is playing, from their summary: a few at most, one request each, each cached.
        return page.results.take(FOLLOWED_SHOWN).map { f ->
            val summary = fetch("$KEY_FOLLOWED${f.user.lowercase()}", io.github.matiyaaa.fuse.integrations.retroachievements.RaUserSummary.serializer(), FOLLOWING_TTL, force = false) {
                client.userSummary(f.ulid ?: f.user, recentGames = 1, recentAchievements = 0)
            }
            io.github.matiyaaa.fuse.ui.shell.store.FollowedPlayer(
                name = f.user,
                points = f.points,
                followsYou = f.followsMe,
                avatarUrl = "$MEDIA/UserPic/${f.user}.png",
                game = summary?.recentlyPlayed?.firstOrNull()?.title?.takeIf { it.isNotBlank() },
                presence = summary?.richPresenceMsg?.takeIf { it.isNotBlank() && !it.startsWith("Unknown") },
            )
        }
    }

    override suspend fun leaderboards(game: GameId): List<io.github.matiyaaa.fuse.ui.shell.store.GameLeaderboard>? {
        val client = client() ?: return null
        val raId = ctx.data.games.get(game)?.links?.retroAchievementsGameId ?: return null
        val serializer = io.github.matiyaaa.fuse.integrations.retroachievements.RaLeaderboards.serializer()
        val all = fetch("$KEY_BOARDS$raId", serializer, FOLLOWING_TTL, force = false) { client.gameLeaderboards(raId) } ?: return null
        val mine = fetch("$KEY_MY_BOARDS$raId", serializer, FOLLOWING_TTL, force = false) { client.myLeaderboards(raId) }
            ?.results.orEmpty().associateBy { it.id }
        return all.results.map { b ->
            val m = mine[b.id]?.mine
            io.github.matiyaaa.fuse.ui.shell.store.GameLeaderboard(b.title, b.description, b.top?.user, b.top?.formattedScore, m?.formattedScore, m?.rank)
        }.sortedBy { it.myRank == null }
    }

    /** Cached value regardless of age (shown while offline or rate limited). */
    private suspend fun <T> cachedAny(key: String, serializer: KSerializer<T>): T? =
        runCatching { cache.get(userNs(), key, serializer, now = 0L) }.getOrNull()

    /** RetroAchievements' times ("2024-05-01T12:34:56+00:00", or with a space and no zone) as epoch millis. */
    private fun raTime(text: String): Long? {
        val t = text.trim().replace(' ', 'T')
        return runCatching { kotlin.time.Instant.parse(t).toEpochMilliseconds() }.getOrNull()
            ?: runCatching { kotlin.time.Instant.parse(t + "Z").toEpochMilliseconds() }.getOrNull()
    }

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
        /** The systems whose own achievements Fuse reads on this device. */
        private val LOCAL_PLATFORMS = listOf("ps3", "ps4", "psvita", "xbox360", "steam")
        private val STEAM_ID = Regex("(?:rungameid/|^\\s*)(\\d{1,10})")

        /** An Xbox 360 title id, and the way it is tagged in a file name ("Halo 3 [4D5307E6].iso"). */
        private val X360_TITLE = Regex("[0-9A-Fa-f]{8}")
        private val X360_TAG = Regex("\\[([0-9A-Fa-f]{8})]")
        const val NS = "retroachievements"
        /** A profile's own account, disconnected: it shows none, not the device's. */
        const val NONE = "-"
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
        /** The achievement of the week, asked at most every few hours (it changes on Mondays). */
        const val KEY_WEEK = "week"
        val WEEK_TTL: Duration = 6.hours
        /** The people the person follows, and each one's summary, asked at most every few minutes. */
        const val KEY_FOLLOWING = "following"
        const val KEY_FOLLOWED = "followed:"
        /** A game's leaderboards, and the person's own scores on them. */
        const val KEY_BOARDS = "boards:"
        const val KEY_MY_BOARDS = "myboards:"
        val FOLLOWING_TTL: Duration = 15.minutes
        /** How many of them are shown, so a long list asks no more than this many summaries. */
        const val FOLLOWED_SHOWN = 8
        const val MEDIA = io.github.matiyaaa.fuse.integrations.retroachievements.RaMedia.BASE
        const val RARITY_TTL_MS = 24 * 60 * 60 * 1000L
        const val MISS_TTL_MS = 24L * 60 * 60 * 1000

        /** How long a game's page waits for RomM's note of its trophies before showing what it has. */
        const val SHARE_WAIT_MS = 4_000L
        const val RECENT_WINDOW_MINUTES = 60 * 24 * 14

        /** How often RetroAchievements is asked about new unlocks while they are shown as they come. */
        const val WATCH_MS = 60_000L

        /** The longest look back for one game's unlocks: a day of play. */
        const val MAX_RECENT_MINUTES = 24 * 60L
        val RecentList = ListSerializer(RecentAchievement.serializer())
        val CompletionList = ListSerializer(RaCompletionEntry.serializer())
        val GameList = ListSerializer(RaGameListEntry.serializer())
    }
}
