package io.github.matiyaaa.fuse.integrations.obtainium

import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.ktor.http.decodeURLPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A link found on a page: where it goes, and its text (or the last part of its address). */
data class PageLink(val url: String, val text: String)

/** How links are picked from one page: Obtainium's settings for a step of an HTML source. */
data class LinkRules(
    val filter: String?,
    val filterByLinkText: Boolean,
    val matchLinksOutsideATags: Boolean,
    val skipSort: Boolean,
    val reverseSort: Boolean,
    val sortByLastLinkSegment: Boolean,
)

/**
 * Finds links on a download page the way Obtainium's HTML source does: every `<a href>`, and when
 * the page has none (a JSON file) or the pack asks for it, every address written anywhere in the
 * page. Links are resolved against the page's address, filtered by the pack's pattern (or, with no
 * pattern, to app files), and sorted in natural order; the last one is the newest.
 */
object HtmlLinks {
    fun links(body: String, pageUrl: String, rules: LinkRules): List<PageLink> {
        var all = anchors(body, pageUrl)
        if (all.isEmpty() || rules.matchLinksOutsideATags) {
            val merged = LinkedHashMap<String, PageLink>()
            all.forEach { merged[it.url] = it }
            fun add(found: List<PageLink>) = found.forEach { merged.getOrPut(it.url) { it } }
            if (all.isEmpty()) {
                val strings = jsonStrings(body)
                if (strings != null) {
                    var found = inLines(strings.joinToString("\n"))
                    if (found.isEmpty()) found = inLines(strings.mapNotNull { resolve(pageUrl, it) }.joinToString("\n"))
                    add(found)
                } else {
                    add(inLines(body))
                }
            }
            if (rules.matchLinksOutsideATags) {
                add(inLines(body))
                add(inAttributes(body, pageUrl))
            }
            all = merged.values.toList()
        }
        val pattern = rules.filter?.takeIf { it.isNotEmpty() }
        var picked = if (pattern != null) {
            val regex = VersionText.compile(pattern) ?: return emptyList()
            all.filter { regex.containsMatchIn(if (rules.filterByLinkText) it.text else decoded(it.url)) }
        } else {
            all.filter { ApkPicker.isAppFile(pathOf((if (rules.filterByLinkText) it.text else decoded(it.url)).trim())) }
        }
        if (!rules.skipSort) {
            picked = picked.sortedWith { a, b ->
                if (rules.sortByLastLinkSegment) VersionText.compareAlphaNumeric(lastSegment(a.url), lastSegment(b.url))
                else VersionText.compareAlphaNumeric(a.url, b.url)
            }
        }
        if (rules.reverseSort) picked = picked.reversed()
        return picked
    }

    /** `<a href="...">text</a>` links, resolved; the text falls back to the last part of the address. */
    internal fun anchors(body: String, pageUrl: String): List<PageLink> =
        ANCHOR.findAll(body).mapNotNull { m ->
            val href = attribute(m.groupValues[1], "href")?.let(::entities)?.trim().orEmpty()
            if (href.isEmpty()) return@mapNotNull null
            val url = resolve(pageUrl, href) ?: return@mapNotNull null
            val text = entities(TAG.replace(m.groupValues[2], "")).trim()
            PageLink(url, text.ifEmpty { href.substringAfterLast('/') })
        }.toList()

    /** Addresses written out in text (Obtainium's pattern, trailing punctuation dropped). */
    internal fun inLines(text: String): List<PageLink> =
        URL_IN_TEXT.findAll(text).map { it.value.replace(TRAILING, "") }.filter { it.isNotEmpty() }
            .map { PageLink(it, it.substringAfterLast('/')) }.toList()

    /** Absolute and root-relative addresses in any element's attributes (`<script src="/app.js">`). */
    private fun inAttributes(body: String, pageUrl: String): List<PageLink> =
        ATTRIBUTE.findAll(body).mapNotNull { m ->
            val value = entities(m.groupValues[2].ifEmpty { m.groupValues[3] }).trim()
            if (value.isEmpty()) return@mapNotNull null
            val absolute = ABSOLUTE.containsMatchIn(value)
            if (!absolute && !value.startsWith("/")) return@mapNotNull null
            val url = resolve(pageUrl, value) ?: return@mapNotNull null
            PageLink(url, url.substringAfterLast('/'))
        }.toList()

    /** Every string in [body] when it is JSON (Eden publishes a release.json), else null. */
    private fun jsonStrings(body: String): List<String>? {
        val root = try {
            FuseHttp.json.parseToJsonElement(body)
        } catch (e: Exception) {
            return null
        }
        val out = ArrayList<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonPrimitive -> if (e.isString) out += e.content
                is JsonArray -> e.forEach(::walk)
                is JsonObject -> e.values.forEach(::walk)
            }
        }
        walk(root)
        return out
    }

    private fun attribute(tag: String, name: String): String? {
        val m = Regex("""(?i)\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""").find(tag) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }
    }

    /**
     * [ref] resolved against [base] (RFC 3986): absolute, protocol-relative, root-relative and
     * relative references, with "." and ".." segments. Null when it isn't an http(s) address.
     */
    fun resolve(base: String, ref: String): String? {
        val r = ref.trim()
        if (r.isEmpty()) return null
        SCHEME.find(r)?.let { s ->
            val scheme = s.groupValues[1].lowercase()
            return if (scheme == "http" || scheme == "https") r else null
        }
        val b = parse(base) ?: return null
        if (r.startsWith("//")) return "${b.scheme}:$r"
        val fragmentless = r.substringBefore('#')
        if (fragmentless.isEmpty()) return b.scheme + "://" + b.authority + b.path + (b.query?.let { "?$it" } ?: "")
        if (fragmentless.startsWith("?")) return b.scheme + "://" + b.authority + b.path + fragmentless
        val pathPart = fragmentless.substringBefore('?')
        val query = fragmentless.substringAfter('?', "").takeIf { '?' in fragmentless }
        val merged = if (pathPart.startsWith("/")) pathPart else b.path.substringBeforeLast('/', "") + "/" + pathPart
        return b.scheme + "://" + b.authority + removeDots(merged) + (query?.let { "?$it" } ?: "")
    }

    private class Parts(val scheme: String, val authority: String, val path: String, val query: String?)

    private fun parse(url: String): Parts? {
        val m = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)(?:\\?([^#]*))?").find(url.trim()) ?: return null
        return Parts(m.groupValues[1].lowercase(), m.groupValues[2], m.groupValues[3].ifEmpty { "/" }, m.groups[4]?.value)
    }

    private fun removeDots(path: String): String {
        val out = ArrayList<String>()
        val segments = path.split('/')
        for ((i, seg) in segments.withIndex()) {
            when (seg) {
                "." -> if (i == segments.lastIndex) out += ""
                ".." -> {
                    if (out.size > 1) out.removeAt(out.lastIndex)
                    if (i == segments.lastIndex) out += ""
                }
                else -> out += seg
            }
        }
        val joined = out.joinToString("/")
        return if (joined.startsWith("/")) joined else "/$joined"
    }

    /** The host of [url], lower case, or null. */
    fun hostOf(url: String): String? = parse(url)?.authority?.substringAfterLast('@')?.substringBefore(':')?.lowercase()?.takeIf { it.isNotEmpty() }

    fun isHttps(url: String): Boolean = parse(url)?.scheme == "https"

    private fun pathOf(url: String): String = parse(url)?.path ?: url.substringBefore('?').substringBefore('#')

    private fun lastSegment(url: String): String = url.split('/').lastOrNull { it.isNotEmpty() } ?: url

    internal fun decoded(url: String): String = try {
        url.decodeURLPart()
    } catch (e: Exception) {
        url
    }

    private fun entities(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.equals("amp", true) -> "&"
            e.equals("lt", true) -> "<"
            e.equals("gt", true) -> ">"
            e.equals("quot", true) -> "\""
            e.equals("apos", true) -> "'"
            e.equals("nbsp", true) -> " "
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { codePointText(it) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { codePointText(it) } ?: m.value
            else -> m.value
        }
    }

    private fun codePointText(cp: Int): String? = when {
        cp in 0..0xFFFF -> cp.toChar().toString()
        cp in 0x10000..0x10FFFF -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
        else -> null
    }

    private val ANCHOR = Regex("""(?is)<a\b([^>]*)>(.*?)</a\s*>""")
    private val TAG = Regex("""(?s)<[^>]*>""")
    /** An attribute and its quoted value: group 2 when double-quoted, group 3 when single-quoted. */
    private val ATTRIBUTE = Regex("""(?i)\s([a-z_:][-a-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val URL_IN_TEXT = Regex("""(?:(?:http|https|ftp)://)[^\s"'<>()\[\]{}]+""")
    private val TRAILING = Regex("[.,;:!?]+$")
    private val ABSOLUTE = Regex("^(https?|ftp)://", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val ENTITY = Regex("&(#[xX]?[0-9A-Fa-f]+|[A-Za-z]+);")
}
