package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.CopyPlace
import io.github.matiyaaa.fuse.ui.shell.store.PortGameCopy
import io.github.matiyaaa.fuse.ui.shell.store.ReachOps
import io.github.matiyaaa.fuse.ui.shell.store.RommOps
import io.github.matiyaaa.fuse.ui.shell.store.RommPresence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a port's game may be besides this device: the RomM server's library and the household's
 * other devices (Fuse One). Fuse brings a game here from either, as their own pages do.
 */
internal interface PortGameSources {
    /** The games of [systems] (Fuse's ids) on RomM and on other devices that this device hasn't got. */
    suspend fun games(systems: Set<String>): List<PortGameCopy>

    /** Brings [copy] here; null when it was asked for, else why not. */
    suspend fun bring(copy: PortGameCopy): String?

    /** The library game [copy] became once it came here; null while it hasn't. */
    fun arrived(copy: PortGameCopy): Flow<GameId?>

    object None : PortGameSources {
        override suspend fun games(systems: Set<String>): List<PortGameCopy> = emptyList()
        override suspend fun bring(copy: PortGameCopy): String? = "Neither RomM nor Fuse One is set up here."
        override fun arrived(copy: PortGameCopy): Flow<GameId?> = flowOf(null)
    }
}

/** [PortGameSources] over RomM's library ([romm]) and the household's ([reach]), each when it is set up. */
internal class RommAndHousehold(private val romm: RommOps, private val reach: ReachOps) : PortGameSources {
    override suspend fun games(systems: Set<String>): List<PortGameCopy> {
        val out = ArrayList<PortGameCopy>()
        if (romm.supported && romm.state.value.usable) {
            for (s in romm.systems.value) {
                val platform = s.platform?.takeIf { it.value in systems } ?: continue
                val list = withTimeoutOrNull(WAIT_MS) { romm.games(s.slug).first() }.orEmpty()
                // Only RomM's: a game downloaded or found here is this device's.
                for (g in list) if (g.game == null && g.presence == RommPresence.ON_SERVER) {
                    out += PortGameCopy(g.card.id, g.card.title, platform, CopyPlace.ROMM, "RomM", romId = g.romId, sizeBytes = g.sizeBytes, art = g.card.art)
                }
            }
        }
        if (reach.supported && reach.state.value.supported) {
            for (s in reach.systems.value) {
                if (s.platform.value !in systems) continue
                val list = withTimeoutOrNull(WAIT_MS) { reach.games(s.platform).first() }.orEmpty()
                for (h in list) {
                    out += PortGameCopy(
                        h.id, h.card.title, s.platform, CopyPlace.DEVICE, h.holders.firstOrNull() ?: "another device",
                        sizeBytes = h.sizeBytes, online = h.onlineHolders > 0, art = h.card.art,
                    )
                }
            }
        }
        return out
    }

    override suspend fun bring(copy: PortGameCopy): String? = when (copy.place) {
        CopyPlace.ROMM -> {
            val rom = copy.romId ?: return "That game isn't on RomM any more."
            romm.download(rom)
        }
        else -> reach.download(copy.id)
    }

    override fun arrived(copy: PortGameCopy): Flow<GameId?> = when (copy.place) {
        CopyPlace.ROMM -> copy.romId?.let(romm::libraryGame) ?: flowOf(null)
        else -> reach.libraryGame(copy.id)
    }

    private companion object {
        /** How long one list is waited for: a server or host that doesn't answer leaves it out. */
        const val WAIT_MS = 5_000L
    }
}

/** [copies] by system and name, to find a port's game among them as among the library's. */
internal fun copiesIndex(copies: List<PortGameCopy>): AndroidPortCatalog.Games<PortGameCopy> =
    AndroidPortCatalog.Games(copies, { it.platform.value }, { io.github.matiyaaa.fuse.model.GameTitles(original = it.title) }, { it.id })
