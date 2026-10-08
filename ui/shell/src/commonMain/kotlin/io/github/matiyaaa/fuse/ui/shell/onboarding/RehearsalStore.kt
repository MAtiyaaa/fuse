package io.github.matiyaaa.fuse.ui.shell.onboarding

import io.github.matiyaaa.fuse.model.*
import io.github.matiyaaa.fuse.ui.shell.store.*
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.data.settings.SyncSettings
import io.github.matiyaaa.fuse.jellyfin.JellyfinClient
import io.github.matiyaaa.fuse.jellyfin.JellyfinService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*

/** A new, disposable installation. It has no reference to the running installation or filesystem. */
class RehearsalStore(scope: CoroutineScope, host: Host = Host.LINUX) : FuseStore {
    private val serviceJob = SupervisorJob(scope.coroutineContext[Job])
    private val serviceScope = CoroutineScope(scope.coroutineContext + serviceJob)
    override val displaySession = DisplaySession()
    override val prefs = MutableStateFlow(UiPrefs())
    private val secretValues = mutableMapOf<String, String>()
    private val sampleGames = MutableStateFlow<List<GameCard>>(emptyList())
    private val sampleSystems = listOfNotNull(
        io.github.matiyaaa.fuse.library.PlatformCatalog.byId("nes"),
        io.github.matiyaaa.fuse.library.PlatformCatalog.byId("n64"),
    )
    private fun scanSamples() {
        sampleGames.value = sampleSystems.mapIndexed { index, system ->
            GameCard(GameId((index + 1).toLong()), system.id, "Rehearsal adventure ${index + 1}", system.shortName, system.accent, Art.None)
        }
        (library.platforms as MutableStateFlow<List<PlatformCard>>).value = sampleSystems.map { system ->
            PlatformCard(system, 1, Art.None, "Rehearsal emulator", true, 1, BiosStatus.NotRequired, LibraryLayout.ICON, listOf("/rehearsal/ROMs/${system.id.value}"))
        }
        (library.home as MutableStateFlow<HomeFeed>).value = HomeFeed(recentlyAdded = sampleGames.value, systems = library.platforms.value)
    }
    private val http = HttpClient(MockEngine { request ->
        val body = when {
            request.url.encodedPath.endsWith("System/Info/Public") -> """{"Id":"rehearsal","ServerName":"Rehearsal Jellyfin","Version":"10.11.0"}"""
            request.url.encodedPath.endsWith("Users/AuthenticateByName") -> """{"User":{"Id":"rehearsal","Name":"Rehearsal viewer"},"AccessToken":"rehearsal-only","ServerId":"rehearsal"}"""
            request.url.encodedPath.contains("Latest") -> "[]"
            else -> """{"Items":[],"TotalRecordCount":0}"""
        }
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    override val jellyfin = JellyfinService(
        JellyfinClient(http, io.github.matiyaaa.fuse.jellyfin.DeviceInfo("Fuse rehearsal", "rehearsal", "0.4.1")),
        object : SecretStore {
            override suspend fun get(key: String) = secretValues[key]
            override suspend fun put(key: String, value: String) { secretValues[key] = value }
            override suspend fun remove(key: String) { secretValues.remove(key) }
        }, serviceScope,
    )
    override fun updatePrefs(transform: (UiPrefs) -> UiPrefs) {
        prefs.value = transform(prefs.value)
        val j = prefs.value.jellyfin
        jellyfin.configure(j.enabled, io.github.matiyaaa.fuse.jellyfin.JellyfinConnection(io.github.matiyaaa.fuse.jellyfin.ConnectionMode.valueOf(j.mode), j.localAddress, j.remoteAddress))
    }
    val people = FakeSyncService(canHost = host != Host.ANDROID)
    override val sync = object : SyncOps {
        override val service = people
        override val config = MutableStateFlow(SyncSettings())
        override suspend fun configure(change: (SyncSettings) -> SyncSettings) { config.value = change(config.value); prefs.value = prefs.value.copy(sync = config.value) }
        override suspend fun setEnabled(enabled: Boolean, keepProfiles: Boolean) {
            config.value = config.value.copy(enabled = enabled)
            prefs.value = prefs.value.copy(sync = config.value)
            people.setEnabled(enabled, keepProfiles)
        }
        override suspend fun setOwnHome(own: Boolean) { config.value = config.value.copy(homeScope = if (own) "DEVICE" else "PROFILE") }
    }
    override val library = object : LibraryOps {
        override val home = MutableStateFlow(HomeFeed())
        override val platforms = MutableStateFlow<List<PlatformCard>>(emptyList())
        override fun games(query: GameQuery) = sampleGames
        override fun game(id: GameId) = flowOf<GameDetail?>(null)
        override fun search(query: String) = sampleGames.map { SearchResults(query, games = it.filter { card -> card.title.contains(query, ignoreCase = true) }) }
        override suspend fun launch(id: GameId, emulator: EmulatorId?, discPath: String?, display: LaunchDisplay?, skipSaveCheck: Boolean, playAnyway: Boolean, onStage: (LaunchStage) -> Unit) = LaunchOutcome.Started
        override fun onResume() = Unit
        override fun onPause() = Unit
        override suspend fun setFavorite(id: GameId, favorite: Boolean) = Unit
        override suspend fun setHidden(id: GameId, hidden: Boolean) = Unit
        override suspend fun setPinned(id: GameId, pinned: Boolean) = Unit
        override suspend fun rename(id: GameId, title: String?) = Unit
        override suspend fun setEmulator(id: GameId, emulator: EmulatorId?) = Unit
        override suspend fun setFolderPolicy(id: GameId, policy: FolderPolicy?) = Unit
        override suspend fun removeFromFuse(id: GameId) = Unit
        override suspend fun restore(id: GameId) = Unit
        override suspend fun forgetMissing(id: GameId) = Unit
        override suspend fun previewCleanNames() = emptyList<Pair<String, String>>()
        override suspend fun applyCleanNames(enabled: Boolean) = Unit
        override suspend fun undoCleanNames() = true
        override suspend fun launchCandidates(id: GameId) = emptyList<String>()
    }
    override val sources = object : SourceOps {
        override val sources = MutableStateFlow<List<LibrarySource>>(emptyList())
        override val scan = MutableStateFlow(ScanProgress(ScanPhase.IDLE))
        override suspend fun add(path: String, kind: LibrarySourceKind): LibrarySource {
            val source = LibrarySource(LibrarySourceId((sources.value.size + 1).toLong()), path, "Rehearsal games", kind)
            sources.value = sources.value + source
            return source
        }
        override suspend fun remove(source: LibrarySource) { sources.value = sources.value - source }
        override suspend fun suggestions() = listOf(SuggestedSource("/rehearsal/ROMs", "Rehearsal ROMs", LibrarySourceKind.ROMS_ROOT, 2))
        override fun rescan(scope: ScanScope, platform: PlatformId?) { scanSamples(); scan.value = ScanProgress(ScanPhase.DONE, gamesFound = sampleGames.value.size) }
        override fun refreshBios() = Unit
        override suspend fun findSteamGames(extra: String?) = listOf(io.github.matiyaaa.fuse.library.steam.SteamGame(1, "Rehearsal Steam game", "/rehearsal/steam/game", "/rehearsal/steam"))
        override suspend fun addSteamGames(games: List<io.github.matiyaaa.fuse.library.steam.SteamGame>): Int { scanSamples(); return games.size }
    }
    override val emulators = object : EmulatorOps {
        override val installed = MutableStateFlow<List<InstalledEmulator>>(emptyList())
        override fun refresh() = Unit
        override fun optionsFor(platform: PlatformId) = emptyList<EmulatorOption>()
        override suspend fun setPlatformEmulator(platform: PlatformId, emulator: EmulatorId?) = Unit
        override suspend fun openEmulator(emulator: EmulatorId) = Unit
        override fun limitations(emulator: EmulatorId) = emptyList<String>()
        override fun homepage(emulator: EmulatorId): String? = null
    }
    override val media = object : MediaOps {
        override fun media(owner: MediaOwner) = flowOf(MediaSet())
        override suspend fun artworkOptions(owner: MediaOwner, kind: MediaKind) = ArtworkResult.Options(emptyList())
        override suspend fun apply(owner: MediaOwner, option: ArtworkOption) = Unit
        override suspend fun setFromFile(owner: MediaOwner, kind: MediaKind, path: String) = Unit
        override suspend fun adjust(owner: MediaOwner, kind: MediaKind, focusX: Float, focusY: Float, zoom: Float) = Unit
        override suspend fun reset(owner: MediaOwner, kind: MediaKind?) = Unit
        override fun fill(mode: MediaFillMode, kinds: Set<MediaKind>, platform: PlatformId?, game: GameId?) { fillProgress.value = FillProgress(0, 0, null, 0, true) }
        override fun fillEverything(platform: PlatformId?, remote: Boolean) { fillProgress.value = FillProgress(0, 0, null, 0, true) }
        override fun cancelFill() = Unit
        override val fillProgress = MutableStateFlow<FillProgress?>(null)
        override suspend fun searchTitle(game: GameId): SearchTitle? = null
        override suspend fun identify(game: GameId) = IdentifyResult.Matches("Rehearsal", emptyList())
        override suspend fun acceptCandidate(game: GameId, candidate: ScrapeCandidate) = true
        override suspend fun resetDetails(game: GameId) = true
        override val providers = MutableStateFlow<List<ProviderStatus>>(emptyList())
        override val keyChecks = MutableStateFlow<Map<ScrapeProviderId, io.github.matiyaaa.fuse.integrations.KeyCheck?>>(emptyMap())
        override fun checkKeys() = Unit
        override fun downloadSystemArt() = Unit
        override val systemArtProgress = MutableStateFlow<FillProgress?>(null)
    }
    override val collections = object : CollectionOps {
        override val collections = MutableStateFlow<List<GameCollection>>(emptyList())
        override suspend fun create(name: String): CollectionId {
            val id = CollectionId((collections.value.size + 1).toLong())
            collections.value += GameCollection(id, name, CollectionKind.MANUAL)
            return id
        }
        override suspend fun rename(id: CollectionId, name: String) { collections.value = collections.value.map { if (it.id == id) it.copy(name = name) else it } }
        override suspend fun delete(id: CollectionId) { collections.value = collections.value.filterNot { it.id == id } }
        override suspend fun add(id: CollectionId, game: GameId) = Unit
        override suspend fun addGames(id: CollectionId, games: List<GameId>) = Unit
        override suspend fun remove(id: CollectionId, game: GameId) = Unit
        override suspend fun membership(game: GameId) = emptySet<CollectionId>()
        override suspend fun keepSeries(id: CollectionId) = Unit
    }
    override val achievements = object : AchievementOps {
        override val feed = MutableStateFlow<AchievementsFeed?>(null)
        override val configured = MutableStateFlow(false)
        override fun refresh(force: Boolean) = Unit
        override suspend fun forGame(game: GameId): AchievementState? = null
        override suspend fun connect(username: String, apiKey: String): Result<Unit> { configured.value = true; return Result.success(Unit) }
        override suspend fun disconnect() { configured.value = false }
    }
    override val apps = object : AppOps {
        override val supported = true
        override fun apps(filter: AppFilter) = flowOf(emptyList<AppCard>())
        override suspend fun launch(app: AppCard, display: LaunchDisplay?) = Unit
        override suspend fun setPinned(app: AppCard, pinned: Boolean) = Unit
        override suspend fun setHidden(app: AppCard, hidden: Boolean) = Unit
        override suspend fun rename(app: AppCard, title: String?) = Unit
        override suspend fun openInfo(app: AppCard) = Unit
    }
    override val cartridge = object : CartridgeOps {
        override val status = MutableStateFlow(CartridgeStatus())
        override val recent = MutableStateFlow<List<RecentDownload>>(emptyList())
        override fun open(route: CartridgeRoute) = Unit
        override suspend fun latestRelease(): ReleaseInfo? = null
        override suspend fun install(release: ReleaseInfo) = Result.success(Unit)
        override fun refresh() = Unit
        override suspend fun upload(game: GameId) = UploadHandoff.OPENED
    }
    override val settings = object : ScopedSettingsOps {
        override fun <T> observe(key: ScopedKey<T>, platform: PlatformId?, game: GameId?) = flowOf(Resolved(key.default, SettingScope.GLOBAL, true))
        override suspend fun <T> set(key: ScopedKey<T>, scope: ScopeRef, value: T) = Unit
        override suspend fun <T> clear(key: ScopedKey<T>, scope: ScopeRef) = Unit
        override suspend fun setLayout(platform: PlatformId?, layout: LibraryLayout) = Unit
        override suspend fun setBorder(scope: ScopeRef, border: BorderStyle) = Unit
    }
    override val credentials = object : CredentialOps {
        override val stored = MutableStateFlow<Set<String>>(emptySet())
        override suspend fun put(key: String, value: String) { secretValues[key] = value; stored.value = secretValues.keys.toSet() }
        override suspend fun remove(key: String) { secretValues.remove(key); stored.value = secretValues.keys.toSet() }
    }
    override val updates = object : UpdateOps {
        override val available = MutableStateFlow<ReleaseInfo?>(null)
        override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        override suspend fun check(): ReleaseInfo? = null
        override fun download(release: ReleaseInfo) = Unit
        override fun cancelDownload() = Unit
        override suspend fun apply() = Result.success(false)
        override val currentVersion = "0.4.1 rehearsal"
    }
    override val storage = object : StorageOps {
        override val usage = MutableStateFlow<StorageUsage?>(null)
        override fun refresh() = Unit
        override suspend fun size(game: GameId) = 0L
        override suspend fun delete(games: List<GameId>) = DeleteReport(games.size, 0, emptyList())
    }
    override val themes = object : ThemeOps {
        override suspend fun fetch(url: String) = Result.success("{}")
        override suspend fun readFile(path: String) = Result.success("{}")
        override fun parse(text: String) = ThemeCodec.parse(text, io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets::find, io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets.Fuse)
        override suspend fun add(spec: ThemeSpec, json: String, source: String?, apply: Boolean) = Unit
        override suspend fun remove(id: String) = Unit
        override fun export(spec: ThemeSpec) = ThemeCodec.encode(spec)
    }
    override val romm = FakeRommOps()

    init {
        people.onSetup = { role, name ->
            val configured = sync.config.value.copy(enabled = true, role = role, hostName = name)
            (sync.config as MutableStateFlow<SyncSettings>).value = configured
            prefs.value = prefs.value.copy(sync = configured)
        }
    }

    /** Called after the nested composition and its coroutines leave; no rehearsal state survives. */
    fun close() {
        serviceJob.cancel()
        people.stop()
        http.close()
        secretValues.clear()
        sampleGames.value = emptyList()
        (library.platforms as MutableStateFlow<List<PlatformCard>>).value = emptyList()
        (library.home as MutableStateFlow<HomeFeed>).value = HomeFeed()
        (credentials.stored as MutableStateFlow<Set<String>>).value = emptySet()
        (sources.sources as MutableStateFlow<List<LibrarySource>>).value = emptyList()
        (collections.collections as MutableStateFlow<List<GameCollection>>).value = emptyList()
        (sync.config as MutableStateFlow<SyncSettings>).value = SyncSettings()
        prefs.value = UiPrefs()
    }
}
