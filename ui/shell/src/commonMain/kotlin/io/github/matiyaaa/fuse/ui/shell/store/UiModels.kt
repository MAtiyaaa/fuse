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
    /** Set while the drive or folder holding the game can't be read (an SD card that's out). */
    val unavailable: Unavailable? = null,
) {
    /** Drawn dimmed: its file is gone, or its drive can't be reached right now. */
    val dimmed: Boolean get() = missing || unavailable != null
}

/** Why a game can't be reached right now, and on which drive it lives. */
@Immutable
data class Unavailable(val driveLabel: String, val state: io.github.matiyaaa.fuse.model.SourceState) {
    /** "SD card unavailable" for the tile and stage. */
    val label: String get() = when (state) {
        io.github.matiyaaa.fuse.model.SourceState.NO_ACCESS -> "No access to $driveLabel"
        io.github.matiyaaa.fuse.model.SourceState.FOLDER_MISSING, io.github.matiyaaa.fuse.model.SourceState.MOVED -> "Library folder missing"
        else -> "$driveLabel unavailable"
    }
}

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
    /** The emulator the user chose for the system, when they did (it may no longer be installed). */
    val emulatorChosen: EmulatorId? = null,
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
    /** Set while the game's drive or folder can't be reached. */
    val unavailable: Unavailable? = null,
    /** The last scan didn't find the game's file. */
    val missing: Boolean = false,
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
    /** Jellyfin's Home widgets, empty while Jellyfin is off or no widget of it is on Home. */
    val media: io.github.matiyaaa.fuse.jellyfin.MediaFeed = io.github.matiyaaa.fuse.jellyfin.MediaFeed(),
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

/** Where play time went: today, this week, this month, all of it, per day, per game and per system. */
@Immutable
data class PlayTimeReport(
    val todaySeconds: Long = 0,
    val weekSeconds: Long = 0,
    val monthSeconds: Long = 0,
    /** Time Fuse saw, and time imported from elsewhere (Steam, RetroAchievements). */
    val trackedSeconds: Long = 0,
    val importedSeconds: Long = 0,
    /** Seconds per day for the last 30 days, oldest first, ending today. */
    val days: List<Long> = emptyList(),
    /** "October": the month [monthSeconds] and [games] are about. */
    val month: String = "",
    val games: List<Pair<GameCard, Long>> = emptyList(),
    val systems: List<Pair<PlatformCard, Long>> = emptyList(),
    val loaded: Boolean = false,
) {
    val totalSeconds: Long get() = trackedSeconds + importedSeconds
}

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
    /** The filters in the search, as chips to read and take back out. */
    val chips: List<SearchChip> = emptyList(),
    /** Filters to add, or values for the filter being typed. */
    val suggestions: List<SearchSuggestion> = emptyList(),
) {
    val isEmpty: Boolean get() = games.isEmpty() && platforms.isEmpty() && apps.isEmpty() && collections.isEmpty()
}

/** A filter in a search, in words ("PlayStation", "1995 to 1999"); [valid] is false for one that can't be read. */
data class SearchChip(val token: String, val key: io.github.matiyaaa.fuse.data.search.FilterKey?, val label: String, val valid: Boolean = true)

/** Something to add to a search: [text] is the whole search after choosing it. */
data class SearchSuggestion(
    val label: String,
    val detail: String?,
    val text: String,
    val key: io.github.matiyaaa.fuse.data.search.FilterKey,
)

/** Result of asking to play a game, shown to the user when it didn't simply start. */
sealed interface LaunchOutcome {
    data object Started : LaunchOutcome
    data class OpenedAppOnly(val appName: String, val reason: String) : LaunchOutcome

    /** The game didn't start; [problem] says what happened and what can be done. */
    data class Problem(val problem: io.github.matiyaaa.fuse.ui.shell.store.Problem) : LaunchOutcome

    /** Started, with word from Fuse Sync ("Your save from Steam Deck is in place"). */
    data class Synced(val note: String) : LaunchOutcome

    /**
     * Not started yet: this device and Fuse Sync both have a newer save. The person picks one (the
     * other is kept in its history), then it starts.
     */
    data class SaveConflict(val conflict: io.github.matiyaaa.fuse.sync.SaveConflict) : LaunchOutcome

    /** Not started yet: another device is playing this game or still sending its save. Wait for it, or play here. */
    data class SyncBusy(val busy: io.github.matiyaaa.fuse.sync.LaunchGate.Busy) : LaunchOutcome

    /** Syncthing kept two versions of this game's save: ask which to keep, then play. */
    data class SyncthingConflict(val conflicts: List<io.github.matiyaaa.fuse.sync.syncthing.SyncthingConflict>) : LaunchOutcome
}

/**
 * Something that went wrong or needs attention, told the way a player needs it: what happened
 * ([title]), why it probably happened ([message]), whether anything was harmed ([reassurance]),
 * and what can be done now ([actions]). Technical text stays behind [details]. Launch failures and
 * System health speak through this one shape.
 */
@Immutable
data class Problem(
    val title: String,
    val message: String,
    val kind: ProblemKind = ProblemKind.GENERAL,
    val severity: Severity = Severity.ATTENTION,
    /** Said plainly when it is true: "Nothing was changed." Null when it isn't worth saying. */
    val reassurance: String? = NOTHING_CHANGED,
    val actions: List<ProblemAction> = emptyList(),
    /** The technical story (the system's message, paths, ids) for a bug report. */
    val details: String? = null,
) {
    companion object {
        const val NOTHING_CHANGED = "Nothing was changed. Your games and settings are as they were."
    }
}

/** What a problem is about, for its icon. */
enum class ProblemKind { DRIVE, EMULATOR, FILE, ACCESS, FIRMWARE, NETWORK, ACCOUNT, DATA, DISPLAY, RECOVERY, GENERAL }

/** How much a problem matters. Never alarming: the worst is "Broken", for something that can't work. */
enum class Severity { HEALTHY, INFO, ATTENTION, BROKEN }

/** Something the player can do about a [Problem]. The screen showing it decides how each runs. */
@Immutable
sealed interface ProblemAction {
    val label: String

    data class Retry(override val label: String = "Try again") : ProblemAction
    data class PickEmulator(val game: GameId? = null, val platform: io.github.matiyaaa.fuse.model.PlatformId? = null, override val label: String = "Choose another emulator") : ProblemAction
    data class OpenEmulator(val emulator: EmulatorId, val name: String, override val label: String = "Open $name") : ProblemAction
    data class OpenLink(val url: String, override val label: String) : ProblemAction
    data class CheckDrives(override val label: String = "Check drives again") : ProblemAction
    data class OpenStorage(override val label: String = "Storage") : ProblemAction
    data class OpenSettings(val section: String, override val label: String, val group: String? = null) : ProblemAction
    data class GrantAccess(override val label: String = "Allow access") : ProblemAction
    data class OpenSystem(val platform: io.github.matiyaaa.fuse.model.PlatformId, override val label: String) : ProblemAction
    data class OpenGame(val game: GameId, override val label: String) : ProblemAction
    /** The game's Installed Content page, to install it into its emulator. */
    data class InstallContent(val game: GameId, override val label: String = "Install it") : ProblemAction
    data class AdoptDrive(val source: io.github.matiyaaa.fuse.model.LibrarySourceId, override val label: String = "It's the same library") : ProblemAction
    data class RemoveSource(val source: io.github.matiyaaa.fuse.model.LibrarySourceId, override val label: String = "Remove this folder") : ProblemAction
    data class Rescan(override val label: String = "Scan again") : ProblemAction
    data class LeaveSafeMode(override val label: String = "Leave safe mode") : ProblemAction

    /** The library's list of games whose files weren't found. */
    data class ShowMissing(override val label: String = "Show these games") : ProblemAction
    data class OpenHealth(override val label: String = "System health") : ProblemAction

    /** Back to Fuse's own theme with standard motion and no glass or CRT, saved. */
    data class ResetAppearance(override val label: String = "Reset appearance") : ProblemAction
}

/** Emulator choice option for pickers. */
@Immutable
data class EmulatorOption(
    val id: EmulatorId,
    val name: String,
    val installed: Boolean,
    val note: String?,
    /** Why it can't be chosen for this game (it can't open this kind of file, it isn't installed); null when it can. */
    val unavailable: String? = null,
)

/** A PS2 game's PCSX2 patches and who turned each on, or why Fuse can't show them. */
sealed interface Pcsx2PatchList {
    data class Ready(
        val serial: String,
        val crc: String,
        val patches: List<io.github.matiyaaa.fuse.launch.patches.PatchStatus>,
        /** False when PCSX2's bundled patches couldn't be read here (only patch files in its patches folder are listed). */
        val bundledRead: Boolean,
    ) : Pcsx2PatchList

    data class Unavailable(val title: String, val reason: String) : Pcsx2PatchList
}

/** What RPCS3's compatibility list says about a PS3 game, asked for by the user. */
sealed interface CompatibilityAnswer {
    data class Listed(val entry: io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Compat) : CompatibilityAnswer

    /** The game's title id isn't known (not in its name, no PARAM.SFO), so there is nothing to ask with. */
    data object NoTitleId : CompatibilityAnswer

    /** The list has no entry for this title id. */
    data class NotListed(val titleId: String) : CompatibilityAnswer

    /** rpcs3.net couldn't be reached. */
    data object Unreachable : CompatibilityAnswer
}

/** Everything Fuse knows about one emulator, for its page in Settings. */
data class EmulatorDetails(
    val id: EmulatorId,
    val name: String,
    val version: String?,
    val installed: Boolean,
    /** How it was found ("Flatpak", "PATH", "Located"), and the app or program found. */
    val foundVia: String?,
    val appId: String?,
    /** Where the user pointed Fuse at it, when they did. */
    val locatedAt: String?,
    /** Names of the systems it runs. */
    val systems: List<String>,
    /** Systems whose games go to it because it was chosen for them. */
    val chosenFor: List<String>,
    /** How sure Fuse is that it starts games the way Fuse asks, in words. */
    val support: String,
    val limitations: List<String>,
    val homepage: String?,
    /** True when it only opens its own app (the game is picked there). */
    val opensAppOnly: Boolean,
)
