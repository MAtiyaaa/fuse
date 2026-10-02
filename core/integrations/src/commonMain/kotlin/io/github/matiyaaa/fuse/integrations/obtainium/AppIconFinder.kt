package io.github.matiyaaa.fuse.integrations.obtainium

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.ktor.client.HttpClient
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod

/**
 * Finds a Store app's own icon before it is installed, from where the app itself publishes it:
 * - a GitHub project: the icon its F-Droid/fastlane metadata ships (`fastlane/metadata/android/en-US/
 *   images/icon.png`, or `metadata/en-US/images/icon.png`), else the launcher icon the Play Store
 *   build uses (`app/src/main/ic_launcher-playstore.png`);
 * - a website: the icon the page names for home screens (`apple-touch-icon`, else the largest `icon`).
 *
 * Only HTTPS addresses, only answers that really are pictures. Null when the app publishes none, and
 * the Store shows its monogram; nothing is made up. The owner's avatar is never used: on a personal
 * account it is a person, not the app.
 */
class AppIconFinder(http: HttpClient) {
    private val web = ProviderHttp(http, "The app's page", null, { emptyList() })

    suspend fun find(appUrl: String): String? {
        val repo = REPO.find(appUrl)
        return if (repo != null) {
            val (owner, name) = repo.destructured
            GITHUB_PATHS.firstNotNullOfOrNull { path ->
                "https://raw.githubusercontent.com/$owner/${name.removeSuffix(".git")}/HEAD/$path".takeIf { isPicture(it) }
            }
        } else if (HtmlLinks.isHttps(appUrl)) {
            fromPage(appUrl)
        } else {
            null
        }
    }

    /** The home-screen icon a page names, if it really is a picture. */
    private suspend fun fromPage(pageUrl: String): String? {
        val raw = (web.execute { url(pageUrl) } as? ApiResult.Success)?.value ?: return null
        if (!raw.isSuccess || raw.body.length > MAX_PAGE_CHARS) return null
        val base = raw.url.ifEmpty { pageUrl }
        val icons = LINK.findAll(raw.body).mapNotNull { m ->
            val tag = m.value
            val rel = attr(tag, "rel")?.lowercase() ?: return@mapNotNull null
            val href = attr(tag, "href") ?: return@mapNotNull null
            val rank = when {
                "apple-touch-icon" in rel -> 0
                rel.split(' ').contains("icon") -> 1
                else -> return@mapNotNull null
            }
            val size = attr(tag, "sizes")?.substringBefore('x')?.toIntOrNull() ?: 0
            Triple(rank, -size, href)
        }.sortedWith(compareBy({ it.first }, { it.second })).toList()
        for ((_, _, href) in icons) {
            val url = HtmlLinks.resolve(base, HtmlLinks.decoded(href)) ?: continue
            if (!HtmlLinks.isHttps(url) || url.endsWith(".ico", ignoreCase = true) || url.endsWith(".svg", ignoreCase = true)) continue
            if (isPicture(url)) return url
        }
        return null
    }

    /** True when [url] answers with a picture (a HEAD request, so nothing is downloaded). */
    private suspend fun isPicture(url: String): Boolean {
        val raw = (web.execute { this.method = HttpMethod.Head; url(url) } as? ApiResult.Success)?.value ?: return false
        if (!raw.isSuccess || (raw.url.isNotEmpty() && !HtmlLinks.isHttps(raw.url))) return false
        return raw.headers[HttpHeaders.ContentType]?.lowercase()?.let { it.startsWith("image/png") || it.startsWith("image/jpeg") || it.startsWith("image/webp") } == true
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag)?.let { m ->
            m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
        }

    companion object {
        private val REPO = Regex("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)")
        private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        private const val MAX_PAGE_CHARS = 2 * 1024 * 1024
        private val GITHUB_PATHS = listOf(
            "fastlane/metadata/android/en-US/images/icon.png",
            "metadata/en-US/images/icon.png",
            "app/src/main/ic_launcher-playstore.png",
            "android/app/src/main/ic_launcher-playstore.png",
        )
    }
}
