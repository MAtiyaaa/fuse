package io.github.matiyaaa.fuse.launch.ports

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * XXH3-64 against the reference library (xxHash 0.8 through the `xxhash` 3.5.0 package's
 * `xxh3_64_hexdigest`), over the bytes `(i * 31 + 7) & 0xff`, at every length its code treats
 * differently: none, 1 to 3, 4 to 8, 9 to 16, 17 to 128, 129 to 240, and long ones over blocks.
 */
class Xxh3Test {
    private fun bytes(n: Int) = ByteArray(n) { ((it * 31 + 7) and 0xff).toByte() }

    @Test
    fun everyLengthHashesAsTheReferenceDoes() {
        val expected = mapOf(
            0 to "2d06800538d394c2", 1 to "4c5cca45d0f4811f", 3 to "15f7093b173d005c", 4 to "dca012f95811b6b9",
            8 to "dec6a9a43575982e", 9 to "cbe393399f17ffbd", 16 to "7e484c18d74895d0", 17 to "208bde5ee2bed407",
            128 to "f92b70eaa21a6288", 129 to "f8f76713f2bb60fa", 240 to "ccc7375172c41f03", 241 to "0b3b630948ce4a00",
            1024 to "23bc880ebf0d29c6", 1025 to "c09fdfbc398c7d82", 4096 to "a3c19f8174cde0bb", 100000 to "ccf90df7e7e37036",
        )
        for ((n, hex) in expected) assertEquals(hex, Xxh3.hex(bytes(n)), "length $n")
        // Only the first [length] bytes count.
        assertEquals(expected.getValue(1024), Xxh3.hex(bytes(2048).copyOf(1024)))
        assertEquals(expected.getValue(1024), Xxh3.hex(bytes(1024) + ByteArray(10), 1024))
    }

    @Test
    fun anN64RomInAnyOrderIsTurnedIntoItsOwnAsTheRuntimeDoes() {
        // librecomp select_rom: 80 37 12 40 first, as it is; pairs swapped (.v64) or fours reversed (.n64) turned round.
        val z64 = ByteArray(16) { it.toByte() }.also { it[0] = 0x80.toByte(); it[1] = 0x37; it[2] = 0x12; it[3] = 0x40 }
        val v64 = ByteArray(16) { z64[it xor 1] }
        val n64 = ByteArray(16) { z64[it xor 3] }
        assertEquals(z64.toList(), PortPlan.bigEndianRom(z64)!!.toList())
        assertEquals(z64.toList(), PortPlan.bigEndianRom(v64)!!.toList())
        assertEquals(z64.toList(), PortPlan.bigEndianRom(n64)!!.toList())
        assertEquals(true, PortPlan.isBigEndianRom(z64))
        assertEquals(false, PortPlan.isBigEndianRom(v64))
        assertEquals(null, PortPlan.bigEndianRom(ByteArray(16)), "not an N64 ROM")
        // Padded to four bytes, as the runtime pads it before hashing.
        assertEquals(20, PortPlan.bigEndianRom(z64 + byteArrayOf(1, 2))!!.size)
        val rom = io.github.matiyaaa.fuse.model.PortRom("n64", Xxh3.hex(z64), "x.z64")
        assertEquals(true, PortPlan.isRom(rom, v64))
        assertEquals(false, PortPlan.isRom(rom, z64.copyOf().also { it[9] = 0 }))
    }
}
