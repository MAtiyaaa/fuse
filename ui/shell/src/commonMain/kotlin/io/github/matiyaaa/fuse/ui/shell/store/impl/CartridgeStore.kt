package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeMatch
import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol
import io.github.matiyaaa.fuse.integrations.github.GitHubReleases
import io.github.matiyaaa.fuse.integrations.github.SemVer
import io.github.matiyaaa.fuse.integrations.getOrNull
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.ReleaseInfo
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.ui.shell.store.CartridgeOps
import io.github.matiyaaa.fuse.ui.shell.store.RecentDownload
import io.github.matiyaaa.fuse.ui.shell.store.UpdateOps
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
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
 * without a restart. With bridge protocol 2 it also brings in RomM's details and pictures for the
 * games Cartridge downloaded ([CartridgeDetails]).
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
    private var seenGamesRevision: Long? = null
    private val details = CartridgeDetails(ctx)
    private var gamesJob: Job? = null

    /** Games Cartridge reported that Fuse hadn't indexed yet: matched again after the next scan. */
    private var waitingForScan = false

    override val recent: StateFlow<List<RecentDownload>> = combine(
        state.map { it.recent }.distinctUntilChanged(),
        // Re-match after scans, so a download shows as a game as soon as Fuse indexed it.
        engine.scan.map { it.phase }.distinctUntilChanged(),
    ) { downloads, _ -> downloads }
        .mapLatest { downloads ->
            // The same file can be written differently by the two apps (see CartridgeMatch).
            val match = if (downloads.any { it.path != null }) CartridgeMatch(ctx.data.games.paths()) else null
            downloads.map { d ->
                val id = d.path?.let { p -> match?.find(p) }
                if (id != null && d.romId > 0) rememberRomId(id, d.romId)
                RecentDownload(d, id?.let { ctx.card(it) })
            }
        }
        .flowOn(Dispatchers.Default)
        .resilient().stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    /** Links a game to its RomM entry so "Open in Cartridge" can jump straight to it. */
    private suspend fun rememberRomId(id: io.github.matiyaaa.fuse.model.GameId, romId: Long) {
        val current = ctx.data.games.get(id)?.links ?: return
        if (current.rommRomId != romId) ctx.data.games.updateLinks(id) { it.copy(rommRomId = romId) }
    }

    private val enabled: Boolean get() = ctx.settings.value.cartridge.enabled

    /** Follows the Cartridge switch: watching and reading only while it is on. */
    fun start() {
        // A scan that just finished may have indexed games Cartridge already reported.
        ctx.scope.launch {
            engine.scan.map { it.phase }.distinctUntilChanged().collect { phase ->
                if (phase == ScanPhase.DONE && waitingForScan) syncGames()
            }
        }
        // Turning RomM's details on applies them straight away.
        ctx.scope.launch {
            ctx.settings.map { it.cartridge.rommDetails }.distinctUntilChanged().collect { on -> if (on) syncGames() }
        }
        ctx.scope.launch {
            ctx.settings.map { it.cartridge.enabled }.distinctUntilChanged().collect { on ->
                watcher?.let { runCatching { it.close() } }
                watcher = null
                readJob?.cancel()
                if (on) {
                    watcher = runCatching { ctx.services.cartridge.watch { refresh() } }.getOrNull()
                    refresh()
                } else {
                    // Everything Cartridge-related hides when it reads as not installed.
                    state.value = CartridgeStatus()
                    gamesJob?.cancel()
                    seenGamesRevision = null
                }
            }
        }
    }

    /**
     * Reads the games Cartridge downloaded (protocol 2) and brings in their RomM details. One run at
     * a time; a newer change restarts it.
     */
    private fun syncGames() {
        if (!enabled || state.value.protocol < CartridgeProtocol.GAMES_PROTOCOL) return
        gamesJob?.cancel()
        gamesJob = ctx.scope.launch(Dispatchers.Default) {
            val games = try {
                ctx.services.cartridge.games()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@launch
            val outcome = details.sync(games, applyDetails = ctx.settings.value.cartridge.rommDetails)
            waitingForScan = outcome.unmatched > 0
        }
    }

    /** Opening can wait on the system (xdg-open on Linux), so it never runs on the caller's thread. */
    override fun open(route: CartridgeRoute) {
        ctx.scope.launch(Dispatchers.Default) { ctx.services.cartridge.open(route, CartridgeProtocol.deepLink(route)) }
    }

    override suspend fun latestRelease(): ReleaseInfo? =
        GitHubReleases(ctx.services.http).latest(CartridgeProtocol.RELEASE_REPO).getOrNull()

    override suspend fun install(release: ReleaseInfo): Result<Unit> {
        val asset = GitHubReleases.pickAsset(release, ctx.services.installer.platform)
            ?: return Result.failure(IllegalStateException("This Cartridge release has no build for this device."))
        return ctx.services.installer.install(asset)
    }

    override fun refresh() {
        if (!enabled || readJob?.isActive == true) return
        readJob = ctx.scope.launch { readNow() }
    }

    /** On return to Fuse: re-read status, and rescan when Cartridge changed the library meanwhile. */
    suspend fun refreshOnResume() {
        if (!enabled) return
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
        if (!enabled) return
        state.value = next
        val changedAt = next.libraryChangedAt
        val previous = seenLibraryChange
        seenLibraryChange = changedAt
        val changed = previous != null && changedAt > previous
        if (next.protocol >= CartridgeProtocol.GAMES_PROTOCOL && (next.gamesRevision != seenGamesRevision || changed)) {
            seenGamesRevision = next.gamesRevision
            syncGames()
        }
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

    private val stateFlow = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = stateFlow
    private var job: Job? = null

    override fun download(release: ReleaseInfo) {
        val current = stateFlow.value
        if (current is UpdateState.Downloading || current is UpdateState.Installing) return
        if (current is UpdateState.Ready && current.release.tag == release.tag) return
        val asset = GitHubReleases.pickAsset(release, ctx.services.installer.platform)
        if (asset == null) {
            stateFlow.value = UpdateState.Failed(release, "This release has no build for this device.")
            return
        }
        stateFlow.value = UpdateState.Downloading(release, null)
        job = ctx.scope.launch {
            val result = try {
                ctx.services.installer.download(asset) { p -> stateFlow.value = UpdateState.Downloading(release, p) }
            } catch (e: CancellationException) {
                stateFlow.value = UpdateState.Idle
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            stateFlow.value = result.fold(
                onSuccess = { UpdateState.Ready(release, it) },
                onFailure = { UpdateState.Failed(release, it.message ?: "The download failed. Try again.") },
            )
        }
    }

    override fun cancelDownload() {
        job?.cancel()
        job = null
        stateFlow.value = UpdateState.Idle
    }

    override suspend fun apply(): Result<Boolean> {
        val ready = stateFlow.value as? UpdateState.Ready ?: return Result.failure(IllegalStateException("Download the update first."))
        stateFlow.value = UpdateState.Installing(ready.release)
        val result = try {
            ctx.services.installer.applyUpdate(ready.file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        // Stays ready: after a refusal (install permission not given yet, the confirmation dismissed)
        // it can be tried again without downloading again.
        stateFlow.value = ready
        return result
    }

    private companion object {
        const val NS = "updates"
        const val KEY_CHECKED = "checked"
        const val KEY_RELEASE = "release"
        const val DAY_MS = 86_400_000L
    }
}
