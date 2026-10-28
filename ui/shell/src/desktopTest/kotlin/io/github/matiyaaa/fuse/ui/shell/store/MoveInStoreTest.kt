package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.library.movein.MoveInSource
import io.github.matiyaaa.fuse.model.GameJournal
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlayStatus
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Moving in from ES-DE and RetroArch: what they say comes in, nothing set in Fuse is undone, and it all goes back. */
class MoveInStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-movein").toFile()
        cache = Files.createTempDirectory("fuse-movein-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun aMoveInBringsHistoryKeepsFusesOwnAndGoesBack() = runBlocking {
        val roms = File(root, "roms")
        File(roms, "psx").mkdirs()
        File(roms, "snes").mkdirs()
        listOf("psx/Crash Bandicoot (USA).chd", "psx/Spyro.chd", "snes/Earthbound.sfc").forEach { File(roms, it).writeBytes(ByteArray(64)) }
        val home = File(root, "home")
        // ES-DE: Crash a favourite, completed and rated; Spyro hidden; a collection of two.
        File(home, "ES-DE/gamelists/psx").mkdirs()
        File(home, "ES-DE/gamelists/psx/gamelist.xml").writeText(
            """<gameList>
                <game><path>./Crash Bandicoot (USA).chd</path><favorite>true</favorite><completed>true</completed><rating>0.9</rating><lastplayed>20240301T100000</lastplayed></game>
                <game><path>./Spyro.chd</path><hidden>true</hidden></game>
            </gameList>""",
        )
        File(home, "ES-DE/collections").mkdirs()
        File(home, "ES-DE/collections/custom-Couch.cfg").writeText("%ROMPATH%/psx/Crash Bandicoot (USA).chd\n%ROMPATH%/snes/Earthbound.sfc\n")
        // RetroArch: three hours of Earthbound.
        File(home, ".config/retroarch/playlists/logs/Snes9x").mkdirs()
        File(home, ".config/retroarch/retroarch.cfg").writeText("")
        File(home, ".config/retroarch/playlists/logs/Snes9x/Earthbound.lrtl").writeText("""{"version": "1.0", "runtime": "3:00:00", "last_played": "2024-03-02 21:00:00"}""")

        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache).also { it.homeFolder = home.absolutePath }
        val store = createFuseStore(services, scope)
        store.sources.add(roms.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val games = withTimeout(20_000) { store.library.games(GameQuery(includeHidden = true)).first { it.size == 3 } }
        fun game(title: String) = games.single { it.title.startsWith(title) }
        val crash = game("Crash")
        val spyro = game("Spyro")
        val earthbound = game("Earthbound")
        // Set in Fuse already: Crash is being played. Moving in never changes it.
        store.library.setJournal(crash.id, GameJournal(PlayStatus.PLAYING))

        val look = store.library.moveInLook().associateBy { it.source }
        assertEquals(setOf(MoveInSource.ESDE, MoveInSource.RETROARCH), look.keys)
        assertEquals(MoveInReport(games = 2, favourites = 1, hidden = 1, timed = 1, journal = 1, collections = 1), look.getValue(MoveInSource.ESDE).report)
        assertEquals(MoveInReport(games = 1, timed = 1), look.getValue(MoveInSource.RETROARCH).report)

        val report = store.library.moveIn(setOf(MoveInSource.ESDE, MoveInSource.RETROARCH))
        assertEquals(3, report.games)
        val crashNow = services.data.games.get(crash.id)!!
        assertTrue(crashNow.favorite)
        // The status set in Fuse stays; the rating, empty here, comes in.
        assertEquals(PlayStatus.PLAYING, crashNow.journal.status)
        assertEquals(9, crashNow.journal.rating)
        assertTrue(services.data.games.get(spyro.id)!!.hidden)
        val eb = services.data.games.get(earthbound.id)!!.play
        assertEquals(3 * 3_600L, eb.importedSeconds)
        assertEquals("RetroArch", eb.importedSource)
        // Imported time is never time Fuse saw.
        assertEquals(0L, eb.trackedSeconds)
        val couch = services.data.collections.observeManual().first().single { it.name == "Couch" }
        assertEquals(2, couch.gameCount)
        assertTrue(store.library.lastMoveIn() != null)

        // Put back: every game and the collection as they were, Fuse's own status still there.
        assertTrue(store.library.undoMoveIn())
        val back = services.data.games.get(crash.id)!!
        assertFalse(back.favorite)
        assertEquals(PlayStatus.PLAYING, back.journal.status)
        assertEquals(0, back.journal.rating)
        assertFalse(services.data.games.get(spyro.id)!!.hidden)
        assertEquals(0L, services.data.games.get(earthbound.id)!!.play.importedSeconds)
        assertTrue(services.data.collections.observeManual().first().none { it.name == "Couch" })
        assertNull(store.library.lastMoveIn())
        assertFalse(store.library.undoMoveIn())
    }

    @Test
    fun aWantToPlayListOnRetroAchievementsFillsTheJournalWhereItIsEmpty() = runBlocking {
        val roms = File(root, "roms")
        File(roms, "psx").mkdirs()
        File(roms, "snes").mkdirs()
        listOf("psx/Spyro the Dragon (USA).chd", "psx/crash.chd", "psx/Tekken 3 (USA).chd", "snes/Chrono Trigger (USA).sfc").forEach { File(roms, it).writeBytes(ByteArray(64)) }
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache).also { it.homeFolder = File(root, "home").absolutePath }
        val json = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")
        services.web = { request ->
            when {
                request.url.host != "retroachievements.org" -> null
                request.url.encodedPath.endsWith("API_GetUserProfile.php") -> respond("""{"User":"Ana","ULID":"01HANA","TotalPoints":120}""", io.ktor.http.HttpStatusCode.OK, json)
                request.url.encodedPath.endsWith("API_GetUserWantToPlayList.php") -> respond(
                    """{"Count":5,"Total":5,"Results":[
                        {"ID":11,"Title":"Crash Bandicoot","ConsoleID":12,"ConsoleName":"PlayStation"},
                        {"ID":12,"Title":"Spyro the Dragon","ConsoleID":12,"ConsoleName":"PlayStation"},
                        {"ID":319,"Title":"Chrono Trigger","ConsoleID":3,"ConsoleName":"SNES/Super Famicom"},
                        {"ID":555,"Title":"~Hack~ Tekken 3","ConsoleID":12,"ConsoleName":"PlayStation"},
                        {"ID":777,"Title":"Vagrant Story","ConsoleID":12,"ConsoleName":"PlayStation"}]}""",
                    io.ktor.http.HttpStatusCode.OK, json,
                )
                else -> null
            }
        }
        val store = createFuseStore(services, scope)
        store.sources.add(roms.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val games = withTimeout(20_000) { store.library.games(GameQuery(includeHidden = true)).first { it.size == 4 } }
        fun game(title: String) = games.single { it.title.startsWith(title, ignoreCase = true) }
        // Crash's file says nothing of its name, but Fuse already knows its RetroAchievements id.
        services.data.games.updateLinks(game("crash").id) { it.copy(retroAchievementsGameId = 11) }
        // Chrono Trigger is already beaten in Fuse: that stays.
        store.library.setJournal(game("Chrono").id, GameJournal(PlayStatus.BEATEN))
        assertTrue(store.achievements.connect("Ana", "key-a").isSuccess)

        val look = store.library.moveInLook().associateBy { it.source }
        val ra = look.getValue(MoveInSource.RETROACHIEVEMENTS)
        assertEquals("Ana on RetroAchievements", ra.where)
        assertEquals(MoveInReport(games = 2, journal = 2), ra.report)
        store.library.moveIn(setOf(MoveInSource.RETROACHIEVEMENTS))
        assertEquals(PlayStatus.WANT, services.data.games.get(game("crash").id)!!.journal.status)
        assertEquals(PlayStatus.WANT, services.data.games.get(game("Spyro").id)!!.journal.status)
        assertEquals(PlayStatus.BEATEN, services.data.games.get(game("Chrono").id)!!.journal.status)
        // A hack is another game than the one here.
        assertNull(services.data.games.get(game("Tekken").id)!!.journal.status)
        assertTrue(store.library.undoMoveIn())
        assertNull(services.data.games.get(game("Spyro").id)!!.journal.status)
    }
}
