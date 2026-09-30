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

/**
 * The interface's view of the user's settings. The store maps it to and from persistent settings,
 * so screens never depend on how settings are stored.
 */
@Immutable
data class UiPrefs(
    val onboardingDone: Boolean = false,
    val themeId: String = "fuse",
    val motion: MotionProfile? = null,
    val glass: GlassSettings = GlassSettings(),
    val crt: CrtSettings = CrtSettings(),
    val highContrastFocus: Boolean = false,
    val home: HomeLayoutConfig = HomeLayoutConfig(),
    val destinations: List<Destination> = Destination.entries,
    val defaultLayout: LibraryLayout = LibraryLayout.ICON,
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
    val clock24h: Boolean = false,
    val showWifi: Boolean = true,
    val showBluetooth: Boolean = false,
    val cleanDisplayNames: Boolean = true,
    /** Systems in the user's order (platform ids); the rest follow in catalog order. */
    val systemOrder: List<String> = emptyList(),
    /** Games taken off Continue Playing (game id to when), until they are played again. */
    val continueDismissed: Map<String, Long> = emptyMap(),
    val systemArtAuto: Boolean = true,
    /** How the Library is sorted. */
    val librarySort: io.github.matiyaaa.fuse.model.SortOrder = io.github.matiyaaa.fuse.model.SortOrder.TITLE,
    val systemArtStyle: String = "CLASSIC",
    val scraperOrder: List<ScrapeProviderId> = io.github.matiyaaa.fuse.data.settings.ScrapingSettings.DefaultProviderOrder,
    val scraperLanguage: String = "en",
    val scraperRegion: String = "any",
    val matching: MatchStrictness = MatchStrictness.NORMAL,
    val autoRefreshFromCartridge: Boolean = true,
    val checkForUpdates: Boolean = true,
    val heroDim: Float = 0.3f,
)
