package io.github.matiyaaa.fuse.integrations.obtainium

/**
 * A project's address as a person pastes it, read as the repository whose releases Fuse follows:
 * on GitHub, or on a Forgejo or Gitea server (Codeberg, a project's own like Eden's), whose release
 * list has the same shape. Only HTTPS: a plain `http://` address is refused, and one with no scheme
 * is taken as HTTPS. Whatever follows the project (`/releases`, `/tree/main`, `.git`) is dropped.
 */
data class ReleaseLink(val kind: Kind, val server: String, val owner: String, val project: String) {
    enum class Kind { GITHUB, FORGE }

    /** The repository's own address. */
    val url: String get() = "https://$server/$owner/$project"

    /** Where its releases are shown. */
    val releasesPage: String get() = "$url/releases"

    companion object {
        /** [text] as a repository, or null when it isn't one Fuse can read releases from. */
        fun parse(text: String): ReleaseLink? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val rest = when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
                "://" in trimmed -> return null
                else -> trimmed
            }
            val parts = rest.substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
            if (parts.size < 3) return null
            val server = parts[0].lowercase().removePrefix("www.")
            if (!HOST.matches(server) || '.' !in server) return null
            val owner = parts[1]
            val project = parts[2].removeSuffix(".git")
            if (listOf(owner, project).any { !NAME.matches(it) || it.all { c -> c == '.' } }) return null
            val kind = if (server == "github.com") Kind.GITHUB else Kind.FORGE
            return ReleaseLink(kind, server, owner, project)
        }

        private val HOST = Regex("^[a-z0-9.-]+(:[0-9]{1,5})?$")
        private val NAME = Regex("^[A-Za-z0-9_.-]+$")
    }
}
