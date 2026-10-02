package io.github.matiyaaa.fuse.ui.shell.store.impl

/**
 * A diagnostics report: plain text a person can read before sharing it with a bug report. Built
 * only when the user asks, previewed before it is saved, never sent anywhere by Fuse.
 *
 * What it never contains: keys, passwords or tokens (only whether one is stored), ROM or firmware
 * contents, or personal folder names (the home folder becomes `~`, user folders `<user>`).
 */
internal object DiagnosticsReport {
    class Section(val title: String, val lines: List<String>)

    fun render(sections: List<Section>, home: String?): String = buildString {
        appendLine("Fuse diagnostics report")
        appendLine("Made on request and shown before saving. No keys, passwords, game or firmware contents.")
        for (section in sections) {
            appendLine()
            appendLine("## ${section.title}")
            if (section.lines.isEmpty()) appendLine("(nothing)")
            section.lines.forEach { appendLine(redact(it, home)) }
        }
    }.trimEnd() + "\n"

    private val QUERY = Regex("""(https?://[^\s?#]+)\?[^\s#]*""")
    private val SECRET_PARAM = Regex("""(?i)\b(api[_-]?key|apikey|token|password|passwd|secret|key|y|devpassword|sspassword)=([^&\s]+)""")
    private val BEARER = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+""")
    private val LINUX_USER = Regex("""/(home|run/media|media)/([^/\s]+)""")
    private val MAC_USER = Regex("""/Users/([^/\s]+)""")
    private val WINDOWS_USER = Regex("""(?i)([A-Z]:[/\\]Users[/\\])([^/\\\s]+)""")

    /** [text] without anything personal or secret: the home folder as `~`, user names as `<user>`, query strings and keys dropped. */
    fun redact(text: String, home: String?): String {
        var t = text
        if (!home.isNullOrBlank() && home.length > 1) t = t.replace(home.trimEnd('/', '\\'), "~")
        t = QUERY.replace(t) { "${it.groupValues[1]}?[removed]" }
        t = SECRET_PARAM.replace(t) { "${it.groupValues[1]}=[removed]" }
        t = BEARER.replace(t, "Bearer [removed]")
        t = LINUX_USER.replace(t) { "/${it.groupValues[1]}/<user>" }
        t = MAC_USER.replace(t, "/Users/<user>")
        t = WINDOWS_USER.replace(t) { "${it.groupValues[1]}<user>" }
        return t
    }
}
