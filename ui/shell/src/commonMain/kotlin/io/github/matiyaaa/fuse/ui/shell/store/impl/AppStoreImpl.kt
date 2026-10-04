package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.obtainium.AppIconFinder
import io.github.matiyaaa.fuse.ui.shell.store.InFuse
import io.github.matiyaaa.fuse.model.AppKind
import io.github.matiyaaa.fuse.model.KnownApps
import io.github.matiyaaa.fuse.data.settings.StoreInstall
import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.obtainium.ApkChoice
import io.github.matiyaaa.fuse.integrations.obtainium.HtmlLinks
import io.github.matiyaaa.fuse.integrations.obtainium.Pack
import io.github.matiyaaa.fuse.integrations.obtainium.PackApp
import io.github.matiyaaa.fuse.integrations.obtainium.PackDocument
import io.github.matiyaaa.fuse.integrations.obtainium.PackFetcher
import io.github.matiyaaa.fuse.integrations.obtainium.PackResolver
import io.github.matiyaaa.fuse.integrations.obtainium.PackSourceKind
import io.github.matiyaaa.fuse.launch.android.AndroidEmulatorCatalog
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.StoreVariant
import io.github.matiyaaa.fuse.ui.shell.store.AppStoreOps
import io.github.matiyaaa.fuse.ui.shell.store.ArchiveInfo
import io.github.matiyaaa.fuse.ui.shell.store.DownloadSink
import io.github.matiyaaa.fuse.ui.shell.store.Availability
import io.github.matiyaaa.fuse.ui.shell.store.InstallRecord
import io.github.matiyaaa.fuse.ui.shell.store.InstalledApp
import io.github.matiyaaa.fuse.ui.shell.store.PackageBridge
import io.github.matiyaaa.fuse.ui.shell.store.PackageOutcome
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseCheck
import io.github.matiyaaa.fuse.ui.shell.store.SourceKind
import io.github.matiyaaa.fuse.ui.shell.store.StoreApp
import io.github.matiyaaa.fuse.ui.shell.store.StoreCatalogue
import io.github.matiyaaa.fuse.ui.shell.store.StoreCategory
import io.github.matiyaaa.fuse.ui.shell.store.StoreFile
import io.github.matiyaaa.fuse.ui.shell.store.StoreJob
import io.github.matiyaaa.fuse.ui.shell.store.StoreRelease
import io.github.matiyaaa.fuse.ui.shell.store.StoreState
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/**
 * The Store on a device that can install apps ([PackageBridge]). It keeps the catalogue of the
 * chosen pack edition (cached, so it opens offline), what Android has installed, what each app's
 * newest release is, and every install, update and uninstall in progress. Jobs run in the store's
 * own scope, so they carry on while the user is elsewhere in Fuse.
 */
internal class DefaultAppStoreOps(
    private val ctx: StoreContext,
    private val bridge: PackageBridge,
    private val prefs: StateFlow<UiPrefs>,
    private val updatePrefs: suspend ((UiPrefs) -> UiPrefs) -> Unit,
) : AppStoreOps {
    override val supported: Boolean = true

    private val cache = ctx.data.cache
    private var fetcher = fetcherFor(ctx.settings.value.store.packRepo)
    private var token: String? = null
    private val resolver = PackResolver(ctx.services.http, githubToken = { token })
    private val downloader = ApkDownloader(ctx.services.http)
    private val iconFinder = AppIconFinder(ctx.services.http)
    private var iconJob: Job? = null
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val mutable = MutableStateFlow(
        StoreState(
            packRepo = ctx.settings.value.store.packRepo,
            variant = prefs.value.storeVariant,
            recommended = if (bridge.hasSecondScreen) StoreVariant.DUAL_SCREEN else StoreVariant.STANDARD,
            canInstall = bridge.canInstall(),
        ),
    )
    override val state: StateFlow<StoreState> = mutable.asStateFlow()
    private val noticesFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val notices: SharedFlow<String> = noticesFlow

    /** The pack as parsed, by key: what the resolver needs. */
    @kotlin.concurrent.Volatile private var packApps: Map<String, PackApp> = emptyMap()
    private val records = MutableStateFlow(ctx.settings.value.store.installs)
    private val running = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val checking = MutableStateFlow<Set<String>>(emptySet())
    private val loadLock = Mutex()
    private val fetching = Mutex()
    private val downloads = Semaphore(MAX_DOWNLOADS)
    private val checks = Semaphore(MAX_CHECKS)
    private val installer = Mutex()
    private val permission = MutableStateFlow(bridge.canInstall())

    /** GitHub said its limit is used up until then (epoch millis); GitHub checks wait till then. */
    @kotlin.concurrent.Volatile private var gitHubLimitedUntil = 0L
    private var lastAutoCheck = 0L

    fun start() {
        ctx.resumeHooks.update { it + ::onResume }
        ctx.scope.launch {
            token = ctx.services.secrets.get(TOKEN_KEY)
            mutable.update { it.copy(hasGitHubToken = token != null) }
            // The edition can change from Settings, a restore, or the first-run choice.
            prefs.map { it.storeVariant }.distinctUntilChanged().collect { variant ->
                mutable.update { it.copy(variant = variant) }
                if (variant != null) load(variant, fetchWhenStale = false)
            }
        }
        ctx.scope.launch {
            bridge.changes.collect { refreshInstalled() }
        }
    }

    /** What the Store does by itself (held back in safe mode): installed apps are checked for updates. */
    fun startAutomatic() {
        ctx.scope.launch {
            delay(AUTO_CHECK_DELAY_MS)
            autoCheck()
        }
    }

    override fun open() {
        val variant = mutable.value.variant ?: return
        ctx.scope.launch {
            load(variant, fetchWhenStale = true)
            refreshInstalled()
            checkInstalled(force = false)
        }
    }

    override fun refresh() {
        val variant = mutable.value.variant ?: return
        ctx.scope.launch { fetch(variant) }
    }

    override suspend fun chooseVariant(variant: StoreVariant) {
        if (prefs.value.storeVariant == variant) return
        updatePrefs { it.copy(storeVariant = variant) }
    }

    override fun onResume() {
        val allowed = bridge.canInstall()
        permission.value = allowed
        mutable.update { it.copy(canInstall = allowed) }
        ctx.scope.launch { refreshInstalled() }
    }

    override fun allowInstalls() = bridge.requestInstallPermission()

    /** The installed app's own icon, else the icon it publishes (looked up once, see [findIcons]). */
    override fun iconModel(key: String): Any? =
        mutable.value.installed[key]?.packageName?.let(bridge::iconModel) ?: mutable.value.icons[key]

    /**
     * Looks up the icon each app publishes, a few at a time, once per app (the answer, or that there
     * is none, is kept for weeks). Icons appear as they are found.
     */
    private fun findIcons(apps: Map<String, PackApp>) {
        iconJob?.cancel()
        iconJob = ctx.scope.launch {
            val known = HashMap<String, String>()
            val missing = ArrayList<Pair<String, PackApp>>()
            for ((key, app) in apps) {
                when (val cached = cache.entry(ICONS, app.url)?.takeUnless { it.isExpired(ctx.now()) }?.valueJson) {
                    null -> missing += key to app
                    "\"\"" -> Unit
                    else -> known[key] = cached.trim('"')
                }
            }
            if (known.isNotEmpty()) mutable.update { it.copy(icons = it.icons + known) }
            val lookups = Semaphore(ICON_LOOKUPS)
            kotlinx.coroutines.coroutineScope {
                for ((key, app) in missing) launch {
                    lookups.withPermit {
                        val found = runCatching { iconFinder.find(app.url) }.getOrNull()
                        cache.put(ICONS, app.url, "\"${found.orEmpty()}\"", ctx.now(), if (found != null) ICON_FOUND_MS else ICON_NONE_MS)
                        if (found != null) mutable.update { it.copy(icons = it.icons + (key to found)) }
                    }
                }
            }
        }
    }

    override fun launch(key: String): Boolean =
        mutable.value.installed[key]?.packageName?.let(bridge::launch) ?: false

    override suspend fun setGitHubToken(token: String?) {
        val clean = token?.trim()?.takeIf { it.isNotEmpty() }
        if (clean == null) ctx.services.secrets.remove(TOKEN_KEY) else ctx.services.secrets.put(TOKEN_KEY, clean)
        this.token = clean
        gitHubLimitedUntil = 0
        mutable.update { it.copy(hasGitHubToken = clean != null) }
    }

    // The catalogue

    /** Shows [variant]'s cached catalogue, and fetches a fresh one when there is none or ([fetchWhenStale]) it is old. */
    private suspend fun load(variant: StoreVariant, fetchWhenStale: Boolean) {
        val shown = mutable.value.catalogue
        if (shown == null || shown.variant != variant) {
            loadLock.withLock {
                val cached = runCatching { cache.get(CATALOGUE, variant.name, CachedCatalogue.serializer(), ctx.now()) }.getOrNull()
                val pack = cached?.let { PackDocument.parse(it.text).getOrNull() }
                if (cached != null && pack != null) show(variant, pack, cached.version, cached.fetchedAt, cached.sourceUrl)
                else if (mutable.value.catalogue?.variant != variant) {
                    packApps = emptyMap()
                    mutable.update { it.copy(catalogue = null, releases = emptyMap()) }
                }
            }
            refreshInstalled()
        }
        val now = mutable.value.catalogue
        if (now == null || now.variant != variant || (fetchWhenStale && ctx.now() - now.fetchedAt > CATALOGUE_FRESH_MS)) fetch(variant)
    }

    private suspend fun fetch(variant: StoreVariant) {
        // One download of the catalogue at a time; a second ask while one runs is the same ask.
        if (!fetching.tryLock()) return
        try {
            fetchLocked(variant)
        } finally {
            mutable.update { it.copy(refreshing = false) }
            fetching.unlock()
        }
    }

    private suspend fun fetchLocked(variant: StoreVariant) {
        mutable.update { it.copy(refreshing = true) }
        val result = fetcher.fetch(variant)
        when (result) {
            is ApiResult.Success -> {
                val f = result.value
                val now = ctx.now()
                runCatching {
                    cache.put(CATALOGUE, variant.name, CachedCatalogue(f.text, f.version, now, f.sourceUrl), CachedCatalogue.serializer(), now, null)
                }
                // The edition may have changed while this one downloaded.
                if (mutable.value.variant == variant) {
                    show(variant, f.pack, f.version, now, f.sourceUrl)
                    mutable.update { it.copy(refreshProblem = null) }
                    refreshInstalled()
                }
            }
            is ApiResult.Failure -> mutable.update { it.copy(refreshProblem = friendly(result)) }
        }
        mutable.update { it.copy(refreshing = false) }
    }

    private suspend fun show(variant: StoreVariant, pack: Pack, version: String?, fetchedAt: Long, sourceUrl: String) {
        lastPack = PackShown(variant, pack, version, fetchedAt, sourceUrl)
        val byKey = LinkedHashMap<String, PackApp>()
        for (app in pack.apps) byKey[keyOf(app)] = app
        // Apps the user added follow the pack's, under their category.
        val custom = ctx.settings.value.store.custom.map(::customApp)
        for (app in custom) byKey.getOrPut(keyOf(app)) { app }
        val categories = pack.categories.map { StoreCategory(it.name, it.color) } +
            custom.flatMap { it.categories }.distinct().filter { c -> pack.categories.none { it.name.equals(c, ignoreCase = true) } }.map { StoreCategory(it, OTHER_COLOR) }
        val colors = categories.associate { it.name to it.color }
        val apps = byKey.map { (key, app) -> storeApp(key, app, colors) }
        val previous = packApps
        packApps = byKey
        val catalogue = StoreCatalogue(variant, apps, categories, version, fetchedAt, sourceUrl)
        // Releases already known stay; apps gone from the edition lose theirs and their jobs.
        mutable.update { s -> s.copy(catalogue = catalogue, releases = s.releases.filterKeys { it in byKey }) }
        for (key in previous.keys - byKey.keys) {
            val job = running.value[key] ?: continue
            job.cancel()
            noticesFlow.tryEmit("${previous[key]?.name ?: "A download"} stopped: it isn't in this edition of the Store.")
        }
        mutable.update { s -> s.copy(jobs = s.jobs.filterKeys { it in byKey }) }
        findIcons(byKey)
        // Cached releases appear at once, without a request.
        for (key in byKey.keys) if (key !in mutable.value.releases) cachedRelease(key)?.let { (r, at) -> mutable.update { s -> s.copy(releases = s.releases + (key to ReleaseCheck.Ready(r, at))) } }
    }

    private fun storeApp(key: String, app: PackApp, colors: Map<String, Long?>): StoreApp {
        val record = records.value[key]
        val pkg = app.packageName ?: record?.packageName
        return StoreApp(
            key = key,
            id = app.id,
            packageName = pkg,
            pinned = app.packageName == null && record != null,
            name = app.name,
            author = app.author,
            about = app.about,
            categories = app.categories,
            sourceUrl = app.url,
            sourceHost = HtmlLinks.hostOf(app.url)?.removePrefix("www.") ?: app.url,
            sourceKind = when (app.source) {
                PackSourceKind.GITHUB -> SourceKind.GITHUB
                PackSourceKind.HTML -> SourceKind.WEB
                PackSourceKind.OTHER -> SourceKind.OTHER
            },
            availability = when {
                app.rules.trackOnly -> Availability.TRACK_ONLY
                app.source == PackSourceKind.OTHER -> Availability.MANUAL
                else -> Availability.INSTALLABLE
            },
            allowIdChange = app.allowIdChange,
            systems = systemsOf(app, pkg),
            color = app.categories.firstNotNullOfOrNull { colors[it] },
            inFuse = inFuseOf(app, pkg),
            custom = app.id == CUSTOM_ID,
        )
    }

    // Apps the user adds, and the catalogue's repository

    /** The pack last shown, so an added app can join it without fetching again. */
    private data class PackShown(val variant: StoreVariant, val pack: Pack, val version: String?, val fetchedAt: Long, val sourceUrl: String)

    @kotlin.concurrent.Volatile private var lastPack: PackShown? = null

    /** An app the user added, as the pack would list it: its GitHub releases, any APK that suits the device. */
    private fun customApp(c: io.github.matiyaaa.fuse.data.settings.CustomStoreApp) = PackApp(
        id = CUSTOM_ID,
        url = c.url,
        name = c.name,
        author = c.url.substringAfter("github.com/").substringBefore('/'),
        categories = listOf(c.category),
        source = PackSourceKind.GITHUB,
        allowIdChange = true,
        preferredApkIndex = null,
        rules = io.github.matiyaaa.fuse.integrations.obtainium.PackRules(about = "Added by you from ${c.url.substringAfter("://")}.", fallbackToOlderReleases = true),
    )

    override suspend fun addCustom(url: String, category: String): String? {
        val clean = url.trim().removeSuffix("/").removeSuffix(".git").let { if (it.startsWith("github.com/")) "https://$it" else it }
        val match = GITHUB_REPO.matchEntire(clean) ?: return "That isn't a GitHub repository address (github.com/owner/project)."
        val canonical = "https://github.com/${match.groupValues[1]}/${match.groupValues[2]}"
        if (ctx.settings.value.store.custom.any { it.url.equals(canonical, ignoreCase = true) } || packApps.values.any { it.url.trimEnd('/').equals(canonical, ignoreCase = true) }) {
            return "It's already in the Store."
        }
        val app = customApp(io.github.matiyaaa.fuse.data.settings.CustomStoreApp(canonical, match.groupValues[2], category.ifBlank { AppStoreOps.OTHER }))
        // It must be an app this device can install: a release with an APK for it.
        when (val r = resolver.resolve(app, bridge.abis)) {
            is ApiResult.Failure -> return friendly(r)
            is ApiResult.Success -> if (r.value.choice !is ApkChoice.Install) return "${app.name} has no Android app in its releases, so it can't be added."
        }
        val next = ctx.data.settings.update { it.copy(store = it.store.copy(custom = it.store.custom + io.github.matiyaaa.fuse.data.settings.CustomStoreApp(canonical, app.name, app.categories.first()))) }
        ctx.settings.value = next
        lastPack?.let { show(it.variant, it.pack, it.version, it.fetchedAt, it.sourceUrl) }
        refreshInstalled()
        return null
    }

    override suspend fun removeCustom(key: String) {
        val url = packApps[key]?.takeIf { it.id == CUSTOM_ID }?.url ?: return
        val next = ctx.data.settings.update { it.copy(store = it.store.copy(custom = it.store.custom.filterNot { c -> c.url.equals(url, ignoreCase = true) })) }
        ctx.settings.value = next
        lastPack?.let { show(it.variant, it.pack, it.version, it.fetchedAt, it.sourceUrl) }
    }

    override suspend fun setPackRepo(url: String?): String? {
        val repo = url?.trim()?.removeSuffix("/")?.removeSuffix(".git")?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("github.com/")) "https://$it" else it }
        if (repo != null && GITHUB_REPO.matchEntire(repo) == null) return "That isn't a GitHub repository address (github.com/owner/project)."
        val variant = mutable.value.variant ?: StoreVariant.STANDARD
        val candidate = fetcherFor(repo)
        // Only a repository that really publishes a readable catalogue replaces the one in use.
        val fetched = candidate.fetch(variant)
        if (fetched is ApiResult.Failure) return "No catalogue there Fuse can read: ${friendly(fetched)}"
        fetcher = candidate
        val next = ctx.data.settings.update { it.copy(store = it.store.copy(packRepo = repo)) }
        ctx.settings.value = next
        mutable.update { it.copy(packRepo = repo) }
        fetch(variant)
        return null
    }

    /** The catalogue's downloader for [repo], or for the Obtainium Emulation Pack when null. */
    private fun fetcherFor(repo: String?): PackFetcher {
        val match = repo?.let { GITHUB_REPO.matchEntire(it) } ?: return PackFetcher(ctx.services.http)
        return PackFetcher(ctx.services.http, webUrl = repo, rawUrl = "https://raw.githubusercontent.com/${match.groupValues[1]}/${match.groupValues[2]}")
    }

    /** What the app plays, from Fuse's own emulator catalogue (by package, else by its name). */
    private fun systemsOf(app: PackApp, pkg: String?): List<PlatformId> =
        defsOf(app, pkg).flatMap { it.platforms }.distinct().sortedBy { it.value }

    private fun defsOf(app: PackApp, pkg: String?): List<io.github.matiyaaa.fuse.launch.android.AndroidEmulatorDef> {
        val defs = AndroidEmulatorCatalog.defs
        val byPackage = pkg?.let { p -> defs.filter { p in it.packages } }.orEmpty()
        return byPackage.ifEmpty {
            val base = app.name.substringBefore(" (").trim()
            defs.filter { it.name.equals(base, ignoreCase = true) }
        }
    }

    /** What Fuse does with [app] once installed: from its emulator entries, else from what Apps knows of it. */
    private fun inFuseOf(app: PackApp, pkg: String?): InFuse {
        val defs = defsOf(app, pkg)
        if (defs.isNotEmpty()) return if (defs.any { it.modes.isNotEmpty() }) InFuse.LAUNCHES_GAMES else InFuse.OPENS_APP
        return when (pkg?.let(KnownApps::kindOf)) {
            AppKind.STREAMING -> InFuse.STREAMING
            AppKind.TOOL -> InFuse.TOOL
            else -> if (app.rules.trackOnly) InFuse.NOTHING else InFuse.TOOL
        }
    }

    // What is installed

    private suspend fun refreshInstalled() {
        val apps = mutable.value.catalogue?.apps ?: return
        val recs = records.value
        val found = LinkedHashMap<String, InstalledApp>()
        for (app in apps) {
            val pkg = app.packageName ?: continue
            val p = runCatching { bridge.installed(pkg) }.getOrNull() ?: continue
            val rec = recs[app.key]?.takeIf { it.packageName == pkg }
            found[app.key] = InstalledApp(p.packageName, p.versionName, p.versionCode, p.label, rec?.let { InstallRecord(it.version, it.file, it.versionCode) })
        }
        mutable.update { it.copy(installed = found) }
    }

    // Releases

    override fun check(key: String, force: Boolean) {
        ctx.scope.launch { checkNow(key, force) }
    }

    override fun checkInstalled(force: Boolean) {
        val keys = mutable.value.installed.keys.toList()
        ctx.scope.launch { keys.forEach { key -> launch { checkNow(key, force) } } }
    }

    private suspend fun autoCheck() {
        if (!prefs.value.storeAutoCheck || mutable.value.variant == null) return
        if (ctx.now() - lastAutoCheck < AUTO_CHECK_EVERY_MS) return
        lastAutoCheck = ctx.now()
        refreshInstalled()
        mutable.value.installed.keys.forEach { key -> ctx.scope.launch { checkNow(key, force = false) } }
    }

    /** The newest release of [key], from the cache when it is fresh, else from its source. */
    private suspend fun checkNow(key: String, force: Boolean, freshFor: Long = RELEASE_FRESH_MS): StoreRelease? {
        val app = packApps[key] ?: return null
        val known = mutable.value.releases[key]
        if (!force && known is ReleaseCheck.Ready && !known.stale && ctx.now() - known.checkedAt < freshFor) return known.release
        if (!force) cachedRelease(key)?.let { (r, at) ->
            if (ctx.now() - at < freshFor) {
                mutable.update { s -> s.copy(releases = s.releases + (key to ReleaseCheck.Ready(r, at))) }
                return r
            }
        }
        if (!checking.compareAndSetAdd(key)) {
            // Another look is under way; wait for it.
            checking.first { key !in it }
            return (mutable.value.releases[key] as? ReleaseCheck.Ready)?.release
        }
        try {
            if (app.source == PackSourceKind.GITHUB && ctx.now() < gitHubLimitedUntil) {
                return failed(key, "GitHub's limit for checks is used up for now.", gitHubLimitedUntil)
            }
            if (known !is ReleaseCheck.Ready) mutable.update { s -> s.copy(releases = s.releases + (key to ReleaseCheck.Checking)) }
            val result = checks.withPermit { resolver.resolve(app, bridge.abis) }
            return when (result) {
                is ApiResult.Success -> {
                    val r = release(result.value)
                    val now = ctx.now()
                    runCatching { cache.put(RELEASES, key, CachedRelease.of(r), CachedRelease.serializer(), now, RELEASE_KEEP_MS) }
                    mutable.update { s -> s.copy(releases = s.releases + (key to ReleaseCheck.Ready(r, now))) }
                    r
                }
                is ApiResult.Failure -> {
                    val retryAt = (result as? ApiResult.RateLimited)?.retryAfterSeconds?.let { it * 1000 }
                    if (result is ApiResult.RateLimited && app.source == PackSourceKind.GITHUB) gitHubLimitedUntil = retryAt ?: (ctx.now() + LIMIT_PAUSE_MS)
                    failed(key, friendly(result), retryAt)
                }
            }
        } finally {
            checking.update { it - key }
        }
    }

    /** A failed look: the last known release stays shown (marked stale) when there is one. */
    private suspend fun failed(key: String, message: String, retryAt: Long?): StoreRelease? {
        val known = mutable.value.releases[key] as? ReleaseCheck.Ready ?: cachedRelease(key)?.let { (r, at) -> ReleaseCheck.Ready(r, at) }
        mutable.update { s -> s.copy(releases = s.releases + (key to (known?.copy(stale = true) ?: ReleaseCheck.Failed(message, retryAt)))) }
        return null
    }

    private suspend fun cachedRelease(key: String): Pair<StoreRelease, Long>? {
        val entry = runCatching { cache.entry(RELEASES, key) }.getOrNull() ?: return null
        val cached = runCatching { json.decodeFromString(CachedRelease.serializer(), entry.valueJson) }.getOrNull() ?: return null
        return cached.toRelease() to entry.fetchedAt
    }

    private fun release(u: io.github.matiyaaa.fuse.integrations.obtainium.UpstreamRelease) = StoreRelease(
        version = u.version,
        publishedAt = u.publishedAt,
        notes = u.notes?.take(MAX_NOTES),
        pageUrl = u.pageUrl,
        file = (u.choice as? ApkChoice.Install)?.apk?.let { StoreFile(it.name, it.url, it.sizeBytes, it.digest) },
        manual = (u.choice as? ApkChoice.Manual)?.reason,
    )

    // Installing

    override fun install(key: String) {
        if (mutable.value.jobs[key]?.active == true) return
        val app = packApps[key] ?: return
        setJob(key, StoreJob.Waiting)
        val job = ctx.scope.launch(start = CoroutineStart.LAZY) { runInstall(key, app) }
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

    private suspend fun runInstall(key: String, app: PackApp) {
        val name = app.name
        try {
            val (sink, archive, release, replaces) = downloads.withPermit { prepare(key, app) }
            try {
                if (installer.isLocked) setJob(key, StoreJob.Installing(waitingTurn = true))
                val outcome = installer.withLock {
                    setJob(key, StoreJob.Installing(waitingTurn = false))
                    bridge.install(sink.path) {}
                }
                when (outcome) {
                    PackageOutcome.Done -> {
                        remember(key, StoreInstall(archive.packageName, release.version, release.file?.url, archive.versionCode, ctx.now()))
                        refreshInstalled()
                        clearJob(key)
                        // Decided before Android installed it: its package broadcast can arrive first.
                        noticesFlow.tryEmit(if (replaces) "$name is updated." else "$name is installed.")
                    }
                    PackageOutcome.Cancelled -> clearJob(key)
                    is PackageOutcome.Failed -> setJob(
                        key,
                        StoreJob.Failed(
                            if (outcome.conflict) "$name is already installed from somewhere else, signed with a different key. Uninstall it first to install this one." else outcome.message,
                            retry = !outcome.conflict,
                            uninstallFirst = outcome.conflict,
                        ),
                    )
                }
            } finally {
                // Android's installer keeps its own copy: the download goes now.
                sink.discard()
            }
        } catch (e: CancellationException) {
            clearJob(key)
            throw e
        } catch (e: StoreFailure) {
            setJob(key, StoreJob.Failed(e.message ?: "It didn't work.", e.retry))
        } catch (e: Exception) {
            setJob(key, StoreJob.Failed("Something went wrong installing $name. Try again.", retry = true))
        }
    }

    /** What [prepare] made ready: the file, what Android read in it, its release, and whether it replaces an installed build. */
    private data class Prepared(val sink: DownloadSink, val archive: ArchiveInfo, val release: StoreRelease, val replaces: Boolean)

    /** Finds, downloads and verifies [app]'s newest APK. */
    private suspend fun prepare(key: String, app: PackApp): Prepared {
        setJob(key, StoreJob.Resolving)
        val release = checkNow(key, force = false, freshFor = INSTALL_FRESH_MS)
            ?: throw StoreFailure((mutable.value.releases[key] as? ReleaseCheck.Failed)?.message ?: "Fuse couldn't find ${app.name}'s newest release.", retry = true)
        val file = release.file ?: throw StoreFailure(release.manual ?: "${app.name} has nothing Fuse can install.", retry = false)
        if (!HtmlLinks.isHttps(file.url)) throw StoreFailure("The download isn't offered over a secure (HTTPS) address, so Fuse won't install it.", retry = false)
        if (!permission.value) {
            setJob(key, StoreJob.NeedsPermission)
            bridge.requestInstallPermission()
            permission.first { it }
        }
        val sink = bridge.newDownload(file.name) ?: throw StoreFailure("Fuse couldn't prepare its download folder.", retry = true)
        setJob(key, StoreJob.Downloading(null, 0, file.sizeBytes))
        var lastPercent = -1
        try {
            downloader.download(file.url, sink, file.sizeBytes, file.digest, bridge.freeBytes(), app.rules.requestHeaders) { written, total ->
                val percent = if (total != null) (written * 100 / total).toInt() else (written / (1024 * 1024)).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    setJob(key, StoreJob.Downloading(total?.let { (written.toFloat() / it).coerceIn(0f, 1f) }, written, total))
                }
            }
        } catch (e: DownloadException) {
            throw StoreFailure(e.message ?: "The download failed.", retry = true)
        }
        try {
            setJob(key, StoreJob.Verifying)
            val archive = bridge.inspect(sink.path)
                ?: throw StoreFailure("The download isn't an app Android can read, so it wasn't installed.", retry = true)
            val expected = expectedPackage(key, app)
            if (expected != null && archive.packageName != expected) {
                throw StoreFailure("The download is a different app (${archive.packageName}) than the Store lists ($expected), so it wasn't installed.", retry = false)
            }
            val current = bridge.installed(archive.packageName)
            if (current != null && current.versionCode > archive.versionCode) {
                throw StoreFailure("A newer build of ${app.name} is already installed (${current.versionName ?: "version ${current.versionCode}"}).", retry = false)
            }
            return Prepared(sink, archive, release, replaces = current != null)
        } catch (t: Throwable) {
            sink.discard()
            throw t
        }
    }

    /**
     * The package the download must be. The pack's id when it is a package name (unless the pack
     * lets the package differ); for an app the pack lists by a generated id, the package Fuse saw
     * it install as the first time, so an update is always the same app.
     */
    private fun expectedPackage(key: String, app: PackApp): String? {
        val pinned = records.value[key]?.packageName
        val listed = app.packageName
        return when {
            listed != null && !app.allowIdChange -> listed
            pinned != null -> pinned
            else -> listed?.takeIf { !app.allowIdChange }
        }
    }

    override fun uninstall(key: String) {
        val pkg = mutable.value.installed[key]?.packageName ?: return
        if (mutable.value.jobs[key]?.active == true) return
        val name = mutable.value.app(key)?.name ?: pkg
        setJob(key, StoreJob.Uninstalling)
        val job = ctx.scope.launch {
            try {
                val outcome = installer.withLock { bridge.uninstall(pkg) }
                when (outcome) {
                    PackageOutcome.Done -> {
                        forget(key)
                        refreshInstalled()
                        clearJob(key)
                        noticesFlow.tryEmit("$name is uninstalled.")
                    }
                    PackageOutcome.Cancelled -> clearJob(key)
                    is PackageOutcome.Failed -> setJob(key, StoreJob.Failed(outcome.message, retry = false))
                }
            } catch (e: CancellationException) {
                clearJob(key)
                throw e
            }
        }
        running.update { it + (key to job) }
        job.invokeOnCompletion { running.update { m -> if (m[key] === job) m - key else m } }
    }

    private fun setJob(key: String, job: StoreJob) = mutable.update { it.copy(jobs = it.jobs + (key to job)) }

    private fun clearJob(key: String) = mutable.update { it.copy(jobs = it.jobs - key) }

    private suspend fun remember(key: String, install: StoreInstall) {
        val next = ctx.data.settings.update { it.copy(store = it.store.copy(installs = it.store.installs + (key to install))) }
        ctx.settings.value = next
        records.value = next.store.installs
        rebuildApps()
    }

    private suspend fun forget(key: String) {
        val next = ctx.data.settings.update { it.copy(store = it.store.copy(installs = it.store.installs - key)) }
        ctx.settings.value = next
        records.value = next.store.installs
        rebuildApps()
    }

    /** Pinned packages change an app's package name: the catalogue's apps are rebuilt. */
    private fun rebuildApps() {
        val catalogue = mutable.value.catalogue ?: return
        val colors = catalogue.categories.associate { it.name to it.color }
        val apps = packApps.map { (key, app) -> storeApp(key, app, colors) }
        mutable.update { it.copy(catalogue = catalogue.copy(apps = apps)) }
    }

    private fun MutableStateFlow<Set<String>>.compareAndSetAdd(key: String): Boolean {
        while (true) {
            val now = value
            if (key in now) return false
            if (compareAndSet(now, now + key)) return true
        }
    }

    private fun friendly(f: ApiResult.Failure): String = when (f) {
        is ApiResult.NetworkError -> "Fuse couldn't connect. Check the connection and try again."
        else -> f.message
    }

    private class StoreFailure(message: String, val retry: Boolean) : Exception(message)

    @Serializable
    private data class CachedCatalogue(val text: String, val version: String?, val fetchedAt: Long, val sourceUrl: String)

    @Serializable
    private data class CachedRelease(
        val version: String? = null,
        val publishedAt: String? = null,
        val notes: String? = null,
        val pageUrl: String = "",
        val fileName: String? = null,
        val fileUrl: String? = null,
        val fileSize: Long? = null,
        val fileDigest: String? = null,
        val manual: String? = null,
    ) {
        fun toRelease() = StoreRelease(
            version, publishedAt, notes, pageUrl,
            if (fileName != null && fileUrl != null) StoreFile(fileName, fileUrl, fileSize, fileDigest) else null,
            manual,
        )

        companion object {
            fun of(r: StoreRelease) = CachedRelease(r.version, r.publishedAt, r.notes, r.pageUrl, r.file?.name, r.file?.url, r.file?.sizeBytes, r.file?.digest, r.manual)
        }
    }

    companion object {
        const val ICONS = "store.icons"

        /** The id apps the user added carry (their key adds their address). */
        private const val CUSTOM_ID = "custom"
        private const val OTHER_COLOR = 0xFF8A93A6
        private val GITHUB_REPO = Regex("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$")
        const val ICON_LOOKUPS = 4
        const val ICON_FOUND_MS = 30L * 24 * 60 * 60 * 1000
        const val ICON_NONE_MS = 7L * 24 * 60 * 60 * 1000
        const val TOKEN_KEY = "store.github.token"
        private const val CATALOGUE = "store.catalogue"
        private const val RELEASES = "store.release"
        const val MAX_DOWNLOADS = 2
        private const val MAX_CHECKS = 3
        private const val MAX_NOTES = 4_000
        const val CATALOGUE_FRESH_MS = 6L * 60 * 60 * 1000
        const val RELEASE_FRESH_MS = 6L * 60 * 60 * 1000
        private const val RELEASE_KEEP_MS = 30L * 24 * 60 * 60 * 1000
        /** An install looks again unless the release was checked this recently. */
        private const val INSTALL_FRESH_MS = 10L * 60 * 1000
        private const val LIMIT_PAUSE_MS = 15L * 60 * 1000
        private const val AUTO_CHECK_DELAY_MS = 20_000L
        private const val AUTO_CHECK_EVERY_MS = 12L * 60 * 60 * 1000

        /**
         * An app's key in its edition: its id with the address of its source, so a fork the other
         * edition lists under the same id (Cemu) is another app with its own install record.
         */
        fun keyOf(app: PackApp): String {
            val place = app.url.substringAfter("://").substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
            return "${app.id}@$place"
        }
    }
}
