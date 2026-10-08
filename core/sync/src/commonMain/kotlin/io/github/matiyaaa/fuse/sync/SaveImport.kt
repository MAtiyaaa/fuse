package io.github.matiyaaa.fuse.sync

/** A staged import is a preview, never permission to replace an emulator's files. */
data class SaveImportPlan(
    val id: String,
    val sourceName: String,
    val entries: List<SaveImportEntry>,
    val unmatched: List<String>,
    val totalBytes: Long,
)

/** A possible destination is derived from the same adapter used by normal save synchronization. */
data class SaveImportTarget(
    val id: String,
    val query: SaveQuery,
    val kind: SaveKind,
    val format: String,
    val destination: String,
    val conversion: Boolean,
    val confidence: SaveImportConfidence,
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
)

/** The person's selection, made after inspecting the plan; missing entries are not imported. */
data class SaveImportChoice(val entry: String, val target: String)

/** Revisions are durable locally even while the host is away. No source file is moved or deleted. */
data class SaveImportResult(val imported: Int, val revisions: List<String>, val queued: Boolean,
    val failures: List<SaveImportFailure> = emptyList())

/** A batch can finish some independent destinations while another destination becomes unavailable. */
data class SaveImportFailure(val entry: String, val name: String, val message: String)
