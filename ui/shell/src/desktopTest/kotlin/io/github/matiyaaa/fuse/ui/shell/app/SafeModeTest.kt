package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeModeTest {
    private class Disk(var count: Int = 0, var broken: Boolean = false) {
        fun guard() = StartupGuard(load = { if (broken) error("unreadable") else count }, save = { if (broken) error("read only") else count = it })
    }

    @Test
    fun threeStartsThatNeverSettledMakeTheFourthSafe() {
        val disk = Disk()
        repeat(3) { assertFalse(disk.guard().begin(), "start ${it + 1} is normal") }
        val fourth = disk.guard()
        assertTrue(fourth.begin())
        assertEquals(3, fourth.failedBefore)
    }

    @Test
    fun aSettledStartClearsTheCount() {
        val disk = Disk()
        repeat(2) { disk.guard().begin() }
        disk.guard().also { it.begin() }.settle()
        assertEquals(0, disk.count)
        assertFalse(disk.guard().begin())
    }

    @Test
    fun aProcessCountsOnceHoweverOftenItsInterfaceStarts() {
        val disk = Disk()
        val guard = disk.guard()
        repeat(5) { guard.begin() }
        assertEquals(1, disk.count)
    }

    @Test
    fun anUnreadableCountNeverStopsAStart() {
        val disk = Disk(broken = true)
        assertFalse(disk.guard().begin())
        disk.guard().settle()
    }

    @Test
    fun safeModeLooksPlainWithoutChangingWhatIsSaved() {
        val mine = UiPrefs(themeId = "crt", motion = MotionProfile.ENHANCED, videoPreview = true)
        val safe = mine.inSafeMode()
        assertEquals("fuse", safe.themeId)
        assertEquals(MotionProfile.REDUCED, safe.motion)
        assertFalse(safe.videoPreview)
        assertFalse(safe.music.enabled)
        assertTrue(safe.lowPower)
        assertFalse(safe.crt.enabled)
        // The original is untouched: leaving safe mode puts it back.
        assertEquals("crt", mine.themeId)
        assertEquals(safe.home, mine.home)
    }
}
