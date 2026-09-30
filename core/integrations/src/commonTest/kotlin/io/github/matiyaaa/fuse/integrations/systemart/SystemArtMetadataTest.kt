package io.github.matiyaaa.fuse.integrations.systemart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A trimmed copy of Art Book Next's `_inc/systems/_metadata-global/snes.xml`. */
internal val SNES_XML = """
<theme>
    <variables>
        <systemName>Super Nintendo</systemName>
        <systemDescription>The Super Nintendo Entertainment System (also known as the Super NES, SNES or Super Nintendo) is a 16-bit home video game console developed by Nintendo.</systemDescription>
        <systemManufacturer>Nintendo</systemManufacturer>
        <systemReleaseYear>1992</systemReleaseYear>
        <systemReleaseDate>1992-04-11</systemReleaseDate>
        <systemReleaseDateFormated>April 11, 1992</systemReleaseDateFormated>
        <systemHardwareType>Console</systemHardwareType>
        <systemCoverSize>4-3</systemCoverSize>
        <systemCoverSizeType>landscape</systemCoverSizeType>
        <systemColor>df5142</systemColor>
        <systemColorPalette1>FED01B</systemColorPalette1>
        <systemColorPalette2>BA2318</systemColorPalette2>
        <systemColorPalette3>0A2A8D</systemColorPalette3>
        <systemColorPalette4>007544</systemColorPalette4>
        <systemCartSize>112-67</systemCartSize>
    </variables>
    <language name="de_DE">
        <variables>
            <systemName>Super Nintendo (DE)</systemName>
            <systemDescription>Das Super Nintendo Entertainment System ist eine von Nintendo entwickelte 16-Bit-Videospielkonsole.</systemDescription>
            <systemHardwareType>Konsole</systemHardwareType>
        </variables>
    </language>
</theme>
""".trimIndent()

class SystemArtMetadataTest {

    @Test
    fun readsTheTopLevelVariablesOnly() {
        val meta = SystemArtMetadata.parse(SNES_XML)!!
        assertEquals("Super Nintendo", meta.name)
        assertEquals("Nintendo", meta.manufacturer)
        assertEquals(1992, meta.year)
        assertEquals("Console", meta.hardwareType)
        assertEquals(0xFFDF5142, meta.color)
        assertEquals(listOf(0xFFFED01B, 0xFFBA2318, 0xFF0A2A8D, 0xFF007544), meta.palette)
    }

    @Test
    fun toleratesMissingAndOddValues() {
        val xml = """
            <theme><variables>
                <systemName>Arcade &amp; Co</systemName>
                <systemManufacturer>Various</systemManufacturer>
                <systemReleaseYear>Various</systemReleaseYear>
                <systemColor>not a colour</systemColor>
                <systemColorPalette2>11223380</systemColorPalette2>
            </variables></theme>
        """.trimIndent()
        val meta = SystemArtMetadata.parse(xml)!!
        assertEquals("Arcade & Co", meta.name)
        assertEquals("Various", meta.manufacturer)
        assertNull(meta.year)
        assertNull(meta.hardwareType)
        assertNull(meta.color)
        assertEquals(listOf(0x80112233L), meta.palette)
    }

    @Test
    fun withoutASystemNameThereIsNoMetadata() {
        assertNull(SystemArtMetadata.parse(""))
        assertNull(SystemArtMetadata.parse("<html><body>404: Not Found</body></html>"))
        // A name that only appears in a translation does not count.
        assertNull(SystemArtMetadata.parse("<theme><variables/><language name=\"x\"><variables><systemName>X</systemName></variables></language></theme>"))
    }
}
