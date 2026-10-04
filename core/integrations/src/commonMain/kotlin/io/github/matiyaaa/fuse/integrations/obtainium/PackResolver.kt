package io.github.matiyaaa.fuse.integrations.obtainium

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.Secret
import io.github.matiyaaa.fuse.integrations.flatMap
import io.github.matiyaaa.fuse.integrations.github.GhRelease
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.url
import kotlinx.serialization.builtins.ListSerializer

/**
 * The newest release of an app as its source names it: its [version] (null when the source names
 * none, as some download pages don't), when it was published, its notes, the page it was found on,
 * and the file this device would install ([choice]; null for track-only apps, which are only
 * followed). Nothing here is installed or even downloaded.
 */
data class UpstreamRelease(
    val version: String?,
    val title: String?,
    val publishedAt: String?,
    val notes: String?,
    val pageUrl: String,
    val choice: ApkChoice?,
)

/** A desktop program's newest release and its file for this computer (null when it has none). */
data class DesktopRelease(
    val version: String?,
    val title: String?,
    val publishedAt: String?,
    val notes: String?,
    val pageUrl: String,
    val file: ReleaseAsset?,
    val kind: DesktopAssetKind?,
)

/**
 * Finds the newest release of an app of the pack by the pack's own rules: GitHub's releases for
 * GitHub sources, the download pages for HTML sources. Every address it follows or returns is
 * HTTPS; a page that leads anywhere else stops the search instead of downgrading.
 */
class PackResolver(
    http: HttpClient,
    /** A GitHub token, when the user added one, for GitHub's higher request limit. */
    private val githubToken: () -> String? = { null },
    limiter: RateLimiter = RateLimiter(minIntervalMillis = 250, maxConcurrency = 2),
    private val apiUrl: String = API_URL,
) {
    private val github = ProviderHttp(http, "GitHub", limiter, { listOfNotNull(githubToken()?.let(::Secret)) })
    private val pages = ProviderHttp(http, "The download page", limiter, { emptyList() })

    /** The newest release of [app] for a device whose processor types are [abis] (best first). */
    suspend fun resolve(app: PackApp, abis: List<String>): ApiResult<UpstreamRelease> = when (app.source) {
        PackSourceKind.GITHUB -> gitHub(app, abis)
        PackSourceKind.HTML -> html(app, abis)
        PackSourceKind.OTHER -> ApiResult.NotConfigured("Fuse can't read releases from ${HtmlLinks.hostOf(app.url) ?: "this source"}.")
    }

    // GitHub

    private suspend fun gitHub(app: PackApp, abis: List<String>): ApiResult<UpstreamRelease> {
        val repo = REPO.find(app.url) ?: return ApiResult.NotConfigured("The catalogue's address for ${app.name} isn't a GitHub repository.")
        val owner = repo.groupValues[1]
        val name = repo.groupValues[2].removeSuffix(".git")
        val rules = app.rules
        val base = "$apiUrl/repos/$owner/$name/releases"
        val latest = if (rules.verifyLatestTag) {
            when (val r = releases("$base/latest", single = true)) {
                is ApiResult.Success -> r.value.firstOrNull()
                // GitHub has no "latest" while a repository has only pre-releases: the list decides.
                is ApiResult.HttpError -> if (r.code == 404) null else return r
                is ApiResult.Failure -> return r
            }
        } else null
        return releases("$base?per_page=$PER_PAGE", single = false).flatMap { list ->
            var all = list
            if (latest != null && all.none { tagOf(it) == tagOf(latest) }) all = listOf(latest) + all
            all = sort(all, rules.sortMethod)
            if (latest != null) {
                val i = all.indexOfFirst { tagOf(it) == tagOf(latest) }
                if (i >= 0 && i != all.lastIndex) all = all.toMutableList().also { it.add(it.removeAt(i)) }
            }
            val target = select(all.reversed(), rules) ?: return@flatMap ApiResult.HttpError(404, "${app.name} has no release Fuse can use yet.")
            val titleOrTag = target.name?.trim()?.takeIf { it.isNotEmpty() } ?: target.tagName
            val raw = when {
                rules.releaseDateAsVersion && target.publishedAt != null -> target.publishedAt
                rules.releaseTitleAsVersion -> titleOrTag
                else -> target.tagName.ifEmpty { titleOrTag }
            }
            val version = VersionText.extract(rules.versionPattern, rules.versionGroups, raw).getOrElse {
                return@flatMap ApiResult.InvalidResponse("${app.name}'s newest release has no version name Fuse can read.")
            } ?: raw
            val files = target.assets
                .filter { ApkPicker.isAppFile(it.name, rules.includeZips) }
                .map { ApkLink(it.name, it.browserDownloadUrl, it.size.takeIf { s -> s > 0 }, it.digest) }
                .filter { HtmlLinks.isHttps(it.url) }
            ApiResult.Success(
                UpstreamRelease(
                    version = version,
                    title = target.name?.takeIf { it.isNotBlank() },
                    publishedAt = target.publishedAt,
                    notes = target.body?.takeIf { it.isNotBlank() },
                    pageUrl = target.htmlUrl.takeIf { HtmlLinks.isHttps(it) } ?: "https://github.com/$owner/$name/releases",
                    choice = if (rules.trackOnly) null else ApkPicker.choose(files, rules, app.preferredApkIndex, abis),
                ),
            )
        }
    }

    /**
     * The newest release of the GitHub repository at [repoUrl] (named [name] in messages) with its
     * file for this computer ([DesktopAssets.pick]): the newest release that has one, else the
     * newest release with no file (shown, not installed). Pre-releases count only when [prereleases]
     * says so, or when a project publishes nothing else (rolling "continuous" builds).
     */
    suspend fun desktop(
        repoUrl: String,
        name: String,
        host: io.github.matiyaaa.fuse.model.Host,
        arch: String,
        pattern: Regex? = null,
        prereleases: Boolean = false,
    ): ApiResult<DesktopRelease> {
        val repo = REPO.find(repoUrl) ?: return ApiResult.NotConfigured("$name's address isn't a GitHub repository.")
        val owner = repo.groupValues[1]
        val project = repo.groupValues[2].removeSuffix(".git")
        return releases("$apiUrl/repos/$owner/$project/releases?per_page=$PER_PAGE", single = false).flatMap { list ->
            val published = list.filter { !it.draft }.sortedByDescending { it.publishedAt.orEmpty() }
            val stable = published.filter { prereleases || !it.prerelease }.ifEmpty { published }
            if (stable.isEmpty()) return@flatMap ApiResult.HttpError(404, "$name has no release yet.")
            fun assets(r: GhRelease) = r.assets.map { ReleaseAsset(it.name, it.browserDownloadUrl, it.size.takeIf { s -> s > 0 }, it.digest) }.filter { HtmlLinks.isHttps(it.url) }
            val withFile = stable.firstNotNullOfOrNull { r -> DesktopAssets.pick(assets(r), host, arch, pattern)?.let { r to it } }
            // A newer release without a build for this computer doesn't hide the one before it that has one.
            val (target, file) = withFile ?: (stable.first() to null)
            ApiResult.Success(
                DesktopRelease(
                    version = target.tagName.ifEmpty { target.name.orEmpty() }.ifEmpty { null },
                    title = target.name?.takeIf { it.isNotBlank() },
                    publishedAt = target.publishedAt,
                    notes = target.body?.takeIf { it.isNotBlank() },
                    pageUrl = target.htmlUrl.takeIf { HtmlLinks.isHttps(it) } ?: "https://github.com/$owner/$project/releases",
                    file = file,
                    kind = file?.let { DesktopAssets.kindOf(it.name, host) },
                ),
            )
        }
    }

    private suspend fun releases(url: String, single: Boolean): ApiResult<List<GhRelease>> = github.execute {
        url(url)
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
        githubToken()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }.flatMap { raw ->
        when {
            raw.status == 404 -> ApiResult.HttpError(404, "GitHub has no such repository (it may have moved).")
            (raw.status == 403 || raw.status == 429) && raw.headers["x-ratelimit-remaining"] == "0" -> {
                val reset = raw.headers["x-ratelimit-reset"]?.toLongOrNull()
                ApiResult.RateLimited(reset, "GitHub's limit for checks is used up for now.")
            }
            else -> github.failureFor(raw) ?: if (single) {
                github.decode(raw, GhRelease.serializer()).flatMap { ApiResult.Success(listOf(it)) }
            } else {
                github.decode(raw, ListSerializer(GhRelease.serializer()))
            }
        }
    }

    private fun tagOf(r: GhRelease) = r.tagName.ifEmpty { r.name.orEmpty() }

    /** Oldest first, by the pack's sort method (Obtainium's "date", "smartname", "name", "none"). */
    private fun sort(releases: List<GhRelease>, method: String): List<GhRelease> = when (method) {
        "none" -> releases.reversed()
        "date" -> releases.sortedBy { it.publishedAt.orEmpty() }
        else -> releases.sortedWith { a, b ->
            val na = tagOf(a)
            val nb = tagOf(b)
            val shared = VersionText.compareNumerically(na, nb)
            when {
                method == "smartname-datefallback" && shared == null -> a.publishedAt.orEmpty().compareTo(b.publishedAt.orEmpty())
                method != "name" && shared != null -> shared
                else -> VersionText.compareAlphaNumeric(na, nb)
            }
        }
    }

    /**
     * The release to take from [newestFirst], as Obtainium chooses: no drafts, no pre-releases
     * unless allowed, only titles and notes the pack's filters accept, and only a release with a
     * file that passes the pack's file filter (track-only apps need none). Without "fall back to
     * older releases", only the newest eligible release is considered.
     */
    private fun select(newestFirst: List<GhRelease>, rules: PackRules): GhRelease? {
        val title = rules.releaseTitleFilter?.let { VersionText.compile(it) ?: return null }
        val notes = rules.releaseNotesFilter?.let { VersionText.compile(it) ?: return null }
        var skipped = 0
        for ((i, r) in newestFirst.withIndex()) {
            if (!rules.fallbackToOlderReleases && i > skipped) break
            if ((!rules.includePrereleases && r.prerelease) || r.draft) {
                skipped++
                continue
            }
            val name = r.name?.trim()?.takeIf { it.isNotEmpty() } ?: r.tagName
            if (title != null && !title.containsMatchIn(name.trim())) continue
            if (notes != null && !notes.containsMatchIn(r.body.orEmpty().trim())) continue
            val files = r.assets.map { ApkLink(it.name, it.browserDownloadUrl) }.filter { ApkPicker.isAppFile(it.name, rules.includeZips) }
            if (!rules.trackOnly && ApkPicker.filter(files, rules.apkFilter, rules.invertApkFilter).isEmpty()) continue
            return r
        }
        return null
    }

    // HTML

    private suspend fun html(app: PackApp, abis: List<String>): ApiResult<UpstreamRelease> {
        val rules = app.rules
        var current = app.url
        if (!HtmlLinks.isHttps(current)) return ApiResult.NotConfigured("${app.name}'s download page isn't a secure (HTTPS) address, so Fuse won't use it.")
        for (step in rules.intermediateLinks.take(MAX_STEPS)) {
            val (page, at) = when (val r = page(current, rules.requestHeaders)) {
                is ApiResult.Success -> r.value
                is ApiResult.Failure -> return r
            }
            var links = HtmlLinks.links(
                page, at,
                LinkRules(step.filter, step.filterByLinkText, step.matchLinksOutsideATags, step.skipSort, step.reverseSort, step.sortByLastLinkSegment),
            )
            if (step.autoLinkFilterByArch) {
                links = ApkPicker.byArch(links.map { ApkLink(it.text, it.url) }, abis) { it.url }.map { PageLink(it.url, it.name) }
            }
            val next = links.lastOrNull() ?: return ApiResult.HttpError(404, "${app.name}'s download page has changed; Fuse couldn't find the way to the download.")
            if (!HtmlLinks.isHttps(next.url)) return ApiResult.NotConfigured("${app.name}'s download page leads to an address that isn't secure (HTTPS).")
            current = next.url
        }
        val (body, at) = when (val r = page(current, rules.requestHeaders)) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return r
        }
        val found = HtmlLinks.links(
            body, at,
            LinkRules(rules.linkFilter, rules.filterByLinkText, rules.matchLinksOutsideATags, rules.skipSort, rules.reverseSort, rules.sortByLastLinkSegment),
        )
        val filtered = ApkPicker.filter(found.map { ApkLink(it.url.substringAfterLast('/'), it.url) }, rules.apkFilter, rules.invertApkFilter) { it.url }
        val link = filtered.lastOrNull() ?: return ApiResult.HttpError(404, "${app.name}'s download page has no download Fuse can find right now.")
        val source = if (rules.versionFromWholePage) body.replace("\r\n", "\n").replace("\n", "\\n") else HtmlLinks.decoded(link.url)
        val version = VersionText.extract(rules.versionPattern, rules.versionGroups, source).getOrElse {
            return ApiResult.InvalidResponse("${app.name}'s download page has no version name Fuse can read.")
        }
        val name = HtmlLinks.decoded(link.url).substringBefore('?').substringAfterLast('/')
        val choice = when {
            rules.trackOnly -> null
            !HtmlLinks.isHttps(link.url) -> ApkChoice.Manual("The download isn't offered over a secure (HTTPS) address, so Fuse won't install it.")
            else -> ApkPicker.choose(listOf(ApkLink(name, link.url)), rules.copy(apkFilter = null, filterByArch = false), null, abis)
        }
        return ApiResult.Success(UpstreamRelease(version, null, null, null, current, choice))
    }

    /** The page at [url]: its text and the address it finally came from, which its links are relative to. */
    private suspend fun page(url: String, headers: Map<String, String>): ApiResult<Pair<String, String>> = pages.execute {
        url(url)
        headers.forEach { (k, v) -> header(k, v) }
    }.flatMap { raw ->
        when {
            raw.status == 404 -> ApiResult.HttpError(404, "The download page is gone (it may have moved).")
            else -> pages.failureFor(raw) ?: if (raw.body.length > MAX_PAGE_CHARS) {
                ApiResult.InvalidResponse("The download page is larger than Fuse reads.")
            } else if (raw.url.isNotEmpty() && !HtmlLinks.isHttps(raw.url)) {
                ApiResult.NotConfigured("The download page moved to an address that isn't secure (HTTPS).")
            } else ApiResult.Success(raw.body to raw.url.ifEmpty { url })
        }
    }

    companion object {
        const val API_URL = "https://api.github.com"
        private const val PER_PAGE = 50
        private const val MAX_STEPS = 10
        private const val MAX_PAGE_CHARS = 4 * 1024 * 1024
        private val REPO = Regex("^https://github\\.com/([^/\\s]+)/([^/\\s?#]+)")
    }
}
