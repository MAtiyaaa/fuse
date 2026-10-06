package io.github.matiyaaa.fuse.ui.shell.store

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.model.CrtSettings
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.DisplayProfile
import io.github.matiyaaa.fuse.model.GlassSettings
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.MatchStrictness
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.model.ThemeSpec
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets

/**
 * The interface's view of the user's settings. The store maps it to and from persistent settings,
 * so screens never depend on how settings are stored.
 */
@Immutable
data class UiPrefs(
    /** Jellyfin, an addon: off until turned on in Settings, Addons, Jellyfin. */
    val jellyfin: io.github.matiyaaa.fuse.data.settings.JellyfinSettings = io.github.matiyaaa.fuse.data.settings.JellyfinSettings(),
    /** Syncthing, an addon. Whether it is on follows [FuseStore.syncthing]'s state; only how it behaves changes here. */
    val syncthing: io.github.matiyaaa.fuse.data.settings.SyncthingSettings = io.github.matiyaaa.fuse.data.settings.SyncthingSettings(),
    /** Fuse RomM, the Fuse RomM native integration. Its sign-in is in the secret store. */
    val romm: io.github.matiyaaa.fuse.data.settings.FuseRommSettings = io.github.matiyaaa.fuse.data.settings.FuseRommSettings(),
    /** Streaming from a computer at home with Moonlight. */
    val streaming: io.github.matiyaaa.fuse.data.settings.StreamingSettings = io.github.matiyaaa.fuse.data.settings.StreamingSettings(),
    /** How every transfer (Downloads) behaves. */
    val downloads: io.github.matiyaaa.fuse.data.settings.DownloadSettings = io.github.matiyaaa.fuse.data.settings.DownloadSettings(),
    /**
     * Fuse Sync's settings, read only: changed through [FuseStore.sync], never through
     * [FuseStore.updatePrefs]. Here so every screen follows whether it is on.
     */
    val sync: io.github.matiyaaa.fuse.data.settings.SyncSettings = io.github.matiyaaa.fuse.data.settings.SyncSettings(),
    val onboardingDone: Boolean = false,
    /** The answer to setup's "Do you use RomM?": "FUSE", "CARTRIDGE", "NONE", or "" when not asked yet. */
    val rommAnswer: String = "",
    val themeId: String = "fuse",
    val motion: MotionProfile? = null,
    val glass: GlassSettings = GlassSettings(),
    val crt: CrtSettings = CrtSettings(),
    val highContrastFocus: Boolean = false,
    /** Text size: 1 as designed, 1.15 (Large) or 1.3 (Extra large). */
    val textScale: Float = 1f,
    /** Room kept clear at every edge, in percent, for TVs that cut the picture's edges off. */
    val screenMargin: Int = 0,
    val home: HomeLayoutConfig = HomeLayoutConfig(),
    val destinations: List<Destination> = Destination.entries,
    val defaultLayout: LibraryLayout = LibraryLayout.ICON,
    /** Square box art or tall posters on game tiles. */
    val gameArt: io.github.matiyaaa.fuse.model.GameArtStyle = io.github.matiyaaa.fuse.model.GameArtStyle.BOX_ART,
    val showHero: Boolean = true,
    val showLogo: Boolean = true,
    val videoPreview: Boolean = true,
    val videoDelaySeconds: Int = 10,
    val input: InputProfile = InputProfile(),
    val display: DisplayProfile = DisplayProfile(),
    val performance: PerformanceProfile = PerformanceProfile.AUTOMATIC,
    val lowPower: Boolean = false,
    val performanceOverlay: Boolean = false,
    val sound: SoundProfile = SoundProfile.SOFT,
    val soundVolume: Float = 0.6f,
    val music: MusicPrefs = MusicPrefs(),
    val clock24h: Boolean = false,
    val showWifi: Boolean = true,
    val showBluetooth: Boolean = false,
    val cleanDisplayNames: Boolean = true,
    /** Systems in the user's order (platform ids); the rest follow in catalog order. */
    val systemOrder: List<String> = emptyList(),
    /** Games taken off Continue Playing (game id to when), until they are played again. */
    val continueDismissed: Map<String, Long> = emptyMap(),
    /** Addons' tabs in the user's order (part names); parts not listed follow in their usual order. */
    val addonsOrder: List<String> = emptyList(),
    /** The quick menu's items and widths, as [io.github.matiyaaa.fuse.ui.shell.quick.QuickLayout] stores them; empty is Fuse's own. */
    val quickMenu: List<String> = emptyList(),
    /** Home was reset here and can go back as it was (Undo Home Reset). */
    val canUndoHomeReset: Boolean = false,
    val systemArtAuto: Boolean = true,
    /** How the Library is sorted. */
    val librarySort: io.github.matiyaaa.fuse.model.SortOrder = io.github.matiyaaa.fuse.model.SortOrder.TITLE,
    val systemArtStyle: String = "CLASSIC",
    /** Confirm (or a tap on a selected tile) opens a game's page instead of playing it. */
    val openGamePage: Boolean = false,
    /** Phone Link: a phone on the same network can see and fix the library (see docs/PHONE_LINK.md). */
    val phoneLinkEnabled: Boolean = false,
    /** Phone Link: a signed-in phone can be used as a controller, and type into fields. */
    val phoneLinkController: Boolean = true,
    /** Collections as a whole; off hides every collection feature. */
    val collectionsEnabled: Boolean = true,
    /** Automatic series collections. */
    val autoSeries: Boolean = true,
    /** Series Fuse no longer makes (hidden, or kept as the user's own), lower case. */
    val hiddenSeries: List<String> = emptyList(),
    /** Systems whose firmware the user marked as set up, by platform id. */
    val biosConfirmed: List<String> = emptyList(),
    /** Drives Fuse already asked about setting up for games, by drive id. */
    val drivesAsked: List<String> = emptyList(),
    val scraperOrder: List<ScrapeProviderId> = io.github.matiyaaa.fuse.data.settings.ScrapingSettings.DefaultProviderOrder,
    val scraperLanguage: String = "en",
    val scraperRegion: String = "any",
    val matching: MatchStrictness = MatchStrictness.NORMAL,
    /** Missing art and details are looked for by themselves after scans. */
    val autoFillArt: Boolean = true,
    val autoRefreshFromCartridge: Boolean = true,
    /** Cartridge support as a whole; off hides every Cartridge feature. */
    val cartridgeEnabled: Boolean = true,
    /** RomM's details and pictures for games Cartridge downloaded. */
    val cartridgeRommDetails: Boolean = true,
    val checkForUpdates: Boolean = true,
    /** L3 + R3 takes a screenshot, and held, records (where Fuse can capture its screen). */
    val captureCombo: Boolean = true,
    /** Recordings include Fuse's own sound. */
    val captureSound: Boolean = true,
    val heroDim: Float = 0.3f,
    /** Fuse's mark lights up when Fuse starts ([io.github.matiyaaa.fuse.ui.shell.app.StartupIntroOverlay]). */
    val startupAnimation: Boolean = true,
    /** Colours picked last in the theme studio, newest first. */
    val recentColors: List<Long> = emptyList(),
    /** Tabs keep where you were in them; off, each tab opens at its start. */
    val rememberPlace: Boolean = true,
    /** Minutes idle before the standby screen; 0 never. */
    val standbyMinutes: Int = 5,
    /** Which of its lists the Apps tab opens on: Pinned, Emulators or All apps. */
    val appsFilter: io.github.matiyaaa.fuse.model.AppFilter = io.github.matiyaaa.fuse.model.AppFilter.ALL,
    /** Themes added from a link, a file or pasted text, ready to use. */
    val customThemes: List<ThemeSpec> = emptyList(),
    /** The Store's edition of the Obtainium Emulation Pack; null until chosen (Android). */
    val storeVariant: io.github.matiyaaa.fuse.model.StoreVariant? = null,
    /** The Store checks installed apps for updates by itself. */
    val storeAutoCheck: Boolean = true,
    /** The Store in Addons (Settings, Addons, Store); off, Addons has no Store and nothing is checked. */
    val storeEnabled: Boolean = true,
) {
    /** The theme in use: a built-in one, else an added one, else Fuse (an added theme was removed). */
    val theme: ThemeSpec
        get() = ThemePresets.find(themeId) ?: customThemes.firstOrNull { it.id == themeId } ?: ThemePresets.Fuse

    /**
     * Switches to [next]. Glass, CRT and sounds that still match the current theme's own follow
     * the new theme's; ones the user changed are kept.
     */
    fun withTheme(next: ThemeSpec): UiPrefs {
        val old = theme
        return copy(
            themeId = next.id,
            glass = if (glass == old.glass) next.glass else glass,
            crt = if (crt == old.crt) next.crt else crt,
            sound = if (sound == old.sound) next.sound else sound,
        )
    }
}

/**
 * Menu music: on or off, how loud, and which song. [track] is a bundled song's id
 * ([io.github.matiyaaa.fuse.ui.shell.music.BundledMusic]) or `"file"` for the user's own song at
 * [songPath].
 */
data class MusicPrefs(
    val enabled: Boolean = true,
    val volume: Float = 0.2f,
    val songPath: String? = null,
    val songName: String? = null,
    val track: String = io.github.matiyaaa.fuse.ui.shell.music.BundledMusic.MENU_DEFAULT,
    /** Every bundled song in a random order, a new one each time a song ends, instead of looping [track] (the default). */
    val shuffle: Boolean = true,
)
