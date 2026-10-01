package io.github.matiyaaa.fuse.integrations.cartridge

import io.github.matiyaaa.fuse.integrations.UrlCoding

/**
 * Finds Fuse's game for a path Cartridge reports. The two apps often describe the same file
 * differently: Fuse may know a folder through Android's document picker (a `content://` URI) while
 * Cartridge reports `/storage/emulated/0/...`, or Cartridge saved a multi-disc game as a folder that
 * Fuse lists by its first disc. In order: the exact path, the same place written another way, a game
 * inside the folder Cartridge reports (when there is only one), the same folder and file name, and a
 * file name no other game has.
 */
class CartridgeMatch<T>(entries: List<Pair<T, String>>) {
    private val exact = HashMap<String, T>()
    private val byKey = HashMap<String, T>()
    private val byTail = HashMap<String, MutableList<T>>()
    private val byName = HashMap<String, MutableList<T>>()
    private val keys = ArrayList<Pair<String, T>>(entries.size)

    init {
        for ((id, path) in entries) {
            exact[path] = id
            val key = pathKey(path)
            if (key.isEmpty()) continue
            byKey.putIfAbsent(key, id)
            keys += key to id
            tail(key)?.let { byTail.getOrPut(it) { ArrayList(1) } += id }
            byName.getOrPut(key.substringAfterLast('/')) { ArrayList(1) } += id
        }
    }

    fun find(path: String): T? {
        exact[path]?.let { return it }
        val key = pathKey(path)
        if (key.isEmpty()) return null
        byKey[key]?.let { return it }
        // A folder (multi-disc games, games with extra files): the one game inside it.
        val inside = keys.filter { it.first.startsWith("$key/") }.map { it.second }.distinct()
        if (inside.size == 1) return inside.first()
        tail(key)?.let { t -> byTail[t]?.singleOrNull()?.let { return it } }
        return byName[key.substringAfterLast('/')]?.singleOrNull()
    }

    companion object {
        private val volume = Regex("^([0-9a-f]{4}-[0-9a-f]{4}):")

        /**
         * A comparable form of a path or an Android document URI: decoded, `/` separated, the
         * primary storage and SD cards written one way, lower case, without a trailing `/`.
         */
        fun pathKey(path: String): String {
            var p = path.trim()
            if (p.isEmpty()) return ""
            if (p.startsWith("content://", ignoreCase = true)) {
                val decoded = UrlCoding.decode(p)
                p = when {
                    "/document/" in decoded -> decoded.substringAfterLast("/document/")
                    "/tree/" in decoded -> decoded.substringAfter("/tree/")
                    else -> return decoded.lowercase()
                }
                p = when {
                    p.startsWith("primary:", ignoreCase = true) -> "/storage/emulated/0/" + p.substring("primary:".length)
                    volume.containsMatchIn(p.lowercase()) -> "/storage/" + p.substringBefore(':') + "/" + p.substringAfter(':')
                    else -> p
                }
            } else if (p.startsWith("file://", ignoreCase = true)) {
                p = UrlCoding.decode(p.substring("file://".length))
            }
            p = p.replace('\\', '/').replace(Regex("/{2,}"), "/").trimEnd('/')
            val lower = p.lowercase()
            for ((alias, real) in aliases) {
                if (lower == alias || lower.startsWith("$alias/")) return (real + lower.substring(alias.length))
            }
            return lower
        }

        private val aliases = listOf(
            "/sdcard" to "/storage/emulated/0",
            "/mnt/sdcard" to "/storage/emulated/0",
            "/storage/self/primary" to "/storage/emulated/0",
            "/mnt/user/0/primary" to "/storage/emulated/0",
            "/data/media/0" to "/storage/emulated/0",
        )

        /** The folder and file name (the last two parts), or null for a path with only one part. */
        private fun tail(key: String): String? {
            val parts = key.split('/').filter { it.isNotEmpty() }
            return if (parts.size < 2) null else parts.takeLast(2).joinToString("/")
        }
    }
}
