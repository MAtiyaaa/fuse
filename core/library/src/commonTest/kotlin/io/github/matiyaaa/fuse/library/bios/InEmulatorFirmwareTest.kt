package io.github.matiyaaa.fuse.library.bios

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.BiosStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InEmulatorFirmwareTest {
    private val unknown = BiosStatus(BiosState.UNKNOWN, note = "Install it from the emulator.")
    private val eden = EmulatorDataFolder("switch", "yuzu", "Eden", "/home/deck/.local/share/eden")
    private val ryujinx = EmulatorDataFolder("switch", "ryujinx", "Ryujinx", "/home/deck/.config/Ryujinx")

    @Test
    fun switchFirmwareInstalledInAnyEmulatorIsPresent() = runTest {
        val fs = InMemoryFileSystem()
            .dir("/home/deck/.local/share/eden/nand/system/Contents/registered")
            .file("/home/deck/.config/Ryujinx/system/prod.keys", size = 9000)
            .file("/home/deck/.config/Ryujinx/bis/system/Contents/registered/0100.nca/00", size = 10)
        val status = InEmulatorFirmware.merge("switch", unknown, listOf(eden, ryujinx), fs)
        assertEquals(BiosState.READY, status.state)
        assertTrue(status.note!!.contains("Ryujinx"))
        // Present, not verified: nothing was hashed.
        assertFalse(status.allVerified)
    }

    @Test
    fun aFolderWithoutItIsSaidPlainlyAndNeverCalledMissing() = runTest {
        val fs = InMemoryFileSystem().file("/home/deck/.local/share/eden/keys/prod.keys", size = 9000)
        val status = InEmulatorFirmware.merge("switch", unknown, listOf(eden), fs)
        assertEquals(BiosState.UNKNOWN, status.state)
        assertTrue(status.note!!.contains("Eden has no system firmware"), status.note)
        assertTrue("/home/deck/.local/share/eden" in status.searched)
    }

    @Test
    fun rpcs3NeedsItsSystemSoftwareModule() = runTest {
        val rpcs3 = EmulatorDataFolder("ps3", "rpcs3", "RPCS3", "/home/deck/.config/rpcs3")
        val empty = InMemoryFileSystem().dir("/home/deck/.config/rpcs3/dev_flash/vsh/module")
        assertEquals(BiosState.UNKNOWN, InEmulatorFirmware.merge("ps3", unknown, listOf(rpcs3), empty).state)
        val installed = InMemoryFileSystem().file("/home/deck/.config/rpcs3/dev_flash/vsh/module/vsh.self", size = 100)
        assertEquals(BiosState.READY, InEmulatorFirmware.merge("ps3", unknown, listOf(rpcs3), installed).state)
    }

    @Test
    fun withNoEmulatorFolderTheCheckStands() = runTest {
        assertSame(unknown, InEmulatorFirmware.merge("switch", unknown, listOf(eden), InMemoryFileSystem()))
        assertSame(unknown, InEmulatorFirmware.merge("psx", unknown, listOf(eden), InMemoryFileSystem()))
    }
}
