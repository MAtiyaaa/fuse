package io.github.matiyaaa.fuse.sync

/** A staged import is a preview, never permission to replace an emulator's files. */
data class SaveImportPlan(
    val id: String,
    val sourceName: String,
    val entries: List<SaveImportEntry>,
    val unmatched: List<String>,
    val totalBytes: Long,
)

/**
 * A possible destination is derived from the same adapter used by normal save synchronization.
 * [fuseOne]: the emulator's save folder can't be reached on this device ([note] says why), so the
 * save goes to Fuse One as the person's newest, put in place by a device that can reach it.
 */
data class SaveImportTarget(
    val id: String,
    val query: SaveQuery,
    val kind: SaveKind,
    val format: String,
    val destination: String,
    val conversion: Boolean,
    val confidence: SaveImportConfidence,
    val fuseOne: Boolean = false,
    val note: String? = null,
)

enum class SaveImportConfidence { TITLE_ID, EXACT_NAME, MANUAL }

/** Uncertain matches stay unselected until the person chooses a destination. */
data class SaveImportEntry(
    val id: String,
    val name: String,
    val sourceFormat: String,
    val bytes: Long,
    val targets: List<SaveImportTarget>,
    val selectedTarget: String? = null,
    /** Set for a backup copied off a Switch by its save manager. */
    val switch: SwitchSource? = null,
)

/** Where a Switch backup came from: [tool] (JKSV, Checkpoint), its name, the Switch user and when it was made. */
data class SwitchSource(
    val tool: String,
    val backup: String,
    val user: String?,
    /** Seconds since 1970, when known. */
    val savedAt: Long?,
    val titleId: String?,
    val game: String?,
    /** The game's device save (one for everyone on the console), not an account's. */
    val device: Boolean = false,
)

/**
 * The person's selection, made after inspecting the plan; missing entries are not imported. [profiles]
 * are whose save it becomes (each gets their own copy); empty: the one the import is made for.
 */
data class SaveImportChoice(val entry: String, val target: String, val profiles: List<String> = emptyList())

/** Revisions are durable locally even while the host is away. No source file is moved or deleted. */
data class SaveImportResult(val imported: Int, val revisions: List<String>, val queued: Boolean,
    val failures: List<SaveImportFailure> = emptyList(),
    /** Each save the import replaced, kept on this device first (see [SyncService.restoreImportBackup]). */
    val replaced: List<ReplacedSave> = emptyList())

/** A batch can finish some independent destinations while another destination becomes unavailable. */
data class SaveImportFailure(val entry: String, val name: String, val message: String)

/** A save a person already has of a game, which an import would replace: where it is, and when it was made (ms), when known. */
data class ExistingSave(val where: Where, val at: Long?) {
    enum class Where {
        /** In the emulator's folder now. */
        IN_FOLDER,
        /** Set aside on this device while someone else's is in the folder. */
        SET_ASIDE,
        /** Saved here and waiting to go to the host. */
        WAITING,
        /** Agreed with the host. */
        SYNCED,
    }
}

/** A save an import replaced for [profile], kept on this device as [backup] so the import can be undone. */
data class ReplacedSave(val entry: String, val profile: String, val query: SaveQuery, val kind: SaveKind, val backup: String)

/** A save kept on this device from before an import replaced it: [at] in milliseconds. */
data class ImportBackupInfo(val id: String, val at: Long, val bytes: Long, val title: String)

/**
 * A Switch SD card (or a copy of one) Fuse found: [path] is its save backups folder ("" when it has
 * none), [name] the card's, [what] what is on it. [emuMmcLost]: the emuMMC settings files the card
 * had when Fuse last saw it and has lost since ([EmuMmcGuard]), which Fuse can put back; [root] is
 * the card itself.
 */
data class SwitchCard(val path: String, val name: String, val what: String, val emuMmcLost: List<String> = emptyList(), val root: String = "")
