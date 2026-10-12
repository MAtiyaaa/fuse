package io.github.matiyaaa.fuse.integrations.emucorex

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A PS2 texture pack in EmuCoreX's public catalogue (github.com/sashkinbro/EmuCoreX-Textures,
 * textures.json): made for the discs [serials] name, with its makers, the credits and licence they
 * gave, and the archive's size and SHA-256.
 */
@Serializable
data class ExTexturePack(
    val id: String,
    val name: String,
    val gameTitle: String? = null,
    val serials: List<String> = emptyList(),
    val version: String? = null,
    val authors: List<String> = emptyList(),
    val credits: String? = null,
    val description: String? = null,
    val downloadUrl: String,
    val sourceUrl: String? = null,
    val license: String? = null,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    val fileCount: Int = 0,
    /** The archive split in pieces (each its own download), for packs too big for one file. */
    val parts: List<ExPart> = emptyList(),
) {
    /** Why Fuse leaves this pack to its own page, in plain words; null when Fuse can fetch it. */
    val notFetchable: String?
        get() = when {
            parts.size > 1 -> "it comes in ${parts.size} pieces, which Fuse doesn't join"
            !EmuCoreX.trusted(downloadUrl) -> "it isn't on EmuCoreX's own GitHub"
            !downloadUrl.substringBefore('?').endsWith(".zip", ignoreCase = true) -> "it isn't a .zip archive, the only kind Fuse opens"
            sha256.isNullOrBlank() || sizeBytes <= 0 -> "the catalogue gives no size and checksum to check it against"
            sizeBytes > EmuCoreX.MAX_PACK_BYTES -> "it is larger than Fuse fetches (4 GB)"
            license?.contains("no explicit redistribution", ignoreCase = true) == true -> "its makers gave no permission to share it, so it is fetched from their page"
            else -> null
        }
}

@Serializable
data class ExPart(val downloadUrl: String, val sizeBytes: Long = 0, val sha256: String? = null)

/**
 * A `.pnach` in EmuCoreX's cheat catalogue (github.com/sashkinbro/EmuCoreX-Cheat, cheats.json),
 * gathered from community collections ([sourceName]) and kept with where it came from. PCSX2 reads
 * a pnach by the game's [crc], so that is what it is matched by.
 */
@Serializable
data class ExCheat(
    val id: String,
    val title: String,
    val serials: List<String> = emptyList(),
    val crc: String? = null,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val downloadUrl: String,
    val sourceUrl: String? = null,
    val sourceName: String? = null,
    val license: String? = null,
    val blockCount: Int = 0,
) {
    /**
     * A game patch (widescreen, frame rate, fixes) rather than a cheat, by the collection it comes
     * from: PCSX2's own patches, the widescreen databases and the enhancement collections.
     */
    val isPatch: Boolean
        get() = sourceName?.let { s -> PATCH_SOURCES.any { it in s.lowercase() } } == true

    val notFetchable: String?
        get() = when {
            !EmuCoreX.trusted(downloadUrl) -> "it isn't on GitHub"
            !downloadUrl.substringBefore('?').endsWith(".pnach", ignoreCase = true) -> "it isn't a .pnach file"
            else -> null
        }
}

/** Words in a collection's name that make its files patches rather than cheats. */
private val PATCH_SOURCES = listOf("patch", "widescreen", "enhancement")

/**
 * EmuCoreX's public catalogues of PS2 texture packs and pnach cheats and patches: two JSON files on
 * GitHub, read as they are. A pack is matched to a game by the disc's serial and a pnach by the
 * CRC PCSX2 gives the game, never by name. Files are fetched only from GitHub, packs checked
 * against the catalogue's size and SHA-256.
 */
object EmuCoreX {
    const val TEXTURES_URL = "https://raw.githubusercontent.com/sashkinbro/EmuCoreX-Textures/main/textures.json"
    const val CHEATS_URL = "https://raw.githubusercontent.com/sashkinbro/EmuCoreX-Cheat/main/cheats.json"
    const val HOME = "https://github.com/sashkinbro/EmuCoreX"

    const val MAX_PACK_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_PNACH_BYTES = 1024L * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable
    private data class Catalogue<T>(val schemaVersion: Int = 0, val entries: List<T> = emptyList())

    /** The texture catalogue, or null when it isn't one (another schema, or not JSON). */
    fun parseTextures(body: String): List<ExTexturePack>? = runCatching {
        json.decodeFromString(Catalogue.serializer(ExTexturePack.serializer()), body).takeIf { it.schemaVersion == 1 }?.entries
    }.getOrNull()

    fun parseCheats(body: String): List<ExCheat>? = runCatching {
        json.decodeFromString(Catalogue.serializer(ExCheat.serializer()), body).takeIf { it.schemaVersion == 1 }?.entries
    }.getOrNull()

    /** Packs made for the disc [serial] ("SLUS-21287", "SLUS_212.87" alike). */
    fun packsFor(packs: List<ExTexturePack>, serial: String): List<ExTexturePack> {
        val key = serialKey(serial) ?: return emptyList()
        return packs.filter { p -> p.serials.any { serialKey(it) == key } }
    }

    /** Pnach files PCSX2 would read for the game whose CRC is [crc]. */
    fun cheatsFor(cheats: List<ExCheat>, crc: String): List<ExCheat> {
        val key = crc.trim().uppercase().takeIf { CRC.matches(it) } ?: return emptyList()
        return cheats.filter { it.crc?.trim()?.uppercase() == key }
    }

    /** A disc serial as letters and digits only ("SLUS21287"); null for anything that isn't one. */
    fun serialKey(serial: String): String? = serial.uppercase().filter { it.isLetterOrDigit() }.takeIf { SERIAL.matches(it) }

    /** Only GitHub's own addresses, over HTTPS. */
    fun trusted(url: String): Boolean =
        url.startsWith("https://github.com/") || url.startsWith("https://raw.githubusercontent.com/")

    private val CRC = Regex("[0-9A-F]{8}")
    private val SERIAL = Regex("[A-Z]{4}\\d{5}")
}
