package io.github.matiyaaa.fuse.launch.patches

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Dolphin's Gecko and Action Replay codes read and written as Dolphin's own code does (made-up codes). */
class DolphinCheatsTest {
    private val gameIni = """
        [Gecko]
        ${'$'}Infinite Lives [Ana]
        *Keeps the counter at 9
        04123456 00000009
        # a comment Dolphin skips
        ${'$'}Moon Jump
        C2000000 00000002
        60000000 00000000
        [ActionReplay]
        ${'$'}All Items
        0A1B-2C3D-4E5F6
        00800000 00000063
        [Gecko_Enabled]
        ${'$'}Infinite Lives
        [Video_Settings]
        HiresTextures = True
    """.trimIndent()

    @Test
    fun aGameFileGivesItsGeckoAndActionReplayCodes() {
        val read = DolphinCheats.parse(gameIni)
        assertEquals(listOf("Infinite Lives [Ana]", "Moon Jump", "All Items"), read.codes.map { it.name })
        val lives = read.codes.first()
        assertEquals(DolphinCheats.Kind.GECKO, lives.kind)
        assertEquals(listOf("Keeps the counter at 9"), lives.notes)
        assertEquals(listOf("04123456 00000009"), lives.lines)
        assertEquals("Infinite Lives", DolphinCheats.shortName(lives))
        assertEquals(listOf("0A1B-2C3D-4E5F6", "00800000 00000063"), read.codes.last().lines, "encrypted and decrypted lines both")
        assertTrue(read.rejected.isEmpty(), read.rejected.toString())
    }

    @Test
    fun aPlainListIsGeckoCodesAndWhatDolphinWouldntReadIsSaid() {
        val read = DolphinCheats.parse("﻿\$Speed x2\r\n04ABCDEF 3F800000\r\nnot a code\r\n\$Empty\r\n")
        assertEquals(listOf("Speed x2"), read.codes.map { it.name })
        assertEquals(DolphinCheats.Kind.GECKO, read.codes.single().kind)
        assertTrue(read.rejected.any { it.startsWith("not a code") })
        assertTrue(read.rejected.any { it.startsWith("\$Empty") })
        // Sections Dolphin doesn't keep codes in are passed over.
        assertTrue(DolphinCheats.parse("[Core]\nCPUThread = True\n").codes.isEmpty())
    }

    @Test
    fun theBlockIsOneThatDolphinsIniReaderAddsToWhatIsThere() {
        val codes = DolphinCheats.parse(gameIni).codes
        val block = DolphinCheats.block(codes)
        assertEquals(
            """
            [Gecko]
            ${'$'}Infinite Lives [Ana]
            *Keeps the counter at 9
            04123456 00000009
            ${'$'}Moon Jump
            C2000000 00000002
            60000000 00000000
            [ActionReplay]
            ${'$'}All Items
            0A1B-2C3D-4E5F6
            00800000 00000063
            [Core]
            EnableCheats = True
            """.trimIndent(),
            block,
        )
        // Read back the way Dolphin would after the block is appended: every code once more, by name.
        assertEquals(setOf("Infinite Lives [Ana]", "Moon Jump"), DolphinCheats.namesIn(gameIni, DolphinCheats.Kind.GECKO))
        assertEquals(setOf("All Items"), DolphinCheats.namesIn(gameIni, DolphinCheats.Kind.ACTION_REPLAY))
        assertEquals(codes.map { it.name }, DolphinCheats.parse(block).codes.map { it.name })
    }
}
