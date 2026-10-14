package io.github.matiyaaa.fuse.launch.patches

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Shadps4PatchesTest {
    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <!-- <Metadata Name="Commented out" isEnabled="true"> -->
        <Patch>
            <TitleID>
                <ID>CUSA00900</ID>
                <ID>CUSA03173</ID>
            </TitleID>
            <Metadata Title="Bloodborne" Name="60 FPS" Note="Needs a fast PC &amp; patience" Author="lander" PatchVer="1.1" AppVer="01.09" AppElf="eboot.bin" isEnabled="false">
                <PatchList>
                    <Line Type="bytes" Address="0x0123" Value="90909090"/>
                </PatchList>
            </Metadata>
            <Metadata Title="Bloodborne" Name="Skip intro" AppVer="01.00" AppElf="eboot.bin">
                <PatchList>
                    <Line Type="mask" Address="48 8B ?? 90" Value="9090" Offset="2"/>
                </PatchList>
            </Metadata>
        </Patch>
    """.trimIndent()

    @Test
    fun `the patch file for a serial is the one files_json names`() {
        val files = """{"Bloodborne.xml":["CUSA00900","CUSA03173"],"Other.xml":["CUSA00001"]}"""
        assertEquals("Bloodborne.xml", Shadps4Patches.fileFor(files, "CUSA03173"))
        assertNull(Shadps4Patches.fileFor(files, "CUSA99999"))
        assertNull(Shadps4Patches.fileFor("not json", "CUSA00900"))
    }

    @Test
    fun `patches are read as shadPS4 reads them`() {
        val patches = Shadps4Patches.parse(xml)
        assertEquals(listOf("60 FPS", "Skip intro"), patches.map { it.name })
        assertEquals("Needs a fast PC & patience", patches[0].note)
        assertFalse(patches[0].enabled)
        assertTrue(Shadps4Patches.applies(patches[0], "01.09"))
        assertFalse(Shadps4Patches.applies(patches[0], "01.00"))
        // Pattern patches apply whatever the version.
        assertTrue(Shadps4Patches.applies(patches[1], "01.09"))
    }

    @Test
    fun `switching changes only isEnabled`() {
        val on = Shadps4Patches.setEnabled(xml, 0, true)!!
        assertEquals(xml.replaceFirst("isEnabled=\"false\"", "isEnabled=\"true\""), on)
        assertTrue(Shadps4Patches.parse(on)[0].enabled)
        // A patch without the attribute gets it.
        val second = Shadps4Patches.setEnabled(on, 1, true)!!
        assertTrue(Shadps4Patches.parse(second)[1].enabled)
        // Both off again: the file as it was, but for the attribute the second one gained.
        val back = Shadps4Patches.setEnabled(Shadps4Patches.setEnabled(second, 1, false)!!, 0, false)!!
        val gained = back.lastIndexOf(" isEnabled=\"false\"")
        assertEquals(xml, back.removeRange(gained, gained + " isEnabled=\"false\"".length))
        assertNull(Shadps4Patches.setEnabled(xml, 5, true))
    }
}
