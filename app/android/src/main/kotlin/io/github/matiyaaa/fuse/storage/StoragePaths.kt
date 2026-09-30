package io.github.matiyaaa.fuse.storage

import io.github.matiyaaa.fuse.model.LibrarySourceKind

/**
 * A mounted shared-storage volume: where it is mounted and the id the external storage documents
 * provider uses for it ("primary" for internal storage, the volume UUID for SD cards and USB drives).
 */
data class Volume(val root: String, val safId: String, val isPrimary: Boolean) {
    /** Human name for hints ("Internal storage", "SD card ABCD-1234"). */
    val label: String get() = if (isPrimary) "Internal storage" else "SD card $safId"
}

/** A library source root and how its folders are laid out. */
data class SourceRoot(val path: String, val kind: LibrarySourceKind)

/**
 * Pure path logic for Android shared storage. Kept free of Android classes so it is unit tested on
 * the JVM; the Android code turns the ids into URIs with `DocumentsContract`.
 */
object StoragePaths {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val PRIMARY_ID = "primary"

    private val primaryAliases = listOf("/sdcard", "/storage/self/primary", "/mnt/sdcard")

    /**
     * The filesystem path of an external storage document id from a folder picker:
     * `primary:ROMs` is `<primaryRoot>/ROMs`, `ABCD-1234:x` is the SD card's root plus `x`,
     * and `home:x` (the Documents shortcut) is `<primaryRoot>/Documents/x`. Null for ids that are
     * not paths (other providers, raw ids).
     */
    fun pathForDocumentId(docId: String, volumes: List<Volume>): String? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volumeId = docId.substring(0, colon)
        val relative = docId.substring(colon + 1).trim('/')
        if (relative.split('/').any { it == ".." }) return null
        val primary = volumes.firstOrNull { it.isPrimary }
        val root = when {
            volumeId.equals(PRIMARY_ID, ignoreCase = true) -> primary?.root ?: return null
            volumeId.equals("home", ignoreCase = true) -> (primary?.root ?: return null) + "/Documents"
            else -> volumes.firstOrNull { it.safId.equals(volumeId, ignoreCase = true) }?.root
                ?: if (VOLUME_UUID.matches(volumeId)) "/storage/$volumeId" else return null
        }
        return join(root, relative)
    }

    /**
     * Splits [path] into the volume it lives on and its path relative to that volume root, or null
     * when it is not on shared storage (Fuse's private cache, `/data`, unknown mounts).
     */
    fun locate(path: String, volumes: List<Volume>): Pair<Volume, String>? {
        val normalized = normalize(path, volumes)
        val volume = volumes
            .filter { normalized == it.root || normalized.startsWith(it.root.trimEnd('/') + "/") }
            .maxByOrNull { it.root.length }
            ?: return null
        return volume to normalized.removePrefix(volume.root.trimEnd('/')).trim('/')
    }

    /** Document id of [path] (`primary:ROMs/psx/Game.chd`), or null when it is not on shared storage. */
    fun documentId(path: String, volumes: List<Volume>): String? =
        locate(path, volumes)?.let { (volume, relative) -> "${volume.safId}:$relative" }

    /**
     * The SAF tree and document ids ES-DE's `%ROMSAF%` uses: the tree is the per-system folder that
     * holds [target] (for example `primary:ROMs/psx`) and the document is the target inside it. The
     * emulator must already hold a grant on exactly that tree. Null when [target] is not on shared
     * storage.
     */
    fun safIds(target: String, sources: List<SourceRoot>, volumes: List<Volume>): Pair<String, String>? {
        val (volume, relative) = locate(target, volumes) ?: return null
        val folder = systemFolder(normalize(target, volumes), sources.map { it.copy(path = normalize(it.path, volumes)) })
        val (folderVolume, folderRelative) = locate(folder, volumes) ?: return null
        if (folderVolume != volume) return null
        return "${volume.safId}:$folderRelative" to "${volume.safId}:$relative"
    }

    /**
     * The per-system folder that contains [target], the folder a user grants an emulator in ES-DE
     * setups (`ROMs/psx`). It follows the library source that holds the game:
     * - a ROMs root: its direct child (`ROMs/psx`);
     * - a RomM library: `roms/<platform>` (structure A) or `<platform>/roms` (structure B);
     * - a platform folder or shortcut folder: the source itself.
     * Without a matching source, the child of a folder named "roms" is used, else the parent folder.
     */
    fun systemFolder(target: String, sources: List<SourceRoot>): String {
        val clean = target.trimEnd('/')
        val source = sources
            .filter { isInside(clean, it.path) }
            .maxByOrNull { it.path.trimEnd('/').length }
        if (source != null) {
            val root = source.path.trimEnd('/')
            val segments = clean.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }
            return when (source.kind) {
                LibrarySourceKind.PLATFORM_FOLDER, LibrarySourceKind.SHORTCUTS -> root
                LibrarySourceKind.ROMS_ROOT -> if (segments.size >= 2) "$root/${segments[0]}" else root
                LibrarySourceKind.ROMM_LIBRARY -> when {
                    segments.size >= 3 && segments[0].equals("roms", ignoreCase = true) -> "$root/${segments[0]}/${segments[1]}"
                    segments.size >= 3 && segments[1].equals("roms", ignoreCase = true) -> "$root/${segments[0]}/${segments[1]}"
                    segments.size >= 2 -> "$root/${segments[0]}"
                    else -> root
                }
            }
        }
        val segments = clean.split('/')
        val romsIndex = segments.indexOfLast { it.equals("roms", ignoreCase = true) }
        if (romsIndex >= 0 && romsIndex + 2 < segments.size) {
            return segments.subList(0, romsIndex + 2).joinToString("/")
        }
        return clean.substringBeforeLast('/', clean).ifEmpty { "/" }
    }

    /** True when [path] is [folder] or below it. */
    fun isInside(path: String, folder: String): Boolean {
        val f = folder.trimEnd('/')
        val p = path.trimEnd('/')
        return p == f || p.startsWith("$f/")
    }

    /** Maps `/sdcard/...` style aliases of internal storage onto the primary volume root. */
    fun normalize(path: String, volumes: List<Volume>): String {
        val primary = volumes.firstOrNull { it.isPrimary }?.root ?: return path
        for (alias in primaryAliases) {
            if (path == alias || path.startsWith("$alias/")) return primary + path.removePrefix(alias)
        }
        return path
    }

    /** True when [relativePath] stays inside the folder it is resolved against. */
    fun isSafeRelative(relativePath: String): Boolean {
        if (relativePath.isBlank() || relativePath.startsWith('/') || relativePath.contains('\u0000')) return false
        return relativePath.split('/', '\\').none { it == ".." }
    }

    private fun join(root: String, relative: String): String =
        if (relative.isEmpty()) root.trimEnd('/') else root.trimEnd('/') + "/" + relative

    /** FAT/exFAT volume ids look like `ABCD-1234`; other removable volumes use longer hex UUIDs. */
    private val VOLUME_UUID = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|[0-9A-Fa-f-]{8,36}")
}
