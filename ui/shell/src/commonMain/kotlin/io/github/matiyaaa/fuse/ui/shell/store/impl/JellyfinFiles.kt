package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.jellyfin.JellyfinDiskCache
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices

/** Jellyfin's kept answers, as files in Fuse's cache folder, so pages open offline after a restart. */
internal class JellyfinFiles(private val services: FuseServices) : JellyfinDiskCache {
    private fun path(key: String) = "jellyfin/$key.json"

    override suspend fun read(key: String): String? =
        services.fs.readText("${services.cacheDir.trimEnd('/')}/${path(key)}", maxBytes = MAX_BYTES)

    override suspend fun write(key: String, text: String) {
        if (text.length <= MAX_BYTES) services.writeCacheFile(path(key), text)
    }

    /** Signing out: the files are keyed by the session that wrote them, so a new one never reads them. */
    override suspend fun clear() = Unit

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
