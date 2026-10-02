package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.patches.Pcsx2PatchRules
import io.github.matiyaaa.fuse.launch.patches.PatchState
import io.github.matiyaaa.fuse.launch.patches.Pnach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Pcsx2PatchesTest {
    private val pnach = """
        gametitle=Sky Racer (SLUS-20946)
        // an unlabelled patch is always on and isn't listed
        patch=1,EE,00100000,word,00000000

        [Widescreen 16:9]
        author=someone
        description=Renders in 16:9
        patch=1,EE,00200000,word,3F400000 // aspect

        [60 FPS]
        comment=Runs at 60 frames a second
        patch=0,EE,00300000,word,00000001

        [Empty group]
        description=has no patch lines
    """.trimIndent()

    @Test
    fun pnachGroupsWithPatchesAreListed() {
        val patches = Pnach.parse(pnach)
        assertEquals(listOf("Widescreen 16:9", "60 FPS"), patches.map { it.name })
        assertEquals("Renders in 16:9", patches[0].description)
        assertEquals("someone", patches[0].author)
        assertEquals("Runs at 60 frames a second", patches[1].description)
    }

    @Test
    fun theIniKeepsEverythingElseAsItWas() {
        val text = "; my notes\r\n[EmuCore]\r\nEnableCheats = false\r\n\r\n[Patches]\r\nEnable = Mine\r\n\r\n[Other]\r\nx = 1\r\n"
        val ini = IniText(text)
        assertTrue(ini.add("Patches", "Enable", "60 FPS"))
        assertEquals(listOf("Mine", "60 FPS"), ini.values("patches", "enable"))
        assertEquals(
            "; my notes\r\n[EmuCore]\r\nEnableCheats = false\r\n\r\n[Patches]\r\nEnable = Mine\r\nEnable = 60 FPS\r\n\r\n[Other]\r\nx = 1\r\n",
            ini.toString(),
        )
        assertTrue(ini.remove("Patches", "Enable", "60 FPS"))
        assertEquals(text, ini.toString())

        val fresh = IniText("")
        fresh.add("Patches", "Enable", "60 FPS")
        assertEquals("[Patches]\nEnable = 60 FPS\n", fresh.toString())
    }

    @Test
    fun fuseOnlyTurnsOffWhatItTurnedOn() {
        val patches = Pnach.parse(pnach) + io.github.matiyaaa.fuse.launch.patches.PnachPatch("No-Interlacing")
        val game = IniText("[Patches]\nEnable = Widescreen 16:9\nDisable = No-Interlacing\n")
        val global = IniText("[EmuCore]\nEnableNoInterlacingPatches = true\n")
        var owned = emptySet<String>()
        var states = Pcsx2PatchRules.states(patches, game, global, owned).associateBy { it.patch.name }
        assertEquals(PatchState.ON_IN_PCSX2, states.getValue("Widescreen 16:9").state)
        assertEquals(PatchState.OFF, states.getValue("60 FPS").state)
        assertEquals(PatchState.OFF_IN_PCSX2, states.getValue("No-Interlacing").state)

        // The user's own patch can't be turned off from Fuse.
        assertNull(Pcsx2PatchRules.change(states.getValue("Widescreen 16:9"), on = false, game = game, owned = owned))
        assertNull(Pcsx2PatchRules.change(states.getValue("No-Interlacing"), on = true, game = game, owned = owned))

        owned = Pcsx2PatchRules.change(states.getValue("60 FPS"), on = true, game = game, owned = owned)!!
        states = Pcsx2PatchRules.states(patches, game, global, owned).associateBy { it.patch.name }
        assertEquals(PatchState.ON_BY_FUSE, states.getValue("60 FPS").state)
        owned = Pcsx2PatchRules.change(states.getValue("60 FPS"), on = false, game = game, owned = owned)!!
        assertEquals(emptySet(), owned)
        assertEquals("[Patches]\nEnable = Widescreen 16:9\nDisable = No-Interlacing\n", game.toString())
    }

    @Test
    fun widescreenForEveryGameIsPcsx2s() {
        val ws = io.github.matiyaaa.fuse.launch.patches.PnachPatch("Widescreen 16:9")
        val s = Pcsx2PatchRules.states(listOf(ws), IniText(""), IniText("[EmuCore]\nEnableWideScreenPatches = true\n"), emptySet()).single()
        assertEquals(PatchState.ON_FOR_ALL_GAMES, s.state)
        assertTrue(s.on && !s.fuseCanChange)
    }

    @Test
    fun namesFollowPcsx2() {
        assertEquals("SLUS-20946_0C040404.ini", Pcsx2PatchRules.gameSettingsName("SLUS-20946", 0x0C040404))
        assertEquals(listOf("SLUS-20946_0C040404.pnach", "0C040404.pnach"), Pcsx2PatchRules.zipEntries("SLUS-20946", 0x0C040404))
        assertTrue(Pcsx2PatchRules.matchesPnach("SLUS-20946_0C040404.pnach", "SLUS-20946", 0x0C040404))
        assertTrue(Pcsx2PatchRules.matchesPnach("0C040404 - mine.pnach", "SLUS-20946", 0x0C040404))
        assertTrue(!Pcsx2PatchRules.matchesPnach("SLUS-20946_FFFFFFFF.pnach", "SLUS-20946", 0x0C040404))
    }
}
