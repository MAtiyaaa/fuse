package io.github.matiyaaa.fuse.ui.shell.store

import androidx.compose.runtime.Immutable
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.AchievementUser
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.RecentAchievement

/** Artwork references already resolved to loadable models (file paths or URLs). */
@Immutable
data class Art(
    /** Square box art, the tile art. */
    val square: Any? = null,
    val icon: Any? = null,
    val boxart: Any? = null,
    val grid: Any? = null,
    val hero: Any? = null,
    val logo: Any? = null,
    val heroFocusX: Float = 0.5f,
    val heroFocusY: Float = 0.35f,
    val video: String? = null,
    /** The first screenshot, a background for games without one of their own. */
    val screenshot: Any? = null,
) {
    /** Art for a small square (thumbnails, menus): square box art, then icon, cover and wide art. */
    val tile: Any? get() = square ?: icon ?: boxart ?: grid

    companion object {
        val None = Art()

        fun from(media: MediaSet): Art = Art(
            square = media.square?.model,
            icon = media.icon?.model,
            boxart = media.boxart?.model,
            grid = media.grid?.model,
            hero = media.hero?.model,
            logo = media.logo?.model,
            heroFocusX = media.hero?.focusX ?: 0.5f,
            heroFocusY = media.hero?.focusY ?: 0.35f,
            video = media.video?.model,
            screenshot = media.screenshots.firstOrNull()?.model,
        )
    }
}

/** A game as list screens need it: small, stable, cheap to compare. */
@Immutable
data class GameCard(
    val id: GameId,
    val platformId: PlatformId,
    val title: String,
    val platformShort: String,
    /** ARGB accent of the platform, used for generated art and glow. */
    val accent: Long,
    val art: Art,
    val favorite: Boolean = false,
    val lastPlayedAt: Long? = null,
    val playSeconds: Long = 0,
    val addedAt: Long = 0,
    val year: Int? = null,
    val updates: Int = 0,
    val dlc: Int = 0,
    val discs: Int = 0,
    /** File no longer found on disk (kept so user edits survive; shown dimmed). */
    val missing: Boolean = false,
    /** RomM rom id when Cartridge downloaded it (enables "Open in Cartridge"). */
    val rommRomId: Long? = null,
    /** An installed app played as a game (Android), not a file: no files to upload or delete. */
    val isApp: Boolean = false,
)

@Immutable
data class PlatformCard(
    val platform: Platform,
    val gameCount: Int,
    val art: Art,
    val emulatorName: String?,
    val emulatorInstalled: Boolean,
    val installedEmulators: Int,
    val bios: BiosStatus,
    val layout: LibraryLayout,
    val romFolders: List<String>,
)

/** Everything the game page shows. */
@Immutable
data class GameDetail(
    val game: Game,
    val platform: Platform,
    val media: MediaSet,
    val art: Art,
    /** Chosen emulator for this game (after overrides) and every installed alternative. */
    val emulator: EmulatorChoice,
    /** What happens with DLC/updates/other content for the chosen emulator. */
    val contentNotes: List<ContentNote>,
    val achievements: AchievementState?,
    val collections: List<GameCollection>,
    val secondsThisWeek: Long,
)

@Immutable
data class EmulatorChoice(
    val selected: InstalledEmulator?,
    val alternatives: List<InstalledEmulator>,
    /** "Game", "Platform" or "Automatic": where the choice came from. */
    val source: String,
    /** Short sentence for Game Info, e.g. "Opens the folder in aPS3e" or "Opens the app; pick the game there". */
    val launchSummary: String?,
    val canLaunch: Boolean,
    val emulatorHomepage: String? = null,
)

@Immutable
data class ContentNote(val kind: ContentKind, val count: Int, val message: String)

/** One card on Home. */
@Immutable
data class HomeFeed(
    val continuePlaying: List<GameCard> = emptyList(),
    val recentlyPlayed: List<GameCard> = emptyList(),
    val recentlyAdded: List<GameCard> = emptyList(),
    val favorites: List<GameCard> = emptyList(),
    val pinnedGames: List<GameCard> = emptyList(),
    val mostPlayed: List<GameCard> = emptyList(),
    val systems: List<PlatformCard> = emptyList(),
    val collections: List<GameCollection> = emptyList(),
    val pinnedApps: List<AppCard> = emptyList(),
    val playtime: PlaytimeSummary = PlaytimeSummary(),
    val achievements: AchievementsFeed? = null,
    val storage: StorageSummary? = null,
)

@Immutable
data class PlaytimeSummary(
    val totalSeconds: Long = 0,
    val weekSeconds: Long = 0,
    /** Seconds per day for the last 7 days, oldest first. */
    val lastSevenDays: List<Long> = emptyList(),
    val currentGame: GameCard? = null,
    /** When the open session started (epoch millis), while a game is running. */
    val currentSince: Long? = null,
)

@Immutable
data class AchievementsFeed(
    val user: AchievementUser,
    val recent: List<RecentAchievement>,
    val inProgress: List<AchievementState>,
    val recentlyMastered: List<AchievementState>,
)

@Immutable
data class StorageSummary(val label: String, val freeBytes: Long, val totalBytes: Long)

@Immutable
data class AppCard(
    val entry: AppEntry,
    /** Model for the app icon loader (platform specific). */
    val icon: Any?,
)

@Immutable
data class SearchResults(
    val query: String = "",
    val games: List<GameCard> = emptyList(),
    val platforms: List<PlatformCard> = emptyList(),
    val apps: List<AppCard> = emptyList(),
    val collections: List<GameCollection> = emptyList(),
) {
    val isEmpty: Boolean get() = games.isEmpty() && platforms.isEmpty() && apps.isEmpty() && collections.isEmpty()
}

/** Result of asking to play a game, shown to the user when it didn't simply start. */
sealed interface LaunchOutcome {
    data object Started : LaunchOutcome
    data class OpenedAppOnly(val appName: String, val reason: String) : LaunchOutcome
    data class NeedsEmulator(val platformName: String, val suggestions: List<String>) : LaunchOutcome
    data class Failed(val message: String) : LaunchOutcome
    data class Unsupported(val message: String) : LaunchOutcome
}

/** Emulator choice option for pickers. */
@Immutable
data class EmulatorOption(val id: EmulatorId, val name: String, val installed: Boolean, val note: String?)
