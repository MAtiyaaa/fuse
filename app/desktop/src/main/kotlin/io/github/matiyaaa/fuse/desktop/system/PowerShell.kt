package io.github.matiyaaa.fuse.desktop.system

import java.io.File
import java.util.Base64

/**
 * Windows PowerShell 5.1, which every Windows 10 and 11 has, for what Java can't reach (DPAPI, the
 * Run key, the battery). Scripts go through `-EncodedCommand`, so nothing in them is ever re-quoted
 * by a command line; data a script needs goes through stdin.
 */
object PowerShell {
    val path: String by lazy {
        val root = System.getenv("SystemRoot") ?: "C:\\Windows"
        File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe").takeIf { it.isFile }?.path ?: "powershell.exe"
    }

    /** Runs [script]; null when PowerShell is missing or it took longer than [timeoutMs]. */
    fun run(script: String, stdin: String? = null, timeoutMs: Long = 10_000): Processes.Output? {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return Processes.run(
            listOf(path, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded),
            timeoutMs = timeoutMs,
            stdin = stdin,
        )
    }

    /** [text] as a single-quoted PowerShell string (quotes doubled, typographic ones too). */
    fun literal(text: String): String {
        val sb = StringBuilder("'")
        for (c in text) {
            sb.append(c)
            if (c == '\'' || c in '\u2018'..'\u201B') sb.append(c)
        }
        return sb.append('\'').toString()
    }
}
