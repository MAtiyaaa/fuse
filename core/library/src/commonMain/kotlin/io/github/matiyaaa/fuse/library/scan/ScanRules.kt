package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.parse.TagTables
import io.github.matiyaaa.fuse.model.Platform

/**
 * Tunables for a scan.
 *
 * @property maxDepth How many folder levels below a platform folder are walked. Organisational
 *   folders (`psx/Europe/A/...`) count as levels. Deeper content is not listed.
 * @property extraExcludedFolders Additional folder names (case-insensitive) never scanned.
 */
data class ScanOptions(
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val extraExcludedFolders: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 4
    }
}

/**
 * Which folders and files are never games. Built from RomM's `DEFAULT_EXCLUDED_MULTI_FILE_DIRS`
 * and ignored-file list, ES-DE/Batocera media folders, and common emulator save files.
 */
object ScanRules {
    /** RomM's never-game folders plus ES-DE/Batocera media folders, lower case. */
    val excludedFolderNames: Set<String> = setOf(
        // RomM DEFAULT_EXCLUDED_MULTI_FILE_DIRS (system and NAS folders)
        "@eadir", "assets", "__macosx", "#recycle", "\$recycle.bin", ".stfolder", ".spotlight-v100",
        ".fseventsd", ".documentrevisions-v100", "system volume information",
        // ES-DE / Batocera media folders (also in RomM's list)
        "images", "covers", "backcovers", "3dboxes", "bezels", "fanart", "manuals", "marquees", "miximages",
        "miximages_v2", "physicalmedia", "screenshots", "thumbnails", "titlescreens", "videos",
        // Other media layouts Fuse reads (never games)
        "media", "downloaded_media", "snap", "snaps", "wheel", "boxart", "box2dfront", "logos",
    )

    /** Extensions RomM ignores, plus partial downloads and emulator saves. */
    val ignoredExtensions: Set<String> = setOf(
        "db", "tmp", "bak", "lock", "log", "cache", "crdownload", "assembling", "part", "partial",
        // saves and save states
        "sav", "srm", "eep", "fla", "mpk", "rtc", "dsv", "state", "auto",
    )

    private val saveStateExtension = Regex("^(state\\d*|st\\d)$")

    /** File names RomM and Fuse ignore, lower case. */
    val ignoredFileNames: Set<String> = setOf(
        ".ds_store", ".localized", ".trashes", ".stfolder", "@synoresource", "gamelist.xml",
        "metadata.pegasus.txt", "thumbs.db", "desktop.ini",
    )

    /** Folder names that are never platforms, so they are not reported as unknown. */
    val nonPlatformFolderNames: Set<String> = setOf(
        "bios", "system", "firmware", "saves", "savestates", "states", "gamelists", "themes", "collections",
        "custom_systems", "logs", "config", "configs", "cheats", "playlists", "shaders", "overlays", "es-de",
        "emulationstation", "retroarch", "tools", "roms",
    )

    /**
     * Folder names that suggest a folder groups unrelated games rather than holding one game:
     * region names, release groupings and alphabetical buckets.
     */
    val organisationalFolderNames: Set<String> = buildSet {
        addAll(TagTables.regionNames.keys)
        TagTables.regionCodes.keys.filter { it.length > 1 }.forEach { add(it.lowercase()) }
        addAll(TagTables.videoStandards)
        addAll(
            listOf(
                "hacks", "hack", "romhacks", "rom hacks", "homebrew", "translations", "translated",
                "fan translations", "betas", "beta", "protos", "prototypes", "demos", "unlicensed", "unl",
                "aftermarket", "pirate", "pirates", "misc", "miscellaneous", "other", "others", "favorites",
                "favourites", "favs", "collection", "collections", "games", "new", "archive", "archives",
                "backup", "backups", "imports", "import", "english", "japanese", "multi-disc", "multidisc",
                "multi disc", "single disc", "complete", "all", "a-z", "0-9", "#", "ntsc-u", "ntsc-j",
            ),
        )
    }

    /** Known MAME BIOS sets that sit next to arcade games but are not games. */
    val arcadeBiosSets: Set<String> = setOf(
        "neogeo.zip", "naomi.zip", "naomi2.zip", "naomigd.zip", "awbios.zip", "pgm.zip", "stvbios.zip",
        "skns.zip", "cpzn1.zip", "cpzn2.zip", "qsound.zip", "decocass.zip", "isgsm.zip", "hng64.zip",
        "konamigx.zip", "nss.zip", "megatech.zip", "megaplay.zip",
    )

    /** True for hidden folders, RomM/ES-DE excluded folders and [extra] names. */
    fun isExcludedFolder(name: String, extra: Set<String> = emptySet()): Boolean {
        val n = name.trim().lowercase()
        return n.startsWith(".") || n in excludedFolderNames || n.startsWith(".trash-") ||
            (extra.isNotEmpty() && extra.any { it.equals(n, ignoreCase = true) })
    }

    /** True for files that are never games or game content (temp files, saves, metadata). */
    fun isIgnoredFile(name: String): Boolean {
        val n = name.lowercase()
        if (n in ignoredFileNames || n.startsWith("._") || n.endsWith(":zone.identifier")) return true
        val ext = FsPath.extension(n)
        return ext in ignoredExtensions || saveStateExtension.matches(ext)
    }

    /** True when a folder name reads like an organisational bucket ("Europe", "Hacks", "A"). */
    fun isOrganisationalName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n in organisationalFolderNames || (n.length == 1 && n[0].isLetterOrDigit())
    }

    /** True when [name] is firmware for [platform] (or a MAME BIOS set) and not a game. */
    fun isBiosFile(platform: Platform, name: String): Boolean {
        val n = name.lowercase()
        if (platform.id.value in ARCADE_LIKE && n in arcadeBiosSets) return true
        val bios = platform.bios ?: return false
        return bios.files.any { f -> f.name.lowercase() == n || f.aliases.any { it.lowercase() == n } }
    }

    private val ARCADE_LIKE = setOf("arcade", "neogeoaes")
}
