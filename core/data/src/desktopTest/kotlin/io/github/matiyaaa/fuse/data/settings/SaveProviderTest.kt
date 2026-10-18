package io.github.matiyaaa.fuse.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaveProviderTest {
    private val fuseOne = AppSettings(sync = SyncSettings(enabled = true, role = "CLIENT"))

    @Test
    fun beforeAnyChoiceItFollowsWhatIsOn() {
        assertEquals(SaveProvider.OFF, SaveProvider.of(AppSettings()))
        assertEquals(SaveProvider.FUSE_ONE, SaveProvider.of(fuseOne))
        assertEquals(SaveProvider.ROMM, SaveProvider.of(AppSettings(romm = FuseRommSettings(enabled = true, saveSync = true))))
        assertEquals(SaveProvider.SYNCTHING, SaveProvider.of(fuseOne.copy(syncthing = SyncthingSettings(enabled = true))))
    }

    @Test
    fun choosingOneTurnsTheOthersOff() {
        val all = fuseOne.copy(romm = FuseRommSettings(enabled = true, saveSync = true), syncthing = SyncthingSettings(enabled = true))
        for (p in SaveProvider.entries) {
            val s = SaveProvider.choose(all, p)
            assertEquals(p, SaveProvider.of(s))
            assertEquals(p == SaveProvider.FUSE_ONE || p == SaveProvider.ROMM_THEN_FUSE_ONE, s.sync.saves)
            assertEquals(p == SaveProvider.ROMM || p == SaveProvider.ROMM_THEN_FUSE_ONE, s.romm.saveSync)
            assertEquals(p == SaveProvider.SYNCTHING, s.syncthing.enabled)
            // Fuse One itself stays on: profiles, play time and the household's games still work.
            assertTrue(s.sync.enabled)
        }
    }

    @Test
    fun oldSettingsWithTwoOnAreSettledToOne() {
        val both = fuseOne.copy(romm = FuseRommSettings(enabled = true, saveSync = true))
        val settled = SaveProvider.settle(both)
        assertEquals(SaveProvider.FUSE_ONE, SaveProvider.of(settled))
        assertFalse(settled.romm.saveSync)
        // Still undecided: it follows what is turned on next.
        assertEquals("", settled.sync.saveProvider)
    }

    @Test
    fun aFreshDeviceIsLeftAsItIsSoTurningFuseOneOnMakesItTheOne() {
        val fresh = AppSettings()
        assertEquals(fresh, SaveProvider.settle(fresh))
        assertEquals(SaveProvider.FUSE_ONE, SaveProvider.of(fresh.copy(sync = fresh.sync.copy(enabled = true, role = "HOST"))))
    }

    @Test
    fun rommThenFuseOneKeepsBothOnOnlyWhenChosen() {
        val chosen = SaveProvider.choose(fuseOne.copy(romm = FuseRommSettings(enabled = true)), SaveProvider.ROMM_THEN_FUSE_ONE)
        assertTrue(chosen.sync.saves && chosen.romm.saveSync)
        // Settling leaves a choice made as it is: both stay on.
        assertEquals(chosen, SaveProvider.settle(chosen))
        assertTrue(SaveProvider.ROMM_THEN_FUSE_ONE.fuseOne && SaveProvider.ROMM_THEN_FUSE_ONE.romm)
    }

    @Test
    fun offOnThisDeviceStaysOffWhateverIsSwitchedOn() {
        val off = SaveProvider.choose(fuseOne, SaveProvider.OFF)
        assertEquals(SaveProvider.OFF, SaveProvider.of(off.copy(romm = off.romm.copy(enabled = true))))
        assertFalse(SaveProvider.settle(off).sync.saves)
    }
}
