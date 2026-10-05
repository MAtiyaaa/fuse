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
 * the server can't be reached. A recent answer is used without asking again; an older one is shown
 * at once while a fresh one is fetched behind it, and is the only one there is while offline. Keys
 * include the user, never the address, so a change of route keeps everything.
 */
class ResponseCache(private val disk: JellyfinDiskCache?, private val clock: () -> Long) {
    private class Entry(val text: String, val at: Long)

    private val lock = Mutex()
    private val memory = LinkedHashMap<String, Entry>(64, 0.75f, true)

    /** Answers kept from before this moment are out of date (something was played or marked since). */
    private var validFrom = 0L

    /** An answer fresh enough to use without asking. */
    suspend fun fresh(key: String, maxAgeMs: Long): String? = lock.withLock {
        val e = memory[key] ?: return null
        if (clock() - e.at <= maxAgeMs && e.at >= validFrom) e.text else null
    }

    /** Any answer kept for [key], however old (offline). */
    suspend fun any(key: String): String? = entry(key, current = false)?.text

    /** A kept answer that is still right, however old: shown while a fresh one is fetched. */
    suspend fun current(key: String): String? = entry(key, current = true)?.text

    private suspend fun entry(key: String, current: Boolean): Entry? {
        lock.withLock { memory[key]?.let { if (!current || it.at >= validFrom) return it } }
        val stored = runCatching { disk?.read(safe(key)) }.getOrNull() ?: return null
        val e = decode(stored)
        if (current && e.at < validFrom) return null
        lock.withLock { if (key !in memory) memory[key] = e }
        return e
    }

    /** Keeps an answer; returns true when it differs from what was kept. */
    suspend fun put(key: String, text: String): Boolean {
        val now = clock()
        val changed = lock.withLock {
            val before = memory[key]?.text
            memory[key] = Entry(text, now)
            while (memory.size > MEMORY_ENTRIES) memory.remove(memory.keys.first())
            before != null && before != text
        }
        runCatching { disk?.write(safe(key), "$now\n$text") }
        return changed
    }

    /** Forgets what was kept after something changed here (favourite, watched): asked again next time. */
    suspend fun invalidate(predicate: (String) -> Boolean) = lock.withLock {
        validFrom = clock()
        memory.keys.filter(predicate).forEach { memory.remove(it) }
    }

    suspend fun clear() {
        lock.withLock { memory.clear() }
        runCatching { disk?.clear() }
    }

    /** An answer as kept on disk: when, then the text. Older files had no time and count as old. */
    private fun decode(stored: String): Entry {
        val nl = stored.indexOf('\n')
        val at = if (nl in 1..19) stored.substring(0, nl).toLongOrNull() else null
        return if (at != null) Entry(stored.substring(nl + 1), at) else Entry(stored, 0)
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
