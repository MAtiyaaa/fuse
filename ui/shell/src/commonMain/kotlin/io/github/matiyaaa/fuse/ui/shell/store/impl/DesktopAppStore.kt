package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.CustomStoreApp
import io.github.matiyaaa.fuse.data.settings.StoreInstall
import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssetKind
import io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssets
import io.github.matiyaaa.fuse.integrations.obtainium.DesktopRelease
import io.github.matiyaaa.fuse.integrations.obtainium.HtmlLinks
import io.github.matiyaaa.fuse.integrations.obtainium.PackResolver
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.StoreVariant
import io.github.matiyaaa.fuse.ui.shell.store.AppStoreOps
import io.github.matiyaaa.fuse.ui.shell.store.Availability
import io.github.matiyaaa.fuse.ui.shell.store.DesktopInstaller
import io.github.matiyaaa.fuse.ui.shell.store.InFuse
import io.github.matiyaaa.fuse.ui.shell.store.InstallRecord
import io.github.matiyaaa.fuse.ui.shell.store.InstalledApp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    /** Looks for emulators again, so a program just put in place is used at once. */
    private val redetect: () -> Unit = {},
) : AppStoreOps {
    override val supported: Boolean = true

    private var token: String? = null
    private val resolver = PackResolver(ctx.services.http, githubToken = { token })
    private val downloader = ApkDownloader(ctx.services.http)
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
        val all = (DesktopCatalogue.entries + custom).associateBy { it.key }
        entries = all
        val categories = all.values.map { it.category }.distinct()
            .sortedBy { listOf(DesktopCatalogue.EMULATORS, DesktopCatalogue.STREAMING, DesktopCatalogue.TOOLS).indexOf(it).let { i -> if (i < 0) 99 else i } }
        val catalogue = StoreCatalogue(
            variant = StoreVariant.STANDARD,
            apps = all.values.map(::storeApp),
            categories = categories.map { StoreCategory(it, DesktopCatalogue.colors[it] ?: DesktopCatalogue.colors[AppStoreOps.OTHER]) },
            packVersion = null,
            fetchedAt = ctx.now(),
            sourceUrl = "https://github.com/matiyaaa/fuse",
        )
        mutable.update { s -> s.copy(catalogue = catalogue, releases = s.releases.filterKeys { it in all }, jobs = s.jobs.filterKeys { it in all }) }
    }

    private fun customEntry(c: CustomStoreApp): DesktopEntry {
        val place = c.url.substringAfter("://").trimEnd('/').lowercase()
        return DesktopEntry(
            key = "custom@$place", name = c.name, author = c.url.substringAfter("github.com/").substringBefore('/'),
            about = "Added by you from ${c.url.substringAfter("://")}.", category = c.category, systems = emptyList(),
            repo = c.url, inFuse = InFuse.TOOL,
        )
    }

    private fun storeApp(e: DesktopEntry): StoreApp {
        val repo = e.repoFor(installer.host)
        val url = repo ?: e.page ?: ""
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
            availability = if (repo != null) Availability.INSTALLABLE else Availability.MANUAL,
            allowIdChange = false,
            systems = e.systems.map(::PlatformId),
            color = DesktopCatalogue.colors[e.category] ?: DesktopCatalogue.colors[AppStoreOps.OTHER],
            inFuse = e.inFuse,
            custom = e.key.startsWith("custom@"),
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
        for (e in entries.values) {
            val rec = records[e.key]
            if (rec != null && installer.exists(rec.packageName)) {
                found[e.key] = InstalledApp(rec.packageName, rec.version, rec.versionCode, e.name, InstallRecord(rec.version, rec.file, rec.versionCode))
                continue
            }
            val other = e.emulator?.let { detected["$prefix$it"] } ?: continue
            // Just removed: detection catches up a moment later.
            if (e.key in gone) continue
            found[e.key] = InstalledApp(other.appId, other.version, 0, other.name)
        }
        mutable.update { it.copy(installed = found) }
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
        if (repo == null) {
            // Built elsewhere: the release is the program's page.
            val r = StoreRelease(null, null, null, e.page ?: "", null, "${e.name} publishes its builds on its own site. Download it there, and Fuse finds it.")
            mutable.update { s -> s.copy(releases = s.releases + (key to ReleaseCheck.Ready(r, ctx.now()))) }
            return r
        }
        val known = mutable.value.releases[key]
        if (!force && known is ReleaseCheck.Ready && !known.stale && ctx.now() - known.checkedAt < freshFor) return known.release
        if (ctx.now() < gitHubLimitedUntil) return failed(key, "GitHub's limit for checks is used up for now.", gitHubLimitedUntil)
        if (known !is ReleaseCheck.Ready) mutable.update { s -> s.copy(releases = s.releases + (key to ReleaseCheck.Checking)) }
        return when (val result = checks.withPermit { resolver.desktop(repo, e.name, installer.host, installer.arch, prereleases = e.prereleases) }) {
            is ApiResult.Success -> {
                val r = release(e, result.value)
                mutable.update { s -> s.copy(releases = s.releases + (key to ReleaseCheck.Ready(r, ctx.now()))) }
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
        manual = if (r.file == null) "${e.name}'s newest release has no ${systemWord()} build Fuse can use as published. Its page has every download." else null,
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
        mutable.value.updates.forEach { install(it.key) }
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
                val file = release.file ?: throw Failure(release.manual ?: "${e.name} has nothing Fuse can install here.", retry = false)
                val kind = DesktopAssets.kindOf(file.name, installer.host) ?: throw Failure("${e.name}'s download isn't something Fuse can put in place.", retry = false)
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
                    val previous = ctx.settings.value.store.installs[key]?.packageName
                    val replaces = previous != null && installer.exists(previous)
                    val path = try {
                        installer.install(e.name, sink.path, file.name, kind, previous)
                    } catch (x: CancellationException) {
                        throw x
                    } catch (x: Exception) {
                        throw Failure(x.message ?: "Fuse couldn't put ${e.name} in place.", retry = true)
                    }
                    remember(key, StoreInstall(path, release.version, file.url, 0, ctx.now()))
                    refreshInstalled()
                    clearJob(key)
                    redetect()
                    noticesFlow.tryEmit(if (replaces) "${e.name} is updated." else "${e.name} is installed.")
                } finally {
                    sink.discard()
                }
            }
        } catch (x: CancellationException) {
            clearJob(key)
            throw x
        } catch (x: Failure) {
            setJob(key, StoreJob.Failed(x.message ?: "It didn't work.", x.retry))
        } catch (x: Exception) {
            setJob(key, StoreJob.Failed("Something went wrong installing ${e.name}. Try again.", retry = true))
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
        mutable.update { it.copy(password = null) }
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
            .filter { it.key !in installed && it.repoFor(installer.host) != null && it.page == null }
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

    override suspend fun addCustom(url: String, category: String): String? {
        val clean = url.trim().removeSuffix("/").removeSuffix(".git").let { if (it.startsWith("github.com/")) "https://$it" else it }
        val match = GITHUB.matchEntire(clean) ?: return "That isn't a GitHub repository address (github.com/owner/project)."
        val canonical = "https://github.com/${match.groupValues[1]}/${match.groupValues[2]}"
        val existing = ctx.settings.value.store.custom
        if (existing.any { it.url.equals(canonical, ignoreCase = true) } || DesktopCatalogue.entries.any { it.repoFor(installer.host).equals(canonical, ignoreCase = true) }) {
            return "It's already in the Store."
        }
        val name = match.groupValues[2]
        // It must be an app: a release with this computer's build.
        val found = resolver.desktop(canonical, name, installer.host, installer.arch, prereleases = true)
        when {
            found is ApiResult.Failure -> return friendly(found)
            (found as ApiResult.Success).value.file == null -> return "$name has no ${systemWord()} build in its releases, so it can't be installed here."
        }
        val app = CustomStoreApp(canonical, name, category.ifBlank { AppStoreOps.OTHER })
        val next = ctx.data.settings.update { it.copy(store = it.store.copy(custom = it.store.custom + app)) }
        ctx.settings.value = next
        build()
        return null
    }

    override suspend fun removeCustom(key: String) {
        val url = entries[key]?.repo ?: return
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
        const val MAX_CHECKS = 3
        const val MAX_NOTES = 4_000
        const val INSTALL_FRESH_MS = 10L * 60 * 1000
        const val LIMIT_PAUSE_MS = 15L * 60 * 1000
        const val AUTO_CHECK_DELAY_MS = 30_000L
        val GITHUB = Regex("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$")

        /** The password asked for Uninstall all, not one program. */
        const val ALL = "*all"
    }
}

/** The kind of file [DesktopAssetKind] names, for messages. */
internal fun DesktopAssetKind.word(): String = when (this) {
    DesktopAssetKind.APPIMAGE -> "AppImage"
    DesktopAssetKind.ZIP -> "zip"
    DesktopAssetKind.DMG -> "disk image"
}
