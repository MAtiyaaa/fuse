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
        val release = latestTag().flatMap { tag ->
            val url = "$webUrl/releases/download/$tag/${fileName(variant, tag)}"
            download(url).flatMap { parse(it, tag, url) }
        }
        if (release is ApiResult.Success) return release
        val fallback = "$rawUrl/main/${fileName(variant, "latest")}"
        val main = download(fallback).flatMap { parse(it, null, fallback) }
        // When both fail, the release's reason is the more useful one (it is tried first).
        return if (main is ApiResult.Success) main else release
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

        /** The pack's file for [variant] at [tag] ("latest" names the main branch's copy). */
        fun fileName(variant: StoreVariant, tag: String): String = when (variant) {
            StoreVariant.STANDARD -> "obtainium-emulation-pack-$tag.json"
            StoreVariant.DUAL_SCREEN -> "obtainium-emulation-pack-dual-screen-$tag.json"
        }
    }
}
