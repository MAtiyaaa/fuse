package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * A game as the scanner found it on disk, before it has a database identity. The scanner produces
 * these; the data layer reconciles them into [Game]s and never loses user edits while doing so.
 */
@Serializable
data class ScannedGame(
    val platformId: PlatformId,
    val sourceId: LibrarySourceId,
    /** The game's own entry: a file, or the title folder. Unique per game. */
    val path: String,
    val kind: LocationKind,
    val launchPath: String,
    /** Title from the file or folder name, without extension (the "original" title). */
    val title: String,
    val tags: FilenameTags = FilenameTags(),
    val content: List<ChildContent> = emptyList(),
    val discs: List<Disc> = emptyList(),
    val sizeBytes: Long = 0,
    val modifiedAt: Long = 0,
    val interpretation: FolderInterpretation = FolderInterpretation.SINGLE_FILE,
    /** Media found next to the game (ES-DE downloaded_media, RomM-style folders), by kind. */
    val localMedia: Map<MediaKind, String> = emptyMap(),
)

/**
 * The result of scanning one platform folder. When [complete] is true, [games] is everything in that
 * folder, so games previously seen there but absent now are missing (marked, never deleted).
 */
@Serializable
data class PlatformFolderScan(
    val sourceId: LibrarySourceId,
    val platformId: PlatformId,
    val folderPath: String,
    val folderModifiedAt: Long,
    val games: List<ScannedGame>,
    val complete: Boolean = true,
    /** Folders read in this scan that are not games: an earlier entry for one of them is forgotten. */
    val notGames: Set<String> = emptySet(),
    /** Folders whose whole tree is not games (an emulator's saves and caches). */
    val notGameTrees: Set<String> = emptySet(),
    /**
     * Files that were games of their own and are now part of another game (a copy of it, its update
     * or DLC): an earlier entry for one of them is forgotten, unless the person did something with it.
     */
    val absorbed: Set<String> = emptySet(),
    /** Proven ownership from scanner consolidation. Never infer migration ownership from a similar title. */
    val absorbedBy: Map<String, String> = emptyMap(),
    /** Commit only when this report has been reconciled; interrupted upgrades must retry. */
    val rulesVersion: Long? = null,
)

/** A folder the scanner could map to a platform, or could not. */
@Serializable
data class DiscoveredFolder(
    val path: String,
    val name: String,
    val platformId: PlatformId?,
    val modifiedAt: Long,
)

/** Everything a scan pass produced. Folders that were unchanged since last time are listed, not rescanned. */
@Serializable
data class ScanReport(
    val scanned: List<PlatformFolderScan>,
    val unchanged: List<DiscoveredFolder>,
    val unknownFolders: List<DiscoveredFolder>,
    val errors: List<String> = emptyList(),
)

/**
 * Remembers what the scanner saw last time so a quick scan only walks folders that changed.
 * Implemented by the data layer.
 */
interface FolderStateStore {
    /** Last seen modification time of a folder, or null if never scanned. */
    suspend fun lastModified(path: String): Long?
    suspend fun remember(path: String, modifiedAt: Long)
    suspend fun forget(pathPrefix: String)

    /** Semantic rule revision, kept separately from filesystem dates so upgrades reinterpret once. */
    suspend fun rulesVersion(path: String): Long? = lastModified(scannerRulesKey(path))
    suspend fun rememberRulesVersion(path: String, version: Long) = remember(scannerRulesKey(path), version)

}

/** Virtual folder-state key, never interpreted as a filesystem path. NUL cannot occur in a real path. */
fun scannerRulesKey(path: String): String = "\u0000fuse-scanner-rules:$path"
