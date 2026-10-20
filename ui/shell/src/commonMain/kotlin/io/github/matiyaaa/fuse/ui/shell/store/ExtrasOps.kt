package io.github.matiyaaa.fuse.ui.shell.store

/**
 * What an installed emulator can be given from Fuse beyond its games: downloads its own menus offer
 * (Cemu's community graphic packs, RPCS3's patches from rpcs3.net, Xenia Canary's game patches),
 * system firmware put in place (a Switch emulator's NAND, the BIOS Fuse found copied into each
 * emulator's own folder) and how it goes online (Cemu through the Pretendo Network). Each is done
 * as the emulator itself does it, can be undone where it replaces something, and says what it did.
 */
interface ExtrasOps {
    /** What [emulator] (an installed emulator's id without its system's prefix, as "cemu") offers here. */
    suspend fun offered(emulator: String): List<Extra> = emptyList()

    /**
     * Does [kind] for [emulator]; [file] is what the person chose where one is needed (a firmware
     * archive or folder). A sentence saying what was done, or why it wasn't.
     */
    suspend fun run(emulator: String, kind: ExtraKind, file: String? = null, onProgress: (Float) -> Unit = {}): Result<String> =
        Result.failure(IllegalStateException("Extras are added from Fuse on a computer."))

    /** Puts back what the last run of [kind] for [emulator] replaced. */
    suspend fun undo(emulator: String, kind: ExtraKind): Result<String> = Result.failure(IllegalStateException("Nothing to put back."))

    object None : ExtrasOps
}

/** The things [ExtrasOps] does. */
enum class ExtraKind {
    CEMU_GRAPHIC_PACKS,
    CEMU_PRETENDO,
    RPCS3_PATCHES,
    XENIA_PATCHES,
    SWITCH_FIRMWARE,
    PLACE_FIRMWARE,
}

/**
 * One thing an emulator can be given: what it is, a line on what it does, where it stands now
 * ([state]: "On", a version, "Not yet"), whether it needs a file chosen first ([needsFile]), and
 * whether its last run can be undone ([undoable]).
 */
data class Extra(
    val kind: ExtraKind,
    val title: String,
    val detail: String,
    val state: String? = null,
    val needsFile: Boolean = false,
    val undoable: Boolean = false,
    /** For an on and off thing (Pretendo), whether it is on. */
    val on: Boolean? = null,
)
