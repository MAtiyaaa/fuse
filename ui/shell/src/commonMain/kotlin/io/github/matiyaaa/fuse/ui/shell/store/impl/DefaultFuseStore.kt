package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    override val settings = DefaultScopedSettingsOps(ctx) { reloadPrefs() }
    override val library: DefaultLibraryOps

    init {
        mediaOps = DefaultMediaOps(ctx, credentials)
        library = DefaultLibraryOps(ctx, engine, emulators, collections, apps, achievements, cartridge) { enabled ->
            updatePrefs { it.copy(cleanDisplayNames = enabled) }
        }
    }

    override val media get() = mediaOps

    override fun updatePrefs(transform: (UiPrefs) -> UiPrefs) {
        val before = prefsState.value
        val after = transform(before)
        if (after == before) return
        prefsState.value = after
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
    }

    private fun start() {
        engine.start()
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
            credentials.load()
            achievements.load()
            emulators.detectNow()
            cartridge.start()
            if (data.sources.all().isNotEmpty()) engine.rescan(ScanScope.QUICK)
            achievements.refresh(force = false)
            data.cache.purgeExpired(ctx.now())
            runCatching { updates.checkIfDue() }
        }
    }

    companion object {
        suspend fun create(services: FuseServices, scope: CoroutineScope): DefaultFuseStore {
            val settings = services.data.settings.current()
            val ctx = StoreContext(services, scope, settings)
            return DefaultFuseStore(ctx, settings.toUiPrefs(globalScoped(ctx))).also { it.start() }
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
