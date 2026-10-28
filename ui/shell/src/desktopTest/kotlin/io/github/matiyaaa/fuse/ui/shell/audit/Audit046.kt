package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameJournal
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.PlayStatus
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.LibraryOps
import io.github.matiyaaa.fuse.ui.shell.store.ResumeInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val HOUR = 60 * 60_000L
private const val DAY = 24 * HOUR

/** What 0.4.6 adds: Continue on a game's page, the Journal, Your Year, the people you follow and Make Room. */
internal fun AuditDriver.fuse046Screens() {
    scenario("achievements", "the week and the people you follow") {
        useLibrary()
        val base = libraryStore
        val now = System.currentTimeMillis()
        fun state(title: String, console: String, earned: Int, total: Int) =
            io.github.matiyaaa.fuse.model.AchievementState(title.hashCode().toLong(), title, console, null, total, earned, earned, total * 5, earned * 5, null, fetchedAt = now)
        val feed = io.github.matiyaaa.fuse.ui.shell.store.AchievementsFeed(
            user = io.github.matiyaaa.fuse.model.AchievementUser("sample", null, null, 1_200, 0, 1_500, 4_200, null, null, now),
            recent = emptyList(),
            inProgress = listOf(state("Velvet Orbit", "Dreamcast", 34, 52), state("Beacon Bay", "Game Boy Advance", 12, 40)),
            recentlyMastered = emptyList(),
        )
        val achievements = object : io.github.matiyaaa.fuse.ui.shell.store.AchievementOps by base.achievements {
            override val configured = kotlinx.coroutines.flow.MutableStateFlow(true)
            override val feed = kotlinx.coroutines.flow.MutableStateFlow<io.github.matiyaaa.fuse.ui.shell.store.AchievementsFeed?>(feed)
            override fun refresh(force: Boolean) {}
            override suspend fun week() = io.github.matiyaaa.fuse.ui.shell.store.WeekAchievement(
                "Lighthouse Keeper", "Ring the bell before lighting the lamp, in chapter three", 25,
                "Last Ferry Home", "PlayStation", null, null, unlocks = 1_284, players = 9_420, earned = false, localGame = null,
            )
            override suspend fun following() = listOf(
                io.github.matiyaaa.fuse.ui.shell.store.FollowedPlayer("Amaranth", 48_210, followsYou = true, avatarUrl = "", game = "Pokemon Emerald", presence = "Route 119, 6 badges"),
                io.github.matiyaaa.fuse.ui.shell.store.FollowedPlayer("kestrel", 12_005, followsYou = false, avatarUrl = "", game = "Metroid Fusion"),
                io.github.matiyaaa.fuse.ui.shell.store.FollowedPlayer("oldbyte", 3_420, followsYou = true, avatarUrl = ""),
            )
        }
        show(object : FuseStore by base { override val achievements = achievements })
        tab(io.github.matiyaaa.fuse.model.Destination.ACHIEVEMENTS)
        waitFor("Achievement of the week")
        settle(1_500)
        shoot("the week's achievement and the people you follow")
    }
    scenario("storage", "make room") {
        useLibrary()
        val base = libraryStore
        val cards = runBlocking { base.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first { it.size >= 6 } }
        val now = System.currentTimeMillis()
        val storage = object : io.github.matiyaaa.fuse.ui.shell.store.StorageOps by base.storage {
            override suspend fun roomToMake() = cards.take(5).mapIndexed { i, g ->
                io.github.matiyaaa.fuse.ui.shell.store.RoomSuggestion(
                    io.github.matiyaaa.fuse.ui.shell.store.GameSize(g, (8L - i) * 900_000_000L, 2),
                    if (i % 2 == 0) "RomM" else "Steam Deck", if (i == 3) null else now - (4L + i) * 30 * DAY,
                )
            }
        }
        show(object : FuseStore by base { override val storage = storage })
        openSettings()
        focusText("Storage and backups")
        tap(PadButton.DPAD_RIGHT)
        tapText("Games and space")
        waitFor("Make room")
        settle(2_000)
        shoot("Make room leads the games")
        focusText("Make room") { tap(PadButton.DPAD_UP) }
        tap(PadButton.A)
        waitFor("Pick all 5")
        shoot("what can go, and where each comes back from", 1_200)
    }
    scenario("recap", "Your Year") {
        // Home's week widget opens Play time, whose first row is this year's recap.
        useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS, board = listOf(HomeWidget("week", WidgetKind.PLAYTIME_WEEK, 0, width = 4, height = 2)))) }
        val base = libraryStore
        val cards = runBlocking { base.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first { it.size >= 8 } }
        val systems = base.library.platforms.value.filter { it.gameCount > 0 }
        val hours = listOf(52L, 31L, 24L, 18L, 11L, 7L, 4L, 2L)
        val year = io.github.matiyaaa.fuse.ui.shell.store.PlayRecap(
            io.github.matiyaaa.fuse.ui.shell.store.RecapPeriod(2026), "2026",
            seconds = 512_400, sessions = 312, days = 128, gameCount = 46,
            bars = listOf(31L, 22L, 48L, 40L, 26L, 61L, 77L, 52L, 44L, 69L, 12L, 0L).map { it * 3600 / 2 },
            barLabels = "JFMAMJJASOND".map { it.toString() }, barNow = 10,
            games = cards.take(8).zip(hours).map { (g, h) -> g to h * 3600 },
            systems = systems.take(4).zip(listOf(61L, 44L, 23L, 9L)).map { (p, h) -> p to h * 3600 },
            longest = io.github.matiyaaa.fuse.ui.shell.store.RecapLongest(cards[1], 4 * 3600 + 12 * 60, "3 Oct"),
            streakDays = 12, streakWords = "3 Oct to 14 Oct",
            firsts = cards.drop(3).take(5), firstCount = 9,
            finished = cards.drop(5).take(2),
            weekday = "Saturdays", partOfDay = "evenings", loaded = true,
        )
        val periods = listOf(
            io.github.matiyaaa.fuse.ui.shell.store.RecapPeriod(2026), io.github.matiyaaa.fuse.ui.shell.store.RecapPeriod(2026, 11),
            io.github.matiyaaa.fuse.ui.shell.store.RecapPeriod(2026, 10), io.github.matiyaaa.fuse.ui.shell.store.RecapPeriod(2025),
        )
        val library = object : LibraryOps by base.library {
            override fun recap(period: io.github.matiyaaa.fuse.ui.shell.store.RecapPeriod) = kotlinx.coroutines.flow.flowOf(
                if (period.month == null) year else year.copy(
                    period = period, title = "October 2026", seconds = 124_200, sessions = 41, days = 19, gameCount = 9,
                    bars = List(31) { d -> if (d % 3 == 1) 0L else ((d * 37) % 11 + 1) * 1800L }, barLabels = (1..31).map { it.toString() }, barNow = null,
                    streakDays = 6, streakWords = "8 Oct to 13 Oct", firstCount = 2, firsts = cards.drop(3).take(2),
                ),
            )
            override suspend fun recapPeriods() = periods
        }
        show(object : FuseStore by base { override val library: LibraryOps = library }, capturePlatform)
        settle(1_500)
        tap(PadButton.DPAD_LEFT)
        tap(PadButton.A)
        waitFor("Your year")
        shoot("Play time leads with the recaps", 1_200)
        tap(PadButton.A)
        waitFor("Longest streak")
        shoot("the year in play", 1_600)
        // Kept as a picture: drawn where nothing shows it, then saved with the screenshots.
        focusText("Save as a picture")
        tap(PadButton.A)
        waitFor("Picture saved")
        shoot("saved as a picture", 600)
        focusText("October")
        tap(PadButton.A)
        waitFor("8 Oct to 13 Oct")
        shoot("a month in play, day by day", 1_600)
    }
    scenario("continue", "a game left mid-play") {
        useLibrary()
        val base = libraryStore
        val now = System.currentTimeMillis()
        // Left two hours ago on the Steam Deck, which DuckStation here carries on from.
        val library = object : LibraryOps by base.library {
            override suspend fun resumePoint(id: GameId) = ResumeInfo("DuckStation", now - 2 * HOUR, "Steam Deck")
        }
        show(object : FuseStore by base { override val library: LibraryOps = library })
        openGame("Last Ferry Home")
        shoot("Continue leads, Start fresh beside it", 1_400)
    }
    scenario("journal", "a game's Journal") {
        withJournals {
            useLibrary()
            openGame("Last Ferry Home")
            shoot("its status beside the heart", 1_200)
            // Play, its emulator, the heart, then the Journal.
            tap(PadButton.DPAD_RIGHT, 3)
            tap(PadButton.A)
            waitFor("Where you are")
            shoot("the Journal sheet", 1_200)
            focusText("Your rating")
            tap(PadButton.A)
            waitFor("Your rating now")
            shoot("rating it in half stars", 1_200)
            tap(PadButton.B)
            tap(PadButton.B)
            // The Journal card among the details, with its stars and a glimpse of the note.
            tap(PadButton.DPAD_DOWN, 3)
            shoot("the Journal card among the details", 1_400)
        }
    }
    scenario("journal", "the Library's Journal") {
        withJournals {
            useLibrary(prefs = { it.copy(libraryOpensOn = "INSTALLED") })
            libraryTab()
            waitFor("Journal")
            libraryFilter(4)
            shoot("every status, playing first", 1_400)
        }
    }
    scenario("journal", "Up next on Home") {
        withJournals {
            val board = listOf(HomeWidget("upnext", WidgetKind.UP_NEXT, 0, width = 4, height = 1), HomeWidget("upnext2", WidgetKind.UP_NEXT, 1, width = 2, height = 2))
            useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS, board = board)) }
            settle(1_500)
            tap(PadButton.DPAD_LEFT)
            shoot("a strip and a square", 1_500)
        }
    }
}

/**
 * A library with a Journal: one game being played (rated, with a note), two wanted, one beaten and
 * one completed; cleared again afterwards, so other scenarios see the library as it was.
 */
private fun AuditDriver.withJournals(block: () -> Unit) {
    val now = System.currentTimeMillis()
    val games = runBlocking { libraryData.games.observeAll().first() }.associateBy { it.displayTitle }
    fun id(title: String) = games[title]?.id ?: throw NotCovered("No $title in the audit library")
    val journals = mapOf(
        id("Last Ferry Home") to GameJournal(PlayStatus.PLAYING, now - 4 * DAY, 9, "Stuck at the lighthouse puzzle. Try the bell before the lamp next time."),
        id("Glasswing Requiem") to GameJournal(PlayStatus.WANT, now - 2 * DAY),
        id("Lumen Drift") to GameJournal(PlayStatus.WANT, now - 20 * DAY, 0, "A friend says the second act is the best part."),
        id("Emberline Saga") to GameJournal(PlayStatus.BEATEN, now - 60 * DAY, 10),
        id("Pixel Pilgrims") to GameJournal(PlayStatus.COMPLETED, now - 120 * DAY, 8),
    )
    runBlocking { for ((g, j) in journals) libraryData.games.setJournal(g, j) }
    try {
        block()
    } finally {
        runBlocking { for (g in journals.keys) libraryData.games.setJournal(g, GameJournal()) }
    }
}
