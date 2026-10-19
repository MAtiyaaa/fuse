package io.github.matiyaaa.fuse.launch.settings

/**
 * A MAME ini file (`mame.ini`, a system's own `<short name>.ini`), as MAME writes one
 * (src/lib/util/options.cpp `core_options::output_ini`): `#` comment lines for its headers, then one
 * option a line, its name padded to 25 characters, a space and its value, in double quotes when the
 * value has a space. Edited without disturbing anything else in it: every other line stays as it
 * was, byte for byte. There are no sections; every option is in "". A new option goes last, laid
 * out as MAME lays one out.
 */
class MameIniText(text: String) : SettingsText {
    private val crlf = text.contains("\r\n")
    private val lines: MutableList<String> = text.replace("\r\n", "\n").split('\n').toMutableList().also {
        if (it.size > 1 && it.last().isEmpty()) it.removeAt(it.lastIndex)
        if (it.size == 1 && it[0].isEmpty()) it.clear()
    }

    /** An option's line: its name, and where its value starts (after the spaces that pad the name). */
    private class Entry(val key: String, val valueStart: Int)

    override fun values(section: String, key: String): List<String> {
        val at = find(key) ?: return emptyList()
        val line = lines[at]
        val raw = line.substring(entry(line)!!.valueStart).trimEnd()
        return listOf(if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) raw.substring(1, raw.length - 1) else raw)
    }

    override fun set(section: String, key: String, value: String) {
        val written = if (' ' in value) "\"$value\"" else value
        val at = find(key)
        if (at != null) {
            lines[at] = lines[at].substring(0, entry(lines[at])!!.valueStart) + written
            return
        }
        lines += key.padEnd(25) + " " + written
    }

    override fun removeKey(section: String, key: String): Boolean {
        val at = find(key) ?: return false
        lines.removeAt(at)
        return true
    }

    override fun hasEntries(): Boolean = lines.any { entry(it) != null }

    override fun toString(): String {
        if (lines.isEmpty()) return ""
        val nl = if (crlf) "\r\n" else "\n"
        return lines.joinToString(nl) + nl
    }

    /** The last line setting [key] (MAME takes the last of several). */
    private fun find(key: String): Int? = lines.indexOfLast { entry(it)?.key == key }.takeIf { it >= 0 }

    private fun entry(line: String): Entry? {
        val t = line.trimStart()
        if (t.isEmpty() || t.startsWith("#")) return null
        val lead = line.length - t.length
        val nameEnd = t.indexOfFirst { it.isWhitespace() }.takeIf { it > 0 } ?: return null
        var start = lead + nameEnd
        while (start < line.length && line[start].isWhitespace()) start++
        return Entry(t.substring(0, nameEnd), start)
    }
}
