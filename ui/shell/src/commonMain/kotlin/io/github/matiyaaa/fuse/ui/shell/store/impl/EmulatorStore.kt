package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.launch.Confidence
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
    }

    override fun optionsFor(platform: PlatformId): List<EmulatorOption> {
        val installedIds = installed.value.associateBy { it.id }
        return ctx.registry.forPlatform(platform, ctx.host)
            .map { adapter ->
                val found = installedIds[adapter.id]
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
