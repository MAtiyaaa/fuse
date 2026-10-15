package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwitchDumpsTest {
    /** A meta file as JKSV writes it (fs::SaveMetaData, packed, little-endian). */
    private fun meta(app: Long, type: Int = 1, at: Long = 1_760_000_000L, magic: Int = 0x56534B4A): ByteArray {
        val b = ByteArray(86)
        fun put(at0: Int, v: Long, n: Int) { for (i in 0 until n) b[at0 + i] = (v ushr (8 * i)).toByte() }
        put(0, magic.toLong(), 4)
        b[4] = 1
        put(5, app, 8)
        for (i in 13 until 29) b[i] = (i * 7).toByte()
        b[37] = type.toByte()
        put(49, at, 8)
        return b
    }

    @Test fun readsJksvMeta() {
        val m = SwitchDumps.meta(meta(0x0100000000010000L))!!
        assertEquals("0100000000010000", m.titleId)
        assertEquals(SwitchDumps.Kind.ACCOUNT, m.kind)
        assertEquals(1_760_000_000L, m.savedAt)
        assertEquals(32, m.account!!.length)
        assertEquals(SwitchDumps.Kind.DEVICE, SwitchDumps.meta(meta(0x01006F8002326000L, type = 3))!!.kind)
    }

    @Test fun rejectsWhatIsNotJksvMeta() {
        assertNull(SwitchDumps.meta(meta(0x0100000000010000L, magic = 0x12345678)))
        assertNull(SwitchDumps.meta(ByteArray(10)))
        assertNull(SwitchDumps.meta(meta(0L)))
    }

    @Test fun findsBackupsInAWholeJksvFolder() {
        val metaBytes = meta(0x0100000000010000L)
        val paths = listOf(
            "Super Mario Odyssey/Matt - 2026.10.01 @ 12.00.00/${SwitchDumps.META}",
            "Super Mario Odyssey/Matt - 2026.10.01 @ 12.00.00/file1",
            "Super Mario Odyssey/Matt - 2026.10.01 @ 12.00.00/sub/file2",
            "Zelda/Sam - 2025.01.02 @ 03.04.05/game_data.sav",
        )
        val found = SwitchDumps.backups(paths, listOf("sdcard", "JKSV")) { p -> if (p.endsWith(SwitchDumps.META)) metaBytes else null }
        assertEquals(2, found.size)
        val odyssey = found.first { it.game == "Super Mario Odyssey" }
        assertEquals("0100000000010000", odyssey.titleId)
        assertEquals("Super Mario Odyssey/Matt - 2026.10.01 @ 12.00.00", odyssey.root)
        assertEquals(setOf("file1", "sub/file2"), odyssey.files.toSet())
        assertEquals("Matt", odyssey.user)
        // An older backup with no meta: known by its game folder's name.
        val zelda = found.first { it.game == "Zelda" }
        assertNull(zelda.titleId)
        assertEquals("Sam", zelda.user)
        assertTrue(SwitchDumps.order(zelda) > 0)
    }

    @Test fun findsCheckpointBackups() {
        val paths = listOf("0x0100000000010000 Super Mario Odyssey/2026-10-01 12-00-00 Matt/file1")
        val found = SwitchDumps.backups(paths, listOf("switch", "Checkpoint", "saves")) { null }.single()
        assertEquals(SwitchDumps.Tool.CHECKPOINT, found.tool)
        assertEquals("0100000000010000", found.titleId)
        assertEquals("Super Mario Odyssey", found.game)
        assertEquals("Matt", found.user)
        assertEquals(listOf("file1"), found.files)
    }

    @Test fun aBackupOrGameFolderChosenOnItsOwnIsRecognisedByWhereItWas() {
        // The backup folder itself, inside a Checkpoint game folder.
        val backup = SwitchDumps.backups(listOf("file1"), listOf("saves", "0x0100000000010000 Odyssey", "2026-10-01 12-00-00 Matt")) { null }.single()
        assertEquals("", backup.root)
        assertEquals("0100000000010000", backup.titleId)
        // A JKSV game folder, holding two backups.
        val game = SwitchDumps.backups(listOf("A - 2026.01.01 @ 00.00.00/x", "B - 2026.02.01 @ 00.00.00/x"), listOf("sdcard", "JKSV", "Odyssey")) { null }
        assertEquals(setOf("A - 2026.01.01 @ 00.00.00", "B - 2026.02.01 @ 00.00.00"), game.map { it.root }.toSet())
        assertTrue(game.all { it.game == "Odyssey" })
    }

    @Test fun anUnrelatedFolderIsNoBackup() {
        assertTrue(SwitchDumps.backups(listOf("saves/game.srm", "x/y/z.sav"), listOf("home", "me", "Downloads")) { null }.isEmpty())
    }

    @Test fun newerBackupsSortHigher() {
        fun b(name: String) = SwitchDumps.Backup("", SwitchDumps.Tool.JKSV, null, "G", name, null, SwitchDumps.Kind.ACCOUNT, null, listOf("x"))
        assertTrue(SwitchDumps.order(b("Matt - 2026.10.01 @ 12.00.00")) > SwitchDumps.order(b("Matt - 2026.09.30 @ 23.59.59")))
        assertTrue(SwitchDumps.order(b("2026-10-01 12-00-00 Matt")) > SwitchDumps.order(b("2025-10-01 12-00-00 Matt")))
        assertEquals(0L, SwitchDumps.order(b("my backup")))
    }
}
