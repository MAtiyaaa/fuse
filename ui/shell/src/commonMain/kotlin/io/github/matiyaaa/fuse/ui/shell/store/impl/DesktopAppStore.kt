package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.CustomStoreApp
import io.github.matiyaaa.fuse.data.settings.KeptVersion
import io.github.matiyaaa.fuse.data.settings.StoreInstall
import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssetKind
import io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssets
import io.github.matiyaaa.fuse.integrations.obtainium.DesktopRelease
import io.github.matiyaaa.fuse.integrations.obtainium.HtmlLinks
import io.github.matiyaaa.fuse.integrations.obtainium.PackResolver
import io.github.matiyaaa.fuse.integrations.obtainium.ReleaseLink
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.StoreVariant
import io.github.matiyaaa.fuse.ui.shell.store.AppStoreOps
import io.github.matiyaaa.fuse.ui.shell.store.Availability
import io.github.matiyaaa.fuse.ui.shell.store.CustomPreview
import io.github.matiyaaa.fuse.ui.shell.store.DesktopInstaller
import io.github.matiyaaa.fuse.ui.shell.store.InFuse
import io.github.matiyaaa.fuse.ui.shell.store.EarlierVersion
import io.github.matiyaaa.fuse.ui.shell.store.InstallRecord
import io.github.matiyaaa.fuse.ui.shell.store.InstalledApp
import io.github.matiyaaa.fuse.ui.shell.store.Managed
import io.github.matiyaaa.fuse.ui.shell.store.UpdateOutcome
import io.github.matiyaaa.fuse.ui.shell.store.PasswordAsk
import io.github.matiyaaa.fuse.ui.shell.store.RemoveOutcome
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseCheck
import io.github.matiyaaa.fuse.ui.shell.store.SourceKind
import io.github.matiyaaa.fuse.ui.shell.store.StoreApp
import io.github.matiyaaa.fuse.ui.shell.store.StoreCatalogue
import io.github.matiyaaa.fuse.ui.shell.store.StoreCategory
import io.github.matiyaaa.fuse.ui.shell.store.StoreFile
import io.github.matiyaaa.fuse.ui.shell.store.StoreJob
import io.github.matiyaaa.fuse.ui.shell.store.StoreRelease
import io.github.matiyaaa.fuse.ui.shell.store.StoreState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The Store on a computer: emulators and companions fetched from their own GitHub releases, each
 * as its developers build it for this system ([DesktopAssets]: AppImages on Linux, portable zips on
 * Windows, disk images or zipped apps on macOS), and put where Fuse finds emulators
 * ([DesktopInstaller]). Programs whose builds live on their own sites are listed with a way there.
 * Apps the user adds by their GitHub address join under their category.
 */
internal class DesktopAppStoreOps(
    private val ctx: StoreContext,
    private val installer: DesktopInstaller,
    /** After [key]'s program was updated: what has to be said about it besides (its Play Together setup gone), or null. */
    private val afterUpdate: suspend (key: String) -> String? = { null },
    /** Looks for emulators again, so a program just put in place is used at once. */
    private val redetect: () -> Unit = {},
) : AppStoreOps {
    override val supported: Boolean = true

    private var token: String? = null
    private val resolver by lazy { PackResolver(ctx.services.http, githubToken = { token }) }
    private val downloader by lazy { ApkDownloader(ctx.services.http) }
    private val mutable = MutableStateFlow(
        StoreState(variant = StoreVariant.STANDARD, canInstall = true, desktop = true, folder = installer.folder),
    )
    override val state: StateFlow<StoreState> = mutable.asStateFlow()
    private val noticesFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val notices: SharedFlow<String> = noticesFlow
    private val running = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val downloads = Semaphore(DefaultAppStoreOps.MAX_DOWNLOADS)
    private val checks = Semaphore(MAX_CHECKS)

    /** Every listed program by key: the built-in ones, then the user's. */
    private var entries: Map<String, DesktopEntry> = emptyMap()

    @kotlin.concurrent.Volatile private var gitHubLimitedUntil = 0L

    fun start() {
        ctx.resumeHooks.update { it + ::onResume }
        // Emulators found or gone (one removed, one installed by hand): the Store follows.
        ctx.scope.launch {
            ctx.installed.collect { list ->
                val ids = list.map { it.id.value }.toSet()
                gone.retainAll { key -> entries[key]?.emulator?.let { e -> ids.any { it.endsWith(".$e") } } == true }
                refreshInstalled()
            }
        }
        ctx.scope.launch {
            token = ctx.services.secrets.get(DefaultAppStoreOps.TOKEN_KEY)
            mutable.update { it.copy(hasGitHubToken = token != null) }
            build()
            refreshInstalled()
        }
    }

    override fun open() {
        ctx.scope.launch {
            build()
            refreshInstalled()
            checkInstalled(force = false)
        }
    }

    override fun refresh() {
        ctx.scope.launch {
            build()
            refreshInstalled()
            entries.keys.forEach { key -> launch { checkNow(key, force = true) } }
        }
    }

    // The catalogue: built in, so there is nothing to choose and nothing to fetch.
    override suspend fun chooseVariant(variant: StoreVariant) = Unit

    private fun build() {
        val custom = ctx.settings.value.store.custom.map(::customEntry)
        val all = (DesktopCatalogue.entries.filter { it.hosts == null || installer.host in it.hosts } + custom).associateBy { it.key }
        entries = all
        val categories = all.values.map { it.category }.distinct()
            .sortedBy { listOf(DesktopCatalogue.EMULATORS, DesktopCatalogue.STREAMING, AppStoreOps.COMPANIONS, DesktopCatalogue.TOOLS).indexOf(it).let { i -> if (i < 0) 99 else i } }
        val catalogue = StoreCatalogue(
            variant = StoreVariant.STANDARD,
            apps = all.values.map(::storeApp),
            categories = categories.map { StoreCategory(it, DesktopCatalogue.colors[it] ?: DesktopCatalogue.colors[AppStoreOps.OTHER]) },
            packVersion = null,
            fetchedAt = ctx.now(),
            sourceUrl = "https://github.com/matiyaaa/fuse",
        )
        // A channel can be chosen where the project publishes stable releases on GitHub (pre-releases beside them).
        val channels = all.values.filter { it.repoFor(installer.host) != null && !it.prereleases }.map { it.key }.toSet()
        val store = ctx.settings.value.store
        // What this computer can't run, said before installing (read only: its processor and graphics).
        val look = ctx.services.intelligence()?.first
        val requirements = if (look == null) emptyMap() else all.values.mapNotNull { e ->
            e.emulator?.let { io.github.matiyaaa.fuse.launch.intelligence.EmulatorRequirements.unmet(it, installer.host, look) }?.let { e.key to it }
        }.toMap()
        mutable.update { s ->
            s.copy(
                catalogue = catalogue, releases = s.releases.filterKeys { it in all }, jobs = s.jobs.filterKeys { it in all },
                channels = channels, excluded = store.excluded, prerelease = store.prerelease, requirements = requirements,
            )
        }
    }

    private fun customEntry(c: CustomStoreApp): DesktopEntry {
        val place = c.url.substringAfter("://").trimEnd('/').lowercase()
        // Added from GitHub, or from a Forgejo or Gitea server (its releases read the same way).
        val link = ReleaseLink.parse(c.url)
        val forge = link?.kind == ReleaseLink.Kind.FORGE
        return DesktopEntry(
            key = "custom@$place", name = c.name, author = link?.owner ?: c.url.substringAfter("github.com/").substringBefore('/'),
            about = "Added by you from ${c.url.substringAfter("://")}.", category = c.category, systems = emptyList(),
            repo = c.url.takeIf { !forge }, forge = c.url.takeIf { forge }, inFuse = InFuse.TOOL,
        )
    }

    private fun storeApp(e: DesktopEntry): StoreApp {
        val repo = e.repoFor(installer.host)
        val source = e.sourceFor(installer.host)
        val url = repo ?: e.forge ?: e.listings[installer.host]?.page ?: e.flathub?.takeIf { source == DesktopEntry.Source.FLATHUB }?.let { "https://flathub.org/apps/$it" } ?: e.page ?: ""
        return StoreApp(
            key = e.key,
            id = e.key,
            packageName = null,
            pinned = false,
            name = e.name,
            author = e.author,
            about = e.about,
            categories = listOf(e.category),
            sourceUrl = url,
            sourceHost = HtmlLinks.hostOf(url)?.removePrefix("www.") ?: url,
            sourceKind = if (repo != null) SourceKind.GITHUB else SourceKind.WEB,
            availability = if (source != null) Availability.INSTALLABLE else Availability.MANUAL,
            allowIdChange = false,
            systems = e.systems.map(::PlatformId),
            color = DesktopCatalogue.colors[e.category] ?: DesktopCatalogue.colors[AppStoreOps.OTHER],
            inFuse = e.inFuse,
            custom = e.key.startsWith("custom@"),
            family = e.family,
            otherBuilds = e.family?.let { f -> entries.values.filter { it.family == f && it.key != e.key }.map { it.key } }.orEmpty(),
        )
    }

    // What is installed: what Fuse put in place (while it is still there), and emulators Fuse found installed some other way.

    private suspend fun refreshInstalled() {
        val records = ctx.settings.value.store.installs
        val found = LinkedHashMap<String, InstalledApp>()
        val prefix = when (installer.host) {
            Host.WINDOWS -> "windows."
            Host.MACOS -> "macos."
            else -> "linux."
        }
        val detected = ctx.installed.value.associateBy { it.id.value }
        val managed = HashMap<String, Managed>()
        for (e in entries.values) {
            val rec = records[e.key]
            if (rec != null && installer.exists(rec.packageName)) {
                val earlier = rec.kept.filter { installer.exists(it.path) }
                found[e.key] = InstalledApp(
                    rec.packageName, rec.version, rec.versionCode, e.name,
                    InstallRecord(rec.version, rec.file, rec.versionCode, earlier.firstOrNull()?.version, earlier.map { EarlierVersion(it.path, it.version, it.keptAt) }),
                )
                continue
            }
            val other = e.emulator?.let { detected["$prefix$it"] } ?: continue
            // Just removed: detection catches up a moment later.
            if (e.key in gone) continue
            found[e.key] = InstalledApp(other.appId, other.version, 0, other.name)
            managed[e.key] = managedOf(other.appId, other.detectedVia, other.installedBy)
        }
        val suites = runCatching { ctx.services.emulators.suites() }.getOrDefault(emptySet())
        mutable.update { it.copy(installed = found, managed = managed, suites = suites) }
    }

    // Updates: left out, channels, and programs installed some other way.

    override fun setExcluded(key: String, excluded: Boolean) {
        ctx.scope.launch {
            val next = ctx.data.settings.update { s -> s.copy(store = s.store.copy(excluded = if (excluded) s.store.excluded + key else s.store.excluded - key)) }
            ctx.settings.value = next
            mutable.update { it.copy(excluded = next.store.excluded) }
        }
    }

    override fun setPrerelease(key: String, on: Boolean) {
        ctx.scope.launch {
            val next = ctx.data.settings.update { s -> s.copy(store = s.store.copy(prerelease = if (on) s.store.prerelease + key else s.store.prerelease - key)) }
            ctx.settings.value = next
            mutable.update { it.copy(prerelease = next.store.prerelease) }
            checkNow(key, force = true)
        }
    }

    override fun updateOther(key: String) {
        if (mutable.value.jobs[key]?.active == true) return
        ctx.scope.launch { updateOtherNow(key, password = null) }
    }

    private suspend fun updateOtherNow(key: String, password: String?) {
        val name = entries[key]?.name ?: key
        val other = mutable.value.installed[key] ?: return
        if (mutable.value.managedBy(key) != Managed.FLATPAK) return
        if (holdForGame { updateOtherNow(key, password) }) return
        setJob(key, StoreJob.Installing(waitingTurn = false))
        when (val r = installer.updateOther(other.packageName, name, password)) {
            UpdateOutcome.Updated -> {
                clearJob(key)
                redetect()
                refreshInstalled()
                if (inRound(key)) settle(key, null) else noticesFlow.tryEmit("$name is up to date.")
            }
            is UpdateOutcome.NeedsPassword -> {
                clearJob(key)
                ask(PasswordAsk(key, name, r.wrong, update = true))
            }
            is UpdateOutcome.Failed -> {
                setJob(key, StoreJob.Failed(r.message, retry = false))
                settle(key, r.message)
            }
        }
    }

    /**
     * While a game is played nothing is updated (a running emulator is never replaced under it):
     * [then] runs once it ends, and true says it was held.
     */
    private fun holdForGame(then: suspend () -> Unit): Boolean {
        if (ctx.playing.value == null) return false
        if (!mutable.value.holding) noticesFlow.tryEmit("Updates wait until your game ends.")
        mutable.update { it.copy(holding = true) }
        ctx.scope.launch {
            ctx.playing.first { it == null }
            mutable.update { it.copy(holding = false) }
            then()
        }
        return true
    }

    override fun onResume() {
        ctx.scope.launch { refreshInstalled() }
    }

    override fun allowInstalls() = Unit

    override fun iconModel(key: String): Any? = null

    override fun launch(key: String): Boolean = mutable.value.installed[key]?.packageName?.let(installer::launch) ?: false

    override suspend fun setGitHubToken(token: String?) {
        val clean = token?.trim()?.takeIf { it.isNotEmpty() }
        if (clean == null) ctx.services.secrets.remove(DefaultAppStoreOps.TOKEN_KEY) else ctx.services.secrets.put(DefaultAppStoreOps.TOKEN_KEY, clean)
        this.token = clean
        gitHubLimitedUntil = 0
        mutable.update { it.copy(hasGitHubToken = clean != null) }
    }

    // Releases

    override fun check(key: String, force: Boolean) {
        ctx.scope.launch { checkNow(key, force) }
    }

    override fun checkInstalled(force: Boolean) {
        val keys = mutable.value.installed.keys.toList()
        ctx.scope.launch { keys.forEach { key -> launch { checkNow(key, force) } } }
    }

    private suspend fun checkNow(key: String, force: Boolean, freshFor: Long = DefaultAppStoreOps.RELEASE_FRESH_MS): StoreRelease? {
        val e = entries[key] ?: return null
        val repo = e.repoFor(installer.host)
        val source = e.sourceFor(installer.host)
        if (source == null) {
            // Built elsewhere: the release is the program's page.
            val r = StoreRelease(null, null, null, e.page ?: "", null, "${e.name} publishes its builds on its own site. Download it there, and Fuse finds it.")
            mutable.update { s -> s.copy(releases = s.releases + (key to ReleaseCheck.Ready(r, ctx.now()))) }
            return r
        }
        val known = mutable.value.releases[key]
        if (!force && known is ReleaseCheck.Ready && !known.stale && ctx.now() - known.checkedAt < freshFor) return known.release
        if (source == DesktopEntry.Source.GITHUB && ctx.now() < gitHubLimitedUntil) return failed(key, "GitHub's limit for checks is used up for now.", gitHubLimitedUntil)
        if (known !is ReleaseCheck.Ready) mutable.update { s -> s.copy(releases = s.releases + (key to ReleaseCheck.Checking)) }
        val pre = e.prereleases || key in ctx.settings.value.store.prerelease
        val pattern = e.pick?.invoke(installer.host, installer.arch == "arm64", installer.handheld)
        val found = checks.withPermit {
            when (source) {
                DesktopEntry.Source.GITHUB -> resolver.desktop(repo!!, e.name, installer.host, installer.arch, pattern, prereleases = pre)
                DesktopEntry.Source.FORGE -> resolver.desktopForgejo(e.forge!!, e.name, installer.host, installer.arch, pattern, prereleases = pre)
                DesktopEntry.Source.LISTING -> e.listings.getValue(installer.host).let { l -> resolver.desktopListing(l.page, e.name, installer.host, l.pattern, l.then) }
                DesktopEntry.Source.FLATHUB -> resolver.flathub(e.flathub!!, e.name)
            }
        }
        return when (val result = found) {
            is ApiResult.Success -> {
                val r = release(e, result.value)
                // What it would need to install, against what is free, said before anything is downloaded.
                val room = r.file?.let { f -> StoreRoom.line(f.sizeBytes, DesktopAssets.kindOf(f.name, installer.host), installer.freeBytes()) }
                mutable.update { s -> s.copy(releases = s.releases + (key to ReleaseCheck.Ready(r, ctx.now())), room = if (room != null) s.room + (key to room) else s.room - key) }
                r
            }
            is ApiResult.Failure -> {
                val retryAt = (result as? ApiResult.RateLimited)?.retryAfterSeconds?.let { it * 1000 }
                if (result is ApiResult.RateLimited) gitHubLimitedUntil = retryAt ?: (ctx.now() + LIMIT_PAUSE_MS)
                failed(key, friendly(result), retryAt)
            }
        }
    }

    private fun release(e: DesktopEntry, r: DesktopRelease) = StoreRelease(
        version = r.version,
        publishedAt = r.publishedAt,
        notes = r.notes?.take(MAX_NOTES),
        pageUrl = r.pageUrl,
        file = r.file?.let { StoreFile(it.name, it.url, it.sizeBytes, it.digest) },
        // A Flathub program has no file: Flatpak installs it.
        manual = when {
            r.file != null || e.sourceFor(installer.host) == DesktopEntry.Source.FLATHUB -> null
            // Said as the processor it is, so the reason is plain: most emulators build for x86-64 only.
            installer.arch == "arm64" -> "${e.name}'s newest release has no ${systemWord()} build for this computer's ARM processor. Its page has every download."
            else -> "${e.name}'s newest release has no ${systemWord()} build Fuse can use as published. Its page has every download."
        },
    )

    private fun systemWord() = when (installer.host) {
        Host.LINUX -> "AppImage"
        Host.WINDOWS -> "portable Windows"
        Host.MACOS -> "macOS"
        Host.ANDROID -> ""
    }

    private fun failed(key: String, message: String, retryAt: Long?): StoreRelease? {
        val known = mutable.value.releases[key] as? ReleaseCheck.Ready
        mutable.update { s -> s.copy(releases = s.releases + (key to (known?.copy(stale = true) ?: ReleaseCheck.Failed(message, retryAt)))) }
        return null
    }

    // Installing

    override fun install(key: String) {
        if (mutable.value.jobs[key]?.active == true) return
        val e = entries[key] ?: return
        setJob(key, StoreJob.Waiting)
        val job = ctx.scope.launch(start = CoroutineStart.LAZY) { runInstall(key, e) }
        running.update { it + (key to job) }
        job.invokeOnCompletion { running.update { m -> if (m[key] === job) m - key else m } }
        job.start()
    }

    override fun updateAll() {
        if (holdForGame { updateAll() }) return
        val s = mutable.value
        val own = s.updatable.map { it.key }
        val flatpaks = s.flatpakUpdates.map { it.key }
        if (own.isEmpty() && flatpaks.isEmpty()) return
        // One round: each update is shown as it goes, and what came of all of them is said once at the end.
        round.update { r -> Round((r?.keys.orEmpty()) + own + flatpaks, r?.done.orEmpty()) }
        own.forEach(::install)
        flatpaks.forEach(::updateOther)
    }

    /** Update All's round: the updates in it, and each one's outcome as it lands (null: it worked). */
    private data class Round(val keys: Set<String>, val done: Map<String, String?> = emptyMap())

    private val round = MutableStateFlow<Round?>(null)

    private fun inRound(key: String) = round.value?.keys?.contains(key) == true

    /** [key]'s update came to an end ([problem] when it didn't work); once every one has, the round is said. */
    private fun settle(key: String, problem: String?) {
        val r = round.updateAndGet { r -> if (r == null || key !in r.keys) r else r.copy(done = r.done + (key to problem)) } ?: return
        if (key in r.keys && r.done.keys.containsAll(r.keys) && round.compareAndSet(r, null)) noticesFlow.tryEmit(roundText(r))
    }

    private fun roundText(r: Round): String {
        fun name(k: String) = entries[k]?.name ?: k
        val worked = r.keys.filter { r.done[it] == null }.map(::name)
        val failed = r.keys.filter { r.done[it] != null }
        val good = when (worked.size) {
            0 -> null
            1 -> "${worked.single()} is updated."
            else -> "${worked.dropLast(1).joinToString(", ")} and ${worked.last()} are updated."
        }
        val bad = when (failed.size) {
            0 -> null
            1 -> "${name(failed.single())} wasn't updated. ${r.done[failed.single()]}"
            else -> "${failed.size} weren't updated (${failed.joinToString(", ") { name(it) }}). Updates says why for each."
        }
        return listOfNotNull(good, bad).joinToString(" ")
    }

    /** [earlier] as Fuse keeps it: the newest [KEPT_VERSIONS], the rest let go from the disk. */
    private suspend fun pruneKept(earlier: List<KeptVersion>): List<KeptVersion> {
        earlier.drop(KEPT_VERSIONS).forEach { runCatching { installer.forgetKept(it.path) } }
        return earlier.take(KEPT_VERSIONS)
    }

    override fun rollBack(key: String) {
        val newest = ctx.settings.value.store.installs[key]?.kept?.firstOrNull() ?: return
        rollBackTo(key, newest.path)
    }

    override fun rollBackTo(key: String, earlierId: String) {
        if (mutable.value.jobs[key]?.active == true) return
        val e = entries[key] ?: return
        val rec = ctx.settings.value.store.installs[key] ?: return
        val chosen = rec.kept.firstOrNull { it.path == earlierId } ?: return
        ctx.scope.launch {
            setJob(key, StoreJob.Installing(waitingTurn = false))
            try {
                // What is in place now is kept in turn, so going back is never a version lost.
                val now = runCatching { installer.keepPrevious(e.name, rec.packageName) }.getOrNull()
                val path = try {
                    installer.restorePrevious(chosen.path, if (now != null) null else rec.packageName)
                } catch (x: Exception) {
                    now?.let { k -> withContext(NonCancellable) { runCatching { installer.restorePrevious(k, null) } } }
                    throw x
                }
                val earlier = listOfNotNull(now?.let { KeptVersion(it, rec.version, ctx.now()) }) + rec.kept.filter { it.path != chosen.path }
                remember(key, StoreInstall(path, chosen.version, null, 0, ctx.now(), earlier = pruneKept(earlier)))
                refreshInstalled()
                clearJob(key)
                redetect()
                noticesFlow.tryEmit("${e.name} is back to ${chosen.version?.let { "version $it" } ?: "the version you had"}.")
            } catch (x: CancellationException) {
                clearJob(key)
                throw x
            } catch (x: Exception) {
                setJob(key, StoreJob.Failed(x.message ?: "Fuse couldn't go back to the earlier version.", retry = false))
            }
        }
    }

    override fun cancel(key: String) {
        running.value[key]?.cancel()
        clearJob(key)
    }

    private suspend fun runInstall(key: String, e: DesktopEntry) {
        try {
            downloads.withPermit {
                setJob(key, StoreJob.Resolving)
                val release = checkNow(key, force = false, freshFor = INSTALL_FRESH_MS)
                    ?: throw Failure((mutable.value.releases[key] as? ReleaseCheck.Failed)?.message ?: "Fuse couldn't find ${e.name}'s newest release.", retry = true)
                if (e.sourceFor(installer.host) == DesktopEntry.Source.FLATHUB) {
                    // Flatpak fetches and keeps it; Fuse then finds it, and updates and removes it as a Flatpak.
                    setJob(key, StoreJob.Installing(waitingTurn = false))
                    try {
                        installer.installFlatpak(e.flathub!!, e.name)
                    } catch (x: CancellationException) {
                        throw x
                    } catch (x: Exception) {
                        throw Failure(x.message ?: "Flatpak couldn't install ${e.name}.", retry = true)
                    }
                    gone.remove(key)
                    clearJob(key)
                    redetect()
                    noticesFlow.tryEmit("${e.name} is installed.")
                    return@withPermit
                }
                val file = release.file ?: throw Failure(release.manual ?: "${e.name} has nothing Fuse can install here.", retry = false)
                val kind = DesktopAssets.kindOf(file.name, installer.host) ?: throw Failure("${e.name}'s download isn't something Fuse can put in place.", retry = false)
                // Room for the download and its unpacking, before a byte is fetched.
                StoreRoom.problem(e.name, file.sizeBytes, kind, installer.freeBytes())?.let { throw Failure(it, retry = true) }
                val sink = installer.newDownload(file.name) ?: throw Failure("Fuse couldn't prepare its download folder.", retry = true)
                setJob(key, StoreJob.Downloading(null, 0, file.sizeBytes))
                var lastPercent = -1
                try {
                    try {
                        downloader.download(file.url, sink, file.sizeBytes, file.digest, installer.freeBytes()) { written, total ->
                            val percent = if (total != null) (written * 100 / total).toInt() else (written / (1024 * 1024)).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                setJob(key, StoreJob.Downloading(total?.let { (written.toFloat() / it).coerceIn(0f, 1f) }, written, total))
                            }
                        }
                    } catch (x: DownloadException) {
                        throw Failure(x.message ?: "The download failed.", retry = true)
                    }
                    setJob(key, StoreJob.Installing(waitingTurn = false))
                    val before = ctx.settings.value.store.installs[key]
                    val previous = before?.packageName
                    val replaces = previous != null && installer.exists(previous)
                    // The version being replaced is kept aside, to go back to; a failed update puts it straight back.
                    val kept = if (replaces) runCatching { installer.keepPrevious(e.name, previous!!) }.getOrNull() else null
                    val path = try {
                        installer.install(e.name, sink.path, file.name, kind, if (kept != null) null else previous)
                    } catch (x: CancellationException) {
                        kept?.let { k -> withContext(NonCancellable) { runCatching { installer.restorePrevious(k, null) } } }
                        throw x
                    } catch (x: Exception) {
                        val restored = kept?.let { k -> runCatching { installer.restorePrevious(k, null) }.isSuccess } == true
                        throw Failure((x.message ?: "Fuse couldn't put ${e.name} in place.") + if (restored) " The version you had is back in place." else "", retry = true)
                    }
                    val earlier = listOfNotNull(kept?.let { KeptVersion(it, before?.version, ctx.now()) }) + before?.kept.orEmpty().filter { installer.exists(it.path) }
                    remember(key, StoreInstall(path, release.version, file.url, 0, ctx.now(), earlier = pruneKept(earlier)))
                    refreshInstalled()
                    clearJob(key)
                    redetect()
                    if (inRound(key)) settle(key, null) else noticesFlow.tryEmit(if (replaces) "${e.name} is updated." else "${e.name} is installed.")
                    // Its settings for playing together, looked at again: a portable program's go with the version it replaced.
                    if (replaces) runCatching { afterUpdate(key) }.getOrNull()?.let { noticesFlow.tryEmit(it) }
                } finally {
                    sink.discard()
                }
            }
        } catch (x: CancellationException) {
            clearJob(key)
            settle(key, "It was stopped.")
            throw x
        } catch (x: Failure) {
            setJob(key, StoreJob.Failed(x.message ?: "It didn't work.", x.retry))
            settle(key, x.message ?: "It didn't work.")
        } catch (x: Exception) {
            setJob(key, StoreJob.Failed("Something went wrong installing ${e.name}. Try again.", retry = true))
            settle(key, "Something went wrong. Try it again from its page.")
        }
    }

    override fun uninstall(key: String) {
        if (mutable.value.jobs[key]?.active == true) return
        ctx.scope.launch {
            when (val step = removeOne(key, password = null, quiet = false)) {
                is Step.Password -> ask(PasswordAsk(key, entries[key]?.name ?: key, step.wrong))
                else -> Unit
            }
        }
    }

    override fun uninstallWith(key: String, password: String?) {
        val asked = mutable.value.password
        mutable.update { it.copy(password = null) }
        // The password was asked for an update (a Flatpak for every account), not a removal.
        if (asked?.update == true && asked.key == key) {
            if (password != null) ctx.scope.launch { updateOtherNow(key, password) } else settle(key, "It needs this computer's password, and none was given.")
            return
        }
        if (key == ALL) {
            val keys = pendingAll
            pendingAll = emptyList()
            if (password == null) keys.forEach(::clearJob) else ctx.scope.launch { uninstallMany(keys, password) }
            return
        }
        if (password == null) {
            clearJob(key)
            return
        }
        ctx.scope.launch {
            when (val step = removeOne(key, password, quiet = false)) {
                is Step.Password -> ask(PasswordAsk(key, entries[key]?.name ?: key, step.wrong))
                else -> Unit
            }
        }
    }

    override fun installAll() {
        val installed = mutable.value.installed.keys
        entries.values
            .filter { it.key !in installed && it.sourceFor(installer.host) != null }
            .forEach { install(it.key) }
    }

    override fun uninstallAll() {
        val keys = mutable.value.installed.keys.filter { mutable.value.jobs[it]?.active != true }
        if (keys.isEmpty()) return
        ctx.scope.launch { uninstallMany(keys, password = null) }
    }

    /** Removes [keys] one after another; the password, asked once when one needs it, does for the rest. */
    private suspend fun uninstallMany(keys: List<String>, password: String?) {
        var removed = 0
        for ((i, key) in keys.withIndex()) {
            when (val step = removeOne(key, password, quiet = true)) {
                Step.Done -> removed++
                is Step.Password -> {
                    pendingAll = keys.drop(i)
                    pendingAll.drop(1).forEach { setJob(it, StoreJob.Uninstalling) }
                    ask(PasswordAsk(ALL, if (pendingAll.size == 1) entries[key]?.name ?: key else "${pendingAll.size} programs", step.wrong))
                    if (removed > 0) noticesFlow.tryEmit(if (removed == 1) "1 program is removed." else "$removed programs are removed.")
                    return
                }
                Step.Failed -> Unit
            }
        }
        if (removed > 0) noticesFlow.tryEmit(if (removed == 1) "1 program is removed." else "$removed programs are removed.")
    }

    /**
     * Removes [key]: what Fuse put in place is deleted; a program found here otherwise (a Flatpak, an
     * AppImage) is removed as it was installed, with [password] when only an administrator can.
     */
    private suspend fun removeOne(key: String, password: String?, quiet: Boolean): Step {
        val name = entries[key]?.name ?: key
        val record = ctx.settings.value.store.installs[key]
        setJob(key, StoreJob.Uninstalling)
        if (record != null) {
            if (!installer.remove(record.packageName)) {
                setJob(key, StoreJob.Failed("Fuse couldn't remove $name. It may be open; close it and try again.", retry = false))
                return Step.Failed
            }
            forget(key)
        } else {
            val other = mutable.value.installed[key] ?: run { clearJob(key); return Step.Done }
            when (val r = installer.removeOther(other.packageName, name, password)) {
                RemoveOutcome.Removed -> Unit
                is RemoveOutcome.NeedsPassword -> return Step.Password(r.wrong)
                is RemoveOutcome.Failed -> {
                    setJob(key, StoreJob.Failed(r.message, retry = false))
                    return Step.Failed
                }
            }
        }
        gone += key
        refreshInstalled()
        clearJob(key)
        redetect()
        if (!quiet) noticesFlow.tryEmit("$name is removed.")
        return Step.Done
    }

    private fun ask(ask: PasswordAsk) = mutable.update { it.copy(password = ask) }

    /** Programs the Store is waiting to remove once the password is given (Uninstall all). */
    private var pendingAll: List<String> = emptyList()

    /** Programs removed this run that detection may still list for a moment. */
    private val gone = HashSet<String>()

    private sealed interface Step {
        data object Done : Step
        data object Failed : Step
        data class Password(val wrong: Boolean) : Step
    }

    // Apps the user adds

    /** The project at [url] with its newest release that has this computer's build, or why it can't be added. */
    private suspend fun linked(url: String): Result<Pair<ReleaseLink, DesktopRelease>> {
        val link = ReleaseLink.parse(url)
            ?: return Result.failure(IllegalArgumentException("That isn't a project address Fuse can read releases from: github.com/owner/project, or the same on a Forgejo or Gitea server."))
        val existing = ctx.settings.value.store.custom
        if (existing.any { it.url.equals(link.url, ignoreCase = true) } ||
            DesktopCatalogue.entries.any { it.repoFor(installer.host).equals(link.url, ignoreCase = true) || it.forge.equals(link.url, ignoreCase = true) }
        ) return Result.failure(IllegalArgumentException("It's already in the Store."))
        // It must be an app: a release with this computer's build.
        val found = when (link.kind) {
            ReleaseLink.Kind.GITHUB -> resolver.desktop(link.url, link.project, installer.host, installer.arch, prereleases = true)
            ReleaseLink.Kind.FORGE -> resolver.desktopForgejo(link.url, link.project, installer.host, installer.arch, prereleases = true)
        }
        return when {
            found is ApiResult.Failure -> Result.failure(IllegalStateException(friendly(found)))
            (found as ApiResult.Success).value.file == null ->
                Result.failure(IllegalStateException("${link.project} has no ${systemWord()} build in its releases, so it can't be installed here."))
            else -> Result.success(link to found.value)
        }
    }

    override suspend fun previewCustom(url: String): Result<CustomPreview> = linked(url).map { (link, r) ->
        val f = r.file!!
        CustomPreview(
            url = link.url, name = link.project, source = "${link.server}/${link.owner}/${link.project}",
            version = r.version, publishedAt = r.publishedAt,
            file = StoreFile(f.name, f.url, f.sizeBytes, f.digest),
            room = StoreRoom.line(f.sizeBytes, DesktopAssets.kindOf(f.name, installer.host), installer.freeBytes()),
        )
    }

    override suspend fun addCustom(url: String, category: String): String? {
        val (link, _) = linked(url).getOrElse { return it.message }
        val app = CustomStoreApp(link.url, link.project, category.ifBlank { AppStoreOps.OTHER })
        val next = ctx.data.settings.update { it.copy(store = it.store.copy(custom = it.store.custom + app)) }
        ctx.settings.value = next
        build()
        return null
    }

    override suspend fun removeCustom(key: String) {
        val url = entries[key]?.let { it.repo ?: it.forge } ?: return
        val next = ctx.data.settings.update { it.copy(store = it.store.copy(custom = it.store.custom.filterNot { c -> c.url.equals(url, ignoreCase = true) })) }
        ctx.settings.value = next
        build()
        refreshInstalled()
    }

    private suspend fun remember(key: String, install: StoreInstall) {
        val next = ctx.data.settings.update { it.copy(store = it.store.copy(installs = it.store.installs + (key to install))) }
        ctx.settings.value = next
    }

    private suspend fun forget(key: String) {
        val next = ctx.data.settings.update { it.copy(store = it.store.copy(installs = it.store.installs - key)) }
        ctx.settings.value = next
    }

    private fun setJob(key: String, job: StoreJob) = mutable.update { it.copy(jobs = it.jobs + (key to job)) }

    private fun clearJob(key: String) = mutable.update { it.copy(jobs = it.jobs - key) }

    private fun friendly(f: ApiResult.Failure): String = when (f) {
        is ApiResult.NetworkError -> "Fuse couldn't connect. Check the connection and try again."
        else -> f.message
    }

    /** Checks installed programs a while after Fuse starts, when the user wants that. */
    fun startAutomatic() {
        ctx.scope.launch {
            delay(AUTO_CHECK_DELAY_MS)
            if (ctx.settings.value.store.autoCheck) checkInstalled(force = false)
        }
    }

    private class Failure(message: String, val retry: Boolean) : Exception(message)

    private companion object {
        /** How many earlier versions of a program Fuse keeps to go back to. */
        const val KEPT_VERSIONS = 3
        const val MAX_CHECKS = 3
        const val MAX_NOTES = 4_000
        const val INSTALL_FRESH_MS = 10L * 60 * 1000
        const val LIMIT_PAUSE_MS = 15L * 60 * 1000
        const val AUTO_CHECK_DELAY_MS = 30_000L

        /** The password asked for Uninstall all, not one program. */
        const val ALL = "*all"
    }
}

/** The kind of file [DesktopAssetKind] names, for messages. */
internal fun DesktopAssetKind.word(): String = when (this) {
    DesktopAssetKind.APPIMAGE -> "AppImage"
    DesktopAssetKind.ZIP -> "zip"
    DesktopAssetKind.DMG -> "disk image"
    DesktopAssetKind.TAR -> "tarball"
    DesktopAssetKind.SEVEN_ZIP -> "7-Zip archive"
}

/**
 * Who looks after a program found on this computer (not put there by Fuse), from how it was found
 * ([appId], [detectedVia]): Flatpak, Linux's packages (under /usr, /bin, /opt or /snap), or the
 * person (an AppImage of their own, a program installed with its own installer). One EmuDeck
 * installed ([installedBy]) is EmuDeck's.
 */
internal fun managedOf(appId: String, detectedVia: String, installedBy: String? = null): Managed = when {
    installedBy == io.github.matiyaaa.fuse.launch.linux.LinuxSuites.EMUDECK -> Managed.EMUDECK
    detectedVia.contains("flatpak", ignoreCase = true) -> Managed.FLATPAK
    SYSTEM_PLACES.any { appId.startsWith(it) } -> Managed.SYSTEM
    else -> Managed.ELSEWHERE
}

private val SYSTEM_PLACES = listOf("/usr/", "/bin/", "/sbin/", "/opt/", "/snap/")
