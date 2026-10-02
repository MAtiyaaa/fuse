package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.library.content.ContentFixtures
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.LibrarySourceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** PS3 and Vita packages are found, and their updates and DLC go to their game by title id. */
class PackageScanTest {
    private val fs = InMemoryFileSystem()

    private suspend fun scan(platform: String, folder: String) =
        FolderInterpreter(fs).scanPlatformFolder(PlatformCatalog.byId(platform)!!, folder, LibrarySourceId(1))

    @Test
    fun ps3PackagesBecomeOneGameWithItsUpdatesAndDlc() = runTest {
        val id = "UP0700-BLUS30443_00-DEMONSSOULS00000"
        fs.bytes("/ROMs/ps3/UP0700-BLUS30443_00-DEMONSSOULS00000.pkg", ContentFixtures.ps3Game(id))
            .bytes("/ROMs/ps3/BLUS30443-ver-0104.pkg", ContentFixtures.ps3Update(id, "01.04"))
            .bytes("/ROMs/ps3/Armour pack.pkg", ContentFixtures.ps3Dlc("UP0700-BLUS30443_00-DEMONSSOULSDLC01"))
            .bytes("/ROMs/ps3/$id.rap", ByteArray(16))
        val games = scan("ps3", "/ROMs/ps3").games
        val game = games.single()
        assertEquals("BLUS30443", game.tags.serial)
        assertEquals(setOf(ContentKind.UPDATE, ContentKind.DLC), game.content.map { it.kind }.toSet())
    }

    @Test
    fun aPs3UpdateGoesToItsDiscGame() = runTest {
        fs.file("/ROMs/ps3/Metal Gear Solid 4 [BLUS30109].iso", size = 100)
            .bytes("/ROMs/ps3/patch.pkg", ContentFixtures.ps3Update("UP0101-BLUS30109_00-MGS4PATCH0000000", "02.00"))
        val game = scan("ps3", "/ROMs/ps3").games.single()
        assertEquals("BLUS30109", game.tags.serial)
        assertEquals(ContentKind.UPDATE, game.content.single().kind)
    }

    @Test
    fun aVitaPackageGameIsFoundWithItsOwnTitle() = runTest {
        val id = "UP9000-PCSA00001_00-GRAVITYRUSH00001"
        fs.bytes("/ROMs/psvita/$id.pkg", ContentFixtures.vitaPkg(id, "gd"))
            .bytes("/ROMs/psvita/$id-patch.pkg", ContentFixtures.vitaPkg(id, "gp", "01.05"))
            .file("/ROMs/psvita/notes.zip", size = 30)
        val game = scan("psvita", "/ROMs/psvita").games.single()
        assertEquals("Gravity Rush", game.title)
        assertEquals("PCSA00001", game.tags.serial)
        assertEquals(ContentKind.UPDATE, game.content.single().kind)
    }

    @Test
    fun aVitaFolderOfPackagesIsOneGame() = runTest {
        val id = "UP9000-PCSA00001_00-GRAVITYRUSH00001"
        fs.bytes("/ROMs/psvita/Gravity Rush/game.pkg", ContentFixtures.vitaPkg(id, "gd"))
            .bytes("/ROMs/psvita/Gravity Rush/update.pkg", ContentFixtures.vitaPkg(id, "gp", "01.05"))
            .bytes("/ROMs/psvita/Gravity Rush/dlc.pkg", ContentFixtures.vitaPkg("UP9000-PCSA00001_00-GRAVITYRUSHDLC01", "ac", type = 0x16))
            .file("/ROMs/psvita/Gravity Rush/zrif.txt", content = ContentFixtures.ZRIF)
        val game = scan("psvita", "/ROMs/psvita").games.single()
        assertEquals("Gravity Rush", game.title)
        assertEquals("/ROMs/psvita/Gravity Rush/game.pkg", game.launchPath)
        assertEquals("PCSA00001", game.tags.serial)
        assertEquals(setOf(ContentKind.UPDATE, ContentKind.DLC), game.content.map { it.kind }.toSet())
    }

    @Test
    fun aVpkIsAVitaGame() = runTest {
        fs.bytes("/ROMs/psvita/gr.vpk", ContentFixtures.b64(ContentFixtures.VPK))
        val game = scan("psvita", "/ROMs/psvita").games.single()
        assertEquals("PCSA00001", game.tags.serial)
    }
}
