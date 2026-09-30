package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlatformKind { CONSOLE, HANDHELD, ARCADE, COMPUTER, PC_GAMES, ANDROID }

@Serializable
enum class PlatformFamily { NINTENDO, SONY, SEGA, MICROSOFT, ATARI, NEC, SNK, BANDAI, PC, ARCADE, ANDROID, OTHER }

/**
 * A system Fuse can organise games for. The catalog of known platforms lives in
 * [io.github.matiyaaa.fuse.library.PlatformCatalog]; user choices about a platform (media, emulator,
 * folder policy, layout) live in scoped settings so they inherit and can be reset.
 */
@Serializable
data class Platform(
    val id: PlatformId,
    /** Full name, for example "PlayStation 2". */
    val name: String,
    /** Compact label used on tiles and badges, for example "PS2". */
    val shortName: String,
    val kind: PlatformKind,
    val family: PlatformFamily,
    val manufacturer: String? = null,
    val releaseYear: Int? = null,
    /** Lower-case file extensions (without dot) that are games on this platform. */
    val extensions: Set<String> = emptySet(),
    /** Every folder name that means this platform (RomM slug, ES-DE name, common spellings), lower case. */
    val folderAliases: Set<String> = emptySet(),
    /** How a folder directly inside this platform's ROM folder is treated when nothing overrides it. */
    val defaultFolderPolicy: FolderPolicy = FolderPolicy.AUTO,
    /** RetroAchievements console id, when RetroAchievements supports the platform. */
    val retroAchievementsConsoleId: Int? = null,
    /** Accent colour used by original Fuse system tiles (ARGB). Not an official brand colour. */
    val accent: Long = 0xFF8A93A6,
    /** Firmware this platform needs, if any. */
    val bios: BiosRequirement? = null,
    /** Default library presentation for this platform. */
    val defaultLayout: LibraryLayout = LibraryLayout.ICON,
    /** Default artwork aspect for this platform's covers (width / height). */
    val coverAspect: Float = 0.72f,
)

/** A platform together with what is currently known about it on this device. */
@Serializable
data class PlatformSummary(
    val platform: Platform,
    val gameCount: Int,
    val romFolders: List<String>,
    val emulator: EmulatorAvailability,
    val bios: BiosStatus,
)

@Serializable
data class EmulatorAvailability(
    /** The emulator that will be used (per-platform choice, else the first installed by priority). */
    val selected: EmulatorId? = null,
    val selectedName: String? = null,
    /** Every installed emulator that supports this platform. */
    val installed: List<EmulatorId> = emptyList(),
) {
    val hasEmulator: Boolean get() = selected != null
}
