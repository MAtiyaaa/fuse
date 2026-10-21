package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.GameSummary
import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.obtainium.ApkChoice
import io.github.matiyaaa.fuse.integrations.obtainium.HtmlLinks
import io.github.matiyaaa.fuse.integrations.obtainium.PackApp
import io.github.matiyaaa.fuse.integrations.obtainium.PackResolver
import io.github.matiyaaa.fuse.integrations.obtainium.PackRules
import io.github.matiyaaa.fuse.integrations.obtainium.PackSourceKind
import io.github.matiyaaa.fuse.integrations.ports.OpenPorts
import io.github.matiyaaa.fuse.integrations.ports.OpenPortsClient
import io.github.matiyaaa.fuse.integrations.ports.PortsClient
import io.github.matiyaaa.fuse.integrations.ports.PortsIndexReader
import io.github.matiyaaa.fuse.integrations.ports.PortsSignature
import io.github.matiyaaa.fuse.launch.ports.PortPlan
import io.github.matiyaaa.fuse.launch.ports.Xxh3
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.library.content.ZipReader
import io.github.matiyaaa.fuse.model.AndroidPort
import io.github.matiyaaa.fuse.model.AndroidPorts
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.model.AppKind
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.AndroidPortLists
import io.github.matiyaaa.fuse.ui.shell.store.AndroidPortOffer
import io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView
import io.github.matiyaaa.fuse.ui.shell.store.AndroidPortsState
import io.github.matiyaaa.fuse.ui.shell.store.ApkPort
import io.github.matiyaaa.fuse.ui.shell.store.AppPortGuess
import io.github.matiyaaa.fuse.ui.shell.store.PackageBridge
import io.github.matiyaaa.fuse.ui.shell.store.PackageOutcome
import io.github.matiyaaa.fuse.ui.shell.store.PortGameChoice
import io.github.matiyaaa.fuse.ui.shell.store.PortJob
import io.github.matiyaaa.fuse.ui.shell.store.PortMatch
import io.github.matiyaaa.fuse.ui.shell.store.PortsOps
import io.github.matiyaaa.fuse.ui.shell.store.PortsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * The system's installer, one install at a time: Android shows one confirmation at once, so the
 * Store and Android ports take turns.
 */
internal object SystemInstaller {
    val turn = Mutex()
}

/**
 * Ports on Android ([PortsOps]): ports published as apps, each an APK its project puts in its GitHub
 * releases, which takes the game's files inside itself. The list is Fuse's own checked entries
 * (`ports/index.json`, a newer signed one when it carries them) beside Open Ports Games' listings
 * (inside Fuse, refreshed once a day at most). Installing downloads the release's APK for this
 * device as the Store does and hands it to Android's installer; the app is then attached to its
 * game (a recomp or port, [AppKind.PORT]), and Play starts the game as the app, its time the
 * game's own. An app the person installed themselves can be attached the same way.
 */
internal class AndroidPortsStore(
    private val ctx: StoreContext,
    private val bridge: PackageBridge,
    private val lists: AndroidPortLists?,
    private val apps: DefaultAppOps,
    private val write: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val publicKey: () -> String? = { PortsSignature.PUBLIC_KEY },
) : PortsOps {
    override val supported: Boolean = true
    override val androidApps: Boolean = true

    // Fuse's own folder of ports is a computer's: nothing of it here.
    override val state: StateFlow<PortsState> = MutableStateFlow(PortsState(loaded = true))

    private val mutable = MutableStateFlow(AndroidPortsState())
    override val android: StateFlow<AndroidPortsState> = mutable.asStateFlow()

    @kotlin.concurrent.Volatile private var index = AndroidPortCatalog.Index(emptyList())
    @kotlin.concurrent.Volatile private var checked: List<AndroidPort> = emptyList()
    @kotlin.concurrent.Volatile private var listed: List<AndroidPort> = emptyList()
    @kotlin.concurrent.Volatile private var listedAt: String? = null
    @kotlin.concurrent.Volatile private var lastLook = 0L
    @kotlin.concurrent.Volatile private var library: Pair<Long, AndroidPortCatalog.Games<GameSummary>>? = null
    private val loaded = MutableStateFlow(false)
    private val jobs = MutableStateFlow<Map<String, PortJob>>(emptyMap())
    private val running = MutableStateFlow<Map<String, Job>>(emptyMap())

    /** Packages being installed for a game, attached to it once Android has installed them. */
    private val attachWhenIn = MutableStateFlow<Map<String, GameId>>(emptyMap())

    /** One look at what is here at a time (made before [init] starts the looks). */
    private val publishing = Mutex()

    private val resolver = PackResolver(ctx.services.http, githubToken = { token })
    private val downloader = ApkDownloader(ctx.services.http)
    private val signedClient by lazy { PortsClient(ctx.services.http, publicKey = publicKey) }
    private val openPorts by lazy { OpenPortsClient(ctx.services.http) }
    @kotlin.concurrent.Volatile private var token: String? = null

    init {
        ctx.scope.launch {
            load()
            publish()
        }
        ctx.scope.launch {
            apps.installedApps.collect { list ->
                // One app that can't be attached never stops the next from being.
                try {
                    attachArrivals(list)
                    attachFound(list)
                    publish()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Unit
                }
            }
        }
        ctx.scope.launch { jobs.drop(1).collect { publish() } }
        ctx.scope.launch {
            ctx.settings.map { it.ports.androidPlayWith to it.ports.androidInstalled }.distinctUntilChanged().drop(1).collect { publish() }
        }
    }

    override fun open() {
        ctx.scope.launch {
            library = null
            publish()
            look(force = false)
        }
    }

    override fun refresh() {
        ctx.scope.launch { look(force = true) }
    }

    // The lists

    /** Fuse's own entries and the listings it has: inside Fuse, else newer ones kept from before. */
    private suspend fun load() {
        val inside = lists?.bundledIndex()?.let { PortsIndexReader.read(it).getOrNull() }?.index?.android.orEmpty()
        checked = keptSigned() ?: inside
        val kept = runCatching { ctx.data.cache.get(NS, LISTED, CachedListing.serializer(), ctx.now()) }.getOrNull()
        if (kept != null) {
            listed = kept.ports
            listedAt = kept.generated
        } else {
            lists?.bundledListing()?.let { OpenPorts.read(it).getOrNull() }?.let {
                listed = it.ports
                listedAt = it.generated
            }
        }
        rebuild()
        loaded.value = true
    }

    /** The Android entries of the signed list kept from before, checked again; null when there is none (or it has none). */
    private suspend fun keptSigned(): List<AndroidPort>? {
        val key = publicKey() ?: return null
        val kept = runCatching { ctx.data.cache.get(NS, SIGNED, CachedSigned.serializer(), ctx.now()) }.getOrNull() ?: return null
        if (!PortsSignature.verify(kept.text.encodeToByteArray(), kept.signature, key)) return null
        return PortsIndexReader.read(kept.text, kept.signature).getOrNull()?.index?.android?.takeIf { it.isNotEmpty() }
    }

    private fun rebuild() {
        val now = AndroidPortCatalog.Index(AndroidPortCatalog.merge(checked, listed))
        index = now
        // An installed app Fuse knows as a port is one, until the person says otherwise; Apps looks again with each new list.
        apps.portIdentifier.value = { pkg, label -> now.identify(pkg, label, ctx.settings.value.ports.androidInstalled).isNotEmpty() }
    }

    /** Looks for newer lists, once a day at most unless [force]d: Open Ports Games' and Fuse's own signed one. */
    private suspend fun look(force: Boolean) {
        loaded.first { it }
        if (!force && ctx.now() - lastLook < LOOK_EVERY_MS) return
        lastLook = ctx.now()
        mutable.update { it.copy(refreshing = true) }
        var problem: String? = null
        when (val r = openPorts.fetch()) {
            is ApiResult.Success -> if (r.value.ports.isNotEmpty()) {
                listed = r.value.ports
                listedAt = r.value.generated
                runCatching { ctx.data.cache.put(NS, LISTED, CachedListing(r.value.ports, r.value.generated), CachedListing.serializer(), ctx.now(), null) }
            }
            is ApiResult.Failure -> problem = friendly(r)
        }
        if (publicKey() != null) {
            when (val r = signedClient.fetch(newerThan = 0)) {
                is ApiResult.Success -> r.value?.takeIf { it.index.android.isNotEmpty() && it.signature != null }?.let { fresh ->
                    checked = fresh.index.android
                    runCatching { ctx.data.cache.put(NS, SIGNED, CachedSigned(fresh.text, fresh.signature!!), CachedSigned.serializer(), ctx.now(), null) }
                }
                is ApiResult.Failure -> Unit
            }
        }
        rebuild()
        mutable.update { it.copy(refreshing = false, problem = problem) }
        publish()
    }

    // What is here

    /** The library's games by system and name, read again after a little while ([fresh]: now). */
    private suspend fun games(fresh: Boolean = false): AndroidPortCatalog.Games<GameSummary> {
        if (!fresh) library?.takeIf { ctx.now() - it.first < LIBRARY_FRESH_MS }?.let { return it.second }
        val all = runCatching { ctx.data.games.observeAll().first() }.getOrDefault(emptyList()).filterNot { it.isApp || it.missing || it.removed }
        val g = AndroidPortCatalog.Games(all, { it.platformId.value }, { it.titles }, { it.id })
        library = ctx.now() to g
        return g
    }

    /**
     * The installed apps each port is, by port id: by the package Fuse installed it as or its own,
     * else (installed by the person) by its whole name, when the name is that port's alone.
     */
    private fun appsByPort(installed: List<AppEntry>): Map<String, AppEntry> {
        val byPackage = installed.associateBy { it.packageName }
        val pinned = ctx.settings.value.ports.androidInstalled
        val out = HashMap<String, AppEntry>()
        for (a in installed) index.identify(a.packageName, a.label).singleOrNull()?.let { out.putIfAbsent(it.id, a) }
        for (p in index.ports) (pinned[p.id] ?: p.packageName)?.let { byPackage[it] }?.let { out[p.id] = it }
        return out
    }

    /** The installed app that is [port]. */
    private fun appOf(port: AndroidPort, installed: List<AppEntry>): AppEntry? = appsByPort(installed)[port.id]

    private suspend fun view(port: AndroidPort, installed: List<AppEntry>, games: AndroidPortCatalog.Games<GameSummary>?, app: AppEntry? = appOf(port, installed)): AndroidPortView {
        val version = app?.let { runCatching { bridge.installed(it.packageName) }.getOrNull()?.versionName }
        return AndroidPortView(
            port = port,
            packageName = app?.packageName ?: ctx.settings.value.ports.androidInstalled[port.id] ?: port.packageName,
            appId = app?.id,
            installedVersion = version,
            job = jobs.value[port.id],
            games = games?.of(port)?.map { choice(it, true) }.orEmpty(),
            attachedTo = installed.filter { a -> a.packageName == app?.packageName && a.plays != null }.mapNotNull { a ->
                games?.byId(GameId(a.plays!!))?.let { choice(it, true) }
            },
        )
    }

    private suspend fun publish() = publishing.withLock {
        if (!loaded.value) return@withLock
        val installed = apps.installedApps.value
        val games = games()
        val byPort = appsByPort(installed)
        val views = index.ports.map { view(it, installed, games, byPort[it.id]) }
        mutable.update { it.copy(ports = views, loaded = true, listedAt = listedAt) }
    }

    // Play With

    override suspend fun androidPortsFor(game: GameId): List<AndroidPortOffer> {
        loaded.first { it }
        ctx.scope.launch { look(force = false) }
        val g = runCatching { ctx.data.games.get(game) }.getOrNull() ?: return emptyList()
        if (g.appId != null) return emptyList()
        val installed = apps.installedApps.value
        val candidates = index.forSystem(g.platformId.value)
        val rom = if (candidates.any { it.roms.isNotEmpty() }) romXxh3(g) else null
        val chosen = androidPlayWith(game)
        val known = candidates.mapNotNull { p -> AndroidPortCatalog.match(p, g, rom)?.let { p to it } }.map { (p, m) ->
            val v = view(p, installed, null)
            AndroidPortOffer(v, m, chosen != null && v.appId == chosen)
        }
        // Apps the person attached to this game that no list knows.
        val knownApps = known.mapNotNull { it.view.appId }.toSet()
        val own = installed.filter { it.plays == game.value && it.id !in knownApps }.map { a ->
            val p = AndroidPort(id = "app-${a.packageName}", name = a.displayTitle, game = g.displayTitle, systems = listOf(g.platformId.value), repo = "")
            val here = PortGameChoice(game, g.displayTitle, PlatformCatalog.byId(g.platformId)?.name ?: g.platformId.value, true)
            AndroidPortOffer(AndroidPortView(p, packageName = a.packageName, appId = a.id, attachedTo = listOf(here)), PortMatch.ATTACHED, chosen == a.id)
        }
        return (own + known).sortedWith(compareBy({ if (it.view.installed) 0 else 1 }, { if (it.match == PortMatch.ATTACHED) -1 else AndroidPortCatalog.order(it.view.port, it.match) }))
    }

    override fun androidPlayWith(game: GameId): String? = ctx.settings.value.ports.androidPlayWith[game.value.toString()]

    override suspend fun setAndroidPlayWith(game: GameId, appId: String?) = write { s ->
        val key = game.value.toString()
        s.copy(ports = s.ports.copy(androidPlayWith = if (appId == null) s.ports.androidPlayWith - key else s.ports.androidPlayWith + (key to appId)))
    }

    /** The app Play starts [game] as, with its name: the one chosen, while it is installed. */
    fun playApp(game: GameId): Pair<String, String>? {
        val appId = androidPlayWith(game) ?: return null
        val app = apps.installedApps.value.firstOrNull { it.id == appId } ?: return null
        return app.id to app.displayTitle
    }

    override suspend fun attach(appId: String, game: GameId): String? {
        val app = apps.installedApps.value.firstOrNull { it.id == appId } ?: return "That app isn't installed any more."
        if (runCatching { ctx.data.games.get(game) }.getOrNull() == null) return "That game isn't in the library any more."
        apps.setKind(app.id, AppKind.PORT)
        apps.setPlays(app.id, game)
        setAndroidPlayWith(game, app.id)
        return null
    }

    override suspend fun detach(appId: String) {
        apps.setPlays(appId, null)
        write { s -> s.copy(ports = s.ports.copy(androidPlayWith = s.ports.androidPlayWith.filterValues { it != appId })) }
    }

    /**
     * A port the person installed themselves, known by its package (Fuse's checked one), is attached
     * to its game when the library has exactly one copy of it, and plays it unless another app was
     * chosen. Only once: attaching says what it is, so a port they detach later stays detached.
     */
    private suspend fun attachFound(list: List<AppEntry>) {
        if (!loaded.value) return
        val found = list.filter { it.chosenKind == null && it.detectedKind == AppKind.PORT && it.plays == null }
        if (found.isEmpty()) return
        val games = games(fresh = true)
        for (a in found) {
            val port = index.identify(a.packageName, "").singleOrNull() ?: continue
            val game = games.of(port).singleOrNull()?.id ?: continue
            apps.setKind(a.id, AppKind.PORT)
            apps.setPlays(a.id, game)
            if (androidPlayWith(game) == null) setAndroidPlayWith(game, a.id)
        }
    }

    /** Attaches each app awaited ([attachWhenIn]) once Android has installed it. */
    private suspend fun attachArrivals(list: List<AppEntry>) {
        val waiting = attachWhenIn.value
        if (waiting.isEmpty()) return
        for ((pkg, game) in waiting) {
            val app = list.firstOrNull { it.packageName == pkg } ?: continue
            attachWhenIn.update { it - pkg }
            attach(app.id, game)
        }
    }

    // "What game is this?"

    override suspend fun gamesForApp(packageName: String, label: String, query: String?): AppPortGuess {
        loaded.first { it }
        val ports = index.identify(packageName, label, ctx.settings.value.ports.androidInstalled)
        val port = ports.singleOrNull() ?: AndroidPortCatalog.oneGame(ports)
        val all = runCatching { ctx.data.games.observeAll().first() }.getOrDefault(emptyList()).filterNot { it.isApp || it.removed }
        val g = AndroidPortCatalog.Games(all, { it.platformId.value }, { it.titles }, { it.id })
        val plays = if (query == null) port?.let { g.of(it) }.orEmpty() else emptyList()
        val likely = AndroidPortCatalog.guess(query ?: label, all, limit = GUESSES) { it.titles }.filter { c -> plays.none { it.id == c.id } }
        return AppPortGuess(port, plays.map { choice(it, true) } + likely.map { choice(it, false) })
    }

    override suspend fun attachOnceInstalled(packageName: String, game: GameId) {
        val app = apps.installedApps.value.firstOrNull { it.packageName == packageName }
        if (app != null) {
            attach(app.id, game)
            return
        }
        apps.expect(packageName, AppKind.PORT, game)
        attachWhenIn.update { it + (packageName to game) }
    }

    override suspend fun apkPort(path: String): ApkPort? {
        loaded.first { it }
        val archive = runCatching { bridge.inspect(path) }.getOrNull() ?: return null
        val port = index.identify(archive.packageName, "", ctx.settings.value.ports.androidInstalled).let { it.singleOrNull() ?: AndroidPortCatalog.oneGame(it) } ?: return null
        return ApkPort(port, archive.packageName, games().of(port).map { choice(it, true) })
    }

    private fun choice(g: GameSummary, plays: Boolean) =
        PortGameChoice(g.id, g.titles.display, PlatformCatalog.byId(g.platformId)?.name ?: g.platformId.value, plays)

    // Installing

    override fun installAndroid(id: String, forGame: GameId?) {
        if (jobs.value[id]?.active == true) return
        jobs.update { it + (id to PortJob.Downloading(null, 0, null)) }
        val job = ctx.scope.launch {
            // Asked for as Fuse starts: the lists are read first.
            loaded.first { it }
            val port = index.byId[id]
            if (port == null) jobs.update { it - id } else runInstall(port, forGame)
        }
        running.update { it + (id to job) }
        job.invokeOnCompletion { running.update { m -> if (m[id] === job) m - id else m } }
    }

    override fun cancel(id: String) {
        running.value[id]?.cancel()
        jobs.update { it - id }
    }

    private suspend fun runInstall(port: AndroidPort, forGame: GameId?) {
        try {
            val pkg = install(port)
            if (pkg == null) {
                jobs.update { it - port.id }
                return
            }
            write { s -> s.copy(ports = s.ports.copy(androidInstalled = s.ports.androidInstalled + (port.id to pkg))) }
            // Attached to the game it was installed for, else to its one game in the library.
            val game = forGame ?: games().of(port).singleOrNull()?.id
            if (game != null) {
                val app = apps.installedApps.value.firstOrNull { it.packageName == pkg }
                if (app != null) attach(app.id, game) else attachWhenIn.update { it + (pkg to game) }
            } else {
                apps.expect(pkg, AppKind.PORT, null)
            }
            jobs.update { it - port.id }
        } catch (e: CancellationException) {
            jobs.update { it - port.id }
            throw e
        } catch (e: InstallFailure) {
            jobs.update { it + (port.id to PortJob.Failed(e.message ?: "It didn't install.")) }
        } catch (e: Exception) {
            jobs.update { it + (port.id to PortJob.Failed("Something went wrong installing ${port.name}. Try again.")) }
        }
    }

    /** Downloads [port]'s newest APK for this device and has Android install it: its package, or null when the person said no. */
    private suspend fun install(port: AndroidPort): String? {
        if (!port.installable) throw InstallFailure("${port.name} isn't on GitHub, so get it from its own page.")
        token = runCatching { ctx.services.secrets.get(DefaultAppStoreOps.TOKEN_KEY) }.getOrNull()
        val release = when (val r = resolver.resolve(packApp(port), bridge.abis)) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> throw InstallFailure(friendly(r))
        }
        val apk = when (val c = release.choice) {
            is ApkChoice.Install -> c.apk
            is ApkChoice.Manual -> throw InstallFailure(c.reason)
            null -> throw InstallFailure("${port.name} has no Android app in its releases.")
        }
        if (!HtmlLinks.isHttps(apk.url)) throw InstallFailure("The download isn't offered over a secure (HTTPS) address, so Fuse won't install it.")
        if (!bridge.canInstall()) {
            bridge.requestInstallPermission()
            // Back from Android's settings, it carries on.
            var waited = 0L
            while (!bridge.canInstall()) {
                if (waited >= PERMISSION_WAIT_MS) throw InstallFailure("Let Fuse install apps in Android's settings, then try again.")
                delay(PERMISSION_POLL_MS)
                waited += PERMISSION_POLL_MS
            }
        }
        val sink = bridge.newDownload(apk.name) ?: throw InstallFailure("Fuse couldn't prepare its download folder.")
        try {
            var lastPercent = -1
            try {
                downloader.download(apk.url, sink, apk.sizeBytes, apk.digest, bridge.freeBytes()) { written, total ->
                    val percent = if (total != null) (written * 100 / total).toInt() else (written / (1024 * 1024)).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        jobs.update { it + (port.id to PortJob.Downloading(total?.let { t -> (written.toFloat() / t).coerceIn(0f, 1f) }, written, total)) }
                    }
                }
            } catch (e: DownloadException) {
                throw InstallFailure(e.message ?: "The download failed.")
            }
            val archive = bridge.inspect(sink.path) ?: throw InstallFailure("The download isn't an app Android can read, so it wasn't installed.")
            // Once installed, a port is always the same app: an update of another package is refused.
            val pinned = ctx.settings.value.ports.androidInstalled[port.id]
            if (pinned != null && pinned != archive.packageName) {
                throw InstallFailure("The download is a different app (${archive.packageName}) than ${port.name} was ($pinned), so it wasn't installed.")
            }
            val current = bridge.installed(archive.packageName)
            if (current != null && current.versionCode > archive.versionCode) {
                throw InstallFailure("A newer build of ${port.name} is already installed (${current.versionName ?: "version ${current.versionCode}"}).")
            }
            jobs.update { it + (port.id to PortJob.Installing(waitingTurn = SystemInstaller.turn.isLocked)) }
            val outcome = SystemInstaller.turn.withLock {
                jobs.update { it + (port.id to PortJob.Installing(waitingTurn = false)) }
                bridge.install(sink.path) {}
            }
            return when (outcome) {
                PackageOutcome.Done -> archive.packageName
                PackageOutcome.Cancelled -> null
                is PackageOutcome.Failed -> throw InstallFailure(
                    if (outcome.conflict) "${port.name} is already installed from somewhere else, signed with a different key. Uninstall it first to install this one." else outcome.message,
                )
            }
        } finally {
            // Android's installer keeps its own copy: the download goes now.
            sink.discard()
        }
    }

    /** [port] as the Store's resolver reads an app: its GitHub releases, an APK for this device, older releases when the newest has none. */
    private fun packApp(port: AndroidPort) = PackApp(
        id = port.packageName ?: "port-${port.id}",
        url = port.repo,
        name = port.name,
        author = AndroidPorts.githubRepo(port.repo)?.first.orEmpty(),
        categories = emptyList(),
        source = PackSourceKind.GITHUB,
        allowIdChange = true,
        preferredApkIndex = null,
        rules = PackRules(about = port.about, apkFilter = port.asset, fallbackToOlderReleases = true),
    )

    override fun uninstallAndroid(id: String) {
        val app = index.byId[id]?.let { appOf(it, apps.installedApps.value) } ?: return
        ctx.scope.launch {
            val outcome = runCatching { bridge.uninstall(app.packageName) }.getOrNull()
            if (outcome == PackageOutcome.Done) {
                write { s -> s.copy(ports = s.ports.copy(androidPlayWith = s.ports.androidPlayWith.filterValues { it != app.id })) }
            }
        }
    }

    override fun openAndroid(id: String): Boolean {
        val app = index.byId[id]?.let { appOf(it, apps.installedApps.value) } ?: return false
        return bridge.launch(app.packageName)
    }

    // A ROM's hash

    private val roms = HashMap<String, String?>()

    /** The XXH3-64 of [g]'s N64 ROM in its big-endian form (as recomps check it), when it can be read. */
    private suspend fun romXxh3(g: Game): String? {
        val fs = ctx.services.fs
        val path = g.location.launchPath
        val st = runCatching { fs.stat(path) }.getOrNull()?.takeIf { !it.isDirectory && it.sizeBytes > 0 } ?: return null
        val key = "$path|${st.sizeBytes}"
        if (key in roms) return roms[key]
        val bytes = runCatching {
            if (path.substringAfterLast('.').equals("zip", ignoreCase = true)) {
                val zip = ZipReader(fs)
                zip.entries(path)?.firstOrNull { e -> e.name.substringAfterLast('.').lowercase() in ROM_EXTENSIONS && e.size in 1..MAX_ROM }
                    ?.let { zip.read(path, it, MAX_ROM.toInt()) }
            } else if (st.sizeBytes <= MAX_ROM) {
                fs.readBytes(path, 0, st.sizeBytes.toInt())
            } else {
                null
            }
        }.getOrNull()
        val hash = bytes?.let { PortPlan.bigEndianRom(it) }?.let { Xxh3.hex(it) }
        roms[key] = hash
        return hash
    }

    private fun friendly(f: ApiResult.Failure): String = when (f) {
        is ApiResult.NetworkError -> "Fuse couldn't connect. Check the connection and try again."
        else -> f.message
    }

    private class InstallFailure(message: String) : Exception(message)

    @Serializable
    private data class CachedListing(val ports: List<AndroidPort>, val generated: String? = null)

    @Serializable
    private data class CachedSigned(val text: String, val signature: String)

    private companion object {
        const val NS = "ports.android"
        const val LISTED = "listed"
        const val SIGNED = "signed"
        const val LOOK_EVERY_MS = 24L * 60 * 60 * 1000
        const val LIBRARY_FRESH_MS = 30_000L
        const val PERMISSION_POLL_MS = 1_000L
        const val PERMISSION_WAIT_MS = 5L * 60 * 1000
        const val GUESSES = 8
        const val MAX_ROM = 128L * 1024 * 1024
        val ROM_EXTENSIONS = setOf("z64", "n64", "v64")
    }
}
