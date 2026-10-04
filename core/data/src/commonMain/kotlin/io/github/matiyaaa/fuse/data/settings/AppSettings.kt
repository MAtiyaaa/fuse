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
import io.github.matiyaaa.fuse.model.StoreVariant
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
    val capture: CaptureSettings = CaptureSettings(),
    val store: StoreSettings = StoreSettings(),
    val jellyfin: JellyfinSettings = JellyfinSettings(),
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

/**
 * Jellyfin, an addon (Settings, Addons, Jellyfin): off until turned on. How Fuse reaches the
 * server, what it prefers to hear and read, and how Fuse Player behaves. The sign-in itself is in
 * the secret store, never here.
 */
@Serializable
data class JellyfinSettings(
    val enabled: Boolean = false,
    /** "AUTO", "LOCAL" or "REMOTE". */
    val mode: String = "AUTO",
    val localAddress: String = "",
    val remoteAddress: String = "",
    /** ISO 639 codes ("eng", "jpn"); empty follows the server's defaults. */
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
    /** "DEFAULT" (the server's choice), "ALWAYS", "FOREIGN" (when the sound isn't in your language), "FORCED" or "OFF". */
    val subtitleMode: String = "DEFAULT",
    val subtitleScale: Float = 1f,
    val subtitleLift: Float = 0f,
    val subtitleBackground: Boolean = false,
    /** The most each route should carry, in bits per second; 0 for no limit. */
    val localMaxBitrate: Long = 0,
    val remoteMaxBitrate: Long = 20_000_000,
    val hardwareDecoding: Boolean = true,
    val autoplayNext: Boolean = true,
    val seekSeconds: Int = 10,
    val controlsTimeoutSeconds: Int = 4,
    val rememberSpeed: Boolean = false,
    val speed: Float = 1f,
    /** The other screen while playing: "REMOTE" (controls and art) or "OFF". */
    val playerCompanion: String = "REMOTE",
    /** The other screen while browsing: "DETAILS", "MINIMAL" or "OFF". */
    val browsingCompanion: String = "DETAILS",
)

/**
 * The Store (Android): which edition of the Obtainium Emulation Pack it follows (null until the
 * user chose one), whether installed apps are checked for updates by themselves, and what Fuse
 * installed through it.
 */
@Serializable
data class StoreSettings(
    val variant: StoreVariant? = null,
    val autoCheck: Boolean = true,
    /** Apps Fuse installed or updated, by their key in the catalogue. */
    val installs: Map<String, StoreInstall> = emptyMap(),
    /** Apps the user added to the Store by their GitHub address, shown under their category. */
    val custom: List<CustomStoreApp> = emptyList(),
    /**
     * The GitHub repository the catalogue comes from, for a fork of the Obtainium Emulation Pack
     * that publishes its files the same way; null for the pack itself.
     */
    val packRepo: String? = null,
)

/** An app the user added to the Store: its GitHub repository, its name and the category it goes under. */
@Serializable
data class CustomStoreApp(val url: String, val name: String, val category: String = "Other")

/**
 * One app Fuse installed from the Store: the package Android installed it as (the first install
 * pins it, so later updates must be the same app), the upstream version and file it was, and the
 * version code Android gave it then. When the app changes outside Fuse, its version code no longer
 * matches and Fuse goes by what Android reports instead.
 */
@Serializable
data class StoreInstall(
    val packageName: String,
    val version: String? = null,
    val file: String? = null,
    val versionCode: Long = 0,
    val installedAt: Long = 0,
)

/** Screenshots and recordings of Fuse's own screen. */
@Serializable
data class CaptureSettings(
    /** L3 + R3 takes a screenshot; held, it starts or stops a recording. */
    val combo: Boolean = true,
    /** Recordings include Fuse's own sound (the menu music) where the system allows it. */
    val sound: Boolean = true,
)

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
    /** Addons' tabs (cartridge, store, jellyfin) in the order the user dragged them into; the rest follow. */
    val addonsOrder: List<String> = emptyList(),
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
    /** How large text is: 1 as designed, up to 1.3 for reading from further away. */
    val textScale: Float = 1f,
    /** Room kept clear at every edge, in percent of the screen, for TVs that cut the picture's edges off. */
    val screenMargin: Int = 0,
    /** Themes added from a link, a file or pasted text, kept as they were written. */
    val customThemes: List<StoredTheme> = emptyList(),
    /** Fuse's mark lights up when Fuse starts. */
    val startupAnimation: Boolean = true,
    /** Colours picked last in the theme studio, newest first (ARGB), to pick again in one move. */
    val recentColors: List<Long> = emptyList(),
    /** Each tab keeps the game or row you were on when you come back to it. */
    val rememberPlace: Boolean = true,
    /**
     * Minutes without a touch, a button or the stick before Fuse dims to its standby screen (which
     * keeps an OLED screen from wearing in); 0 never.
     */
    val standbyMinutes: Int = 5,
)

/**
 * An added theme: its id (always starting "custom."), the theme file as it was given (so parts a
 * later Fuse understands are kept), where it came from and when.
 */
@Serializable
data class StoredTheme(
    val id: String,
    val json: String,
    val source: String? = null,
    val addedAt: Long = 0,
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
    /** Systems whose firmware the user marked as set up although Fuse didn't find it all, by platform id. */
    val biosConfirmed: List<String> = emptyList(),
    /** Drives Fuse asked about setting up for games (yes or no), by drive id, so it asks once. */
    val drivesAsked: List<String> = emptyList(),
    /** Fetch system logos and art from the system art pack when a system has none. */
    val systemArtAuto: Boolean = true,
    /** How the Library is sorted. */
    val sort: io.github.matiyaaa.fuse.model.SortOrder = io.github.matiyaaa.fuse.model.SortOrder.TITLE,
    /** Artwork set used from the system art pack (a SystemArtStyle name). */
    val systemArtStyle: String = "CLASSIC",
    /** Confirm on a game opens its page instead of starting it. */
    val selectOpensGamePage: Boolean = false,
    /** Phone Link's server runs while this is on (Settings, Accounts, Phone Link). */
    val phoneLinkEnabled: Boolean = false,
    /** A signed-in phone may be used as a controller (Phone Link's Remote). */
    val phoneLinkController: Boolean = true,
    /** Collections as a whole; off hides them everywhere (they are kept). */
    val collectionsEnabled: Boolean = true,
    /** Fuse makes a collection for each series it finds and keeps it up to date. */
    val autoSeries: Boolean = true,
    /** Series the user hid or kept as their own collection, lower case, so Fuse doesn't make them again. */
    val hiddenSeries: List<String> = emptyList(),
    /** Brand colours from the system art pack (opaque ARGB), by platform id. */
    val systemColors: Map<String, Long> = emptyMap(),
    /** Which of its lists the Apps tab opens on. */
    val appsFilter: io.github.matiyaaa.fuse.model.AppFilter = io.github.matiyaaa.fuse.model.AppFilter.ALL,
    /** Square box art or tall posters on game tiles. */
    val gameArt: io.github.matiyaaa.fuse.model.GameArtStyle = io.github.matiyaaa.fuse.model.GameArtStyle.BOX_ART,
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
    /** Plays Fuse's songs one after another in a random order instead of looping [track]. */
    val shuffle: Boolean = false,
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
