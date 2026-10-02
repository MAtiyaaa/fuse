package io.github.matiyaaa.fuse.library.content

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.library.content.ContentFixtures.b64
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What to install, in what order, with which licence; and what is already there. */
class ContentPlanTest {
    private val gameId = "UP0700-BLUS30443_00-DEMONSSOULS00000"
    private val dlcId = "UP0700-BLUS30443_00-DEMONSSOULSDLC01"
    private val vitaId = "UP9000-PCSA00001_00-GRAVITYRUSH00001"
    private val vitaDlc = "UP9000-PCSA00001_00-GRAVITYRUSHDLC01"

    /** A PS3 game folder on a USB drive (spaces and all), with its updates out of order and its DLC. */
    private fun ps3Drive(fs: InMemoryFileSystem = InMemoryFileSystem(), root: String = "/run/media/me/USB Drive/PS3/Demon's Souls"): InMemoryFileSystem = fs
        .bytes("$root/Demon's Souls [BLUS30443].pkg", ContentFixtures.ps3Game(gameId))
        .bytes("$root/Updates/Update 10.pkg", ContentFixtures.ps3Update(gameId, "01.10"))
        .bytes("$root/Updates/Update 2.pkg", ContentFixtures.ps3Update(gameId, "01.02"))
        .bytes("$root/DLC/Armour.pkg", ContentFixtures.ps3Dlc(dlcId))
        .bytes("$root/$gameId.rap", ByteArray(16) { 1 })

    private suspend fun plan(fs: InMemoryFileSystem, paths: List<String>, emulator: ContentEmulator, storage: List<String>, picked: Map<String, String> = emptyMap(), recorded: Map<String, Long> = emptyMap()): ContentPlan {
        val reader = ContentSourceReader(fs)
        val sources = reader.read(reader.collect(paths))
        val installed = when (emulator) {
            ContentEmulator.RPCS3 -> InstalledContentReader(fs).rpcs3(storage)
            ContentEmulator.VITA3K -> InstalledContentReader(fs).vita3k(storage)
        }
        return ContentPlanner.plan(emulator, sources, installed, recorded, picked)
    }

    @Test
    fun ps3InstallsLicencesThenTheGameThenUpdatesInOrderThenDlc() = runTest {
        val fs = ps3Drive().dir("/home/me/.config/rpcs3/dev_hdd0/game")
        val p = plan(fs, listOf("/run/media/me/USB Drive/PS3/Demon's Souls"), ContentEmulator.RPCS3, listOf("/home/me/.config/rpcs3/dev_hdd0"))
        assertEquals(
            listOf("$gameId.rap", "Demon's Souls [BLUS30443].pkg", "Update 2.pkg", "Update 10.pkg"),
            p.toInstall.map { it.fileName },
            "the DLC waits for its licence",
        )
        assertEquals(listOf(ItemRole.LICENCE, ItemRole.GAME, ItemRole.UPDATE, ItemRole.UPDATE), p.toInstall.map { it.role })
        assertEquals("$gameId.rap", p.toInstall.first().installAs)
        val dlc = p.items.single { it.role == ItemRole.DLC }
        assertEquals(ItemStatus.NEEDS_LICENCE, dlc.status)
        assertEquals(listOf(ContentState.NEEDS_INSTALL, ContentState.MISSING_LICENCE, ContentState.UPDATE_AVAILABLE, ContentState.DLC_AVAILABLE), p.states)
        assertTrue(p.storageReadable)
    }

    @Test
    fun aSingleUnnamedRapIsTheSingleMissingLicence() = runTest {
        val fs = InMemoryFileSystem()
            .bytes("/mnt/sd/ps3/game.pkg", ContentFixtures.ps3Game(gameId))
            .bytes("/mnt/sd/ps3/licence.rap", ByteArray(16) { 3 })
            .dir("/mnt/sd/rpcs3/dev_hdd0/game")
        val p = plan(fs, listOf("/mnt/sd/ps3"), ContentEmulator.RPCS3, listOf("/mnt/sd/rpcs3/dev_hdd0"))
        val licence = p.toInstall.first()
        assertEquals(LicenceSource.RENAMED, licence.licence?.source)
        assertEquals("/mnt/sd/ps3/licence.rap", licence.path)
        assertEquals("$gameId.rap", licence.installAs, "copied under the name RPCS3 looks for")
        assertTrue(p.missingLicences.isEmpty())
    }

    @Test
    fun aPickedLicenceIsUsedForThatContent() = runTest {
        val fs = InMemoryFileSystem()
            .bytes("/g/game.pkg", ContentFixtures.ps3Game(gameId))
            .bytes("/elsewhere/mine.rap", ByteArray(16) { 4 })
            .dir("/h/game")
        val missing = plan(fs, listOf("/g"), ContentEmulator.RPCS3, listOf("/h"))
        assertEquals(listOf(ContentState.NEEDS_INSTALL, ContentState.MISSING_LICENCE), missing.states)
        assertTrue(missing.toInstall.isEmpty(), "nothing is attempted while the licence is missing")
        val picked = plan(fs, listOf("/g"), ContentEmulator.RPCS3, listOf("/h"), picked = mapOf(gameId to "/elsewhere/mine.rap"))
        assertEquals(LicenceSource.PICKED, picked.toInstall.first().licence?.source)
        assertEquals(listOf("mine.rap", "game.pkg"), picked.toInstall.map { it.fileName })
    }

    @Test
    fun whatRpcs3AlreadyHoldsIsLeftAlone() = runTest {
        val hdd = "/home/me/.config/rpcs3/dev_hdd0"
        val fs = ps3Drive()
            .bytes("$hdd/game/BLUS30443/PARAM.SFO", ContentFixtures.sfo("APP_VER" to "01.02", "CATEGORY" to "HG", "TITLE" to "Demon's Souls", "TITLE_ID" to "BLUS30443"))
            .bytes("$hdd/home/00000001/exdata/$gameId.rap", ByteArray(16))
            .bytes("$hdd/home/00000001/exdata/$dlcId.rap", ByteArray(16))
        val p = plan(fs, listOf("/run/media/me/USB Drive/PS3/Demon's Souls"), ContentEmulator.RPCS3, listOf(hdd))
        assertEquals(ItemStatus.INSTALLED, p.items.single { it.role == ItemRole.GAME }.status)
        assertEquals(ItemStatus.INSTALLED, p.items.single { it.fileName == "Update 2.pkg" }.status)
        assertEquals(listOf("Update 10.pkg", "Armour.pkg"), p.toInstall.map { it.fileName }, "only what's newer, and the DLC now its licence is there")
        assertEquals(LicenceSource.INSTALLED, p.items.single { it.role == ItemRole.DLC }.licence?.source)
        assertTrue(p.items.none { it.role == ItemRole.LICENCE && it.needsWork }, "a licence RPCS3 has isn't copied again")
        assertEquals(listOf(ContentState.READY, ContentState.UPDATE_AVAILABLE, ContentState.DLC_AVAILABLE), p.states)
    }

    @Test
    fun aRetryOnlyDoesWhatIsLeft() = runTest {
        val hdd = "/h/dev_hdd0"
        val fs = ps3Drive(root = "/g").bytes("$hdd/home/00000001/exdata/$dlcId.rap", ByteArray(16)).dir("$hdd/game")
        val first = plan(fs, listOf("/g"), ContentEmulator.RPCS3, listOf(hdd))
        assertEquals(5, first.toInstall.size)
        // The run stopped after the game and the first update.
        fs.bytes("$hdd/game/BLUS30443/PARAM.SFO", ContentFixtures.sfo("APP_VER" to "01.02", "CATEGORY" to "HG", "TITLE_ID" to "BLUS30443"))
            .bytes("$hdd/home/00000001/exdata/$gameId.rap", ByteArray(16))
        val retry = plan(fs, listOf("/g"), ContentEmulator.RPCS3, listOf(hdd))
        assertEquals(listOf("Update 10.pkg", "Armour.pkg"), retry.toInstall.map { it.fileName })
        // Everything in: the DLC was recorded by Fuse after it checked the install, and the last update is in PARAM.SFO.
        fs.bytes("$hdd/game/BLUS30443/PARAM.SFO", ContentFixtures.sfo("APP_VER" to "01.10", "CATEGORY" to "HG", "TITLE_ID" to "BLUS30443"))
        val armour = fs.stat("/g/DLC/Armour.pkg")!!
        val done = plan(fs, listOf("/g"), ContentEmulator.RPCS3, listOf(hdd), recorded = mapOf(armour.path to armour.sizeBytes))
        assertTrue(done.toInstall.isEmpty())
        assertEquals(listOf(ContentState.READY), done.states)
    }

    @Test
    fun aDiscGamesUpdateFolderIsNotTheGame() = runTest {
        val hdd = "/h/dev_hdd0"
        val fs = InMemoryFileSystem()
            .bytes("/g/patch.pkg", ContentFixtures.ps3Update("UP9000-BCUS98174_00-MGS4PATCH0000000", "02.00"))
            .bytes("$hdd/game/BCUS98174/PARAM.SFO", ContentFixtures.sfo("APP_VER" to "01.00", "CATEGORY" to "GD", "TITLE_ID" to "BCUS98174"))
        val reader = ContentSourceReader(fs)
        val p = ContentPlanner.plan(ContentEmulator.RPCS3, reader.read(reader.collect(listOf("/g"))), InstalledContentReader(fs).rpcs3(listOf(hdd)), playsWithoutInstall = true)
        assertTrue(!p.gameInstalled)
        assertEquals(listOf(ContentState.READY, ContentState.UPDATE_AVAILABLE), p.states, "the disc plays; its update is waiting")
    }

    @Test
    fun storageThatCantBeReadSaysSo() = runTest {
        val fs = ps3Drive(root = "/g")
        val p = plan(fs, listOf("/g"), ContentEmulator.RPCS3, listOf("/nowhere/dev_hdd0"))
        assertTrue(!p.storageReadable)
        assertTrue(!p.gameInstalled)
    }

    @Test
    fun vitaPackagesFindTheirZrifAndUpdatesUseTheGamesLicence() = runTest {
        val pref = "/home/me/.local/share/Vita3K/Vita3K"
        val fs = InMemoryFileSystem()
            .bytes("/media/sd/vita/Gravity Rush/game.pkg", ContentFixtures.vitaPkg(vitaId, "gd"))
            .bytes("/media/sd/vita/Gravity Rush/patch.pkg", ContentFixtures.vitaPkg(vitaId, "gp", "01.05"))
            .bytes("/media/sd/vita/Gravity Rush/dlc.pkg", ContentFixtures.vitaPkg(vitaDlc, "ac", type = 0x16))
            .file("/media/sd/vita/Gravity Rush/PSV_GAMES.tsv", content = "PCSA00001\tUS\tGravity Rush\tx\t${ContentFixtures.ZRIF}\t$vitaId\n")
            .dir("$pref/ux0/app")
        val p = plan(fs, listOf("/media/sd/vita/Gravity Rush"), ContentEmulator.VITA3K, listOf(pref))
        assertEquals(listOf("game.pkg", "patch.pkg"), p.toInstall.map { it.fileName })
        assertEquals(ContentFixtures.ZRIF, p.toInstall[0].licence?.zrif)
        assertEquals(ContentFixtures.ZRIF, p.toInstall[1].licence?.zrif, "the update opens with the game's key")
        assertEquals(ItemStatus.NEEDS_LICENCE, p.items.single { it.role == ItemRole.DLC }.status)
        assertEquals(listOf(ContentState.NEEDS_INSTALL, ContentState.MISSING_LICENCE, ContentState.UPDATE_AVAILABLE, ContentState.DLC_AVAILABLE), p.states)
    }

    @Test
    fun vita3kStorageTellsWhatIsInstalled() = runTest {
        val pref = "/home/me/.local/share/Vita3K/Vita3K"
        val fs = InMemoryFileSystem()
            .bytes("/v/game.pkg", ContentFixtures.vitaPkg(vitaId, "gd"))
            .bytes("/v/patch.pkg", ContentFixtures.vitaPkg(vitaId, "gp", "01.05"))
            .bytes("/v/dlc.pkg", ContentFixtures.vitaPkg(vitaDlc, "ac", type = 0x16))
            .bytes("$pref/ux0/app/PCSA00001/sce_sys/param.sfo", ContentFixtures.sfo("APP_VER" to "01.00", "CATEGORY" to "gd", "TITLE_ID" to "PCSA00001"))
            .bytes("$pref/ux0/patch/PCSA00001/sce_sys/param.sfo", ContentFixtures.sfo("APP_VER" to "01.05", "CATEGORY" to "gp", "TITLE_ID" to "PCSA00001"))
            .dir("$pref/ux0/addcont/PCSA00001/GRAVITYRUSHDLC01")
            .bytes("$pref/ux0/license/PCSA00001/$vitaId.rif", ContentFixtures.rif(vitaId))
        val p = plan(fs, listOf("/v"), ContentEmulator.VITA3K, listOf(pref))
        assertTrue(p.toInstall.isEmpty())
        assertTrue(p.items.all { it.status == ItemStatus.INSTALLED })
        assertEquals(listOf(ContentState.READY), p.states)
    }

    @Test
    fun aLicenceAlreadyInVita3kOpensTheUpdate() = runTest {
        val pref = "/p"
        val fs = InMemoryFileSystem()
            .bytes("/v/patch.pkg", ContentFixtures.vitaPkg(vitaId, "gp", "01.05"))
            .bytes("$pref/ux0/app/PCSA00001/sce_sys/param.sfo", ContentFixtures.sfo("APP_VER" to "01.00", "TITLE_ID" to "PCSA00001"))
            .bytes("$pref/ux0/license/PCSA00001/$vitaId.rif", ContentFixtures.rif(vitaId))
        val p = plan(fs, listOf("/v"), ContentEmulator.VITA3K, listOf(pref))
        val update = p.toInstall.single()
        assertEquals(LicenceSource.INSTALLED, update.licence?.source)
        assertEquals("$pref/ux0/license/PCSA00001/$vitaId.rif", update.licence?.path)
        assertEquals(listOf(ContentState.READY, ContentState.UPDATE_AVAILABLE), p.states)
    }

    @Test
    fun aVpkCarriesItsOwnLicence() = runTest {
        val fs = InMemoryFileSystem().bytes("/v/Gravity Rush.vpk", b64(ContentFixtures.VPK)).dir("/p/ux0/app")
        val p = plan(fs, listOf("/v"), ContentEmulator.VITA3K, listOf("/p"))
        val item = p.toInstall.single()
        assertTrue(item.archive)
        assertEquals(LicenceSource.BUILT_IN, item.licence?.source)
        assertEquals(listOf(ContentState.NEEDS_INSTALL), p.states)
        assertNull(item.why)
    }

    @Test
    fun theSameFileTwiceIsInstalledOnce() = runTest {
        val fs = InMemoryFileSystem()
            .bytes("/g/a/Update.pkg", ContentFixtures.ps3Update(gameId, "01.02"))
            .bytes("/g/b/Update copy.pkg", ContentFixtures.ps3Update(gameId, "01.02"))
            .dir("/h/game")
        val p = plan(fs, listOf("/g"), ContentEmulator.RPCS3, listOf("/h"))
        assertEquals(1, p.toInstall.size)
        assertEquals(ItemStatus.SUPERSEDED, p.items.last().status)
    }
}
