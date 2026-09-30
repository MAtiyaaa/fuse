package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.android.AndroidEmulatorCatalog
import io.github.matiyaaa.fuse.launch.linux.LinuxCatalog
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.PlatformId

/** All adapters Fuse knows, with lookups by id, platform and host. Ids must be unique. */
class AdapterRegistry(
    val adapters: List<EmulatorAdapter>,
    private val priority: (Host, PlatformId) -> List<EmulatorId> = EmulatorPriority::forPlatform,
) {
    private val byId: Map<EmulatorId, EmulatorAdapter>

    init {
        val dupes = adapters.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "Duplicate adapter ids: $dupes" }
        byId = adapters.associateBy { it.id }
    }

    operator fun get(id: EmulatorId): EmulatorAdapter? = byId[id]

    fun forHost(host: Host): List<EmulatorAdapter> = adapters.filter { it.host == host }

    /** The documented priority order for [platform] on [host]. */
    fun priority(platform: PlatformId, host: Host): List<EmulatorId> = priority(host, platform)

    /**
     * Adapters for [platform] on [host]: the priority list first, then any other adapter that lists
     * the platform, in registry order.
     */
    fun forPlatform(platform: PlatformId, host: Host): List<EmulatorAdapter> {
        val ordered = priority(host, platform).mapNotNull { byId[it] }.filter { it.host == host }
        val rest = adapters.filter { it.host == host && platform in it.platforms && it !in ordered }
        return ordered + rest
    }

    companion object {
        /** Every Android and Linux adapter. */
        val Default: AdapterRegistry by lazy { AdapterRegistry(AndroidEmulatorCatalog.adapters + LinuxCatalog.adapters) }
    }
}
