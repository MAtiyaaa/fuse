package io.github.matiyaaa.fuse.integrations.github

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.LooseBoolean
import io.github.matiyaaa.fuse.integrations.LooseLong
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.flatMap
import io.github.matiyaaa.fuse.model.ReleaseAsset
import io.github.matiyaaa.fuse.model.ReleaseInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** owner/repo on GitHub. */
data class RepoRef(val owner: String, val repo: String) {
    override fun toString(): String = "$owner/$repo"
}

/** Which build of a release the device needs. */
/** Which build of a release this device takes. */
enum class ReleasePlatform { ANDROID, LINUX_X86_64, WINDOWS_X64, MACOS_ARM64, MACOS_X64 }

@Serializable
internal data class GhAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    @Serializable(with = LooseLong::class) val size: Long = 0,
    val digest: String? = null,
)

@Serializable
internal data class GhRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @Serializable(with = LooseBoolean::class) val draft: Boolean = false,
    @Serializable(with = LooseBoolean::class) val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

/**
 * Reads GitHub releases (unauthenticated REST API, 60 requests per hour per IP), for Fuse updates and
 * "Install Cartridge". Asset digests (`sha256:<hex>`) are passed through so the installer can verify
 * the download.
 */
class GitHubReleases(
    http: HttpClient,
    limiter: RateLimiter = RateLimiter(minIntervalMillis = 500, maxConcurrency = 2),
    private val apiUrl: String = API_URL,
) {
    private val api = ProviderHttp(http, "GitHub", limiter, { emptyList() })

    /** The latest published (non-draft, non-prerelease) release; Success(null) when there is none. */
    suspend fun latest(owner: String, repo: String): ApiResult<ReleaseInfo?> = api.execute {
        url("$apiUrl/repos/$owner/$repo/releases/latest")
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }.flatMap { raw ->
        when {
            raw.status == 404 -> ApiResult.Success(null)
            // GitHub signals an exhausted unauthenticated quota with 403/429 and x-ratelimit-remaining: 0.
            (raw.status == 403 || raw.status == 429) && raw.headers["x-ratelimit-remaining"] == "0" -> {
                val reset = raw.headers["x-ratelimit-reset"]?.toLongOrNull()
                ApiResult.RateLimited(null, "GitHub API rate limit reached" + (reset?.let { " (resets at epoch $it)" } ?: ""))
            }
            else -> api.failureFor(raw) ?: api.decode(raw, GhRelease.serializer()).flatMap { ApiResult.Success(it.toModel()) }
        }
    }

    suspend fun latest(repo: RepoRef): ApiResult<ReleaseInfo?> = latest(repo.owner, repo.repo)

    override fun toString(): String = "GitHubReleases"

    companion object {
        const val API_URL = "https://api.github.com"

        /** Fuse's own releases. */
        val FUSE_REPO = RepoRef("MAtiyaaa", "fuse")

        /**
         * The asset for [platform]: an `.apk` on Android (a universal or "android" build preferred),
         * the `x86_64.AppImage` on Linux, the `windows-x64.msi` (else its portable zip) on Windows,
         * and the `macos-arm64.dmg` or `macos-x64.dmg` on a Mac. Null when the release has none.
         */
        fun pickAsset(release: ReleaseInfo, platform: ReleasePlatform): ReleaseAsset? = when (platform) {
            ReleasePlatform.ANDROID -> {
                val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
                apks.firstOrNull { it.name.contains("universal", true) }
                    ?: apks.firstOrNull { it.name.contains("android", true) }
                    ?: apks.firstOrNull()
            }
            ReleasePlatform.LINUX_X86_64 ->
                release.assets.firstOrNull { it.name.endsWith("x86_64.AppImage", ignoreCase = true) }
            ReleasePlatform.WINDOWS_X64 ->
                release.assets.firstOrNull { it.name.endsWith("windows-x64.msi", ignoreCase = true) }
                    ?: release.assets.firstOrNull { it.name.endsWith("windows-x64.zip", ignoreCase = true) }
            ReleasePlatform.MACOS_ARM64 -> release.assets.firstOrNull { it.name.endsWith("macos-arm64.dmg", ignoreCase = true) }
            ReleasePlatform.MACOS_X64 -> release.assets.firstOrNull { it.name.endsWith("macos-x64.dmg", ignoreCase = true) }
        }
    }
}

private fun GhRelease.toModel(): ReleaseInfo = ReleaseInfo(
    tag = tagName,
    name = name?.takeIf { it.isNotBlank() } ?: tagName,
    notes = body.orEmpty(),
    publishedAt = publishedAt,
    assets = assets.map { ReleaseAsset(name = it.name, url = it.browserDownloadUrl, sizeBytes = it.size, digest = it.digest) },
    htmlUrl = htmlUrl,
)

/**
 * Semantic-version comparison that tolerates a leading "v" and missing parts: `v0.9.10` > `v0.9.9`,
 * `1.0` == `1.0.0`, and a pre-release (`1.0.0-beta.2`) sorts before its release. Build metadata
 * (`+abc`) is ignored.
 */
object SemVer {
    private class Parsed(val numbers: List<Long>, val preRelease: List<String>)

    private fun parse(version: String): Parsed? {
        val v = version.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        if (v.isEmpty()) return null
        val core = v.substringBefore('-')
        val pre = if ('-' in v) v.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
        val numbers = core.split('.').map { part -> part.takeWhile { it.isDigit() }.toLongOrNull() ?: return null }
        return Parsed(numbers, pre)
    }

    /** Negative, zero or positive like [Comparable.compareTo]; unparseable versions sort first. */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        if (pa == null || pb == null) return (if (pa == null) 0 else 1) - (if (pb == null) 0 else 1)
        val n = maxOf(pa.numbers.size, pb.numbers.size)
        for (i in 0 until n) {
            val c = (pa.numbers.getOrNull(i) ?: 0L).compareTo(pb.numbers.getOrNull(i) ?: 0L)
            if (c != 0) return c
        }
        if (pa.preRelease.isEmpty() || pb.preRelease.isEmpty()) {
            return (if (pa.preRelease.isEmpty()) 1 else 0) - (if (pb.preRelease.isEmpty()) 1 else 0)
        }
        for (i in 0 until maxOf(pa.preRelease.size, pb.preRelease.size)) {
            val x = pa.preRelease.getOrNull(i) ?: return -1
            val y = pb.preRelease.getOrNull(i) ?: return 1
            val xn = x.toLongOrNull()
            val yn = y.toLongOrNull()
            val c = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return 0
    }

    /** True when [candidate] is strictly newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /** True when [version] is at least [minimum]. */
    fun atLeast(version: String, minimum: String): Boolean = compare(version, minimum) >= 0
}
