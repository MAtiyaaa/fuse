package io.github.matiyaaa.fuse.integrations.systemart

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.getOrNull
import io.ktor.client.HttpClient
import io.ktor.client.request.url
import io.ktor.http.HttpMethod

/**
 * Art for one system. [artworkUrl] is null when neither the requested style nor Classic has a
 * panel; [artworkStyle] says which style it is (Classic after a fallback). [meta] is null when the
 * metadata file is missing or unreadable, which never fails the whole fetch.
 */
data class SystemArt(
    val logoUrl: String,
    val artworkUrl: String?,
    val meta: SystemArtMeta?,
    val artworkStyle: SystemArtStyle? = null,
)

/**
 * Reads the Art Book Next system art pack ([SystemArtPack]) from raw.githubusercontent.com. No key
 * is needed and nothing about the user is sent: requests carry only the system name. Existence is
 * checked with HEAD requests; the images themselves are downloaded by the app's image loader.
 */
class SystemArtPackClient(
    http: HttpClient,
    limiter: RateLimiter = RateLimiter(minIntervalMillis = 50, maxConcurrency = 4),
    private val baseUrl: String = SystemArtPack.BASE_URL,
) {
    private val api = ProviderHttp(http, "Art Book Next", limiter, { emptyList() })

    /** Every URL for pack system [name], without checking that the files exist. */
    fun urls(name: String): SystemArtUrls = SystemArtPack.urls(name, baseUrl)

    /**
     * The logo, the artwork in [style] (or Classic when that style lacks the system) and the
     * metadata of pack system [name] (see [SystemArtNames.forPlatform]). Fails when the logo is not
     * there, since then the name is not a pack system.
     */
    suspend fun fetch(name: String, style: SystemArtStyle = SystemArtStyle.CLASSIC): ApiResult<SystemArt> {
        val urls = urls(name)
        when (val logo = exists(urls.logo)) {
            is ApiResult.Failure -> return logo
            is ApiResult.Success -> if (!logo.value) return ApiResult.HttpError(404, "Art Book Next has no system named $name")
        }
        var artworkStyle: SystemArtStyle? = null
        for (candidate in listOf(style, SystemArtStyle.CLASSIC).distinct()) {
            when (val found = exists(urls.artwork(candidate))) {
                is ApiResult.Failure -> return found
                is ApiResult.Success -> if (found.value) {
                    artworkStyle = candidate
                    break
                }
            }
        }
        return ApiResult.Success(
            SystemArt(
                logoUrl = urls.logo,
                artworkUrl = artworkStyle?.let(urls::artwork),
                meta = metadata(name).getOrNull(),
                artworkStyle = artworkStyle,
            ),
        )
    }

    /** The styles that have artwork for [name], in [SystemArtStyle] order, for a style picker. */
    suspend fun availableStyles(name: String): ApiResult<List<SystemArtStyle>> {
        val urls = urls(name)
        val out = ArrayList<SystemArtStyle>()
        for (style in SystemArtStyle.entries) {
            when (val found = exists(urls.artwork(style))) {
                is ApiResult.Failure -> return found
                is ApiResult.Success -> if (found.value) out += style
            }
        }
        return ApiResult.Success(out)
    }

    /** The parsed `_metadata-global` file; Success(null) when it is missing or has no system name. */
    suspend fun metadata(name: String): ApiResult<SystemArtMeta?> = when (val r = api.execute { url(urls(name).metadata) }) {
        is ApiResult.Failure -> r
        is ApiResult.Success -> when {
            r.value.status == 404 -> ApiResult.Success(null)
            else -> api.failureFor(r.value) ?: ApiResult.Success(SystemArtMetadata.parse(r.value.body))
        }
    }

    /** HEAD probe: true for 2xx, false for 404/403, failure otherwise. */
    suspend fun exists(url: String): ApiResult<Boolean> = when (val r = api.execute { method = HttpMethod.Head; url(url) }) {
        is ApiResult.Failure -> r
        is ApiResult.Success -> when {
            r.value.isSuccess -> ApiResult.Success(true)
            r.value.status == 404 || r.value.status == 403 -> ApiResult.Success(false)
            else -> api.failureFor(r.value) ?: ApiResult.Success(false)
        }
    }
}
