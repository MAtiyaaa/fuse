package io.github.matiyaaa.fuse.tune

/**
 * The entries of C++ arrays (`s_xxx_names = { "A", "B" }`), with the systems each is limited to by
 * the `#if` it sits in: `_WIN32` Windows, `__APPLE__` macOS, `__linux__` Linux, `__ANDROID__`
 * Android (`#ifndef`, `!defined(...)` and `#else` turn it round); any other condition (a build
 * option) leaves it for all, so the data can offer each system only what its build has.
 */
object Choices {
    private val HOSTS = mapOf("_WIN32" to "windows", "__APPLE__" to "macos", "__linux__" to "linux", "__ANDROID__" to "android")
    val ALL = HOSTS.values.toSet()

    /** One entry: the string literals in it, and the systems that have it (null for all). */
    data class Entry(val strings: List<String>, val hosts: Set<String>?) {
        /** What it is written as: its one string. */
        val value: String? get() = strings.firstOrNull()

        /** What it is called: the text of a `TRANSLATE_NOOP("Context", "Text")` entry, else its one string. */
        val label: String? get() = if (strings.size > 1) strings[1] else strings.firstOrNull()
    }

    /** Every `s_xxx_names` array in [text] (display names too) by its name. */
    fun arrays(text: String): Map<String, List<Entry>> {
        val out = HashMap<String, List<Entry>>()
        for (m in Regex("""\b(s_\w+_names)\s*=\s*\{""").findAll(text)) {
            val start = m.range.last
            val end = text.indexOf("};", start).takeIf { it > 0 } ?: continue
            out[m.groupValues[1]] = entries(text.substring(start + 1, end))
        }
        return out
    }

    /** The entries between an array's braces, split at the commas between them, `nullptr` left out. */
    fun entries(body: String): List<Entry> {
        val out = ArrayList<Entry>()
        val conditions = Conditions()
        val cur = StringBuilder()
        var hosts: Set<String>? = null
        var depth = 0
        var inString = false
        fun end() {
            val strings = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(cur).map { it.groupValues[1] }.toList()
            if (strings.isNotEmpty()) out += Entry(strings, hosts)
            cur.clear()
        }
        for (line in body.lines()) {
            if (!inString && depth == 0 && conditions.read(line.trim())) continue
            for (c in line + "\n") {
                if (cur.isBlank() && !c.isWhitespace()) hosts = conditions.hosts()
                when {
                    inString -> {
                        if (c == '"' && !cur.endsWith('\\')) inString = false
                        cur.append(c)
                    }
                    c == '"' -> { inString = true; cur.append(c) }
                    c == '(' || c == '{' -> { depth++; cur.append(c) }
                    c == ')' || c == '}' -> { depth--; cur.append(c) }
                    c == ',' && depth == 0 -> end()
                    else -> cur.append(c)
                }
            }
        }
        end()
        return out
    }

    /** The systems a preprocessor condition keeps, or null when it names none of them. */
    fun hostsOf(directive: String): Set<String>? {
        val d = directive.trim()
        val named = HOSTS.filterKeys { Regex("""\b$it\b""").containsMatchIn(d) }
        if (named.isEmpty()) return null
        val negative = d.startsWith("#ifndef") || named.keys.any { Regex("""!\s*(?:defined\s*\(?\s*)?$it\b""").containsMatchIn(d) }
        return if (negative) ALL - named.values.toSet() else named.values.toSet()
    }

    /** The `#if` blocks a line is in, as the systems each keeps (null for a condition naming none). */
    class Conditions {
        private val stack = ArrayList<Set<String>?>()

        /** Takes a preprocessor line into account; false for any other line. */
        fun read(line: String): Boolean {
            when {
                line.startsWith("#if") -> stack += hostsOf(line)
                line.startsWith("#elif") -> if (stack.isNotEmpty()) stack[stack.size - 1] = hostsOf(line.replaceFirst("#elif", "#if"))
                line.startsWith("#else") -> if (stack.isNotEmpty()) stack[stack.size - 1] = stack.last()?.let { ALL - it }
                line.startsWith("#endif") -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                line.startsWith("#") -> Unit
                else -> return false
            }
            return true
        }

        /** The systems every block the line is in keeps, or null when none limits it. */
        fun hosts(): Set<String>? =
            stack.filterNotNull().takeIf { it.isNotEmpty() }?.reduce { a, b -> a intersect b }?.takeIf { it != ALL }
    }
}
