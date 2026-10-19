package io.github.matiyaaa.fuse.launch.settings

/**
 * A RetroArch-style config (`retroarch.cfg`, a core's or a game's override `.cfg`, a core options
 * `.opt` file) edited without disturbing anything else in it: comments, `#include` and `#reference`
 * lines, order, spacing and every other key stay as they were, byte for byte, and the file keeps its
 * own line endings. Read as RetroArch v1.22.2 reads it (`libretro-common/file/config_file.c`,
 * `config_file_parse_line` and `config_file_extract_value`): a key is the first run of visible
 * characters, then `=`, then a value in double quotes (up to the next quote) or without (up to the
 * next space); a `#` outside quotes starts a comment; and of a key written twice only the first
 * counts (`RHMAP_HAS_FULL` before `RHMAP_SET_FULL`), so that is the one read and changed here. New
 * keys are written as RetroArch writes them (`config_file_dump`: `key = "value"`) at the end.
 *
 * These files have no sections: [section] must be empty. A value can't hold a double quote or a line
 * break, which RetroArch has no way to read back; [set] refuses one.
 */
class CfgText(text: String) : SettingsText {
    private val crlf = text.contains("\r\n")
    private val lines: MutableList<String> = text.replace("\r\n", "\n").split('\n').toMutableList().also {
        if (it.size > 1 && it.last().isEmpty()) it.removeAt(it.lastIndex)
        if (it.size == 1 && it[0].isEmpty()) it.clear()
    }
    /** Whether the file ends with a line break (as RetroArch writes it, and as a new file will). */
    private val endsWithBreak = text.endsWith("\n") || text.isEmpty()

    /** The key a line sets and its value, as RetroArch reads it; null for a comment, a blank or a line it skips. */
    private fun entry(line: String): Pair<String, String>? {
        val body = stripComment(line) ?: return null
        var i = 0
        while (i < body.length && body[i].isWhitespace()) i++
        val keyStart = i
        while (i < body.length && visible(body[i])) i++
        if (i == keyStart) return null
        val key = body.substring(keyStart, i)
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != '=') return null
        i++
        while (i < body.length && body[i].isWhitespace()) i++
        val value = when {
            i >= body.length -> ""
            body[i] == '"' -> {
                val end = body.indexOf('"', i + 1)
                if (end < 0) body.substring(i + 1) else body.substring(i + 1, end)
            }
            else -> {
                var j = i
                while (j < body.length && visible(body[j])) j++
                body.substring(i, j)
            }
        }
        return key to value
    }

    /** RetroArch's `isgraph`: a printable character that isn't a space. */
    private fun visible(c: Char) = c > ' ' && c != '\u007f'

    /**
     * The line without its comment, as `config_file_strip_comment` leaves it; null when the whole
     * line is a comment (or an `#include`, which isn't a key either).
     */
    private fun stripComment(line: String): String? {
        val hash = line.indexOf('#')
        if (hash < 0) return line
        if (hash == 0) return null
        val quote = line.indexOf('"')
        if (quote in 0 until hash) {
            val end = line.indexOf('"', quote + 1)
            if (end > hash) return line
        }
        return line.substring(0, hash)
    }

    /** Where [key] is set, the first time (the one RetroArch reads), or -1. */
    private fun indexOf(key: String): Int = lines.indexOfFirst { entry(it)?.first == key }

    override fun values(section: String, key: String): List<String> {
        if (section.isNotEmpty()) return emptyList()
        val at = indexOf(key)
        return if (at < 0) emptyList() else listOf(entry(lines[at])!!.second)
    }

    override fun set(section: String, key: String, value: String) {
        require(section.isEmpty()) { "A RetroArch config has no sections" }
        require(key.isNotEmpty() && key.all(::visible) && '=' !in key && '#' !in key) { "Not a key RetroArch reads: $key" }
        require('"' !in value && '\n' !in value && '\r' !in value) { "RetroArch can't read a value with a quote or a line break back" }
        val line = "$key = \"$value\""
        val at = indexOf(key)
        if (at >= 0) lines[at] = line else lines.add(line)
    }

    override fun removeKey(section: String, key: String): Boolean {
        if (section.isNotEmpty()) return false
        val at = indexOf(key)
        if (at < 0) return false
        lines.removeAt(at)
        return true
    }

    override fun hasEntries(): Boolean = lines.any { entry(it) != null }

    override fun toString(): String {
        if (lines.isEmpty()) return ""
        val sep = if (crlf) "\r\n" else "\n"
        return lines.joinToString(sep) + if (endsWithBreak) sep else ""
    }
}
