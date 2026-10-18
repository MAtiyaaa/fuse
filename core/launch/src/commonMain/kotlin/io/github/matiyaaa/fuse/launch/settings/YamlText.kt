package io.github.matiyaaa.fuse.launch.settings

/**
 * An emulator's settings file Fuse edits in place: a value read, set or taken out, and every other
 * line kept exactly as it was. A section is where the key is kept: an ini file's `[Section]`, or
 * the nodes of a YAML file joined by [YamlText.PATH] (RPCS3's `Video`, then `Vulkan`).
 */
interface SettingsText {
    fun values(section: String, key: String): List<String>
    fun set(section: String, key: String, value: String)
    fun removeKey(section: String, key: String): Boolean
    fun hasEntries(): Boolean
}

/**
 * A YAML settings file (RPCS3's `config.yml` and a game's `custom_configs/config_<serial>.yml`)
 * edited without disturbing anything else in it: comments, order, spacing, quoting and every other
 * key stay as they were, byte for byte. It reads the block mappings these files are made of (a key,
 * then its value or, indented under it, its own keys); a value is written plain where YAML reads it
 * back the same, else in double quotes. Keys match exactly, as YAML's do. Lists and other values
 * spread over several lines are left alone.
 */
class YamlText(text: String) : SettingsText {
    private val crlf = text.contains("\r\n")
    private val lines: MutableList<String> = text.replace("\r\n", "\n").split('\n').toMutableList().also {
        if (it.size > 1 && it.last().isEmpty()) it.removeAt(it.lastIndex)
        if (it.size == 1 && it[0].isEmpty()) it.clear()
    }

    /** A line holding a key: how far it is indented, its key, where its colon is and what is written after it. */
    private data class Entry(val indent: Int, val key: String, val colon: Int, val rest: String)

    override fun values(section: String, key: String): List<String> {
        val (range, indent) = block(pathOf(section)) ?: return emptyList()
        val at = child(range, indent, key) ?: return emptyList()
        val e = entry(lines[at]) ?: return emptyList()
        return if (e.rest.isEmpty()) emptyList() else listOf(unquote(e.rest))
    }

    /** The keys directly under [section], in order. */
    fun keys(section: String): List<String> {
        val (range, indent) = block(pathOf(section)) ?: return emptyList()
        return range.mapNotNull { i -> entry(lines[i])?.takeIf { it.indent == indent }?.key }
    }

    override fun set(section: String, key: String, value: String) {
        val path = pathOf(section)
        val (range, indent) = block(path) ?: make(path)
        val at = child(range, indent, key)
        if (at != null) {
            val line = lines[at]
            // Only what follows the key changes: its spelling and indentation stay.
            lines[at] = line.substring(0, entry(line)!!.colon) + ": " + quote(value)
            return
        }
        val pad = indent ?: (depth(path) * STEP)
        lines.add(endOf(range, path), " ".repeat(pad) + plainKey(key) + ": " + quote(value))
    }

    override fun removeKey(section: String, key: String): Boolean {
        val (range, indent) = block(pathOf(section)) ?: return false
        val at = child(range, indent, key) ?: return false
        // A key with its own keys under it isn't one value.
        if (entry(lines[at])!!.rest.isEmpty()) return false
        lines.removeAt(at)
        return true
    }

    override fun hasEntries(): Boolean = lines.any { entry(it)?.rest?.isNotEmpty() == true }

    override fun toString(): String {
        val sep = if (crlf) "\r\n" else "\n"
        return if (lines.isEmpty()) "" else lines.joinToString(sep) + sep
    }

    private fun pathOf(section: String): List<String> = if (section.isEmpty()) emptyList() else section.split(PATH)

    /** How many nodes deep [path] is, as written by Fuse when it makes them. */
    private fun depth(path: List<String>) = path.size

    /**
     * The lines inside the node at [path] (after its own line; the whole file for no path) and the
     * indent of the keys directly in it (null when it has none yet); null when the node isn't there.
     */
    private fun block(path: List<String>): Pair<IntRange, Int?>? {
        var range: IntRange = lines.indices
        var parent = -1
        for (part in path) {
            val indent = childIndent(range, parent)
            val at = child(range, indent, part) ?: return null
            val own = entry(lines[at])!!
            if (own.rest.isNotEmpty()) return null
            var end = at + 1
            while (end <= range.last && (quiet(lines[end]) || indentOf(lines[end]) > own.indent)) end++
            range = (at + 1) until end
            parent = own.indent
        }
        return range to childIndent(range, parent)
    }

    /** Makes the nodes of [path] that aren't there, and gives back the innermost one's block. */
    private fun make(path: List<String>): Pair<IntRange, Int?> {
        for (n in 1..path.size) {
            if (block(path.take(n)) != null) continue
            val parentPath = path.take(n - 1)
            val (range, indent) = block(parentPath)!!
            val pad = indent ?: ((n - 1) * STEP)
            lines.add(endOf(range, parentPath), " ".repeat(pad) + plainKey(path[n - 1]) + ":")
        }
        return block(path)!!
    }

    /** Where a new line goes in [range]: after the last line in it that says something. */
    private fun endOf(range: IntRange, path: List<String>): Int {
        if (path.isEmpty()) {
            var at = lines.size
            while (at > 0 && quiet(lines[at - 1])) at--
            return at
        }
        var at = range.first
        for (i in range) if (!quiet(lines[i])) at = i + 1
        return at
    }

    private fun child(range: IntRange, indent: Int?, key: String): Int? {
        if (indent == null) return null
        return range.firstOrNull { i -> entry(lines[i])?.let { it.indent == indent && it.key == key } == true }
    }

    private fun childIndent(range: IntRange, parent: Int): Int? =
        range.firstNotNullOfOrNull { i -> entry(lines[i])?.indent?.takeIf { it > parent } }

    private fun indentOf(line: String) = line.length - line.trimStart(' ').length

    /** A blank line or a comment, which belongs to no key. */
    private fun quiet(line: String): Boolean = line.isBlank() || line.trimStart().startsWith('#')

    private fun entry(line: String): Entry? {
        if (quiet(line)) return null
        val indent = indentOf(line)
        val t = line.trim()
        if (t.startsWith("- ") || t == "-" || t == "---" || t == "..." || t.startsWith('[') || t.startsWith('{')) return null
        if (t.startsWith('"') || t.startsWith('\'')) {
            val q = t[0]
            var i = 1
            while (i < t.length && t[i] != q) i += if (t[i] == '\\' && q == '"') 2 else 1
            if (i >= t.length || t.getOrNull(i + 1) != ':') return null
            return Entry(indent, unquote(t.substring(0, i + 1)), indent + i + 1, t.substring(i + 2).trim())
        }
        val colon = t.indexOf(": ").takeIf { it > 0 } ?: if (t.endsWith(':') && t.length > 1) t.length - 1 else return null
        return Entry(indent, t.substring(0, colon).trim(), indent + colon, t.substring(colon + 1).trim())
    }

    companion object {
        /** Joins the nodes of a YAML section. */
        const val PATH = "\u001f"

        /** How far Fuse indents a node it makes, as RPCS3 writes its own. */
        private const val STEP = 2

        /** A value as YAML reads it back: quotes taken off, escapes read, a trailing comment left out. */
        fun unquote(raw: String): String {
            val t = raw.trim()
            if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
                val body = t.substring(1, t.length - 1)
                val out = StringBuilder()
                var i = 0
                while (i < body.length) {
                    val c = body[i]
                    if (c == '\\' && i + 1 < body.length) {
                        out.append(when (val n = body[i + 1]) { 'n' -> '\n'; 't' -> '\t'; '0' -> '\u0000'; else -> n })
                        i += 2
                    } else {
                        out.append(c)
                        i++
                    }
                }
                return out.toString()
            }
            if (t.length >= 2 && t.startsWith('\'') && t.endsWith('\'')) return t.substring(1, t.length - 1).replace("''", "'")
            val comment = t.indexOf(" #")
            return if (comment >= 0) t.substring(0, comment).trimEnd() else t
        }

        /** [value] written so YAML reads it back as the same text. */
        fun quote(value: String): String {
            // `-`, `?` and `:` may start a plain value when something other than a space follows (`-1`).
            val start = value.firstOrNull()?.let { c -> c !in "!&*|>'\"%@`[]{},#" && (c !in "-?:" || value.getOrNull(1)?.isWhitespace() == false) } ?: false
            val plain = start && value == value.trim() && ": " !in value && " #" !in value && !value.endsWith(':') && value.none { it < ' ' }
            if (plain) return value
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""
        }

        private fun plainKey(key: String): String = if (key == quote(key)) key else quote(key)
    }
}
