package io.github.matiyaaa.fuse.data.backup

/** Packs [entries] (name, bytes) into a zip. */
internal expect fun zip(entries: List<Pair<String, ByteArray>>): ByteArray

/** The files in zip [bytes] by name, or null when it isn't a zip or unpacks past [maxTotalBytes]. */
internal expect fun unzip(bytes: ByteArray, maxTotalBytes: Long): Map<String, ByteArray>?

/** An opened backup: what it says it is, what it holds, and its pictures by their name inside it. */
class BackupArchive(val manifest: BackupManifest, val content: BackupContent, val media: Map<String, ByteArray>) {
    /** Made by a newer Fuse: read as far as this one understands it. */
    val newer: Boolean get() = manifest.version > BackupManifest.VERSION
}

/** Why a file couldn't be opened as a backup. */
enum class BackupProblem {
    /** Not a zip, or a zip without a Fuse manifest. */
    NOT_A_BACKUP,

    /** A Fuse backup whose contents couldn't be read. */
    DAMAGED,

    /** Bigger than any backup Fuse makes. */
    TOO_BIG,
}

sealed interface BackupRead {
    data class Opened(val archive: BackupArchive) : BackupRead
    data class Failed(val problem: BackupProblem) : BackupRead
}

/**
 * The `.fusebackup` file: a zip holding `manifest.json`, `content.json` and the user's own pictures
 * under `media/`. Pictures are found by the names `content.json` gives them, never by paths taken
 * from the archive, so a backup can't write anywhere but where Fuse puts restored art.
 */
object BackupFile {
    const val EXTENSION = "fusebackup"
    const val MIME_TYPE = "application/octet-stream"

    /** Pictures larger than this stay out of a backup; their links (when they have one) still go in. */
    const val MAX_PICTURE_BYTES = 12 * 1024 * 1024

    /** The most a backup may unpack to. */
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    private const val MANIFEST = "manifest.json"
    private const val CONTENT = "content.json"

    fun write(manifest: BackupManifest, content: BackupContent, media: Map<String, ByteArray>): ByteArray {
        val codec = BackupRepository.Codec
        val entries = buildList {
            add(MANIFEST to codec.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray())
            add(CONTENT to codec.encodeToString(BackupContent.serializer(), content).encodeToByteArray())
            media.forEach { (name, bytes) -> add(name to bytes) }
        }
        return zip(entries)
    }

    fun read(bytes: ByteArray): BackupRead {
        if (bytes.size > MAX_TOTAL_BYTES) return BackupRead.Failed(BackupProblem.TOO_BIG)
        val files = unzip(bytes, MAX_TOTAL_BYTES) ?: return BackupRead.Failed(BackupProblem.NOT_A_BACKUP)
        val codec = BackupRepository.Codec
        val manifest = files[MANIFEST]
            ?.let { runCatching { codec.decodeFromString(BackupManifest.serializer(), it.decodeToString()) }.getOrNull() }
            ?.takeIf { it.format == BackupManifest.FORMAT }
            ?: return BackupRead.Failed(BackupProblem.NOT_A_BACKUP)
        val content = files[CONTENT]
            ?.let { runCatching { codec.decodeFromString(BackupContent.serializer(), it.decodeToString()) }.getOrNull() }
            ?: return BackupRead.Failed(BackupProblem.DAMAGED)
        val named = content.media.mapNotNull { it.file }.toSet()
        return BackupRead.Opened(BackupArchive(manifest, content, files.filterKeys { it in named }))
    }

    /** The name a picture takes inside a backup: `media/<n>.<ext>`, keeping a known picture extension. */
    fun pictureName(index: Int, path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase().takeIf { it in PICTURE_EXTENSIONS } ?: "img"
        return "media/$index.$ext"
    }

    private val PICTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "avif")
}
