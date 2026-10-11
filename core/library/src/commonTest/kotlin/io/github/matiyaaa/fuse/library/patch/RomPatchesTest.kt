package io.github.matiyaaa.fuse.library.patch

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Patches built here byte by byte, the way each format's specification lays them out. */
class RomPatchesTest {
    private val game = ByteArray(64) { (it * 7).toByte() }

    /** UPS's and BPS's numbers: seven bits a byte, the last one marked. */
    private fun varint(value: Long): ByteArray {
        val out = ArrayList<Byte>()
        var v = value
        while (true) {
            val x = (v and 0x7F).toInt()
            v = v shr 7
            if (v == 0L) {
                out += (0x80 or x).toByte()
                return out.toByteArray()
            }
            out += x.toByte()
            v--
        }
    }

    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun withFooter(body: ByteArray, source: ByteArray, target: ByteArray): ByteArray {
        val head = body + le32(RomPatches.crc32(source)) + le32(RomPatches.crc32(target))
        return head + le32(RomPatches.crc32(head))
    }

    /** A BPS patch: [actions] between its header and footer. */
    private fun bps(source: ByteArray, target: ByteArray, actions: ByteArray, metadata: String = "") =
        withFooter("BPS1".encodeToByteArray() + varint(source.size.toLong()) + varint(target.size.toLong()) + varint(metadata.length.toLong()) + metadata.encodeToByteArray() + actions, source, target)

    private fun ups(source: ByteArray, target: ByteArray): ByteArray {
        var body = "UPS1".encodeToByteArray() + varint(source.size.toLong()) + varint(target.size.toLong())
        var pos = 0
        val n = maxOf(source.size, target.size)
        var last = 0
        while (pos < n) {
            val s = source.getOrElse(pos) { 0 }
            val t = target.getOrElse(pos) { 0 }
            if (s == t) { pos++; continue }
            body += varint((pos - last).toLong())
            while (pos < n) {
                val x = (source.getOrElse(pos) { 0 }.toInt() xor target.getOrElse(pos) { 0 }.toInt()).toByte()
                pos++
                if (x == 0.toByte()) break
                body += x
            }
            body += 0
            last = pos
        }
        return withFooter(body, source, target)
    }

    @Test
    fun ipsRecordsAndRunsAndTheSizeItEndsWith() {
        val patch = "PATCH".encodeToByteArray() +
            byteArrayOf(0, 0, 4, 0, 3, 1, 2, 3) + // three bytes at 4
            byteArrayOf(0, 0, 70, 0, 0, 0, 5, 9) + // five 9s at 70, past the game's end
            "EOF".encodeToByteArray()
        val out = assertIs<PatchOutcome.Patched>(RomPatches.apply(game, patch))
        assertEquals(PatchFormat.IPS, out.format)
        assertFalse(out.checked, "an IPS patch says nothing of the game it is for")
        assertEquals(75, out.bytes.size)
        assertContentEquals(byteArrayOf(1, 2, 3), out.bytes.copyOfRange(4, 7))
        assertContentEquals(ByteArray(5) { 9 }, out.bytes.copyOfRange(70, 75))
        assertEquals(game[10], out.bytes[10])
        // A size after the end marker cuts the game to it.
        val cut = assertIs<PatchOutcome.Patched>(RomPatches.apply(game, "PATCH".encodeToByteArray() + "EOF".encodeToByteArray() + byteArrayOf(0, 0, 32)))
        assertEquals(32, cut.bytes.size)
    }

    @Test
    fun bpsReadsCopiesAndRepeatsCheckedBothWays() {
        val target = game.copyOfRange(0, 16) + "HACK".encodeToByteArray() + ByteArray(8) { 0x5A } + game.copyOfRange(40, 64)
        val actions = varint(((16 - 1L) shl 2) or 0) + // source read: 16 bytes
            varint(((5 - 1L) shl 2) or 1) + "HACKZ".encodeToByteArray() + // target read: "HACK" and one 0x5A
            varint(((7 - 1L) shl 2) or 3) + varint((20L shl 1)) + // target copy: seven more 0x5A from the one just written
            varint(((24 - 1L) shl 2) or 2) + varint((40L shl 1)) // source copy: the rest from 40
        val patch = bps(game, target, actions, metadata = "<patch/>")
        val out = assertIs<PatchOutcome.Patched>(RomPatches.apply(game, patch))
        assertTrue(out.checked)
        assertContentEquals(target, out.bytes)
        // Applied to the result: it says so, rather than patching twice.
        assertIs<PatchOutcome.AlreadyPatched>(RomPatches.apply(target, patch))
        // Another version of the game: refused, with both checksums.
        val other = game.copyOf().also { it[3] = 99 }
        val wrong = assertIs<PatchOutcome.WrongGame>(RomPatches.apply(other, patch))
        assertEquals(RomPatches.crc32(game), wrong.expected)
        assertEquals(RomPatches.crc32(other), wrong.actual)
    }

    @Test
    fun upsXorRunsAndGrowth() {
        val target = game.copyOf(80).also { it[2] = 42; it[3] = 43; it[50] = 7; it[70] = 1; it[79] = 2 }
        val out = assertIs<PatchOutcome.Patched>(RomPatches.apply(game, ups(game, target)))
        assertEquals(PatchFormat.UPS, out.format)
        assertContentEquals(target, out.bytes)
    }

    @Test
    fun aCopierHeaderIsLeftOffWhenThePatchIsForTheHeaderlessGame() {
        val rom = ByteArray(1024) { (it % 251).toByte() }
        val target = rom.copyOf().also { it[100] = 1 }
        val patch = bps(rom, target, varint(((1024 - 1L) shl 2) or 1) + target)
        val headered = ByteArray(512) + rom
        val out = assertIs<PatchOutcome.Patched>(RomPatches.apply(headered, patch))
        assertTrue(out.headerRemoved)
        assertContentEquals(target, out.bytes)
    }

    @Test
    fun damagedOrUnknownPatchesAreNeverApplied() {
        val target = game.copyOf().also { it[0] = 1 }
        val good = bps(game, target, varint(((64 - 1L) shl 2) or 1) + target)
        val damaged = good.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertIs<PatchOutcome.Unreadable>(RomPatches.apply(game, damaged))
        assertIs<PatchOutcome.Unreadable>(RomPatches.apply(game, "PATCH".encodeToByteArray() + byteArrayOf(0, 0, 4, 0, 9, 1)))
        assertIs<PatchOutcome.Unreadable>(RomPatches.apply(game, "PK\u0003\u0004 a zip".encodeToByteArray()))
        assertEquals(null, RomPatches.formatOf(ByteArray(3)))
    }
}
