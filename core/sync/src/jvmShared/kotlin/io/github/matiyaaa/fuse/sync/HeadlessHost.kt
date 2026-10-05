package io.github.matiyaaa.fuse.sync

import java.io.File

/**
 * The host on its own, with no window: what the background service runs (`Fuse --sync-host`), so
 * Fuse Sync keeps serving when Fuse is closed and after the computer restarts. Fuse itself then
 * manages it through its admin calls ([HostAdmin]); it never opens the host's files a second time.
 */
object HeadlessHost {
    /**
     * Serves the host kept in [hostDir] on [port] and answers discovery, until closed. Null when
     * something already serves that port (Fuse itself, still hosting): the service tries again later.
     */
    fun serve(hostDir: File, port: Int, name: String, version: String, clock: () -> Long = System::currentTimeMillis): AutoCloseable? {
        if (!HostAdmin.portFree(port)) return null
        val store = HostStore(hostDir, clock, hostName = name)
        // Fuse reads it to manage this process.
        store.adminToken
        val server = runCatching { SyncHost(store, port, version, clock).start() }.getOrNull() ?: return null
        val responder = runCatching { Discovery.answer({ store.hello(port, version) }) }.getOrNull()
        return AutoCloseable {
            responder?.stop()
            server.stop()
        }
    }
}
