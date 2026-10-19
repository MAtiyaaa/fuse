package io.github.matiyaaa.fuse.romm

import kotlin.test.Test
import kotlin.test.assertEquals

/** A game from RomM placed so it plays: its own layout kept, so the scan and the emulator find each part. */
class RommPlacementTest {
    @Test
    fun aPs3FolderGameKeepsItsDiscLayoutWithItsUpdateBeside() {
        val files = listOf(
            RommFile(1, "PS3_DISC.SFB", ""),
            RommFile(2, "PARAM.SFO", "PS3_GAME"),
            RommFile(3, "EBOOT.BIN", "PS3_GAME/USRDIR"),
            RommFile(4, "BLUS30443-patch.pkg", "update", category = "update"),
        )
        val rom = RommRom(9, 30, "ps3", "Demon's Souls", "Demon's Souls (USA)", files = files, multi = true)
        val p = RommPlacement.layout(rom, files, "/games/ps3")
        val game = "/games/ps3/Demon's Souls (USA)"
        assertEquals(game, p.gamePath)
        // The disc's own layout at the top of the game's folder: what RPCS3 boots, and what Fuse's
        // scan reads as one PS3 game (PS3_DISC.SFB, PS3_GAME/PARAM.SFO).
        assertEquals("$game/PS3_DISC.SFB", p.files[1])
        assertEquals("$game/PS3_GAME/PARAM.SFO", p.files[2])
        assertEquals("$game/PS3_GAME/USRDIR/EBOOT.BIN", p.files[3])
        // Its update in `update/`, which the scan reads as this game's content (installed by RPCS3 from Installed Content).
        assertEquals("$game/update/BLUS30443-patch.pkg", p.files[4])
        assertEquals(io.github.matiyaaa.fuse.model.ContentKind.UPDATE, files[3].kind)
    }

    @Test
    fun aGameOfOneFileSitsInItsSystemFolder() {
        val f = RommFile(11, "Metroid Fusion (USA).gba")
        val p = RommPlacement.layout(RommRom(1, 10, "gba", "Metroid Fusion", "Metroid Fusion (USA).gba", files = listOf(f)), listOf(f), "/games/gba/")
        assertEquals("/games/gba/Metroid Fusion (USA).gba", p.files[11])
    }
}
