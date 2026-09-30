package io.github.matiyaaa.fuse

import android.os.Build
import java.io.File
import java.time.Instant

/**
 * Keeps the last crash and recent failed background tasks in Fuse's private files, for Settings to
 * show. Nothing leaves the device unless the user shares it. Query strings are removed from URLs in
 * messages, since request URLs can carry API keys.
 */
class CrashLog(private val dir: File, private val appVersion: String) {
    private val crashFile get() = File(dir, "last-crash.txt")
    private val errorsFile get() = File(dir, "background-errors.txt")

    /** Records crashes on any thread, then lets the previous handler end the process as before. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(crashFile, report("Fuse $appVersion crashed", thread, error))
            } catch (t: Throwable) {
                // Recording must never hide the crash itself.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A background task failed but Fuse keeps running. Appended, keeping the newest [MAX_ERRORS_CHARS]. */
    fun recordNonFatal(error: Throwable) {
        try {
            synchronized(this) {
                val entry = report("Background task failed", Thread.currentThread(), error)
                val old = if (errorsFile.isFile) errorsFile.readText() else ""
                write(errorsFile, (old + entry + "\n").takeLast(MAX_ERRORS_CHARS))
            }
        } catch (t: Throwable) {
            // Best effort.
        }
    }

    /** The last crash, then recent background errors; null when there is neither. */
    fun read(): String? = try {
        val parts = listOfNotNull(
            crashFile.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() },
            errorsFile.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() },
        )
        parts.joinToString("\n").ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    fun clear() {
        synchronized(this) {
            crashFile.delete()
            errorsFile.delete()
        }
    }

    private fun report(title: String, thread: Thread, error: Throwable): String = buildString {
        appendLine(title)
        appendLine("Time: ${Instant.now()}")
        appendLine("Version: $appVersion")
        appendLine("Thread: ${thread.name}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        appendLine()
        append(redact(error.stackTraceToString()))
    }

    private fun write(file: File, text: String) {
        if (!dir.isDirectory) dir.mkdirs()
        file.writeText(text)
    }

    companion object {
        const val MAX_ERRORS_CHARS = 20 * 1024
        private val QUERY = Regex("""(https?://[^\s?#]+)\?[^\s#]*""")

        /** Drops query strings from URLs (API keys travel there). */
        fun redact(text: String): String = QUERY.replace(text) { "${it.groupValues[1]}?[removed]" }
    }
}
