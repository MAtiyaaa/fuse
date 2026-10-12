package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.library.steam.SteamArt
import io.github.matiyaaa.fuse.library.steam.SteamGameEntry
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamRestartTest {

    /** A pretend system: Steam closes when asked (after [closesAfter] polls), comes back by itself or when started. */
    private class FakeOps(
        var steam: Boolean = true,
        val game: Boolean = false,
        val closesAfter: Int = 2,
        /** Game Mode's session starts Steam again by itself after this many polls; -1: never. */
        val sessionBringsBackAfter: Int = -1,
        val canAsk: Boolean = true,
    ) : SteamProcessOps {
        val log = ArrayList<String>()
        private var asked = false
        private var closedPolls = 0
        private var openPolls = 0
        private var downPolls = 0
        override fun steamRunning(): Boolean = steam
        override fun gameRunning() = game
        override fun askSteamToClose(): Boolean { log += "ask"; asked = canAsk; return canAsk }
        override fun startSteam(gameMode: Boolean): Boolean { log += if (gameMode) "session" else "start"; steam = true; return true }
        override fun openFuse(): Boolean { log += "open"; return true }
        override fun sleep(ms: Long) {
            if (asked && steam && ++closedPolls >= closesAfter) { steam = false; asked = false; log += "closed" }
            else if (!steam) { downPolls++; if (sessionBringsBackAfter >= 0 && downPolls >= sessionBringsBackAfter) { steam = true; log += "back" } }
            openPolls++
        }
    }

    @Test
    fun steamIsClosedBeforeTheChangeAndStartedAgainAfterIt() {
        val ops = FakeOps()
        val r = SteamRestarter(ops, gameMode = false).run(reopenFuse = false) { ops.log += "write"; assertFalse(ops.steam, "never written while Steam is open"); Result.success("done") }
        assertTrue(r.isSuccess)
        assertEquals(listOf("ask", "closed", "write", "start"), ops.log)
    }

    @Test
    fun inGameModeTheSessionBringsSteamBackAndFuseOpensAgain() {
        val ops = FakeOps(sessionBringsBackAfter = 3)
        SteamRestarter(ops, gameMode = true).run(reopenFuse = true) { ops.log += "write"; Result.success("done") }
        // The session started Steam by itself: Fuse doesn't start a second one.
        assertEquals(listOf("ask", "closed", "write", "back", "open"), ops.log)
    }

    @Test
    fun inGameModeTheSessionIsAskedBackOnlyWhenItDoesntComeBackByItself() {
        val ops = FakeOps(sessionBringsBackAfter = -1)
        SteamRestarter(ops, gameMode = true).run(reopenFuse = true) { Result.success("done") }
        assertEquals(listOf("ask", "closed", "session", "open"), ops.log)
    }

    @Test
    fun nothingIsClosedWhileAGameRuns() {
        val ops = FakeOps(game = true)
        var wrote = false
        val r = SteamRestarter(ops, gameMode = true).run(reopenFuse = true) { wrote = true; Result.success("done") }
        assertTrue(r.isFailure)
        assertFalse(wrote)
        assertTrue(ops.log.isEmpty(), "Steam isn't even asked")
    }

    @Test
    fun steamThatCantBeAskedIsLeftAloneAndNothingIsWritten() {
        val ops = FakeOps(canAsk = false)
        var wrote = false
        val r = SteamRestarter(ops, gameMode = false).run(reopenFuse = false) { wrote = true; Result.success("done") }
        assertTrue(r.isFailure)
        assertFalse(wrote)
    }

    @Test
    fun aClosedSteamIsWrittenStraightAwayAndStarted() {
        val ops = FakeOps(steam = false)
        SteamRestarter(ops, gameMode = false).run(reopenFuse = false) { ops.log += "write"; Result.success("done") }
        assertEquals(listOf("write", "start"), ops.log)
    }

    @Test
    fun aJobSurvivesTheTripToTheHelper() {
        val file = File(Files.createTempDirectory("steam-job").toFile(), "steam-restart.json")
        val games = SteamRestartJob.games(
            listOf(SteamGameEntry(7, "Metroid Fusion", "Game Boy Advance"), SteamGameEntry(9, "Ico")),
            mapOf(7L to SteamArt(portrait = "/art/7.png", hero = "/art/7-hero.png")),
        )
        val job = SteamRestartJob("/apps/Fuse.AppImage", home = "/home/deck", note = "/data/note", gameMode = true, fuse = "repair", games = games, collections = true)
        job.write(file)
        val back = SteamRestartJob.read(file)
        assertEquals(job, back)
        assertEquals(SteamArt(portrait = "/art/7.png", hero = "/art/7-hero.png"), back!!.games.first().art)
        assertEquals(SteamGameEntry(9, "Ico"), back.games.last().entry)
    }

    @Test
    fun fusesOwnSteamLaunchIsNotAGame() {
        val fuse = "/home/deck/Applications/Fuse.AppImage"
        val onlyFuse = "4242 /home/deck/.steam/ubuntu12_32/reaper SteamLaunch AppId=3141592653 -- '$fuse' --fullscreen"
        val withGame = onlyFuse + "\n5151 /home/deck/.steam/ubuntu12_32/reaper SteamLaunch AppId=620 -- /games/portal2/portal2.sh"
        assertFalse(SystemSteamProcessOps.steamGamesIn(onlyFuse, fuse))
        assertTrue(SystemSteamProcessOps.steamGamesIn(withGame, fuse))
        // Without knowing Fuse's program, any launch counts: never close Steam on a guess.
        assertTrue(SystemSteamProcessOps.steamGamesIn(onlyFuse, null))
    }

    @Test
    fun steamSaysWhenItStartedFuse() {
        assertTrue(DesktopSteam.insideSteam(mapOf("SteamGameId" to "13493712812342345728")))
        assertTrue(DesktopSteam.insideSteam(mapOf("SteamClientLaunch" to "1")))
        assertFalse(DesktopSteam.insideSteam(mapOf("HOME" to "/home/deck")))
    }
}
