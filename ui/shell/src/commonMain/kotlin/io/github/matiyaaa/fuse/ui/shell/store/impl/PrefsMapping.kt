package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.DestinationSetting
import io.github.matiyaaa.fuse.data.settings.StoredTheme
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.model.ThemeCodec
import io.github.matiyaaa.fuse.model.ThemeSpec
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.shell.music.BundledMusic
import io.github.matiyaaa.fuse.ui.shell.store.MusicPrefs
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs

/** Global values of scoped keys that the interface shows as plain preferences. */
internal data class GlobalScoped(
    val layout: LibraryLayout = LibraryLayout.ICON,
    val showHero: Boolean = true,
    val showLogo: Boolean = true,
)

/** Region choices the settings screen offers; stored null means "any region". */
private const val ANY_REGION = "any"

/** An added theme, ready to use; null if its file no longer reads as a theme. */
internal fun StoredTheme.spec(): ThemeSpec? =
    (ThemeCodec.parse(json, ThemePresets::find, ThemePresets.Fuse) as? ThemeCodec.Imported)?.spec?.copy(id = id)

/** The theme with [id]: built in, else added, else Fuse. */
internal fun resolveTheme(id: String?, custom: List<ThemeSpec>): ThemeSpec =
    ThemePresets.find(id) ?: custom.firstOrNull { it.id == id } ?: ThemePresets.Fuse

internal fun AppSettings.toUiPrefs(scoped: GlobalScoped): UiPrefs {
    val custom = appearance.customThemes.mapNotNull { it.spec() }
    val theme = resolveTheme(appearance.themeId, custom)
    return UiPrefs(
        jellyfin = jellyfin,
        syncthing = syncthing,
        sync = sync,
        onboardingDone = onboarding.completed,
        rommAnswer = onboarding.romm,
        onboardingStep = onboarding.stepId,
        themeId = theme.id,
        customThemes = custom,
        motion = appearance.motion,
        glass = appearance.glass ?: theme.glass,
        crt = appearance.crt ?: theme.crt,
        highContrastFocus = appearance.highContrastFocus,
        colorVision = appearance.colorVision,
        layoutDirection = appearance.layoutDirection,
        textScale = appearance.textScale.coerceIn(1f, 1.5f),
        screenMargin = appearance.screenMargin.coerceIn(0, 10),
        home = home.layout,
        unlocked = appearance.unlocked.toSet(),
        unlockedNew = appearance.unlockedNew.toSet(),
        systemsBoard = home.systemsBoard,
        systemsBoardFlipped = home.systemsBoardFlipped,
        systemTileStep = home.systemTileStep.coerceIn(0, 2),
        systemTileStepFlipped = home.systemTileStepFlipped.coerceIn(0, 2),
        systemsCardsSeen = home.systemsCardsSeen,
        systemTiles = library.systemTiles,
        destinations = home.visibleDestinations(),
        defaultLayout = scoped.layout,
        gameArt = library.gameArt,
        libraryOpensOn = library.opensOn,
        steam = steam,
        extensions = extensions,
        showHero = scoped.showHero,
        showLogo = scoped.showLogo,
        videoPreview = videoPreview.enabled,
        videoDelaySeconds = videoPreview.delaySeconds,
        input = input.migrated(),
        display = display,
        performance = performance.profile,
        lowPower = performance.lowPowerMode,
        performanceOverlay = performance.overlay,
        sound = if (!sound.enabled) SoundProfile.OFF else sound.profile ?: theme.sound,
        soundVolume = sound.volume,
        muted = sound.muted,
        masterVolume = sound.master,
        music = MusicPrefs(
            music.enabled, music.volume, music.songPath, music.songName,
            track = music.track ?: if (music.songPath != null) BundledMusic.OWN_SONG else BundledMusic.MENU_DEFAULT,
            shuffle = music.shuffle,
        ),
        clock24h = statusArea.use24HourClock ?: false,
        showWifi = statusArea.showWifi,
        showBluetooth = statusArea.showBluetooth,
        cleanDisplayNames = library.cleanDisplayNames,
        systemOrder = library.systemOrder,
        continueDismissed = home.continueDismissed,
        addonsOrder = home.addonsOrder,
        quickMenu = home.quickMenu,
        canUndoHomeReset = home.beforeReset != null,
        systemArtAuto = library.systemArtAuto,
        librarySort = library.sort,
        systemArtStyle = library.systemArtStyle,
        openGamePage = library.selectOpensGamePage,
        collectionsEnabled = library.collectionsEnabled,
        phoneLinkEnabled = library.phoneLinkEnabled,
        phoneLinkController = library.phoneLinkController,
        autoSeries = library.autoSeries,
        systemSpotlight = library.systemSpotlight,
        hiddenSeries = library.hiddenSeries,
        biosConfirmed = library.biosConfirmed,
        drivesAsked = library.drivesAsked,
        steamGames = library.steamGames,
        manuals = library.manuals,
        scraperOrder = scraping.providerOrder,
        scraperLanguage = scraping.preferredLanguage,
        scraperRegion = scraping.preferredRegion ?: ANY_REGION,
        matching = scraping.matching,
        autoFillArt = scraping.autoFill,
        howLongToBeat = scraping.howLongToBeat,
        autoRefreshFromCartridge = cartridge.autoRefreshOnReturn,
        cartridgeRommDetails = cartridge.rommDetails,
        cartridgeEnabled = cartridge.enabled,
        checkForUpdates = updates.checkForUpdates,
        captureCombo = capture.combo,
        captureSound = capture.sound,
        heroDim = appearance.heroDim,
        startupAnimation = appearance.startupAnimation,
        recentColors = appearance.recentColors,
        themeStudioAdvanced = appearance.studioAdvanced,
        rememberPlace = appearance.rememberPlace,
        standbyMinutes = appearance.standbyMinutes,
        appsFilter = library.appsFilter,
        storeVariant = store.variant,
        storeAutoCheck = store.autoCheck,
        storeEnabled = store.enabled,
        romm = romm,
        streaming = streaming,
        downloads = downloads,
    )
}

/**
 * Writes [prefs] into [base]. Values equal to the theme's own (glass, CRT, sound profile) are stored
 * as "follow the theme", so switching themes later still changes them.
 */
internal fun AppSettings.withUiPrefs(prefs: UiPrefs): AppSettings {
    val theme = resolveTheme(prefs.themeId, prefs.customThemes)
    val visible = prefs.destinations.distinct()
    val destinations = visible.map { DestinationSetting(it, visible = true) } +
        Destination.entries.filterNot { it in visible }.map { DestinationSetting(it, visible = false) }
    return copy(
        onboarding = onboarding.copy(completed = prefs.onboardingDone, romm = prefs.rommAnswer, stepId = prefs.onboardingStep),
        home = home.copy(layout = prefs.home, systemsBoard = prefs.systemsBoard, systemsBoardFlipped = prefs.systemsBoardFlipped, systemTileStep = prefs.systemTileStep, systemTileStepFlipped = prefs.systemTileStepFlipped, systemsCardsSeen = prefs.systemsCardsSeen, destinations = destinations, continueDismissed = prefs.continueDismissed, addonsOrder = prefs.addonsOrder, quickMenu = prefs.quickMenu),
        appearance = appearance.copy(
            themeId = theme.id,
            unlocked = prefs.unlocked.sorted(),
            unlockedNew = prefs.unlockedNew.filter { it in prefs.unlocked }.sorted(),
            motion = prefs.motion,
            glass = prefs.glass.takeIf { it != theme.glass },
            crt = prefs.crt.takeIf { it != theme.crt },
            heroDim = prefs.heroDim.coerceIn(0f, 0.9f),
            highContrastFocus = prefs.highContrastFocus,
            colorVision = prefs.colorVision,
            layoutDirection = prefs.layoutDirection,
            textScale = prefs.textScale,
            screenMargin = prefs.screenMargin,
            startupAnimation = prefs.startupAnimation,
            recentColors = prefs.recentColors.distinct().take(RECENT_COLORS),
            studioAdvanced = prefs.themeStudioAdvanced,
            rememberPlace = prefs.rememberPlace,
            standbyMinutes = prefs.standbyMinutes.coerceIn(0, 120),
        ),
        performance = performance.copy(profile = prefs.performance, lowPowerMode = prefs.lowPower, overlay = prefs.performanceOverlay),
        input = prefs.input,
        display = prefs.display,
        scraping = scraping.copy(
            providerOrder = prefs.scraperOrder,
            preferredLanguage = prefs.scraperLanguage,
            preferredRegion = prefs.scraperRegion.takeIf { it != ANY_REGION && it.isNotBlank() },
            matching = prefs.matching,
            autoFill = prefs.autoFillArt,
            howLongToBeat = prefs.howLongToBeat,
        ),
        videoPreview = videoPreview.copy(enabled = prefs.videoPreview, delaySeconds = prefs.videoDelaySeconds.coerceIn(0, 60)),
        library = library.copy(
            cleanDisplayNames = prefs.cleanDisplayNames,
            systemOrder = prefs.systemOrder,
            systemArtAuto = prefs.systemArtAuto,
            sort = prefs.librarySort,
            systemArtStyle = prefs.systemArtStyle,
            selectOpensGamePage = prefs.openGamePage,
            collectionsEnabled = prefs.collectionsEnabled,
            phoneLinkEnabled = prefs.phoneLinkEnabled,
            phoneLinkController = prefs.phoneLinkController,
            autoSeries = prefs.autoSeries,
            systemSpotlight = prefs.systemSpotlight,
            hiddenSeries = prefs.hiddenSeries,
            biosConfirmed = prefs.biosConfirmed,
            drivesAsked = prefs.drivesAsked,
            steamGames = prefs.steamGames,
            // The most recently read, so the list never grows without end.
            manuals = prefs.manuals.entries.sortedByDescending { it.value.readAt }.take(MANUALS_KEPT).associate { it.key to it.value },
            appsFilter = prefs.appsFilter,
            gameArt = prefs.gameArt,
            systemTiles = prefs.systemTiles,
            opensOn = prefs.libraryOpensOn,
        ),
        sound = sound.copy(
            enabled = prefs.sound != SoundProfile.OFF,
            volume = prefs.soundVolume.coerceIn(0f, 1f),
            profile = prefs.sound.takeIf { it != SoundProfile.OFF && it != theme.sound },
            muted = prefs.muted,
            master = prefs.masterVolume.coerceIn(0f, 1f),
        ),
        music = music.copy(
            enabled = prefs.music.enabled,
            volume = prefs.music.volume.coerceIn(0f, 1f),
            songPath = prefs.music.songPath,
            songName = prefs.music.songName,
            track = prefs.music.track,
            shuffle = prefs.music.shuffle,
        ),
        statusArea = statusArea.copy(
            use24HourClock = prefs.clock24h,
            showWifi = prefs.showWifi,
            showBluetooth = prefs.showBluetooth,
        ),
        cartridge = cartridge.copy(autoRefreshOnReturn = prefs.autoRefreshFromCartridge, enabled = prefs.cartridgeEnabled, rommDetails = prefs.cartridgeRommDetails),
        updates = updates.copy(checkForUpdates = prefs.checkForUpdates),
        capture = capture.copy(combo = prefs.captureCombo, sound = prefs.captureSound),
        steam = prefs.steam,
        extensions = prefs.extensions,
        // What Fuse installed is kept as it is: only the Store writes it.
        store = store.copy(enabled = prefs.storeEnabled, variant = prefs.storeVariant, autoCheck = prefs.storeAutoCheck),
        jellyfin = prefs.jellyfin,
        romm = prefs.romm,
        streaming = prefs.streaming,
        downloads = prefs.downloads,
        // Whether Syncthing is on, and where, is the Syncthing service's to write: only how it behaves comes from here.
        syncthing = syncthing.copy(waitBeforePlaying = prefs.syncthing.waitBeforePlaying, keepVersions = prefs.syncthing.keepVersions),
    )
}

/** How many recent colours the theme studio keeps. */
internal const val RECENT_COLORS = 10

/** How many manuals keep their page and bookmarks. */
private const val MANUALS_KEPT = 200
