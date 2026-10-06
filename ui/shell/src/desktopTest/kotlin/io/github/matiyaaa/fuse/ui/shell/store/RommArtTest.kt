package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.romm.RommFile
import io.github.matiyaaa.fuse.romm.RommMirror
import io.github.matiyaaa.fuse.romm.RommRom
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A RomM game Fuse doesn't have gets Fuse's box art (the square art) on the lists already open, no
 * restart needed; and a system the system art pack has nothing for (PlayStation 5) takes its art
 * from its own games.
 */
class RommArtTest {
    private val dir: File = Files.createTempDirectory("fuse-romm-art").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val square = "https://cdn.steamgriddb.com/grid/chrono-square.png"
    private val hero = "https://cdn.steamgriddb.com/hero/astro-hero.png"

    @Test
    fun boxArtShowsOnOpenListsAndPs5GetsArtFromItsGames(): Unit = runBlocking {
        val data = FuseData(DesktopDatabase.open(File(dir, "fuse.db").absolutePath))
        val services = FakeServices(data, File(dir, "cache").apply { mkdirs() }, autoFill = true)
        services.secrets.put("sgdb.apikey", "KEY")
        services.web = { r ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val path = r.url.encodedPath
            fun page(vararg assets: String) = respond("""{"success":true,"page":0,"total":${assets.size},"limit":20,"data":[${assets.joinToString(",")}]}""", HttpStatusCode.OK, json)
            when {
                r.url.host != "www.steamgriddb.com" -> null
                "search/autocomplete" in path && "Chrono" in path -> respond("""{"success":true,"data":[{"id":5150,"name":"Chrono Trigger"}]}""", HttpStatusCode.OK, json)
                "search/autocomplete" in path && "Astro" in path -> respond("""{"success":true,"data":[{"id":6160,"name":"Astro Bot"}]}""", HttpStatusCode.OK, json)
                "search/autocomplete" in path -> respond("""{"success":true,"data":[]}""", HttpStatusCode.OK, json)
                "grids/game/5150" in path && r.url.parameters["dimensions"]?.contains("512x512") == true ->
                    page("""{"id":1,"url":"$square","width":512,"height":512}""")
                "grids/game/5150" in path && r.url.parameters["dimensions"]?.contains("600x900") == true ->
                    page("""{"id":2,"url":"https://cdn.steamgriddb.com/grid/chrono-poster.png","width":600,"height":900}""")
                "heroes/game/6160" in path -> page("""{"id":3,"url":"$hero","width":1920,"height":620}""")
                else -> page()
            }
        }
        val q = data.database.rommQueries
        q.upsertPlatform("main", 7, "snes", "snes", "Super Nintendo", 1, 0)
        q.upsertPlatform("main", 9, "ps5", "ps5", "PlayStation 5", 1, 0)
        val mirror = RommMirror(data.database, { System.currentTimeMillis() })
        mirror.put("main", RommRom(id = 42, platformId = 7, platformSlug = "snes", name = "Chrono Trigger", fsName = "Chrono Trigger (USA).sfc", cover = "/cover/42.png", files = listOf(RommFile(420, "Chrono Trigger (USA).sfc"))))
        mirror.put("main", RommRom(id = 77, platformId = 9, platformSlug = "ps5", name = "Astro Bot", fsName = "Astro Bot", files = listOf(RommFile(770, "Astro Bot/eboot.bin"))))
        val store = createFuseStore(services, scope)

        // The RomM tab's list is open before any art is found, and stays open.
        var shown: List<RommGame> = emptyList()
        val watching = scope.launch { store.romm.games(null).collect { shown = it } }
        store.romm.detail(42).first { it != null }
        store.romm.detail(77).first { it != null }

        withTimeout(30_000) {
            while (shown.firstOrNull { it.romId == 42L }?.card?.art?.square?.toString() != square) delay(50)
        }
        // RomM's own cover stays behind it, only for where there is no box art.
        assertEquals(square, shown.first { it.romId == 42L }.card.art.square.toString())

        // The PS5, which the pack has no art for, takes the background of one of its games.
        val ps5 = MediaOwner.OfPlatform(PlatformId("ps5"))
        withTimeout(30_000) {
            while (data.media.get(ps5).boxart == null) delay(50)
        }
        val panel = data.media.get(ps5).boxart!!
        assertEquals(MediaSource.GAME_ART, panel.source)
        assertTrue(panel.model == hero, "${panel.model}")
        watching.cancel()
    }
}
