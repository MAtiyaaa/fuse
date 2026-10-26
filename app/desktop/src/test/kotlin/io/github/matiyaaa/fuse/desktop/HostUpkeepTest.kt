package io.github.matiyaaa.fuse.desktop

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostUpkeepTest {
    private val dir = Files.createTempDirectory("fuse-upkeep").toFile().apply { deleteOnExit() }

    @Test
    fun anUpdatedProgramStopsTheHostOnceItHoldsStill() = runBlocking {
        val program = File(dir, "Fuse.AppImage").apply { writeText("0.4.2.5") }
        val upkeep = HostUpkeep(program, newerFuse = { null }, update = null)
        assertFalse(upkeep.check(), "nothing changed")
        // An update puts a new file at the same path, as Fuse's own updater does.
        val next = File(dir, ".Fuse.AppImage.part").apply { writeText("0.4.2.6, a little longer") }
        Files.move(next.toPath(), program.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertFalse(upkeep.check(), "first seen changed: it may still be written")
        assertTrue(upkeep.check(), "the same new file a minute later: start again on it")
    }

    @Test
    fun aStartHeldBackIsAskedForAgainButSaidOnce() = runBlocking {
        val program = File(dir, "Fuse3.AppImage").apply { writeText("0.4.4") }
        val logs = mutableListOf<String>()
        val upkeep = HostUpkeep(program, newerFuse = { null }, update = null, log = { logs += it })
        val next = File(dir, ".Fuse3.AppImage.part").apply { writeText("0.4.4.1, a little longer") }
        Files.move(next.toPath(), program.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertFalse(upkeep.check())
        // The host holds its start while it can't use its data folder, and asks again each minute.
        repeat(3) { assertTrue(upkeep.check()) }
        assertEquals(1, logs.count { "starts again" in it }, logs.joinToString())
    }

    @Test
    fun aHostBehindItsDevicesUpdatesItselfAndNotOverAndOver() = runBlocking {
        val program = File(dir, "Fuse2.AppImage").apply { writeText("old") }
        var clock = 0L
        var tries = 0
        val upkeep = HostUpkeep(program, newerFuse = { "0.4.2.6" }, update = { tries++; Result.failure(IllegalStateException("offline")) }, now = { clock })
        assertFalse(upkeep.check())
        assertFalse(upkeep.check())
        assertEquals(1, tries, "tried once, then waits")
        clock += HostUpkeep.RETRY_MS
        upkeep.check()
        assertEquals(2, tries, "and again hours later")
    }

    @Test
    fun aHostAsNewAsItsDevicesLeavesItselfAlone() = runBlocking {
        var tries = 0
        val upkeep = HostUpkeep(null, newerFuse = { null }, update = { tries++; Result.success(Unit) })
        repeat(3) { assertFalse(upkeep.check()) }
        assertEquals(0, tries)
    }
}
