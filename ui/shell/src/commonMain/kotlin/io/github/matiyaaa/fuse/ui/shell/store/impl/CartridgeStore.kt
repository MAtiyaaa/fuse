package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeMatch
import io.github.matiyaaa.fuse.integrations.cartridge.CartridgeProtocol
import io.github.matiyaaa.fuse.integrations.getOrNull
import io.github.matiyaaa.fuse.integrations.github.GitHubReleases
import io.github.matiyaaa.fuse.integrations.github.SemVer
import io.github.matiyaaa.fuse.library.storage.UploadFiles
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.CartridgeUpload
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.ReleaseInfo
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.ui.shell.store.CartridgeOps
import io.github.matiyaaa.fuse.ui.shell.store.RecentDownload
import io.github.matiyaaa.fuse.ui.shell.store.UpdateOps
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
import io.github.matiyaaa.fuse.ui.shell.store.UploadHandoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val reads = Channel<Unit>(Channel.CONFLATED)
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
                // A path that leads to another game (see CartridgeDetails) shows as not found yet.
                val id = d.path?.let { p -> match?.find(p) }
                    ?.takeIf { found -> ctx.data.games.get(found)?.let { details.fits(it, d.title, d.platformSlug) } == true }
                if (id != null && d.romId > 0) rememberRomId(id, d.romId)
                d to id
            }
        }
        // Follows the matched games, so art and details that arrive after the download (RomM's
        // pictures, a scrape) show on the tile without leaving the page.
        .flatMapLatest { matched ->
            val ids = matched.mapNotNull { it.second }.distinct()
            if (ids.isEmpty()) {
                flowOf(matched.map { (d, _) -> RecentDownload(d, null) })
            } else {
                combine(
                    ctx.data.media.observeFor(ids.map { MediaOwner.OfGame(it) }),
                    combine(ids.map { ctx.data.games.observe(it) }) { it.toList() },
                    ctx.offline,
                ) { media, _, roots ->
                    val summaries = ids.mapNotNull { id -> ctx.data.games.summary(id)?.let { id to it } }.toMap()
                    matched.map { (d, id) ->
                        RecentDownload(d, id?.let { summaries[it] }?.let { ctx.summaryToCard(it, media[MediaOwner.OfGame(it.id)], roots) })
                    }
                }
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .resilient().stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    /** Links a game to its RomM entry so "Open in Cartridge" can jump straight to it. */
    private suspend fun rememberRomId(id: io.github.matiyaaa.fuse.model.GameId, romId: Long) {
        val current = ctx.data.games.get(id)?.links ?: return
        if (current.rommRomId != romId) ctx.data.games.updateLinks(id) { it.copy(rommRomId = romId) }
    }

    private val enabled: Boolean get() = ctx.settings.value.cartridge.enabled

    private val noticeFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val notices: Flow<String> = noticeFlow

    override suspend fun checkRommMatches(): Int = withContext(Dispatchers.Default) {
        val undone = details.repair()
        ctx.data.cache.put(REPAIR_NS, REPAIR_KEY, "\"$REPAIR_VERSION\"", ctx.now(), ttlMs = null)
        undone
    }

    /** Follows the Cartridge switch: watching and reading only while it is on. */
    fun start() {
        // Once: undo RomM details an earlier version gave games they didn't belong to.
        ctx.scope.launch(Dispatchers.Default) {
            if (ctx.data.cache.entry(REPAIR_NS, REPAIR_KEY)?.valueJson == "\"$REPAIR_VERSION\"") return@launch
            val undone = checkRommMatches()
            if (undone > 0) noticeFlow.tryEmit(if (undone == 1) "Put back the name of a game RomM had mixed up" else "Put back the names of $undone games RomM had mixed up")
        }
        ctx.scope.launch { for (request in reads) if (enabled) readNow() }
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
            if (outcome.undone > 0) {
                noticeFlow.tryEmit(if (outcome.undone == 1) "Put back the name of a game RomM had mixed up" else "Put back the names of ${outcome.undone} games RomM had mixed up")
            }
        }
    }

    /** Opening can wait on the system (xdg-open on Linux), so it never runs on the caller's thread. */
    override fun open(route: CartridgeRoute) {
        ctx.scope.launch(Dispatchers.Default) { ctx.services.cartridge.open(route, CartridgeProtocol.deepLink(route)) }
    }

    override suspend fun upload(game: GameId): UploadHandoff = withContext(Dispatchers.Default) {
        val status = state.value
        if (!enabled || !status.installed) return@withContext UploadHandoff.NOT_INSTALLED
        if (!CartridgeProtocol.supportsUploads(status)) return@withContext UploadHandoff.TOO_OLD
        val g = ctx.data.games.get(game) ?: return@withContext UploadHandoff.NO_FILES
        val files = try {
            UploadFiles.collect(ctx.services.fs, g.location, g.discs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (files.isEmpty()) return@withContext UploadHandoff.NO_FILES
        val handed = try {
            ctx.services.cartridge.upload(CartridgeUpload(g.displayTitle, g.platformId.value, files))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (handed) UploadHandoff.OPENED else UploadHandoff.FAILED
    }

    override suspend fun latestRelease(): ReleaseInfo? =
        GitHubReleases(ctx.services.http).latest(CartridgeProtocol.RELEASE_REPO).getOrNull()

    override suspend fun install(release: ReleaseInfo): Result<Unit> {
        val asset = GitHubReleases.pickAsset(release, ctx.services.installer.platform)
            ?: return Result.failure(IllegalStateException("This Cartridge release has no build for this device."))
        return ctx.services.installer.install(asset)
    }

    override fun refresh() {
        // Conflated: a request made during a read queues exactly one more read, so a change
        // Cartridge announces mid-read is never lost and a burst of announcements costs one read.
        if (enabled) reads.trySend(Unit)
    }

    /** On return to Fuse: re-read status, and rescan when Cartridge changed the library meanwhile. */
    suspend fun refreshOnResume() {
        if (!enabled) return
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
    override val inPlace: Boolean get() = ctx.services.installer.inPlace

    /**
     * A release counts for this device once it has this device's build: the APK is published first
     * and the desktop builds join minutes later, so a Mac never hears of an update it can't get yet.
     */
    private fun ReleaseInfo.isUpdateHere(): Boolean =
        SemVer.isNewer(tag, currentVersion) && GitHubReleases.pickAsset(this, ctx.services.installer.platform) != null
    private val latest = MutableStateFlow<ReleaseInfo?>(null)
    override val available: StateFlow<ReleaseInfo?> = latest

    /** Checks at most once a day on its own; [check] from Settings always asks. */
    suspend fun checkIfDue() {
        if (!ctx.settings.value.updates.checkForUpdates) return
        val last = ctx.data.cache.entry(NS, KEY_CHECKED)?.fetchedAt ?: 0
        if (ctx.now() - last < DAY_MS) {
            latest.value = ctx.data.cache.get(NS, KEY_RELEASE, ReleaseInfo.serializer(), now = 0L)
                ?.takeIf { it.isUpdateHere() }
            return
        }
        check()
    }

    override suspend fun check(): ReleaseInfo? {
        val result = GitHubReleases(ctx.services.http).latest(GitHubReleases.FUSE_REPO)
        val now = ctx.now()
        val release = (result as? ApiResult.Success)?.value
        if (release != null) {
            ctx.data.cache.put(NS, KEY_RELEASE, release, ReleaseInfo.serializer(), now, ttlMs = null)
        }
        // A newer release still waiting for this device's build is asked about again in an hour.
        val waiting = release != null && SemVer.isNewer(release.tag, currentVersion) && !release.isUpdateHere()
        ctx.data.cache.put(NS, KEY_CHECKED, "{}", if (waiting) now - DAY_MS + HOUR_MS else now, ttlMs = null)
        latest.value = release?.takeIf { it.isUpdateHere() }
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
        const val HOUR_MS = 3_600_000L
    }
}

private const val REPAIR_NS = "cartridge.romm.repair"
private const val REPAIR_KEY = "done"

/** Moves when the check of RomM's details changes, so it runs once more. */
private const val REPAIR_VERSION = 1
