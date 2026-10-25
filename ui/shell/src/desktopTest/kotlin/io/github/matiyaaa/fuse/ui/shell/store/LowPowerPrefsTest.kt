package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.shell.settings.next
import io.github.matiyaaa.fuse.ui.shell.store.impl.GlobalScoped
import io.github.matiyaaa.fuse.ui.shell.store.impl.toUiPrefs
import io.github.matiyaaa.fuse.ui.shell.store.impl.withUiPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Low Power is one switch: turning it off leaves nothing of it behind. */
class LowPowerPrefsTest {
    @Test
    fun anOlderLowPowerProfileIsLowPowerModeAndTurningItOffEndsIt() {
        // An older Fuse saved Low power as a profile, apart from the Low Power Mode switch.
        val old = AppSettings().let { it.copy(performance = it.performance.copy(profile = PerformanceProfile.LOW_POWER, lowPowerMode = false)) }
        val prefs = old.toUiPrefs(GlobalScoped())
        assertTrue(prefs.lowPower)
        assertEquals(PerformanceProfile.AUTOMATIC, prefs.performance)

        val off = old.withUiPrefs(prefs.copy(lowPower = false)).toUiPrefs(GlobalScoped())
        assertFalse(off.lowPower)
        assertEquals(PerformanceProfile.AUTOMATIC, off.performance)
        assertTrue(RenderQuality.of(off.performance, null, off.lowPower).backgroundVideo, "everything Low Power turned off is back")
    }

    @Test
    fun aPerformanceTileNeverLandsOnLowPower() {
        var p = PerformanceProfile.AUTOMATIC
        val seen = buildList { repeat(6) { p = p.next(); add(p) } }
        assertFalse(PerformanceProfile.LOW_POWER in seen)
        assertEquals(PerformanceProfile.choosable.toSet(), seen.toSet())
        assertEquals(PerformanceProfile.BALANCED, PerformanceProfile.LOW_POWER.next())
    }
}
