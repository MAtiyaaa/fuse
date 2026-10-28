package io.github.matiyaaa.fuse.sync

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PlayStation 2 cards and saves against mymc's own (resources/ps2): a card mymc formatted, the same
 * card with a save mymc put on it, and that save as EMS, Max, CodeBreaker and SharkPort files.
 */
class Ps2CardTest {
    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/ps2/$name")!!.use { input ->
        if (name.endsWith(".gz")) GZIPInputStream(input).readBytes() else input.readBytes()
    }

    /** 03:04:05 on 2 October 2026 in Japan, the time mymc's card and saves were made at. */
    private val made = 1_790_877_845_000L

    private val names = listOf("icon.sys", "BASLUS-21050GAME", "view.ico", "empty")

    /** The bytes mymc's save holds, as gen.py made them. */
    private fun data(n: Int, seed: Int) = ByteArray(n) { i -> (((i * seed + (i shr 7)) * 31 + seed) and 0xFF).toByte() }
    private val contents = listOf(data(964, 3), data(3000, 7), data(1500, 11), ByteArray(0))

    private fun assertTheSave(save: Ps2Card.Save?) {
        assertNotNull(save)
        assertEquals("BASLUS-21050GAME", save.name)
        assertEquals(names, save.files.map { it.first.name })
        for ((i, f) in save.files.withIndex()) assertContentEquals(contents[i], f.second, f.first.name)
    }

    /** Every page's ECC, worked out the way mymc's ps2mc_ecc does, outside the erased backup block. */
    private fun assertEcc(card: ByteArray) {
        val masks = intArrayOf(0x55, 0x33, 0x0F, 0x00, 0xAA, 0xCC, 0xF0)
        fun parity(v: Int) = Integer.bitCount(v) and 1
        for (page in 0 until card.size / 528) {
            if (page / 16 == 1022) continue
            for (chunk in 0 until 4) {
                var cp = 0x77
                var lp0 = 0x7F
                var lp1 = 0x7F
                for (i in 0 until 128) {
                    val v = card[page * 528 + chunk * 128 + i].toInt() and 0xFF
                    for ((bit, m) in masks.withIndex()) cp = cp xor (parity(v and m) shl bit)
                    if (parity(v) == 1) { lp0 = lp0 xor i.inv(); lp1 = lp1 xor i }
                }
                val at = page * 528 + 512 + chunk * 3
                assertEquals(listOf(cp, lp0 and 0x7F, lp1), (0 until 3).map { card[at + it].toInt() and 0xFF }, "page $page")
            }
        }
    }

    @Test
    fun aBlankCardIsFormattedByteForByteAsMymcFormatsOne() {
        val mymc = resource("blank.ps2.gz")
        val ours = Ps2Card.blank(made)
        assertEquals(8_650_752, ours.size)
        assertTrue(Ps2Card.isCardSize(ours.size.toLong()))
        assertContentEquals(mymc, ours)
        assertTrue(Ps2Card.isCard(ours))
        assertEquals(emptyList(), Ps2Card.saves(ours))
    }

    @Test
    fun aSaveMymcPutOnACardIsReadWhole() {
        val card = resource("withsave.ps2.gz")
        assertEquals(listOf("BASLUS-21050GAME"), Ps2Card.saves(card))
        assertTheSave(Ps2Card.read(card, "BASLUS-21050GAME"))
        assertNull(Ps2Card.read(card, "BASLUS-99999"))
    }

    @Test
    fun theSameSaveIsReadFromEveryFormatItComesIn() {
        for (name in listOf("save.psu", "save.max", "save.cbs", "save.sps")) {
            val save = Ps2Card.saveIn(resource(name), made)
            assertTheSave(save)
            assertTrue(save!!.dir.isDir, name)
        }
        // A Max keeps no time: it is dated now.
        assertContentEquals(Ps2Card.tod(made), Ps2Card.saveIn(resource("save.max"), made)!!.dir.created)
        assertNull(Ps2Card.saveIn(ByteArray(2048)))
        assertNull(Ps2Card.saveIn(resource("save.max").copyOf(400)))
        assertNull(Ps2Card.saveIn(resource("save.cbs").copyOf(600)))
    }

    @Test
    fun aSaveWrittenOnACardReadsBackAndTakesThePlaceOfAnOlderCopy() {
        val blank = Ps2Card.blank(made)
        val save = Ps2Card.saveIn(resource("save.psu"))!!
        val card = assertNotNull(Ps2Card.write(blank, save))
        assertFalse(card === blank)
        assertEquals(emptyList(), Ps2Card.saves(blank), "the card given is never changed")
        assertEquals(listOf("BASLUS-21050GAME"), Ps2Card.saves(card))
        assertTheSave(Ps2Card.read(card, "BASLUS-21050GAME"))
        assertEcc(card)
        // The backup block is erased, so the console never replays it.
        assertTrue((1022 * 16 * 528 until 1023 * 16 * 528).all { card[it] == 0xFF.toByte() })

        // On mymc's card beside its own copy: the older one goes, the newer is the one read.
        val changed = Ps2Card.Save(save.dir, save.files.map { (e, b) -> e to b.reversedArray() })
        val again = assertNotNull(Ps2Card.write(resource("withsave.ps2.gz"), changed))
        assertEquals(listOf("BASLUS-21050GAME"), Ps2Card.saves(again))
        assertContentEquals(contents[1].reversedArray(), Ps2Card.read(again, "BASLUS-21050GAME")!!.files[1].second)
        assertEcc(again)

        // Another save goes beside it.
        val other = Ps2Card.Save(save.dir.copy(name = "BASLES-50330ICO"), save.files)
        val both = assertNotNull(Ps2Card.write(again, other))
        assertEquals(listOf("BASLUS-21050GAME", "BASLES-50330ICO"), Ps2Card.saves(both))
    }

    @Test
    fun aFullCardSaysSoAndForeignBytesAreNoCard() {
        val save = Ps2Card.saveIn(resource("save.psu"))!!
        // About a megabyte each: the eighth doesn't fit on 8 MB.
        val big = { n: Int -> Ps2Card.Save(save.dir.copy(name = "BASLUS-2105${n}BIG"), listOf(save.files[0].first to ByteArray(1_000_000) { (it * n).toByte() })) }
        var card = Ps2Card.blank()
        var written = 0
        while (true) {
            card = Ps2Card.write(card, big(written)) ?: break
            written++
        }
        assertEquals(8, written)
        assertEquals(8, Ps2Card.saves(card).size)
        assertFalse(Ps2Card.isCard(ByteArray(8_650_752)))
        assertTrue(Ps2Card.isUnformatted(ByteArray(8_650_752) { 0xFF.toByte() }))
        assertFalse(Ps2Card.isUnformatted(card))
        assertNull(Ps2Card.write(ByteArray(8_650_752), save))
        assertFalse(Ps2Card.isCardSize(8L * 1024 * 1024 + 512 * 1024))
    }
}
