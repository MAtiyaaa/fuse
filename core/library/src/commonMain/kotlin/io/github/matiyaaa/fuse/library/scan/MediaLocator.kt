package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsAccessException
import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.ScannedGame
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Media files found for one platform folder, keyed by the game's name relative to the platform
 * folder (without extension, lower case), as ES-DE and Batocera lay them out.
 */
class MediaIndex internal constructor(
    // Per kind: sources in priority order, each mapping key -> path.
    private val byKind: Map<MediaKind, List<Map<String, String>>>,
) {
    /** True when no media folder was found at all. */
    val isEmpty: Boolean get() = byKind.values.all { sources -> sources.all { it.isEmpty() } }

    /** The best file per kind for the first key that has one; [keys] are tried in order per source. */
    fun find(keys: List<String>): Map<MediaKind, String> {
        val lowered = keys.map { it.lowercase() }.distinct()
        val out = LinkedHashMap<MediaKind, String>()
        for ((kind, sources) in byKind) {
            val hit = sources.firstNotNullOfOrNull { source -> lowered.firstNotNullOfOrNull { source[it] } }
            if (hit != null) out[kind] = hit
        }
        return out
    }

    companion object {
        val Empty = MediaIndex(emptyMap())
    }
}

/**
 * Finds artwork that already sits next to a library, so Fuse can show it without scraping:
 * - type folders inside the platform folder (`snes/covers/`, `snes/videos/`...), or inside its
 *   `media/` or `downloaded_media/` folder;
 * - ES-DE's global layout `<mediaRoot>/<system>/<type>/<name>.png` for each configured media root,
 *   where `<system>` is the platform folder's name or any alias of the platform;
 * - Batocera's `images/<name>-thumb.png`, `-marquee`, `-fanart`, `-image` suffix convention.
 *
 * Type folders map to kinds: covers/boxart/box2dfront (then 3dboxes, then miximages) -> BOXART,
 * screenshots/snaps (then titlescreens) -> SCREENSHOT, marquees/wheel/logos -> LOGO,
 * fanart/heroes -> HERO, videos -> VIDEO, squares -> SQUARE, icons -> ICON, grids -> GRID. Subfolders mirror the ROM
 * folder (`covers/Europe/Game.png` for `psx/Europe/Game.chd`).
 */
class MediaLocator(private val fs: FuseFileSystem, private val maxDepth: Int = 3) {
    private data class TypeDir(val kind: MediaKind, val priority: Int)

    private val typeDirs = mapOf(
        "covers" to TypeDir(MediaKind.BOXART, 0), "boxart" to TypeDir(MediaKind.BOXART, 0),
        "box2dfront" to TypeDir(MediaKind.BOXART, 0), "3dboxes" to TypeDir(MediaKind.BOXART, 2),
        "miximages" to TypeDir(MediaKind.BOXART, 3),
        "screenshots" to TypeDir(MediaKind.SCREENSHOT, 0), "snaps" to TypeDir(MediaKind.SCREENSHOT, 0),
        "snap" to TypeDir(MediaKind.SCREENSHOT, 0), "titlescreens" to TypeDir(MediaKind.SCREENSHOT, 1),
        "marquees" to TypeDir(MediaKind.LOGO, 0), "wheel" to TypeDir(MediaKind.LOGO, 0),
        "logos" to TypeDir(MediaKind.LOGO, 0),
        "fanart" to TypeDir(MediaKind.HERO, 0), "heroes" to TypeDir(MediaKind.HERO, 0),
        "videos" to TypeDir(MediaKind.VIDEO, 0),
        "icons" to TypeDir(MediaKind.ICON, 0), "grids" to TypeDir(MediaKind.GRID, 0),
        "squares" to TypeDir(MediaKind.SQUARE, 0), "square" to TypeDir(MediaKind.SQUARE, 0),
    )

    // Batocera names media "<rom>-<suffix>.<ext>" inside images/ and videos/.
    private val suffixes = mapOf(
        "thumb" to TypeDir(MediaKind.BOXART, 1), "boxart" to TypeDir(MediaKind.BOXART, 1),
        "box" to TypeDir(MediaKind.BOXART, 1), "image" to TypeDir(MediaKind.SCREENSHOT, 2),
        "screenshot" to TypeDir(MediaKind.SCREENSHOT, 1), "titleshot" to TypeDir(MediaKind.SCREENSHOT, 1),
        "marquee" to TypeDir(MediaKind.LOGO, 1), "wheel" to TypeDir(MediaKind.LOGO, 1),
        "logo" to TypeDir(MediaKind.LOGO, 1), "fanart" to TypeDir(MediaKind.HERO, 1),
        "video" to TypeDir(MediaKind.VIDEO, 1), "square" to TypeDir(MediaKind.SQUARE, 1),
    )

    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v")

    /**
     * Indexes the media for [platform] whose games live in [platformFolder]. [systemName] is the
     * folder name used under ES-DE media roots (the platform folder's own name by default).
     */
    suspend fun index(
        platform: Platform,
        platformFolder: String,
        mediaRoots: List<String> = emptyList(),
        systemName: String = FsPath.name(platformFolder),
    ): MediaIndex {
        // (kind, priority, base order) -> key -> path
        val collected = HashMap<Triple<MediaKind, Int, Int>, MutableMap<String, String>>()
        val bases = ArrayList<String>()
        bases += FsPath.join(platformFolder, "media")
        bases += FsPath.join(platformFolder, "downloaded_media")
        bases += platformFolder
        val names = (platform.folderAliases + systemName.lowercase() + platform.id.value).toSet()
        for (root in mediaRoots) {
            safeList(root)?.filter { it.isDirectory && it.name.lowercase() in names }?.sortedBy { it.name }?.forEach { bases += it.path }
        }

        for ((order, base) in bases.withIndex()) {
            val children = safeList(base) ?: continue
            for (dir in children.filter { it.isDirectory }) {
                val lower = dir.name.lowercase()
                val type = typeDirs[lower]
                if (type == null && lower != "images") continue
                collect(dir.path, "", 0) { key, file ->
                    val (strippedKey, suffixType) = splitSuffix(key)
                    val resolved = when {
                        suffixType != null -> suffixType
                        type != null -> type
                        else -> TypeDir(MediaKind.BOXART, 2) // images/<name>.png without a suffix
                    }
                    if (!accepts(resolved.kind, file.extension)) return@collect
                    val slot = collected.getOrPut(Triple(resolved.kind, resolved.priority, order)) { HashMap() }
                    slot.getOrPut(strippedKey) { file.path }
                    if (suffixType == null && strippedKey != key) slot.getOrPut(key) { file.path }
                }
            }
        }
        val byKind = collected.entries
            .sortedWith(compareBy({ it.key.second }, { it.key.third }))
            .groupBy({ it.key.first }, { it.value.toMap() })
        return MediaIndex(byKind)
    }

    /** Lookup keys for [game], most specific first: its path relative to the platform folder, then its title. */
    fun keysFor(game: ScannedGame, platform: Platform, platformFolder: String): List<String> {
        val rel = FsPath.relativize(platformFolder, game.path)
        val relDir = FsPath.parent(rel)
        val name = FsPath.name(rel)
        val ext = FsPath.extension(name)
        val stem = if (game.kind == LocationKind.FILE || (ext.isNotEmpty() && ext in platform.extensions)) FsPath.stem(name) else name
        val keys = ArrayList<String>()
        keys += if (relDir != null) FsPath.join(relDir, stem) else stem
        keys += stem
        keys += game.title
        if (relDir != null) keys += FsPath.join(relDir, game.title)
        return keys.map { it.lowercase() }.distinct()
    }

    private fun accepts(kind: MediaKind, ext: String) =
        if (kind == MediaKind.VIDEO) ext in videoExtensions else ext in imageExtensions

    private fun splitSuffix(key: String): Pair<String, TypeDir?> {
        val dash = key.lastIndexOf('-')
        if (dash <= 0) return key to null
        val type = suffixes[key.substring(dash + 1)] ?: return key to null
        return key.substring(0, dash) to type
    }

    private suspend fun collect(dir: String, prefix: String, depth: Int, sink: (String, FsEntry) -> Unit) {
        currentCoroutineContext().ensureActive()
        val entries = safeList(dir) ?: return
        for (entry in entries) {
            if (entry.name.startsWith(".")) continue
            if (entry.isDirectory) {
                if (depth < maxDepth) collect(entry.path, prefix + entry.name + "/", depth + 1, sink)
            } else {
                sink((prefix + FsPath.stem(entry.name)).lowercase(), entry)
            }
        }
    }

    private suspend fun safeList(path: String): List<FsEntry>? = try {
        fs.list(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: FsAccessException) {
        null
    } catch (e: Exception) {
        null
    }
}
