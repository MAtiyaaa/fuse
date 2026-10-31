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
    /** Fuse and Steam together: the integration's answers and parts (this device's own). */
    val steam: SteamIntegrationSettings = SteamIntegrationSettings(),
    /** What apps that talk to Fuse (the Decky plugin, Phone Link) may do here (this device's own). */
    val extensions: ExtensionSettings = ExtensionSettings(),
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
    val sync: SyncSettings = SyncSettings(),
    val syncthing: SyncthingSettings = SyncthingSettings(),
    val romm: FuseRommSettings = FuseRommSettings(),
    val downloads: DownloadSettings = DownloadSettings(),
    val streaming: StreamingSettings = StreamingSettings(),
    /** Fuse Ports on a computer (this device's own: its paths are this computer's). */
    val ports: PortsSettings = PortsSettings(),
) {
    companion object {
        const val CURRENT_VERSION = 3
    }
}

/**
 * Fuse Ports (Addons, Ports, on a computer): off until turned on in Settings, Addons, and nothing is
 * fetched before. The ports installed here, and the folders the person chose for each one's game
 * files (by port, then by the port's data id), kept when a port is removed so it comes back set up.
 * [copies]: the library game whose folder a port reads, when the person took it from the library
 * (by port). [playWith]: the library games Play starts with a port instead (by game id, the port).
 */
@Serializable
data class PortsSettings(
    val enabled: Boolean = false,
    val installed: Map<String, InstalledPort> = emptyMap(),
    val folders: Map<String, Map<String, String>> = emptyMap(),
    val copies: Map<String, Long> = emptyMap(),
    val playWith: Map<String, String> = emptyMap(),
    /** The AnyPS5 release Fuse has, for recompiling PlayStation 5 games; null before the first. */
    val anyPs5: String? = null,
    /**
     * Android: the app (package/activity) Play starts each game with, by game id: a recomp or port
     * attached to it ([io.github.matiyaaa.fuse.model.AppKind.PORT]), where [playWith] names ports in
     * Fuse's own folder.
     */
    val androidPlayWith: Map<String, String> = emptyMap(),
    /**
     * Android: when each game's app in [androidPlayWith] was chosen (epoch ms), by game id. Play from
     * outside a system starts what played the game last, unless the app was chosen since.
     */
    val androidPlayWithAt: Map<String, Long> = emptyMap(),
    /** Android: the package each port Fuse installed was, by port id, so it is the same app ever after. */
    val androidInstalled: Map<String, String> = emptyMap(),
)

/**
 * A port installed in Fuse's folder of ports: the list's entry and the build it was installed from
 * (so it starts as that version does, whatever a newer list says), and the links Fuse made in its
 * copy (relative to it), so a folder chosen again replaces exactly those.
 */
@Serializable
data class InstalledPort(
    val port: io.github.matiyaaa.fuse.model.Port,
    val build: io.github.matiyaaa.fuse.model.PortBuild,
    val links: List<String> = emptyList(),
    val installedAt: Long = 0,
)

/**
 * Fuse Sync by Fuse (Settings, Addons, Fuse Sync): off until set up. Whether this device hosts or
 * connects, how it reaches its host, what syncs, how much history is kept, which profile it opens
 * with, and this device's own Home when it keeps one. The device's secret is in the secret store,
 * never here.
 */
/**
 * Syncthing, for people who already run it (Fuse Sync is the one Fuse recommends): whether Fuse
 * uses it, where it answers, and how Fuse keeps saves in step with it. Its API key is kept in the
 * secret store, never here.
 */
@Serializable
data class SyncthingSettings(
    val enabled: Boolean = false,
    /** Where Syncthing's API answers ("127.0.0.1:8384", or with https:// on Android). */
    val address: String = "",
    /** Before a game starts, wait a moment for Syncthing to bring in the newest save. */
    val waitBeforePlaying: Boolean = true,
    /** Folders Fuse shares keep older versions of each save (Syncthing's staggered versioning). */
    val keepVersions: Boolean = true,
)

@Serializable
data class SyncSettings(
    val enabled: Boolean = false,
    /**
     * What moves this device's saves: one of [SaveProvider]'s names, or "" until someone chooses
     * (then it follows what is switched on, see [SaveProvider.of]). This device's own choice: never
     * synced, so one device can keep its saves to itself while the others sync.
     */
    val saveProvider: String = "",
    /** "CLIENT", "HOST", or "" while not set up. */
    val role: String = "",
    /** This device's id with its host: made once, kept for the life of the install. */
    val deviceId: String = "",
    /** What this device is called on the host ("Steam Deck"); empty uses the device's own name. */
    val deviceName: String = "",
    /** The host's name and id, as it said when this device connected. */
    val hostName: String = "",
    val hostId: String = "",
    val localAddress: String = "",
    val remoteAddress: String = "",
    /** [remoteAddress] came from the host (it is updated when the host's changes); false when typed here. */
    val remoteFromHost: Boolean = false,
    /** Prefer home, fall back to outside, and back again by itself. */
    val autoRoute: Boolean = true,
    val wifiOnly: Boolean = false,
    val mobileData: Boolean = false,
    val saves: Boolean = true,
    val states: Boolean = true,
    val records: Boolean = true,
    val settings: Boolean = true,
    /** Games themselves, off: they are large, and Cartridge or the person's own copies usually have them. */
    val gameFiles: Boolean = false,
    /** This device's games are listed for the household (Fuse Library), so others can see and fetch them. */
    val shareLibrary: Boolean = true,
    /** Other devices may send games here (they land where RomM's downloads do, or [receiveFolder]). */
    val acceptSends: Boolean = true,
    /** Where games sent here go: a library folder of the person's; empty uses each system's folder, as RomM does. */
    val receiveFolder: String = "",
    /** Games may pass through the host when this device and another can't reach each other. */
    val relay: Boolean = true,
    /** Other devices' games show in the RomM tab ("Not on RomM, from another device"). */
    val showInRomm: Boolean = true,
    val keepRecent: Int = 10,
    val keepDays: Int = 14,
    val keepWeeks: Int = 8,
    /** "LAST" (the last used profile), "ASK" (Who are you? every start) or "PROFILE" ([startupProfile]). */
    val startup: String = "LAST",
    val startupProfile: String = "",
    /** The profile in use on this device. */
    val activeProfile: String = "",
    /** Games the household plays as one save, as the host last said (kept here so it holds offline). */
    val sharedGames: List<String> = emptyList(),
    /**
     * Games whose saves stay on this device, by the household's id for them: each person's saves
     * are still kept apart and swapped here, but never sent to or brought from other devices.
     */
    val localOnlyGames: List<String> = emptyList(),
    /** Games this device takes the newest save of but never sends its own (it only receives). */
    val receiveOnlyGames: List<String> = emptyList(),
    /** Games whose saves the person syncs on none of their devices (travels with their profile's settings). */
    val offEverywhereGames: List<String> = emptyList(),
    /**
     * Save folders the person chose, by emulator id ("drastic" to its folder), for emulators that
     * save where you tell them or that Fuse can't find alone. Used by Fuse Sync and Syncthing alike.
     */
    val saveFolders: Map<String, String> = emptyMap(),
    /** Home on this device: "PROFILE" (the profile's, as on every device) or "DEVICE" (this device's own). */
    val homeScope: String = "PROFILE",
    /** This device's own Home, while [homeScope] is DEVICE; the profile's stays as it was. */
    val deviceHome: io.github.matiyaaa.fuse.model.HomeLayoutConfig? = null,
    /** The port a host serves on. */
    val hostPort: Int = 47311,
    /** Where the host keeps everyone's saves and profiles; empty is Fuse's own data folder. */
    val hostDataDir: String = "",
    /** This computer keeps a copy of the household and takes over when the host is gone for good (a joined computer). */
    val backupHost: Boolean = false,
    /** Where the host keeps whole copies of the household (every day, week and month); empty is Fuse's own folder. */
    val householdBackupDir: String = "",
    /**
     * The host runs only while Fuse is open: the person took its background service away. Otherwise
     * a host runs as a service of the system, so it keeps serving with Fuse closed, after a restart
     * or a power cut, and through the network coming and going.
     */
    val hostInFuseOnly: Boolean = false,
    /** Profiles whose saves this device never brings in (it still sends theirs). This device's own choice. */
    val noPullProfiles: List<String> = emptyList(),
    /** Profiles whose saves never move on this device, either way. This device's own choice. */
    val noSyncProfiles: List<String> = emptyList(),
    /** RomM and Jellyfin sign-ins go to and come from the household (sealed, never in plain text). */
    val shareSignIns: Boolean = true,
    /**
     * How [shareSignIns] was decided: "" not yet (a device that updated with Fuse Sync already set
     * up, asked once), "AUTO" (joined on this version, or the household already shares), "YES" or "NO".
     */
    val signInsChoice: String = "",
    /**
     * Keys (SteamGridDB, TheGamesDB, RetroAchievements and the rest, by their secret store names)
     * the person removed on this device: the household's copy never comes back here by itself.
     * Adding one here again takes it off this list.
     */
    val keysKeptOff: List<String> = emptyList(),
    /** Profiles that have had their welcome on this device: the first time someone plays here, they get the grand one. */
    val welcomedProfiles: List<String> = emptyList(),
    /** [welcomedProfiles] was started (with everyone already playing here on a device that updated). */
    val welcomedSeeded: Boolean = false,
)

/**
 * Jellyfin, an addon (Settings, Addons, Jellyfin): off until turned on. How Fuse reaches the
 * server, what it prefers to hear and read, and how Fuse Player behaves. The sign-in itself is in
 * the secret store, never here.
 */
@Serializable
data class JellyfinSettings(
    val enabled: Boolean = false,
    /** Where films and episodes kept offline go; empty uses Fuse's choice for this device. */
    val offlineFolder: String = "",
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
    /**
     * At an intro or a recap the server marks (Intro Skipper, chapter names): "ASK" (a button to
     * skip it), "AUTO" (skipped by itself) or "OFF".
     */
    val skipIntros: String = "ASK",
    /** The same at the credits and a preview after them; asking offers the next episode. */
    val skipCredits: String = "ASK",
    val seekSeconds: Int = 10,
    val controlsTimeoutSeconds: Int = 4,
    val rememberSpeed: Boolean = false,
    val speed: Float = 1f,
    /** The other screen while playing: "REMOTE" (controls and art) or "OFF". */
    val playerCompanion: String = "REMOTE",
    /**
     * On a device with two screens, where the picture goes: "ASK" (each time, the default), "MAIN"
     * (the main screen, the one above) or "SECOND" (the second screen, the touch screen below). The
     * other screen is its remote, and the menus stay free to browse.
     */
    val playOn: String = "ASK",
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
    /** The Store in Addons; off, it is gone from Addons and checks nothing. */
    val enabled: Boolean = true,
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
    /** Apps left out of Update All (each still updates from its own page), by key. */
    val excluded: Set<String> = emptySet(),
    /** Apps that follow their pre-releases (nightly, beta) instead of their stable releases, by key. */
    val prerelease: Set<String> = emptySet(),
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
    /** The earlier version an update replaced, kept to go back to (its program's path), and its version (before [earlier]). */
    val previous: String? = null,
    val previousVersion: String? = null,
    /** The earlier versions kept to go back to, newest first. */
    val earlier: List<KeptVersion> = emptyList(),
) {
    /** Every earlier version kept, newest first: [earlier], or the one a record from before it named. */
    val kept: List<KeptVersion>
        get() = earlier.ifEmpty { listOfNotNull(previous?.let { KeptVersion(it, previousVersion, installedAt) }) }
}

/** An earlier version of a program Fuse kept: where its program is now, which version it was, and when it was kept. */
@Serializable
data class KeptVersion(val path: String, val version: String? = null, val keptAt: Long = 0)

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
    /** What the person answered to "Do you use RomM?": "FUSE", "CARTRIDGE", "NONE", or "" when not asked. */
    val romm: String = "",
    /**
     * The step setup was on, by its id, so a restart in the middle (Steam's, Decky's or Fuse's own)
     * comes back to the same step instead of the beginning.
     */
    val stepId: String = "",
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
    /** The quick menu's items and widths ("WIFI:1"), in the user's order; empty is Fuse's own. */
    val quickMenu: List<String> = emptyList(),
    /**
     * The Systems page arranged by hand, like Home's board: each system's place and size, on pages.
     * Systems not on it yet join the first page; one taken off is kept, hidden. This device's own
     * (each screen sizes systems for itself); the systems' order follows the person.
     */
    val systemsBoard: HomeLayoutConfig = HomeLayoutConfig(),
    /**
     * The Systems page while the menus are on the lower screen (flipped): its own sizes and order,
     * this device's alone. Null until it is first arranged there; it starts from [systemsBoard]'s order.
     */
    val systemsBoardFlipped: HomeLayoutConfig? = null,
    /** How many more systems a row of the Systems page holds than at their usual size (0 to 2). */
    val systemTileStep: Int = 0,
    /** The same while the menus are on the lower screen. */
    val systemTileStepFlipped: Int = 0,
    /**
     * System cards across the Systems page at their usual size, as last shown with the menus on top
     * ("fuse") and below ("flipped"), so either can be arranged from the other screen.
     */
    val systemsCardsSeen: Map<String, Int> = emptyMap(),
    /** Home as it was before it was last reset here, for Undo Home Reset (this device's alone). */
    val beforeReset: HomeLayoutConfig? = null,
    /** That reset gave this device its own Home (the profile's stayed as it was on every other device). */
    val resetLeftProfile: Boolean = false,
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
    /** The colours states use, for how this person sees colour. */
    val colorVision: io.github.matiyaaa.fuse.model.ColorVision = io.github.matiyaaa.fuse.model.ColorVision.TYPICAL,
    /** Left to right, right to left, or as the system's language reads. */
    val layoutDirection: io.github.matiyaaa.fuse.model.LayoutDirectionChoice = io.github.matiyaaa.fuse.model.LayoutDirectionChoice.AUTOMATIC,
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
    /** The theme studio offers every colour and effect, not only its main choices. */
    val studioAdvanced: Boolean = false,
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
    /** Show how long a game takes to beat (HowLongToBeat) on its page. */
    val howLongToBeat: Boolean = true,
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
            ScrapeProviderId.GAMETDB,
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
    /**
     * The emulator this person starts each system's games in, their own whoever else plays here
     * (one picks Eden for Switch, another Ryujinx): by the kind of device and the system
     * ("android:switch") to the emulator, or empty for the device's automatic choice. A system they
     * never chose follows this device's choice.
     */
    val systemEmulators: Map<String, String> = emptyMap(),
    /** Drives Fuse asked about setting up for games (yes or no), by drive id, so it asks once. */
    val drivesAsked: List<String> = emptyList(),
    /**
     * Steam's games in the library here: "ON", "OFF" (no thanks, in setup's Steam step or Settings,
     * Library, Steam), or "" when never asked: then on a computer only when Steam games were added.
     */
    val steamGames: String = "",
    /** Manuals read on this device, by their file: the page each opens on and its bookmarks. */
    val manuals: Map<String, io.github.matiyaaa.fuse.model.ManualPlace> = emptyMap(),
    /** Fetch system logos and art from the system art pack when a system has none. */
    val systemArtAuto: Boolean = true,
    /** How the Library is sorted. */
    val sort: io.github.matiyaaa.fuse.model.SortOrder = io.github.matiyaaa.fuse.model.SortOrder.TITLE,
    /**
     * Artwork set used from the system art pack (a SystemArtStyle name), or "GAMES": the pack's
     * logos with a screenshot of one of each system's own games as the panel.
     */
    val systemArtStyle: String = "CLASSIC",
    /**
     * Systems whose panel the person picked from a game's screenshot, by platform id, to the
     * picture's model: drawn cut to the pack's panel like the automatic ones.
     */
    val systemPanels: Map<String, String> = emptyMap(),
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
    /** A system's page names a game from it to try today (its spotlight). */
    val systemSpotlight: Boolean = true,
    /**
     * Continue where you left off: the emulators that can are asked to keep a resume state as a
     * game is left (PCSX2's Save State On Shutdown, RetroArch's automatic state, which DuckStation
     * keeps by itself), and a game's page offers Continue from it beside Start Fresh.
     */
    val keepPlace: Boolean = true,
    /**
     * The same hotkeys in every emulator (core:launch's `Hotkeys`):
     * written into the emulators that can take them as a game starts, beside the person's own
     * bindings; turned off, Fuse's are taken out again.
     */
    val gameHotkeys: Boolean = false,
    /** Series the user hid or kept as their own collection, lower case, so Fuse doesn't make them again. */
    val hiddenSeries: List<String> = emptyList(),
    /** Brand colours from the system art pack (opaque ARGB), by platform id. */
    val systemColors: Map<String, Long> = emptyMap(),
    /** How each system's small tile looks where the person changed it, by platform id. */
    val systemTiles: Map<String, io.github.matiyaaa.fuse.model.SystemTileLook> = emptyMap(),
    /** Systems the person put back to Fuse's own art: nothing is downloaded for them by itself. */
    val systemArtDefault: List<String> = emptyList(),
    /** Which of its lists the Apps tab opens on. */
    val appsFilter: io.github.matiyaaa.fuse.model.AppFilter = io.github.matiyaaa.fuse.model.AppFilter.ALL,
    /** Square box art or tall posters on game tiles. */
    val gameArt: io.github.matiyaaa.fuse.model.GameArtStyle = io.github.matiyaaa.fuse.model.GameArtStyle.BOX_ART,
    /**
     * Removed games the person removed for good, by game id: gone from the Removed view, their art
     * deleted, and still remembered as removed so a scan never brings them back. Files are never touched.
     */
    val purgedGames: List<Long> = emptyList(),
    /** Which view the Library opens on: "INSTALLED" (games on this device) or "ALL" (everywhere they are). */
    val opensOn: String = "ALL",
    /** The smart filters pinned among the Library's tabs ("UNPLAYED", "TOGETHER"...), by name. */
    val pinnedFilters: List<String> = emptyList(),
    /** Emulator settings a person saved to give their games at once; theirs on every device. */
    val settingProfiles: List<OwnSettingProfile> = emptyList(),
)

/**
 * A person's own set of an emulator's settings ([family]: `pcsx2`, `duckstation`, `ppsspp`), by
 * Fuse's id for each setting to the value the emulator stores.
 */
@Serializable
data class OwnSettingProfile(val id: String, val family: String, val name: String, val values: Map<String, String>)

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
    /** Every sound Fuse makes is off (music, interface sounds, startup), whatever each one's own switch says. */
    val muted: Boolean = false,
    /** 0..1: scales every sound Fuse makes, under each one's own volume. */
    val master: Float = 1f,
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
    /** Plays Fuse's songs one after another in a random order instead of looping [track] (the default). */
    val shuffle: Boolean = true,
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

/**
 * RomM, the RomM native integration (Settings, Addons, RomM): off until set up. How
 * Fuse reaches the RomM server, where its games land and how transfers to it behave. The sign-in
 * itself is in the secret store, never here. Turning it off keeps all of this, so turning it on again
 * reconnects without setting it up again.
 */
@Serializable
data class FuseRommSettings(
    val enabled: Boolean = false,
    /** True once the server was reached and signed in to at least once. */
    val configured: Boolean = false,
    /** "AUTO" (recommended), "LOCAL" or "REMOTE". */
    val mode: String = "AUTO",
    val localAddress: String = "",
    val remoteAddress: String = "",
    /**
     * The key everything kept for this server goes under in Fuse (its mirror, the changes and saves
     * waiting for it); a different server gets its own (`RommServers`).
     */
    val server: String = "main",
    /** Each address Fuse was set up with, normalised, to the key of the server it was ([server]). */
    val servers: Map<String, String> = emptyMap(),
    /** Who Fuse is signed in as, to show (never a password or token). */
    val account: String = "",
    /** The RomM version last seen, to show. */
    val serverVersion: String = "",
    /** The library folder new systems' folders are made in; empty asks the first time. */
    val libraryRoot: String = "",
    /** The folder for a system's games, by Fuse's platform id, when the person chose one. */
    val systemFolders: Map<String, String> = emptyMap(),
    /** Look at a downloaded game's folder at once, so it is playable without a full scan. */
    val scanAfterDownload: Boolean = true,
    /** What new games on the server do: "BADGE" (marked new in RomM), "NOTIFY" (and a message), "QUIET". */
    val newGames: String = "BADGE",
    /** Keep the library mirrored for browsing while the server is away. */
    val offlineMirror: Boolean = true,
    /** How often the mirror catches up while Fuse is open, in minutes. */
    val refreshMinutes: Int = 30,
    /** Show what will be sent before an upload starts. */
    val confirmUploads: Boolean = true,
    /** Ask RomM to scan after an upload, when the sign-in may. */
    val scanAfterUpload: Boolean = true,
    /** Where BIOS files from RomM go when an emulator has no folder of its own; empty is Fuse's BIOS folder. */
    val biosFolder: String = "",
    /** Put downloaded BIOS where each emulator reads it. */
    val placeBios: Boolean = true,
    /** Keep games' saves in step with RomM's save sync (games from RomM, or matched to it). */
    val saveSync: Boolean = false,
    /**
     * Send the person's changes to games from RomM back to it: favourites (RomM's favourites
     * collection), games hidden or shown, and play sessions. Off until turned on.
     */
    val sendChanges: Boolean = false,
)

/**
 * Downloads (Settings, Downloads): how Fuse's transfers behave, for every source together. Up to five
 * downloads and five uploads at once, each counted apart. [chosen] stays false until the person
 * changes the counts, so a sensible number for the device is used meanwhile.
 */
@Serializable
data class DownloadSettings(
    val maxDownloads: Int = 3,
    val maxUploads: Int = 2,
    val chosen: Boolean = false,
    /** Kilobytes a second for all transfers together; 0 for no limit. */
    val bandwidthKbps: Int = 0,
    /**
     * "FULL" or "PAUSE" while a game is being played, the same for both since 0.4.4 (one switch,
     * [transfersWhilePlaying]); "REDUCED", the default before, reads as FULL.
     */
    val downloadsWhilePlaying: String = "FULL",
    val uploadsWhilePlaying: String = "FULL",
    val wifiOnly: Boolean = false,
    /** Transfers move only while plugged in. */
    val chargingOnly: Boolean = false,
    /** On battery, transfers wait below this percent; 0 for never. */
    val minBattery: Int = 0,
    /** Quiet hours as minutes since midnight, from and to; equal for none. */
    val quietFrom: Int = 0,
    val quietTo: Int = 0,
    val resumeOnStart: Boolean = true,
    /** Days finished transfers stay listed. */
    val keepFinishedDays: Int = 7,
) {
    /**
     * Downloads and uploads carry on while a game is being played (on, the default), or wait until
     * it closes: one switch for both. A pause chosen before for either one reads as off.
     */
    val transfersWhilePlaying: Boolean get() = downloadsWhilePlaying != "PAUSE" && uploadsWhilePlaying != "PAUSE"

    /** These settings with transfers carrying on while playing, or not. */
    fun withTransfersWhilePlaying(on: Boolean): DownloadSettings = (if (on) "FULL" else "PAUSE").let { copy(downloadsWhilePlaying = it, uploadsWhilePlaying = it) }
}

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
/** Which releases Fuse updates to: published ones, pre-releases too (Beta), or the nightly one as well. */
enum class UpdateChannel { STABLE, PRERELEASE, NIGHTLY }

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

/**
 * Streaming games from a computer at home with Moonlight: the computers running Sunshine (or
 * Apollo, or GeForce Experience), how to wake them, and the apps on each.
 */
@Serializable
data class StreamingSettings(
    val enabled: Boolean = false,
    val hosts: List<StreamHostSettings> = emptyList(),
    /** How long to wait for a computer to wake before saying it didn't, in seconds. */
    val wakeWaitSeconds: Int = 75,
    /** Which Moonlight to start when several are installed (an Android package, or a program path); empty picks one. */
    val client: String = "",
    /**
     * Games played by streaming them rather than here, Moonlight as their emulator: each game (by its
     * id on this device) to the computer and the app there. A game's own choice wins over its system's.
     */
    val games: Map<String, StreamPick> = emptyMap(),
    /** Systems whose games stream from a computer (by its id) unless a game chooses otherwise. */
    val systems: Map<String, String> = emptyMap(),
    /** Moonlight as a system of its own on the Systems page (never by default), opening the computers' apps. */
    val onSystems: Boolean = false,
)

/** A game streamed from [host] (a [StreamHostSettings.id]) as [app] there; empty: the app named as the game. */
@Serializable
data class StreamPick(val host: String, val app: String = "")

/** One computer to stream from. [uniqueId] and [mac] are filled in from what it says about itself. */
@Serializable
data class StreamHostSettings(
    val id: String,
    val name: String,
    val address: String,
    val uniqueId: String = "",
    /** Its network card's address, for waking it (Wake-on-LAN). */
    val mac: String = "",
    val wake: Boolean = true,
    /** The apps to offer on it, as Sunshine names them. */
    val apps: List<String> = listOf("Desktop", "Steam Big Picture"),
)


/**
 * How Fuse works with Steam on this device. [integrate] is the answer to setup's first Steam
 * question ("YES", "NO", or "" when never asked); [decky] runs the Fuse Decky Bridge the Fuse
 * Decky plugin talks to (Linux); [fuseInSteam] remembers that Fuse was added to Steam's library.
 * None of these are a person's: they belong to the device and its Steam install.
 */
@Serializable
data class SteamIntegrationSettings(
    val integrate: String = "",
    val decky: Boolean = false,
    /**
     * The person switched the bridge on or off themselves. Until then, a Fuse plugin found installed
     * (from Decky's store, or a zip) switches it on, so the plugin never sits there saying Fuse isn't running.
     */
    val deckyChosen: Boolean = false,
    val fuseInSteam: Boolean = false,
    /**
     * The Steam account (its 64-bit SteamID) whose achievements Fuse shows on this device; empty
     * follows the account Steam remembers as most recent. Never switched by switching Fuse profiles.
     */
    val achievementsAccount: String = "",
    /**
     * Fuse's games go into Steam as Direct entries, which start their emulator with nothing of
     * Fuse's in between, instead of with Fuse Fast Start (the default). Only where Steam can have
     * them (Linux); a game that can't start on its own still goes in with Fast Start.
     */
    val directEntries: Boolean = false,
    /**
     * A Direct entry for a system starts the way the person's own shortcuts start that system's
     * games, where Fuse learned it from them (their program, start folder and launch options, as
     * they write them). On by default; off, every Direct entry starts as Play would.
     */
    val asTheirShortcuts: Boolean = true,
    /**
     * Keep Steam in Step: games that join Fuse's library go into Steam by themselves, and Fuse's
     * entries for games that leave it come out. Off until the person turns it on.
     */
    val automatic: Boolean = false,
    /** Keep Steam in Step was offered once, after the first game the person added to Steam. */
    val automaticOffered: Boolean = false,
    /** How a game's entry starts when the person chose it for that game: true Direct, false Fast Start. */
    val startOverrides: Map<Long, Boolean> = emptyMap(),
)
