package io.github.matiyaaa.fuse.library.parse

import io.github.matiyaaa.fuse.library.parse.SwitchTitleKind.BASE
import io.github.matiyaaa.fuse.library.parse.SwitchTitleKind.DLC
import io.github.matiyaaa.fuse.library.parse.SwitchTitleKind.UPDATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Made-up titles, named every way real Switch libraries name their files. */
class SwitchNamesTest {
    private fun kind(name: String) = SwitchNames.read(name).kind
    private fun key(name: String) = SwitchNames.read(name).key

    @Test
    fun aTitleIdDecidesInBracketsOrNotInAnyCase() {
        assertEquals(BASE, kind("Harbor Lights [0100AAAA11112000][v0][US].nsp"))
        assertEquals(UPDATE, kind("Harbor Lights [0100AAAA11112800][v196608][US].nsp"))
        assertEquals(DLC, kind("Harbor Lights [Lighthouse Pack] [0100AAAA11113001][v0][US].nsp"))
        assertEquals(UPDATE, kind("Harbor Lights 0100AAAA11112800__v65536___0.02_GB_.nsz"))
        assertEquals(UPDATE, kind("harbor-lights-[0100aaaa11112800][v131072][1.0.2].nsp"))
        val dlc = SwitchNames.read("Harbor Lights[0100AAAA11113004][US][v0].nsp")
        assertEquals("0100AAAA11112000", dlc.baseId)
    }

    @Test
    fun anUpdateNamedWithItsGamesIdIsStillAnUpdate() {
        // The version (or the word) tells, whatever the id says.
        assertEquals(UPDATE, kind("Paper Kites [0100BBBB22224000][USA][v131072].nsp"))
        assertEquals(UPDATE, kind("Paper Kites [0100BBBB22224000][v262144]Update 1.0.3.nsp"))
        assertEquals(BASE, kind("Paper Kites [0100BBBB22224000][USA][v0](eShop).nsp"))
    }

    @Test
    fun updatesWithoutAnIdAreReadFromTheirVersionsAndWords() {
        assertEquals(UPDATE, kind("Moss Garden v1.1.3.nsp"))
        assertEquals(UPDATE, kind("Moss Garden v524288.nsp"))
        assertEquals(UPDATE, kind("Moss Garden Update v1.6.15.13.nsp"))
        assertEquals(UPDATE, kind("Moss Garden (Update 1.0.1).nsp"))
        assertEquals(UPDATE, kind("Moss Garden v1.25.9.19_5499.nsp"))
        assertEquals(UPDATE, kind("Moss Garden Update 1.0.3.37670 NSP.nsp"))
        assertEquals(UPDATE, kind("MOSS GARDEN [0100CCCC33336800][v393216][Update v1.4.1].nsp"))
        assertEquals(UPDATE, kind("Moss Garden v1.1.0[0100CCCC33336800][65536][UPD].nsp"))
        assertEquals(UPDATE, kind("Moss Garden [0100CCCC33336800][v1179648][US][Up v1.86.1233].nsp"))
        assertEquals(UPDATE, kind("Moss Garden [v720896].nsp"))
        assertEquals(UPDATE, kind("sxs-moss_garden_v327680.nsp"))
        assertEquals("moss garden", key("sxs-moss_garden_v327680.nsp"))
    }

    @Test
    fun dlcWithoutAnIdIsReadFromItsWords() {
        assertEquals(DLC, kind("Moss Garden DLC.Seed.Pack.nsp"))
        assertEquals(DLC, kind("Moss Garden [Gardener Hat DLC][USA][v0].nsp"))
        assertEquals(DLC, kind("Moss Garden [DLC Night Bloom].nsp"))
        assertEquals(DLC, kind("M.G.__DLC_Pack_1_The_Night_Bloom___v196608_.nsp"))
        assertEquals(DLC, kind("sxs-moss_garden_night_bloom_pack_dlc.nsp"))
        assertEquals("moss garden seed pack", key("Moss Garden DLC.Seed.Pack.nsp"))
    }

    @Test
    fun baseGamesKeepTheirTitleWithoutTheNoise() {
        assertNull(kind("Moss Garden.xci"))
        assertEquals(BASE, kind("Moss Garden Base eShop NSP.nsp"))
        assertEquals(BASE, kind("Moss Garden v0__.nsp"))
        assertEquals("moss garden", key("Moss Garden Base eShop NSP.nsp"))
        assertEquals("moss garden", key("Moss Garden(1).nsp"))
        assertEquals("moss garden", key("Moss Garden [0100CCCC33336000]-002.xci"))
        assertEquals("moss garden", key("Moss Garden [0100CCCC33336000] - romsite.com(1).nsp"))
        assertEquals("moss garden", key("Moss Garden (World) (En,Ja,Fr,De).xci"))
        // A trailing word said once already only describes the file; said once, it is the title.
        assertEquals("moss garden switch edition", key("Moss Garden Switch Edition Switch XCI Base Game.xci"))
        assertEquals("1 2 switch", key("1-2 Switch [01000320000CC000][v0].nsp"))
    }

    @Test
    fun lookAlikePunctuationAndApostrophesMatch() {
        assertEquals(key("Moss Garden: Night [0100CCCC33336000].nsp"), key("Moss Garden꞉ Night™ [0100CCCC33336800][v65536].nsp"))
        assertEquals(key("Crab's Cove.nsp"), key("Crabs Cove v1.0.1.nsp"))
        assertEquals(key("Snail & The Shell.xci").split(' ').take(2), listOf("snail", "and"))
    }

    @Test
    fun numberedTitlesStayApart() {
        assertEquals("star rally 2", key("star_rally_2_v1.3.1.nsp"))
        assertEquals("star rally", key("star_rally_v1.3.1.nsp"))
        assertEquals("star rally 2", key("Star Rally 2 .nsp"))
        assertEquals("party pack 3", key("Party Pack 3 eShop.nsp"))
    }

    @Test
    fun oneFileHoldingEverythingIsTheGame() {
        val n = SwitchNames.read("Moss Garden [0100CCCC33336000]+[v13.0.0+99DLC][Patched](romsite.xyz).xci")
        assertEquals(BASE, n.kind)
        assertTrue(n.bundle)
    }

    @Test
    fun bracketedNamesAreTellingButTagsAreNot() {
        assertTrue(SwitchNames.read("Moss Garden [New Uniform Set].nsp").extra)
        assertFalse(SwitchNames.read("Moss Garden [0100CCCC33336000][US][v0][CR-13].nsp").extra)
        assertFalse(SwitchNames.read("Moss Garden [0100CCCC33336000][v0] (0.72 GB).nsp").extra)
        assertFalse(SwitchNames.read("Moss Garden [0100CCCC33336800][v2031616][FW20.5.0.0][3.0.0].nsp").extra)
    }
}
