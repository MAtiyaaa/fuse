package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.AppOverrideRepository
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.model.AppFilter
import io.github.matiyaaa.fuse.model.AppGames
import io.github.matiyaaa.fuse.model.AppKind
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.KnownApps
import io.github.matiyaaa.fuse.model.LaunchDisplay
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.PlatformFolderScan
import io.github.matiyaaa.fuse.model.ScannedGame
import io.github.matiyaaa.fuse.ui.shell.store.ApkInstall
import io.github.matiyaaa.fuse.ui.shell.store.AppCard
import io.github.matiyaaa.fuse.ui.shell.store.AppIconModel
import io.github.matiyaaa.fuse.ui.shell.store.AppOps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Installed apps with the user's choices applied. Each app is a game, an app or an emulator: Fuse
 * decides (emulators it detected, then what the app says about itself) until the user picks "Type"
 * in its options. On Android, games also join the library in the Android system ([AppGames]), with
 * their icon, art from the scrapers and play time like any other game.
 */
internal class DefaultAppOps(private val ctx: StoreContext) : AppOps {
    private val provider = ctx.services.apps
    private val overrides = ctx.data.apps
    override val supported: Boolean = provider != null
    override val gamesInLibrary: Boolean = provider != null && ctx.host == Host.ANDROID

    /** Called after apps joined the library as games, so their art is looked for. */
    var onGamesAdded: (List<io.github.matiyaaa.fuse.model.GameId>) -> Unit = {}

    /** Packages being installed, and what each is once Android has installed it (a game, or a port of one). */
    private val expected = MutableStateFlow<Map<String, Expected>>(emptyMap())

    /** What an app being installed is: [kind], and for a port the library game it [plays]. */
    private data class Expected(val kind: AppKind, val plays: io.github.matiyaaa.fuse.model.GameId? = null)

    /**
     * Whether an installed app (its package and its name) is a known recomp or port; set by the
     * Android ports, which know them. Such an app is one until the person says otherwise.
     */
    val portIdentifier = MutableStateFlow<(String, String) -> Boolean> { _, _ -> false }

    /** Packages of the emulators Fuse detected; their apps are emulators unless the user says otherwise. */
    private val emulatorPackages: Flow<Set<String>> = ctx.installed
        .map { list -> list.filter { it.host == ctx.host }.map { it.appId.substringBefore('/') }.toSet() }
        .distinctUntilChanged()

    private val entries: Flow<List<AppEntry>> = if (provider == null) {
        flowOf(emptyList())
    } else {
        combine(provider.apps, overrides.observeAll(), emulatorPackages, portIdentifier) { apps, o, emulators, isPort ->
            val detected = apps.map {
                when {
                    it.packageName in emulators -> it.copy(detectedKind = AppKind.EMULATOR)
                    isPort(it.packageName, it.label) -> it.copy(detectedKind = AppKind.PORT)
                    // Streaming apps and emulation tools from the Store get lists of their own.
                    else -> KnownApps.kindOf(it.packageName)?.let { kind -> it.copy(detectedKind = kind) } ?: it
                }
            }
            AppOverrideRepository.applyTo(detected, o)
        }
    }

    private val all: StateFlow<List<AppEntry>> = entries.resilient().stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    /** Every installed app with the person's choices, as it is now. */
    val installedApps: StateFlow<List<AppEntry>> get() = all

    /** Icons the user set for apps (Customise Artwork), by app id. They win over the app's own icon. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val customIcons: StateFlow<Map<String, Any>> = all
        .map { list -> list.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            ctx.data.media.observeFor(ids.map { MediaOwner.OfApp(it) }).map { media ->
                media.mapNotNull { (owner, set) ->
                    val id = (owner as? MediaOwner.OfApp)?.packageName ?: return@mapNotNull null
                    (set.icon ?: set.square ?: set.boxart ?: set.grid)?.model?.let { id to (it as Any) }
                }.toMap()
            }
        }
        .resilient()
        .stateIn(ctx.scope, SharingStarted.Eagerly, emptyMap())

    override fun apps(filter: AppFilter): Flow<List<AppCard>> = combine(all, customIcons) { list, icons ->
        // Apps played as games live in the Android system with the other games, not in Apps.
        val visible = list.filterNot { it.hidden || (gamesInLibrary && it.kind == AppKind.GAME) }
        when (filter) {
            AppFilter.PINNED -> visible.filter { it.pinned }
            // A recomp or port is listed with the games: the game it plays is in the library.
            AppFilter.GAMES -> visible.filter { it.kind == AppKind.GAME || it.kind == AppKind.PORT }.sortedBy { it.displayTitle.lowercase() }
            AppFilter.EMULATORS -> visible.filter { it.kind == AppKind.EMULATOR }.sortedBy { it.displayTitle.lowercase() }
            AppFilter.STREAMING -> visible.filter { it.kind == AppKind.STREAMING }.sortedBy { it.displayTitle.lowercase() }
            AppFilter.TOOLS -> visible.filter { it.kind == AppKind.TOOL }.sortedBy { it.displayTitle.lowercase() }
            AppFilter.ALL -> visible.sortedBy { it.displayTitle.lowercase() }
        }.map { card(it, icons) }
    }

    override fun everyApp(): Flow<List<AppCard>> = combine(all, customIcons) { list, icons ->
        list.sortedBy { it.displayTitle.lowercase() }.map { card(it, icons) }
    }

    fun search(query: String): List<AppCard> =
        all.value.filter { !it.hidden && !(gamesInLibrary && it.kind == AppKind.GAME) && it.displayTitle.contains(query, ignoreCase = true) }.take(20).map { card(it, customIcons.value) }

    fun refreshInstalled() {
        provider?.refresh()
    }

    /** The installed app with [appId], if there is one. */
    fun app(appId: String): AppCard? = all.value.firstOrNull { it.id == appId }?.let { card(it, customIcons.value) }

    private fun card(entry: AppEntry, icons: Map<String, Any>) = AppCard(entry, icons[entry.id] ?: provider?.iconModel(entry))

    override suspend fun launch(app: AppCard, display: LaunchDisplay?) {
        val p = provider ?: return
        val displayId = if (display == LaunchDisplay.SECONDARY) ctx.services.launcher.secondaryDisplayId() else null
        p.launch(app.entry, displayId)
        overrides.markUsed(app.entry.id, ctx.now())
    }

    override suspend fun setPinned(app: AppCard, pinned: Boolean) = overrides.setPinned(app.entry.id, pinned)

    override suspend fun setHidden(app: AppCard, hidden: Boolean) = overrides.setHidden(app.entry.id, hidden)

    override suspend fun rename(app: AppCard, title: String?) =
        overrides.setCustomTitle(app.entry.id, title?.trim()?.takeIf { it.isNotEmpty() })

    override suspend fun setKind(appId: String, kind: AppKind?) = overrides.setKind(appId, kind)

    override suspend fun setPlays(appId: String, game: io.github.matiyaaa.fuse.model.GameId?) = overrides.setPlays(appId, game?.value)

    override suspend fun expect(packageName: String, kind: AppKind, plays: io.github.matiyaaa.fuse.model.GameId?) {
        val installed = all.value.filter { it.packageName == packageName }
        // Already there (an update): it is what the person said at once. Else once Android has installed it.
        if (installed.isEmpty()) {
            expected.update { it + (packageName to Expected(kind, plays)) }
            return
        }
        installed.forEach { apply(it.id, Expected(kind, plays)) }
    }

    private suspend fun apply(appId: String, e: Expected) {
        setKind(appId, e.kind)
        if (e.kind == AppKind.PORT) setPlays(appId, e.plays)
    }

    override suspend fun openInfo(app: AppCard) {
        provider?.openInfo(app.entry)
    }

    override suspend fun installGame(path: String): ApkInstall {
        val p = provider ?: return ApkInstall.Failed("Apps can't be installed on this system.")
        val result = p.installApk(path)
        // A game until the person says what it is ("What is it?" follows the install).
        if (result is ApkInstall.Started) expect(result.packageName, AppKind.GAME, null)
        return result
    }

    /** Keeps the library's Android games in step with the apps that are games, from now on. */
    fun start() {
        if (!gamesInLibrary) return
        ctx.scope.launch {
            all.collect { list ->
                val waiting = expected.value
                if (waiting.isEmpty()) return@collect
                val arrived = list.filter { it.packageName in waiting }
                arrived.forEach { a -> waiting[a.packageName]?.let { apply(a.id, it) } }
                if (arrived.isNotEmpty()) expected.update { it - arrived.map { a -> a.packageName }.toSet() }
            }
        }
        ctx.scope.launch {
            all.map { list -> if (list.isEmpty()) null else list.filter { it.kind == AppKind.GAME || it.standalonePort } }
                .distinctUntilChanged()
                .collect { games -> if (games != null) runCatching { syncGames(games) } }
        }
    }

    /**
     * Files every game app under the Android system, and every recomp or port that plays no game of
     * the library ([standalonePort]) under Ports, as a game of its own: new ones are added with their
     * icon, renamed ones follow, one that becomes a port or a game again moves system as the same game
     * (its play time and art with it), and ones that were uninstalled or aren't games any more leave
     * the lists (their play time and art are kept for when they come back).
     */
    private suspend fun syncGames(games: List<AppEntry>) {
        val scan = PlatformFolderScan(
            sourceId = AppGames.SOURCE,
            platformId = AppGames.PLATFORM,
            folderPath = AppGames.FOLDER,
            folderModifiedAt = 0,
            games = games.map { app ->
                val path = AppGames.path(app.id)
                ScannedGame(
                    platformId = if (app.standalonePort) io.github.matiyaaa.fuse.launch.ports.PortAdapter.PLATFORM else AppGames.PLATFORM,
                    sourceId = AppGames.SOURCE,
                    path = path,
                    kind = LocationKind.FILE,
                    launchPath = path,
                    title = app.label,
                    modifiedAt = app.installedAt ?: 0,
                    localMedia = mapOf(MediaKind.ICON to AppIconModel.ref(app.packageName)),
                )
            },
            complete = true,
        )
        val delta = ctx.data.indexer.applyFolder(scan, ctx.now(), DisplayNameCleaner::clean, useCleanedForNew = false)
        if (delta.addedIds.isNotEmpty()) onGamesAdded(delta.addedIds)
    }
}

/** A recomp or port that plays no game of the library: a game of its own, in Ports. */
private val AppEntry.standalonePort: Boolean get() = kind == AppKind.PORT && plays == null
