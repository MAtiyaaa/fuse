package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol
import io.github.matiyaaa.fuse.integrations.github.GitHubReleases
import io.github.matiyaaa.fuse.integrations.github.SemVer
import io.github.matiyaaa.fuse.integrations.getOrNull
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.ReleaseInfo
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.ui.shell.store.CartridgeOps
import io.github.matiyaaa.fuse.ui.shell.store.RecentDownload
import io.github.matiyaaa.fuse.ui.shell.store.UpdateOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Fuse's side of the Cartridge bridge: status, recent downloads and deep links. When Cartridge
 * reports a library change (a finished download), Fuse runs a quick scan so the game shows up
 * without a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultCartridgeOps(
    private val ctx: StoreContext,
    private val engine: LibraryEngine,
) : CartridgeOps {
    private val state = MutableStateFlow(CartridgeStatus())
    override val status: StateFlow<CartridgeStatus> = state
    private var readJob: Job? = null
    private var watcher: AutoCloseable? = null
    private var seenLibraryChange: Long? = null

    override val recent: StateFlow<List<RecentDownload>> = combine(
        state.map { it.recent }.distinctUntilChanged(),
        // Re-match after scans, so a download shows as a game as soon as Fuse indexed it.
        engine.scan.map { it.phase }.distinctUntilChanged(),
    ) { downloads, _ -> downloads }
        .mapLatest { downloads ->
            downloads.map { d ->
                val id = d.path?.let { ctx.data.games.idByPath(it) }
                RecentDownload(d, id?.let { ctx.card(it) })
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    fun start() {
        watcher = runCatching { ctx.services.cartridge.watch { refresh() } }.getOrNull()
        refresh()
    }

    override fun open(route: CartridgeRoute) {
        ctx.services.cartridge.open(route, CartridgeProtocol.deepLink(route))
    }

    override suspend fun latestRelease(): ReleaseInfo? =
        GitHubReleases(ctx.services.http).latest(CartridgeProtocol.RELEASE_REPO).getOrNull()

    override suspend fun install(release: ReleaseInfo): Result<Unit> {
        val asset = GitHubReleases.pickAsset(release, ctx.services.installer.platform)
            ?: return Result.failure(IllegalStateException("This Cartridge release has no build for this device."))
        return ctx.services.installer.install(asset)
    }

    override fun refresh() {
        if (readJob?.isActive == true) return
        readJob = ctx.scope.launch { readNow() }
    }

    /** On return to Fuse: re-read status, and rescan when Cartridge changed the library meanwhile. */
    suspend fun refreshOnResume() {
        readJob?.cancel()
        readNow()
    }

    private suspend fun readNow() {
        val next = try {
            ctx.services.cartridge.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        state.value = next
        val changedAt = next.libraryChangedAt
        val previous = seenLibraryChange
        seenLibraryChange = changedAt
        val changed = previous != null && changedAt > previous
        if (changed && ctx.settings.value.cartridge.autoRefreshOnReturn) {
            // Let Cartridge finish writing the file before looking at the folder.
            delay(300)
            engine.rescan(ScanScope.QUICK)
        }
    }
}

internal class DefaultUpdateOps(private val ctx: StoreContext) : UpdateOps {
    override val currentVersion: String = ctx.services.appVersion
    private val latest = MutableStateFlow<ReleaseInfo?>(null)
    override val available: StateFlow<ReleaseInfo?> = latest

    /** Checks at most once a day on its own; [check] from Settings always asks. */
    suspend fun checkIfDue() {
        if (!ctx.settings.value.updates.checkForUpdates) return
        val last = ctx.data.cache.entry(NS, KEY_CHECKED)?.fetchedAt ?: 0
        if (ctx.now() - last < DAY_MS) {
            latest.value = ctx.data.cache.get(NS, KEY_RELEASE, ReleaseInfo.serializer(), now = 0L)
                ?.takeIf { SemVer.isNewer(it.tag, currentVersion) }
            return
        }
        check()
    }

    override suspend fun check(): ReleaseInfo? {
        val result = GitHubReleases(ctx.services.http).latest(GitHubReleases.FUSE_REPO)
        val now = ctx.now()
        ctx.data.cache.put(NS, KEY_CHECKED, "{}", now, ttlMs = null)
        val release = (result as? ApiResult.Success)?.value
        if (release != null) {
            ctx.data.cache.put(NS, KEY_RELEASE, release, ReleaseInfo.serializer(), now, ttlMs = null)
        }
        latest.value = release?.takeIf { SemVer.isNewer(it.tag, currentVersion) }
        return latest.value
    }

    /** Downloads, verifies and opens the system installer. Only called after the user confirmed. */
    override suspend fun install(release: ReleaseInfo): Result<Unit> {
        val asset = GitHubReleases.pickAsset(release, ctx.services.installer.platform)
            ?: return Result.failure(IllegalStateException("This release has no build for this device."))
        return ctx.services.installer.install(asset)
    }

    private companion object {
        const val NS = "updates"
        const val KEY_CHECKED = "checked"
        const val KEY_RELEASE = "release"
        const val DAY_MS = 86_400_000L
    }
}
