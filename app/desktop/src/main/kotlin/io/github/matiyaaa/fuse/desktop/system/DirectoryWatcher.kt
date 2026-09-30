package io.github.matiyaaa.fuse.desktop.system

import io.github.matiyaaa.fuse.desktop.Log
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

/**
 * Watches a few directories (not recursively) with inotify through [WatchService] and calls
 * [onChange] once per burst of changes, after [debounceMs] of quiet. Directories that do not exist
 * yet are picked up when they appear (checked every few seconds); none are ever created. [onChange]
 * runs on the watcher's own daemon thread.
 */
class DirectoryWatcher(
    private val name: String,
    private val debounceMs: Long = 500,
    /** Only changes to file names accepted by this filter count; null accepts every change. */
    private val fileFilter: ((String) -> Boolean)? = null,
    private val onChange: () -> Unit,
) : AutoCloseable {
    @Volatile private var closed = false
    private var service: WatchService? = null
    private var thread: Thread? = null
    private val registered = HashMap<WatchKey, Path>()

    @Synchronized
    fun watch(dirs: List<String>) {
        if (closed || thread != null) return
        val ws = try {
            FileSystems.getDefault().newWatchService()
        } catch (e: IOException) {
            Log.warn("file watching is not available", e)
            return
        }
        service = ws
        val targets = dirs.map { Paths.get(it) }
        thread = Thread({ loop(ws, targets) }, name).apply {
            isDaemon = true
            start()
        }
    }

    private fun loop(ws: WatchService, targets: List<Path>) {
        try {
            while (!closed) {
                registerExisting(ws, targets)
                val key = ws.poll(RESCAN_SECONDS, TimeUnit.SECONDS) ?: continue
                var relevant = handle(key)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(debounceMs)
                while (true) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0) break
                    val next = ws.poll(left, TimeUnit.NANOSECONDS) ?: break
                    relevant = handle(next) || relevant
                }
                if (relevant && !closed) {
                    try {
                        onChange()
                    } catch (e: Exception) {
                        Log.warn("$name: change handler failed", e)
                    }
                }
            }
        } catch (e: ClosedWatchServiceException) {
            // Closed.
        } catch (e: InterruptedException) {
            // Closed.
        }
    }

    private fun registerExisting(ws: WatchService, targets: List<Path>) {
        for (dir in targets) {
            if (registered.containsValue(dir) || !Files.isDirectory(dir)) continue
            try {
                val key = dir.register(
                    ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                )
                registered[key] = dir
            } catch (e: IOException) {
                // Unreadable or vanished; try again on the next pass.
            }
        }
    }

    private fun handle(key: WatchKey): Boolean {
        var relevant = false
        for (event in key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                relevant = true
                continue
            }
            val file = (event.context() as? Path)?.toString() ?: continue
            if (fileFilter == null || fileFilter.invoke(file)) relevant = true
        }
        if (!key.reset()) {
            // The directory is gone; it is registered again if it comes back.
            registered.remove(key)
            relevant = true
        }
        return relevant
    }

    override fun close() {
        closed = true
        try {
            service?.close()
        } catch (e: IOException) {
            // Already closed.
        }
        thread?.interrupt()
    }

    private companion object {
        const val RESCAN_SECONDS = 5L
    }
}
