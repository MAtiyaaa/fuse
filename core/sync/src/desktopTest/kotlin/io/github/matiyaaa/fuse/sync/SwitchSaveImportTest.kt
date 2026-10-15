package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saves copied off a Switch by JKSV or Checkpoint, imported into Eden's NAND. */
class SwitchSaveImportTest {
    private val title = "0100000000010000"
    private val user = "00112233445566778899aabbccddeeff"

    private fun <T> temp(block: (File) -> T): T {
        val dir = Files.createTempDirectory("fuse-switch-import").toFile()
        return try { block(dir) } finally { dir.deleteRecursively() }
    }

    private fun env(root: File) = FileSaveEnvironment("LINUX", root.path) { null }

    private fun query() = SaveQuery(GameKey.of("switch", null, null, "Super Mario Odyssey"), "switch",
        "/games/Super Mario Odyssey [$title].nsp", "linux.eden", title = "Super Mario Odyssey")

    /** Eden's save space, in its newer layout, with one user who hasn't played the game yet. */
    private fun eden(root: File): File = File(root, ".local/share/eden/nand/user/save").also { File(it, "account/$user").mkdirs() }

    private fun meta(type: Int = 1, at: Long): ByteArray {
        val b = ByteArray(86)
        fun put(o: Int, v: Long, n: Int) { for (i in 0 until n) b[o + i] = (v ushr (8 * i)).toByte() }
        put(0, 0x56534B4AL, 4)
        put(5, title.toLong(16), 8)
        b[37] = type.toByte()
        put(49, at, 8)
        return b
    }

    private fun backup(dir: File, files: Map<String, String>, meta: ByteArray? = null) {
        dir.mkdirs()
        files.forEach { (n, t) -> File(dir, n).apply { parentFile.mkdirs() }.writeText(t) }
        meta?.let { File(dir, SwitchDumps.META).writeBytes(it) }
    }

    @Test fun jksvFolderGoesInEdensLayoutWithoutItsMetaFile() = temp { root ->
        val space = eden(root)
        val jksv = File(root, "sd/JKSV")
        backup(File(jksv, "Super Mario Odyssey/Matt - 2026.10.01 @ 12.00.00"), mapOf("file1" to "new", "sub/file2" to "deep"), meta(at = 1_790_000_000L))
        backup(File(jksv, "Super Mario Odyssey/Matt - 2025.01.01 @ 12.00.00"), mapOf("file1" to "old"), meta(at = 1_735_000_000L))
        val importer = JvmSaveImporter(File(root, "stage"))
        val plan = importer.scan(jksv, listOf(query()), env(root))
        assertEquals(2, plan.entries.size)
        // The newest backup is chosen; the older one waits to be picked.
        val newest = plan.entries.first()
        assertEquals("Matt - 2026.10.01 @ 12.00.00", newest.name)
        assertEquals("Matt", newest.switch?.user)
        assertEquals("JKSV", newest.switch?.tool)
        assertNotNull(newest.selectedTarget)
        assertNull(plan.entries[1].selectedTarget)
        val target = newest.targets.single { it.id == newest.selectedTarget }
        assertEquals(SaveImportConfidence.TITLE_ID, target.confidence)
        assertEquals(File(space, "account/$user/$title/0").path, target.destination)
        val binding = importer.binding(plan, SaveImportChoice(newest.id, target.id))
        assertEquals(setOf("file1", "sub/file2"), binding.files.keys)
        // The meta file is understood, not left over as unmatched content.
        assertTrue(plan.unmatched.none { it.endsWith(SwitchDumps.META) })
    }

    @Test fun jksvZipsBesideEachOtherAreRead() = temp { root ->
        eden(root)
        val game = File(root, "sd/JKSV/Super Mario Odyssey").apply { mkdirs() }
        ZipOutputStream(File(game, "Matt - 2026.10.01 @ 12.00.00.zip").outputStream()).use { z ->
            z.putNextEntry(ZipEntry(SwitchDumps.META)); z.write(meta(at = 1_790_000_000L)); z.closeEntry()
            z.putNextEntry(ZipEntry("file1")); z.write("zipped".toByteArray()); z.closeEntry()
        }
        val importer = JvmSaveImporter(File(root, "stage"))
        val plan = importer.scan(File(root, "sd/JKSV"), listOf(query()), env(root))
        val e = plan.entries.single()
        val binding = importer.binding(plan, SaveImportChoice(e.id, assertNotNull(e.selectedTarget)))
        assertEquals("zipped", binding.files.getValue("file1").readText())
    }

    @Test fun checkpointBackupIsMatchedByItsTitleId() = temp { root ->
        eden(root)
        val saves = File(root, "sd/switch/Checkpoint/saves")
        backup(File(saves, "0x$title Super Mario Odyssey/2026-10-01 12-00-00 Matt"), mapOf("file1" to "cp"))
        val plan = JvmSaveImporter(File(root, "stage")).scan(saves, listOf(query()), env(root))
        val e = plan.entries.single()
        assertEquals("CHECKPOINT", e.switch?.tool)
        assertEquals(SaveImportConfidence.TITLE_ID, e.targets.single().confidence)
    }

    @Test fun aBackupWithOnlyAGameNameAsksToBeConfirmed() = temp { root ->
        eden(root)
        val jksv = File(root, "sd/JKSV")
        backup(File(jksv, "Super Mario Odyssey/Matt - 2026.10.01 @ 12.00.00"), mapOf("file1" to "named"))
        val plan = JvmSaveImporter(File(root, "stage")).scan(jksv, listOf(query()), env(root))
        assertEquals(SaveImportConfidence.EXACT_NAME, plan.entries.single().targets.single().confidence)
    }

    @Test fun theGamesDeviceSaveIsKeptWhenItsAccountSaveIsImported() = temp { root ->
        val space = eden(root)
        File(space, "account/$user/$title/0").mkdirs()
        File(space, "device/$title/0/shared.bin").apply { parentFile.mkdirs() }.writeText("device")
        val jksv = File(root, "sd/JKSV")
        backup(File(jksv, "Super Mario Odyssey/Matt - 2026.10.01 @ 12.00.00"), mapOf("file1" to "new"), meta(at = 1_790_000_000L))
        val importer = JvmSaveImporter(File(root, "stage"))
        val plan = importer.scan(jksv, listOf(query()), env(root))
        val e = plan.entries.single()
        val binding = importer.binding(plan, SaveImportChoice(e.id, assertNotNull(e.selectedTarget)))
        assertEquals(setOf("file1", "${SwitchNand.DEVICE}/shared.bin"), binding.files.keys)
    }

    @Test fun aDeviceBackupGoesToTheDeviceSave() = temp { root ->
        eden(root)
        val jksv = File(root, "sd/JKSV")
        backup(File(jksv, "Super Mario Odyssey/Device - 2026.10.01 @ 12.00.00"), mapOf("d.bin" to "device"), meta(type = 3, at = 1_790_000_000L))
        val importer = JvmSaveImporter(File(root, "stage"))
        val plan = importer.scan(jksv, listOf(query()), env(root))
        val e = plan.entries.single()
        assertEquals(true, e.switch?.device)
        val binding = importer.binding(plan, SaveImportChoice(e.id, assertNotNull(e.selectedTarget)))
        assertEquals(setOf("${SwitchNand.DEVICE}/d.bin"), binding.files.keys)
    }

    @Test fun oneSaveForTwoProfilesLeavesEachTheirOwnCopy(): Unit = runBlocking {
        temp { root -> runBlocking {
            val dest = File(root, "dest").apply { mkdirs() }
            val slot = LocalSlot(query().game, SaveKind.SAVE, "switch.savedata", dest, emptyList())
            val source = File(root, "file1").apply { writeText("from the switch") }
            val d = SyncDevice(File(root, "device"), "deck", "Deck")
            val here = d.importSave("sam", slot, "switch.savedata", mapOf("file1" to source), "Super Mario Odyssey")
            val parked = d.importSaveParked("maya", slot, "switch.savedata", mapOf("file1" to source), "Super Mario Odyssey")
            assertEquals("from the switch", File(dest, "file1").readText())
            assertEquals("Imported", here.provenance)
            assertEquals("Imported", parked.provenance)
            assertEquals("maya", parked.profile)
            assertEquals(setOf("sam", "maya"), d.pendingProfiles())
            // Maya's copy waits for her here, so it is there when she plays, even offline.
            assertTrue("maya" in d.saveKeepers())
        } }
    }
}
