package io.github.matiyaaa.fuse.sync

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmuMmcGuardTest {
    private fun card(): File = Files.createTempDirectory("card").toFile().also { c ->
        File(c, "emuMMC/RAW1").mkdirs()
        File(c, "emuMMC/emummc.ini").writeText("[emummc]\nenabled=1\nsector=0x8000\npath=emuMMC/RAW1\nid=0x0000\nnintendo_path=emuMMC/RAW1/Nintendo\n")
        File(c, "emuMMC/RAW1/raw_based").writeBytes(byteArrayOf(0, 0x80.toByte(), 0, 0))
        File(c, "emuMMC/RAW1/Nintendo/Contents").mkdirs()
        File(c, "emuMMC/RAW1/Nintendo/Contents/big.nca").writeBytes(ByteArray(200_000))
    }

    @Test fun aCardThatLostItsEmuMmcSettingsGetsThemBackExactly() {
        val store = Files.createTempDirectory("guard").toFile()
        val c = card()
        val guard = EmuMmcGuard(store)
        assertTrue(guard.snapshot(c))
        val ini = File(c, "emuMMC/emummc.ini").readText()
        // What a phone's storage check did: the settings gone, the Nintendo folder kept.
        File(c, "emuMMC/emummc.ini").delete()
        File(c, "emuMMC/RAW1/raw_based").delete()
        assertEquals(listOf("emuMMC/RAW1/raw_based", "emuMMC/emummc.ini"), guard.lost(c))
        assertEquals(2, guard.restore(c))
        assertEquals(ini, File(c, "emuMMC/emummc.ini").readText())
        assertEquals(listOf<Byte>(0, 0x80.toByte(), 0, 0), File(c, "emuMMC/RAW1/raw_based").readBytes().toList())
        assertTrue(guard.lost(c).isEmpty())
        // The emuMMC's own data is never copied.
        assertFalse(store.walkTopDown().any { it.name == "big.nca" })
    }

    @Test fun anEmuMmcTurnedOffIsLostToo() {
        val store = Files.createTempDirectory("guard").toFile()
        val c = card()
        val guard = EmuMmcGuard(store)
        guard.snapshot(c)
        File(c, "emuMMC/emummc.ini").writeText("[emummc]\nenabled=0\n")
        assertEquals(listOf("emuMMC/emummc.ini"), guard.lost(c))
        // A card seen with its emuMMC off isn't kept as it is: the healthy copy stays.
        assertFalse(guard.snapshot(c))
        guard.restore(c)
        assertTrue(EmuMmcGuard.enabled(File(c, "emuMMC/emummc.ini")))
    }
}
