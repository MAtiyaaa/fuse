package io.github.matiyaaa.fuse.integrations.libretro

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.RateLimiter
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.text
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibretroThumbnailsTest {

    @Test
    fun sanitiseReplacesForbiddenCharacters() {
        assertEquals("Ratchet _ Clank", LibretroThumbnails.sanitize("Ratchet & Clank"))
        assertEquals("Zelda_ A Link_ _Past_ _a_b_ _ _ _ _", LibretroThumbnails.sanitize("Zelda: A Link* \"Past\" <a/b> ? \\ | `"))
    }

    @Test
    fun urlIsEncoded() {
        val http = TestHttp { text("", HttpStatusCode.OK) }
        val thumbs = LibretroThumbnails(http.client)
        assertEquals(
            "https://thumbnails.libretro.com/Nintendo%20-%20Super%20Nintendo%20Entertainment%20System/Named_Boxarts/Super%20Metroid%20%28Japan%2C%20USA%29%20%28En%2CJa%29.png",
            thumbs.url("Nintendo - Super Nintendo Entertainment System", LibretroThumbnailType.BOXART, "Super Metroid (Japan, USA) (En,Ja)"),
        )
    }

    @Test
    fun nameCandidatesAndSystems() {
        assertEquals(
            listOf("Super Metroid", "Super Metroid (Japan, USA) (En,Ja)"),
            LibretroThumbnails.nameCandidates("Super Metroid", "/roms/snes/Super Metroid (Japan, USA) (En,Ja).sfc"),
        )
        assertEquals(
            listOf("Legend of Zelda, The - A Link to the Past (USA)", "Legend of Zelda, The - A Link to the Past"),
            LibretroThumbnails.nameCandidates(null, "Legend of Zelda, The - A Link to the Past (USA).sfc"),
        )
        assertEquals(listOf("Sonic_Knuckles"), LibretroThumbnails.nameCandidates("Sonic&Knuckles", null))
        assertEquals(listOf("Sony - PlayStation"), LibretroThumbnails.systemsFor(PlatformId("psx")))
        assertEquals(listOf("Sega - Mega Drive - Genesis"), LibretroThumbnails.systemsFor(PlatformId("genesis")))
        assertEquals(listOf("Nintendo - GameCube"), LibretroThumbnails.systemsFor(PlatformId("ngc")))
        assertEquals(listOf("MAME", "FBNeo - Arcade Games"), LibretroThumbnails.systemsFor(PlatformId("arcade")))
        assertTrue(LibretroThumbnails.systemsFor(PlatformId("switch")).isEmpty())
    }

    @Test
    fun headProbesFindTheFirstExistingName() = runTest {
        val existing = setOf(
            "/Nintendo%20-%20Super%20Nintendo%20Entertainment%20System/Named_Boxarts/Super%20Metroid%20%28Japan%2C%20USA%29%20%28En%2CJa%29.png",
            "/Nintendo%20-%20Super%20Nintendo%20Entertainment%20System/Named_Titles/Super%20Metroid.png",
        )
        val http = TestHttp { request ->
            text("", if (request.url.encodedPath in existing) HttpStatusCode.OK else HttpStatusCode.NotFound)
        }
        val thumbs = LibretroThumbnails(http.client, RateLimiter.unlimited())
        val found = (thumbs.find(PlatformId("snes"), "Super Metroid", "Super Metroid (Japan, USA) (En,Ja).sfc") as ApiResult.Success).value
        assertTrue(http.requests.all { it.method == HttpMethod.Head })
        assertEquals(listOf(LibretroThumbnailType.BOXART, LibretroThumbnailType.TITLE), found.map { it.type })
        assertEquals("Super Metroid (Japan, USA) (En,Ja)", found[0].name)
        val options = found.map { it.toArtworkOption() }
        assertEquals(listOf(MediaKind.BOXART, MediaKind.SCREENSHOT), options.map { it.kind })
        assertEquals("title", options[1].style)
    }
}
