package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.patches.EnhancementRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnhancementRulesTest {
    @Test
    fun unsafePathsAreCaught() {
        assertTrue(EnhancementRules.unsafe("../evil.png"))
        assertTrue(EnhancementRules.unsafe("a/../../evil.png"))
        assertTrue(EnhancementRules.unsafe("/etc/passwd"))
        assertTrue(EnhancementRules.unsafe("C:/Windows/evil.dll"))
        assertTrue(EnhancementRules.unsafe("a\\..\\..\\evil.png"))
        assertTrue(EnhancementRules.unsafe("a/b\u0000.png"))
        assertTrue(EnhancementRules.unsafe(""))
        assertFalse(EnhancementRules.unsafe("SLUS-20062/replacements/a.png"))
        assertFalse(EnhancementRules.unsafe("./a/b.png"))
        assertFalse(EnhancementRules.unsafe("a..b.png"))
        assertEquals("a/b.png", EnhancementRules.clean("./a//b.png"))
    }

    @Test
    fun pcsx2PackRootIsItsReplacementsFolder() {
        val pack = EnhancementRules.texturePack(
            "pcsx2",
            listOf(
                "Pack v2/SLUS-20062/replacements/hud/a.png",
                "Pack v2/SLUS-20062/replacements/b.dds",
                "Pack v2/SLUS-20062/replacements/c.txt",
                "Pack v2/readme.txt",
                "Pack v2/SLUS-20062/replacements/",
                "../escape.png",
            ),
        )!!
        assertEquals(
            listOf("Pack v2/SLUS-20062/replacements/hud/a.png" to "hud/a.png", "Pack v2/SLUS-20062/replacements/b.dds" to "b.dds"),
            pack.files,
        )
        assertTrue(pack.rejected.any { it.startsWith("../escape.png") && "outside" in it })
        assertTrue(pack.rejected.any { it.startsWith("Pack v2/readme.txt") && "not part" in it })
        assertTrue(pack.rejected.any { it.startsWith("Pack v2/SLUS-20062/replacements/c.txt") && "PCSX2" in it })
    }

    @Test
    fun loosePicturesArePackedFromTheirOwnFolder() {
        val pack = EnhancementRules.texturePack("duckstation", listOf("textures/vram-write-1.png", "textures/sub/texpage-2.webp", "textures/x.dds"))!!
        assertEquals(listOf("textures/vram-write-1.png" to "vram-write-1.png", "textures/sub/texpage-2.webp" to "sub/texpage-2.webp"), pack.files)
        assertEquals(1, pack.rejected.size)
        assertEquals(true to true, EnhancementRules.duckStationNeeds(pack.files.map { it.second }))
        assertEquals(true to false, EnhancementRules.duckStationNeeds(listOf("a/VRAM-WRITE-1.png")))
    }

    @Test
    fun ppssppPackIsTheFolderOfItsIni() {
        val pack = EnhancementRules.texturePack("ppsspp", listOf("ULUS10041/textures.ini", "ULUS10041/new/a.png", "ULUS10041/.DS_Store", "other.txt"))!!
        assertEquals(listOf("ULUS10041/textures.ini" to "textures.ini", "ULUS10041/new/a.png" to "new/a.png"), pack.files)
        assertNull(EnhancementRules.texturePack("ppsspp", listOf("a.png")))
        assertNull(EnhancementRules.texturePack("pcsx2", listOf("readme.txt")))
        assertNull(EnhancementRules.texturePack("dolphin", listOf("a.png")))
    }

    @Test
    fun ownPnachIsNamedSoPcsx2ReadsItBesideTheOthers() {
        assertEquals("SLUS-20062_ABCD1234_60 fps.pnach", EnhancementRules.pnachName("SLUS-20062", 0xABCD1234, "60 fps!.pnach"))
        assertEquals("SLUS-20062_0000BEEF_own.pnach", EnhancementRules.pnachName("slus-20062", 0xBEEF, "###"))
    }

    @Test
    fun codesAreCountedAsEachEmulatorReadsThem() {
        assertEquals(1, EnhancementRules.codesIn("pcsx2", "[Infinite Health]\npatch=1,EE,00100000,word,00000000\n"))
        assertEquals(1, EnhancementRules.codesIn("duckstation", "[Max Money]\nType = Gameshark\nActivation = EndFrame\n80010000 FFFF\n"))
        assertEquals(1, EnhancementRules.codesIn("ppsspp", "_S ULUS-10041\n_G Game\n_C0 Money\n_L 0x20000000 0x0000FFFF\n", "ULUS10041"))
        assertEquals(1, EnhancementRules.codesIn("retroarch", "cheats = 1\ncheat0_desc = \"x\"\ncheat0_code = \"1\"\n"))
        assertEquals(0, EnhancementRules.codesIn("pcsx2", "just words"))
    }

    @Test
    fun appendedCodesComeOutAgainExactly() {
        val before = "[A]\r\n80000000 0001\r\n"
        val block = "[B]\n80000000 0002"
        val after = EnhancementRules.append(before, block)
        assertEquals("[A]\r\n80000000 0001\r\n[B]\r\n80000000 0002\r\n", after)
        assertEquals(before, EnhancementRules.removeAppended(after, block))
        assertEquals("", EnhancementRules.removeAppended(EnhancementRules.append(null, block), block))
        assertNull(EnhancementRules.removeAppended("[A]\n", block))
        // Codes added after it stay.
        val more = after + "[C]\r\n1\r\n"
        assertEquals("[A]\r\n80000000 0001\r\n[C]\r\n1\r\n", EnhancementRules.removeAppended(more, block))
    }

    @Test
    fun ppssppCheatsTurnedOnSinceStillComeOut() {
        val block = "_C0 Money\n_L 0x20000000 0x0000FFFF"
        val file = EnhancementRules.append("_S ULUS-10041\n", block).replace("_C0 Money", "_C1 Money")
        assertEquals("_S ULUS-10041\n", EnhancementRules.removeAppended(file, block, ppsspp = true))
        assertNull(EnhancementRules.removeAppended(file, block, ppsspp = false))
    }

    @Test
    fun aPnachNameSaysTheCrcPcsx2MatchesItTo() {
        assertEquals("94A82AAA", EnhancementRules.pnachCrc("94A82AAA_Run_R2.pnach"))
        // Nine digits: a variant its makers keep off, as PCSX2 won't match it.
        assertEquals("094A82AAA", EnhancementRules.pnachCrc("cheats/094A82AAA_Run_Circle.pnach"))
        assertEquals("ABCD1234", EnhancementRules.pnachCrc("SLUS-20062_ABCD1234.pnach"))
        assertEquals("ABCD1234", EnhancementRules.pnachCrc("SLUS_20062_ABCD1234_60fps.pnach"))
        assertNull(EnhancementRules.pnachCrc("Infinite Health.pnach"))
        assertNull(EnhancementRules.pnachCrc("SLUS-20062.pnach"))
    }

    @Test
    fun pnachAddressesAreReadFromItsPatchLines() {
        val a = EnhancementRules.pnachAddresses("[A]\npatch=1,EE,00201234,word,00000001 // speed\n// patch=1,EE,1,word,1\npatch=0,ee,2B000,byte,1\n")
        assertEquals(setOf("EE:201234", "EE:2B000"), a)
        assertTrue(EnhancementRules.pnachAddresses("gametitle=x").isEmpty())
    }
}
