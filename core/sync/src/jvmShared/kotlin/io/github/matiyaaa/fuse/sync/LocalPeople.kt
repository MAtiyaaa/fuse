package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.Serializable

/** A profile kept on this device alone (no host): its PIN only as a salted hash. */
@Serializable
internal data class LocalProfile(
    val id: String,
    val name: String,
    val avatar: String,
    val createdAt: Long,
    val pinHash: String? = null,
) {
    val info: ProfileInfo get() = ProfileInfo(id, name, avatar, pinHash != null, createdAt)
}

/**
 * The people this device knows by itself: its own profiles while it has no host ([profiles]),
 * the order profiles show in here ([order]), and a hash of each PIN typed here for a host's
 * profile ([pins]), so a person who leaves the host with this device keeps their PIN.
 */
@Serializable
internal data class LocalPeople(
    val profiles: List<LocalProfile> = emptyList(),
    val order: List<String> = emptyList(),
    val pins: Map<String, String> = emptyMap(),
) {
    /** [list] in this device's order (any not in it after, as they came). */
    fun ordered(list: List<ProfileInfo>): List<ProfileInfo> {
        if (order.isEmpty()) return list
        val at = order.withIndex().associate { it.value to it.index }
        return list.withIndex().sortedBy { (i, p) -> at[p.id] ?: (order.size + i) }.map { it.value }
    }
}

/** A name as two profiles are matched by: case and spaces aside. */
internal fun sameName(a: String, b: String): Boolean = a.filterNot(Char::isWhitespace).equals(b.filterNot(Char::isWhitespace), ignoreCase = true)
