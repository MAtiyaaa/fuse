package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An import into one person's save never touches anyone else's: a save in the folder that is
 * someone else's stays as it is, one no one owns is set aside, a Switch backup's other part is the
 * owner's own, and whatever an import replaces is backed up on the device and can be put back
 * without a host.
 */
class SwitchImportSafetyTest {
    private val game = GameKey.of("switch", null, null, "Super Mario Odyssey")

    private fun <T> temp(block: (File) -> T): T {
        val dir = Files.createTempDirectory("fuse-import-safety").toFile()
        return try { block(dir) } finally { dir.deleteRecursively() }
    }

    /** The game's save as it is on disk now: its account part in [account], its device part in [device]. */
    private fun slot(account: File, device: File): LocalSlot {
        fun listed(dir: File, prefix: String) = dir.walkTopDown().filter { it.isFile }.map { LocalFile(prefix + it.relativeTo(dir).path.replace(File.separatorChar, '/'), it) }.toList()
        return LocalSlot(game, SaveKind.SAVE, "switch.savedata", account, listed(account, "") + listed(device, "${SwitchNand.DEVICE}/"),
            mounts = mapOf(SwitchNand.DEVICE to device))
    }

    private fun write(dir: File, name: String, text: String) = File(dir, name).apply { parentFile.mkdirs() }.writeText(text)

    private class Room(root: File) {
        val account = File(root, "account").apply { mkdirs() }
        val device = File(root, "device-save").apply { mkdirs() }
        val d = SyncDevice(File(root, "fuse"), "deck", "Deck")
        val source = File(root, "source").apply { mkdirs() }
    }

    private fun source(room: Room, name: String, text: String): Map<String, File> =
        mapOf(name to File(room.source, name.replace('/', '_') + text.hashCode()).apply { writeText(text) })

    @Test fun importingForOnePersonLeavesTheSaveInTheFolderOfAnother(): Unit = temp { root -> runBlocking {
        val r = Room(root)
        r.d.importSave("sam", slot(r.account, r.device), "switch.savedata", source(r, "file1", "sam's"))
        val revisions = r.d.importFor(listOf("mo"), active = "mo", slot(r.account, r.device), "switch.savedata", source(r, "file1", "mo's"))
        // Sam's save is in the folder still, exactly as it was; Mo's waits for Mo.
        assertEquals("sam's", File(r.account, "file1").readText())
        assertEquals("sam", r.d.holderOf(slot(r.account, r.device)))
        assertEquals("mo", revisions.single().profile)
        assertEquals(ExistingSave.Where.SET_ASIDE, r.d.existingSave("mo", slot(r.account, r.device))?.where)
        // Mo's next launch puts it in place, Sam's kept for Sam.
        r.d.handover("mo", slot(r.account, r.device))
        assertEquals("mo's", File(r.account, "file1").readText())
        assertEquals(ExistingSave.Where.SET_ASIDE, r.d.existingSave("sam", slot(r.account, r.device))?.where)
    } }

    @Test fun aSaveNoOneOwnsOnASharedDeviceIsSetAsideNotTaken(): Unit = temp { root -> runBlocking {
        val r = Room(root)
        r.d.importSaveParked("sam", slot(r.account, r.device), "switch.savedata", source(r, "file1", "sam's"))
        write(r.account, "file1", "nobody's")
        r.d.importFor(listOf("mo"), active = "mo", slot(r.account, r.device), "switch.savedata", source(r, "file1", "mo's"))
        assertEquals("mo's", File(r.account, "file1").readText())
        val unowned = assertNotNull(r.d.unclaimed(slot(r.account, r.device)))
        assertEquals("nobody's", r.d.store.fileOf(unowned.files.single().hash).readText())
        assertTrue(r.d.importBackups("mo", slot(r.account, r.device)).isEmpty())
    } }

    @Test fun aDeviceBackupKeepsTheOwnersAccountSave(): Unit = temp { root -> runBlocking {
        val r = Room(root)
        val mine = source(r, "file1", "my progress") + source(r, "${SwitchNand.DEVICE}/shared.bin", "old device")
        r.d.importSave("mo", slot(r.account, r.device), "switch.savedata", mine)
        r.d.importSave("mo", slot(r.account, r.device), "switch.savedata", source(r, "${SwitchNand.DEVICE}/shared.bin", "new device"), part = SavePart.SWITCH_DEVICE)
        assertEquals("my progress", File(r.account, "file1").readText())
        assertEquals("new device", File(r.device, "shared.bin").readText())
    } }

    @Test fun aDeviceBackupSetAsideForSomeoneKeepsTheirAccountNotTheFolders(): Unit = temp { root -> runBlocking {
        val r = Room(root)
        r.d.importSave("sam", slot(r.account, r.device), "switch.savedata", source(r, "file1", "sam's account"))
        r.d.importSaveParked("mo", slot(r.account, r.device), "switch.savedata", source(r, "file1", "mo's account"))
        val rev = r.d.importSaveParked("mo", slot(r.account, r.device), "switch.savedata", source(r, "${SwitchNand.DEVICE}/shared.bin", "device"), part = SavePart.SWITCH_DEVICE)
        val account = rev.manifest.files.single { it.path == "file1" }
        assertEquals("mo's account", r.d.store.fileOf(account.hash).readText())
        assertEquals("sam's account", File(r.account, "file1").readText())
    } }

    @Test fun whatAPersonAlreadyHasIsFound(): Unit = temp { root -> runBlocking {
        val r = Room(root)
        assertNull(r.d.existingSave("mo", slot(r.account, r.device)))
        r.d.importSave("mo", slot(r.account, r.device), "switch.savedata", source(r, "file1", "mo's"))
        assertEquals(ExistingSave.Where.IN_FOLDER, r.d.existingSave("mo", slot(r.account, r.device))?.where)
        assertNull(r.d.existingSave("sam", slot(r.account, r.device)))
        r.d.importSaveParked("sam", slot(r.account, r.device), "switch.savedata", source(r, "file1", "sam's"))
        assertEquals(ExistingSave.Where.SET_ASIDE, r.d.existingSave("sam", slot(r.account, r.device))?.where)
    } }

    @Test fun aReplacedSaveIsBackedUpAndComesBackWithoutAHost(): Unit = temp { root -> runBlocking {
        val r = Room(root)
        r.d.importSave("mo", slot(r.account, r.device), "switch.savedata", source(r, "file1", "one"))
        r.d.importSave("mo", slot(r.account, r.device), "switch.savedata", source(r, "file1", "two"))
        val backup = r.d.importBackups("mo", slot(r.account, r.device)).single()
        r.d.collect()
        r.d.restoreImportBackup("mo", slot(r.account, r.device), backup.id)
        assertEquals("one", File(r.account, "file1").readText())
        // The undo is backed up too, so it can be undone.
        assertEquals(2, r.d.importBackups("mo", slot(r.account, r.device)).size)
    } }

    @Test fun aSetAsideSaveReplacedIsBackedUpAndComesBackSetAside(): Unit = temp { root -> runBlocking {
        val r = Room(root)
        r.d.importSave("sam", slot(r.account, r.device), "switch.savedata", source(r, "file1", "sam's"))
        r.d.importSaveParked("mo", slot(r.account, r.device), "switch.savedata", source(r, "file1", "mo's first"))
        r.d.importSaveParked("mo", slot(r.account, r.device), "switch.savedata", source(r, "file1", "mo's second"))
        val backup = r.d.importBackups("mo", slot(r.account, r.device)).single()
        r.d.restoreImportBackup("mo", slot(r.account, r.device), backup.id)
        assertEquals("sam's", File(r.account, "file1").readText())
        r.d.handover("mo", slot(r.account, r.device))
        assertEquals("mo's first", File(r.account, "file1").readText())
    } }
}
