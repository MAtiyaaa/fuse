package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/** Every kind of artwork a game, platform or collection can carry. */
@Serializable
enum class MediaKind(
    /** Width / height the artwork is designed for; null when it varies (logos, borders). */
    val aspect: Float?,
) {
    /** Square app-style icon, the tile in Icon Mode. */
    ICON(1f),
    /** Portrait box art / capsule. */
    BOXART(0.72f),
    /** Wide grid capsule (Steam-style 460x215). */
    GRID(460f / 215f),
    /** Wide background behind the interface. */
    HERO(1920f / 620f),
    /** Transparent title logo drawn over the hero. */
    LOGO(null),
    SCREENSHOT(4f / 3f),
    /** Muted gameplay preview. */
    VIDEO(16f / 9f),
    /** Frame around the artwork (Dynamic Borders). */
    BORDER(null),
    ;
}

/**
 * Where a piece of artwork came from. [USER] media is never replaced without an explicit action.
 * [ART_PACK] is platform art from the Art Book Next system art pack.
 */
@Serializable
enum class MediaSource { USER, LOCAL_FOLDER, ROMM, STEAMGRIDDB, IGDB, THEGAMESDB, SCREENSCRAPER, LIBRETRO, GENERATED, ART_PACK }

/** What a media record belongs to. */
@Serializable
sealed interface MediaOwner {
    @Serializable
    data class OfGame(val id: GameId) : MediaOwner

    @Serializable
    data class OfPlatform(val id: PlatformId) : MediaOwner

    @Serializable
    data class OfCollection(val id: CollectionId) : MediaOwner

    @Serializable
    data class OfApp(val packageName: String) : MediaOwner
}

/**
 * One stored artwork. [localPath] is Fuse's own cached copy; [remoteUrl] is kept so a lost cache can
 * be refetched. Focus/crop let the user choose what part of a hero stays visible.
 */
@Serializable
data class MediaItem(
    val kind: MediaKind,
    val source: MediaSource,
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** 0..1 horizontal / vertical focal point used when the art is cropped. */
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    /** Extra zoom applied on top of fill-cropping, 1 = none. */
    val zoom: Float = 1f,
    val order: Int = 0,
) {
    val isCustom: Boolean get() = source == MediaSource.USER
    val model: String? get() = localPath ?: remoteUrl
}

/** All artwork for one owner, resolved to what the UI should draw. */
@Serializable
data class MediaSet(
    val items: List<MediaItem> = emptyList(),
) {
    fun first(kind: MediaKind): MediaItem? = items.filter { it.kind == kind }.minByOrNull { it.order }
    fun all(kind: MediaKind): List<MediaItem> = items.filter { it.kind == kind }.sortedBy { it.order }
    fun has(kind: MediaKind): Boolean = items.any { it.kind == kind }

    val icon get() = first(MediaKind.ICON)
    val boxart get() = first(MediaKind.BOXART)
    val grid get() = first(MediaKind.GRID)
    val hero get() = first(MediaKind.HERO)
    val logo get() = first(MediaKind.LOGO)
    val video get() = first(MediaKind.VIDEO)
    val screenshots get() = all(MediaKind.SCREENSHOT)

    fun missing(kinds: Collection<MediaKind>): List<MediaKind> = kinds.filterNot(::has)

    companion object {
        val Empty = MediaSet()
    }
}

/** How a media fill job treats art that already exists. */
@Serializable
enum class MediaFillMode {
    /** Only kinds with nothing stored. */
    FILL_MISSING,
    /** Replace the kinds the user selected, except custom media. */
    REPLACE_SELECTED,
    /** Replace every non-custom kind. Custom media still needs its own explicit reset. */
    REPLACE_ALL,
}

/** Frame drawn around artwork. Resolved Global -> Platform -> Game like other settings. */
@Serializable
data class BorderStyle(
    val mode: BorderMode = BorderMode.OFF,
    val shape: BorderShape = BorderShape.ROUNDED,
    val gradient: Boolean = true,
    val logoOverlay: Boolean = false,
    /** ARGB accent, or null to use the platform accent. */
    val accent: Long? = null,
    val customFramePath: String? = null,
)

@Serializable
enum class BorderMode { OFF, PLATFORM_DEFAULT, CUSTOM }

@Serializable
enum class BorderShape { ROUNDED, SQUARE, CIRCLE }
