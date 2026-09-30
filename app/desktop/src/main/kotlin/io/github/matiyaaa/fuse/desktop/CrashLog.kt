package io.github.matiyaaa.fuse.desktop

import java.io.File
import java.time.Instant

/**
 * Keeps the last crash in Fuse's data folder, for Settings to show. Nothing leaves the computer
 * unless the user shares it. Query strings are removed from URLs, since request URLs can carry keys.
 */
class CrashLog(private val dir: File) {
    private val crashFile get() = File(dir, "last-crash.txt")

    /** Records crashes on any thread, then lets the previous handler run as before. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (!dir.isDirectory) dir.mkdirs()
                crashFile.writeText(report(thread, error))
            } catch (t: Throwable) {
                // Recording must never hide the crash itself.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(): String? = try {
        crashFile.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    fun clear() {
        crashFile.delete()
    }

    private fun report(thread: Thread, error: Throwable): String = buildString {
        appendLine("Fuse ${BuildInfo.VERSION} crashed")
        appendLine("Time: ${Instant.now()}")
        appendLine("Thread: ${thread.name}")
        appendLine("System: ${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}")
        appendLine()
        append(QUERY.replace(error.stackTraceToString()) { "${it.groupValues[1]}?[removed]" })
    }

    private companion object {
        val QUERY = Regex("""(https?://[^\s?#]+)\?[^\s#]*""")
    }
}
