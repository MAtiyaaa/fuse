package io.github.matiyaaa.fuse.sync

/**
 * A scanner-proven content child contributes observed play and positive favourite/pin/membership
 * intent to its owner. Its custom title, hidden state, emulator and provider knowledge remain
 * archived with the child: they cannot rename, hide or misidentify the base title.
 * This projection is idempotent; it does not destructively rewrite the host's archived records.
 */
fun ProfileMeta.foldAbsorbed(owners: Map<String, GameKey>): ProfileMeta {
    if (owners.isEmpty()) return this
    val remaining = games.filterKeys { it !in owners }.toMutableMap()
    for ((childId, owner) in owners) {
        val child = games[childId] ?: continue
        if (childId == owner.id) continue
        val base = remaining[owner.id] ?: GameRecord(owner)
        val sessions = base.sessions + child.sessions.filterKeys { it !in base.sessions }
        // Counters are not summed: a legacy duplicate can already include the same play time.
        val positive = GameRecord(owner, playSeconds = child.playSeconds, sessions = sessions,
            lastPlayed = child.lastPlayed,
            favorite = child.favorite?.takeIf { it.value }, pinned = child.pinned?.takeIf { it.value })
        remaining[owner.id] = base.merge(positive)
    }
    val normalizedCollections = collections.mapValues { (_, collection) ->
        val members = collection.members.filterKeys { it !in owners }.toMutableMap()
        for ((childId, owner) in owners) {
            val child = collection.members[childId]?.takeIf { it.value } ?: continue
            members[owner.id] = members[owner.id].mergeWith(child)!!
        }
        collection.copy(members = members)
    }
    return copy(games = remaining, collections = normalizedCollections)
}
