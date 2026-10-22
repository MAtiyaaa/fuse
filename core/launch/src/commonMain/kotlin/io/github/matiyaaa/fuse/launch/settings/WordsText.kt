package io.github.matiyaaa.fuse.launch.settings

/**
 * A config of one setting a line, its name then its value after spaces, edited without disturbing
 * anything else in it: every other line, its order and spacing stay as they were, and the file keeps
 * its own line endings. Two engines write them:
 *
 * - The Doom ports (Woof!, Nugget Doom, dsda-doom: `M_SaveDefaults`): `name   value`, the name
 *   padded, a number bare and text in double quotes. [command] is null.
 * - sm64coopdx (`configfile_save`): `name value`, text as it is, never quoted ([bare]).
 * - EDGE-Classic (`WriteConsoleVariables`): `/name<tab>"value"`, every value in quotes. [command]
 *   is [SLASH]: the name is the first word's, after its slash.
 * - The Quake engines (`Cvar_WriteVariables`): `seta name "value"`, every value in quotes. [command]
 *   is `seta`; a line set with `set` is read too.
 *
 * A line that starts with `//` or `#` is a comment. Of a name written twice the last counts, as each
 * engine reads its file top to bottom; that is the one changed. A new setting goes at the end, as
 * the engine writes one. These files have no sections: [section] must be empty. A value can't hold a
 * double quote or a line break, which neither engine reads back; [set] refuses one.
 */
class WordsText(
    text: String,
    private val command: String? = null,
    private val bare: Boolean = false,
    private val isText: (String) -> Boolean = { false },
) : SettingsText {
    private val crlf = text.contains("\r\n")
    private val lines: MutableList<String> = text.replace("\r\n", "\n").split('\n').toMutableList().also {
        if (it.size > 1 && it.last().isEmpty()) it.removeAt(it.lastIndex)
        if (it.size == 1 && it[0].isEmpty()) it.clear()
    }
    private val endsWithBreak = text.endsWith("\n") || text.isEmpty()

    /** The words of [line] (a quoted one as one word, its quotes off), or null for a comment or a blank. */
    private fun words(line: String): List<String>? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("//") || t.startsWith("#")) return null
        val out = ArrayList<String>()
        var i = 0
        while (i < t.length) {
            while (i < t.length && t[i].isWhitespace()) i++
            if (i >= t.length) break
            if (t[i] == '"') {
                val end = t.indexOf('"', i + 1)
                out += if (end < 0) t.substring(i + 1) else t.substring(i + 1, end)
                i = if (end < 0) t.length else end + 1
            } else {
                val start = i
                while (i < t.length && !t[i].isWhitespace()) i++
                out += t.substring(start, i)
            }
        }
        return out
    }

    /** The name a line sets and its value, or null. */
    private fun entry(line: String): Pair<String, String>? {
        val w = words(line) ?: return null
        return if (command == SLASH) {
            w.getOrNull(0)?.takeIf { it.length > 1 && it.startsWith("/") }?.let { it.substring(1) to w.getOrElse(1) { "" } }
        } else if (command == null) {
            w.getOrNull(0)?.let { it to w.drop(1).joinToString(" ") }
        } else {
            if (w.size < 2 || !(w[0].equals(command, ignoreCase = true) || w[0].equals("set", ignoreCase = true))) return null
            w[1] to w.getOrElse(2) { "" }
        }
    }

    /** Where [key] is last set (the one the engine ends up with), or -1. */
    private fun indexOf(key: String): Int = lines.indexOfLast { entry(it)?.first.equals(key, ignoreCase = true) }

    override fun values(section: String, key: String): List<String> {
        if (section.isNotEmpty()) return emptyList()
        val at = indexOf(key)
        return if (at < 0) emptyList() else listOf(entry(lines[at])!!.second)
    }

    override fun set(section: String, key: String, value: String) {
        require(section.isEmpty()) { "This config has no sections" }
        require(key.isNotEmpty() && key.none { it.isWhitespace() || it == '"' }) { "Not a name the engine reads: $key" }
        require('"' !in value && '\n' !in value && '\r' !in value) { "The engine can't read a value with a quote or a line break back" }
        val at = indexOf(key)
        // Quoted as the engine writes it: every value for the Quake engines, text for the Doom ports.
        val written = when {
            bare -> value
            command != null || isText(key) || value.isEmpty() || value.any { it.isWhitespace() } -> "\"$value\""
            else -> value
        }
        if (at >= 0) {
            val line = lines[at]
            val indent = line.takeWhile { it.isWhitespace() }
            val body = line.trimStart()
            // Keep its name, and its padding (Woof! pads every name to the longest), and put the value after it.
            val head = if (command == SLASH) {
                Regex("""^(\S+\s+)""").find(body)?.value ?: "/$key\t"
            } else if (command != null) {
                Regex("""^(\S+\s+\S+\s+)""").find(body)?.value ?: "$command $key "
            } else {
                Regex("""^(\S+\s+)""").find(body)?.value ?: "$key "
            }
            lines[at] = indent + head + written
        } else {
            lines.add(
                when (command) {
                    SLASH -> "/$key\t$written"
                    null -> "$key $written"
                    else -> "$command $key $written"
                },
            )
        }
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

    companion object {
        /** [command] for EDGE-Classic's lines, each name after a slash. */
        const val SLASH = "/"
    }
}
