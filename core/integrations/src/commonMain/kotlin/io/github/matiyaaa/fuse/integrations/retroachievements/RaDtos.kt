package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.FlexBoolean
import io.github.matiyaaa.fuse.integrations.FlexListSerializer
import io.github.matiyaaa.fuse.integrations.FlexLong
import io.github.matiyaaa.fuse.integrations.FlexString
import io.github.matiyaaa.fuse.integrations.LooseBoolean
import io.github.matiyaaa.fuse.integrations.LooseInt
import io.github.matiyaaa.fuse.integrations.LooseLong
import io.github.matiyaaa.fuse.integrations.decodeFlexList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject

// Response shapes of the RetroAchievements Web API (api-docs.retroachievements.org). Every field has
// a default and numeric fields go through tolerant serializers: RA mixes numbers and numeric strings,
// sends null for missing values, and encodes empty id-keyed maps as [].

/** API_GetUserProfile. Image paths are relative; see [RaMedia]. */
@Serializable
data class RaUserProfile(
    @SerialName("User") val user: String = "",
    @SerialName("ULID") val ulid: String? = null,
    @SerialName("UserPic") val userPic: String? = null,
    @SerialName("MemberSince") val memberSince: String? = null,
    @SerialName("RichPresenceMsg") val richPresenceMsg: String? = null,
    @SerialName("LastGameID") @Serializable(with = FlexLong::class) val lastGameId: Long? = null,
    @SerialName("TotalPoints") @Serializable(with = LooseLong::class) val totalPoints: Long = 0,
    @SerialName("TotalSoftcorePoints") @Serializable(with = LooseLong::class) val totalSoftcorePoints: Long = 0,
    @SerialName("TotalTruePoints") @Serializable(with = LooseLong::class) val totalTruePoints: Long = 0,
    @SerialName("Permissions") @Serializable(with = LooseInt::class) val permissions: Int = 0,
    @SerialName("Untracked") @Serializable(with = LooseBoolean::class) val untracked: Boolean = false,
    @SerialName("ID") @Serializable(with = FlexLong::class) val id: Long? = null,
    @SerialName("Motto") val motto: String? = null,
)

/** One entry of API_GetUserSummary's "RecentlyPlayed" and of API_GetUserRecentlyPlayedGames. */
@Serializable
data class RaRecentlyPlayedGame(
    @SerialName("GameID") @Serializable(with = LooseLong::class) val gameId: Long = 0,
    @SerialName("ConsoleID") @Serializable(with = LooseInt::class) val consoleId: Int = 0,
    @SerialName("ConsoleName") val consoleName: String? = null,
    @SerialName("Title") val title: String = "",
    @SerialName("ImageIcon") val imageIcon: String? = null,
    @SerialName("ImageTitle") val imageTitle: String? = null,
    @SerialName("ImageIngame") val imageIngame: String? = null,
    @SerialName("ImageBoxArt") val imageBoxArt: String? = null,
    @SerialName("LastPlayed") val lastPlayed: String? = null,
    @SerialName("AchievementsTotal") @Serializable(with = LooseInt::class) val achievementsTotal: Int = 0,
    @SerialName("NumPossibleAchievements") @Serializable(with = LooseInt::class) val numPossibleAchievements: Int = 0,
    @SerialName("PossibleScore") @Serializable(with = LooseInt::class) val possibleScore: Int = 0,
    @SerialName("NumAchieved") @Serializable(with = LooseInt::class) val numAchieved: Int = 0,
    @SerialName("ScoreAchieved") @Serializable(with = LooseInt::class) val scoreAchieved: Int = 0,
    @SerialName("NumAchievedHardcore") @Serializable(with = LooseInt::class) val numAchievedHardcore: Int = 0,
    @SerialName("ScoreAchievedHardcore") @Serializable(with = LooseInt::class) val scoreAchievedHardcore: Int = 0,
)

/** Per-game totals in API_GetUserSummary's "Awarded" map. */
@Serializable
data class RaSummaryAward(
    @SerialName("NumPossibleAchievements") @Serializable(with = LooseInt::class) val numPossibleAchievements: Int = 0,
    @SerialName("PossibleScore") @Serializable(with = LooseInt::class) val possibleScore: Int = 0,
    @SerialName("NumAchieved") @Serializable(with = LooseInt::class) val numAchieved: Int = 0,
    @SerialName("ScoreAchieved") @Serializable(with = LooseInt::class) val scoreAchieved: Int = 0,
    @SerialName("NumAchievedHardcore") @Serializable(with = LooseInt::class) val numAchievedHardcore: Int = 0,
    @SerialName("ScoreAchievedHardcore") @Serializable(with = LooseInt::class) val scoreAchievedHardcore: Int = 0,
)

/** An achievement inside API_GetUserSummary's "RecentAchievements" (game id -> achievement id -> this). */
@Serializable
data class RaSummaryAchievement(
    @SerialName("ID") @Serializable(with = LooseLong::class) val id: Long = 0,
    @SerialName("GameID") @Serializable(with = LooseLong::class) val gameId: Long = 0,
    @SerialName("GameTitle") val gameTitle: String = "",
    @SerialName("Title") val title: String = "",
    @SerialName("Description") val description: String = "",
    @SerialName("Points") @Serializable(with = LooseInt::class) val points: Int = 0,
    @SerialName("Type") val type: String? = null,
    @SerialName("BadgeName") @Serializable(with = FlexString::class) val badgeName: String? = null,
    @SerialName("IsAwarded") @Serializable(with = LooseBoolean::class) val isAwarded: Boolean = false,
    @SerialName("DateAwarded") val dateAwarded: String? = null,
    @SerialName("HardcoreAchieved") @Serializable(with = LooseBoolean::class) val hardcoreAchieved: Boolean = false,
)

/** API_GetUserSummary. [awarded] is keyed by game id (as a string). */
@Serializable
data class RaUserSummary(
    @SerialName("User") val user: String = "",
    @SerialName("ULID") val ulid: String? = null,
    @SerialName("UserPic") val userPic: String? = null,
    @SerialName("MemberSince") val memberSince: String? = null,
    @SerialName("RichPresenceMsg") val richPresenceMsg: String? = null,
    @SerialName("LastGameID") @Serializable(with = FlexLong::class) val lastGameId: Long? = null,
    @SerialName("TotalPoints") @Serializable(with = LooseLong::class) val totalPoints: Long = 0,
    @SerialName("TotalSoftcorePoints") @Serializable(with = LooseLong::class) val totalSoftcorePoints: Long = 0,
    @SerialName("TotalTruePoints") @Serializable(with = LooseLong::class) val totalTruePoints: Long = 0,
    @SerialName("Motto") val motto: String? = null,
    @SerialName("Rank") @Serializable(with = FlexLong::class) val rank: Long? = null,
    @SerialName("TotalRanked") @Serializable(with = FlexLong::class) val totalRanked: Long? = null,
    @SerialName("Status") val status: String? = null,
    @SerialName("RecentlyPlayed") @Serializable(with = RaRecentlyPlayedList::class)
    val recentlyPlayed: List<RaRecentlyPlayedGame> = emptyList(),
    @SerialName("Awarded") @Serializable(with = RaSummaryAwardMap::class)
    val awarded: Map<String, RaSummaryAward> = emptyMap(),
    @SerialName("RecentAchievements") @Serializable(with = RaSummaryRecentList::class)
    val recentAchievements: List<RaSummaryAchievement> = emptyList(),
)

/** API_GetUserRecentAchievements entry. */
@Serializable
data class RaRecentAchievement(
    @SerialName("Date") val date: String? = null,
    @SerialName("HardcoreMode") @Serializable(with = LooseBoolean::class) val hardcoreMode: Boolean = false,
    @SerialName("AchievementID") @Serializable(with = LooseLong::class) val achievementId: Long = 0,
    @SerialName("Title") val title: String = "",
    @SerialName("Description") val description: String = "",
    @SerialName("BadgeName") @Serializable(with = FlexString::class) val badgeName: String? = null,
    @SerialName("Points") @Serializable(with = LooseInt::class) val points: Int = 0,
    @SerialName("TrueRatio") @Serializable(with = LooseInt::class) val trueRatio: Int = 0,
    @SerialName("Type") val type: String? = null,
    @SerialName("Author") val author: String? = null,
    @SerialName("GameTitle") val gameTitle: String = "",
    @SerialName("GameIcon") val gameIcon: String? = null,
    @SerialName("GameID") @Serializable(with = LooseLong::class) val gameId: Long = 0,
    @SerialName("ConsoleName") val consoleName: String? = null,
    @SerialName("BadgeURL") val badgeUrl: String? = null,
    @SerialName("GameURL") val gameUrl: String? = null,
)

/** An achievement inside API_GetGameInfoAndUserProgress. */
@Serializable
data class RaGameAchievement(
    @SerialName("ID") @Serializable(with = LooseLong::class) val id: Long = 0,
    @SerialName("NumAwarded") @Serializable(with = LooseInt::class) val numAwarded: Int = 0,
    @SerialName("NumAwardedHardcore") @Serializable(with = LooseInt::class) val numAwardedHardcore: Int = 0,
    @SerialName("Title") val title: String = "",
    @SerialName("Description") val description: String = "",
    @SerialName("Points") @Serializable(with = LooseInt::class) val points: Int = 0,
    @SerialName("TrueRatio") @Serializable(with = LooseInt::class) val trueRatio: Int = 0,
    @SerialName("Author") val author: String? = null,
    @SerialName("BadgeName") @Serializable(with = FlexString::class) val badgeName: String? = null,
    @SerialName("DisplayOrder") @Serializable(with = LooseInt::class) val displayOrder: Int = 0,
    @SerialName("Type") val type: String? = null,
    @SerialName("DateEarned") val dateEarned: String? = null,
    @SerialName("DateEarnedHardcore") val dateEarnedHardcore: String? = null,
)

/** API_GetGameInfoAndUserProgress (requested with a=1 so the highest award is included). */
@Serializable
data class RaGameProgress(
    @SerialName("ID") @Serializable(with = LooseLong::class) val id: Long = 0,
    @SerialName("Title") val title: String = "",
    @SerialName("ConsoleID") @Serializable(with = LooseInt::class) val consoleId: Int = 0,
    @SerialName("ConsoleName") val consoleName: String? = null,
    @SerialName("ParentGameID") @Serializable(with = FlexLong::class) val parentGameId: Long? = null,
    @SerialName("ImageIcon") val imageIcon: String? = null,
    @SerialName("ImageTitle") val imageTitle: String? = null,
    @SerialName("ImageIngame") val imageIngame: String? = null,
    @SerialName("ImageBoxArt") val imageBoxArt: String? = null,
    @SerialName("Publisher") val publisher: String? = null,
    @SerialName("Developer") val developer: String? = null,
    @SerialName("Genre") val genre: String? = null,
    @SerialName("Released") val released: String? = null,
    @SerialName("NumDistinctPlayers") @Serializable(with = LooseInt::class) val numDistinctPlayers: Int = 0,
    @SerialName("NumAchievements") @Serializable(with = LooseInt::class) val numAchievements: Int = 0,
    @SerialName("Achievements") @Serializable(with = RaGameAchievementList::class)
    val achievements: List<RaGameAchievement> = emptyList(),
    @SerialName("NumAwardedToUser") @Serializable(with = LooseInt::class) val numAwardedToUser: Int = 0,
    @SerialName("NumAwardedToUserHardcore") @Serializable(with = LooseInt::class) val numAwardedToUserHardcore: Int = 0,
    @SerialName("UserCompletion") @Serializable(with = FlexString::class) val userCompletion: String? = null,
    @SerialName("UserCompletionHardcore") @Serializable(with = FlexString::class) val userCompletionHardcore: String? = null,
    @SerialName("HighestAwardKind") val highestAwardKind: String? = null,
    @SerialName("HighestAwardDate") val highestAwardDate: String? = null,
)

/** One page of API_GetUserCompletionProgress. */
@Serializable
data class RaCompletionPage(
    @SerialName("Count") @Serializable(with = LooseInt::class) val count: Int = 0,
    @SerialName("Total") @Serializable(with = LooseInt::class) val total: Int = 0,
    @SerialName("Results") @Serializable(with = RaCompletionList::class) val results: List<RaCompletionEntry> = emptyList(),
)

@Serializable
data class RaCompletionEntry(
    @SerialName("GameID") @Serializable(with = LooseLong::class) val gameId: Long = 0,
    @SerialName("Title") val title: String = "",
    @SerialName("ImageIcon") val imageIcon: String? = null,
    @SerialName("ConsoleID") @Serializable(with = LooseInt::class) val consoleId: Int = 0,
    @SerialName("ConsoleName") val consoleName: String? = null,
    @SerialName("MaxPossible") @Serializable(with = LooseInt::class) val maxPossible: Int = 0,
    @SerialName("NumAwarded") @Serializable(with = LooseInt::class) val numAwarded: Int = 0,
    @SerialName("NumAwardedHardcore") @Serializable(with = LooseInt::class) val numAwardedHardcore: Int = 0,
    @SerialName("MostRecentAwardedDate") val mostRecentAwardedDate: String? = null,
    @SerialName("HighestAwardKind") val highestAwardKind: String? = null,
    @SerialName("HighestAwardDate") val highestAwardDate: String? = null,
)

/** API_GetUserAwards. */
@Serializable
data class RaUserAwards(
    @SerialName("TotalAwardsCount") @Serializable(with = LooseInt::class) val totalAwardsCount: Int = 0,
    @SerialName("HiddenAwardsCount") @Serializable(with = LooseInt::class) val hiddenAwardsCount: Int = 0,
    @SerialName("MasteryAwardsCount") @Serializable(with = LooseInt::class) val masteryAwardsCount: Int = 0,
    @SerialName("CompletionAwardsCount") @Serializable(with = LooseInt::class) val completionAwardsCount: Int = 0,
    @SerialName("BeatenHardcoreAwardsCount") @Serializable(with = LooseInt::class) val beatenHardcoreAwardsCount: Int = 0,
    @SerialName("BeatenSoftcoreAwardsCount") @Serializable(with = LooseInt::class) val beatenSoftcoreAwardsCount: Int = 0,
    @SerialName("EventAwardsCount") @Serializable(with = LooseInt::class) val eventAwardsCount: Int = 0,
    @SerialName("SiteAwardsCount") @Serializable(with = LooseInt::class) val siteAwardsCount: Int = 0,
    @SerialName("VisibleUserAwards") @Serializable(with = RaAwardList::class) val visibleUserAwards: List<RaAward> = emptyList(),
)

@Serializable
data class RaAward(
    @SerialName("AwardedAt") val awardedAt: String? = null,
    /** "Mastery/Completion", "Game Beaten", "Event", "Site", ... */
    @SerialName("AwardType") val awardType: String? = null,
    /** The game id for game awards. */
    @SerialName("AwardData") @Serializable(with = FlexLong::class) val awardData: Long? = null,
    /** 1 = hardcore for game awards. */
    @SerialName("AwardDataExtra") @Serializable(with = LooseInt::class) val awardDataExtra: Int = 0,
    @SerialName("DisplayOrder") @Serializable(with = LooseInt::class) val displayOrder: Int = 0,
    @SerialName("Title") val title: String? = null,
    @SerialName("ConsoleID") @Serializable(with = FlexLong::class) val consoleId: Long? = null,
    @SerialName("ConsoleName") val consoleName: String? = null,
    @SerialName("ImageIcon") val imageIcon: String? = null,
)

/** One supported ROM of a game (API_GetGameHashes). */
@Serializable
data class RaGameHash(
    @SerialName("Name") val name: String? = null,
    @SerialName("MD5") val md5: String = "",
    @SerialName("Labels") val labels: List<String> = emptyList(),
    @SerialName("PatchUrl") val patchUrl: String? = null,
)

@Serializable
internal data class RaGameHashesResponse(
    @SerialName("Results") @Serializable(with = RaGameHashList::class) val results: List<RaGameHash> = emptyList(),
)

/** One game of API_GetGameList (requested with h=1 so [hashes] is filled). */
@Serializable
data class RaGameListEntry(
    @SerialName("ID") @Serializable(with = LooseLong::class) val id: Long = 0,
    @SerialName("Title") val title: String = "",
    @SerialName("ConsoleID") @Serializable(with = LooseInt::class) val consoleId: Int = 0,
    @SerialName("ConsoleName") val consoleName: String? = null,
    @SerialName("ImageIcon") val imageIcon: String? = null,
    @SerialName("NumAchievements") @Serializable(with = LooseInt::class) val numAchievements: Int = 0,
    @SerialName("NumLeaderboards") @Serializable(with = LooseInt::class) val numLeaderboards: Int = 0,
    @SerialName("Points") @Serializable(with = LooseInt::class) val points: Int = 0,
    @SerialName("DateModified") val dateModified: String? = null,
    @SerialName("Hashes") val hashes: List<String> = emptyList(),
)

/** API_GetConsoleIDs entry. [iconUrl] is already absolute. */
@Serializable
data class RaConsole(
    @SerialName("ID") @Serializable(with = LooseInt::class) val id: Int = 0,
    @SerialName("Name") val name: String = "",
    @SerialName("IconURL") val iconUrl: String? = null,
    @SerialName("Active") @Serializable(with = FlexBoolean::class) val active: Boolean? = null,
    @SerialName("IsGameSystem") @Serializable(with = FlexBoolean::class) val isGameSystem: Boolean? = null,
)

internal object RaRecentlyPlayedList : FlexListSerializer<RaRecentlyPlayedGame>(RaRecentlyPlayedGame.serializer())
internal object RaGameAchievementList : FlexListSerializer<RaGameAchievement>(RaGameAchievement.serializer())
internal object RaCompletionList : FlexListSerializer<RaCompletionEntry>(RaCompletionEntry.serializer())
internal object RaAwardList : FlexListSerializer<RaAward>(RaAward.serializer())
internal object RaGameHashList : FlexListSerializer<RaGameHash>(RaGameHash.serializer())
internal object RaGameList : FlexListSerializer<RaGameListEntry>(RaGameListEntry.serializer())
internal object RaRecentAchievementList : FlexListSerializer<RaRecentAchievement>(RaRecentAchievement.serializer())
internal object RaConsoleList : FlexListSerializer<RaConsole>(RaConsole.serializer())

/** "Awarded": an object keyed by game id, or [] when empty. */
internal object RaSummaryAwardMap : KSerializer<Map<String, RaSummaryAward>> {
    private val delegate = MapSerializer(String.serializer(), RaSummaryAward.serializer())
    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Map<String, RaSummaryAward> {
        val json = decoder as JsonDecoder
        val element = json.decodeJsonElement() as? JsonObject ?: return emptyMap()
        return element.mapValues { json.json.decodeFromJsonElement(RaSummaryAward.serializer(), it.value) }
    }

    override fun serialize(encoder: Encoder, value: Map<String, RaSummaryAward>) = delegate.serialize(encoder, value)
}

/** "RecentAchievements": game id -> achievement id -> achievement, flattened; [] when empty. */
internal object RaSummaryRecentList : KSerializer<List<RaSummaryAchievement>> {
    private val delegate = kotlinx.serialization.builtins.ListSerializer(RaSummaryAchievement.serializer())
    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<RaSummaryAchievement> {
        val json = decoder as JsonDecoder
        val root = json.decodeJsonElement()
        val perGame = (root as? JsonObject)?.values ?: return emptyList()
        return perGame.flatMap { decodeFlexList(json.json, it, RaSummaryAchievement.serializer()) }
    }

    override fun serialize(encoder: Encoder, value: List<RaSummaryAchievement>) = delegate.serialize(encoder, value)
}
