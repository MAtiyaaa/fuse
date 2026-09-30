package io.github.matiyaaa.fuse.integrations.libretro

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.UrlCoding
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.ktor.client.HttpClient
import io.ktor.client.request.url
import io.ktor.http.HttpMethod

/** The libretro-thumbnails folders and the media kind each one fills. */
enum class LibretroThumbnailType(val folder: String, val kind: MediaKind) {
    BOXART("Named_Boxarts", MediaKind.BOXART),
    SNAP("Named_Snaps", MediaKind.SCREENSHOT),
    TITLE("Named_Titles", MediaKind.SCREENSHOT),
    LOGO("Named_Logos", MediaKind.LOGO),
}

/** A thumbnail that answered the HEAD probe. */
data class LibretroThumbnail(val system: String, val type: LibretroThumbnailType, val name: String, val url: String) {
    fun toArtworkOption(): ArtworkOption = ArtworkOption(
        provider = ScrapeProviderId.LIBRETRO,
        kind = type.kind,
        url = url,
        thumbUrl = url,
        width = null,
        height = null,
        style = if (type == LibretroThumbnailType.TITLE) "title" else null,
    )
}

/**
 * Libretro thumbnails (`https://thumbnails.libretro.com/{System}/{Named_*}/{Name}.png`). No key is
 * needed. The repository has no licence file, so Fuse fetches images at runtime and caches them on
 * the device only; nothing from it is ever bundled with Fuse.
 */
class LibretroThumbnails(
    http: HttpClient,
    limiter: RateLimiter = RateLimiter(minIntervalMillis = 100, maxConcurrency = 2),
    private val baseUrl: String = BASE_URL,
) {
    private val api = ProviderHttp(http, "Libretro thumbnails", limiter, { emptyList() })

    /**
     * Finds, for each of [types], the first name candidate whose image exists (HEAD 200), across
     * every libretro system folder of [platform]. Returns nothing for platforms without a folder.
     */
    suspend fun find(
        platform: PlatformId,
        label: String?,
        fileName: String?,
        types: Set<LibretroThumbnailType> = LibretroThumbnailType.entries.toSet(),
    ): ApiResult<List<LibretroThumbnail>> {
        val systems = systemsFor(platform)
        val names = nameCandidates(label, fileName)
        if (systems.isEmpty() || names.isEmpty()) return ApiResult.Success(emptyList())
        val found = ArrayList<LibretroThumbnail>()
        for (type in LibretroThumbnailType.entries.filter { it in types }) {
            search@ for (system in systems) for (name in names) {
                val url = url(system, type, name)
                when (val exists = exists(url)) {
                    is ApiResult.Failure -> return exists
                    is ApiResult.Success -> if (exists.value) {
                        found += LibretroThumbnail(system, type, name, url)
                        break@search
                    }
                }
            }
        }
        return ApiResult.Success(found)
    }

    /** HEAD probe: true for 2xx, false for 404/403, failure otherwise. */
    suspend fun exists(url: String): ApiResult<Boolean> = when (val r = api.execute { method = HttpMethod.Head; url(url) }) {
        is ApiResult.Failure -> r
        is ApiResult.Success -> when {
            r.value.isSuccess -> ApiResult.Success(true)
            r.value.status == 404 || r.value.status == 403 -> ApiResult.Success(false)
            else -> api.failureFor(r.value) ?: ApiResult.Success(false)
        }
    }

    /** The thumbnail URL for an already sanitised [name]. */
    fun url(system: String, type: LibretroThumbnailType, name: String): String =
        "$baseUrl/${UrlCoding.encode(system)}/${type.folder}/${UrlCoding.encode(name)}.png"

    companion object {
        const val BASE_URL = "https://thumbnails.libretro.com"

        /** libretro database/system folder names per Fuse platform id (RomM slugs). */
        val SYSTEMS: Map<String, List<String>> = mapOf(
            "nes" to listOf("Nintendo - Nintendo Entertainment System"),
            "famicom" to listOf("Nintendo - Nintendo Entertainment System"),
            "fds" to listOf("Nintendo - Family Computer Disk System"),
            "snes" to listOf("Nintendo - Super Nintendo Entertainment System"),
            "sfam" to listOf("Nintendo - Super Nintendo Entertainment System"),
            "n64" to listOf("Nintendo - Nintendo 64"),
            "gb" to listOf("Nintendo - Game Boy"),
            "gbc" to listOf("Nintendo - Game Boy Color"),
            "gba" to listOf("Nintendo - Game Boy Advance"),
            "nds" to listOf("Nintendo - Nintendo DS"),
            "3ds" to listOf("Nintendo - Nintendo 3DS"),
            "ngc" to listOf("Nintendo - GameCube"),
            "wii" to listOf("Nintendo - Wii"),
            "wiiu" to listOf("Nintendo - Wii U"),
            "virtualboy" to listOf("Nintendo - Virtual Boy"),
            "pokemon-mini" to listOf("Nintendo - Pokemon Mini"),
            "psx" to listOf("Sony - PlayStation"),
            "ps2" to listOf("Sony - PlayStation 2"),
            "ps3" to listOf("Sony - PlayStation 3"),
            "psp" to listOf("Sony - PlayStation Portable"),
            "psvita" to listOf("Sony - PlayStation Vita"),
            "genesis" to listOf("Sega - Mega Drive - Genesis"),
            "sms" to listOf("Sega - Master System - Mark III"),
            "gamegear" to listOf("Sega - Game Gear"),
            "segacd" to listOf("Sega - Mega-CD - Sega CD"),
            "sega32" to listOf("Sega - 32X"),
            "saturn" to listOf("Sega - Saturn"),
            "dc" to listOf("Sega - Dreamcast"),
            "sg1000" to listOf("Sega - SG-1000"),
            "tg16" to listOf("NEC - PC Engine - TurboGrafx 16"),
            "turbografx-cd" to listOf("NEC - PC Engine CD - TurboGrafx-CD"),
            "atari2600" to listOf("Atari - 2600"),
            "atari5200" to listOf("Atari - 5200"),
            "atari7800" to listOf("Atari - 7800"),
            "lynx" to listOf("Atari - Lynx"),
            "jaguar" to listOf("Atari - Jaguar"),
            "neo-geo-pocket" to listOf("SNK - Neo Geo Pocket"),
            "neo-geo-pocket-color" to listOf("SNK - Neo Geo Pocket Color"),
            "neo-geo-cd" to listOf("SNK - Neo Geo CD"),
            "wonderswan" to listOf("Bandai - WonderSwan"),
            "wonderswan-color" to listOf("Bandai - WonderSwan Color"),
            "colecovision" to listOf("Coleco - ColecoVision"),
            "intellivision" to listOf("Mattel - Intellivision"),
            "vectrex" to listOf("GCE - Vectrex"),
            "3do" to listOf("The 3DO Company - 3DO"),
            "msx" to listOf("Microsoft - MSX"),
            "xbox" to listOf("Microsoft - Xbox"),
            "dos" to listOf("DOS"),
            "arcade" to listOf("MAME", "FBNeo - Arcade Games"),
        )

        /** Folders for [platform]; empty when libretro has none (Switch, for example). */
        fun systemsFor(platform: PlatformId): List<String> = SYSTEMS[platform.value].orEmpty()

        private val unsafe = charArrayOf('&', '*', '/', ':', '`', '"', '<', '>', '?', '\\', '|')

        /** Replaces the characters libretro cannot use in file names (`& * / : \` " < > ? \ |`) with `_`. */
        fun sanitize(name: String): String {
            val sb = StringBuilder(name.length)
            for (c in name) sb.append(if (c in unsafe) '_' else c)
            return sb.toString().trim()
        }

        /**
         * Up to three sanitised names to try, in order: the [label] (for example a No-Intro title
         * from metadata), the file's base name, and the short name before the first " (".
         */
        fun nameCandidates(label: String?, fileName: String?): List<String> {
            val base = fileName?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.let { if ('.' in it) it.substringBeforeLast('.') else it }
            val short = (base ?: label)?.substringBefore(" (")?.substringBefore(" [")
            return listOfNotNull(label, base, short)
                .map { sanitize(it) }
                .filter { it.isNotEmpty() }
                .distinct()
        }
    }
}
