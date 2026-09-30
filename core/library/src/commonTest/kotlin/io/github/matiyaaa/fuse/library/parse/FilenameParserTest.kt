package io.github.matiyaaa.fuse.library.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilenameParserTest {
    private fun parse(name: String) = FilenameParser.parse(name)

    @Test
    fun noIntroWithRevisionAndVerifiedFlag() {
        val p = parse("Metroid Fusion (USA) (Rev 1) [!].gba")
        assertEquals("Metroid Fusion", p.baseTitle)
        assertEquals("gba", p.extension)
        assertEquals(listOf("USA"), p.tags.regions)
        assertEquals("1", p.tags.revision)
        assertEquals(listOf(NameFlags.VERIFIED), p.tags.flags)
    }

    @Test
    fun redumpDiscWithTotalAndLanguages() {
        val p = parse("Final Fantasy VII (Disc 2 of 3) (Europe) (En,Fr,De).chd")
        assertEquals("Final Fantasy VII", p.baseTitle)
        assertEquals(2, p.tags.discNumber)
        assertEquals(3, p.tags.discTotal)
        assertEquals(listOf("Europe"), p.tags.regions)
        assertEquals(listOf("English", "French", "German"), p.tags.languages)
    }

    @Test
    fun goodToolsRegionCodeAndTranslation() {
        val p = parse("Pokemon - Emerald Version (U) [T+Fre].gba")
        assertEquals("Pokemon - Emerald Version", p.baseTitle)
        assertEquals(listOf("USA"), p.tags.regions)
        assertTrue(NameFlags.TRANSLATION in p.tags.flags)
        assertEquals("French", p.translation)
    }

    @Test
    fun ps3SerialInBrackets() {
        val p = parse("Gran Turismo 5 [BCES00569].ps3")
        assertEquals("Gran Turismo 5", p.baseTitle)
        assertEquals("BCES00569", p.tags.serial)
        assertEquals("ps3", p.extension)
    }

    @Test
    fun versionTag() {
        val p = parse("Zelda (Japan) (v1.1).z64")
        assertEquals("Zelda", p.baseTitle)
        assertEquals(listOf("Japan"), p.tags.regions)
        assertEquals("1.1", p.tags.version)
    }

    @Test
    fun tosecYearPublisherDiskAndCrack() {
        val p = parse("Game (1990)(Publisher)(Disk 1 of 2)[cr].adf")
        assertEquals("Game", p.baseTitle)
        assertEquals(1990, p.year)
        assertEquals("Publisher", p.publisher)
        assertEquals(1, p.tags.discNumber)
        assertEquals(2, p.tags.discTotal)
        assertEquals(listOf(NameFlags.CRACKED), p.tags.flags)
    }

    @Test
    fun tosecVersionInTitle() {
        val p = parse("Turrican II v1.1 (1991)(Rainbow Arts)[h].adf")
        assertEquals("Turrican II", p.baseTitle)
        assertEquals("1.1", p.tags.version)
        assertEquals("Rainbow Arts", p.publisher)
        assertEquals(listOf(NameFlags.HACK), p.tags.flags)
    }

    @Test
    fun multipleRegionsInOneTag() {
        val p = parse("Sonic the Hedgehog (USA, Europe).md")
        assertEquals(listOf("USA", "Europe"), p.tags.regions)
    }

    @Test
    fun goodToolsCombinedRegionCodes() {
        assertEquals(listOf("Japan", "USA"), parse("Tetris (JU) [!].gb").tags.regions)
        assertEquals(listOf("World"), parse("Pac-Man (W).nes").tags.regions)
    }

    @Test
    fun dumpFlagsWithNumbers() {
        val p = parse("Super Mario Bros. (E) [a1][b2][h1C][o1][f1].nes")
        assertEquals("Super Mario Bros.", p.baseTitle)
        assertEquals(
            listOf(NameFlags.ALTERNATE, NameFlags.BAD_DUMP, NameFlags.HACK, NameFlags.OVERDUMP, NameFlags.FIXED),
            p.tags.flags,
        )
    }

    @Test
    fun statusFlags() {
        val p = parse("Star Fox 2 (Japan) (Beta).sfc")
        assertEquals(listOf(NameFlags.BETA), p.tags.flags)
        assertEquals(listOf(NameFlags.PROTO), parse("Game (USA) (Proto 2).gba").tags.flags)
        assertEquals(listOf(NameFlags.DEMO), parse("Game (Europe) (Demo).iso").tags.flags)
        assertEquals(listOf(NameFlags.SAMPLE), parse("Game (Japan) (Sample).nds").tags.flags)
        assertEquals(listOf(NameFlags.UNLICENSED), parse("Action 52 (USA) (Unl).nes").tags.flags)
        assertEquals(listOf(NameFlags.HACK), parse("Mario (Hack by Someone).sfc").tags.flags)
    }

    @Test
    fun revisionLetterAndCompactForms() {
        assertEquals("A", parse("Game (USA) (Rev A).n64").tags.revision)
        assertEquals("01", parse("Game (REV01).iso").tags.revision)
        // "Reverse" is not a revision.
        assertNull(parse("Game (Reverse).gba").tags.revision)
    }

    @Test
    fun discVariants() {
        assertEquals(1, parse("Game (CD1).bin").tags.discNumber)
        assertEquals(2, parse("Game (CD 2).cue").tags.discNumber)
        assertEquals(2, parse("Game - Disc 2.cue").tags.discNumber)
        assertEquals("Game", parse("Game - Disc 2.cue").baseTitle)
        assertEquals(2, parse("Game (Disk B).adf").tags.discNumber)
        assertEquals(1, parse("Game (Side A).dsk").tags.discNumber)
        // "CDi" is not a disc marker.
        assertNull(parse("Hotel Mario (CDi).iso").tags.discNumber)
    }

    @Test
    fun playstationSerialsAreNormalised() {
        assertEquals("SLUS-01234", parse("Game [SLUS_012.34].bin").tags.serial)
        assertEquals("SLUS-01234", parse("Game (SLUS-01234).cue").tags.serial)
        assertEquals("SCES-00001", parse("Game [SCES00001].iso").tags.serial)
        assertEquals("BLUS30001", parse("Game [BLUS-30001].iso").tags.serial)
        assertEquals("PCSB00245", parse("Game [PCSB00245].psvita").tags.serial)
        assertEquals("CUSA12345", parse("Game [CUSA12345].ps4").tags.serial)
        assertEquals("NPUB30001", parse("Game [NPUB30001].pkg").tags.serial)
        assertEquals("ULUS10041", parse("Game [ULUS-10041].iso").tags.serial)
    }

    @Test
    fun bareSerialFolderNames() {
        val p = FilenameParser.parse("BLUS30001", hasExtension = false)
        assertEquals("BLUS30001", p.tags.serial)
        assertEquals("BLUS30001", p.baseTitle)
        val q = FilenameParser.parse("BLUS30001-[Metal Gear Solid 4]", hasExtension = false)
        assertEquals("BLUS30001", q.tags.serial)
        assertEquals("Metal Gear Solid 4", q.baseTitle)
        val r = FilenameParser.parse("BCES00569 - Gran Turismo 5", hasExtension = false)
        assertEquals("Gran Turismo 5", r.baseTitle)
    }

    @Test
    fun switchTitleIdsAndContentMarkers() {
        val base = parse("Zelda BOTW [01007EF00011E000][v0].nsp")
        assertEquals("01007EF00011E000", base.tags.serial)
        assertFalse(base.isUpdate)
        assertTrue(parse("Zelda BOTW [01007EF00011E800][v786432].nsp").isUpdate)
        assertTrue(parse("Zelda BOTW [v131072].nsp").isUpdate)
        assertTrue(parse("Zelda BOTW [UPD].nsp").isUpdate)
        assertTrue(parse("Zelda BOTW (Update).nsp").isUpdate)
        assertTrue(parse("Zelda BOTW - Master Trials [01007EF00011F001].nsp").isDlc)
        assertTrue(parse("Zelda BOTW [DLC].nsp").isDlc)
        assertEquals("01007EF00011E000", Serials.switchBaseTitleId("01007EF00011F001"))
        assertEquals("01007EF00011E000", Serials.switchBaseTitleId("01007EF00011E800"))
    }

    @Test
    fun unknownTagsAreKeptAsUnknown() {
        val p = parse("Tetris (Tengen) (USA).nes")
        assertEquals("Tetris", p.baseTitle)
        assertEquals(listOf("Tengen"), p.unknownTags.map { it.text })
        assertEquals(listOf("USA"), p.tags.regions)
    }

    @Test
    fun nestedAndUnbalancedBracketsDoNotThrow() {
        val nested = parse("Game (Hack (v2)) [!].sfc")
        assertEquals("Game", nested.baseTitle)
        assertTrue(NameFlags.HACK in nested.tags.flags)
        val unclosed = parse("Game (USA.sfc")
        assertEquals("sfc", unclosed.extension)
        assertEquals("Game (USA", unclosed.baseTitle)
        val stray = parse("Game) [!].sfc")
        assertEquals(listOf(NameFlags.VERIFIED), stray.tags.flags)
        val mixed = parse("Game (USA] (Rev 1).sfc")
        assertEquals("1", mixed.tags.revision)
    }

    @Test
    fun extensionsAndDotsInTitles() {
        assertEquals("Super Mario Bros. 3", parse("Super Mario Bros. 3.nes").baseTitle)
        assertEquals("", FilenameParser.parse("Dr. Mario", hasExtension = false).extension)
        assertEquals("Dr. Mario", FilenameParser.parse("Dr. Mario", hasExtension = false).baseTitle)
        assertEquals("", parse("Game (v1.1)").extension)
        assertEquals("3ds", parse("Pokemon X.3ds").extension)
    }

    @Test
    fun languageNamesAndMultiLanguageCount() {
        val p = parse("Game (Europe) (M5).gba")
        assertEquals(listOf("Europe"), p.tags.regions)
        assertTrue(p.nameTags.any { it.text == "M5" && it.kind == TagKind.LANGUAGE })
        assertEquals(listOf("Japanese"), parse("Game (Japan) (Ja).gb").tags.languages)
    }

    @Test
    fun providerIdsAndVideoStandardsAreRecognised() {
        val p = parse("Game (USA) (igdb-1234) (PAL).iso")
        assertEquals(TagKind.PROVIDER_ID, p.nameTags[1].kind)
        assertEquals(TagKind.VIDEO, p.nameTags[2].kind)
        assertTrue(p.unknownTags.isEmpty())
    }

    @Test
    fun withoutDiscKeepsReleaseDistinct() {
        val a = FilenameParser.withoutDisc(parse("Final Fantasy VII (Europe) (Disc 1).chd"))
        val b = FilenameParser.withoutDisc(parse("Final Fantasy VII (Disc 2) (Europe).chd"))
        val c = FilenameParser.withoutDisc(parse("Final Fantasy VII (USA) (Disc 1).chd"))
        assertEquals("Final Fantasy VII (Europe)", a)
        assertEquals(a, b)
        assertTrue(a != c)
        assertEquals("Game (USA)", FilenameParser.withoutDisc(parse("Game - Disc 2 (USA).cue")))
    }
}
