package io.github.matiyaaa.fuse.launch.settings

/**
 * A JSON settings file of groups (shadPS4's `config.json` and a game's `custom_configs/<serial>.json`:
 * `{ "GPU": { "readbacks_mode": 0, ... }, ... }`), edited without disturbing anything else in it:
 * every other member, its order, spacing and line breaks stay as they were, byte for byte. A
 * section is a top-level member that is an object, a key one of its members. A value is read as
 * written (a string's text unescaped); one Fuse writes is a string when [isText] says the setting
 * holds text, else as it is (`true`, `0`, `1.5`). New members go last in their object, indented as
 * the ones beside them, the way nlohmann's `dump(2)` lays them out.
 */
class JsonText(text: String, private val isText: (section: String, key: String) -> Boolean = { _, _ -> false }) : SettingsText {
    private var doc = text

    /** A member of an object: where its key starts, where its value is, and what that is. */
    private class Member(val start: Int, val key: String, val valueStart: Int, val valueEnd: Int, val members: List<Member>?)

    /** The top-level object: where its braces are, and its members. */
    private class Obj(val open: Int, val close: Int, val members: List<Member>)

    override fun values(section: String, key: String): List<String> {
        val m = find(section, key) ?: return emptyList()
        val raw = doc.substring(m.valueStart, m.valueEnd)
        return when {
            raw.startsWith("\"") -> listOf(unquote(raw))
            raw.startsWith("{") || raw.startsWith("[") || raw == "null" -> emptyList()
            else -> listOf(raw)
        }
    }

    override fun set(section: String, key: String, value: String) {
        val written = encode(section, key, value)
        find(section, key)?.let { m ->
            doc = doc.substring(0, m.valueStart) + written + doc.substring(m.valueEnd)
            return
        }
        val top = top() ?: run {
            doc = "{\n  ${quote(section)}: {\n    ${quote(key)}: $written\n  }\n}\n" + doc.trimStart()
            return
        }
        val group = top.members.firstOrNull { it.key == section && it.members != null }
        if (group == null) {
            val indent = indentOf(top.members.lastOrNull()?.start) ?: "  "
            insert(top.open, top.close, top.members.lastOrNull()?.valueEnd, indent, "${quote(section)}: {\n$indent  ${quote(key)}: $written\n$indent}")
        } else {
            val open = group.valueStart
            val close = group.valueEnd - 1
            val indent = indentOf(group.members!!.lastOrNull()?.start) ?: ((indentOf(group.start) ?: "") + "  ")
            insert(open, close, group.members.lastOrNull()?.valueEnd, indent, "${quote(key)}: $written")
        }
    }

    override fun removeKey(section: String, key: String): Boolean {
        val group = top()?.members?.firstOrNull { it.key == section && it.members != null } ?: return false
        val list = group.members!!
        val i = list.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return false
        remove(list, i, group.valueStart, group.valueEnd - 1)
        return true
    }

    override fun hasEntries(): Boolean = top()?.members?.any { it.members == null || it.members.isNotEmpty() } == true

    override fun hasSection(section: String): Boolean = top()?.members?.any { it.key == section } == true

    override fun removeEmptySection(section: String, blankBefore: Boolean): Boolean {
        val top = top() ?: return false
        val i = top.members.indexOfFirst { it.key == section && it.members?.isEmpty() == true }.takeIf { it >= 0 } ?: return false
        remove(top.members, i, top.open, top.close)
        return true
    }

    override fun toString(): String = doc

    private fun find(section: String, key: String): Member? =
        top()?.members?.firstOrNull { it.key == section && it.members != null }?.members?.firstOrNull { it.key == key }

    /** [entry] as the last member of the object whose braces are at [open] and [close], after [last]'s value (none when it is empty). */
    private fun insert(open: Int, close: Int, last: Int?, indent: String, entry: String) {
        if (last == null) {
            // `{}` becomes the member on its own line, the closing brace where the object began.
            val outer = lineIndentAt(open)
            doc = doc.substring(0, open + 1) + "\n$indent$entry\n$outer" + doc.substring(close)
        } else {
            doc = doc.substring(0, last) + ",\n$indent$entry" + doc.substring(last)
        }
    }

    /** Takes out [list]'s member [i], with the comma that set it apart and its line. */
    private fun remove(list: List<Member>, i: Int, open: Int, close: Int) {
        val m = list[i]
        if (list.size == 1) {
            doc = doc.substring(0, open + 1) + doc.substring(close)
            return
        }
        // From the end of the one before (its comma goes) or, for the first, from after the brace.
        val from = if (i > 0) list[i - 1].valueEnd else lineStart(m.start).takeIf { it > open } ?: (open + 1)
        val to = if (i > 0) m.valueEnd else list[i + 1].start.let { lineStart(it).takeIf { s -> s > m.valueEnd } ?: it }
        doc = doc.substring(0, from) + doc.substring(to)
    }

    private fun lineStart(at: Int): Int = doc.lastIndexOf('\n', at - 1) + 1

    /** The spaces before [at] when it is the first thing on its line, else null. */
    private fun indentOf(at: Int?): String? {
        if (at == null) return null
        val start = lineStart(at)
        val before = doc.substring(start, at)
        return before.takeIf { it.isBlank() }
    }

    private fun lineIndentAt(at: Int): String {
        val start = lineStart(at)
        return doc.substring(start).takeWhile { it == ' ' || it == '\t' }
    }

    private fun encode(section: String, key: String, value: String): String = when {
        // A value already written as a string stays one; else as the setting holds it.
        find(section, key)?.let { doc[it.valueStart] == '"' } == true || isText(section, key) -> quote(value)
        value == "true" || value == "false" || NUMBER.matches(value) -> value
        else -> quote(value)
    }

    private fun top(): Obj? {
        val i = skip(0)
        if (i >= doc.length || doc[i] != '{') return null
        val (members, end) = obj(i, depth = 0) ?: return null
        return Obj(i, end, members)
    }

    /** The members of the object whose brace is at [open], and where its closing brace is; only the top two levels are read as members. */
    private fun obj(open: Int, depth: Int): Pair<List<Member>, Int>? {
        val out = ArrayList<Member>()
        var i = skip(open + 1)
        if (i < doc.length && doc[i] == '}') return out to i
        while (i < doc.length) {
            if (doc[i] != '"') return null
            val keyEnd = stringEnd(i) ?: return null
            val key = unquote(doc.substring(i, keyEnd))
            var j = skip(keyEnd)
            if (j >= doc.length || doc[j] != ':') return null
            j = skip(j + 1)
            if (j >= doc.length) return null
            val valueStart = j
            val (members, valueEnd) = if (doc[j] == '{' && depth < 1) {
                val (inner, close) = obj(j, depth + 1) ?: return null
                inner to close + 1
            } else {
                null to (valueEnd(j) ?: return null)
            }
            out += Member(i, key, valueStart, valueEnd, members)
            i = skip(valueEnd)
            when {
                i < doc.length && doc[i] == ',' -> i = skip(i + 1)
                i < doc.length && doc[i] == '}' -> return out to i
                else -> return null
            }
        }
        return null
    }

    /** Where the value starting at [at] ends: past a string, a nested object or array, or a bare word. */
    private fun valueEnd(at: Int): Int? = when (doc[at]) {
        '"' -> stringEnd(at)
        '{', '[' -> {
            var depth = 0
            var i = at
            var end: Int? = null
            while (i < doc.length) {
                when (doc[i]) {
                    '"' -> { i = (stringEnd(i) ?: return null) - 1 }
                    '{', '[' -> depth++
                    '}', ']' -> if (--depth == 0) { end = i + 1; break }
                }
                i++
            }
            end
        }
        else -> {
            var i = at
            while (i < doc.length && doc[i] !in ",}] \t\r\n") i++
            i
        }
    }

    /** Past the closing quote of the string starting at [at]. */
    private fun stringEnd(at: Int): Int? {
        var i = at + 1
        while (i < doc.length) {
            when (doc[i]) {
                '\\' -> i++
                '"' -> return i + 1
            }
            i++
        }
        return null
    }

    private fun skip(from: Int): Int {
        var i = from
        while (i < doc.length && doc[i].isWhitespace()) i++
        return i
    }

    private companion object {
        val NUMBER = Regex("""^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][-+]?\d+)?$""")

        fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
            }
            append('"')
        }

        fun unquote(raw: String): String {
            val body = raw.substring(1, raw.length - 1)
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    when (val n = body[i + 1]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> if (i + 6 <= body.length) {
                            out.append(body.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(n)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
