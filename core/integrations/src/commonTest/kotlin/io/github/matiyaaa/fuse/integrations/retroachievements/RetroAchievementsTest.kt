package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.json
import io.github.matiyaaa.fuse.integrations.param
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class RetroAchievementsTest {

    private val profileJson = """
        {"User":"MaxMilyin","ULID":"00003EMFWR7XB8SDPEHB3K56ZQ","UserPic":"/UserPic/MaxMilyin.png",
         "MemberSince":"2016-01-02 00:43:04","RichPresenceMsg":"Playing Super Metroid",
         "LastGameID":19504,"ContribCount":0,"ContribYield":0,"TotalPoints":"399597","TotalSoftcorePoints":0,
         "TotalTruePoints":1599212,"Permissions":1,"Untracked":0,"ID":16446,"UserWallActive":1,
         "Motto":"Join me on Twitch!"}
    """.trimIndent()

    // Odd types on purpose: "Awarded" as a map, "RecentAchievements" as nested maps, numbers as strings.
    private val summaryJson = """
        {"User":"MaxMilyin","ULID":"00003EMFWR7XB8SDPEHB3K56ZQ","MemberSince":"2016-01-02 00:43:04",
         "RichPresenceMsg":"","LastGameID":"19504","TotalPoints":399597,"TotalSoftcorePoints":null,"TotalTruePoints":"1599212",
         "Motto":"","Rank":"4","TotalRanked":55432,"Status":"Online","UserPic":"/UserPic/MaxMilyin.png",
         "RecentlyPlayedCount":1,
         "RecentlyPlayed":[{"GameID":"19504","ConsoleID":3,"ConsoleName":"SNES/Super Famicom","Title":"Super Metroid",
            "ImageIcon":"/Images/079931.png","LastPlayed":"2023-12-19 02:51:40","AchievementsTotal":"99"}],
         "Awarded":{"19504":{"NumPossibleAchievements":99,"PossibleScore":"1110","NumAchieved":34,"ScoreAchieved":358,
            "NumAchievedHardcore":"34","ScoreAchievedHardcore":358}},
         "RecentAchievements":{"19504":{"348421":{"ID":"348421","GameID":19504,"GameTitle":"Super Metroid",
            "Title":"Morph Ball","Description":"Get the Morph Ball","Points":"10","Type":null,"BadgeName":391331,
            "IsAwarded":"1","DateAwarded":"2023-12-19 02:47:20","HardcoreAchieved":1}}}}
    """.trimIndent()

    private val emptySummaryJson = """
        {"User":"Newbie","ULID":null,"TotalPoints":0,"Rank":null,"RecentlyPlayed":[],"Awarded":[],"RecentAchievements":[]}
    """.trimIndent()

    private val gameProgressJson = """
        {"ID":1,"Title":"Sonic the Hedgehog","ConsoleID":1,"ForumTopicID":112,"Flags":0,
         "ImageIcon":"/Images/085573.png","ImageTitle":"/Images/064152.png","ImageIngame":"/Images/000010.png",
         "ImageBoxArt":"/Images/051872.png","Publisher":"Sega","Developer":"Sonic Team","Genre":"Platforming",
         "Released":"1991-06-23","IsFinal":false,"ConsoleName":"Genesis/Mega Drive","ParentGameID":null,
         "NumDistinctPlayers":"27080","NumAchievements":3,
         "Achievements":{
           "9":{"ID":9,"NumAwarded":24273,"NumAwardedHardcore":10831,"Title":"That Was Easy",
                "Description":"Complete the first act in Green Hill Zone","Points":3,"TrueRatio":3,"Author":"Scott",
                "DateModified":"2023-08-08 00:36:59","DateCreated":"2012-11-02 00:03:12","BadgeName":"250336",
                "DisplayOrder":1,"MemAddr":"0xH00fe10=1","Type":"progression",
                "DateEarnedHardcore":"2016-04-23 02:21:37","DateEarned":"2016-04-23 02:21:37"},
           "10":{"ID":"10","NumAwarded":"20000","NumAwardedHardcore":9000,"Title":"Speed Demon",
                "Description":"Finish Green Hill Act 2 in 30 seconds","Points":"10","TrueRatio":"25",
                "BadgeName":250337,"DisplayOrder":"0","Type":null,"DateEarned":"2016-04-24 10:00:00"},
           "11":{"ID":11,"Title":"Emerald","Description":"Collect a Chaos Emerald","Points":5,"BadgeName":"250338",
                "DisplayOrder":2}
         },
         "NumAwardedToUser":2,"NumAwardedToUserHardcore":1,"UserCompletion":"66.67%",
         "UserCompletionHardcore":"33.33%","HighestAwardKind":"beaten-hardcore","HighestAwardDate":"2024-04-23T21:28:49+00:00"}
    """.trimIndent()

    private val recentJson = """
        [{"Date":"2023-12-19 02:47:20","HardcoreMode":1,"AchievementID":348421,"Title":"Morph Ball",
          "Description":"Get the Morph Ball","BadgeName":"391331","Points":10,"TrueRatio":35,"Type":null,
          "Author":"someone","GameTitle":"Super Metroid","GameIcon":"/Images/079931.png","GameID":19504,
          "ConsoleName":"SNES/Super Famicom","BadgeURL":"/Badge/391331.png","GameURL":"/game/19504"},
         {"Date":"2023-12-19 03:00:00","HardcoreMode":"0","AchievementID":"348422","Title":"Bombs",
          "Description":"Get the Bombs","BadgeName":"391332","Points":"5","GameTitle":"Super Metroid",
          "GameIcon":"/Images/079931.png","GameID":"19504","ConsoleName":"SNES/Super Famicom"}]
    """.trimIndent()

    private fun client(http: TestHttp) =
        RetroAchievementsClient(http.client, RaCredentials("MaxMilyin", "RA_SECRET_KEY_42"), RateLimiter.unlimited(), clock = { 1_000L })

    @Test
    fun profileRequestAndMapping() = runTest {
        val http = TestHttp { json(profileJson) }
        val user = client(http).verifyCredentials()
        assertIs<ApiResult.Success<*>>(user)
        val u = (user as ApiResult.Success).value
        assertEquals("MaxMilyin", u.username)
        assertEquals("00003EMFWR7XB8SDPEHB3K56ZQ", u.ulid)
        assertEquals("https://media.retroachievements.org/UserPic/MaxMilyin.png", u.avatarUrl)
        assertEquals(399597, u.points)
        assertEquals(1599212, u.truePoints)
        assertEquals("Playing Super Metroid", u.richPresence)
        assertEquals(1_000L, u.fetchedAt)
        val req = http.last
        assertTrue(req.url.toString().startsWith("https://retroachievements.org/API/API_GetUserProfile.php"))
        assertEquals("MaxMilyin", req.param("u"))
        assertEquals("RA_SECRET_KEY_42", req.param("y"))
    }

    @Test
    fun summaryToleratesStringsNullsAndMaps() = runTest {
        val http = TestHttp { json(summaryJson) }
        val summary = (client(http).userSummary(recentGames = 3, recentAchievements = 5) as ApiResult.Success).value
        assertEquals(4, summary.rank)
        assertEquals(0, summary.totalSoftcorePoints)
        assertEquals(19504, summary.lastGameId)
        assertEquals(1, summary.recentlyPlayed.size)
        assertEquals(99, summary.recentlyPlayed[0].achievementsTotal)
        assertEquals(1110, summary.awarded.getValue("19504").possibleScore)
        assertEquals(34, summary.awarded.getValue("19504").numAchievedHardcore)
        val ach = summary.recentAchievements.single()
        assertEquals(348421, ach.id)
        assertEquals("391331", ach.badgeName)
        assertTrue(ach.isAwarded)
        assertTrue(ach.hardcoreAchieved)
        assertEquals("3", http.last.param("g"))
        assertEquals("5", http.last.param("a"))
        val user = summary.toAchievementUser(5)
        assertEquals(4, user.rank)
        assertNull(user.richPresence)
    }

    @Test
    fun emptyMapsSentAsArraysDecode() = runTest {
        val http = TestHttp { json(emptySummaryJson) }
        val summary = (client(http).userSummary() as ApiResult.Success).value
        assertTrue(summary.awarded.isEmpty())
        assertTrue(summary.recentAchievements.isEmpty())
        assertNull(summary.rank)
    }

    @Test
    fun gameProgressMapsToModel() = runTest {
        val http = TestHttp { json(gameProgressJson) }
        val state = (client(http).gameProgress(1) as ApiResult.Success).value
        assertEquals("1", http.last.param("g"))
        assertEquals("1", http.last.param("a"))
        assertEquals("MaxMilyin", http.last.param("u"))
        assertEquals(1, state.raGameId)
        assertEquals("Genesis/Mega Drive", state.consoleName)
        assertEquals("https://media.retroachievements.org/Images/085573.png", state.iconUrl)
        assertEquals(3, state.total)
        assertEquals(2, state.earned)
        assertEquals(1, state.earnedHardcore)
        assertEquals(18, state.points)
        assertEquals(13, state.pointsEarned)
        assertEquals("beaten-hardcore", state.highestAward)
        // Display order: 0, 1, 2.
        assertEquals(listOf(10L, 9L, 11L), state.achievements.map { it.id })
        val first = state.achievements.first()
        assertEquals("https://media.retroachievements.org/Badge/250337.png", first.badgeUrl)
        assertEquals("https://media.retroachievements.org/Badge/250337_lock.png", first.badgeLockedUrl)
        assertTrue(first.earned)
        assertNull(first.earnedHardcoreAt)
        val locked = state.achievements.last()
        assertFalse(locked.earned)
        assertEquals(1_461_378_097_000L, state.achievements[1].earnedHardcoreAt)
    }

    @Test
    fun recentAchievementsMapNewestFirst() = runTest {
        val http = TestHttp { json(recentJson) }
        val recent = (client(http).recentAchievements(minutes = 120) as ApiResult.Success).value
        assertEquals("120", http.last.param("m"))
        assertEquals(listOf(348422L, 348421L), recent.map { it.achievement.id })
        val hard = recent.last()
        assertTrue(hard.hardcore)
        assertEquals(hard.earnedAt, hard.achievement.earnedHardcoreAt)
        assertEquals("https://media.retroachievements.org/Badge/391331.png", hard.achievement.badgeUrl)
        assertEquals("https://media.retroachievements.org/Images/079931.png", hard.gameIconUrl)
        val soft = recent.first()
        assertFalse(soft.hardcore)
        assertNull(soft.achievement.earnedHardcoreAt)
        assertEquals(5, soft.achievement.points)
    }

    @Test
    fun completionProgressPaginates() = runTest {
        val http = TestHttp { request ->
            val offset = request.url.parameters["o"]!!.toInt()
            val entries = (0 until if (offset == 0) 2 else 1).joinToString(",") { i ->
                """{"GameID":${offset + i + 1},"Title":"Game ${offset + i}","ConsoleID":1,"ConsoleName":"Genesis/Mega Drive",
                   "MaxPossible":"10","NumAwarded":5,"NumAwardedHardcore":"5","HighestAwardKind":null}"""
            }
            json("""{"Count":${if (offset == 0) 2 else 1},"Total":3,"Results":[$entries]}""")
        }
        val raClient = client(http)
        val all = (raClient.allCompletionProgress() as ApiResult.Success).value
        assertEquals(3, all.size)
        assertEquals(listOf("0", "2"), http.requests.map { it.url.parameters["o"] })
        assertEquals("500", http.requests.first().url.parameters["c"])
    }

    @Test
    fun awardsRecentlyPlayedHashesConsoles() = runTest {
        val http = TestHttp { request ->
            when {
                "GetUserAwards" in request.url.encodedPath -> json(
                    """{"TotalAwardsCount":"2","MasteryAwardsCount":1,"VisibleUserAwards":[
                        {"AwardedAt":"2022-08-26T19:34:43.000000Z","AwardType":"Mastery/Completion","AwardData":802,
                         "AwardDataExtra":1,"DisplayOrder":114,"Title":"WarioWare, Inc.","ConsoleID":5,
                         "ConsoleName":"Game Boy Advance","Flags":null,"ImageIcon":"/Images/067131.png"}]}""",
                )
                "GetUserRecentlyPlayedGames" in request.url.encodedPath -> json(
                    """[{"GameID":11332,"ConsoleID":12,"ConsoleName":"PlayStation","Title":"Final Fantasy Origins",
                        "LastPlayed":"2023-12-18 01:54:01","NumPossibleAchievements":"120","NumAchieved":5}]""",
                )
                "GetGameHashes" in request.url.encodedPath -> json(
                    """{"Results":[{"Name":"Sonic The Hedgehog (USA, Europe).md","MD5":"1BC674BE034E43C96B86487AC69D9293",
                        "Labels":["nointro"],"PatchUrl":null}]}""",
                )
                else -> json(
                    """[{"ID":1,"Name":"Genesis/Mega Drive","IconURL":"https://static.retroachievements.org/assets/images/system/md.png",
                        "Active":true,"IsGameSystem":"1"}]""",
                )
            }
        }
        val c = client(http)
        val awards = (c.userAwards() as ApiResult.Success).value
        assertEquals(2, awards.totalAwardsCount)
        assertEquals(802, awards.visibleUserAwards.single().awardData)
        val played = (c.recentlyPlayed(count = 99) as ApiResult.Success).value
        assertEquals("50", http.last.param("c"))
        assertEquals(120, played.single().numPossibleAchievements)
        val hashes = (c.gameHashes(1) as ApiResult.Success).value
        assertEquals("1", http.last.param("i"))
        assertEquals(listOf("nointro"), hashes.single().labels)
        val consoles = (c.consoleIds() as ApiResult.Success).value
        assertEquals(true, consoles.single().isGameSystem)
        assertEquals("1", http.last.param("a"))
        assertEquals("1", http.last.param("g"))
    }

    @Test
    fun gameListIndexesHashesForMatching() = runTest {
        val http = TestHttp {
            json(
                """[{"Title":"Sonic the Hedgehog","ID":1,"ConsoleID":1,"ConsoleName":"Genesis/Mega Drive",
                     "ImageIcon":"/Images/085573.png","NumAchievements":23,"NumLeaderboards":"4","Points":400,
                     "Hashes":["1BC674BE034E43C96B86487AC69D9293","09dadb5071eb35050067a32462e39c5f"]},
                    {"Title":"Sonic 2","ID":"2","ConsoleID":1,"Hashes":null}]""",
            )
        }
        val list = (client(http).gameList(1) as ApiResult.Success).value
        assertEquals("1", http.last.param("i"))
        assertEquals("1", http.last.param("f"))
        assertEquals("1", http.last.param("h"))
        val matcher = RaGameMatcher(list)
        assertEquals(2, matcher.size)
        assertEquals(1L, matcher.gameIdFor("1bc674be034e43c96b86487ac69d9293"))
        assertEquals(1L, matcher.gameIdFor("09DADB5071EB35050067A32462E39C5F"))
        assertNull(matcher.gameIdFor("00000000000000000000000000000000"))
    }

    @Test
    fun embeddedErrorBecomesAuthError() = runTest {
        val http = TestHttp { json("""{"Error":"Invalid API Key"}""") }
        val result = client(http).userProfile()
        assertIs<ApiResult.AuthError>(result)
        assertFalse("RA_SECRET_KEY_42" in result.message)
    }

    @Test
    fun unauthorizedStatusIsAuthError() = runTest {
        val http = TestHttp { json("""{"message":"Unauthenticated."}""", HttpStatusCode.Unauthorized) }
        assertIs<ApiResult.AuthError>(client(http).verifyCredentials())
    }

    @Test
    fun cachePolicyTtls() {
        assertEquals(10.minutes, RaCachePolicy.PROFILE)
        assertEquals(2.minutes, RaCachePolicy.RECENT_ACHIEVEMENTS)
        assertEquals(15.minutes, RaCachePolicy.GAME_PROGRESS)
        assertTrue(RaCachePolicy.isFresh(fetchedAt = 0, now = 60_000, ttl = RaCachePolicy.RECENT_ACHIEVEMENTS))
        assertFalse(RaCachePolicy.isFresh(fetchedAt = 0, now = 121_000, ttl = RaCachePolicy.RECENT_ACHIEVEMENTS))
    }

    @Test
    fun defaultLimiterIsConservative() = runTest {
        val limiter = RetroAchievementsClient.defaultLimiter()
        assertEquals(400, limiter.minIntervalMillis)
        assertEquals(1, limiter.maxConcurrency)
    }
}
