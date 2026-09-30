package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.DestinationSetting
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs

/** Global values of scoped keys that the interface shows as plain preferences. */
internal data class GlobalScoped(
    val layout: LibraryLayout = LibraryLayout.ICON,
    val showHero: Boolean = true,
    val showLogo: Boolean = true,
)

/** Region choices the settings screen offers; stored null means "any region". */
private const val ANY_REGION = "any"

internal fun AppSettings.toUiPrefs(scoped: GlobalScoped): UiPrefs {
    val theme = ThemePresets.byId(appearance.themeId)
    return UiPrefs(
        onboardingDone = onboarding.completed,
        themeId = theme.id,
        motion = appearance.motion,
        glass = appearance.glass ?: theme.glass,
        crt = appearance.crt ?: theme.crt,
        highContrastFocus = appearance.highContrastFocus,
        home = home.layout,
        destinations = home.visibleDestinations(),
        defaultLayout = scoped.layout,
        showHero = scoped.showHero,
        showLogo = scoped.showLogo,
        videoPreview = videoPreview.enabled,
        videoDelaySeconds = videoPreview.delaySeconds,
        input = input,
        display = display,
        performance = performance.profile,
        lowPower = performance.lowPowerMode,
        performanceOverlay = performance.overlay,
        sound = if (!sound.enabled) SoundProfile.OFF else sound.profile ?: theme.sound,
        soundVolume = sound.volume,
        clock24h = statusArea.use24HourClock ?: false,
        showWifi = statusArea.showWifi,
        showBluetooth = statusArea.showBluetooth,
        cleanDisplayNames = library.cleanDisplayNames,
        scraperOrder = scraping.providerOrder,
        scraperLanguage = scraping.preferredLanguage,
        scraperRegion = scraping.preferredRegion ?: ANY_REGION,
        matching = scraping.matching,
        autoRefreshFromCartridge = cartridge.autoRefreshOnReturn,
        checkForUpdates = updates.checkForUpdates,
        heroDim = appearance.heroDim,
    )
}

/**
 * Writes [prefs] into [base]. Values equal to the theme's own (glass, CRT, sound profile) are stored
 * as "follow the theme", so switching themes later still changes them.
 */
internal fun AppSettings.withUiPrefs(prefs: UiPrefs): AppSettings {
    val theme = ThemePresets.byId(prefs.themeId)
    val visible = prefs.destinations.distinct()
    val destinations = visible.map { DestinationSetting(it, visible = true) } +
        Destination.entries.filterNot { it in visible }.map { DestinationSetting(it, visible = false) }
    return copy(
        onboarding = onboarding.copy(completed = prefs.onboardingDone),
        home = home.copy(layout = prefs.home, destinations = destinations),
        appearance = appearance.copy(
            themeId = theme.id,
            motion = prefs.motion,
            glass = prefs.glass.takeIf { it != theme.glass },
            crt = prefs.crt.takeIf { it != theme.crt },
            heroDim = prefs.heroDim.coerceIn(0f, 0.9f),
            highContrastFocus = prefs.highContrastFocus,
        ),
        performance = performance.copy(profile = prefs.performance, lowPowerMode = prefs.lowPower, overlay = prefs.performanceOverlay),
        input = prefs.input,
        display = prefs.display,
        scraping = scraping.copy(
            providerOrder = prefs.scraperOrder,
            preferredLanguage = prefs.scraperLanguage,
            preferredRegion = prefs.scraperRegion.takeIf { it != ANY_REGION && it.isNotBlank() },
            matching = prefs.matching,
        ),
        videoPreview = videoPreview.copy(enabled = prefs.videoPreview, delaySeconds = prefs.videoDelaySeconds.coerceIn(0, 60)),
        library = library.copy(cleanDisplayNames = prefs.cleanDisplayNames),
        sound = sound.copy(
            enabled = prefs.sound != SoundProfile.OFF,
            volume = prefs.soundVolume.coerceIn(0f, 1f),
            profile = prefs.sound.takeIf { it != SoundProfile.OFF && it != theme.sound },
        ),
        statusArea = statusArea.copy(
            use24HourClock = prefs.clock24h,
            showWifi = prefs.showWifi,
            showBluetooth = prefs.showBluetooth,
        ),
        cartridge = cartridge.copy(autoRefreshOnReturn = prefs.autoRefreshFromCartridge),
        updates = updates.copy(checkForUpdates = prefs.checkForUpdates),
    )
}
