package io.github.matiyaaa.fuse.sync

/**
 * What changed on this device since its records were last put in place: [base] is what was put in
 * place (the profile as the host had it, merged with what was sent), [local] is what the library
 * and settings say now (values only; their clocks are ignored). Each difference becomes a change
 * stamped now, so it wins over anything older, everywhere; play time this device counted beyond
 * [base] goes on this device's own counter, so time played while Fuse Sync was off or offline is
 * never lost and never counted twice.
 */
object ProfileDiff {
    fun changes(base: ProfileMeta, local: ProfileMeta, device: String, clock: HlcClock): ProfileMeta {
        val games = HashMap<String, GameRecord>()
        for ((id, now) in local.games) {
            val before = base.games[id] ?: GameRecord(now.key)
            var change = GameRecord(now.key, name = now.name)
            var changed = false
            // Play time this device counted beyond what was put in place is its own.
            val extra = now.totalSeconds - before.totalSeconds
            if (extra > 0) {
                change = change.copy(playSeconds = mapOf(device to (before.playSeconds[device] ?: 0) + extra))
                changed = true
            }
            val newSessions = now.sessions.filterKeys { it !in before.sessions }
            if (newSessions.isNotEmpty()) {
                change = change.copy(sessions = newSessions)
                changed = true
            }
            if ((now.lastPlayed ?: 0) > (before.lastPlayed ?: 0)) {
                change = change.copy(lastPlayed = now.lastPlayed)
                changed = true
            }
            fun <T> reg(n: Lww<T>?, b: Lww<T>?, default: T): Lww<T>? = if ((n?.value ?: default) != (b?.value ?: default)) Lww(n?.value ?: default, clock.now()) else null
            reg(now.favorite, before.favorite, false)?.let { change = change.copy(favorite = it); changed = true }
            reg(now.hidden, before.hidden, false)?.let { change = change.copy(hidden = it); changed = true }
            reg(now.pinned, before.pinned, false)?.let { change = change.copy(pinned = it); changed = true }
            reg(now.continueDismissed, before.continueDismissed, null)?.let { change = change.copy(continueDismissed = it); changed = true }
            reg(now.title, before.title, null)?.let { change = change.copy(title = it); changed = true }
            reg(now.emulator, before.emulator, null)?.let { change = change.copy(emulator = it); changed = true }
            if (changed) games[id] = change
        }
        val collections = HashMap<String, CollectionRecord>()
        val ids = local.collections.keys + base.collections.keys
        for (id in ids) {
            val now = local.collections[id]
            val before = base.collections[id]
            when {
                now == null && before != null && before.deleted?.value != true ->
                    collections[id] = before.copy(deleted = Lww(true, clock.now()), members = emptyMap())
                now != null -> {
                    var c = CollectionRecord(id, Lww(now.name.value, before?.name?.at ?: Hlc.ZERO))
                    var changed = before == null
                    if (before == null || before.name.value != now.name.value) c = c.copy(name = Lww(now.name.value, clock.now())).also { changed = true }
                    val nowGames = now.games.toSet()
                    val beforeGames = before?.games?.toSet().orEmpty()
                    val members = HashMap<String, Lww<Boolean>>()
                    for (g in nowGames - beforeGames) members[g] = Lww(true, clock.now())
                    for (g in beforeGames - nowGames) members[g] = Lww(false, clock.now())
                    if (members.isNotEmpty()) changed = true
                    if (now.order?.value != null && now.order.value != before?.order?.value) c = c.copy(order = Lww(now.order.value, clock.now())).also { changed = true }
                    if (before?.deleted?.value == true) c = c.copy(deleted = Lww(false, clock.now())).also { changed = true }
                    if (changed) collections[id] = c.copy(members = members)
                }
            }
        }
        val settings = local.settings.filter { (k, v) ->
            val prior = base.settings[k]
            prior?.value != v.value && (v.at == Hlc.ZERO || prior == null || v.at > prior.at)
        }.mapValues { (_, v) -> if (v.at == Hlc.ZERO) Lww(v.value, clock.now()) else v }
        return ProfileMeta(games, collections, settings)
    }

    /** True when [meta] says nothing. */
    fun isEmpty(meta: ProfileMeta): Boolean = meta.games.isEmpty() && meta.collections.isEmpty() && meta.settings.isEmpty()
}
