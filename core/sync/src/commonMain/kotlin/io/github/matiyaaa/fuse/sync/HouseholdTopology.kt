package io.github.matiyaaa.fuse.sync

/**
 * Reconciles the host's membership and its cached game lists. A game list proves that a device
 * exists even when the membership request has not arrived yet. Explicit revocation wins over
 * cached evidence. Availability and willingness to accept a send are separate from membership.
 */
object HouseholdTopology {
    fun reconcile(registry: List<DeviceInfo>, libraries: List<DeviceLibrary>, seen: Map<String, Long> = emptyMap()): List<DeviceInfo> {
        val members = registry.associateBy { it.id }.toMutableMap()
        for (library in libraries) {
            if (library.device.isBlank()) continue
            val known = members[library.device]
            if (known == null) members[library.device] = DeviceInfo(library.device,
                library.name.ifBlank { "Fuse device" }, library.platform, lastSeen = seen[library.device] ?: 0)
        }
        return members.values.filter { it.id.isNotBlank() }.map { device ->
            device.copy(lastSeen = maxOf(device.lastSeen, seen[device.id] ?: 0))
        }.sortedBy { it.id }
    }

    fun others(self: String, devices: List<DeviceInfo>): List<DeviceInfo> = devices.filter { it.id != self && it.id.isNotBlank() && !it.revoked }
}
