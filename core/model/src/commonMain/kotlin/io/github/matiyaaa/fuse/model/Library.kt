package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/** A folder the user pointed Fuse at. Fuse only reads from it. */
@Serializable
data class LibrarySource(
    val id: LibrarySourceId,
    val path: String,
    val label: String,
    val kind: LibrarySourceKind,
    val enabled: Boolean = true,
    /** Epoch millis of the last completed scan. */
    val lastScanAt: Long? = null,
    /** The drive the folder lives on, once Fuse has seen it mounted. */
    val volume: VolumeRef? = null,
)

@Serializable
enum class LibrarySourceKind {
    /** A folder of platform folders (ES-DE `ROMs/`, RomM Structure A `roms/`). */
    ROMS_ROOT,
    /** A RomM library root (`library/`), Structure A or B. */
    ROMM_LIBRARY,
    /** One platform's folder added directly. */
    PLATFORM_FOLDER,
    /** A folder of Steam/PC frontend shortcuts. */
    SHORTCUTS,
    /**
     * A Steam library on a computer (the folder holding `steamapps`): the games Steam has
     * installed there, read from Steam's own manifests, each at its `steamapps/common` folder.
     */
    STEAM_LIBRARY,
}

/** Presentation modes for game lists. Changing mode never touches library data. */
@Serializable
enum class LibraryLayout { ICON, CAPSULE, COVER_GRID, COMPACT_LIST }

/**
 * How game tiles are drawn wherever Fuse shows a grid or row of games: square box art (as Fuse has
 * always shown), or tall posters (the portrait cover, like a shelf of cases).
 */
@Serializable
enum class GameArtStyle { BOX_ART, POSTER }

@Serializable
enum class SortOrder { TITLE, RECENTLY_PLAYED, RECENTLY_ADDED, MOST_PLAYED, RELEASE_YEAR }

@Serializable
data class LibraryFilter(
    val platform: PlatformId? = null,
    val collection: CollectionId? = null,
    val favoritesOnly: Boolean = false,
    val showHidden: Boolean = false,
    val query: String = "",
)

/** Progress of a running scan, shown without blocking the interface. */
@Serializable
data class ScanProgress(
    val phase: ScanPhase,
    val currentPath: String? = null,
    val foldersVisited: Int = 0,
    val gamesFound: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val changed: Int = 0,
)

@Serializable
enum class ScanPhase { IDLE, DISCOVERING, SCANNING, SAVING, DONE, FAILED }

/** What kind of rescan the user asked for. */
@Serializable
enum class ScanScope {
    /** Only folders whose modification time changed. */
    QUICK,
    /** One platform, fully. */
    PLATFORM,
    /** Everything, ignoring cached folder state. */
    FULL,
}
