package io.github.matiyaaa.fuse.integrations.obtainium

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.flatMap
import io.github.matiyaaa.fuse.model.StoreVariant
import io.ktor.client.HttpClient
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders

/** A catalogue as downloaded: its text (kept as the cache), what it lists, its release tag, and where it came from. */
data class FetchedPack(val text: String, val pack: Pack, val version: String?, val sourceUrl: String)

/**
 * Downloads the Obtainium Emulation Pack (github.com/RJNY/Obtainium-Emulation-Pack): the newest
 * release's file for the chosen edition, found through GitHub's "latest release" address (which
 * costs none of GitHub's API quota). When the release can't be reached, the same file from the
 * project's main branch is used. A download is only returned when it parses as a real pack, so a
 * broken file never replaces a good catalogue.
 *
 * Since its version 8 (October 2026) the pack is one file for every device, its dual-screen builds
 * in it: the dual-screen edition is followed by its own file where a release still has one, else
 * by that one pack.
 */
class PackFetcher(
    http: HttpClient,
    private val webUrl: String = WEB_URL,
    private val rawUrl: String = RAW_URL,
) {
    private val plain = ProviderHttp(http, "GitHub", null, { emptyList() })

    /** The same client without following redirects, to read where "latest" points. */
    private val noRedirect = ProviderHttp(http.config { followRedirects = false }, "GitHub", null, { emptyList() })

    suspend fun fetch(variant: StoreVariant): ApiResult<FetchedPack> {
        val editions = editionsFor(variant)
        val release = latestTag().flatMap { tag ->
            firstOf(editions.map { "$webUrl/releases/download/$tag/${fileName(it, tag)}" }, tag)
        }
        if (release is ApiResult.Success) return release
        val main = firstOf(editions.map { "$rawUrl/main/${fileName(it, "latest")}" }, null)
        // When both fail, the release's reason is the more useful one (it is tried first).
        return if (main is ApiResult.Success) main else release
    }

    /** The first of [urls] that downloads and reads as a pack; else the first one's reason. */
    private suspend fun firstOf(urls: List<String>, version: String?): ApiResult<FetchedPack> {
        var first: ApiResult<FetchedPack>? = null
        for (url in urls) {
            val r = download(url).flatMap { parse(it, version, url) }
            if (r is ApiResult.Success) return r
            if (first == null) first = r
        }
        return first ?: ApiResult.InvalidResponse("The catalogue has no file to read.")
    }

    /** The newest release's tag ("v7.18.0"), read from where GitHub's "latest release" page redirects. */
    suspend fun latestTag(): ApiResult<String> = noRedirect.execute { url("$webUrl/releases/latest") }.flatMap { raw ->
        val location = raw.headers[HttpHeaders.Location]
        val tag = location?.substringAfter("/releases/tag/", "")?.substringBefore('?')?.substringBefore('#')
        when {
            raw.status in 300..399 && tag != null && TAG.matches(tag) -> ApiResult.Success(tag)
            raw.status in 300..399 || raw.isSuccess -> ApiResult.InvalidResponse("GitHub didn't say which release of the catalogue is the newest.")
            else -> noRedirect.failureFor(raw) ?: ApiResult.HttpError(raw.status)
        }
    }

    private suspend fun download(url: String): ApiResult<String> = plain.execute { url(url) }.flatMap { raw ->
        plain.failureFor(raw) ?: ApiResult.Success(raw.body)
    }

    private fun parse(text: String, version: String?, url: String): ApiResult<FetchedPack> =
        PackDocument.parse(text).fold(
            onSuccess = { ApiResult.Success(FetchedPack(text, it, version, url)) },
            onFailure = { ApiResult.InvalidResponse(it.message ?: "The catalogue isn't readable.") },
        )

    companion object {
        const val WEB_URL = "https://github.com/RJNY/Obtainium-Emulation-Pack"
        const val RAW_URL = "https://raw.githubusercontent.com/RJNY/Obtainium-Emulation-Pack"

        /** Release tags look like "v7.18.0"; anything else is not trusted into a download address. */
        private val TAG = Regex("v?\\d+(\\.\\d+){0,3}")

        /** The files followed for [variant], in order: the dual-screen edition's own, then the one pack. */
        fun editionsFor(variant: StoreVariant): List<StoreVariant> = when (variant) {
            StoreVariant.STANDARD -> listOf(StoreVariant.STANDARD)
            StoreVariant.DUAL_SCREEN -> listOf(StoreVariant.DUAL_SCREEN, StoreVariant.STANDARD)
        }

        /** The pack's file for [variant] at [tag] ("latest" names the main branch's copy). */
        fun fileName(variant: StoreVariant, tag: String): String = when (variant) {
            StoreVariant.STANDARD -> "obtainium-emulation-pack-$tag.json"
            StoreVariant.DUAL_SCREEN -> "obtainium-emulation-pack-dual-screen-$tag.json"
        }
    }
}
