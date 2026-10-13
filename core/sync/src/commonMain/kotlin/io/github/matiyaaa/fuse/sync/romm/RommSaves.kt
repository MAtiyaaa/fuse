package io.github.matiyaaa.fuse.sync.romm

import io.github.matiyaaa.fuse.romm.RommSave
import io.github.matiyaaa.fuse.sync.SaveQuery

/**
 * RomM's save sync, for games that came from (or are matched to) a RomM server: each game's save
 * here kept in step with the person's newest save of it on RomM, so another device that plays it
 * through RomM (Fuse, or another RomM client) has it.
 *
 * RomM keeps one file per save, so only saves that are one file take part (a cartridge's save
 * memory, a memory card); a save made of folders stays here. Fuse never lets RomM's own verdict put
 * a new save over a real one: it keeps what it last agreed with RomM for each game, and when both
 * this device and RomM changed since (or this device never agreed with RomM yet and the two
 * differ), the person chooses. A blank save is never sent. Every upload is a new save on RomM, the
 * earlier ones kept as its history.
 */
interface RommSaveService {
    /** Before [query] (RomM's game [romId]) starts: RomM's newer save put in place, or a choice to make. */
    suspend fun beforeLaunch(query: SaveQuery, romId: Long): RommSaveGate

    /** After it closes: its save sent to RomM when it changed and RomM didn't meanwhile. */
    suspend fun afterExit(query: SaveQuery, romId: Long)

    /** The person chose: [keepHere] sends this device's save to RomM, else RomM's comes here (this one kept aside). */
    suspend fun settle(conflict: RommSaveConflict, keepHere: Boolean): Result<Unit>
}

sealed interface RommSaveGate {
    /** Go ahead; [note] when something is worth saying (RomM's save came down, RomM unreachable). */
    data class Go(val note: String? = null) : RommSaveGate

    /** This device's save and RomM's both changed (or never agreed): ask which to keep. */
    data class Conflict(val conflict: RommSaveConflict) : RommSaveGate
}

/** The two saves of [title]: this device's at [path] (changed at [hereAt], [hereBytes] long) and RomM's [server]. */
data class RommSaveConflict(
    val title: String,
    val romId: Long,
    val query: SaveQuery,
    val path: String,
    val hereAt: Long,
    val hereBytes: Long,
    val server: RommSave,
)
