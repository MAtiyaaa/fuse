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
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.designsystem.res.Res
import io.github.matiyaaa.fuse.ui.shell.music.BundledMusic
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.ThemeOps
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
) : FuseStore {
    private val data = ctx.data
    private val prefsState = MutableStateFlow(initialPrefs)
    override val prefs: StateFlow<UiPrefs> = prefsState
    private val writes = Channel<UiPrefs>(Channel.CONFLATED)
    private val writeLock = Mutex()

    override val emulators = DefaultEmulatorOps(ctx)
    private val engine = LibraryEngine(ctx)
    override val sources = engine
    override val collections = DefaultCollectionOps(ctx)
    override val apps = DefaultAppOps(ctx)
    private val mediaOps: DefaultMediaOps
    override val credentials = DefaultCredentialOps(ctx) { key ->
        if (key != SecretKeys.RA_USERNAME && key != SecretKeys.RA_API_KEY) mediaOps.invalidate()
    }
    override val achievements = DefaultAchievementOps(ctx, credentials)
    override val cartridge = DefaultCartridgeOps(ctx, engine)
    override val updates = DefaultUpdateOps(ctx)
    override val storage = DefaultStorageOps(ctx, engine.drives)
    override val settings = DefaultScopedSettingsOps(ctx) { reloadPrefs() }
    override val library: DefaultLibraryOps
    override val health: DefaultHealthOps

    init {
        mediaOps = DefaultMediaOps(ctx, credentials)
        // Apps that became games and games added by hand are identified and filled straight away.
        val findArt: (List<io.github.matiyaaa.fuse.model.GameId>) -> Unit = { ids -> mediaOps.fillNew(ids) }
        apps.onGamesAdded = findArt
        library = DefaultLibraryOps(ctx, engine, emulators, collections, apps, achievements, cartridge) { enabled ->
            updatePrefs { it.copy(cleanDisplayNames = enabled) }
        }
        library.onGamesAdded = findArt
        health = DefaultHealthOps(ctx, engine, library, mediaOps, updates, { credentials.stored.value }, { cartridge.status.value })
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
                prefsState.value = prefsState.value.copy(customThemes = settings.appearance.customThemes.mapNotNull { it.spec() })
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
                prefsState.value = prefsState.value.copy(customThemes = settings.appearance.customThemes.mapNotNull { it.spec() })
            }
        }

        override fun export(spec: ThemeSpec): String =
            ctx.settings.value.appearance.customThemes.firstOrNull { it.id == spec.id }?.json ?: ThemeCodec.encode(spec)
    }

    override suspend fun bundledTrack(id: String): String? {
        if (BundledMusic.byId(id) == null) return null
        return ctx.services.cacheFile(BundledMusic.cachePath(id)) { Res.readBytes(BundledMusic.resource(id)) }
    }

    override fun updatePrefs(transform: (UiPrefs) -> UiPrefs) {
        val before = prefsState.value
        val after = transform(before)
        if (after == before) return
        prefsState.value = after
        ctx.systemOrder.value = after.systemOrder
        writes.trySend(after)
    }

    private suspend fun persist(prefs: UiPrefs) = writeLock.withLock {
        ctx.settings.value = data.settings.update { it.withUiPrefs(prefs) }
        val scoped = data.scopedSettings
        val global = ScopeRef.Global
        if (scoped.resolve(ScopedSettings.Layout, null, null).value != prefs.defaultLayout) scoped.set(ScopedSettings.Layout, global, prefs.defaultLayout)
        if (scoped.resolve(ScopedSettings.ShowHero, null, null).value != prefs.showHero) scoped.set(ScopedSettings.ShowHero, global, prefs.showHero)
        if (scoped.resolve(ScopedSettings.ShowLogo, null, null).value != prefs.showLogo) scoped.set(ScopedSettings.ShowLogo, global, prefs.showLogo)
    }

    /** Re-reads preferences after a global scoped setting changed outside [updatePrefs]. */
    private suspend fun reloadPrefs() = writeLock.withLock {
        val settings = data.settings.current()
        ctx.settings.value = settings
        prefsState.value = settings.toUiPrefs(globalScoped(ctx))
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

    private fun start() {
        engine.start()
        health.start()
        ctx.scope.launch {
            for (next in writes) {
                // A failed write must not stop later ones; retry once, then keep the in-memory value.
                val ok = runCatching { persist(next) }.isSuccess
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
            if (!safe) startAutomatic()
        }
    }

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
        // Games a scan found (downloads included) are identified and filled first, once Cartridge's
        // RomM details, read right after the scan, have had a moment to land.
        ctx.scope.launch {
            engine.added.collect { ids ->
                delay(NEW_GAMES_DELAY_MS)
                mediaOps.fillNew(ids)
            }
        }
        // The rest of the library finds missing art by itself after scans.
        ctx.scope.launch {
            engine.scan.map { it.phase }.distinctUntilChanged().collect { phase ->
                if (phase == ScanPhase.DONE) {
                    delay(AUTO_FILL_DELAY_MS)
                    mediaOps.autoFill()
                }
            }
        }
        mediaOps.startSystemArt()
        achievements.load()
        cartridge.start()
        if (data.sources.all().isNotEmpty()) engine.rescan(ScanScope.QUICK)
        achievements.refresh(force = false)
        data.cache.purgeExpired(ctx.now())
        runCatching { updates.checkIfDue() }
    }

    companion object {
        /** Version of [DisplayNameCleaner]'s rules; existing names are cleaned again when it grows. */
        const val CLEAN_RULES = 2
        const val MAX_THEMES = 32
        const val FETCH_TIMEOUT_MS = 10_000L

        /** How long after a scan (or a new key) the automatic fill starts. */
        const val AUTO_FILL_DELAY_MS = 5_000L

        /** How long new games wait for Cartridge's RomM details before they are filled. */
        const val NEW_GAMES_DELAY_MS = 1_500L

        suspend fun create(services: FuseServices, scope: CoroutineScope, safeMode: Boolean = false): DefaultFuseStore {
            val settings = services.data.settings.current()
            val ctx = StoreContext(services, scope, settings)
            return DefaultFuseStore(ctx, settings.toUiPrefs(globalScoped(ctx))).also {
                it.safe = safeMode
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
