package io.github.matiyaaa.fuse.sync

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Whether the host can use its data folder: go into it, write to it, and read each of its own files.
 * Another program changing the folder's owner (a media container setting permissions on a tree the
 * folder sits in) made the host's next start fail on its admin token, and the system started it
 * again every few seconds for good. Now the host says what is wrong, and how to put it right, and
 * waits ([HostDataCheck.answer] tells the household's devices the same).
 */
object HostDataCheck {
    /** What stops this process using [dir], in words with the fix; null when the host can use it. */
    fun problem(dir: File): String? {
        val folder = dir.absoluteFile
        if (!folder.exists()) {
            // A new host makes its folder: only a parent it can't write to stops it.
            val parent = generateSequence(folder.parentFile) { it.parentFile }.firstOrNull { it.exists() } ?: return null
            return if (parent.isDirectory && parent.canWrite() && parent.canExecute()) null else blocked(parent, "make its data folder in", folder)
        }
        if (!folder.isDirectory) return "The host's data folder ${folder.path} is a file, not a folder."
        if (!folder.canExecute() || !folder.canRead()) return blocked(folder, "open its data folder", folder)
        if (!folder.canWrite()) return blocked(folder, "write to its data folder", folder)
        for (f in HostFiles.own(folder).sortedBy { it.name }) {
            when {
                f.isDirectory && (!f.canExecute() || !f.canRead() || !f.canWrite()) -> return blocked(f, "use ${f.name} in its data folder", folder)
                f.isFile && !f.canRead() -> return blocked(f, "read ${f.name} in its data folder", folder)
            }
        }
        return null
    }

    /**
     * [what] the host can't do with [file], whose owner it names beside the user the host runs as,
     * with the command that gives [folder] back on a system with owners (Linux and macOS).
     */
    private fun blocked(file: File, what: String, folder: File): String {
        val owner = runCatching { Files.getOwner(file.toPath()).name }.getOrNull()
        val me = runningAs()
        val whose = if (owner != null && owner != me.name) " It belongs to $owner, and the host runs as ${me.label}." else ""
        val fix = if (File.separatorChar == '/') {
            " Another program may have changed its owner. Give the folder back on that computer with: sudo chown -R ${me.name} \"${folder.path}\""
        } else {
            " Let ${me.label} read and write the folder again."
        }
        return "The host can't $what (${file.path}).$whose$fix"
    }

    /** Who this process runs as: the user's name, with its number where the system says it. */
    private fun runningAs(): RunningAs {
        val name = System.getProperty("user.name").orEmpty().ifBlank { "this user" }
        val uid = runCatching { Files.getAttribute(Paths.get("/proc/self"), "unix:uid") as Int }.getOrNull()
        return RunningAs(name, if (uid != null) "$name ($uid)" else name)
    }

    private data class RunningAs(val name: String, val label: String)

    /**
     * Answers on [port], while the host waits on its folder, with [message] (as the host's own error
     * to Fuse, which shows it on each device, and as text to a browser), so the household learns what
     * is wrong rather than that something else answered. Null when the port is taken.
     */
    fun answer(port: Int, message: () -> String): AutoCloseable? {
        if (!HostAdmin.portFree(port)) return null
        val json = Json { encodeDefaults = true }
        val server = runCatching {
            embeddedServer(CIO, port = port) {
                // Every call, whatever it asks, gets the same answer.
                intercept(ApplicationCallPipeline.Call) {
                    val m = message()
                    if (context.request.path().startsWith(SyncApi.BASE)) {
                        context.respondText(json.encodeToString(ApiError.serializer(), ApiError(m, CODE)), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                    } else {
                        context.respondText(m, ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
                    }
                    finish()
                }
            }.also { it.start(wait = false) }
        }.getOrNull() ?: return null
        return AutoCloseable { server.stop(200, 1_000) }
    }

    /** The code the host's error carries while it can't use its data folder. */
    const val CODE = "host-data"
}
