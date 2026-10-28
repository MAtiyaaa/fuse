package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.github.matiyaaa.fuse.data.settings.StoredTheme
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.ThemeCodec
import io.github.matiyaaa.fuse.model.ThemeLinks
import io.github.matiyaaa.fuse.model.ThemeSpec
import io.github.matiyaaa.fuse.ui.designsystem.res.Res
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.shell.music.BundledMusic
import io.github.matiyaaa.fuse.ui.shell.store.AppStoreOps
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.ThemeOps
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray

/**
 * The store over Fuse's core modules. Preferences change in memory first (so the interface reacts
 * on the same frame) and are written to the database in order on a background coroutine.
 */
internal class DefaultFuseStore private constructor(
    private val ctx: StoreContext,
    initialPrefs: UiPrefs,
    jellyfinDeviceId: String,
) : FuseStore {
    override val displaySession = io.github.matiyaaa.fuse.ui.shell.store.DisplaySession()
    private val data = ctx.data
    private val prefsState = MutableStateFlow(initialPrefs)
    @kotlin.concurrent.Volatile private var prefsProfile = ctx.settings.value.sync.activeProfile

    /**
     * The preferences as storage holds them for [prefsProfile], as last read or written: what the
     * interface shows beyond this is a choice not saved yet. It starts as [initialPrefs], read from
     * storage a moment ago: a choice made before anything is written is then known as one.
     */
    @kotlin.concurrent.Volatile private var storedPrefs: UiPrefs? = initialPrefs

    /** The settings as storage held them when [storedPrefs] was: what changed in storage since is someone else's ([shownOver]). */
    @kotlin.concurrent.Volatile private var storedSettings: io.github.matiyaaa.fuse.data.settings.AppSettings? = ctx.settings.value
    override val prefs: StateFlow<UiPrefs> = prefsState
    private val writes = Channel<UiPrefs>(Channel.CONFLATED)
    private val writeLock = Mutex()

    override val emulators = DefaultEmulatorOps(ctx)
    private val engine = LibraryEngine(ctx)
    override val sources = engine
    override val collections = DefaultCollectionOps(ctx)
    override val apps = DefaultAppOps(ctx)
    private val mediaOps: DefaultMediaOps
    override val credentials = DefaultCredentialOps(ctx) { key, removed ->
        if (!key.startsWith("ra.") && key !in SecretKeys.deviceOnly) mediaOps.invalidate()
        // A key removed here stays off this device; adding it again lets the household's follow.
        if (key in SecretKeys.all) ctx.scope.launch {
            sync.configure { c ->
                val off = if (removed) (c.keysKeptOff + key).distinct() else c.keysKeptOff - key
                if (off == c.keysKeptOff) c else c.copy(keysKeptOff = off)
            }
        }
    }
    override val achievements = DefaultAchievementOps(ctx, credentials)
    override val cartridge = DefaultCartridgeOps(ctx, engine)
    override val updates = DefaultUpdateOps(ctx)
    override val storage = DefaultStorageOps(ctx, engine.drives, engine, copiesOf = { id -> reach.availability(id) })
    override val settings = DefaultScopedSettingsOps(ctx) { reloadPrefs() }
    private val appStoreOps = ctx.services.packages?.let { DefaultAppStoreOps(ctx, it, prefsState) { t -> updatePrefs(t) } }
    /** A computer's Store, where Fuse can put programs in place. */
    private val desktopStoreOps = if (appStoreOps == null) ctx.services.desktopApps?.let { DesktopAppStoreOps(ctx, it, afterUpdate = { key -> library.recheckTogether(key) }) { emulators.refresh() } } else null
    override val appStore: AppStoreOps = appStoreOps ?: desktopStoreOps ?: AppStoreOps.None
    /**
     * Fuse Ports, where the computer has a folder for them; made when Ports is first shown. On
     * Android, ports published as apps, installed like the Store's and attached to their games.
     */
    override val ports: io.github.matiyaaa.fuse.ui.shell.store.PortsOps by lazy {
        ctx.services.portFiles?.let {
            PortsStore(ctx, it, library = { library }, write = { t -> writeSettings(t) }, recompFiles = ctx.services.recompFiles, sources = RommAndHousehold(romm, reach))
        }
            ?: ctx.services.packages?.takeIf { ctx.host == io.github.matiyaaa.fuse.model.Host.ANDROID }?.let { bridge ->
                AndroidPortsStore(
                    ctx, bridge, ctx.services.androidPortLists, apps, write = { t -> writeSettings(t) },
                    sources = RommAndHousehold(romm, reach),
                    findCover = { title, platform -> mediaOps.coverFor(title, platform) },
                )
            }
            ?: io.github.matiyaaa.fuse.ui.shell.store.PortsOps.None
    }
    // Made when Fuse first shows or plays media, never by a Fast Start.
    override val jellyfin by lazy {
        io.github.matiyaaa.fuse.jellyfin.JellyfinService(
            io.github.matiyaaa.fuse.jellyfin.JellyfinClient(
                ctx.services.http,
                io.github.matiyaaa.fuse.jellyfin.DeviceInfo(ctx.services.deviceName, jellyfinDeviceId, ctx.services.appVersion),
            ),
            ctx.services.secrets,
            ctx.scope,
            disk = JellyfinFiles(ctx.services),
            discovery = ctx.services.jellyfinDiscovery,
            awake = ctx.rounds,
        )
    }
    private val mediaFeed = MutableStateFlow(io.github.matiyaaa.fuse.jellyfin.MediaFeed())
    override val homeFeed: StateFlow<io.github.matiyaaa.fuse.ui.shell.store.HomeFeed> by lazy {
        kotlinx.coroutines.flow.combine(library.home, mediaFeed) { h, m -> if (m == h.media) h else h.copy(media = m) }
            // Fuseline 4.2: the moment Home first has games to show, for the start's report.
            .onEach { h -> if (h.recentlyAdded.isNotEmpty() || h.continuePlaying.isNotEmpty() || h.systems.isNotEmpty()) io.github.matiyaaa.fuse.ui.designsystem.effects.StartupTrace.mark(io.github.matiyaaa.fuse.ui.designsystem.effects.StartupTrace.HOME) }
            .stateIn(ctx.scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, library.home.value)
    }
    override val content = DefaultContentOps(ctx, emulators) { engine.drives.volumes.value }

    override val extras: io.github.matiyaaa.fuse.ui.shell.store.ExtrasOps by lazy {
        ExtrasStore(ctx, detect = { emulators.detectNow() }, bios = { engine.bios.value }, refreshBios = { engine.refreshBios() })
    }
    override val backup = DefaultBackupOps(ctx) { restore ->
        // A restored backup replaces everything: nothing shown before it is carried over.
        writeLock.withLock { restore().also { storedPrefs = null; reloadLocked() } }
    }
    override val library: DefaultLibraryOps
    override val health: DefaultHealthOps
    override val compat: io.github.matiyaaa.fuse.ui.shell.store.CompatOps by lazy { DefaultCompatOps(ctx) }
    override val observatory: io.github.matiyaaa.fuse.ui.shell.store.ObservatoryOps by lazy { DefaultObservatoryOps(ctx) { q -> library.search(q).first() } }

    /** Every transfer Fuse makes for the person, kept in Fuse's own data folder across restarts. */
    private val transferEngine = io.github.matiyaaa.fuse.transfer.newTransfers(
        "${ctx.services.dataDir.trimEnd('/')}/transfers", ctx.scope, ctx::now, { engine.drives.volumes.value },
    )
    override val transfers = DefaultTransfersOps(ctx, transferEngine, appStore, updates, cartridge)
    override val romm: DefaultRommOps
    override val reach: DefaultReachOps
    override val streaming: DefaultStreamingOps by lazy { DefaultStreamingOps(ctx) { t -> writeSettings(t) } }
    override val offlineMedia: DefaultOfflineMedia by lazy { DefaultOfflineMedia(ctx, engine, transferEngine, jellyfin) }

    /** Fuse Sync over this library: the person's records and settings, read and put in place. */
    private val profileData = LibraryProfileData(ctx.data, settings = { settingsNow() }, applySettings = { t -> writeSettings(t) },
        applyProfileSettings = { profile, values -> writeProfileSettings(profile, values) })
    override val sync = DefaultSyncOps(ctx, profileData) { t -> writeSettings(t) }

    override val rommSaves: io.github.matiyaaa.fuse.sync.romm.RommSaveService? get() = library.rommSaves

    override val syncthing: io.github.matiyaaa.fuse.sync.syncthing.SyncthingService? =
        runCatching { ctx.services.syncthingService(ctx.scope) }.getOrNull()

    init {
        mediaOps = DefaultMediaOps(ctx, credentials)
        // Apps that became games and games added by hand are identified and filled straight away.
        val findArt: (List<io.github.matiyaaa.fuse.model.GameId>) -> Unit = { ids -> mediaOps.fillNew(ids) }
        apps.onGamesAdded = findArt
        library = DefaultLibraryOps(ctx, engine, emulators, collections, apps, achievements, cartridge, changeSettings = { t -> writeSettings(t) }) { enabled ->
            updatePrefs { it.copy(cleanDisplayNames = enabled) }
        }
        library.onGamesAdded = findArt
        // Syncthing keeps a game's save folders in step around it, and plans folders from the library.
        syncthing?.let { st ->
            library.syncthing = st
            st.useLibrary { library.saveSamples() }
        }
        // Only one way moves this device's saves (Fuse One, RomM or Syncthing): settings from before
        // the choice existed, or something switched on since, are settled to one as they change.
        ctx.scope.launch {
            ctx.data.settings.settings.collect { s ->
                val settled = io.github.matiyaaa.fuse.data.settings.SaveProvider.settle(s)
                if (settled != s) {
                    writeSettings { io.github.matiyaaa.fuse.data.settings.SaveProvider.settle(it) }
                    if (s.syncthing.enabled && !settled.syncthing.enabled) syncthing?.let { st -> runCatching { st.setEnabled(false) } }
                }
            }
        }
        // RomM's save sync, for games from (or matched to) RomM. With more than one person here, each one's saves are their own.
        library.rommSaves = runCatching {
            ctx.services.rommSaves(
                env = { sync.service?.saveEnvironment }, client = { romm.saveClient() }, server = { romm.serverKey },
                person = { sync.service?.takeIf { sync.inUse && it.profiles.value.size > 1 }?.activeProfile?.value?.name },
            )
        }.getOrNull()
        library.installedBoot = { id -> content.bootFile(id) }
        library.writeSettings = { t -> writeSettings(t) }
        library.notInstalled = { id ->
            content.view(id)?.takeIf { it.plan.storageReadable && !it.plan.gameInstalled && it.mode == io.github.matiyaaa.fuse.ui.shell.store.InstallMode.FUSE }?.let { v ->
                io.github.matiyaaa.fuse.ui.shell.store.Problem(
                    title = "${v.title} isn't installed yet",
                    message = "It's a package, so ${v.emulatorName} plays it once it is installed. Fuse installs it, with its updates and DLC, and checks it went in.",
                    kind = io.github.matiyaaa.fuse.ui.shell.store.ProblemKind.FILE,
                    severity = io.github.matiyaaa.fuse.ui.shell.store.Severity.INFO,
                    reassurance = null,
                    actions = listOf(io.github.matiyaaa.fuse.ui.shell.store.ProblemAction.InstallContent(id)),
                )
            }
        }
        health = DefaultHealthOps(ctx, engine, library, mediaOps, updates, { credentials.stored.value }, { cartridge.status.value })
        romm = DefaultRommOps(
            ctx, engine, transferEngine,
            write = { t -> writeSettings { s -> s.copy(romm = t(s.romm)) } },
            cartridgeOff = { writeSettings { s -> if (s.cartridge.enabled) s.copy(cartridge = s.cartridge.copy(enabled = false)) else s } },
            choiceFor = { g -> library.choiceFor(g) },
            details = CartridgeDetails(ctx),
            fillRemote = { ids, visible -> mediaOps.fillRemote(ids, visible) },
        )
        // The Sync tab's view of RomM's saves: how each game's save here stands.
        romm.saveService = library.rommSaves
        // The person's changes to games from RomM go back to it, when they chose that.
        library.rommChange = { id, make -> romm.changed(id, make) }
        // Trophies earned in emulators reach the person's other devices through RomM's notes too.
        achievements.trophyShare = { id, news -> romm.shareTrophies(id, news) }
        // A game played with a port (Play With) starts as the port's game; Ports is made only for one.
        library.portPlay = { id -> if (ctx.settings.value.ports.playWith.containsKey(id.value.toString())) (ports as? PortsStore)?.playTargetNamed(id) else null }
        // On Android, as the port's app attached to it (Play With).
        library.androidPort = { id -> if (ctx.settings.value.ports.androidPlayWith.containsKey(id.value.toString())) (ports as? AndroidPortsStore)?.playApp(id) else null }
        romm.plainSaveQuery = { id -> library.plainSaveQueryFor(id) }
        reach = DefaultReachOps(
            ctx, engine, transferEngine, sync.service, romm,
            householdIds = { profileData.householdIds() },
            choiceFor = { g -> library.choiceFor(g) },
            fillRemote = { ids, visible -> mediaOps.fillRemote(ids, visible) },
            showRemote = { rows, act -> transfers.showRemote(rows, act) },
        )
    }

    /**
     * [shown] written over [onto], as the interface always writes it, except what changed in storage
     * since this Fuse last read or wrote it ([storedSettings]) and the person didn't change here: that
     * is someone else's (Fuse One bringing the profile's in, another Fuse open on the same settings)
     * and stays as storage has it. Written back as shown, a setting from a moment ago went to every
     * device as the person's newest choice: their theme went back to the one this Fuse last saw, a few
     * seconds after they chose another. True beside it when something of someone else's was kept,
     * so the interface shows what is stored.
     */
    private fun shownOver(onto: io.github.matiyaaa.fuse.data.settings.AppSettings, shown: UiPrefs): Pair<io.github.matiyaaa.fuse.data.settings.AppSettings, Boolean> {
        val written = onto.withUiPrefs(shown)
        val read = storedSettings
        val base = storedPrefs
        if (read == null || base == null || read == onto) return written to false
        val kept = io.github.matiyaaa.fuse.data.settings.UnsavedChoices.keepOthers(written, read, onto, read.withUiPrefs(base), read.withUiPrefs(shown))
            ?: return written to false
        return kept to (kept != written)
    }

    /**
     * The settings with anything still on its way to the database written first. What differs only
     * because storage changed meanwhile is shown, not written over.
     */
    private suspend fun settingsNow() = writeLock.withLock {
        val current = data.settings.current()
        if (prefsProfile != current.sync.activeProfile) return@withLock current
        val shown = prefsState.value
        var others = false
        val next = shownOver(current, shown).let { (s, kept) -> others = kept; s }
        val written = if (next == current) current else data.settings.update { now -> shownOver(now, shown).let { (s, kept) -> others = kept; s } }
        storedPrefs = shown
        storedSettings = written
        ctx.settings.value = written
        if (others) reloadLocked()
        ctx.settings.value
    }

    /** Changes the settings (Fuse Sync bringing in a profile's) and has the interface follow at once. */
    private suspend fun writeSettings(transform: (io.github.matiyaaa.fuse.data.settings.AppSettings) -> io.github.matiyaaa.fuse.data.settings.AppSettings) = writeLock.withLock {
        val before = data.settings.current()
        val shown = prefsState.value
        var wroteShown = false
        var others = false
        val after = data.settings.update {
            if (prefsProfile == it.sync.activeProfile) { wroteShown = true; transform(shownOver(it, shown).let { (s, kept) -> others = kept; s }) } else transform(it)
        }
        // What was written is what is stored now, before the reload: a choice made since is newer
        // than it, and is carried over rather than replaced by it.
        if (wroteShown) { storedPrefs = shown; storedSettings = after }
        if (after != before || others) reloadLocked()
    }

    /** Commit pending UI edits before comparing host revisions, then reload without restamping them. */
    private suspend fun writeProfileSettings(profile: String, values: Map<String, io.github.matiyaaa.fuse.data.settings.ProfileSettingValue>) = writeLock.withLock {
        val before = data.settings.current()
        var others = false
        // A profile switch changes the persisted owner before the old UI preferences are reloaded.
        // Those old preferences must not become a local edit belonging to the next person.
        if (prefsProfile == before.sync.activeProfile) {
            val shown = prefsState.value
            val next = shownOver(before, shown).let { (s, kept) -> others = kept; s }
            storedSettings = if (next == before) before else data.settings.update { now -> shownOver(now, shown).let { (s, kept) -> others = kept; s } }
            storedPrefs = shown
        }
        val after = data.settings.mergeProfileValues(profile, values)
        if (after != before || others || prefsState.value != after.toUiPrefs(globalScoped(ctx))) reloadLocked()
    }

    override val media get() = mediaOps

    override val themes = object : ThemeOps {
        override suspend fun fetch(url: String): Result<String> {
            val link = ThemeLinks.normalize(url) ?: return Result.failure(IllegalArgumentException("Only https links can be added."))
            return try {
                withTimeout(FETCH_TIMEOUT_MS) {
                    val response = ctx.services.http.get(link)
                    if (!response.status.isSuccess()) return@withTimeout Result.failure(IllegalStateException("The link answered ${response.status.value}. Check that it points at the theme file."))
                    val bytes = response.bodyAsChannel().readRemaining(ThemeCodec.MAX_BYTES + 1L).readByteArray()
                    if (bytes.size > ThemeCodec.MAX_BYTES) Result.failure(IllegalStateException("This theme is larger than 64 KB.")) else Result.success(bytes.decodeToString())
                }
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) Result.failure(IllegalStateException("The link took too long to answer.")) else throw e
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Couldn't reach that link. Check your connection."))
            }
        }

        override suspend fun readFile(path: String): Result<String> {
            val text = ctx.services.fs.readText(path, ThemeCodec.MAX_BYTES + 1)
                ?: return Result.failure(IllegalStateException("Couldn't read that file."))
            return if (text.encodeToByteArray().size > ThemeCodec.MAX_BYTES) Result.failure(IllegalStateException("This theme is larger than 64 KB.")) else Result.success(text)
        }

        override fun parse(text: String): ThemeCodec.Result = ThemeCodec.parse(text, ThemePresets::find, ThemePresets.Fuse)

        override suspend fun add(spec: ThemeSpec, json: String, source: String?, apply: Boolean) {
            writeLock.withLock {
                val settings = data.settings.update { s ->
                    val kept = s.appearance.customThemes.filterNot { it.id == spec.id }
                    val all = (kept + StoredTheme(spec.id, json.trim(), source, ctx.now())).takeLast(MAX_THEMES)
                    s.copy(appearance = s.appearance.copy(customThemes = all))
                }
                ctx.settings.value = settings
                prefsState.update { it.copy(customThemes = settings.appearance.customThemes.mapNotNull { t -> t.spec() }) }
            }
            if (apply) updatePrefs { it.withTheme(it.customThemes.firstOrNull { t -> t.id == spec.id } ?: spec) }
        }

        override suspend fun remove(id: String) {
            if (prefsState.value.themeId == id) updatePrefs { it.withTheme(ThemePresets.Fuse) }
            writeLock.withLock {
                val settings = data.settings.update { s ->
                    s.copy(
                        appearance = s.appearance.copy(
                            customThemes = s.appearance.customThemes.filterNot { it.id == id },
                            themeId = if (s.appearance.themeId == id) ThemePresets.Fuse.id else s.appearance.themeId,
                        ),
                    )
                }
                ctx.settings.value = settings
                prefsState.update { it.copy(customThemes = settings.appearance.customThemes.mapNotNull { t -> t.spec() }) }
            }
        }

        override fun export(spec: ThemeSpec): String =
            ctx.settings.value.appearance.customThemes.firstOrNull { it.id == spec.id }?.json ?: ThemeCodec.encode(spec)
    }

    /**
     * The games (by every household key each is known by) whose saves another way than Fuse One
     * moves here under [provider]: of the ways a game has here, the first in the order the person
     * chose ([io.github.matiyaaa.fuse.sync.SaveOwners]).
     */
    private suspend fun rommMovesSaves(provider: io.github.matiyaaa.fuse.data.settings.SaveProvider): Set<String> {
        val fuseOne = io.github.matiyaaa.fuse.sync.SaveWay.FUSE_ONE
        val romm = io.github.matiyaaa.fuse.sync.SaveWay.ROMM
        val order = if (provider == io.github.matiyaaa.fuse.data.settings.SaveProvider.ROMM_THEN_FUSE_ONE) listOf(romm.id) else emptyList()
        val keys = profileData.householdIds()
        return ctx.data.games.matchRows().filter { r ->
            val here = setOfNotNull(fuseOne.takeIf { provider.fuseOne }, romm.takeIf { provider.romm && r.rommRomId != null })
            io.github.matiyaaa.fuse.sync.SaveOwners.owner(here, order).let { it != null && it != fuseOne }
        }.flatMapTo(HashSet()) { keys[it.id.value].orEmpty() }
    }

    override suspend fun setSaveProvider(provider: io.github.matiyaaa.fuse.data.settings.SaveProvider) {
        val Syncthing = io.github.matiyaaa.fuse.data.settings.SaveProvider.SYNCTHING
        writeSettings { io.github.matiyaaa.fuse.data.settings.SaveProvider.choose(it, provider) }
        // Syncthing's own service follows its switch: started (found) or stopped.
        syncthing?.let { st -> runCatching { st.setEnabled(provider == Syncthing) } }
        sync.service?.changed()
    }

    override suspend fun bundledTrack(id: String): String? {
        val track = BundledMusic.byId(id) ?: return null
        clearOldMusic()
        val path = BundledMusic.cachePath(id)
        if (track.inside) return ctx.services.cacheFile(path) { Res.readBytes(BundledMusic.resource(id)) }
        // One fetch at a time: the menu asking for the song "Download all" is fetching waits for it.
        return musicFetch.withLock {
            try {
                ctx.services.cacheFile(path) { fetchSong(track) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun musicHere(id: String): Boolean {
        val track = BundledMusic.byId(id) ?: return false
        if (track.inside) return true
        val file = io.github.matiyaaa.fuse.library.FsPath.join(ctx.services.cacheDir, BundledMusic.cachePath(id))
        return runCatching { ctx.services.fs.stat(file) }.getOrNull()?.let { !it.isDirectory && it.sizeBytes == track.bytes } == true
    }

    override suspend fun downloadAllMusic(onProgress: (done: Int, total: Int) -> Unit): Int {
        val wanted = BundledMusic.remote
        var failed = 0
        for ((i, t) in wanted.withIndex()) {
            onProgress(i, wanted.size)
            if (!musicHere(t.id) && bundledTrack(t.id) == null) failed++
        }
        onProgress(wanted.size, wanted.size)
        return failed
    }

    override suspend fun removeDownloadedMusic() {
        musicFetch.withLock {
            for (t in BundledMusic.remote) {
                runCatching { ctx.services.fs.delete(io.github.matiyaaa.fuse.library.FsPath.join(ctx.services.cacheDir, BundledMusic.cachePath(t.id))) }
            }
        }
    }

    private val musicFetch = kotlinx.coroutines.sync.Mutex()
    private var oldMusicCleared = false

    /** A song fetched from where Fuse publishes them, checked against its size and sha256 before it is kept. */
    private suspend fun fetchSong(track: io.github.matiyaaa.fuse.ui.shell.music.MusicTrack): ByteArray {
        for (url in BundledMusic.remoteUrls(track.id)) {
            val bytes = runCatching {
                val resp = ctx.services.http.get(url)
                if (!resp.status.isSuccess()) null else resp.bodyAsBytes()
            }.getOrNull() ?: continue
            if (bytes.size.toLong() == track.bytes && okio.ByteString.Companion.run { bytes.toByteString() }.sha256().hex() == track.sha256) return bytes
        }
        throw java.io.IOException("${track.id} couldn't be fetched")
    }

    /** The MP3 copies 0.4.2.14 and earlier unpacked: replaced by the Opus songs, removed once. */
    private suspend fun clearOldMusic() {
        if (oldMusicCleared) return
        oldMusicCleared = true
        runCatching { ctx.services.fs.delete(io.github.matiyaaa.fuse.library.FsPath.join(ctx.services.cacheDir, BundledMusic.OLD_CACHE)) }
    }

    override fun resetHome(reset: (io.github.matiyaaa.fuse.model.HomeLayoutConfig) -> io.github.matiyaaa.fuse.model.HomeLayoutConfig) {
        ctx.scope.launch {
            val shared = sync.inUse
            writeSettings { s ->
                val leaves = shared && s.sync.homeScope == LibraryProfileData.HOME_PROFILE
                s.copy(
                    home = s.home.copy(layout = reset(s.home.layout), beforeReset = s.home.layout, resetLeftProfile = leaves),
                    sync = if (leaves) s.sync.copy(homeScope = LibraryProfileData.HOME_DEVICE) else s.sync,
                )
            }
            sync.service?.changed()
        }
    }

    override fun undoHomeReset() {
        ctx.scope.launch {
            writeSettings { s ->
                val before = s.home.beforeReset ?: return@writeSettings s
                val rejoin = s.home.resetLeftProfile && s.sync.homeScope == LibraryProfileData.HOME_DEVICE
                s.copy(
                    home = s.home.copy(layout = before, beforeReset = null, resetLeftProfile = false),
                    // The profile's Home comes back as it is now (changed elsewhere since, it stays changed).
                    sync = if (rejoin) s.sync.copy(homeScope = LibraryProfileData.HOME_REJOIN, deviceHome = null) else s.sync,
                )
            }
            sync.service?.changed()
        }
    }

    override fun updatePrefs(transform: (UiPrefs) -> UiPrefs) {
        // Changed in one step: Fuse Sync's settings arriving meanwhile (or another choice) are kept,
        // never written over with what was there before.
        var changed = false
        val after = prefsState.updateAndGet { before ->
            // Fuse Sync's settings change only through the sync store.
            transform(before).copy(sync = before.sync).also { changed = it != before }
        }
        if (!changed) return
        ctx.systemOrder.value = after.systemOrder
        writes.trySend(after)
        ctx.userChanged()
    }

    private suspend fun persist(prefs: UiPrefs) = writeLock.withLock {
        // A newer value replaced this one (a later change, or a restore reloading everything): it is written instead.
        if (prefs != prefsState.value) return@withLock
        if (prefsProfile != data.settings.current().sync.activeProfile) { reloadLocked(); return@withLock }
        // Someone else's change since this Fuse last read stays (see shownOver), and is shown.
        var others = false
        val written = data.settings.update { now -> shownOver(now, prefs).let { (s, kept) -> others = kept; s } }
        ctx.settings.value = written
        storedPrefs = prefs
        storedSettings = written
        if (others) reloadLocked()
        val scoped = data.scopedSettings
        val global = ScopeRef.Global
        if (scoped.resolve(ScopedSettings.Layout, null, null).value != prefs.defaultLayout) scoped.set(ScopedSettings.Layout, global, prefs.defaultLayout)
        if (scoped.resolve(ScopedSettings.ShowHero, null, null).value != prefs.showHero) scoped.set(ScopedSettings.ShowHero, global, prefs.showHero)
        if (scoped.resolve(ScopedSettings.ShowLogo, null, null).value != prefs.showLogo) scoped.set(ScopedSettings.ShowLogo, global, prefs.showLogo)
    }

    /** Re-reads preferences after a global scoped setting changed outside [updatePrefs]. */
    private suspend fun reloadPrefs() = writeLock.withLock { reloadLocked() }

    /**
     * Shows the settings as stored, keeping any choice the interface shows that isn't saved yet: it
     * is the newest thing the person did, so it is written over what came in (a sync round bringing
     * the profile's values, a setting changed elsewhere) instead of being thrown away. That holds
     * across a profile switch the interface is catching up with too: whatever the outgoing person
     * had chosen was saved as theirs when the switch began (it reads the settings through
     * [settingsNow]), so a choice not saved yet was made after, by the new person, and stays theirs.
     */
    private suspend fun reloadLocked() {
        val shown = prefsState.value
        val base = storedPrefs
        var settings = data.settings.current()
        if (base != null && shown != base && io.github.matiyaaa.fuse.data.settings.UnsavedChoices.carry(settings, settings.withUiPrefs(base), settings.withUiPrefs(shown)) != null) {
            settings = data.settings.update { now -> io.github.matiyaaa.fuse.data.settings.UnsavedChoices.carry(now, now.withUiPrefs(base), now.withUiPrefs(shown)) ?: now }
            ctx.userChanged()
        }
        ctx.settings.value = settings
        prefsProfile = settings.sync.activeProfile
        val scoped = globalScoped(ctx)
        val loaded = settings.toUiPrefs(scoped)
        storedPrefs = loaded
        storedSettings = settings
        // A choice can be made while this runs ([updatePrefs] doesn't wait for it): it is newer than
        // anything read here, so it stays, laid over what came in, and is written.
        while (true) {
            val now = prefsState.value
            if (now == shown) {
                if (prefsState.compareAndSet(now, loaded)) break else continue
            }
            val merged = io.github.matiyaaa.fuse.data.settings.UnsavedChoices.carry(settings, settings.withUiPrefs(shown), settings.withUiPrefs(now))?.toUiPrefs(scoped) ?: loaded
            if (prefsState.compareAndSet(now, merged)) {
                if (merged != loaded) writes.trySend(merged)
                break
            }
        }
        ctx.systemOrder.value = prefsState.value.systemOrder
    }

    /**
     * Clean names became the default in 0.0.2. Games already in the library get cleaned names once,
     * and again whenever the cleaning rules improve ([CLEAN_RULES]; 0.0.4 learned list numbers and
     * codes in front of names). Recorded like any cleanup so Settings, Library can undo it; custom
     * titles are never touched.
     */
    private suspend fun cleanExistingNamesOnce() {
        val library = ctx.settings.value.library
        if (!library.cleanDisplayNames) return
        if (library.cleanedExistingNames && library.cleanedNamesRules >= CLEAN_RULES) return
        runCatching { data.titleCleanup.apply(DisplayNameCleaner::clean) }
        writeLock.withLock {
            ctx.settings.value = data.settings.update {
                it.copy(library = it.library.copy(cleanedExistingNames = true, cleanedNamesRules = CLEAN_RULES))
            }
        }
    }

    /** Jellyfin follows its switch and addresses; off, it does nothing at all. Safe mode leaves it off. */
    private fun configureJellyfin(j: io.github.matiyaaa.fuse.data.settings.JellyfinSettings) {
        jellyfin.configure(
            enabled = j.enabled && !safe,
            connection = io.github.matiyaaa.fuse.jellyfin.JellyfinConnection(
                runCatching { io.github.matiyaaa.fuse.jellyfin.ConnectionMode.valueOf(j.mode) }.getOrDefault(io.github.matiyaaa.fuse.jellyfin.ConnectionMode.AUTO),
                j.localAddress,
                j.remoteAddress,
            ),
        )
    }

    private fun start() {
        if (forRun) return startForRun()
        io.github.matiyaaa.fuse.ui.shell.platform.JellyfinImages.service = jellyfin
        // Downloads, and RomM feeding the library through them.
        transfers.start()
        romm.start()
        reach.start()
        offlineMedia.start()
        streaming.start()
        ctx.scope.launch { ctx.playing.collect { transfers.conditions(playing = it != null) } }
        // The achievements unlocked in the game being played, for the screens that show them.
        achievements.followPlay()
        // Search answers its first question as fast as the rest: its index is ready beforehand.
        library.warmSearch()
        // Fuse Engine's Performance Governor: decorative motion follows what Fuse is doing.
        ctx.governor.start()
        ctx.scope.launch {
            ctx.governor.state.collect { io.github.matiyaaa.fuse.ui.fuseline.FramePacing.decorationRate = io.github.matiyaaa.fuse.ui.shell.store.EnginePolicy.of(it).motion }
        }
        // The platform says when the device sleeps (Android: the screen off with nothing to do):
        // optional work holds until it wakes, and starts again after the governor's settling time.
        ctx.scope.launch {
            // Started awake, nothing is said (waking would hold optional work for the settling time);
            // started asleep (Android starting Fuse in the background), it is held from the start.
            if (!ctx.services.awake.value) ctx.governor.onSuspend()
            ctx.services.awake.drop(1).distinctUntilChanged().collect { awake -> if (awake) ctx.governor.woke() else ctx.governor.onSuspend() }
        }
        // One RomM integration at a time: turning Cartridge on in Fuse turns RomM off, and the
        // other way round. Turning one off keeps everything it was set up with.
        ctx.scope.launch {
            var before = prefsState.value.cartridgeEnabled to prefsState.value.romm.enabled
            prefsState.map { it.cartridgeEnabled to it.romm.enabled }.distinctUntilChanged().collect { now ->
                val (cart, romm) = now
                if (cart && romm) {
                    if (!before.first) updatePrefs { it.copy(romm = it.romm.copy(enabled = false)) }
                    else if (!before.second) updatePrefs { it.copy(cartridgeEnabled = false) }
                }
                before = prefsState.value.cartridgeEnabled to prefsState.value.romm.enabled
            }
        }
        // Fuse Sync: saves around games, and what the person changes goes up soon.
        sync.service?.let { svc ->
            library.sync = SyncLaunch(svc, sync.port)
            sync.queries = { id -> library.saveQueryFor(id) }
            // A save made on another device comes into place here by itself, for games this library has.
            svc.saveQueries { household -> profileData.gameFor(household)?.let { library.saveQueryFor(io.github.matiyaaa.fuse.model.GameId(it)) } }
            sync.samples = { library.saveSamples() }
            // A game new to this library (downloaded, sent over, found by a scan) gets its save from
            // the host at once, not first at its launch. The library as it first loads isn't "new".
            ctx.scope.launch {
                var known: Set<io.github.matiyaaa.fuse.model.GameId>? = null
                // The ids alone: as cards, every picture filled anywhere in the library mapped every game again.
                library.gameIds(GameQuery(includeHidden = true)).map { it.toSet() }.distinctUntilChanged().collect { ids ->
                    val before = known
                    known = ids
                    if (before == null) return@collect
                    val added = (ids - before).take(MAX_ARRIVALS)
                    if (added.isEmpty()) return@collect
                    val keys = added.mapNotNull { id -> runCatching { library.saveQueryFor(id)?.game?.id }.getOrNull() }
                    svc.gamesArrived(keys)
                }
            }
            ctx.onUserChange = { if (sync.config.value.enabled) svc.changed() }
            // Whose saves move on this device: its own choice, followed as it changes.
            ctx.scope.launch {
                sync.config.map { it.noPullProfiles.toSet() to it.noSyncProfiles.toSet() }.distinctUntilChanged()
                    .collect { (pullOff, off) -> svc.saveChoices(pullOff, off) }
            }
            // RomM, Then Fuse One: Fuse One leaves the saves of games from RomM to RomM, so one way moves each game's.
            ctx.scope.launch {
                ctx.settings.map { io.github.matiyaaa.fuse.data.settings.SaveProvider.of(it) }.distinctUntilChanged().collectLatest { provider ->
                    if (provider != io.github.matiyaaa.fuse.data.settings.SaveProvider.ROMM_THEN_FUSE_ONE) return@collectLatest svc.savesMovedElsewhere(emptySet())
                    var first = true
                    // Followed as games join, leave or are matched to RomM; a moment's wait lets a scan or a RomM sync settle.
                    ctx.data.games.observeAll(includeHidden = true).collectLatest {
                        if (!first) delay(1_000)
                        first = false
                        svc.savesMovedElsewhere(rommMovesSaves(provider))
                    }
                }
            }
            val setupDone = ctx.settings.value.onboarding.completed
            // Who has had their welcome here. On a device that was already using profiles when it
            // updated, everyone playing here has; anyone new to this device gets the grand one.
            if (!sync.config.value.welcomedSeeded) {
                val wasUsing = setupDone && sync.config.value.enabled
                ctx.scope.launch {
                    val known = if (wasUsing) kotlinx.coroutines.withTimeoutOrNull(5_000) { svc.profiles.first { it.isNotEmpty() } }.orEmpty().map { it.id } else emptyList()
                    sync.configure { it.copy(welcomedSeeded = true, welcomedProfiles = (it.welcomedProfiles + known).distinct()) }
                }
            }
            // RomM and Jellyfin for the household: shared from here, and brought in where this device has none.
            val household = HouseholdSignIns(
                ctx.scope, svc, sync.config, sync::configure, { setupDone },
                romm = object : HouseholdSignIns.Romm {
                    override suspend fun mine() = romm.forHousehold()?.takeIf { it.first.enabled }?.let { (s, credential) ->
                        io.github.matiyaaa.fuse.sync.ServiceAddress(s.localAddress, s.remoteAddress, s.mode) to credential
                    }
                    override suspend fun take(address: io.github.matiyaaa.fuse.sync.ServiceAddress, credential: String?) =
                        romm.fromHousehold(address.local, address.remote, address.mode, credential)
                },
                jellyfin = jellyfin,
                jellyfinPlace = object : HouseholdSignIns.JellyfinPlace {
                    override fun mine() = prefsState.value.jellyfin.takeIf { it.enabled && (it.localAddress.isNotBlank() || it.remoteAddress.isNotBlank()) }
                        ?.let { io.github.matiyaaa.fuse.sync.ServiceAddress(it.localAddress, it.remoteAddress, it.mode) }
                    override suspend fun take(address: io.github.matiyaaa.fuse.sync.ServiceAddress): Boolean {
                        val j = prefsState.value.jellyfin
                        if (j.localAddress.isNotBlank() || j.remoteAddress.isNotBlank()) return false
                        if (address.local.isBlank() && address.remote.isBlank()) return false
                        updatePrefs { it.copy(jellyfin = it.jellyfin.copy(enabled = true, localAddress = address.local, remoteAddress = address.remote, mode = address.mode)) }
                        // In place now, so the person's own sign-in can follow straight away.
                        configureJellyfin(prefsState.value.jellyfin)
                        return true
                    }
                },
                keys = object : HouseholdSignIns.Keys {
                    override suspend fun mine() = SecretKeys.all.mapNotNull { k -> credentials.get(k)?.let { k to it } }.toMap()
                    override suspend fun take(name: String, value: String) {
                        if (name !in SecretKeys.all || name in sync.config.value.keysKeptOff) return
                        credentials.put(name, value)
                        if (name == SecretKeys.RA_USERNAME || name == SecretKeys.RA_API_KEY) {
                            achievements.load()
                            if (achievements.configured.value) achievements.refresh(force = true)
                        }
                    }
                    override fun keptOff() = sync.config.value.keysKeptOff.toSet()
                    override suspend fun agreed() = SecretKeys.all.mapNotNull { k -> credentials.get(AGREED + k)?.let { k to it } }.toMap()
                    override suspend fun agree(values: Map<String, String>) {
                        for ((k, v) in values) if (k in SecretKeys.all) runCatching { ctx.services.secrets.put(AGREED + k, v) }
                    }
                },
                ask = sync.ask,
            )
            sync.signIns = household
            household.start(
                kotlinx.coroutines.flow.combine(
                    ctx.settings.map { Triple(it.romm.localAddress + "|" + it.romm.remoteAddress, it.romm.enabled, it.romm.account) },
                    prefsState.map { Triple(it.jellyfin.localAddress + "|" + it.jellyfin.remoteAddress, it.jellyfin.enabled, it.jellyfin.mode) },
                    jellyfin.state.map { it.account?.userId },
                    credentials.revision,
                ) { a, b, c, d -> listOf(a, b, c, d) }.distinctUntilChanged(),
            )
        }
        // The interface follows Fuse Sync's settings as stored (the service writes some itself).
        ctx.scope.launch {
            sync.config.collect { c ->
                if (prefsProfile != c.activeProfile) {
                    writeLock.withLock { reloadLocked() }
                    // Each profile has its own RetroAchievements account (or the device's).
                    achievements.profileChanged()
                } else if (prefsState.value.sync != c) {
                    // In one step, so a choice made at the same moment stays.
                    prefsState.update { it.copy(sync = c) }
                    writes.trySend(prefsState.value)
                }
            }
        }
        // Jellyfin follows its switch and addresses; off, it does nothing at all. Safe mode leaves it off.
        ctx.scope.launch { prefsState.map { it.jellyfin }.distinctUntilChanged().collect(::configureJellyfin) }
        // Home's Jellyfin widgets: asked for only while Jellyfin is on, signed in and one of them is
        // on Home; again when something was played or marked, and every few minutes.
        ctx.scope.launch {
            val kinds = io.github.matiyaaa.fuse.model.WidgetKind.entries.filter { it.name.startsWith("JELLYFIN_") }.toSet()
            kotlinx.coroutines.flow.combine(
                // On any of Home's pages, not only the first.
                prefsState.map { p ->
                    p.jellyfin.enabled && (
                        p.home.widgets.any { it.visible && it.kind in kinds } ||
                            p.home.boardWidgets().any { it.kind in kinds } ||
                            p.home.pages.any { page -> page.widgets.any { it.kind in kinds } }
                        )
                }.distinctUntilChanged(),
                jellyfin.state.map { it.account != null && !it.authRequired }.distinctUntilChanged(),
                jellyfin.revision,
            ) { wanted, signedIn, rev -> Triple(wanted, signedIn, rev) }.collectLatest { (wanted, signedIn, _) ->
                if (!wanted || !signedIn) {
                    mediaFeed.value = io.github.matiyaaa.fuse.jellyfin.MediaFeed()
                    return@collectLatest
                }
                while (true) {
                    // Asleep, or under a game, Home isn't looked at: the feed is asked for again after.
                    ctx.rounds.first { it }
                    val got = runCatching { jellyfin.widgetFeed() }.onSuccess { mediaFeed.value = it }.getOrNull()
                    // Nothing came (the server not answering yet): soon again, not in five minutes.
                    delay(if (got == null || got.isEmpty) MEDIA_FEED_RETRY_MS else MEDIA_FEED_EVERY_MS)
                }
            }
        }
        engine.start()
        // A computer's Steam games kept as shortcut files move onto Steam's own libraries.
        ctx.scope.launch {
            engine.scan.first { it.phase == ScanPhase.DONE }
            runCatching { engine.moveSteamShortcutsToLibraries() }
        }
        health.start()
        appStoreOps?.start()
        desktopStoreOps?.start()
        ctx.scope.launch {
            // Each send asks for a write; what is written is what Fuse shows then, the newest. (Writing
            // the value sent could write an older one after a newer one's send was folded into it.)
            for (asked in writes) {
                // A failed write must not stop later ones; retry once, then keep the in-memory value.
                val ok = runCatching { persist(prefsState.value) }.isSuccess
                if (!ok) {
                    delay(250)
                    runCatching { persist(prefsState.value) }
                }
            }
        }
        ctx.scope.launch {
            library.recoverSession()
            cleanExistingNamesOnce()
            credentials.load()
            emulators.detectNow()
            collections.start()
            apps.start()
            // Android: the ports are read now, so Apps knows an installed recomp or port as one.
            if (ctx.services.portFiles == null && ctx.services.packages != null) ports
            if (!safe) startAutomatic()
        }
    }

    /**
     * Fast Start: only what starting one game needs (the person's saves put in place first and sent
     * after, the session counted, a session a crash left open closed), none of what Fuse does while
     * open: no scan, download, art, household list, update check or anything else on the network
     * but Fuse One's own rounds for the saves.
     */
    private fun startForRun() {
        sync.service?.let { svc ->
            library.sync = SyncLaunch(svc, sync.port)
            sync.queries = { id -> library.saveQueryFor(id) }
            ctx.onUserChange = { if (sync.config.value.enabled) svc.changed() }
        }
        ctx.scope.launch { for (asked in writes) runCatching { persist(prefsState.value) } }
        ctx.scope.launch { library.recoverSession() }
    }

    /** Started for one game with no window ([startForRun]). */
    private var forRun = false

    /** Set in safe mode until the user leaves it: nothing runs by itself meanwhile. */
    private var safe = false
    private var automaticStarted = false

    override fun resumeAutomaticWork() {
        if (!safe) return
        safe = false
        ctx.scope.launch { startAutomatic() }
    }

    /**
     * What Fuse does by itself once it is up: system art, achievements, Cartridge, a quick scan,
     * cache upkeep, the update check, and filling new games' art after scans. Safe mode holds it back.
     */
    private suspend fun startAutomatic() {
        if (automaticStarted) return
        automaticStarted = true
        // Fuseline 4.2: none of this feeds the first screen, so it waits for it to settle.
        io.github.matiyaaa.fuse.ui.designsystem.effects.StartupGate.settled()
        runCatching { data.media.pruneChanges() }
        // Games a scan found (downloads included) are identified and filled first, once Cartridge's
        // RomM details, read right after the scan, have had a moment to land.
        ctx.scope.launch {
            engine.added.collect { ids ->
                delay(NEW_GAMES_DELAY_MS)
                mediaOps.fillNew(ids)
            }
        }
        // The rest of the library finds missing art by itself after scans, and each disc's own serial
        // is read once (Fuse Identity), so every part of Fuse knows the game by the game itself.
        ctx.scope.launch {
            engine.scan.map { it.phase }.distinctUntilChanged().collect { phase ->
                if (phase == ScanPhase.DONE) {
                    delay(AUTO_FILL_DELAY_MS)
                    mediaOps.autoFill()
                    runCatching { ctx.work.run(BackgroundWork.Job.IDENTIFY) { library.identifyLibrary() } }
                }
            }
        }
        mediaOps.startSystemArt()
        achievements.load()
        cartridge.start()
        appStoreOps?.startAutomatic()
        desktopStoreOps?.startAutomatic()
        if (data.sources.all().isNotEmpty()) engine.rescan(ScanScope.QUICK)
        achievements.refresh(force = false)
        // Only what ran out long ago goes: an answer past its time is still what's shown offline
        // (RetroAchievements' progress runs out in minutes, so purging at once left nothing to show
        // after a start without a connection).
        data.cache.purgeExpired(ctx.now() - STALE_KEPT_MS)
        runCatching { updates.checkIfDue() }
    }

    companion object {
        /** New games at once whose saves are looked for (a whole folder added stays a few calls). */
        const val MAX_ARRIVALS = 200

        /** How long an answer is kept past its time, for showing offline, before it is purged. */
        const val STALE_KEPT_MS = 60L * 24 * 60 * 60 * 1000

        /** Version of [DisplayNameCleaner]'s rules; existing names are cleaned again when it grows. */
        const val CLEAN_RULES = 2
        const val MAX_THEMES = 32
        const val FETCH_TIMEOUT_MS = 10_000L

        /** How often Home's Jellyfin widgets ask again while nothing changed. */
        const val MEDIA_FEED_EVERY_MS = 5 * 60_000L
        const val MEDIA_FEED_RETRY_MS = 20_000L

        /** How long after a scan (or a new key) the automatic fill starts. */
        const val AUTO_FILL_DELAY_MS = 5_000L

        /** How long new games wait for Cartridge's RomM details before they are filled. */
        const val NEW_GAMES_DELAY_MS = 1_500L

        suspend fun create(services: FuseServices, scope: CoroutineScope, safeMode: Boolean = false, forRun: Boolean = false): DefaultFuseStore {
            val settings = services.data.settings.current()
            val ctx = StoreContext(services, scope, settings)
            // Jellyfin knows this device by an id of its own that stays the same across runs.
            val deviceId = services.secrets.get(io.github.matiyaaa.fuse.jellyfin.JellyfinService.DEVICE_ID)
                ?: kotlin.random.Random.nextBytes(12).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                    .also { services.secrets.put(io.github.matiyaaa.fuse.jellyfin.JellyfinService.DEVICE_ID, it) }
            return DefaultFuseStore(ctx, settings.toUiPrefs(globalScoped(ctx)), deviceId).also {
                it.safe = safeMode
                it.forRun = forRun
                it.start()
            }
        }

        private suspend fun globalScoped(ctx: StoreContext): GlobalScoped {
            val s = ctx.data.scopedSettings
            return GlobalScoped(
                layout = s.resolve(ScopedSettings.Layout, null, null).value,
                showHero = s.resolve(ScopedSettings.ShowHero, null, null).value,
                showLogo = s.resolve(ScopedSettings.ShowLogo, null, null).value,
            )
        }
    }
}

/** Where the secret store keeps each key's value as this device and the household last had it alike. */
private const val AGREED = "household.agreed."
