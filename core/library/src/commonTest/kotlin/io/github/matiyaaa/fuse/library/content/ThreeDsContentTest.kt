package io.github.matiyaaa.fuse.library.content

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 3DS CIAs read from their TMD, Azahar's SD card read back, and the order they install in. */
class ThreeDsContentTest {
    private val game = "0004000000055D00"
    private val update = "0004000E00055D00"
    private val dlc = "0004008C00055D00"
    private val sdmc = "/home/me/.local/share/azahar-emu/sdmc"

    @Test
    fun ciasSayWhatTheyAreAndTheirVersion() = runTest {
        val fs = InMemoryFileSystem()
            .bytes("/g/game.cia", ContentFixtures.cia(game, 0))
            .bytes("/g/update.cia", ContentFixtures.cia(update, (1 shl 10) or (2 shl 4)))
            .bytes("/g/not.cia", ByteArray(64))
        val g = Cia.read(fs, "/g/game.cia")!!
        assertEquals(game, g.titleId)
        assertEquals(PackageKind.GAME, g.kind)
        val u = Cia.read(fs, "/g/update.cia")!!
        assertEquals(PackageKind.UPDATE, u.kind)
        assertEquals("1.2.0", u.version)
        assertEquals(game, u.gameId)
        assertNull(Cia.read(fs, "/g/not.cia"))
        assertEquals(game, Cia.cartridgeId(InMemoryFileSystem().bytes("/c.3ds", ContentFixtures.cartridge(game)), "/c.3ds"))
    }

    @Test
    fun aGameThenItsUpdatesThenDlc() = runTest {
        val fs = InMemoryFileSystem()
            .bytes("/run/media/me/SD/3ds/Pokemon/dlc.cia", ContentFixtures.cia(dlc, 0))
            .bytes("/run/media/me/SD/3ds/Pokemon/v1.2.cia", ContentFixtures.cia(update, (1 shl 10) or (2 shl 4)))
            .bytes("/run/media/me/SD/3ds/Pokemon/v1.1.cia", ContentFixtures.cia(update, (1 shl 10) or (1 shl 4)))
            .bytes("/run/media/me/SD/3ds/Pokemon/game.cia", ContentFixtures.cia(game, 0))
            .dir("$sdmc/Nintendo 3DS")
        val reader = ContentSourceReader(fs)
        val p = ContentPlanner.plan(ContentEmulator.AZAHAR, reader.read(reader.collect(listOf("/run/media/me/SD/3ds/Pokemon"))), InstalledContentReader(fs).azahar(listOf(sdmc)))
        assertEquals(listOf("game.cia", "v1.1.cia", "v1.2.cia", "dlc.cia"), p.toInstall.map { it.fileName })
        assertEquals(listOf(ContentState.NEEDS_INSTALL, ContentState.UPDATE_AVAILABLE, ContentState.DLC_AVAILABLE), p.states)
    }

    @Test
    fun azaharsSdCardSaysWhatIsInAndWhereItStarts() = runTest {
        val title = Cia.titleDir(sdmc, game)
        val fs = InMemoryFileSystem()
            .bytes("/g/game.cia", ContentFixtures.cia(game, 0))
            .bytes("/g/v1.1.cia", ContentFixtures.cia(update, (1 shl 10) or (1 shl 4)))
            .bytes("$title/content/00000000.tmd", ContentFixtures.tmd(game, 0, listOf(0x2AL)))
            .bytes("$title/content/0000002a.app", ByteArray(32))
            .bytes("${Cia.titleDir(sdmc, update)}/content/00000001.tmd", ContentFixtures.tmd(update, (1 shl 10) or (1 shl 4)))
        assertTrue(title.endsWith("/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000/title/00040000/00055d00"))
        val installed = InstalledContentReader(fs).azahar(listOf(sdmc))
        assertEquals("$title/content/0000002a.app", installed.bootFiles[game])
        val reader = ContentSourceReader(fs)
        val p = ContentPlanner.plan(ContentEmulator.AZAHAR, reader.read(reader.collect(listOf("/g"))), installed)
        assertTrue(p.toInstall.isEmpty())
        assertEquals(listOf(ContentState.READY), p.states)
    }

    @Test
    fun aCartridgeGamePlaysAndItsUpdateWaits() = runTest {
        val fs = InMemoryFileSystem()
            .bytes("/g/Pokemon.3ds", ContentFixtures.cartridge(game))
            .bytes("/g/update.cia", ContentFixtures.cia(update, 1 shl 10))
            .dir("$sdmc/Nintendo 3DS")
        val reader = ContentSourceReader(fs)
        val p = ContentPlanner.plan(ContentEmulator.AZAHAR, reader.read(reader.collect(listOf("/g"))), InstalledContentReader(fs).azahar(listOf(sdmc)))
        assertEquals(listOf(ContentState.READY, ContentState.UPDATE_AVAILABLE), p.states)
    }
}
