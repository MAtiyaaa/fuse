package io.github.matiyaaa.fuse.desktop.services

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The version an update replaced, kept beside Fuse's AppImage as `.<name>.previous`, and what is
 * known about it: its version, the library schema it reads, and when it was replaced.
 */
internal data class PreviousFuse(val version: String, val schema: Long, val replacedAt: Long) {
    fun write(file: File) {
        file.writeText("version=$version\nschema=$schema\nreplacedAt=$replacedAt\n")
    }

    companion object {
        fun read(file: File): PreviousFuse? {
            val values = runCatching { file.readLines() }.getOrNull()?.mapNotNull { line ->
                line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
            }?.toMap() ?: return null
            val version = values["version"]?.takeIf { it.isNotBlank() } ?: return null
            return PreviousFuse(version, values["schema"]?.toLongOrNull() ?: 0L, values["replacedAt"]?.toLongOrNull() ?: 0L)
        }
    }
}

/**
 * A Fuse update on trial. After an update replaces the AppImage (or a Mac's Fuse.app), the new version has to show it
 * works before the one it replaced stops being the safe one: each start of the new version is
 * counted ([onStart]), and once its window has been up a while it is [healthy] and the trial ends.
 * When it has started [MAX_STARTS] times without getting that far (it crashed, or closed at once),
 * the next start puts the version before it back and starts that instead ([Start.WentBack]).
 *
 * A version that fails before Fuse's own code runs can't count its starts; Settings, About, Go Back
 * covers that, and so does starting the `.previous` copy by hand.
 */
internal class UpdateTrial(private val file: File) {
    data class Trial(val target: String, val version: String, val from: String, val starts: Int)

    sealed interface Start {
        /** No update on trial, or this isn't the version on trial. */
        data object Normal : Start

        /** The version on trial, counted. */
        data class OnTrial(val starts: Int) : Start

        /** The version on trial failed [MAX_STARTS] times; [target] now holds [from] again, to start. */
        data class WentBack(val target: String, val version: String, val from: String) : Start
    }

    fun begin(target: String, version: String, from: String) {
        write(Trial(target, version, from, 0))
    }

    fun read(): Trial? {
        val values = runCatching { file.readLines() }.getOrNull()?.mapNotNull { line ->
            line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
        }?.toMap() ?: return null
        return Trial(
            target = values["target"] ?: return null,
            version = values["version"] ?: return null,
            from = values["from"].orEmpty(),
            starts = values["starts"]?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Counts a start of [version] running from [running]; on the start after [MAX_STARTS] failed
     * ones it puts the previous AppImage back (keeping the failed one as `.<name>.failed`).
     */
    fun onStart(running: String?, version: String): Start {
        val trial = read() ?: return Start.Normal
        // The version on trial: the one the update named, or (when its name didn't say) any but the one it replaced.
        val onTrial = if (trial.version.isNotEmpty()) version == trial.version else version != trial.from
        if (running == null || File(running).absolutePath != File(trial.target).absolutePath || !onTrial) return Start.Normal
        val starts = trial.starts + 1
        if (starts <= MAX_STARTS) {
            write(trial.copy(starts = starts))
            return Start.OnTrial(starts)
        }
        val target = File(trial.target)
        val previous = previousOf(target)
        // A file (an AppImage) or a folder (a Mac's Fuse.app).
        if (!previous.exists()) {
            // Nothing to go back to: carry on with this one, and stop counting.
            end()
            return Start.Normal
        }
        return try {
            val failed = File(target.parentFile, ".${target.name}.failed")
            Files.move(target.toPath(), failed.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(previous.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(failed.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                throw e
            }
            infoOf(target).delete()
            end()
            Start.WentBack(target.path, trial.version, trial.from)
        } catch (e: IOException) {
            end()
            Start.Normal
        }
    }

    /** The version on trial has been up long enough: the trial ends. */
    fun healthy() = end()

    fun end() {
        file.delete()
    }

    private fun write(t: Trial) {
        file.parentFile?.mkdirs()
        file.writeText("target=${t.target}\nversion=${t.version}\nfrom=${t.from}\nstarts=${t.starts}\n")
    }

    companion object {
        /** Its file, in Fuse's data folder. */
        const val FILE = "update-trial"

        /** What Fuse says at its next start about an update it went back from, in Fuse's data folder. */
        const val NOTE = "update-note"

        /** Starts the new version gets to come up before Fuse goes back. */
        const val MAX_STARTS = 2

        fun previousOf(target: File) = File(target.parentFile, ".${target.name}.previous")
        fun infoOf(target: File) = File(target.parentFile, ".${target.name}.previous.info")
    }
}
