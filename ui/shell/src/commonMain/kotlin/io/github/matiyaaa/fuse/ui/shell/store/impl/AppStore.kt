package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.AppOverrideRepository
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.model.AppFilter
import io.github.matiyaaa.fuse.model.AppGames
import io.github.matiyaaa.fuse.model.AppKind
import io.github.matiyaaa.fuse.model.Host
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

    /** Packages being installed from an APK the user added as a game. */
    private val expectedGames = MutableStateFlow<Set<String>>(emptySet())

    /** Packages of the emulators Fuse detected; their apps are emulators unless the user says otherwise. */
    private val emulatorPackages: Flow<Set<String>> = ctx.installed
        .map { list -> list.filter { it.host == ctx.host }.map { it.appId.substringBefore('/') }.toSet() }
        .distinctUntilChanged()

    private val entries: Flow<List<AppEntry>> = if (provider == null) {
        flowOf(emptyList())
    } else {
        combine(provider.apps, overrides.observeAll(), emulatorPackages) { apps, o, emulators ->
            val detected = apps.map { if (it.packageName in emulators) it.copy(detectedKind = AppKind.EMULATOR) else it }
            AppOverrideRepository.applyTo(detected, o)
        }
    }

    private val all: StateFlow<List<AppEntry>> = entries.resilient().stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

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
            AppFilter.GAMES -> visible.filter { it.kind == AppKind.GAME }.sortedBy { it.displayTitle.lowercase() }
            AppFilter.EMULATORS -> visible.filter { it.kind == AppKind.EMULATOR }.sortedBy { it.displayTitle.lowercase() }
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

    override suspend fun openInfo(app: AppCard) {
        provider?.openInfo(app.entry)
    }

    override suspend fun installGame(path: String): ApkInstall {
        val p = provider ?: return ApkInstall.Failed("Apps can't be installed on this system.")
        val result = p.installApk(path)
        if (result is ApkInstall.Started) {
            val installed = all.value.filter { it.packageName == result.packageName }
            // Already there (an update): it is a game from now on. Else once Android has installed it.
            if (installed.isNotEmpty()) installed.forEach { setKind(it.id, AppKind.GAME) } else expectedGames.update { it + result.packageName }
        }
        return result
    }

    /** Keeps the library's Android games in step with the apps that are games, from now on. */
    fun start() {
        if (!gamesInLibrary) return
        ctx.scope.launch {
            all.collect { list ->
                val expected = expectedGames.value
                if (expected.isEmpty()) return@collect
                val arrived = list.filter { it.packageName in expected && it.chosenKind == null }
                arrived.forEach { setKind(it.id, AppKind.GAME) }
                if (arrived.isNotEmpty()) expectedGames.update { it - arrived.map { a -> a.packageName }.toSet() }
            }
        }
        ctx.scope.launch {
            all.map { list -> if (list.isEmpty()) null else list.filter { it.kind == AppKind.GAME } }
                .distinctUntilChanged()
                .collect { games -> if (games != null) runCatching { syncGames(games) } }
        }
    }

    /**
     * Files every game app under the Android system: new ones are added with their icon, renamed ones
     * follow, and ones that were uninstalled or aren't games any more leave the lists (their play
     * time and art are kept for when they come back).
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
                    platformId = AppGames.PLATFORM,
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
