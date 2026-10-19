package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.sync.TrophyUnlock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The person's own note on a game on RomM that carries the trophies they earned in it from one of
 * their devices to the others, for a household without a Fuse One host. RomM shows notes as they
 * are written (`/api/roms/{id}/notes`, kept to the person), so it opens with a line saying what it
 * is and a line for each trophy; each unlock is then kept as one line of JSON in a `fuse-trophies`
 * block, which is all Fuse reads back. Unlocks are only ever added, each trophy's earliest kept
 * ([TrophyUnlock.earlier]), so devices writing in turn agree whatever order they write in.
 */
internal object TrophyNote {
    /** Its title: RomM keeps one note of each title per person and game. */
    const val TITLE = "Fuse trophies"

    /** Its tag in RomM, so the person can find every game's at once. */
    const val TAG = "fuse"

    /** The most unlocks read from one note (a game has a few hundred trophies at most). */
    const val MOST = 5_000

    private const val FENCE = "```"
    private const val BLOCK = "fuse-trophies"
    private val json = Json { ignoreUnknownKeys = true }

    /** The unlocks in a note's [content]; whatever else it holds, or a line that isn't one, is passed over. */
    fun read(content: String): List<TrophyUnlock> {
        val out = ArrayList<TrophyUnlock>()
        var inside = false
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (!inside) {
                if (line == FENCE + BLOCK) inside = true
                continue
            }
            if (line.startsWith(FENCE)) break
            if (line.isEmpty()) continue
            val t = runCatching { json.decodeFromString(TrophyUnlock.serializer(), line) }.getOrNull() ?: continue
            if (t.source.isBlank() || t.id.isBlank() || t.unlockedAt <= 0) continue
            out += t
            if (out.size >= MOST) break
        }
        return out
    }

    /** [a] and [b] as one, by trophy, the earlier of two kept. */
    fun merge(a: Collection<TrophyUnlock>, b: Collection<TrophyUnlock>): Map<String, TrophyUnlock> {
        val out = HashMap<String, TrophyUnlock>()
        for (t in a) out[t.key] = TrophyUnlock.earlier(out[t.key], t)!!
        for (t in b) out[t.key] = TrophyUnlock.earlier(out[t.key], t)!!
        return out
    }

    /** A note's content holding [unlocks], oldest first, the same unlocks always written the same way. */
    fun write(unlocks: Collection<TrophyUnlock>): String = buildString {
        val sorted = unlocks.sortedWith(compareBy<TrophyUnlock>({ it.unlockedAt }, { it.key }))
        append("Trophies you earned in emulators, kept by Fuse so each of your devices shows them. ")
        append("Fuse only adds to this note, each trophy once with the first time you earned it.\n\n")
        for (t in sorted) {
            append("- ").append(t.title.ifBlank { t.id }.replace('\n', ' '))
            append(", ").append(kotlin.time.Instant.fromEpochMilliseconds(t.unlockedAt).toString().take(10))
            if (t.deviceName.isNotBlank()) append(" on ").append(t.deviceName.replace('\n', ' '))
            append('\n')
        }
        append('\n').append(FENCE).append(BLOCK).append('\n')
        for (t in sorted) append(json.encodeToString(TrophyUnlock.serializer(), t)).append('\n')
        append(FENCE).append('\n')
    }
}

/**
 * This device's id in the trophies earned on it: Fuse One's, else one of its own made the first
 * time it is needed and kept. Earlier versions wrote [LEGACY] when Fuse One was off.
 */
internal object ThisDevice {
    const val LEGACY = "this-device"

    private const val NS = "device"
    private const val KEY = "id"
    private val lock = Mutex()

    suspend fun id(ctx: StoreContext): String = ctx.settings.value.sync.deviceId.ifBlank { own(ctx) }

    /** Every id this device's trophies may carry. */
    suspend fun ids(ctx: StoreContext): Set<String> = setOfNotNull(ctx.settings.value.sync.deviceId.ifBlank { null }, own(ctx), LEGACY)

    private suspend fun own(ctx: StoreContext): String = lock.withLock {
        ctx.data.cache.entry(NS, KEY)?.valueJson?.trim('"')?.takeIf { it.isNotBlank() }?.let { return@withLock it }
        val id = "fuse-" + kotlin.random.Random.nextLong().toULong().toString(16)
        ctx.data.cache.put(NS, KEY, "\"$id\"", ctx.now(), ttlMs = null)
        id
    }
}
