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
    /**
     * [boxart] is a system's artwork panel made from one of its games' screenshots (the system art
     * pack has none for it): it is drawn cut to the pack's slanted panel, where the pack's own come cut.
     */
    val boxartFromGames: Boolean = false,
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
            boxartFromGames = media.boxart?.let { b -> b.source == io.github.matiyaaa.fuse.model.MediaSource.GAME_ART || b.model in GamePanels.models } == true,
        )
    }
}

/**
 * The pictures people picked as a system's panel from a game's screenshot (Settings keeps them by
 * system), so [Art.from] knows to cut them like the pack's. Set by the store as settings change.
 */
object GamePanels {
    @kotlin.concurrent.Volatile var models: Set<String> = emptySet()
}

/** A game whose screenshots can make a system's panel, for the Media page's picker. */
data class PanelGame(val id: io.github.matiyaaa.fuse.model.GameId, val title: String, val art: Art, val pictures: Int)

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

/** A game suggested to someone, and why. */
@Immutable
data class Suggestion(val card: GameCard, val reason: String)

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
    /** Games suggested for this person, best first; why each is suggested is in [reasons]. */
    val forYou: List<GameCard> = emptyList(),
    /** The game in today's spotlight. */
    val spotlight: GameCard? = null,
    /** Why each suggested or spotlit game is there ("Next in the Metroid series, after Metroid Fusion"). */
    val reasons: Map<io.github.matiyaaa.fuse.model.GameId, String> = emptyMap(),
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

/** Where a launch is, for the launch veil: bringing the save up to date (it can still be called off), or starting. */
enum class LaunchStage { CHECKING_SAVE, STARTING }

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
    /** The game's emulator settings (its renderer, for one), in Fuse. */
    data class EmulatorSettings(val game: GameId, override val label: String = "Change the renderer") : ProblemAction
    /** Back to the version of a Store program its last update replaced (kept by Fuse). */
    data class GoBack(val storeKey: String, override val label: String) : ProblemAction
    data class OpenLink(val url: String, override val label: String) : ProblemAction
    data class CheckDrives(override val label: String = "Check drives again") : ProblemAction
    data class OpenStorage(override val label: String = "Storage") : ProblemAction
    data class OpenSettings(val section: String, override val label: String, val group: String? = null) : ProblemAction
    data class GrantAccess(override val label: String = "Allow access") : ProblemAction
    data class OpenSystem(val platform: io.github.matiyaaa.fuse.model.PlatformId, override val label: String) : ProblemAction
    data class OpenGame(val game: GameId, override val label: String) : ProblemAction
    /** A system's missing firmware from the person's own RomM server, through RomM. */
    data class BiosFromRomm(val platform: io.github.matiyaaa.fuse.model.PlatformId, val name: String, override val label: String = "Download from RomM") : ProblemAction
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

/** One emulator as Fuse supports it on this system, for the Emulator support dashboard. */
data class EmulatorSupportRow(
    val id: EmulatorId,
    val name: String,
    val installed: Boolean,
    val version: String?,
    /** How it was found ("Flatpak", "PATH", "Located"), and where. */
    val foundVia: String?,
    val path: String?,
    /** How Fuse knows how to start it: its makers' documentation, the community, or not confirmed. */
    val evidence: String,
    /** The furthest step Fuse has seen for itself here ("Launch verified"), when installed. */
    val checked: String?,
    val systems: Int,
    /** What Fuse does with it here: starts games, finds saves, patches, cheats, settings. */
    val features: List<String>,
)

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

/**
 * A game's patches and cheats in its emulator's own settings (PCSX2 for PS2, DuckStation for
 * PlayStation, PPSSPP for PSP) and who turned each on, or why Fuse can't show them.
 */
sealed interface GamePatchList {
    data class Ready(
        val serial: String,
        /** PCSX2's CRC of the boot program; empty for emulators that file codes by serial alone. */
        val crc: String,
        val patches: List<io.github.matiyaaa.fuse.launch.patches.PatchStatus>,
        /** False when the emulator's bundled codes couldn't be read here (only its own folders are listed). */
        val bundledRead: Boolean,
        /** The game's cheats, and who turned each on. */
        val cheats: List<io.github.matiyaaa.fuse.launch.patches.PatchStatus> = emptyList(),
        /** The emulator applies cheats to this game (its own setting, or the emulator's for every game). */
        val cheatsOn: Boolean = false,
        /** The emulator's name, as the sheet says it. */
        val emulator: String = "PCSX2",
        /** Where its codes come from, said when there are none. */
        val whereFrom: String = "",
        /** Something in the emulator's settings that keeps them from applying, said above the list. */
        val warning: String? = null,
        /** How turning on a cheat reaches the emulator's own cheats switch. */
        val cheatsNote: String = "Turning a cheat on turns on PCSX2's cheats for this game, and off again with the last one.",
    ) : GamePatchList

    data class Unavailable(val title: String, val reason: String) : GamePatchList
}

/** A person's own cheats, patches and texture packs added to a game, and what can be added to it here. */
data class Enhancements(
    val added: List<EnhancementView>,
    /** What this game's emulator takes from a person's own files, here; empty when nothing. */
    val kinds: List<io.github.matiyaaa.fuse.launch.patches.EnhancementKind>,
)

/** One thing a person added to a game. */
data class EnhancementView(
    val id: String,
    val kind: io.github.matiyaaa.fuse.launch.patches.EnhancementKind,
    /** The name of the file, folder or archive it came from. */
    val name: String,
    val emulator: String,
    val files: Int,
    val addedAt: Long,
    /** Where it went: the folder, or the file its codes were added to (home as `~`). */
    val where: String,
    /** It turned on something in the emulator's settings for the game, put back when it is taken out. */
    val settingsChanged: Boolean,
    /** Where it came from when Fuse fetched it ("GameBanana"), with its page and version. */
    val origin: String? = null,
    val originUrl: String? = null,
    val version: String? = null,
)

/** Exactly what adding a file, folder or archive would do, worked out before anything changes; or why it can't be added. */
sealed interface EnhancementPreview {
    data class Ready(
        /** Hands the plan back to [FuseStore.LibraryOps.addEnhancement]. */
        val token: String,
        val kind: io.github.matiyaaa.fuse.launch.patches.EnhancementKind,
        val emulator: String,
        val name: String,
        /** The folder it goes in (home as `~`). */
        val target: String,
        /** New files it puts in place. */
        val adds: List<String>,
        /** Files there now that it takes the place of; each is kept and put back when it is taken out. */
        val replaces: List<String>,
        /** The file its codes are added to the end of, when they are. */
        val appendsTo: String?,
        /** Settings it turns on for the game, said plainly. */
        val settings: List<String>,
        /** What in it was left out, and why. */
        val rejected: List<String>,
        val notes: List<String>,
        /** Cheats or patches in it, as the emulator reads them; 0 for textures. */
        val codes: Int,
        val bytes: Long,
    ) : EnhancementPreview

    data class Unavailable(val title: String, val reason: String) : EnhancementPreview
}

/** Which game on GameBanana a library game is: the one chosen, and the ones a search found. */
data class ModSiteGames(
    val linked: io.github.matiyaaa.fuse.integrations.gamebanana.GbGame?,
    val found: List<io.github.matiyaaa.fuse.integrations.gamebanana.GbGame>,
    /** What was searched for. */
    val query: String,
    /** Why the search couldn't be made, when it couldn't. */
    val problem: String? = null,
)

/** A mod on GameBanana, with the kind Fuse can add it as for this game (null: a kind Fuse doesn't add). */
data class ModSiteMod(
    val mod: io.github.matiyaaa.fuse.integrations.gamebanana.GbMod,
    val kind: io.github.matiyaaa.fuse.launch.patches.EnhancementKind?,
)

/** A page of a game's mods on GameBanana, or why there is none. */
sealed interface ModSiteMods {
    data class Ready(
        val game: io.github.matiyaaa.fuse.integrations.gamebanana.GbGame,
        val mods: List<ModSiteMod>,
        val page: Int,
        val total: Long,
        val more: Boolean,
    ) : ModSiteMods

    /** [needsGame]: which game on GameBanana this is hasn't been chosen yet. */
    data class Unavailable(val title: String, val reason: String, val needsGame: Boolean = false) : ModSiteMods
}

/** A mod's page on GameBanana: its files and what Fuse can do with each, or why it can't be read. */
sealed interface ModSitePage {
    data class Ready(
        val page: io.github.matiyaaa.fuse.integrations.gamebanana.GbModPage,
        val kind: io.github.matiyaaa.fuse.launch.patches.EnhancementKind?,
    ) : ModSitePage

    data class Unavailable(val title: String, val reason: String) : ModSitePage
}

/** How taking out something a person added went. */
sealed interface EnhancementRemoval {
    data object Removed : EnhancementRemoval

    /** What Fuse added was changed in the emulator since; [message] asks whether to put the earlier file back anyway. */
    data class ChangedSince(val message: String) : EnhancementRemoval

    data class Failed(val message: String) : EnhancementRemoval
}

/** An emulator's own settings, for one game or for every game, or why Fuse can't change them. */
sealed interface EmulatorSettingsView {
    data class Ready(
        val emulator: String,
        /** The game these are for; null when they are for every game. */
        val game: String?,
        val values: List<io.github.matiyaaa.fuse.launch.settings.SettingValue>,
        /** Fuse changed something in this file that it can put back. */
        val canRestore: Boolean,
        /** For a game: the sets of these settings it can be given at once, Fuse's first, then the person's own. */
        val profiles: List<SettingProfileView> = emptyList(),
    ) : EmulatorSettingsView

    data class Unavailable(val title: String, val reason: String) : EmulatorSettingsView
}

/** One setting profile a game can be given: [active] when every value in it is the game's now. */
data class SettingProfileView(val id: String, val name: String, val about: String, val own: Boolean, val active: Boolean)

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
    /** How far Fuse has seen it go here, step by step with evidence; null when not installed or built in. */
    val verification: io.github.matiyaaa.fuse.launch.intelligence.Verification? = null,
)
