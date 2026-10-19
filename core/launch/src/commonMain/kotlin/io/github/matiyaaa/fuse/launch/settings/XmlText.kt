package io.github.matiyaaa.fuse.launch.settings

/**
 * An XML settings file of elements with attributes under one root (Vita3K's
 * `config/config_<title id>.xml`: `<config><gpu backend-renderer="Vulkan" v-sync="true" /></config>`),
 * edited without disturbing anything else in it: every other element, attribute, its order and
 * the spacing stay as they were, byte for byte. A section is an element's path under the root
 * (`gpu`), a key one of its attributes. Values are read and written with XML's five entities. A
 * new attribute goes last in its element, a new element last in the root on a line of its own,
 * indented with a tab as pugixml writes them, and a file made from nothing starts as pugixml
 * starts one, with its declaration.
 */
class XmlText(text: String, private val root: String) : SettingsText {
    private var doc = text

    /** A start tag: where it begins and ends (its `>`), whether it closes itself, and its attributes. */
    private class Tag(val name: String, val start: Int, val end: Int, val selfClosing: Boolean, val attributes: List<Attr>)

    /** An attribute: where it starts (its name, after the space before it) and where its value's quotes are. */
    private class Attr(val name: String, val start: Int, val valueStart: Int, val valueEnd: Int)

    /** An element: its start tag, and where its closing tag begins (the same as the tag's end for a self-closing one). */
    private class Element(val tag: Tag, val closeStart: Int, val children: List<Element>)

    override fun values(section: String, key: String): List<String> {
        val a = element(section)?.tag?.attributes?.firstOrNull { it.name == key } ?: return emptyList()
        return listOf(unescape(doc.substring(a.valueStart, a.valueEnd)))
    }

    override fun set(section: String, key: String, value: String) {
        val e = element(section)
        if (e != null) {
            val a = e.tag.attributes.firstOrNull { it.name == key }
            doc = if (a != null) {
                doc.substring(0, a.valueStart) + escape(value) + doc.substring(a.valueEnd)
            } else {
                // Before the tag's `/>` (or `>`), after its last attribute.
                val at = e.tag.attributes.lastOrNull()?.let { it.valueEnd + 1 } ?: (e.tag.start + 1 + e.tag.name.length)
                doc.substring(0, at) + " $key=\"${escape(value)}\"" + doc.substring(at)
            }
            return
        }
        val top = parse() ?: run {
            doc = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<$root>\n" + nested(section.split('/'), key, value, 1) + "</$root>\n" + doc.trimStart()
            return
        }
        // The deepest element of the path that is there, then the rest made inside it.
        val parts = section.split('/')
        var parent = top
        var depth = 0
        while (depth < parts.size) {
            parent = parent.children.firstOrNull { it.tag.name == parts[depth] } ?: break
            depth++
        }
        val indent = "\t".repeat(depth + 1)
        val made = nested(parts.drop(depth), key, value, depth + 1)
        doc = if (parent.tag.selfClosing) {
            // `<config />` opens to take it.
            doc.substring(0, parent.tag.end - 1).trimEnd() + ">\n" + made + "\t".repeat(depth) + "</${parent.tag.name}>" + doc.substring(parent.tag.end + 1)
        } else {
            val close = parent.closeStart
            val lineStart = doc.lastIndexOf('\n', close - 1) + 1
            val before = doc.substring(lineStart, close)
            if (before.isBlank()) doc.substring(0, lineStart) + made + doc.substring(lineStart) else doc.substring(0, close) + "\n" + made + indent.dropLast(1) + doc.substring(close)
        }
    }

    override fun removeKey(section: String, key: String): Boolean {
        val a = element(section)?.tag?.attributes?.firstOrNull { it.name == key } ?: return false
        // With the space before it.
        var from = a.start
        while (from > 0 && doc[from - 1].isWhitespace()) from--
        doc = doc.substring(0, from) + doc.substring(a.valueEnd + 1)
        return true
    }

    override fun hasEntries(): Boolean {
        fun any(e: Element): Boolean = e.tag.attributes.isNotEmpty() || e.children.any(::any)
        return parse()?.children?.any(::any) == true
    }

    override fun hasSection(section: String): Boolean = element(section) != null

    override fun removeEmptySection(section: String, blankBefore: Boolean): Boolean {
        val e = element(section) ?: return false
        if (e.tag.attributes.isNotEmpty() || e.children.isNotEmpty()) return false
        val end = if (e.tag.selfClosing) e.tag.end + 1 else doc.indexOf('>', e.closeStart) + 1
        // Its whole line when it is alone on one.
        val lineStart = doc.lastIndexOf('\n', e.tag.start - 1) + 1
        val lineEnd = doc.indexOf('\n', end).let { if (it < 0) doc.length else it + 1 }
        doc = if (doc.substring(lineStart, e.tag.start).isBlank() && doc.substring(end, lineEnd).isBlank()) {
            doc.substring(0, lineStart) + doc.substring(lineEnd)
        } else {
            doc.substring(0, e.tag.start) + doc.substring(end)
        }
        return true
    }

    override fun toString(): String = doc

    /** Elements for [parts] inside one another, the innermost holding [key], each on its own line from [depth]. */
    private fun nested(parts: List<String>, key: String, value: String, depth: Int): String {
        val indent = "\t".repeat(depth)
        if (parts.size == 1) return "$indent<${parts[0]} $key=\"${escape(value)}\" />\n"
        return "$indent<${parts[0]}>\n" + nested(parts.drop(1), key, value, depth + 1) + "$indent</${parts[0]}>\n"
    }

    private fun element(section: String): Element? {
        var e = parse() ?: return null
        for (part in section.split('/')) e = e.children.firstOrNull { it.tag.name == part } ?: return null
        return e
    }

    /** The root element, or null when the file hasn't got the one this is for. */
    private fun parse(): Element? {
        var i = 0
        while (true) {
            i = doc.indexOf('<', i).takeIf { it >= 0 } ?: return null
            when {
                doc.startsWith("<?", i) -> i = doc.indexOf("?>", i).takeIf { it >= 0 }?.plus(2) ?: return null
                doc.startsWith("<!--", i) -> i = doc.indexOf("-->", i).takeIf { it >= 0 }?.plus(3) ?: return null
                doc.startsWith("<!", i) -> i = doc.indexOf('>', i).takeIf { it >= 0 }?.plus(1) ?: return null
                else -> {
                    val (e, _) = elementAt(i) ?: return null
                    return e.takeIf { it.tag.name == root }
                }
            }
        }
    }

    /** The element whose start tag is at [at], and where it ends. */
    private fun elementAt(at: Int): Pair<Element, Int>? {
        val tag = tagAt(at) ?: return null
        if (tag.selfClosing) return Element(tag, tag.end, emptyList()) to tag.end + 1
        val children = ArrayList<Element>()
        var i = tag.end + 1
        while (true) {
            i = doc.indexOf('<', i).takeIf { it >= 0 } ?: return null
            when {
                doc.startsWith("</", i) -> {
                    val close = doc.indexOf('>', i).takeIf { it >= 0 } ?: return null
                    return Element(tag, i, children) to close + 1
                }
                doc.startsWith("<!--", i) -> i = doc.indexOf("-->", i).takeIf { it >= 0 }?.plus(3) ?: return null
                doc.startsWith("<![CDATA[", i) -> i = doc.indexOf("]]>", i).takeIf { it >= 0 }?.plus(3) ?: return null
                doc.startsWith("<?", i) -> i = doc.indexOf("?>", i).takeIf { it >= 0 }?.plus(2) ?: return null
                else -> {
                    val (child, next) = elementAt(i) ?: return null
                    children += child
                    i = next
                }
            }
        }
    }

    private fun tagAt(at: Int): Tag? {
        var i = at + 1
        val nameStart = i
        while (i < doc.length && !doc[i].isWhitespace() && doc[i] != '>' && doc[i] != '/') i++
        val name = doc.substring(nameStart, i).takeIf { it.isNotEmpty() } ?: return null
        val attrs = ArrayList<Attr>()
        while (i < doc.length) {
            while (i < doc.length && doc[i].isWhitespace()) i++
            if (i >= doc.length) return null
            when (doc[i]) {
                '>' -> return Tag(name, at, i, false, attrs)
                '/' -> return if (doc.startsWith("/>", i)) Tag(name, at, i + 1, true, attrs) else null
            }
            val attrStart = i
            while (i < doc.length && doc[i] != '=' && !doc[i].isWhitespace()) i++
            val attrName = doc.substring(attrStart, i)
            while (i < doc.length && doc[i].isWhitespace()) i++
            if (i >= doc.length || doc[i] != '=') return null
            i++
            while (i < doc.length && doc[i].isWhitespace()) i++
            val quote = doc.getOrNull(i)?.takeIf { it == '"' || it == '\'' } ?: return null
            val valueStart = i + 1
            val valueEnd = doc.indexOf(quote, valueStart).takeIf { it >= 0 } ?: return null
            attrs += Attr(attrName, attrStart, valueStart, valueEnd)
            i = valueEnd + 1
        }
        return null
    }

    private companion object {
        fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}
