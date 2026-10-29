package io.github.matiyaaa.fuse.ui.shell.audit

import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
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
    scenario("romm", "collections and Download All") {
        useLibrary { it.copy(romm = it.romm.copy(enabled = true, configured = true)) }
        val base = libraryStore
        val romm = AuditRomm(base)
        show(object : FuseStore by base { override val romm: io.github.matiyaaa.fuse.ui.shell.store.RommOps = romm })
        libraryTab()
        waitFor("Connected")
        settle(900)
        // The sources lead the All page, where it opens: RomM is the second.
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("games on RomM")
        shoot("every game on RomM, its collections first", 1_500)
        tap(PadButton.A)
        waitFor("Download All")
        shoot("a collection's page, with Download All beside its name", 1_500)
        tap(PadButton.DPAD_UP)
        settle(400)
        shoot("Download All chosen", 900)
        tap(PadButton.A)
        waitFor("Download Later")
        shoot("what it takes and where it goes, now or later", 1_200)
        tap(PadButton.B)
        romm.tight = true
        settle(400)
        tap(PadButton.A)
        waitFor("Download What Fits")
        shoot("not all of it fits: what fits, or make room", 1_200)
        tap(PadButton.B)
    }
    scenario("downloads", "kept for later") {
        useLibrary { it.copy(romm = it.romm.copy(enabled = true, configured = true)) }
        val base = libraryStore
        val gb = 1L shl 30
        val down = io.github.matiyaaa.fuse.transfer.TransferItem(
            "d1", "romm:rom:7", "romm", io.github.matiyaaa.fuse.transfer.TransferDirection.DOWNLOAD, io.github.matiyaaa.fuse.transfer.TransferKind.GAME,
            "Velvet Orbit", platform = "dc", target = "SD card  ·  ROMs/dc", totalBytes = 4 * gb, doneBytes = 2 * gb,
            status = io.github.matiyaaa.fuse.transfer.TransferStatus.ACTIVE, phase = io.github.matiyaaa.fuse.transfer.TransferPhase.TRANSFERRING,
        )
        val later = down.copy(
            id = "d2", key = "romm:rom:8", title = "Hollow Meridian", platform = "psx", totalBytes = 1_400L shl 20, doneBytes = 0,
            status = io.github.matiyaaa.fuse.transfer.TransferStatus.WAITING, waiting = io.github.matiyaaa.fuse.transfer.WaitReason.LATER, phase = null, later = true, order = 1,
        )
        val begun = later.copy(id = "d3", key = "romm:rom:9", title = "Lantern Coast", platform = "gba", totalBytes = 32L shl 20, doneBytes = 13L shl 20, order = 2)
        val queued = down.copy(id = "d4", key = "romm:rom:10", title = "Ember Tactics", platform = "snes", totalBytes = 4L shl 20, doneBytes = 0, status = io.github.matiyaaa.fuse.transfer.TransferStatus.QUEUED, phase = null, order = 3)
        val waitingActions = listOf(io.github.matiyaaa.fuse.ui.shell.store.TransferAction.NOW, io.github.matiyaaa.fuse.ui.shell.store.TransferAction.PAUSE, io.github.matiyaaa.fuse.ui.shell.store.TransferAction.CANCEL, io.github.matiyaaa.fuse.ui.shell.store.TransferAction.MOVE_TO_TOP)
        val rows = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(
                io.github.matiyaaa.fuse.ui.shell.store.TransferRow(down, listOf(io.github.matiyaaa.fuse.ui.shell.store.TransferAction.PAUSE, io.github.matiyaaa.fuse.ui.shell.store.TransferAction.LATER, io.github.matiyaaa.fuse.ui.shell.store.TransferAction.CANCEL)),
                io.github.matiyaaa.fuse.ui.shell.store.TransferRow(later, waitingActions),
                io.github.matiyaaa.fuse.ui.shell.store.TransferRow(begun, waitingActions),
                io.github.matiyaaa.fuse.ui.shell.store.TransferRow(queued, listOf(io.github.matiyaaa.fuse.ui.shell.store.TransferAction.PAUSE, io.github.matiyaaa.fuse.ui.shell.store.TransferAction.LATER, io.github.matiyaaa.fuse.ui.shell.store.TransferAction.CANCEL)),
            ),
        )
        val live = mapOf("d1" to io.github.matiyaaa.fuse.transfer.TransferLive(2 * gb + (300L shl 20), 4 * gb, 24L shl 20, 74), "d3" to io.github.matiyaaa.fuse.transfer.TransferLive(13L shl 20, 32L shl 20))
        val ops = object : io.github.matiyaaa.fuse.ui.shell.store.TransfersOps {
            override val rows = rows
            override val summary = kotlinx.coroutines.flow.MutableStateFlow(io.github.matiyaaa.fuse.transfer.TransferSummary(activeDownloads = 1, queued = 1, waiting = 2, later = 2))
            override val settings = kotlinx.coroutines.flow.MutableStateFlow(io.github.matiyaaa.fuse.transfer.TransferSettings())
            override fun live(id: String) = kotlinx.coroutines.flow.MutableStateFlow(live[id] ?: io.github.matiyaaa.fuse.transfer.TransferLive(0, rows.value.firstOrNull { it.item.id == id }?.item?.totalBytes))
            override fun act(id: String, action: io.github.matiyaaa.fuse.ui.shell.store.TransferAction) = Unit
            override fun pauseAll() = Unit
            override fun resumeAll() = Unit
            override fun clearFinished() = Unit
        }
        val romm = AuditRomm(base)
        show(object : FuseStore by base { override val transfers = ops; override val romm: io.github.matiyaaa.fuse.ui.shell.store.RommOps = romm })
        libraryTab()
        waitFor("Connected")
        settle(900)
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("games on RomM")
        // A game on its way (the fourth): its options lead to Downloads. Two columns on a phone:
        // the three collections take two rows, and the fourth game is on the second.
        tap(PadButton.DPAD_DOWN)
        if (size == AuditSize.V) {
            tap(PadButton.DPAD_DOWN, 2)
            tap(PadButton.DPAD_RIGHT)
        } else {
            tap(PadButton.DPAD_RIGHT, 3)
        }
        tap(PadButton.X)
        tapText("See in Downloads")
        waitFor("for later")
        settle(900)
        shoot("two kept for later, one begun, beside one moving", 1_200)
        focusHint("Download Now")
        shoot("one kept for later chosen: confirm sends it now", 900)
        tap(PadButton.X)
        waitFor("Takes its turn now")
        shoot("its options", 900)
        tap(PadButton.B)
    }
    scenario("companion", "while you play") {
        useLibrary()
        val base = libraryStore
        val card = runBlocking { base.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first().first { it.title == "Velvet Orbit" } }
        val start = kotlin.time.Clock.System.now().toEpochMilliseconds() - 42 * 60_000L
        val session = runBlocking { libraryData.playSessions.start(card.id, io.github.matiyaaa.fuse.model.EmulatorId("linux.flycast"), start) }
        // A device that can close the game it runs, as Linux and macOS can.
        val library = object : LibraryOps by base.library { override val canQuitPlaying = true }
        val store = object : FuseStore by base { override val library = library }
        fun tapDescribed(d: String) {
            val at = describedBounds(d).firstOrNull() ?: throw NotCovered("Nothing on screen is described as \"$d\"")
            touch { click(at.center) }
            settle(700)
        }
        try {
            pumpUntil("the open session to show", 20_000) { base.library.home.value.playtime.currentGame != null }
            io.github.matiyaaa.fuse.ui.shell.app.CompanionPage.current.value = 0
            view = AuditView.Companion(store, platform, io.github.matiyaaa.fuse.model.DualScreenMode.GAME_COMPANION)
            settle(1_500)
            shoot("the game running, with its Journal and Quit", 1_200)
            tapDescribed("Journal")
            shoot("choosing where you are with it", 900)
            val beaten = textCentre("Beaten")
            touch { click(beaten) }
            settle(900)
            shoot("marked Beaten", 900)
            tapDescribed("Quit")
            shoot("Quit asks for a second tap", 600)
        } finally {
            runBlocking {
                libraryData.playSessions.end(session, kotlin.time.Clock.System.now().toEpochMilliseconds())
                base.library.setJournal(card.id, GameJournal())
            }
        }
    }
    scenario("library", "box art in square tiles") {
        // Covers only (most scraped games): each square tile shows the cover whole over a soft copy of
        // itself, made from a tiny decode rather than a blur filter on every tile.
        val colors = listOf(0xFF7A4DFF, 0xFFE0573E, 0xFF2E9E6E, 0xFFDAA520, 0xFF3A7BD5, 0xFFB03A8C)
        val names = listOf("Lantern Coast", "Velvet Orbit", "Hollow Meridian", "Ember Tactics", "Beacon Bay", "Tanuki Trails")
        val covers = names.mapIndexed { i, n -> java.io.File(cache, "boxart-$i.png").also { AuditCovers.cover(it, n, colors[i]) } }
        val cards = runBlocking { libraryStore.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).first() }.take(6)
        view = AuditView.Piece {
            androidx.compose.foundation.layout.Row(
                androidx.compose.ui.Modifier.fillMaxSize().padding(40.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                cards.forEachIndexed { i, c ->
                    io.github.matiyaaa.fuse.ui.shell.components.GameIconTile(
                        c.copy(art = io.github.matiyaaa.fuse.ui.shell.store.Art.None.copy(boxart = covers[i].absolutePath)),
                        selected = i == 1, size = 180.dp,
                    )
                }
            }
        }
        shoot("covers whole over their soft copies", 2_000)
    }
    scenario("home", "the widget gallery") {
        useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS)) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        // Arranging: down past the board to the bar, where Add widget is first.
        hold(PadButton.A)
        waitFor("Put down")
        tap(PadButton.A)
        waitFor("Pick up")
        tap(PadButton.DPAD_DOWN, 8)
        tap(PadButton.A)
        waitFor("Shown as each will look")
        shoot("every widget as it will look, under headings", 2_000)
        tap(PadButton.DPAD_DOWN, 2)
        shoot("further down, a widget chosen", 1_200)
        tap(PadButton.A)
        waitGone("Shown as each will look")
        shoot("added to the board", 1_500)
    }
    scenario("home", "every widget in the gallery") {
        useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS)) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        hold(PadButton.A)
        waitFor("Put down")
        tap(PadButton.A)
        waitFor("Pick up")
        tap(PadButton.DPAD_DOWN, 8)
        tap(PadButton.A)
        waitFor("Shown as each will look")
        // Down through every heading, a page at a time.
        for (page in 1..7) {
            tap(PadButton.DPAD_DOWN, 2)
            shoot("page $page", 900)
        }
    }
    scenario("addons", "android ports as box art") {
        val base = androidStore
        // Ports in every state the page shows: playing your game, to attach, installing, your game
        // on RomM, a problem, and ports for games you haven't got.
        val colors = listOf(0xFF3A7BD5, 0xFFE0573E, 0xFF2E9E6E, 0xFFDAA520, 0xFF7A4DFF, 0xFFB03A8C, 0xFF5B6FB5, 0xFFC9A45C)
        val games = listOf("Star Courier 64", "Harbour Kart", "Lantern Quest", "Moth Knight", "Glass Tower", "Ember Racer", "Tidal Saga", "Pocket Comet")
        val covers = games.mapIndexed { i, n -> java.io.File(cache, "port-$i.png").also { AuditCovers.cover(it, n, colors[i]) }.absolutePath }
        fun port(i: Int, name: String, sys: String, checked: Boolean = true, verified: String? = null) = io.github.matiyaaa.fuse.model.AndroidPort(
            id = "p$i", name = name, game = games[i], systems = listOf(sys), repo = "https://github.com/example/$name",
            licence = "MIT", checked = checked, verified = verified, publisher = "Northlight", year = 1996 + i, genre = "action-adventure", cover = covers[i],
        )
        val mine = io.github.matiyaaa.fuse.ui.shell.store.PortGameChoice(GameId(1), games[0], "N64", plays = true)
        val ports = listOf(
            io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView(port(0, "Courier64", "n64", verified = "2026-10-20"), "org.example.courier", "app.courier", "1.4.0", attachedTo = listOf(mine)),
            io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView(port(1, "OpenKart", "n64"), "org.example.kart", "app.kart", "0.9.2", games = listOf(io.github.matiyaaa.fuse.ui.shell.store.PortGameChoice(GameId(2), games[1], "N64", plays = true))),
            io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView(port(2, "LanternEx", "snes"), job = io.github.matiyaaa.fuse.ui.shell.store.PortJob.Downloading(0.42f, 42, 100), games = listOf(io.github.matiyaaa.fuse.ui.shell.store.PortGameChoice(GameId(3), games[2], "SNES", plays = true))),
            io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView(port(3, "MothDroid", "psx"), games = listOf(io.github.matiyaaa.fuse.ui.shell.store.PortGameChoice(GameId(4), games[3], "PS1", plays = true))),
            io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView(port(4, "GlassPort", "psx", checked = false), job = io.github.matiyaaa.fuse.ui.shell.store.PortJob.Failed("The release has no APK for this device.")),
            io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView(port(5, "EmberRe", "n64")),
            io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView(port(6, "TidalPort", "snes", checked = false)),
            io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView(port(7, "CometNative", "gba")),
        )
        val state = kotlinx.coroutines.flow.MutableStateFlow(io.github.matiyaaa.fuse.ui.shell.store.AndroidPortsState(ports, loaded = true, listedAt = "28 October"))
        val portsOps = object : io.github.matiyaaa.fuse.ui.shell.store.PortsOps by base.ports {
            override val androidApps get() = true
            override val android get() = state
        }
        base.updatePrefs { it.copy(portsEnabled = true, addonsOrder = listOf("PORTS")) }
        try {
            show(object : FuseStore by base { override val ports = portsOps })
            home()
            repeat(6) { if (!hasText("Games rebuilt for Android")) tap(PadButton.R1) }
            waitFor("Games rebuilt for Android")
            settle(1_500)
            shoot("box art for every port, the chosen one told above", 1_500)
            tap(PadButton.DPAD_RIGHT, 2)
            settle(900)
            shoot("one installing, the stage following", 1_200)
            tap(PadButton.DPAD_UP)
            tap(PadButton.DPAD_RIGHT, 3)
            tap(PadButton.DPAD_DOWN)
            settle(900)
            shoot("everything, with ports for games you haven't got", 1_200)
        } finally {
            base.updatePrefs { it.copy(portsEnabled = false, addonsOrder = emptyList()) }
        }
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
            waitFor("Half a star")
            shoot("the stars, as they were", 1_200)
            // Half a star at a time, as the D-pad steps it.
            tap(PadButton.DPAD_RIGHT)
            shoot("half a star more", 900)
            tap(PadButton.DPAD_LEFT, 3)
            shoot("a star and a half less", 900)
            tap(PadButton.A)
            waitFor("Where you are")
            shoot("back on the Journal, the new rating beside it", 1_000)
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
