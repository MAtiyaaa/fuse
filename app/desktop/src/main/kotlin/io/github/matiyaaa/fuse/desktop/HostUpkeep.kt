package io.github.matiyaaa.fuse.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Keeps the background host on the newest Fuse, without anyone opening Fuse on its computer.
 *
 * - When the program the service runs from (the AppImage, or the installed launcher) is replaced by
 *   an update, the host stops once the new one is in place; the system starts it again on it.
 * - When the household's devices already run a newer Fuse than the host, the host fetches that
 *   update itself where Fuse can update in place ([update]: Linux), then stops the same way.
 *
 * Without this, a host set up once kept running the Fuse it started with until the computer
 * restarted, or until a newer Fuse was opened on that same computer.
 */
internal class HostUpkeep(
    /** The program the service starts, or null when it isn't known (a development run). */
    private val program: File?,
    /** The newest Fuse the household's devices run, when newer than this host's. */
    private val newerFuse: () -> String?,
    /** Installs the newest Fuse in place of [program]; null where Fuse can't update itself here. */
    private val update: (suspend () -> Result<Unit>)?,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val startedAs: String? = program?.let(::signature)
    private var seenChanged: String? = null
    private var announced: String? = null
    private var lastTry = Long.MIN_VALUE / 2

    /** One look; true when the host should stop so the system starts it again on the new Fuse. */
    suspend fun check(): Boolean {
        val sig = signatureNow()
        if (startedAs != null && sig != null && sig != startedAs) {
            // Only once the file holds still: an update may still be writing it.
            if (sig == seenChanged) {
                // Said once: the host may hold the start a while (its data folder unusable), asking again each minute.
                if (announced != sig) log("Fuse was updated on this computer: the host starts again on the new version")
                announced = sig
                return true
            }
            seenChanged = sig
            return false
        }
        seenChanged = null
        val newer = newerFuse() ?: return false
        val install = update ?: return false
        if (now() - lastTry < RETRY_MS) return false
        lastTry = now()
        log("A device runs Fuse $newer, newer than this host's: updating the host")
        install().onFailure { log("The host couldn't update itself: ${it.message ?: it::class.simpleName}") }
        return false
    }

    private fun signatureNow(): String? = program?.let(::signature)

    companion object {
        /** How often the host looks. */
        const val EVERY_MS = 60_000L

        /** How long after a failed or finished update the host tries again. */
        const val RETRY_MS = 6 * 60 * 60_000L

        /** What tells one copy of a program from another: its file's identity, size and time. */
        internal fun signature(f: File): String? = runCatching {
            val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
            "${a.fileKey()}:${a.size()}:${a.lastModifiedTime().toMillis()}"
        }.getOrNull()
    }
}
