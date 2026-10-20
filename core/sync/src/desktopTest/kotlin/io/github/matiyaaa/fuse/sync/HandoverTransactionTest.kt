package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Swapping one person's save for another's on a shared device, offline: a multi-file save is
 * swapped as one transaction, an interruption at any step leaves the previous person's complete
 * save in place (and is undone on the next start), and a save nobody owned is never handed to
 * whoever launches first once someone else uses the device.
 */
class HandoverTransactionTest {
    private lateinit var root: File
    private val game = GameKey.of("ps2", "SLUS-20946", null, "Ratchet and Clank")

    @BeforeTest fun setUp() { root = Files.createTempDirectory("handover").toFile() }
    @AfterTest fun tearDown() { root.deleteRecursively() }

    private fun slot(dir: File): LocalSlot {
        dir.mkdirs()
        val all = dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.map { LocalFile(it.relativeTo(dir).path.replace('\\', '/'), it) }.toList()
        return LocalSlot(game, SaveKind.SAVE, "switch.savedata", dir, all)
    }

    private fun contents(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.associate { it.relativeTo(dir).path.replace('\\', '/') to it.readText() }

    @Test
    fun johnAndMarkKeepTheirOwnMultiFileSavesOffline(): Unit = runBlocking {
        val saves = File(root, "saves")
        val device = SyncDevice(File(root, "device"), "deck", "Deck")
        device.handover("john", slot(saves))
        File(saves, "main.dat").writeText("john main")
        File(saves, "sub/extra.dat").apply { parentFile.mkdirs() }.writeText("john extra")
        // Mark has never played: he starts clean, John's two files are parked together.
        device.handover("mark", slot(saves))
        assertEquals(emptyMap(), contents(saves))
        File(saves, "main.dat").writeText("mark main")
        // John again: both of his files are back, and only his.
        device.handover("john", slot(saves), holderSeconds = { 10 })
        assertEquals(mapOf("main.dat" to "john main", "sub/extra.dat" to "john extra"), contents(saves))
        // Mark again: his single file, without John's extra file left behind.
        device.handover("mark", slot(saves))
        assertEquals(mapOf("main.dat" to "mark main"), contents(saves))
    }

    @Test
    fun aSwapInterruptedAtAnyStepLeavesTheLastPersonsWholeSave(): Unit = runBlocking {
        for (step in 0..2) {
            val base = File(root, "step$step")
            val saves = File(base, "saves")
            val state = File(base, "device")
            val device = SyncDevice(state, "deck", "Deck")
            device.handover("john", slot(saves))
            File(saves, "a.dat").writeText("john a")
            File(saves, "b.dat").writeText("john b")
            device.handover("mark", slot(saves))
            File(saves, "a.dat").writeText("mark a")
            File(saves, "b.dat").writeText("mark b")
            File(saves, "c.dat").writeText("mark c")
            // John comes back, and the swap breaks partway: the launch must not go ahead.
            device.handoverFault = { moved -> if (moved == step) error("power lost at step $step") }
            assertFailsWith<IllegalStateException> { device.handover("john", slot(saves)) }
            assertEquals(mapOf("a.dat" to "mark a", "b.dat" to "mark b", "c.dat" to "mark c"), contents(saves), "step $step rolled back")
            // A fresh start (the app restarted) still swaps cleanly.
            val again = SyncDevice(state, "deck", "Deck")
            again.handover("john", slot(saves))
            assertEquals(mapOf("a.dat" to "john a", "b.dat" to "john b"), contents(saves), "step $step then retried")
            again.handover("mark", slot(saves))
            assertEquals(mapOf("a.dat" to "mark a", "b.dat" to "mark b", "c.dat" to "mark c"), contents(saves), "Mark's save survived step $step")
        }
    }

    @Test
    fun aCrashMidSwapIsUndoneOnTheNextStart(): Unit = runBlocking {
        val saves = File(root, "saves")
        val state = File(root, "device")
        val device = SyncDevice(state, "deck", "Deck")
        device.handover("john", slot(saves))
        File(saves, "a.dat").writeText("john a")
        File(saves, "b.dat").writeText("john b")
        device.handover("mark", slot(saves))
        File(saves, "a.dat").writeText("mark a")
        File(saves, "b.dat").writeText("mark b")
        // The process dies after the first file moved: the journal is left, its rollback never ran.
        val journal = File(state, "save-handover-transaction.json")
        device.handoverFault = { moved ->
            if (moved == 1) {
                val copy = File(root, "journal-at-crash").also { journal.copyTo(it, overwrite = true) }
                val stagedFiles = saves.listFiles()!!.filter { it.name.startsWith(".fuse-import-") }.map { f -> File(root, "kept-" + f.name).also { f.copyTo(it) } to f }
                error("crash; kept ${copy.name} and ${stagedFiles.size} recovery copies")
            }
        }
        assertFailsWith<IllegalStateException> { device.handover("john", slot(saves)) }
        // Here the live rollback already restored Mark's: recovery on start must be harmless too.
        val again = SyncDevice(state, "deck", "Deck")
        assertFalse(journal.exists())
        assertEquals(mapOf("a.dat" to "mark a", "b.dat" to "mark b"), contents(saves))
        again.handover("john", slot(saves))
        assertEquals(mapOf("a.dat" to "john a", "b.dat" to "john b"), contents(saves))
    }

    @Test
    fun aSaveNobodyOwnedIsKeptAsideOnceSomeoneElseUsesTheDevice(): Unit = runBlocking {
        val saves = File(root, "saves")
        File(saves, "found.dat").apply { parentFile.mkdirs() }.writeText("made before Fuse")
        val device = SyncDevice(File(root, "device"), "deck", "Deck")
        // Mark launches on a device John already uses: the old save isn't given to Mark.
        device.handover("mark", slot(saves), othersHere = true)
        assertEquals(emptyMap(), contents(saves))
        val kept = assertNotNull(device.unclaimed(slot(saves)))
        assertEquals("made before Fuse", device.store.fileOf(kept.files.single().hash).readText())
        // Listed for someone to claim, by game and kind.
        val (id, kind, manifest) = device.unclaimedSaves().single()
        assertEquals(game.id, id)
        assertEquals(SaveKind.SAVE, kind)
        assertEquals(1, manifest.files.size)
        // John says it is his: it is his parked save, and comes back at his handover.
        assertTrue(device.claimUnclaimed("john", game, SaveKind.SAVE))
        assertTrue(device.unclaimedSaves().isEmpty())
        assertNull(device.unclaimed(slot(saves)))
        device.handover("john", slot(saves))
        assertEquals(mapOf("found.dat" to "made before Fuse"), contents(saves))
    }

    @Test
    fun aSaveNobodyOwnedIsTheirsOnADeviceOnlyTheyUse(): Unit = runBlocking {
        val saves = File(root, "saves")
        File(saves, "found.dat").apply { parentFile.mkdirs() }.writeText("mine")
        val device = SyncDevice(File(root, "device"), "deck", "Deck")
        device.handover("john", slot(saves))
        assertEquals(mapOf("found.dat" to "mine"), contents(saves))
        assertNull(device.unclaimed(slot(saves)))
    }
}

/** A save the emulator made blank again never goes anywhere as the newest. */
class BlankSaveTest {
    @Test
    fun aBlankSaveIsNeverSentFirstOrAfterARealOne(): Unit = runBlocking {
        val root = Files.createTempDirectory("blank").toFile()
        try {
            val game = GameKey.of("gba", null, null, "Minish Cap")
            val sav = File(root, "saves/Minish Cap.sav").apply { parentFile.mkdirs() }
            fun slot() = Slots.of(game, SaveSpot(SaveKind.SAVE, "sram", listOf(SpotFile("save.srm", sav.path))))
            val device = SyncDevice(File(root, "device"), "thor", "Thor")
            var told = 0
            device.onBlank = { told++ }
            // A first save that is blank (save memory the emulator just made) holds nothing: it
            // stays, so it can never stand as the newest over another device's real save.
            sav.writeBytes(ByteArray(8192) { 0xFF.toByte() })
            assertNull(device.capture("mo", slot(), 60))
            assertEquals(0, told)
            sav.writeBytes(ByteArray(8192) { if (it < 4) 1 else 0 })
            assertNotNull(device.capture("mo", slot(), 120))
            // The emulator made its save memory again: all 0xFF, then all zeros, then empty.
            for (blank in listOf(ByteArray(8192) { 0xFF.toByte() }, ByteArray(8192), ByteArray(0))) {
                sav.writeBytes(blank)
                assertNull(device.capture("mo", slot(), 180))
            }
            assertEquals(3, told)
            // Found again by the look for saves made outside Fuse, every few minutes: told once, not each time.
            repeat(3) { assertNull(device.capture("mo", slot(), 180)) }
            assertEquals(3, told, "the same blank save is told once")
            assertTrue(Slots.blank(sav))
            sav.writeBytes(byteArrayOf(0, 0, 0, 1))
            assertFalse(Slots.blank(sav))
        } finally {
            root.deleteRecursively()
        }
    }
}
