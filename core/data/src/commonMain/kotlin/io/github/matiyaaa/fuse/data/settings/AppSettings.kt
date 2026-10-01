package io.github.matiyaaa.fuse.data.settings

import io.github.matiyaaa.fuse.model.CrtSettings
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.DisplayProfile
import io.github.matiyaaa.fuse.model.GlassSettings
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.MatchStrictness
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.SoundProfile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder

/**
 * Global app settings, stored as one JSON document. Every field has a default, unknown fields are
 * ignored and unknown enum values fall back to defaults, so older and newer Fuse versions can read
 * each other's settings. Bump [CURRENT_VERSION] and add a step to [AppSettingsCodec] when a field
 * changes meaning.
 */
@Serializable
data class AppSettings(
    val version: Int = CURRENT_VERSION,
    val onboarding: OnboardingState = OnboardingState(),
    val home: HomeSettings = HomeSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val performance: PerformanceSettings = PerformanceSettings(),
    val input: InputProfile = InputProfile(),
    val display: DisplayProfile = DisplayProfile(),
    val scraping: ScrapingSettings = ScrapingSettings(),
    val videoPreview: VideoPreviewSettings = VideoPreviewSettings(),
    val library: LibraryPreferences = LibraryPreferences(),
    val sound: SoundSettings = SoundSettings(),
    val music: MusicSettings = MusicSettings(),
    val statusArea: StatusAreaSettings = StatusAreaSettings(),
    val cartridge: CartridgeSettings = CartridgeSettings(),
    val privacy: PrivacySettings = PrivacySettings(),
    val updates: UpdateSettings = UpdateSettings(),
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

@Serializable
data class OnboardingState(
    val completed: Boolean = false,
    /** Index of the step to resume at when onboarding was interrupted. */
    val step: Int = 0,
)

/** One root destination and whether it is shown. */
@Serializable
data class DestinationSetting(val destination: Destination, val visible: Boolean = true)

@Serializable
data class HomeSettings(
    /** Flow or Channels mode plus the widget layout. */
    val layout: HomeLayoutConfig = HomeLayoutConfig(),
    /** Root destinations in the user's order. Unknown entries from newer versions are dropped. */
    @Serializable(with = DestinationListSerializer::class)
    val destinations: List<DestinationSetting> = Destination.entries.map { DestinationSetting(it) },
    /**
     * Games taken off Continue Playing, by game id, with when. A game comes back once it is played
     * again after that time.
     */
    val continueDismissed: Map<String, Long> = emptyMap(),
) {
    /**
     * Visible destinations in order. Destinations missing from the stored list (added in a newer
     * version) are appended visible, and HOME is kept when everything was hidden.
     */
    fun visibleDestinations(): List<Destination> {
        val known = destinations.distinctBy { it.destination }
        val all = known + Destination.entries.filter { d -> known.none { it.destination == d } }.map { DestinationSetting(it) }
        return all.filter { it.visible }.map { it.destination }.ifEmpty { listOf(Destination.HOME) }
    }
}

@Serializable
data class AppearanceSettings(
    val themeId: String = "fuse",
    /** Null follows the theme's own glass settings. */
    val glass: GlassSettings? = null,
    /** Null follows the theme's own CRT settings. */
    val crt: CrtSettings? = null,
    /** Null follows the theme's motion profile. */
    val motion: MotionProfile? = null,
    /** How much the hero art behind the interface is darkened, 0..1. */
    val heroDim: Float = 0.3f,
    /** Adds an outline to the focused element on top of the glow. */
    val highContrastFocus: Boolean = false,
)

@Serializable
data class PerformanceSettings(
    val profile: PerformanceProfile = PerformanceProfile.AUTOMATIC,
    val lowPowerMode: Boolean = false,
    /** Shows the performance overlay (only values the system really reports). */
    val overlay: Boolean = false,
)

@Serializable
data class ScrapingSettings(
    /** Provider priority, first wins. Providers not listed are tried after, in enum order. */
    @Serializable(with = ScrapeProviderListSerializer::class)
    val providerOrder: List<ScrapeProviderId> = DefaultProviderOrder,
    @Serializable(with = ScrapeProviderListSerializer::class)
    val disabledProviders: List<ScrapeProviderId> = emptyList(),
    /** ISO 639-1 code for descriptions. */
    val preferredLanguage: String = "en",
    /** Preferred release region (for example "us", "eu", "jp"), or null for any. */
    val preferredRegion: String? = null,
    /** Global matching strictness; the global level of [io.github.matiyaaa.fuse.model.ScopedSettings.Matching]. */
    val matching: MatchStrictness = MatchStrictness.NORMAL,
    /** Look for missing art and details by itself after scans and when a source is added. */
    val autoFill: Boolean = true,
) {
    /** Enabled providers in effective order. */
    fun effectiveOrder(): List<ScrapeProviderId> =
        (providerOrder.distinct() + ScrapeProviderId.entries.filterNot { it in providerOrder }).filterNot { it in disabledProviders }

    companion object {
        val DefaultProviderOrder = listOf(
            ScrapeProviderId.LOCAL,
            ScrapeProviderId.ROMM,
            ScrapeProviderId.STEAMGRIDDB,
            ScrapeProviderId.IGDB,
            ScrapeProviderId.SCREENSCRAPER,
            ScrapeProviderId.THEGAMESDB,
            ScrapeProviderId.LIBRETRO,
        )
    }
}

/** Global video preview; the global level of the scoped VideoPreview / VideoDelaySeconds keys. */
@Serializable
data class VideoPreviewSettings(
    val enabled: Boolean = true,
    val delaySeconds: Int = 10,
)

@Serializable
data class LibraryPreferences(
    /**
     * Clean Display Names: show cleaned titles ("Metroid Fusion" for "Metroid Fusion (USA) [!]").
     * On by default since 0.0.2; every cleanup can be undone and custom titles are never touched.
     */
    val cleanDisplayNames: Boolean = true,
    /** Existing games were given cleaned names once, when clean names became the default. */
    val cleanedExistingNames: Boolean = false,
    /** The cleaning rules the library's names were last cleaned with (see DefaultFuseStore.CLEAN_RULES). */
    val cleanedNamesRules: Int = 1,
    /** Systems in the user's order, by platform id. Systems not listed follow in catalog order. */
    val systemOrder: List<String> = emptyList(),
    /** Fetch system logos and art from the system art pack when a system has none. */
    val systemArtAuto: Boolean = true,
    /** How the Library is sorted. */
    val sort: io.github.matiyaaa.fuse.model.SortOrder = io.github.matiyaaa.fuse.model.SortOrder.TITLE,
    /** Artwork set used from the system art pack (a SystemArtStyle name). */
    val systemArtStyle: String = "CLASSIC",
    /** Confirm on a game opens its page instead of starting it. */
    val selectOpensGamePage: Boolean = false,
    /** Phone Link's server runs while this is on (Settings, Phone Link). */
    val phoneLinkEnabled: Boolean = false,
    /** Collections as a whole; off hides them everywhere (they are kept). */
    val collectionsEnabled: Boolean = true,
    /** Fuse makes a collection for each series it finds and keeps it up to date. */
    val autoSeries: Boolean = true,
    /** Series the user hid or kept as their own collection, lower case, so Fuse doesn't make them again. */
    val hiddenSeries: List<String> = emptyList(),
    /** Brand colours from the system art pack (opaque ARGB), by platform id. */
    val systemColors: Map<String, Long> = emptyMap(),
)

/**
 * Interface sounds. [InputProfile.soundsEnabled] and [InputProfile.navigationSoundVolume] cover
 * navigation feedback only; these are the master switch and volume.
 */
@Serializable
data class SoundSettings(
    val enabled: Boolean = true,
    /** 0..1 */
    val volume: Float = 0.7f,
    /** Null follows the theme's sound profile. */
    val profile: SoundProfile? = null,
)

/**
 * Music under Fuse's menus: one of the songs Fuse ships, or a file the user picked, which is copied
 * into Fuse's storage so it keeps playing after the original moves.
 */
@Serializable
data class MusicSettings(
    val enabled: Boolean = true,
    /** 0..1, low by default so the music sits under the interface. */
    val volume: Float = 0.2f,
    /** Fuse's copy of the user's own song; null when none was chosen. */
    val songPath: String? = null,
    /** What Settings calls the song (the picked file's name). */
    val songName: String? = null,
    /**
     * A bundled song's id, or "file" for the user's own song. Null for settings from before songs
     * were bundled: the user's own song if there is one, else the default bundled song.
     */
    val track: String? = null,
)

@Serializable
data class StatusAreaSettings(
    val showClock: Boolean = true,
    /** Null follows the system setting. */
    val use24HourClock: Boolean? = null,
    val showBattery: Boolean = true,
    val showWifi: Boolean = true,
    val showBluetooth: Boolean = true,
    val showNetwork: Boolean = true,
)

@Serializable
data class CartridgeSettings(
    /** Off: Fuse leaves Cartridge alone entirely (no tab, widget, menu entries or status reads). */
    val enabled: Boolean = true,
    /** Rescan the folders Cartridge changed when returning from it. */
    val autoRefreshOnReturn: Boolean = true,
    /**
     * Use RomM's details and pictures for games Cartridge downloaded (bridge protocol 2): they fill
     * empty fields and replace scraped ones, never what the user set.
     */
    val rommDetails: Boolean = true,
)

/**
 * Fuse contains no telemetry, analytics or crash reporting. [telemetry] exists only so the Privacy
 * screen can show that explicitly; nothing reads it to send data, and it is always false.
 */
@Serializable
data class PrivacySettings(
    val telemetry: Boolean = false,
)

@Serializable
enum class UpdateChannel { STABLE, PRERELEASE }

@Serializable
data class UpdateSettings(
    /** Check GitHub releases for a newer Fuse (and Cartridge) version. */
    val checkForUpdates: Boolean = true,
    val channel: UpdateChannel = UpdateChannel.STABLE,
)

/**
 * List serializer that drops elements it cannot decode (for example an enum value added by a newer
 * version) instead of failing the whole document.
 */
abstract class LenientListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val strict = ListSerializer(element)
    override val descriptor: SerialDescriptor = strict.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = strict.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return strict.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { runCatching { json.json.decodeFromJsonElement(element, it) }.getOrNull() }
    }
}

object ScrapeProviderListSerializer : LenientListSerializer<ScrapeProviderId>(ScrapeProviderId.serializer())

object DestinationListSerializer : LenientListSerializer<DestinationSetting>(DestinationSetting.serializer())
