package io.github.matiyaaa.fuse.sync

import io.github.matiyaaa.fuse.romm.RommSave
import io.github.matiyaaa.fuse.sync.romm.JvmRommSaves
import io.github.matiyaaa.fuse.sync.romm.RommSaveStanding
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** How a game's save here stands beside RomM's newest, for the RomM Saves tab; nothing moves. */
class RommStandingTest {
    private val dir = Files.createTempDirectory("fuse-romm-standing").toFile().apply { deleteOnExit() }
    private val rom = File(dir, "Golden Sun.gba").apply { writeBytes(ByteArray(64)) }
    private val sav = File(dir, "Golden Sun.sav")
    private val query = SaveQuery(GameKey.of("gba", null, null, "Golden Sun"), "gba", rom.path, "linux.mgba", title = "Golden Sun")
    private val saves = JvmRommSaves(File(dir, "state"), { FileSaveEnvironment("LINUX") }, { null }, { "https://romm" }, "Desk", "linux", "test")

    private fun remote(hash: String?) = RommSave(7, 1, "Golden Sun.sav", 32, hash, null, "mgba", "2026-10-13T10:00:00+00:00", null, "Deck")

    @Test
    fun aSaveHereStandsBesideRommsNewest() = runBlocking {
        assertEquals(RommSaveStanding.NOT_HERE, saves.standing(query, 1, remote("x")), "nothing here yet")
        sav.writeBytes(ByteArray(32) { 3 })
        val here = JvmRommSaves.md5(sav)
        assertEquals(RommSaveStanding.IN_STEP, saves.standing(query, 1, remote(here)))
        assertEquals(RommSaveStanding.NEWER_HERE, saves.standing(query, 1, null), "RomM has none of it yet")
        // Never agreed with RomM and different: the person chooses when it next starts.
        assertEquals(RommSaveStanding.BOTH_CHANGED, saves.standing(query, 1, remote("other")))
    }
}
