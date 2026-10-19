package io.github.matiyaaa.fuse.tune

/**
 * An Android app's words, as its `res/values` files keep them (Android's string resources): each
 * `<string name="x">text</string>`, with its escapes (`\'`, `\"`, `\n`, `\@`) and XML entities read
 * and any markup inside left out, and each `<string-array>` or `<integer-array>`, an item naming
 * another string as `@string/x`.
 */
object AndroidResources {
    private val STRING = Regex("""<string\s+name="(\w+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val ARRAY = Regex("""<(string-array|integer-array|array)\s+name="(\w+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val ITEM = Regex("""<item>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)

    fun strings(xml: String): Map<String, String> =
        STRING.findAll(withoutComments(xml)).associate { it.groupValues[1] to text(it.groupValues[2]) }

    /** Each array's items, a `@string/x` item read through [strings]. */
    fun arrays(xml: String, strings: Map<String, String>): Map<String, List<String>> =
        ARRAY.findAll(withoutComments(xml)).associate { m ->
            m.groupValues[2] to ITEM.findAll(m.groupValues[3]).map { i ->
                val t = i.groupValues[1].trim()
                if (t.startsWith("@string/")) strings[t.removePrefix("@string/")] ?: t else text(t)
            }.toList()
        }

    private fun withoutComments(xml: String) = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    /** A resource's text as the app shows it: markup out, entities and escapes read, spaces as one. */
    fun text(raw: String): String {
        val plain = raw.replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            .trim().removeSurrounding("\"")
        val out = StringBuilder()
        var i = 0
        while (i < plain.length) {
            val c = plain[i]
            if (c == '\\' && i + 1 < plain.length) {
                out.append(
                    when (val n = plain[i + 1]) {
                        'n' -> '\n'
                        't' -> ' '
                        else -> n
                    },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString().lines().joinToString("\n") { it.replace(Regex("""[ \t]+"""), " ").trim() }.trim()
    }
}
