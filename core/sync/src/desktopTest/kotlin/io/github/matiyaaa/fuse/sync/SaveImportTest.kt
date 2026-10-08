package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SaveImportTest {
    private fun <T> temp(block: (File) -> T): T {
        val dir = Files.createTempDirectory("fuse-import-test").toFile()
        return try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun zip(root: File, vararg entries: Pair<String, String>): File = File(root, "saves.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes.toByteArray()); z.closeEntry() } }
    }
    private fun query(platform: String = "snes", title: String = "Chrono Trigger", emulator: String = "mgba", serial: String? = null) =
        SaveQuery(GameKey.of(platform, serial, null, title), platform, "/games/$title.sfc", emulator, serial = serial, title = title)

    @Test fun unsafeAndDuplicateArchivesNeverReachDestinations() = temp { root ->
        val importer = JvmSaveImporter(File(root, "staging"))
        val env = FileSaveEnvironment("LINUX", root.path) { null }
        for (names in listOf(listOf("../escape.sav"), listOf("/absolute.sav"), listOf("C:\\save.sav"), listOf("a/../../save.sav"), listOf("SAVE.sav", "save.sav"), listOf("a/save.sav", "a"))) {
            assertFailsWith<IllegalArgumentException> { importer.scan(zip(root, *names.map { it to "save" }.toTypedArray()), emptyList(), env) }
        }
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
        assertTrue(!File(root, "escape.sav").exists())
    }

    @Test fun actualInflatedBytesAreBounded(): Unit = temp { root ->
        val importer = JvmSaveImporter(File(root, "stage"), JvmSaveImporter.Limits(bytes = 20, fileBytes = 20))
        assertFailsWith<IllegalArgumentException> { importer.scan(zip(root, "save.sav" to "0".repeat(21)), emptyList(), FileSaveEnvironment("LINUX", root.path) { null }) }
        Unit
    }

    @Test fun fullPspArchiveUsesTitleIdsAndKeepsUsersSeparate() = temp { root ->
        val local = File(root, ".config/ppsspp/PSP/SAVEDATA").apply { mkdirs() }
        val source = zip(root, "memstick/PSP/SAVEDATA/ULUS10041000/PARAM.SFO" to "save", "memstick/PSP/SAVEDATA/ULUS10042000/DATA.BIN" to "other", "memstick/PSP/PPSSPP_STATE/game.ppst" to "state")
        val importer = JvmSaveImporter(File(root, "stage"))
        val q = query("psp", "First", "ppsspp", "ULUS10041")
        val p = importer.scan(source, listOf(q), FileSaveEnvironment("LINUX", root.path) { null })
        val e = p.entries.single()
        assertEquals("psp.savedata", e.sourceFormat)
        val binding = importer.binding(p, SaveImportChoice(e.id, assertNotNull(e.selectedTarget)))
        assertEquals(setOf("ULUS10041000/PARAM.SFO"), binding.files.keys)
        assertTrue(p.unmatched.any { it.contains("ULUS10042000") })
        assertTrue(p.unmatched.any { it.contains("PPSSPP_STATE") })
        assertTrue(local.listFiles().orEmpty().isEmpty())
        importer.discard(p)
        assertTrue(source.isFile)
    }

    @Test fun unknownGameNamesRequireExplicitCorrection() = temp { root ->
        val saves = File(root, ".config/mgba").apply { mkdirs() }
        val q = query("gba", "Mario", "mgba")
        val source = File(root, "unknown.sav").apply { writeText("0".repeat(512)) }
        val importer = JvmSaveImporter(File(root, "stage"))
        val p = importer.scan(source, listOf(q), FileSaveEnvironment("LINUX", root.path) { null })
        assertEquals(null, p.entries.single().selectedTarget)
        assertEquals(SaveImportConfidence.MANUAL, p.entries.single().targets.single().confidence)
        assertTrue(saves.isDirectory)
    }

    @Test fun partialWriteFailureRestoresEveryOriginal() = temp { root ->
        val dest = File(root, "dest").apply { mkdirs() }
        val a = File(dest, "a.sav").apply { writeText("old-a") }
        val b = File(dest, "b.sav").apply { writeText("old-b") }
        val slot = LocalSlot(query().game, SaveKind.SAVE, "sram", dest, listOf(LocalFile("a.sav", a), LocalFile("b.sav", b)))
        val store = ContentStore(File(root, "objects"))
        val files = listOf("a.sav" to "new-a", "b.sav" to "new-b").map { (name, text) -> SaveFile(name, store.put(text.toByteArray()), text.length.toLong()) }
        assertFailsWith<IllegalStateException> { SaveImportTransaction.write(slot, SaveManifest("sram", files), store) { if (it == 1) error("Injected failed move") } }
        assertEquals("old-a", a.readText()); assertEquals("old-b", b.readText())
        assertEquals(setOf("a.sav", "b.sav"), dest.list()!!.toSet())
    }

    @Test fun explicitDestinationCannotFollowSymlinkParent() = temp { root ->
        val outside = File(root, "outside").apply { mkdirs() }
        val original = File(outside, "save.sav").apply { writeText("original") }
        val dest = File(root, "dest").apply { mkdirs() }
        val linked = File(dest, "linked")
        Files.createSymbolicLink(linked.toPath(), outside.toPath())
        val target = File(linked, "save.sav")
        val slot = LocalSlot(query().game, SaveKind.SAVE, "sram", dest,
            listOf(LocalFile("save.sav", target)), targets = mapOf("save.sav" to target))
        val store = ContentStore(File(root, "objects"))
        val manifest = SaveManifest("sram", listOf(SaveFile("save.sav", store.put("new".toByteArray()), 3)))
        assertFailsWith<IllegalArgumentException> { SaveImportTransaction.write(slot, manifest, store) }
        assertEquals("original", original.readText())
    }

    @Test fun revisionCommitFailureRestoresOriginalBytes() = temp { root ->
        val dest = File(root, "dest").apply { mkdirs() }
        val save = File(dest, "save.sav").apply { writeText("original") }
        val slot = LocalSlot(query().game, SaveKind.SAVE, "sram", dest, listOf(LocalFile("save.sav", save)))
        val store = ContentStore(File(root, "objects"))
        val manifest = SaveManifest("sram", listOf(SaveFile("save.sav", store.put("new".toByteArray()), 3)))
        assertFailsWith<IllegalStateException> {
            SaveImportTransaction.write(slot, manifest, store, commit = { error("Injected state commit failure") })
        }
        assertEquals("original", save.readText())
        assertEquals(setOf("save.sav"), dest.list()!!.toSet())
    }

    @Test fun interruptedBatchRecoversReplacedRemovedAndNewFiles() = temp { root ->
        val dest = File(root, "dest").apply { mkdirs() }
        val a = File(dest, "a.sav").apply { writeText("old-a") }
        val stale = File(dest, "stale.sav").apply { writeText("old-stale") }
        val slot = LocalSlot(query().game, SaveKind.SAVE, "sram", dest,
            listOf(LocalFile("a.sav", a), LocalFile("stale.sav", stale)))
        val store = ContentStore(File(root, "objects"))
        val manifest = SaveManifest("sram", listOf("a.sav" to "new-a", "new.sav" to "new").map { (name, value) ->
            SaveFile(name, store.put(value.toByteArray()), value.length.toLong())
        })
        val journal = File(root, "transaction.json")
        // Error skips the caught-exception rollback, leaving the same journal/files as termination.
        assertFailsWith<AssertionError> {
            SaveImportTransaction.write(slot, manifest, store, journal = journal, revision = "r", commit = { throw AssertionError("Interrupted") })
        }
        assertEquals("new-a", a.readText())
        assertTrue(!stale.exists())
        assertTrue(File(dest, "new.sav").exists())
        SaveImportTransaction.recover(journal) { false }
        assertEquals("old-a", a.readText())
        assertEquals("old-stale", stale.readText())
        assertTrue(!File(dest, "new.sav").exists())
        assertTrue(!journal.exists())
    }

    @Test fun durableRevisionWinsWhenInterruptedBeforeJournalCleanup() = temp { root ->
        val dest = File(root, "dest").apply { mkdirs() }
        val save = File(dest, "save.sav").apply { writeText("old") }
        val slot = LocalSlot(query().game, SaveKind.SAVE, "sram", dest, listOf(LocalFile("save.sav", save)))
        val store = ContentStore(File(root, "objects"))
        val journal = File(root, "transaction.json")
        assertFailsWith<AssertionError> {
            SaveImportTransaction.write(slot, SaveManifest("sram", listOf(SaveFile("save.sav", store.put("new".toByteArray()), 3))),
                store, journal = journal, revision = "durable", commit = { throw AssertionError("Interrupted after durable commit") })
        }
        SaveImportTransaction.recover(journal) { it == "durable" }
        assertEquals("new", save.readText())
        assertEquals(setOf("save.sav"), dest.list()!!.toSet())
    }

    @Test fun lateEmulatorEditIsNotOverwrittenByInterruptedImportRecovery() = temp { root ->
        val dest = File(root, "dest").apply { mkdirs() }
        val save = File(dest, "save.sav").apply { writeText("old") }
        val slot = LocalSlot(query().game, SaveKind.SAVE, "sram", dest, listOf(LocalFile("save.sav", save)))
        val store = ContentStore(File(root, "objects"))
        val journal = File(root, "transaction.json")
        assertFailsWith<AssertionError> {
            SaveImportTransaction.write(slot, SaveManifest("sram", listOf(SaveFile("save.sav", store.put("new".toByteArray()), 3))),
                store, journal = journal, revision = "r", commit = { throw AssertionError("Interrupted") })
        }
        save.writeText("played after crash")
        assertFailsWith<IllegalArgumentException> { SaveImportTransaction.recover(journal) { false } }
        assertEquals("played after crash", save.readText())
        assertTrue(journal.isFile)
        assertTrue(dest.list()!!.any { it.endsWith(".backup") })
    }

    @Test fun emulatorEditDuringStagingCannotBeSilentlyReplaced() = temp { root ->
        val dest = File(root, "dest").apply { mkdirs() }
        val save = File(dest, "save.sav").apply { writeText("old") }
        val slot = LocalSlot(query().game, SaveKind.SAVE, "sram", dest, listOf(LocalFile("save.sav", save)))
        val store = ContentStore(File(root, "objects"))
        assertFailsWith<IllegalArgumentException> {
            SaveImportTransaction.write(slot, SaveManifest("sram", listOf(SaveFile("save.sav", store.put("new".toByteArray()), 3))), store) {
                save.writeText("played while staging")
            }
        }
        assertEquals("played while staging", save.readText())
    }

    @Test fun importedRevisionAndSafetyCopySurviveOfflineRestart(): Unit = runBlocking {
        temp { root ->
            runBlocking {
                val dest = File(root, "dest").apply { mkdirs() }
                val save = File(dest, "Chrono Trigger.srm").apply { writeText("chapter 2") }
                val slot = LocalSlot(query().game, SaveKind.SAVE, "sram", dest, listOf(LocalFile("save.srm", save)), targets = mapOf("save.srm" to save))
                val source = File(root, "source.srm").apply { writeText("chapter 8") }
                val d = SyncDevice(File(root, "device"), "deck", "Deck")
                val revision = d.importSave("person", slot, "sram", mapOf("save.srm" to source), "Chrono Trigger")
                assertEquals("Imported", revision.provenance)
                assertTrue(revision.canBeNewest)
                assertEquals("chapter 8", save.readText())
                assertEquals("chapter 8", source.readText())
                val reopened = SyncDevice(File(root, "device"), "deck", "Deck")
                assertEquals(2, reopened.pendingCount)
                assertEquals(setOf("person"), reopened.pendingProfiles())
                assertTrue(File(root, "device/device-state.json").readText().contains("Before import"))
            }
        }
    }

    @Test fun unsupportedStateCannotReplaceBatterySave(): Unit = runBlocking {
        temp { root -> runBlocking {
            val save = File(root, "save.srm").apply { writeText("original") }
            val source = File(root, "save.state").apply { writeText("state") }
            val slot = LocalSlot(query().game, SaveKind.SAVE, "sram", root, listOf(LocalFile("save.srm", save)), targets = mapOf("save.srm" to save))
            assertFailsWith<IllegalArgumentException> { SyncDevice(File(root, "device"), "pc", "PC").importSave("person", slot, "ppsspp.state", mapOf("save.srm" to source)) }
            assertEquals("original", save.readText())
        } }
    }
}
