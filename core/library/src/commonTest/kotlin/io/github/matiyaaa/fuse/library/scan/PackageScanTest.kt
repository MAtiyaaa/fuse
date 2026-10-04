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

    @Test
    fun aZippedAppFolderIsAVitaGame() = runTest {
        val sfo = ContentFixtures.sfo("CATEGORY" to "gd", "TITLE" to "Uncharted: Golden Abyss", "TITLE_ID" to "PCSA00029", "APP_VER" to "01.00")
        val patch = ContentFixtures.sfo("CATEGORY" to "gp", "TITLE" to "Uncharted: Golden Abyss", "TITLE_ID" to "PCSA00029", "APP_VER" to "01.03")
        fs.bytes(
            "/ROMs/psvita/Uncharted - Golden Abyss.zip",
            storedZip("patch/PCSA00029/sce_sys/param.sfo" to patch, "app/PCSA00029/sce_sys/param.sfo" to sfo, "app/PCSA00029/eboot.bin" to ByteArray(64)),
        )
        val game = scan("psvita", "/ROMs/psvita").games.single()
        assertEquals("Uncharted - Golden Abyss", game.title)
        assertEquals("PCSA00029", game.tags.serial)
    }

    @Test
    fun aGameSizedZipFuseCantLookInsideIsStillAGame() = runTest {
        fs.file("/ROMs/psvita/Uncharted - Golden Abyss.zip", size = 3_200L * 1024 * 1024)
            .file("/ROMs/psvita/notes.zip", size = 30)
        val game = scan("psvita", "/ROMs/psvita").games.single()
        assertEquals("Uncharted - Golden Abyss", game.title)
    }

    /** A ZIP with its files stored (not compressed), laid out as zipfile writes one. */
    private fun storedZip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        val central = ArrayList<Byte>()
        fun MutableList<Byte>.le(v: Long, n: Int) = repeat(n) { add((v ushr (8 * it)).toByte()) }
        for ((name, data) in files) {
            val at = out.size.toLong()
            val n = name.encodeToByteArray()
            out.le(0x04034b50, 4); out.le(20, 2); out.le(0, 2); out.le(0, 2); out.le(0, 4); out.le(0, 4)
            out.le(data.size.toLong(), 4); out.le(data.size.toLong(), 4); out.le(n.size.toLong(), 2); out.le(0, 2)
            n.forEach(out::add); data.forEach(out::add)
            central.le(0x02014b50, 4); central.le(20, 2); central.le(20, 2); central.le(0, 2); central.le(0, 2); central.le(0, 4); central.le(0, 4)
            central.le(data.size.toLong(), 4); central.le(data.size.toLong(), 4); central.le(n.size.toLong(), 2); central.le(0, 2); central.le(0, 2)
            central.le(0, 2); central.le(0, 2); central.le(0, 4); central.le(at, 4)
            n.forEach(central::add)
        }
        val cdAt = out.size.toLong()
        out.addAll(central)
        out.le(0x06054b50, 4); out.le(0, 2); out.le(0, 2); out.le(files.size.toLong(), 2); out.le(files.size.toLong(), 2)
        out.le(central.size.toLong(), 4); out.le(cdAt, 4); out.le(0, 2)
        return out.toByteArray()
    }
}

/** 3DS updates and DLC kept as .cia join their game. */
class ThreeDsScanTest {
    private val fs = InMemoryFileSystem()

    @Test
    fun updateCiasJoinTheirCartridge() = runTest {
        fs.bytes("/ROMs/3ds/Pokemon X.3ds", ContentFixtures.cartridge("0004000000055D00"))
            .bytes("/ROMs/3ds/Pokemon X Update.cia", ContentFixtures.cia("0004000E00055D00", 1 shl 10))
            .bytes("/ROMs/3ds/Pokemon X DLC.cia", ContentFixtures.cia("0004008C00055D00", 0))
            .bytes("/ROMs/3ds/Zelda.cia", ContentFixtures.cia("0004000000033500", 0))
        val games = FolderInterpreter(fs).scanPlatformFolder(PlatformCatalog.byId("3ds")!!, "/ROMs/3ds", LibrarySourceId(1)).games
        assertEquals(2, games.size, games.joinToString { it.title + ":" + it.path + ":" + it.content.size })
        val pokemon = games.single { it.title.startsWith("Pokemon") }
        assertEquals(setOf(ContentKind.UPDATE, ContentKind.DLC), pokemon.content.map { it.kind }.toSet())
        // A 3DS title id isn't a serial anything else understands.
        assertEquals(null, pokemon.tags.serial)
    }
}
