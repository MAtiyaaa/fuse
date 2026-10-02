package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.launch.Confidence
import io.github.matiyaaa.fuse.launch.LaunchOptions
import io.github.matiyaaa.fuse.launch.LaunchRequest
import io.github.matiyaaa.fuse.launch.LaunchResolver
import io.github.matiyaaa.fuse.launch.TargetResult
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.SettingScope
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorDetails
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import kotlinx.coroutines.flow.first
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorOps
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorOption
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class DefaultEmulatorOps(private val ctx: StoreContext) : EmulatorOps {
    override val installed: StateFlow<List<InstalledEmulator>> = ctx.installed
    private var detectJob: Job? = null
    private var lastDetect = 0L

    override fun refresh() {
        if (detectJob?.isActive == true) return
        detectJob = ctx.scope.launch { detectNow() }
    }

    /** Re-detects at most once every [minIntervalMs] (used on every return to Fuse). */
    fun refreshIfStale(minIntervalMs: Long = 30_000) {
        if (ctx.now() - lastDetect >= minIntervalMs) refresh()
    }

    suspend fun detectNow() {
        val found = try {
            ctx.services.emulators.detect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        lastDetect = ctx.now()
        // Keep one entry per adapter id; the first detection wins (catalog order).
        ctx.installed.value = found.distinctBy { it.id }
        ctx.emulatorsDetected.value = true
    }

    override fun optionsFor(platform: PlatformId): List<EmulatorOption> {
        val installedIds = installed.value.associateBy { it.id }
        // Shortcut openers belong only where the priority list names them (Steam, PC games).
        val named = ctx.registry.priority(platform, ctx.host).toSet()
        return ctx.registry.forPlatform(platform, ctx.host)
            .filter { !it.shortcutsOnly || it.id in named }
            .map { adapter ->
                val found = installedIds[adapter.id] ?: builtIn(adapter)
                val note = when {
                    adapter.opensAppOnly -> "Opens the app; choose the game there"
                    adapter.confidence == Confidence.COMMUNITY -> "Launch support documented by the community"
                    adapter.confidence == Confidence.UNVERIFIED -> "Launch support not confirmed"
                    found?.isFamilyMatch == true -> "Recognised as a ${adapter.name} build"
                    else -> null
                }
                EmulatorOption(adapter.id, found?.name ?: adapter.name, installed = found != null, note = note)
            }
            .sortedByDescending { it.installed }
    }

    override suspend fun optionsForGame(game: GameId): List<EmulatorOption> {
        val g = ctx.data.games.get(game) ?: return emptyList()
        val found = installed.value.associateBy { it.id }
        // A resolver without a playlist maker: checking never writes anything.
        val checker = LaunchResolver(ctx.registry)
        return optionsFor(g.platformId).map { o ->
            val adapter = ctx.registry[o.id] ?: return@map o
            val here = found[o.id] ?: builtIn(adapter) ?: return@map if (ctx.services.emulators.canLocate) o else o.copy(unavailable = "Not installed on this device")
            val reason = try {
                when (val t = checker.targetFor(g, adapter, host = ctx.host)) {
                    is TargetResult.Failed -> t.reason
                    is TargetResult.Ok -> (adapter.plan(LaunchRequest(g, here, t.target, LaunchOptions())) as? LaunchPlan.Unsupported)?.reason
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (reason != null) o.copy(unavailable = reason) else o
        }
    }

    /** Built-in adapters (shortcuts, scripts, Android apps) need nothing installed. */
    private fun builtIn(adapter: io.github.matiyaaa.fuse.launch.EmulatorAdapter): InstalledEmulator? =
        if (adapter.builtIn) InstalledEmulator(adapter.id, adapter.name, ctx.host, appId = "", platforms = adapter.platforms, detectedVia = "Built in") else null

    override suspend fun details(emulator: EmulatorId): EmulatorDetails? {
        val adapter = ctx.registry[emulator]?.takeIf { it.host == ctx.host } ?: return null
        val here = installed.value.firstOrNull { it.id == emulator }
        val systems = (here?.platforms ?: adapter.platforms).mapNotNull { ctx.platform(it) }
        val chosen = systems.filter { p ->
            val r = ctx.data.scopedSettings.resolve(ScopedSettings.Emulator, p.id, null)
            r.from == SettingScope.PLATFORM && r.value == emulator.value
        }
        return EmulatorDetails(
            id = emulator,
            name = here?.name ?: adapter.name,
            version = here?.version,
            installed = here != null,
            foundVia = here?.detectedVia,
            appId = here?.appId,
            locatedAt = located.value[emulator],
            systems = systems.map { it.name }.sorted(),
            chosenFor = chosen.map { it.name }.sorted(),
            support = when (adapter.confidence) {
                Confidence.VERIFIED_ESDE, Confidence.VERIFIED_SOURCE -> "Starts games directly; checked against its own documentation"
                Confidence.COMMUNITY -> "Starts games the way the community documents; not confirmed by its makers"
                Confidence.UNVERIFIED -> "Launch support not confirmed yet"
            },
            limitations = adapter.limitations,
            homepage = adapter.homepage,
            opensAppOnly = adapter.opensAppOnly,
        )
    }

    override suspend fun testGame(emulator: EmulatorId): GameCard? {
        val adapter = ctx.registry[emulator] ?: return null
        val runs = installed.value.firstOrNull { it.id == emulator }?.platforms ?: adapter.platforms
        val offline = ctx.offline.value
        val pick = ctx.data.games.observeAll().first()
            .filter { it.platformId in runs && !it.missing && !it.isApp && offline.none { root -> root.holds(it.folderPath) } }
            .sortedWith(compareByDescending<io.github.matiyaaa.fuse.data.repo.GameSummary> { it.lastPlayedAt ?: 0L }.thenBy { it.sortKey })
            .firstOrNull() ?: return null
        return ctx.cardsOnce(listOf(pick)).firstOrNull()
    }

    override suspend fun setPlatformEmulator(platform: PlatformId, emulator: EmulatorId?) {
        val scope = ScopeRef.platform(platform)
        if (emulator == null) {
            ctx.data.scopedSettings.clear(ScopedSettings.Emulator, scope)
        } else {
            ctx.data.scopedSettings.set(ScopedSettings.Emulator, scope, emulator.value)
        }
    }

    override suspend fun openEmulator(emulator: EmulatorId) {
        val found = installed.value.firstOrNull { it.id == emulator } ?: return
        if (ctx.services.launcher.openApp(found.appId) is RunResult.NotInstalled) refresh()
    }

    override fun limitations(emulator: EmulatorId): List<String> = ctx.registry[emulator]?.limitations.orEmpty()

    override fun homepage(emulator: EmulatorId): String? = ctx.registry[emulator]?.homepage

    override val canLocate: Boolean get() = ctx.services.emulators.canLocate

    override fun known(): List<EmulatorOption> {
        val found = installed.value.associateBy { it.id }
        return ctx.registry.forHost(ctx.host).filterNot { it.builtIn }
            .map { a -> EmulatorOption(a.id, found[a.id]?.name ?: a.name, installed = a.id in found, note = found[a.id]?.appId) }
            .sortedBy { it.name.lowercase() }
    }

    private val _located = MutableStateFlow(ctx.services.emulators.located())
    override val located: StateFlow<Map<EmulatorId, String>> = _located.asStateFlow()

    private val _searchFolders = MutableStateFlow(ctx.services.emulators.searchFolders())
    override val searchFolders: StateFlow<List<String>> = _searchFolders.asStateFlow()

    override suspend fun locate(emulator: EmulatorId, path: String): Boolean {
        if (!ctx.services.emulators.locate(emulator, path)) return false
        _located.value = ctx.services.emulators.located()
        detectNow()
        return installed.value.any { it.id == emulator }
    }

    override suspend fun forget(emulator: EmulatorId) {
        ctx.services.emulators.forget(emulator)
        _located.value = ctx.services.emulators.located()
        detectNow()
    }

    override suspend fun setSearchFolders(folders: List<String>) {
        ctx.services.emulators.setSearchFolders(folders)
        _searchFolders.value = ctx.services.emulators.searchFolders()
        detectNow()
    }
}
