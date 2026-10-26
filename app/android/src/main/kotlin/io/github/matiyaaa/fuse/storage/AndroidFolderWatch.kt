package io.github.matiyaaa.fuse.storage

import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import io.github.matiyaaa.fuse.ui.shell.store.FolderWatch

/**
 * Library folders followed with Android's own file change notices ([FileObserver], inotify
 * underneath): a game copied in, renamed or deleted is told about once the burst of changes has been
 * quiet for a moment. Each folder is followed on its own, not what is inside its folders. Folders
 * Fuse can't read (no All files access) tell nothing, and the scan on resume still finds changes.
 */
class AndroidFolderWatch : FolderWatch {
    override fun watch(folders: List<String>, onChange: () -> Unit): AutoCloseable? {
        if (folders.isEmpty()) return null
        val thread = HandlerThread("fuse-folder-watch").apply { start() }
        val handler = Handler(thread.looper)
        val quiet = Runnable { onChange() }
        val observers = folders.map { folder ->
            @Suppress("DEPRECATION")
            object : FileObserver(folder, MASK) {
                override fun onEvent(event: Int, path: String?) {
                    // The folder itself went (a card pulled): the drive watch and the next scan say what's left.
                    if (path != null && !FolderWatch.counts(path)) return
                    handler.removeCallbacks(quiet)
                    handler.postDelayed(quiet, FolderWatch.QUIET_MS)
                }
            }.also { it.startWatching() }
        }
        return AutoCloseable {
            observers.forEach { it.stopWatching() }
            handler.removeCallbacks(quiet)
            thread.quitSafely()
        }
    }

    private companion object {
        const val MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }
}
