package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.ScannedGame
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Switch library kept the way people really keep them (made-up titles, real shapes): games in
 * folders of their own, the same files again in one big folder, names with and without title ids,
 * updates in an `updates` folder, DLC in a `dlc` folder.
 */
class SwitchLibraryTest {
    private val fs = InMemoryFileSystem()
    private val root = "/roms/switch"

    private suspend fun scan(contentFolders: List<ContentFolder> = emptyList()): FolderScanResult =
        FolderInterpreter(fs).scanPlatformFolder(
            PlatformCatalog.byId("switch")!!, root, LibrarySourceId(1), FolderPolicyResolver.CatalogDefaults,
            contentFolders = contentFolders,
        )

    private fun List<ScannedGame>.at(path: String) = single { it.path == "$root/$path" }
    private fun ScannedGame.count(kind: ContentKind) = content.count { it.kind == kind }
    private fun file(path: String, size: Long) = fs.file("$root/$path", size = size)

    @Test
    fun everyGameOnceWithItsUpdatesAndDlcWhereverTheyAre() = runTest {
        // A game in its own folder, with ids.
        file("Harbor Lights/Harbor Lights [0100AAAA11112000][v0][US].nsp", 2000)
        file("Harbor Lights/Harbor Lights [0100AAAA11112800][v196608][US].nsp", 300)
        file("Harbor Lights/Harbor Lights [Lighthouse Pack] [0100AAAA11113001][v0][US].nsp", 10)
        file("Harbor Lights/Harbor Lights [Fog Pack] [0100AAAA11113002][v0][US].nsp", 11)
        // Without ids: a version, and something in brackets.
        file("Moss Garden/Moss Garden.nsp", 1500)
        file("Moss Garden/Moss Garden v1.1.3.nsp", 200)
        file("Moss Garden/Moss Garden [New Uniform Set].nsp", 5)
        // An update named with its game's own id.
        file("Paper Kites/Paper Kites [0100BBBB22224000][USA][v0](eShop).nsp", 3000)
        file("Paper Kites/Paper Kites [0100BBBB22224000][USA][v131072].nsp", 400)
        // Two games and their updates in one folder.
        file("Star Rally 1+2/Star Rally .nsp", 900)
        file("Star Rally 1+2/Star Rally 2 .nsp", 950)
        file("Star Rally 1+2/star_rally_v1.3.1.nsp", 50)
        file("Star Rally 1+2/star_rally_2_v1.3.1.nsp", 60)
        file("Mountain Trek/Mountain Trek Base eShop NSP.nsp", 800)
        file("Mountain Trek/Mountain Trek Update 1.0.3.37670 NSP.nsp", 80)
        // One big folder: copies of the above, an older update, DLC only found here, a game only found here.
        file("1- All/Harbor Lights [0100AAAA11112000][v0][US].nsp", 2000)
        file("1- All/Harbor Lights [0100AAAA11112800][v196608][US].nsp", 300)
        file("1- All/Harbor Lights [0100AAAA11112800][v131072][US].nsp", 250)
        file("1- All/Harbor Lights [Lighthouse Pack] [0100AAAA11113001][v0][US].nsp", 10)
        file("1- All/Moss Garden.nsp", 1500)
        file("1- All/Moss Garden v1.1.3.nsp", 200)
        file("1- All/Moss Garden [New Uniform Set].nsp", 5)
        file("1- All/Moss Garden DLC.Seed.Pack.nsp", 7)
        file("1- All/Tide Walker v0__.nsp", 700)
        file("1- All/Tide Walker 0100FFFF66668800__v65536__.nsz", 20)
        // Updates and DLC in folders of their own.
        file("Cloud Ferry [0100DDDD44446000][v0].nsp", 600)
        file("updates/Cloud Ferry [0100DDDD44446800][v65536].nsp", 30)
        file("dlc/Cloud Ferry - Captain Hat.nsp", 3)
        // An update whose game isn't here stays, so nothing disappears.
        file("Lone Isle v1.0.2.nsp", 40)

        val result = scan()
        val games = result.games
        assertEquals(9, games.size, games.joinToString("\n") { it.path })

        val harbor = games.at("Harbor Lights")
        assertEquals(2, harbor.count(ContentKind.UPDATE), "both versions of its update")
        assertEquals(2, harbor.count(ContentKind.DLC), "each DLC once")
        val moss = games.at("Moss Garden")
        assertEquals("$root/Moss Garden/Moss Garden.nsp", moss.launchPath)
        assertEquals(1, moss.count(ContentKind.UPDATE))
        assertEquals(2, moss.count(ContentKind.DLC), "the uniform set, and the seed pack from the big folder")
        assertEquals(1, games.at("Paper Kites").count(ContentKind.UPDATE))
        assertEquals(listOf("star_rally_v1.3.1.nsp"), games.at("Star Rally 1+2/Star Rally .nsp").content.map { it.name })
        assertEquals(listOf("star_rally_2_v1.3.1.nsp"), games.at("Star Rally 1+2/Star Rally 2 .nsp").content.map { it.name })
        assertEquals(1, games.at("Mountain Trek").count(ContentKind.UPDATE))
        val tide = games.at("1- All/Tide Walker v0__.nsp")
        assertEquals(1, tide.count(ContentKind.UPDATE), "an .nsz update")
        val ferry = games.at("Cloud Ferry [0100DDDD44446000][v0].nsp")
        assertEquals(1, ferry.count(ContentKind.UPDATE))
        assertEquals(1, ferry.count(ContentKind.DLC))
        games.at("Lone Isle v1.0.2.nsp")

        // The copies are reported, so entries kept for them before are forgotten, not shown missing.
        assertTrue("$root/1- All/Harbor Lights [0100AAAA11112000][v0][US].nsp" in result.absorbed)
        assertTrue("$root/1- All/Moss Garden.nsp" in result.absorbed)
        assertTrue("$root/updates/Cloud Ferry [0100DDDD44446800][v65536].nsp" in result.absorbed)
    }

    @Test
    fun anotherRegionOrAnotherDumpIsAnotherGame() = runTest {
        file("Harbor Lights [0100AAAA11112000][v0][US].nsp", 2000)
        file("Harbor Lights [0100EEEE55558000][v0][EU].nsp", 2010)
        file("Harbor Lights [0100AAAA11112000]+[v3.0.0+12DLC][Patched].xci", 2600)
        assertEquals(3, scan().games.size)
    }

    @Test
    fun updatesAndDlcKeptInFoldersOfTheirOwnJoinTheirGames() = runTest {
        file("Harbor Lights.xci", 2000)
        fs.file("/elsewhere/updates/Harbor Lights.nsp", size = 300)
        fs.file("/elsewhere/dlc/Harbor Lights Fog.nsp", size = 11)
        fs.file("/elsewhere/dlc/Unknown Game Extra.nsp", size = 9)

        val games = scan(listOf(ContentFolder(ContentKind.UPDATE, "/elsewhere/updates"), ContentFolder(ContentKind.DLC, "/elsewhere/dlc"))).games
        val harbor = games.at("Harbor Lights.xci")
        assertEquals(listOf("/elsewhere/updates/Harbor Lights.nsp"), harbor.content.filter { it.kind == ContentKind.UPDATE }.map { it.path })
        assertEquals(listOf("/elsewhere/dlc/Harbor Lights Fog.nsp"), harbor.content.filter { it.kind == ContentKind.DLC }.map { it.path })
        // DLC for a game that isn't here stays on its own.
        assertEquals(2, games.size)
    }
}
