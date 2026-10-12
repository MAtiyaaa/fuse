package io.github.matiyaaa.fuse.library.disc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NintendoDiscTest {
    private fun header(id: String, wii: Boolean): ByteArray = ByteArray(0x20).also { b ->
        id.encodeToByteArray().copyInto(b)
        if (wii) byteArrayOf(0x5D, 0x1C, 0x9E.toByte(), 0xA3.toByte()).copyInto(b, 0x18)
        else byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D).copyInto(b, 0x1C)
    }

    @Test
    fun readsTheIdOfPlainCompressedAndCisoImages() {
        assertEquals("GALE01", NintendoDisc.gameId(header("GALE01", wii = false)))
        assertEquals("RMGE01", NintendoDisc.gameId(header("RMGE01", wii = true)))
        val rvz = ByteArray(0x100).also { "RVZ\u0001".encodeToByteArray().copyInto(it); header("GZLE01", false).copyInto(it, 0x58) }
        assertEquals("GZLE01", NintendoDisc.gameId(rvz))
        val wia = ByteArray(0x100).also { "WIA\u0001".encodeToByteArray().copyInto(it); header("RSBE01", true).copyInto(it, 0x58) }
        assertEquals("RSBE01", NintendoDisc.gameId(wia))
        val ciso = ByteArray(NintendoDisc.HEAD).also { "CISO".encodeToByteArray().copyInto(it); header("GM4E01", false).copyInto(it, 0x8000) }
        assertEquals("GM4E01", NintendoDisc.gameId(ciso))
    }

    @Test
    fun anythingElseIsNotRead() {
        assertNull(NintendoDisc.gameId(ByteArray(0x40)))
        assertNull(NintendoDisc.gameId(ByteArray(3)))
        // The magic without a sensible id.
        assertNull(NintendoDisc.gameId(header("ga e0!", wii = false)))
        // A compressed image whose header isn't a disc's.
        assertNull(NintendoDisc.gameId(ByteArray(0x100).also { "RVZ\u0001".encodeToByteArray().copyInto(it) }))
        assertEquals("GALE01", NintendoDisc.fromTag("gale01"))
        assertNull(NintendoDisc.fromTag("SLUS-20946"))
    }
}
