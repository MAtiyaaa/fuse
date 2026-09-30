package io.github.matiyaaa.fuse.launch.linux

/** Minimal shell-style glob: `*` any run, `?` one character, everything else literal. Case-insensitive by default. */
internal object Glob {
    fun matches(pattern: String, name: String, ignoreCase: Boolean = true): Boolean = toRegex(pattern, ignoreCase).matches(name)

    private fun toRegex(pattern: String, ignoreCase: Boolean): Regex {
        val sb = StringBuilder()
        for (c in pattern) when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(c.toString()))
        }
        return if (ignoreCase) Regex(sb.toString(), RegexOption.IGNORE_CASE) else Regex(sb.toString())
    }
}
