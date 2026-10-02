package io.github.matiyaaa.fuse.library.content

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.library.content.ContentFixtures.b64
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Package, archive and licence detection, read the way RPCS3, Vita3K and pkg2zip read them. */
class ContentReadersTest {
    private val gameId = "UP0700-BLUS30443_00-DEMONSSOULS00000"
    private val vitaId = "UP9000-PCSA00001_00-GRAVITYRUSH00001"

    @Test
    fun inflateMatchesZlib() {
        val out = Inflate.raw(b64(ContentFixtures.RAW), maxOut = 64 * 1024)
        assertNotNull(out)
        assertEquals(ContentFixtures.deflatedText, out.decodeToString())
        assertEquals(2272108932L, Inflate.adler32(out))
        // Too small a limit, or damaged data: nothing, never a crash.
        assertNull(Inflate.raw(b64(ContentFixtures.RAW), maxOut = 100))
        assertNull(Inflate.raw(b64(ContentFixtures.RAW).copyOf(20), maxOut = 64 * 1024))
    }

    @Test
    fun zrifsDecodeToTheirLicence() {
        val rif = Licences.decodeZrif(ContentFixtures.ZRIF)
        assertNotNull(rif)
        assertContentEquals(ContentFixtures.rif(vitaId), rif)
        assertEquals(vitaId, Licences.zrifContentId(ContentFixtures.ZRIF))
        // A key with a character changed fails its checksum.
        val z = ContentFixtures.ZRIF
        assertNull(Licences.zrifContentId(z.replaceRange(60, 61, if (z[60] == 'A') "B" else "A")))
        assertNull(Licences.zrifContentId("KO5inot-a-key"))
    }

    @Test
    fun aLicenceTurnsBackIntoTheZrifVita3kTakes() {
        val zrif = Licences.zrifOf(ContentFixtures.rif(vitaId))
        assertNotNull(zrif)
        assertTrue(zrif.startsWith("KO5i"), "written like a real zRIF")
        assertContentEquals(ContentFixtures.rif(vitaId), Licences.decodeZrif(zrif))
        assertNull(Licences.zrifOf(ByteArray(512)), "a licence without a content id isn't one")
    }

    @Test
    fun zrifsAreFoundInNoPayStationListsAndNotes() {
        val tsv = "Title ID\tRegion\tName\tPKG direct link\tzRIF\tContent ID\n" +
            "PCSA00001\tUS\tGravity Rush\thttps://example/x.pkg\t${ContentFixtures.ZRIF}\t$vitaId\n" +
            "PCSA00002\tUS\tBroken\tMISSING\tKO5iAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\tUP9000-PCSA00002_00-0000000000000000\n"
        assertEquals(listOf(ContentFixtures.ZRIF to vitaId), Licences.zrifsIn(tsv))
    }

    @Test
    fun ps3PackagesAreToldApartByTheirHeader() = runTest {
        val fs = InMemoryFileSystem()
            .bytes("/g/game.pkg", ContentFixtures.ps3Game(gameId))
            .bytes("/g/patch.pkg", ContentFixtures.ps3Update(gameId, "01.04"))
            .bytes("/g/dlc.pkg", ContentFixtures.ps3Dlc("UP0700-BLUS30443_00-DEMONSSOULSDLC01"))
            .bytes("/g/not.pkg", "this is not a package at all, just some text".encodeToByteArray())
        val game = PsPackages.read(fs, "/g/game.pkg")!!
        assertEquals(PsSystem.PS3, game.system)
        assertEquals(PackageKind.GAME, game.kind)
        assertEquals("BLUS30443", game.titleId)
        assertEquals(gameId, game.contentId)
        assertTrue(game.needsLicence)
        val patch = PsPackages.read(fs, "/g/patch.pkg")!!
        assertEquals(PackageKind.UPDATE, patch.kind)
        assertEquals("01.04", patch.version)
        assertTrue(!patch.needsLicence)
        assertEquals(PackageKind.DLC, PsPackages.read(fs, "/g/dlc.pkg")!!.kind)
        assertNull(PsPackages.read(fs, "/g/not.pkg"))
    }

    @Test
    fun vitaPackagesUseTheirParamSfo() = runTest {
        val fs = InMemoryFileSystem()
            .bytes("/v/game.pkg", ContentFixtures.vitaPkg(vitaId, "gd"))
            .bytes("/v/patch.pkg", ContentFixtures.vitaPkg(vitaId, "gp", "01.05"))
            .bytes("/v/dlc.pkg", ContentFixtures.vitaPkg("UP9000-PCSA00001_00-GRAVITYRUSHDLC01", "ac", type = 0x16))
            .bytes("/v/psm.pkg", ContentFixtures.pkg(2, "UP9000-NPOA00001_00-PSMGAME000000000", 0x18))
        val game = PsPackages.read(fs, "/v/game.pkg")!!
        assertEquals(PsSystem.VITA, game.system)
        assertEquals(PackageKind.GAME, game.kind)
        assertEquals("Gravity Rush", game.title)
        assertEquals(PackageKind.UPDATE, PsPackages.read(fs, "/v/patch.pkg")!!.kind)
        assertEquals("01.05", PsPackages.read(fs, "/v/patch.pkg")!!.version)
        assertEquals(PackageKind.DLC, PsPackages.read(fs, "/v/dlc.pkg")!!.kind)
        assertEquals(PackageKind.UNSUPPORTED, PsPackages.read(fs, "/v/psm.pkg")!!.kind)
    }

    @Test
    fun vpkArchivesAreReadFromTheirZipDirectory() = runTest {
        val fs = InMemoryFileSystem()
            .bytes("/v/Gravity Rush.vpk", b64(ContentFixtures.VPK))
            .bytes("/v/photos.zip", b64(ContentFixtures.VPK).copyOf(40))
        val reader = ContentSourceReader(fs)
        val a = reader.vitaArchive(fs.stat("/v/Gravity Rush.vpk")!!)
        assertNotNull(a)
        assertEquals("PCSA00001", a.titleId)
        assertEquals("gd", a.category)
        assertEquals("01.00", a.version)
        assertNull(reader.vitaArchive(fs.stat("/v/photos.zip")!!), "a damaged or unrelated zip is passed over")
    }

    @Test
    fun licenceFilesSayWhatTheyAreFor() = runTest {
        val fs = InMemoryFileSystem()
            .bytes("/g/$gameId.rap", ByteArray(16) { 1 })
            .bytes("/g/key.rap", ByteArray(16) { 2 })
            .bytes("/g/broken.rap", ByteArray(5))
            .bytes("/g/dlc.edat", ContentFixtures.edat("UP0700-BLUS30443_00-DEMONSSOULSDLC01"))
            .bytes("/g/work.bin", ContentFixtures.rif(vitaId))
            .file("/g/keys.txt", content = "zRIF: ${ContentFixtures.ZRIF}\n")
        val reader = ContentSourceReader(fs)
        val sources = reader.read(reader.collect(listOf("/g")))
        val byName = sources.licences.associateBy { it.path.substringAfterLast('/') }
        assertEquals(gameId, byName["$gameId.rap"]!!.contentId)
        assertNull(byName["key.rap"]!!.contentId, "a .rap under another name is kept, unmatched")
        assertEquals("UP0700-BLUS30443_00-DEMONSSOULSDLC01", byName["dlc.edat"]!!.contentId)
        assertEquals(vitaId, byName["work.bin"]!!.contentId)
        assertNotNull(byName["work.bin"]!!.zrif)
        assertEquals(listOf("/g/broken.rap"), sources.unreadable)
        assertEquals(vitaId, sources.keys.single().contentId)
    }

    @Test
    fun versionsInNamesAndComparisons() {
        assertEquals("01.02", PsPackages.versionInName("UP0700-BLUS30443_00-DEMONSSOULSPATCH-A0102-V0100-PE.pkg"))
        assertEquals("01.04", PsPackages.versionInName("Demon's Souls Update v1.04.pkg"))
        assertEquals("01.10", PsPackages.versionInName("BLUS30443-ver-0110.pkg"))
        assertNull(PsPackages.versionInName("Demon's Souls.pkg"))
        assertTrue(PsPackages.compareVersions("01.10", "01.09") > 0)
        assertTrue(PsPackages.compareVersions(null, "01.00") < 0)
        assertTrue(ContentPlanner.naturalCompare("Update 2.pkg", "Update 10.pkg") < 0)
    }
}
