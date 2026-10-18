package io.github.matiyaaa.fuse.sync

import io.github.matiyaaa.fuse.sync.romm.RommPack
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A save made of folders as the one zip RomM keeps: the same bytes every time, hashed as RomM hashes it, and put back exactly. */
class RommPackTest {
    private val root = Files.createTempDirectory("romm-pack").toFile()

    @AfterTest fun tearDown() { root.deleteRecursively() }

    private val game = GameKey.of("psp", "ULUS10041", null, "Some Game")

    /** A PSP-style save folder: two files in the game's folder, one in a folder of its own, and an empty one. */
    private fun folderSave(at: File): LocalSlot {
        File(at, "ULUS10041DATA00").mkdirs()
        File(at, "ULUS10041DATA00/DATA.BIN").writeBytes(ByteArray(300) { (it * 7).toByte() })
        File(at, "ULUS10041DATA00/PARAM.SFO").writeText("param")
        File(at, "ULUS10041DATA00/sub").mkdirs()
        File(at, "ULUS10041DATA00/sub/ICON0.PNG").writeText("icon")
        File(at, "ULUS10041DATA00/empty").mkdirs()
        return Slots.of(game, SaveSpot(SaveKind.SAVE, "psp.savedata", root = at.path, folders = listOf("ULUS10041DATA00")))
    }

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun theSameSaveIsTheSameBytesWhateverTheFilesTimes() {
        val slot = folderSave(File(root, "a"))
        val first = RommPack.pack(slot)
        // Touched (as copying a folder does): the same zip.
        slot.files.forEach { it.file.setLastModified(1_500_000_000_000L) }
        assertContentEquals(first, RommPack.pack(Slots.of(game, SaveSpot(SaveKind.SAVE, "psp.savedata", root = File(root, "a").path, folders = listOf("ULUS10041DATA00")))))
        // The same save in another place: the same zip too.
        val other = folderSave(File(root, "b"))
        assertContentEquals(first, RommPack.pack(other))
    }

    @Test
    fun itsHashIsRommsHashOfAZipFromTheFilesWhereTheyAre() {
        val slot = folderSave(File(root, "a"))
        // RomM's hash_zip_contents: md5 of "name:md5" per file, sorted by name, joined by new lines; folders left out.
        val expected = md5(
            listOf(
                "ULUS10041DATA00/DATA.BIN" to ByteArray(300) { (it * 7).toByte() },
                "ULUS10041DATA00/PARAM.SFO" to "param".encodeToByteArray(),
                "ULUS10041DATA00/sub/ICON0.PNG" to "icon".encodeToByteArray(),
            ).sortedBy { it.first }.joinToString("\n") { (n, b) -> "$n:${md5(b)}" }.encodeToByteArray(),
        )
        assertEquals(expected, RommPack.contentHash(slot))
        assertEquals(expected, RommPack.contentHash(RommPack.pack(slot)))
        assertEquals(null, RommPack.contentHash(Slots.of(game, SaveSpot(SaveKind.SAVE, "psp.savedata", root = File(root, "none").path, folders = listOf("X")))))
    }

    @Test
    fun aSaveFromRommReplacesTheOneHereExactly() {
        val from = folderSave(File(root, "a"))
        val zip = RommPack.pack(from)
        // Here: an older save of the same game, with a file the new one hasn't got.
        val here = File(root, "here")
        File(here, "ULUS10041DATA00/old").mkdirs()
        File(here, "ULUS10041DATA00/DATA.BIN").writeText("older")
        File(here, "ULUS10041DATA00/old/STALE.BIN").writeText("stale")
        File(here, "OTHERGAME00/DATA.BIN").apply { parentFile.mkdirs(); writeText("not this game's") }
        val slot = Slots.of(game, SaveSpot(SaveKind.SAVE, "psp.savedata", root = here.path, folders = listOf("ULUS10041DATA00")))
        RommPack.unpack(zip, slot)
        assertContentEquals(ByteArray(300) { (it * 7).toByte() }, File(here, "ULUS10041DATA00/DATA.BIN").readBytes())
        assertEquals("icon", File(here, "ULUS10041DATA00/sub/ICON0.PNG").readText())
        assertTrue(File(here, "ULUS10041DATA00/empty").isDirectory)
        // The old save's own file goes, with the folder it leaves empty; another game's never.
        assertFalse(File(here, "ULUS10041DATA00/old").exists())
        assertEquals("not this game's", File(here, "OTHERGAME00/DATA.BIN").readText())
        assertEquals(RommPack.contentHash(from), RommPack.contentHash(Slots.of(game, SaveSpot(SaveKind.SAVE, "psp.savedata", root = here.path, folders = listOf("ULUS10041DATA00")))))
        // No staging files left behind.
        assertTrue(here.walkTopDown().none { it.name.endsWith(".fuse-romm") })
    }

    @Test
    fun aZipWithAFileOutsideTheSaveChangesNothing() {
        val here = File(root, "here").apply { mkdirs() }
        File(here, "ULUS10041DATA00/DATA.BIN").apply { parentFile.mkdirs(); writeText("mine") }
        val slot = Slots.of(game, SaveSpot(SaveKind.SAVE, "psp.savedata", root = here.path, folders = listOf("ULUS10041DATA00")))
        val evil = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("ULUS10041DATA00/DATA.BIN")); z.write("theirs".encodeToByteArray()); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("../escape.txt")); z.write("x".encodeToByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertFailsWith<IllegalStateException> { RommPack.unpack(evil, slot) }
        assertEquals("mine", File(here, "ULUS10041DATA00/DATA.BIN").readText())
        assertFalse(File(root, "escape.txt").exists())
    }
}
