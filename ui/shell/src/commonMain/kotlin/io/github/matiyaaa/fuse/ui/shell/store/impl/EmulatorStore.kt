package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.launch.Confidence
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorSupportRow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.matiyaaa.fuse.launch.intelligence.FuseIntelligence
import io.github.matiyaaa.fuse.launch.intelligence.Verification

/** How long a game must run in an emulator, through Fuse, to count as the emulator really starting games. */
private const val LAUNCH_VERIFIED_MS = 20_000L

internal class DefaultEmulatorOps(private val ctx: StoreContext) : EmulatorOps {
    override val installed: StateFlow<List<InstalledEmulator>> = ctx.installed
    private var detectJob: Job? = null
    private var lastDetect = 0L

    /** Fuse Intelligence: learns where emulators keep their settings by looking, never touching. */
    private val brain = runCatching { ctx.services.intelligence() }.getOrNull()?.let { (look, store) -> FuseIntelligence(store, look, ctx::now) }
    private val brainTurn = Mutex()
    private var learnJob: Job? = null

    override fun refresh() {
        if (detectJob?.isActive == true) return
        detectJob = ctx.scope.launch { detectNow() }
    }

    /** Re-detects at most once every [minIntervalMs] (used on every return to Fuse). */
    fun refreshIfStale(minIntervalMs: Long = 30_000) {
        if (!ctx.governor.policy.detection) return
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
        learnSoon()
    }

    /**
     * Brings Intelligence up to date with what was found: only what changed is looked into, and
     * never while a game is being played or the device has just woken (the Performance Governor
     * holds it until then).
     */
    private fun learnSoon() {
        val b = brain ?: return
        if (learnJob?.isActive == true) return
        learnJob = ctx.scope.launch(Dispatchers.Default) {
            ctx.governor.await { it.detection }
            brainTurn.withLock { runCatching { b.learn(installed.value) } }
        }
    }

    /** How far [emulator] has gone here, from what Intelligence knows and the games seen running in it. */
    private suspend fun verification(emulator: InstalledEmulator): Verification? {
        val b = brain ?: return null
        if (emulator.detectedVia == "Built in") return null
        val played = runCatching { ctx.data.playSessions.playedByEmulator(LAUNCH_VERIFIED_MS) }.getOrDefault(emptyMap())
        return brainTurn.withLock {
            // Asked before the first round: learn this one now (only it, and only what is missing).
            if (emulator.id.value !in b.known.emulators) runCatching { b.learn(listOf(emulator)) }
            runCatching { b.verify(emulator, played[emulator.id.value]) }.getOrNull()
        }
    }

    override suspend fun rejectSettingsFolder(emulator: EmulatorId) {
        val b = brain ?: return
        brainTurn.withLock { b.reject(emulator) }
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

    // Bringing a person's things over from another emulator of the family -------------------------

    @kotlinx.serialization.Serializable
    private data class MoveRecord(val id: String, val from: String, val files: Int, val at: Long)

    private val moveJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val movePlans = HashMap<String, Pair<EmulatorId, io.github.matiyaaa.fuse.sync.FamilyMove>>()

    private suspend fun moveRecords(emulator: EmulatorId): List<MoveRecord> = ctx.data.owned.get(MOVES, emulator.value).mapNotNull {
        runCatching { moveJson.decodeFromString(MoveRecord.serializer(), it) }.getOrNull()
    }

    override suspend fun familyMoves(emulator: EmulatorId): io.github.matiyaaa.fuse.ui.shell.store.FamilyMoves {
        val files = ctx.services.emulatorFiles ?: return io.github.matiyaaa.fuse.ui.shell.store.FamilyMoves(emptyList(), emptyList())
        val here = installed.value.firstOrNull { it.id == emulator }
        val offers = if (here == null || io.github.matiyaaa.fuse.sync.EmulatorFamilies.family(emulator.value) == null) emptyList() else files.familyFolders(here)
        val done = moveRecords(emulator).sortedByDescending { it.at }.map { io.github.matiyaaa.fuse.ui.shell.store.FamilyMoveDone(it.id, it.from, it.files, it.at) }
        return io.github.matiyaaa.fuse.ui.shell.store.FamilyMoves(offers, done)
    }

    override suspend fun planFamilyMove(emulator: EmulatorId, from: String): io.github.matiyaaa.fuse.ui.shell.store.FamilyMovePreview {
        fun no(t: String, r: String) = io.github.matiyaaa.fuse.ui.shell.store.FamilyMovePreview.Unavailable(t, r)
        val files = ctx.services.emulatorFiles ?: return no("Not on this device", "This is done from Fuse on a computer.")
        val here = installed.value.firstOrNull { it.id == emulator } ?: return no("Not installed", "Install it first.")
        val move = files.familyMove(here, from) ?: return no("${here.name} isn't set up yet", "Start ${here.name} once so it makes its folder, then come back.")
        if (move.files.isEmpty()) return no("Nothing to bring over", "${here.name} already has everything ${move.from.name} keeps in ${move.carried.joinToString(", ")}.")
        val token = ctx.now().toString(36) + "-" + kotlin.random.Random.nextInt(1 shl 30).toString(36)
        movePlans[token] = emulator to move
        return io.github.matiyaaa.fuse.ui.shell.store.FamilyMovePreview.Ready(
            token, move.from.name, move.to.name, move.from.folder, move.to.folder, move.files.size, move.kept, move.carried, move.leftOut, move.truncated,
        )
    }

    override suspend fun bringOver(token: String): String? {
        val (emulator, move) = movePlans.remove(token) ?: return "That preview is out of date. Look again."
        val files = ctx.services.emulatorFiles ?: return "Not on this device."
        val id = "move-" + token.filter { it.isLetterOrDigit() || it == '-' }
        val planned = move.files.map { io.github.matiyaaa.fuse.launch.patches.PlannedFile(it, "${move.to.folder}/$it") }
        if (!files.install(move.from.folder, planned, emptyMap(), id)) {
            return "Fuse couldn't copy everything, so it put back what it had copied and changed nothing. ${move.to.name}'s folder may be read-only, or there may not be room for it."
        }
        val record = MoveRecord(id, move.from.name, planned.size, ctx.now())
        ctx.data.owned.set(MOVES, emulator.value, (moveRecords(emulator) + record).map { moveJson.encodeToString(MoveRecord.serializer(), it) }.toSet())
        return null
    }

    override suspend fun takeBackFamilyMove(emulator: EmulatorId, move: String): Boolean {
        val files = ctx.services.emulatorFiles ?: return false
        val all = moveRecords(emulator)
        if (all.none { it.id == move }) return false
        if (!files.undo(move)) return false
        ctx.data.owned.set(MOVES, emulator.value, all.filter { it.id != move }.map { moveJson.encodeToString(MoveRecord.serializer(), it) }.toSet())
        return true
    }

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
            verification = here?.let { verification(it) },
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

    override suspend fun support(): List<EmulatorSupportRow> = ctx.registry.forHost(ctx.host).filterNot { it.builtIn }.map { a ->
        val here = installed.value.firstOrNull { it.id == a.id }
        val family = a.id.value.substringAfter('.')
        EmulatorSupportRow(
            id = a.id,
            name = here?.name ?: a.name,
            installed = here != null,
            version = here?.version,
            foundVia = here?.detectedVia,
            path = here?.appId,
            evidence = when (a.confidence) {
                Confidence.VERIFIED_ESDE, Confidence.VERIFIED_SOURCE -> "Its own documentation"
                Confidence.COMMUNITY -> "Community documentation"
                Confidence.UNVERIFIED -> "Not confirmed yet"
            },
            checked = here?.let { verification(it)?.furthest?.title },
            systems = (here?.platforms ?: a.platforms).size,
            features = buildList {
                add(if (a.opensAppOnly) "opens its app" else "starts games")
                if (io.github.matiyaaa.fuse.sync.SaveAdapters.forEmulator(a.id.value) != null) add("saves")
                if (family in setOf("pcsx2", "duckstation")) add("patches")
                if (family in setOf("pcsx2", "duckstation", "ppsspp") || family.startsWith("retroarch")) add("cheats")
                if (family in io.github.matiyaaa.fuse.launch.settings.NativeSettings.changeable) add("settings")
            },
        )
    }.sortedWith(compareByDescending<EmulatorSupportRow> { it.installed }.thenBy { it.name.lowercase() })

    override suspend fun supportReport(): String {
        val rows = support()
        fun line(r: EmulatorSupportRow) = buildString {
            append(r.name).append(" [").append(r.id.value).append("]: ")
            append(if (r.installed) "installed" + (r.version?.let { ", version $it" } ?: "") + (r.foundVia?.let { ", via $it" } ?: "") else "not installed")
            r.checked?.let { append(", checked: ").append(it) }
            append("; ").append(r.systems).append(if (r.systems == 1) " system" else " systems")
            append("; ").append(r.features.joinToString(", "))
            append("; launch from ").append(r.evidence.lowercase())
            r.path?.let { append("\n    ").append(it) }
        }
        return DiagnosticsReport.render(
            listOf(
                DiagnosticsReport.Section("Installed", rows.filter { it.installed }.map(::line)),
                DiagnosticsReport.Section("Known, not installed", rows.filterNot { it.installed }.map(::line)),
            ),
            ctx.services.emulators.homeDir,
        ).replaceFirst("Fuse diagnostics report", "Fuse emulator support report")
    }

    override suspend fun installCore(emulator: EmulatorId, core: String): String? {
        val e = installed.value.firstOrNull { it.id == emulator } ?: return "It isn't installed any more."
        return ctx.services.emulators.installRetroArchCore(e, core).fold({ null }, { it.message ?: "The core couldn't be downloaded." })
    }

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

private const val MOVES = "family.moves"
