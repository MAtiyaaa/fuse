package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * One game as the user sees it. A game is a title, not a file: a Switch folder with its base game,
 * update and DLC is one [Game] with [content] children, and a four-disc PlayStation game is one
 * [Game] with four [discs].
 */
@Serializable
data class Game(
    val id: GameId,
    val platformId: PlatformId,
    val titles: GameTitles,
    val location: GameLocation,
    val content: List<ChildContent> = emptyList(),
    val discs: List<Disc> = emptyList(),
    val tags: FilenameTags = FilenameTags(),
    val metadata: GameMetadata = GameMetadata(),
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    /** Epoch millis when Fuse first saw this game. */
    val addedAt: Long = 0,
    val play: PlayStats = PlayStats(),
    val links: ExternalLinks = ExternalLinks(),
    /** Per-game emulator choice. Null means "use the platform's". */
    val emulatorOverride: EmulatorId? = null,
    /** Per-game folder behaviour. Null means "use the platform's". */
    val folderPolicyOverride: FolderPolicy? = null,
    /** The system the user chose ("System" in the game's options); [platformId] follows it. */
    val platformOverride: PlatformId? = null,
    /** The system the game's folder says, which [platformOverride] replaces. */
    val scannedPlatformId: PlatformId? = null,
    /** Where the person is with it, what they think of it and their note (the Journal). */
    val journal: GameJournal = GameJournal(),
) {
    val displayTitle: String get() = titles.display

    /** The installed app this game is ([AppGames]), or null for a game on disk. */
    val appId: String? get() = AppGames.appId(location.path)
}

/**
 * Title handling keeps every version so Clean Display Names and Rename can always be undone.
 * Files are never renamed.
 */
@Serializable
data class GameTitles(
    /** Exactly as found on disk, without extension (for example "Metroid Fusion (USA) (Rev 1)"). */
    val original: String,
    /** Result of the automatic cleanup, when Clean Display Names is on. */
    val cleaned: String? = null,
    /** Set by the user with Rename Display Title. Always wins. */
    val custom: String? = null,
    /** Title from trusted metadata (RomM, a confirmed scraper match). */
    val metadata: String? = null,
    val useCleaned: Boolean = false,
) {
    val display: String
        get() = custom ?: metadata ?: (if (useCleaned) cleaned else null) ?: original

    /** Sort key that ignores leading articles and case. */
    val sortKey: String
        get() = display.lowercase().removePrefix("the ").removePrefix("a ").trim()
}

@Serializable
enum class LocationKind { FILE, FOLDER }

/**
 * Where a game lives. [path] is the game's own entry (a file, or the title folder). [launchPath] is
 * what the chosen emulator is actually given, which the launch adapter may refine further.
 */
@Serializable
data class GameLocation(
    val sourceId: LibrarySourceId,
    val path: String,
    val kind: LocationKind,
    /** File or folder to launch. For a folder game this may be a file inside it, or the folder itself. */
    val launchPath: String,
    val sizeBytes: Long = 0,
    val modifiedAt: Long = 0,
    /** How this entry was interpreted by the scanner (for Game Info and debugging). */
    val interpretation: FolderInterpretation = FolderInterpretation.SINGLE_FILE,
)

@Serializable
enum class FolderInterpretation {
    /** A plain file game. */
    SINGLE_FILE,
    /** A folder that is itself the game (PS3 title folder, extracted game, Wii U title). */
    FOLDER_IS_GAME,
    /** A RomM multi-file game folder: one main file plus related files/special folders. */
    MULTI_FILE_GAME,
    /** A multi-disc set grouped from sibling files or an .m3u. */
    MULTI_DISC,
    /** A folder the user browses to pick a launch target. */
    FOLDER_BROWSER,
}

/**
 * How folders inside a platform folder are interpreted. Resolved Global -> Platform -> Game.
 */
@Serializable
enum class FolderPolicy {
    /** Fuse decides from the folder's contents (RomM special folders, known structures, disc sets). */
    AUTO,
    /** Only files are games. Folders are searched for more files. */
    FILE,
    /** Every top-level folder is one game. */
    FOLDER_AS_GAME,
    /** A folder opens like a directory and the user picks what to launch. */
    FOLDER_BROWSER,
}

/**
 * RomM's file categories inside a multi-file game folder (`RomFileCategory` in RomM's
 * backend/models/rom.py). A folder directly under the game folder whose lower-cased name is the
 * category, its plural with "s" or "es", belongs to that category. See INTEGRATIONS.md.
 */
@Serializable
enum class ContentKind(val slug: String, val holdsGameData: Boolean) {
    GAME("game", true),
    DLC("dlc", true),
    UPDATE("update", true),
    PATCH("patch", true),
    HACK("hack", true),
    MOD("mod", true),
    TRANSLATION("translation", true),
    DEMO("demo", true),
    PROTOTYPE("prototype", true),
    MANUAL("manual", false),
    WALKTHROUGH("walkthrough", false),
    CHEAT("cheat", false),
    SOUNDTRACK("soundtrack", false),
    SCREENSHOT("screenshot", false),
    ;

    companion object {
        /** The category a folder name stands for (RomM's x / xs / xes rule), or null for an ordinary folder. */
        fun ofFolder(name: String): ContentKind? {
            val n = name.trim().lowercase()
            return entries.firstOrNull { n == it.slug || n == it.slug + "s" || n == it.slug + "es" }
        }
    }
}

/** DLC, updates and other files that belong to a game instead of being games themselves. */
@Serializable
data class ChildContent(
    val kind: ContentKind,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0,
)

@Serializable
data class Disc(
    /** 1-based disc number. */
    val number: Int,
    val label: String,
    val path: String,
)

/** Metadata parsed from a file name's bracket tags. Display only; never used to rename files. */
@Serializable
data class FilenameTags(
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val revision: String? = null,
    val version: String? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    /** Dump/status flags such as "[!]", "Beta", "Proto", "Hack", "Translated". */
    val flags: List<String> = emptyList(),
    /** A PlayStation-style title id found in the name, for example "BLUS30001" or "PCSB00245". */
    val serial: String? = null,
)

@Serializable
data class GameMetadata(
    val description: String? = null,
    val releaseYear: Int? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genres: List<String> = emptyList(),
    val franchise: String? = null,
    val players: String? = null,
    /** 0..100 */
    val rating: Int? = null,
    val source: MetadataSource? = null,
)

@Serializable
enum class MetadataSource { LOCAL, ROMM, CARTRIDGE, STEAMGRIDDB, IGDB, THEGAMESDB, SCREENSCRAPER, LIBRETRO, USER }

/**
 * Where the person is with a game, in their own words (the Journal). Only ever what they set: Fuse
 * never works it out from play time. RomM keeps the same marks in its own words (RommJournal).
 */
@Serializable
enum class PlayStatus {
    /** Meant to be played: the backlog, Up Next. */
    WANT,
    PLAYING,

    /** Put down for now, to be picked up again. */
    PAUSED,

    /** Played to its end. */
    BEATEN,

    /** Everything in it done. */
    COMPLETED,

    /** Put down for good. */
    DROPPED,
    ;

    /** Done with: beaten, completed or dropped (not suggested again, not in Up Next). */
    val settled: Boolean get() = this == BEATEN || this == COMPLETED || this == DROPPED

    companion object {
        fun of(name: String?): PlayStatus? = name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}

/**
 * What the person wrote down about a game: where they are with it ([status], since [statusAt]),
 * what they think of it ([rating]) and a [note] of their own.
 */
@Serializable
data class GameJournal(
    val status: PlayStatus? = null,
    /** When [status] was set (epoch ms): "Beaten in March", and Up Next's newest first. */
    val statusAt: Long? = null,
    /** Out of [MOST_RATING], shown as five stars in halves. 0 is not rated. */
    val rating: Int = 0,
    val note: String? = null,
) {
    val isEmpty: Boolean get() = status == null && rating == 0 && note.isNullOrBlank()

    companion object {
        /** The top rating: ten halves of five stars, as RomM keeps it. */
        const val MOST_RATING = 10

        /**
         * The most a game's notes hold, every page together: a notebook's worth, so writing never
         * runs out while playing, and still one small field to keep, back up and sync.
         */
        const val LONGEST_NOTE = 40_000
    }
}

/**
 * A game's notes as pages of a notebook, kept in [GameJournal.note] as one text: the pages one after
 * another, each begun with a page break (form feed, the character that has always meant "new page").
 * One page is just its text, so a note written before notebooks, a note from RomM and one read on an
 * older Fuse all stay as they are; a notebook goes everywhere a note already goes (Fuse One, backups,
 * RomM), with nothing new to sync.
 */
object NoteBook {
    /** What starts each page after the first. */
    const val PAGE_BREAK = '\u000C'

    /** The pages of [note], at least one (empty when there is no note yet). */
    fun pages(note: String?): List<String> {
        if (note.isNullOrEmpty()) return listOf("")
        return note.split(PAGE_BREAK).map { it.trim('\n', '\r') }.ifEmpty { listOf("") }
    }

    /** [pages] kept as one note: empty pages left out, null when nothing is written at all. */
    fun join(pages: List<String>): String? {
        val kept = pages.map { it.trimEnd() }.filter { it.isNotBlank() }
        if (kept.isEmpty()) return null
        return kept.joinToString("\n$PAGE_BREAK\n").take(GameJournal.LONGEST_NOTE)
    }

    /** A page's title: its first line, a little of it. */
    fun title(page: String): String? = page.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(TITLE_LENGTH)

    private const val TITLE_LENGTH = 40
}

@Serializable
data class PlayStats(
    val lastPlayedAt: Long? = null,
    /** Only time Fuse observed (or imported with a recorded source). Never estimated. */
    val trackedSeconds: Long = 0,
    val importedSeconds: Long = 0,
    val importedSource: String? = null,
    val sessions: Int = 0,
) {
    val totalSeconds: Long get() = trackedSeconds + importedSeconds
}

@Serializable
data class ExternalLinks(
    val retroAchievementsGameId: Long? = null,
    val steamGridDbGameId: Long? = null,
    val igdbId: Long? = null,
    /** RomM rom id, when Cartridge reported this file as one of its downloads. */
    val rommRomId: Long? = null,
    val steamAppId: Long? = null,
    val providerClaims: List<ProviderClaim> = emptyList(),
)
