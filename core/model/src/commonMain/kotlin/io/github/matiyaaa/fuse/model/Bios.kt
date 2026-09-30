package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * Firmware a platform needs. Fuse never ships firmware; it only checks for files the user owns.
 */
@Serializable
data class BiosRequirement(
    val label: String,
    val files: List<BiosFile>,
    /** How many of [files] are needed (for example "any one PS1 BIOS region" is 1). */
    val requiredCount: Int = files.size,
    /** True for firmware installed inside the emulator (PS3/Vita .PUP, Switch keys): only a hint. */
    val installedInEmulator: Boolean = false,
    val optional: Boolean = false,
    val hint: String,
)

@Serializable
data class BiosFile(
    val name: String,
    /** Lower-case MD5 hashes known to be good dumps, when useful. Empty means "any file with this name". */
    val md5: Set<String> = emptySet(),
    /** Alternative names accepted for the same file. */
    val aliases: Set<String> = emptySet(),
    val minSize: Long? = null,
)

@Serializable
enum class BiosState {
    READY,
    PARTIAL,
    MISSING,
    /**
     * Fuse could not look where the emulator keeps it (Android hides other apps' folders). Never
     * reported as missing.
     */
    UNKNOWN,
    NOT_REQUIRED,
}

@Serializable
data class BiosStatus(
    val state: BiosState,
    val found: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    /** Folders Fuse searched, so the user can see why a result is Unknown. */
    val searched: List<String> = emptyList(),
    val note: String? = null,
) {
    companion object {
        val NotRequired = BiosStatus(BiosState.NOT_REQUIRED)
    }
}
