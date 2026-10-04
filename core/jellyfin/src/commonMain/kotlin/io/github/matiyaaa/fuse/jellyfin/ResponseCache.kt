package io.github.matiyaaa.fuse.jellyfin

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where answers are kept between runs (the app's cache folder); null keeps them in memory only. */
interface JellyfinDiskCache {
    suspend fun read(key: String): String?
    suspend fun write(key: String, text: String)
    suspend fun clear()
}

/**
 * The server's answers, kept so pages open at once when you come back to them and still open when
 * the server can't be reached. A recent answer is used without asking again; an older one is
 * refreshed, and used as it is only while offline. Keys include the user, never the address, so a
 * change of route keeps everything.
 */
class ResponseCache(private val disk: JellyfinDiskCache?, private val clock: () -> Long) {
    private class Entry(val text: String, val at: Long)

    private val lock = Mutex()
    private val memory = LinkedHashMap<String, Entry>(64, 0.75f, true)

    /** An answer fresh enough to use without asking. */
    suspend fun fresh(key: String, maxAgeMs: Long): String? = lock.withLock {
        val e = memory[key] ?: return null
        if (clock() - e.at <= maxAgeMs) e.text else null
    }

    /** Any answer kept for [key], however old (offline). */
    suspend fun any(key: String): String? {
        lock.withLock { memory[key]?.let { return it.text } }
        val text = runCatching { disk?.read(safe(key)) }.getOrNull() ?: return null
        lock.withLock { memory[key] = Entry(text, 0) }
        return text
    }

    suspend fun put(key: String, text: String) {
        lock.withLock {
            memory[key] = Entry(text, clock())
            while (memory.size > MEMORY_ENTRIES) memory.remove(memory.keys.first())
        }
        runCatching { disk?.write(safe(key), text) }
    }

    /** Forgets what was kept about an item after it changed here (favourite, watched). */
    suspend fun invalidate(predicate: (String) -> Boolean) = lock.withLock {
        memory.keys.filter(predicate).forEach { memory.remove(it) }
    }

    suspend fun clear() {
        lock.withLock { memory.clear() }
        runCatching { disk?.clear() }
    }

    /** A file-safe name for a key. */
    private fun safe(key: String): String {
        var h = 1125899906842597L
        for (c in key) h = 31 * h + c.code
        return "jf-" + h.toULong().toString(16)
    }

    companion object {
        private const val MEMORY_ENTRIES = 300
    }
}
