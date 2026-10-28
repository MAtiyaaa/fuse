package io.github.matiyaaa.fuse.library.patch

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** PPF patches built here byte by byte, as ApplyPPF and MakePPF lay them out. */
class PpfTest {
    /** A disc image large enough to hold the block PPF 2.0 and 3.0 check, at 0x9320. */
    private val disc = ByteArray(40_000) { (it * 31 + 7).toByte() }

    private fun le(v: Long, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }

    private fun header(version: Int, description: String = "Fuse test patch") =
        "PPF${version}0".encodeToByteArray() + byteArrayOf((version - 1).toByte()) + description.encodeToByteArray().copyOf(50)

    private fun diz(text: String, lengthBytes: Int) =
        "@BEGIN_FILE_ID.DIZ".encodeToByteArray() + text.encodeToByteArray() + "@END_FILE_ID.DIZ".encodeToByteArray() + le(text.length.toLong(), lengthBytes)

    private fun ppf1(vararg records: Pair<Int, ByteArray>) =
        records.fold(header(1)) { acc, (at, bytes) -> acc + le(at.toLong(), 4) + byteArrayOf(bytes.size.toByte()) + bytes }

    private fun ppf2(image: ByteArray, vararg records: Pair<Int, ByteArray>, trailer: ByteArray = ByteArray(0)) =
        records.fold(header(2) + le(image.size.toLong(), 4) + image.copyOfRange(0x9320, 0x9320 + 1024)) { acc, (at, bytes) ->
            acc + le(at.toLong(), 4) + byteArrayOf(bytes.size.toByte()) + bytes
        } + trailer

    private fun ppf3(image: ByteArray, block: Boolean, undo: Boolean, vararg records: Pair<Long, ByteArray>, trailer: ByteArray = ByteArray(0)): ByteArray {
        var out = header(3) + byteArrayOf(0, if (block) 1 else 0, if (undo) 1 else 0, 0)
        if (block) out += image.copyOfRange(0x9320, 0x9320 + 1024)
        for ((at, bytes) in records) {
            out += le(at, 8) + byteArrayOf(bytes.size.toByte()) + bytes
            if (undo) out += image.copyOfRange(at.toInt(), at.toInt() + bytes.size)
        }
        return out + trailer
    }

    private fun patched(image: ByteArray, vararg records: Pair<Number, ByteArray>): ByteArray {
        val out = image.copyOf()
        for ((at, bytes) in records) bytes.copyInto(out, at.toInt())
        return out
    }

    @Test
    fun aPpf1PatchIsAppliedAndKnownOnceItIs() {
        val records = arrayOf(100 to byteArrayOf(1, 2, 3), 20_000 to "FUSE".encodeToByteArray())
        val patch = ppf1(*records)
        assertEquals(PatchFormat.PPF, RomPatches.formatOf(patch))
        val made = assertIs<PatchOutcome.Patched>(RomPatches.apply(disc, patch))
        assertContentEquals(patched(disc, *records), made.bytes)
        // PPF 1.0 carries nothing of the disc it was made for.
        assertFalse(made.checked)
        assertIs<PatchOutcome.AlreadyPatched>(RomPatches.apply(made.bytes, patch))
        assertEquals("Fuse test patch", Ppf.parse(patch).description)
    }

    @Test
    fun aPpf2PatchChecksTheDiscsBlockAndSkipsItsDescription() {
        val records = arrayOf(50 to byteArrayOf(9, 9), 39_000 to byteArrayOf(4))
        val patch = ppf2(disc, *records, trailer = diz("Translation v1.0\r\nby someone", 4))
        val made = assertIs<PatchOutcome.Patched>(RomPatches.apply(disc, patch))
        assertTrue(made.checked)
        assertContentEquals(patched(disc, *records), made.bytes)
        // Another disc: its block differs, and the reason gives both sizes.
        val other = ByteArray(40_100) { (it * 13).toByte() }
        val refused = assertIs<PatchOutcome.NotForThisGame>(RomPatches.apply(other, patch))
        assertTrue("40,000" in refused.reason && "40,100" in refused.reason, refused.reason)
    }

    @Test
    fun aPpf3PatchWithUndoDataIsCheckedPlaceByPlace() {
        val records = arrayOf(10L to byteArrayOf(0x55, 0x66), 30_000L to byteArrayOf(0x77))
        val patch = ppf3(disc, block = false, undo = true, *records, trailer = diz("A fix", 2))
        val made = assertIs<PatchOutcome.Patched>(RomPatches.apply(disc, patch))
        assertTrue(made.checked)
        assertContentEquals(patched(disc, *records), made.bytes)
        assertIs<PatchOutcome.AlreadyPatched>(RomPatches.apply(made.bytes, patch))
        // A revision that differs where the patch changes it is refused, though no block was carried.
        val revision = disc.copyOf().also { it[30_000] = (it[30_000] + 1).toByte() }
        assertIs<PatchOutcome.NotForThisGame>(RomPatches.apply(revision, patch))
        // With the block too, and without undo data, the block decides.
        val blocked = ppf3(disc, block = true, undo = false, *records)
        assertTrue(assertIs<PatchOutcome.Patched>(RomPatches.apply(disc, blocked)).checked)
        assertIs<PatchOutcome.NotForThisGame>(RomPatches.apply(disc.copyOf().also { it[0x9320 + 5] = 0 }, blocked))
    }

    @Test
    fun theImageIsReadOnlyWhereThePatchLooksAndNoMoreThanNeeded() {
        // Three stretches far apart in a 3 MiB image.
        val size = 3L * 1024 * 1024
        val image = ByteArray(size.toInt()) { (it xor (it shr 8)).toByte() }
        val records = arrayOf(64L to byteArrayOf(1), 1_500_000L to byteArrayOf(2, 2), 3_000_000L to byteArrayOf(3))
        val reads = ArrayList<Pair<Long, Int>>()
        fun read(at: Long, length: Int): ByteArray {
            reads += at to length
            return image.copyOfRange(at.toInt(), minOf(size, at + length).toInt())
        }
        val undo = Ppf.parse(ppf3(image, block = false, undo = true, *records))
        val ready = assertIs<Ppf.Verdict.Ready>(Ppf.check(undo, size) { at, length -> read(at, length) })
        assertEquals(3, ready.edits.size)
        // Undo data is checked everywhere: one read for each stretch, each only as long as its records.
        assertEquals(listOf(64L to 1, 1_500_000L to 2, 3_000_000L to 1), reads)
        reads.clear()
        // Without it, the first change still to make settles it.
        val plain = Ppf.parse(ppf3(image, block = false, undo = false, *records))
        assertIs<Ppf.Verdict.Ready>(Ppf.check(plain, size) { at, length -> read(at, length) })
        assertEquals(1, reads.size)
    }

    @Test
    fun overlappingRecordsEndAsTheLastOneWrites() {
        val records = arrayOf(200 to byteArrayOf(1, 1, 1, 1), 202 to byteArrayOf(2, 2, 2))
        val made = assertIs<PatchOutcome.Patched>(RomPatches.apply(disc, ppf1(*records)))
        assertContentEquals(byteArrayOf(1, 1, 2, 2, 2), made.bytes.copyOfRange(200, 205))
        assertIs<PatchOutcome.AlreadyPatched>(RomPatches.apply(made.bytes, ppf1(*records)))
    }

    @Test
    fun damagedAndForeignPatchesAreRefused() {
        val whole = ppf1(100 to byteArrayOf(1, 2, 3))
        assertIs<PatchOutcome.Unreadable>(RomPatches.apply(disc, whole.copyOf(whole.size - 1)))
        assertIs<PatchOutcome.Unreadable>(RomPatches.apply(disc, header(4)))
        assertIs<PatchOutcome.Unreadable>(RomPatches.apply(disc, header(1)))
        // A description whose start is missing is damage, not records.
        assertIs<PatchOutcome.Unreadable>(RomPatches.apply(disc, whole + "@END_FILE_ID.DIZ".encodeToByteArray() + le(3, 2)))
        // Writing far past the disc's end is a patch for another disc.
        assertIs<PatchOutcome.NotForThisGame>(RomPatches.apply(disc, ppf1(500_000_000 to byteArrayOf(1))))
        assertFalse(Ppf.isPpf("PPF".encodeToByteArray()))
    }
}
