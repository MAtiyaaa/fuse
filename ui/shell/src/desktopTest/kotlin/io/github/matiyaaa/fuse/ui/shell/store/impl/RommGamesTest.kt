package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.romm.RommFile
import io.github.matiyaaa.fuse.romm.RommMirror
import io.github.matiyaaa.fuse.romm.RommRom
import io.github.matiyaaa.fuse.ui.shell.store.FakeServices
import io.github.matiyaaa.fuse.ui.shell.store.rommGameId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * RomM games Fuse doesn't have, as games: the details Fuse's sources find and the names the user
 * gives them sit over the server's, follow the library's rules, and are still there next time.
 */
class RommGamesTest {
    private val dir: File = Files.createTempDirectory("fuse-romm-games").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val rom = RommRom(
        id = 42, platformId = 7, platformSlug = "snes", name = "Chrono Trigger", fsName = "Chrono Trigger (USA).sfc",
        summary = "RomM's summary", year = 1995, genres = listOf("RPG"), files = listOf(RommFile(420, "Chrono Trigger (USA).sfc")),
    )

    private fun open(db: String = "fuse.db"): Pair<StoreContext, RommGames> {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(dir, db).absolutePath)), dir)
        val ctx = StoreContext(services, scope, AppSettings())
        val mirror = RommMirror(services.data.database, ctx::now)
        runBlocking { mirror.put("main", rom) }
        return ctx to RommGames(ctx, mirror, { "main" }, {})
    }

    @Test
    fun `a server's game reads as a game, with RomM's details`() = runBlocking<Unit> {
        val (_, games) = open()
        val g = games.get(rommGameId(42))!!
        assertEquals("Chrono Trigger", g.displayTitle)
        assertEquals("RomM's summary", g.metadata.description)
        assertEquals(42L, g.links.rommRomId)
        assertEquals("snes", g.platformId.value)
        assertTrue(games.owns(rommGameId(42)))
        assertTrue(!games.owns(io.github.matiyaaa.fuse.model.GameId(42)))
    }

    @Test
    fun `found details sit over RomM's, the user's own are never replaced, and all of it is kept`() = runBlocking<Unit> {
        val (_, games) = open()
        val id = rommGameId(42)
        games.applyMetadata(id, GameMetadata(description = "IGDB's summary", developer = "Square", source = MetadataSource.IGDB), titleFromMetadata = "Chrono Trigger", onlyFillEmpty = false)
        games.updateLinks(id) { it.copy(steamGridDbGameId = 5150, igdbId = 77) }
        var g = games.get(id)!!
        assertEquals("IGDB's summary", g.metadata.description)
        assertEquals("Square", g.metadata.developer)
        // What the sources didn't have, RomM still fills.
        assertEquals(1995, g.metadata.releaseYear)
        assertEquals(5150L, g.links.steamGridDbGameId)

        // The user's words stay when a source comes back with others.
        games.applyMetadata(id, GameMetadata(description = "Mine", source = MetadataSource.USER), titleFromMetadata = null, onlyFillEmpty = false)
        games.applyMetadata(id, GameMetadata(description = "TheGamesDB's", source = MetadataSource.THEGAMESDB), titleFromMetadata = null, onlyFillEmpty = false)
        assertEquals("Mine", games.get(id)!!.metadata.description)

        games.rename(id, "Chrono Trigger (my copy)")
        assertEquals("Chrono Trigger (my copy)", games.get(id)!!.displayTitle)

        // Fuse opened again: nothing is lost or looked for again.
        val (_, again) = open()
        g = again.get(id)!!
        assertEquals("Chrono Trigger (my copy)", g.displayTitle)
        assertEquals("Mine", g.metadata.description)
        assertEquals(77L, g.links.igdbId)
        assertEquals("Chrono Trigger (my copy)", again.title(rom, again.all()[42]))
    }

    @Test
    fun `resetting forgets what sources said and keeps the user's name`() = runBlocking<Unit> {
        val (_, games) = open()
        val id = rommGameId(42)
        games.applyMetadata(id, GameMetadata(description = "Another game's", source = MetadataSource.STEAMGRIDDB), titleFromMetadata = "Chrono Cross", onlyFillEmpty = false)
        games.rename(id, "CT")
        games.replaceMetadata(id, metadata = null, titleMetadata = null)
        val g = games.get(id)!!
        assertEquals("RomM's summary", g.metadata.description)
        assertEquals("CT", g.displayTitle)
        games.rename(id, null)
        assertEquals("Chrono Trigger", games.get(id)!!.displayTitle)
        assertNull(games.get(rommGameId(999)))
    }
}
