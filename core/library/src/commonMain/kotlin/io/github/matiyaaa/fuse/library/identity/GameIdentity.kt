package io.github.matiyaaa.fuse.library.identity

import io.github.matiyaaa.fuse.library.parse.FilenameParser

/**
 * Fuse Identity: the one way Fuse decides that a game somewhere else (on RomM, on another device, in
 * a save, on RetroAchievements or HowLongToBeat) is a game in this library.
 *
 * Every part of Fuse that asks "is this that game?" describes both sides as [GameEvidence] and asks
 * [GameMatcher]; every id is written the same way by [Ids]. The strongest evidence decides, a weaker
 * kind never joins two games whose stronger evidence disagrees, and nothing is ever taken twice.
 */

/** Kinds of external id a game can be linked to. A link is the strongest evidence there is. */
enum class LinkKind {
    ROMM_ROM,
    RA_GAME,
    STEAM_APP,
    ANDROID_PACKAGE,
    IGDB,
    STEAMGRIDDB,
    HLTB,
}

/** Kinds of content hash; two hashes only ever compare within their kind. */
enum class HashKind { MD5, SHA1, CRC32, RA }

/** A content hash, lower-case hex. */
data class ContentHash(val kind: HashKind, val value: String) {
    companion object {
        fun of(kind: HashKind, value: String?): ContentHash? =
            value?.trim()?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }?.let { ContentHash(kind, it) }
    }
}

/** A serial (console title id), normalised by [Ids.serial]; one read from the game itself beats one in its file name. */
data class SerialId(val value: String, val fromContent: Boolean = false)

/** The evidence strongest first. Only these ever join two games. */
enum class Evidence {
    /** The same external id (a RomM rom, a RetroAchievements game, a Steam app, an Android package). */
    LINKED,

    /** The same content hash. */
    HASH,

    /** The same console title id (serial, PS4/PS5 content id, Switch title id). */
    SERIAL,

    /** The same file name. */
    FILE_NAME,

    /** The same name with the same region, revision, version, disc and flags, when it is the only one on both sides. */
    RELEASE_NAME,

    /** The same title, whatever its region or version, when it is the only one on both sides. Only at [MatchLevel.WORK]. */
    WORK_TITLE,
}

/**
 * How close two games must be. [RELEASE] is the same copy (a RomM file, a save, a transfer): regions
 * and revisions differ. [WORK] is the same game whatever the copy (how long it takes to beat, a
 * RetroAchievements game, which groups every region's hashes).
 */
enum class MatchLevel { RELEASE, WORK }

/**
 * One game as identity sees it, on either side of a match. [id] is the game's own id on its side (a
 * library game id, a RomM rom id). Titles are the game's own (never a person's rename).
 */
data class GameEvidence(
    val id: Long,
    /** Fuse's platform id the game is on (the one a person chose, when they did). */
    val platform: String,
    /** The game's file or folder name, as on disk (or as the server names it). */
    val fileName: String = "",
    /** The game's title before any rename. */
    val title: String = "",
    val serials: List<SerialId> = emptyList(),
    val hashes: Set<ContentHash> = emptySet(),
    val links: Map<LinkKind, String> = emptyMap(),
    /** The platform the game's folder says, when a person moved it to another. */
    val scannedPlatform: String? = null,
    /** A game whose files aren't there now (a drive that is out); never matched as a copy. */
    val missing: Boolean = false,
) {
    /** The serial identity trusts most: one read from the game itself, else one from its name. */
    val bestSerial: String? get() = (serials.firstOrNull { it.fromContent } ?: serials.firstOrNull())?.value
}

/** One match: [remote] is [local]. */
data class IdentityMatch(val remote: Long, val local: Long, val evidence: Evidence)

/** Ids written one way everywhere. */
object Ids {
    private val SAFE = Regex("[^a-z0-9._-]+")
    private val TAGS = Regex("\\(.*?\\)|\\[.*?]")
    private val PS_ID = Regex("\\b(CUSA|PPSA)[-_ ]?(\\d{5})\\b", RegexOption.IGNORE_CASE)
    private val ARCHIVE = Regex("\\.(zip|7z|rar)$", RegexOption.IGNORE_CASE)

    /** Systems whose games are kept as archives on a server but played from the folder they unpack to. */
    val FOLDER_SYSTEMS: Set<String> = setOf("ps4", "ps5")

    /** A console id written any way ("SLUS-00067", "slus_000.67", "0100ABCD...") the same: letters and digits, lower case. */
    fun serial(id: String): String = id.lowercase().filter { it.isLetterOrDigit() }

    /** [serial] or null when nothing is left. */
    fun serialOrNull(id: String?): String? = id?.let(::serial)?.takeIf { it.isNotEmpty() }

    /** A PS4 or PS5 game's own id in [text] (CUSA12345, PPSA12345), however it is written. */
    fun psId(text: String): String? = PS_ID.find(text)?.let { it.groupValues[1].uppercase() + it.groupValues[2] }

    /** [name] without a trailing archive extension (`.zip`, `.7z`, `.rar`). */
    fun unpacked(name: String): String = name.replace(ARCHIVE, "")

    /** A platform id as it goes into a key. */
    fun platform(id: String): String = id.lowercase().replace(SAFE, "-")

    /**
     * A title whatever its region, revision or version: "Pokemon Ruby (USA)" and "Pokemon Ruby
     * (Europe) (Rev 1)" are both "pokemon-ruby".
     */
    fun workTitle(title: String): String = title.lowercase().replace(TAGS, "").replace(SAFE, "-").trim('-')

    /**
     * A name with what tells copies apart kept (region, revision, version, disc), and what doesn't
     * (case, punctuation, dump flags) dropped: "Metroid Fusion (USA) (Rev 1)" is never "Metroid
     * Fusion (Europe)". Empty when the name has no title.
     */
    fun releaseTitle(name: String): String {
        // A release group's own tag before the name ("[ROMsNS] Zelda") is no part of it.
        val p = FilenameParser.parse(name.replace(GROUP_TAG, ""), hasExtension = name.contains('.'))
        // "&" and "and" are the same name ("Mario & Sonic", "Mario and Sonic"), and a bare base
        // version left at the end of the name ("Red Dead Redemption v0") isn't part of it either.
        val title = p.baseTitle.lowercase().replace("&", " and ").replace(BARE_VERSION, "").filter { it.isLetterOrDigit() }
        if (title.isEmpty()) return ""
        val t = p.tags.let { tags ->
            // A whole number for a version ("[v131072]", "[v65536]") is the update installed on a Switch
            // or 3DS game, not another release of it: the game stays the same game whatever is installed.
            if (tags.version?.all { it.isDigit() } == true) tags.copy(version = null) else tags
        }
        val flags = t.flags.filter { it.lowercase() in DISTINCT_FLAGS }.map { it.lowercase() }.sorted().joinToString(",")
        val parts = listOf(title, t.regions.sorted().joinToString(",").lowercase(), t.revision.orEmpty().lowercase(), t.version.orEmpty().lowercase(), t.discNumber?.toString().orEmpty())
        // Hacks and translations are other games: they never share a name with the original.
        val translation = p.translation?.lowercase().orEmpty()
        return (if (flags.isEmpty() && translation.isEmpty()) parts else parts + flags + translation).joinToString("|")
    }

    private val GROUP_TAG = Regex("^\\s*\\[[^\\]]{2,24}]\\s*(?=\\S)")
    private val BARE_VERSION = Regex("\\s+v\\d+\\s*$")

    /** Flags that make a copy another game, not the same one. */
    private val DISTINCT_FLAGS = setOf("hack", "translated", "beta", "proto", "prototype", "demo", "sample", "homebrew", "unlicensed", "pirate", "aftermarket")
}

/**
 * The household key: who a game is across a person's devices, independent of where its file is.
 * Its form never changes (`platform:s.serial`, `platform:h.hash` or `platform:t.title`), so saves,
 * play time and favourites recorded by any earlier Fuse are still found.
 */
object HouseholdKeys {
    /** The key for a game known by [serial], [contentHash] or [title], in that order of trust. */
    fun of(platform: String, serial: String?, contentHash: String?, title: String): Pair<String, String> {
        val normalizedSerial = Ids.serialOrNull(serial)
        val normalizedHash = contentHash?.lowercase()?.replace(Regex("[^a-z0-9._-]+"), "")?.takeIf { it.isNotEmpty() }
        val identity = when {
            normalizedSerial != null -> "s.$normalizedSerial"
            normalizedHash != null -> "h.$normalizedHash"
            else -> "t." + Ids.workTitle(title)
        }
        return Ids.platform(platform) to identity
    }

    /** The key as one string, `platform:identity`. */
    fun id(platform: String, serial: String?, title: String): String = of(platform, serial, null, title).let { "${it.first}:${it.second}" }

    /** [e]'s key: its best serial (the game's own before its name's), else its title. */
    fun primary(e: GameEvidence): String = id(e.platform, e.bestSerial, e.title)

    /**
     * Every key [e] may have been known by before, the primary first: the key from its file name's
     * serial, from its title, and the same on the system its folder says. Records kept under any of
     * them belong to it, as long as no serial disagrees ([GameMatcher.sameHousehold]).
     */
    fun all(e: GameEvidence): List<String> = buildList {
        add(primary(e))
        for (s in e.serials) add(id(e.platform, s.value, e.title))
        add(id(e.platform, null, e.title))
        e.scannedPlatform?.takeIf { it != e.platform }?.let { scanned ->
            add(id(scanned, e.bestSerial, e.title))
            add(id(scanned, null, e.title))
        }
    }.distinct()

    /** The identity part of a key (`s.slus00067`), or null when it isn't one. */
    fun identityOf(key: String): String? = key.substringAfter(':', "").takeIf { it.isNotEmpty() }
}

/** Which games are which: [match] for many at once, [sameHousehold] for two keys' sets. */
object GameMatcher {
    /**
     * Matches [remotes] to [locals] at [level]. Each local game is matched at most once, and each
     * remote at most once, strongest evidence first across all of them, so a weak guess never takes
     * a game another one is joined to more strongly. [vetoed] are pairs a person said are not the
     * same game (remote id to local id). Missing local games are never matched.
     */
    fun match(
        remotes: List<GameEvidence>,
        locals: List<GameEvidence>,
        level: MatchLevel = MatchLevel.RELEASE,
        vetoed: Set<Pair<Long, Long>> = emptySet(),
    ): List<IdentityMatch> {
        val live = locals.filter { !it.missing }
        val byLink = HashMap<Pair<LinkKind, String>, GameEvidence>()
        live.forEach { g -> g.links.forEach { (k, v) -> byLink.putIfAbsent(k to v, g) } }
        val byHash = live.flatMap { g -> g.hashes.map { it to g } }.groupBy({ it.first }, { it.second })
        val bySerial = live.flatMap { g -> g.serials.map { (g.platform to it.value) to g } }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.distinct() }
        val byFile = live.filter { it.fileName.isNotBlank() }.groupBy { it.platform to it.fileName.lowercase() }
        val byRelease = live.groupBy { it.platform to Ids.releaseTitle(it.fileName.ifBlank { it.title }) }
        val byWork = live.groupBy { it.platform to Ids.workTitle(it.title.ifBlank { it.fileName }) }
        // PS4 and PS5 games carry their own id in a folder, file or serial, however they are kept.
        val byPsId = live.filter { it.platform in Ids.FOLDER_SYSTEMS }
            .mapNotNull { g -> psIdOf(g)?.let { (g.platform to it) to g } }
            .groupBy({ it.first }, { it.second })
        val remotesByRelease = remotes.groupBy { it.platform to Ids.releaseTitle(it.fileName.ifBlank { it.title }) }
        val remotesByWork = remotes.groupBy { it.platform to Ids.workTitle(it.title.ifBlank { it.fileName }) }

        val candidates = ArrayList<IdentityMatch>()
        for (r in remotes) {
            val platform = r.platform
            fun add(local: GameEvidence?, evidence: Evidence) {
                if (local != null && (r.id to local.id) !in vetoed) candidates += IdentityMatch(r.id, local.id, evidence)
            }
            r.links.forEach { (k, v) -> add(byLink[k to v], Evidence.LINKED) }
            r.hashes.firstNotNullOfOrNull { h -> byHash[h]?.singleOrNull { it.platform == platform } }?.let { add(it, Evidence.HASH) }
            r.serials.firstNotNullOfOrNull { s -> bySerial[platform to s.value]?.singleOrNull() }?.let { add(it, Evidence.SERIAL) }
            if (r.fileName.isNotBlank()) {
                byFile[platform to r.fileName.lowercase()]?.singleOrNull()?.takeIf { compatible(r, it) }?.let { add(it, Evidence.FILE_NAME) }
            }
            if (platform in Ids.FOLDER_SYSTEMS) {
                val unpacked = Ids.unpacked(r.fileName).lowercase()
                if (unpacked != r.fileName.lowercase()) byFile[platform to unpacked]?.singleOrNull()?.takeIf { compatible(r, it) }?.let { add(it, Evidence.FILE_NAME) }
                psIdOf(r)?.let { byPsId[platform to it]?.singleOrNull() }?.let { add(it, Evidence.SERIAL) }
            }
            val release = platform to Ids.releaseTitle(r.fileName.ifBlank { r.title })
            val sameRelease = byRelease[release]
            if (release.second.isNotEmpty() && sameRelease != null && sameRelease.size == 1 && remotesByRelease[release]?.size == 1) {
                sameRelease.single().takeIf { compatible(r, it) }?.let { add(it, Evidence.RELEASE_NAME) }
            }
            if (level == MatchLevel.WORK) {
                val work = platform to Ids.workTitle(r.title.ifBlank { r.fileName })
                val sameWork = byWork[work]
                if (work.second.isNotEmpty() && sameWork != null && sameWork.size == 1 && remotesByWork[work]?.size == 1) {
                    sameWork.single().takeIf { compatible(r, it, work = true) }?.let { add(it, Evidence.WORK_TITLE) }
                }
            }
        }
        val claimedRemotes = HashSet<Long>()
        val claimedLocals = HashSet<Long>()
        val out = ArrayList<IdentityMatch>()
        for (c in candidates.sortedBy { it.evidence.ordinal }) {
            if (c.remote in claimedRemotes || c.local in claimedLocals) continue
            claimedRemotes += c.remote
            claimedLocals += c.local
            out += c
        }
        return out
    }

    /** The one local game [remote] is, at [level], or null. */
    fun find(remote: GameEvidence, locals: List<GameEvidence>, level: MatchLevel = MatchLevel.RELEASE, vetoed: Set<Long> = emptySet()): IdentityMatch? =
        match(listOf(remote), locals, level, vetoed.map { remote.id to it }.toSet()).firstOrNull()

    /**
     * Names and titles never overrule stronger evidence that disagrees: a different link of the
     * same kind, a different serial, or hashes of the same kind with none in common. At the work
     * level, serials and hashes may differ (regions differ in both), but links may not.
     */
    fun compatible(remote: GameEvidence, local: GameEvidence, work: Boolean = false): Boolean {
        for ((k, v) in remote.links) {
            val mine = local.links[k] ?: continue
            if (mine != v) return false
        }
        if (work) return true
        val remoteId = remote.bestSerial ?: if (remote.platform in Ids.FOLDER_SYSTEMS) psIdOf(remote)?.let(Ids::serial) else null
        val localId = local.bestSerial ?: if (local.platform in Ids.FOLDER_SYSTEMS) psIdOf(local)?.let(Ids::serial) else null
        if (remoteId != null && localId != null && remoteId != localId) return false
        val kinds = remote.hashes.map { it.kind }.toSet() intersect local.hashes.map { it.kind }.toSet()
        for (kind in kinds) {
            val a = remote.hashes.filter { it.kind == kind }.toSet()
            val b = local.hashes.filter { it.kind == kind }.toSet()
            if ((a intersect b).isEmpty()) return false
        }
        return true
    }

    /**
     * Whether two sets of household keys are the same game: some key in common, and never two
     * different serials (a title shared by two strongly identified games doesn't join them).
     */
    fun sameHousehold(left: Collection<String>, right: Collection<String>): Boolean {
        val a = strongest(left)
        val b = strongest(right)
        val serialsA = a.filter { HouseholdKeys.identityOf(it)?.startsWith("s.") == true }
        val serialsB = b.filter { HouseholdKeys.identityOf(it)?.startsWith("s.") == true }
        if (serialsA.isNotEmpty() && serialsB.isNotEmpty() && serialsA.none { it in serialsB }) return false
        return a.any { it in b }
    }

    /**
     * The keys that identify: serial and hash keys when there are any (a title key can't bridge two
     * strongly identified games), else all of them. [serialKey] limits serial keys to that one.
     */
    fun strongest(keys: Collection<String>, serialKey: String? = null): List<String> {
        val strong = keys.filter { key ->
            val identity = HouseholdKeys.identityOf(key).orEmpty()
            identity.startsWith("h.") || (identity.startsWith("s.") && (serialKey == null || key == serialKey))
        }
        return (listOfNotNull(serialKey) + strong).distinct().ifEmpty { keys.distinct() }
    }

    private fun psIdOf(g: GameEvidence): String? =
        (g.serials.map { it.value } + listOf(g.fileName, g.title)).firstNotNullOfOrNull { Ids.psId(it) }
}
