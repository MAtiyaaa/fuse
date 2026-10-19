package io.github.matiyaaa.fuse.launch.settings

/**
 * A TOML settings file of tables and plain values (Xenia's `xenia-canary.config.toml` and a game's
 * `config/<title id>.config.toml`: `[GPU]`, then `vsync = true    # Enable VSYNC.`), edited without
 * disturbing anything else in it: every other line, the spacing before a value's comment and the
 * comment itself stay as they were, byte for byte. A section is a table's header as written, a key
 * a bare key in it. A value is read as written (a string's text unescaped); one Fuse writes is a
 * basic string when [isText] says the setting holds text, else as it is (`true`, `60`, `1.5`).
 * A new key goes after the last one of its table, a new table at the end after a blank line.
 */
class TomlText(text: String, private val isText: (section: String, key: String) -> Boolean = { _, _ -> false }) : SettingsText {
    private val crlf = text.contains("\r\n")
    private val lines: MutableList<String> = text.replace("\r\n", "\n").split('\n').toMutableList().also {
        if (it.size > 1 && it.last().isEmpty()) it.removeAt(it.lastIndex)
        if (it.size == 1 && it[0].isEmpty()) it.clear()
    }

    /** A key's line: where its value starts and ends (before any comment and the spaces before it). */
    private class Entry(val key: String, val valueStart: Int, val valueEnd: Int)

    override fun values(section: String, key: String): List<String> {
        val at = find(section, key) ?: return emptyList()
        val e = entry(lines[at]) ?: return emptyList()
        val raw = lines[at].substring(e.valueStart, e.valueEnd)
        return when {
            raw.startsWith("\"") -> listOf(unquote(raw))
            raw.startsWith("'") -> listOf(raw.removeSurrounding("'"))
            raw.startsWith("[") || raw.startsWith("{") -> emptyList()
            else -> listOf(raw)
        }
    }

    override fun set(section: String, key: String, value: String) {
        find(section, key)?.let { at ->
            val line = lines[at]
            val e = entry(line) ?: return
            val quoted = line[e.valueStart] == '"' || line[e.valueStart] == '\'' || isText(section, key)
            lines[at] = line.substring(0, e.valueStart) + encode(value, quoted) + line.substring(e.valueEnd)
            return
        }
        val written = "$key = ${encode(value, isText(section, key))}"
        val header = headerAt(section)
        if (header == null) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "[$section]"
            lines += written
            return
        }
        // After the last key (or the header) of its table, before any blank lines that end it.
        var last: Int = header
        var i = header + 1
        while (i < lines.size && !isHeader(lines[i])) {
            if (entry(lines[i]) != null) last = i
            i++
        }
        lines.add(last + 1, written)
    }

    override fun removeKey(section: String, key: String): Boolean {
        val at = find(section, key) ?: return false
        lines.removeAt(at)
        return true
    }

    override fun hasEntries(): Boolean = lines.any { entry(it) != null }

    override fun hasSection(section: String): Boolean = headerAt(section) != null

    override fun separatesNewSection(): Boolean = lines.isNotEmpty() && lines.last().isNotBlank()

    override fun removeEmptySection(section: String, blankBefore: Boolean): Boolean {
        val header = headerAt(section) ?: return false
        var i = header + 1
        while (i < lines.size && !isHeader(lines[i])) {
            if (entry(lines[i]) != null) return false
            i++
        }
        lines.removeAt(header)
        if (blankBefore && header > 0 && lines[header - 1].isBlank()) lines.removeAt(header - 1)
        return true
    }

    override fun toString(): String {
        if (lines.isEmpty()) return ""
        val nl = if (crlf) "\r\n" else "\n"
        return lines.joinToString(nl) + nl
    }

    private fun isHeader(line: String): Boolean = line.trimStart().startsWith("[")

    private fun headerAt(section: String): Int? = lines.indexOfFirst { l ->
        val t = l.trim()
        t.startsWith("[") && !t.startsWith("[[") && t.substringBefore('#').trim().removePrefix("[").removeSuffix("]").trim() == section
    }.takeIf { it >= 0 }

    private fun find(section: String, key: String): Int? {
        val header = headerAt(section) ?: return null
        var i = header + 1
        while (i < lines.size && !isHeader(lines[i])) {
            if (entry(lines[i])?.key == key) return i
            i++
        }
        return null
    }

    /** A `key = value` line's key and value span; null for a comment, blank or anything else. */
    private fun entry(line: String): Entry? {
        val t = line.trimStart()
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("[")) return null
        val eq = line.indexOf('=').takeIf { it > 0 } ?: return null
        val key = line.substring(0, eq).trim().removeSurrounding("\"")
        if (key.isEmpty() || key.any { it.isWhitespace() }) return null
        var start = eq + 1
        while (start < line.length && (line[start] == ' ' || line[start] == '\t')) start++
        var end = start
        var quote: Char? = null
        while (end < line.length) {
            val c = line[end]
            when {
                quote != null && c == '\\' && quote == '"' -> end++
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c == '#' -> break
            }
            end++
        }
        var valueEnd = minOf(end, line.length)
        while (valueEnd > start && (line[valueEnd - 1] == ' ' || line[valueEnd - 1] == '\t')) valueEnd--
        return Entry(key, start, valueEnd)
    }

    private fun encode(value: String, quoted: Boolean): String = when {
        quoted -> quote(value)
        value == "true" || value == "false" || NUMBER.matches(value) -> value
        else -> quote(value)
    }

    private companion object {
        val NUMBER = Regex("""^[+-]?(?:0|[1-9][\d_]*)(?:\.\d+)?(?:[eE][-+]?\d+)?$|^0x[0-9A-Fa-f_]+$""")

        fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> append(c)
            }
            append('"')
        }

        fun unquote(raw: String): String {
            val body = raw.removeSurrounding("\"")
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    when (val n = body[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'u' -> if (i + 6 <= body.length) { out.append(body.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
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
