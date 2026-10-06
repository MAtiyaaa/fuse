package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.ui.shell.platform.StayAwake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Leaving Fuse on its standby screen never stops what it is doing: while art is found or anything
 * downloads or uploads, the computer is kept from sleeping (and the screen on under standby), with
 * a helper that goes away with Fuse, and nothing is held once the work is done.
 */
class DesktopStayAwakeTest {
    private val tools = mapOf("systemd-inhibit" to "/usr/bin/systemd-inhibit", "sh" to "/bin/sh", "caffeinate" to "/usr/bin/caffeinate", "powershell" to "C:/Windows/powershell.exe")

    @Test
    fun theWorkDecidesWhatIsKeptAwake() {
        assertEquals(StayAwake.NONE, StayAwake.plan(transfers = 0, finding = false, standby = true))
        assertEquals(StayAwake(cpu = true, screen = false), StayAwake.plan(transfers = 2, finding = false, standby = false))
        // Under the standby screen the screen stays on too: it is made to be left on.
        assertEquals(StayAwake(cpu = true, screen = true), StayAwake.plan(transfers = 0, finding = true, standby = true))
    }

    @Test
    fun eachSystemIsAskedItsOwnWayAndTheLockGoesWithFuse() {
        val linux = DesktopStayAwake.command(DesktopOs.LINUX, StayAwake(cpu = true, screen = true), 4242, tools::get)!!
        assertEquals("/usr/bin/systemd-inhibit", linux.first())
        assertTrue("--what=sleep:idle" in linux && "--mode=block" in linux)
        assertTrue(linux.last().contains("kill -0 4242"))
        assertTrue("--what=sleep" in DesktopStayAwake.command(DesktopOs.LINUX, StayAwake(cpu = true, screen = false), 4242, tools::get)!!)

        assertEquals(listOf("/usr/bin/caffeinate", "-i", "-w", "4242"), DesktopStayAwake.command(DesktopOs.MACOS, StayAwake(cpu = true, screen = false), 4242, tools::get))
        assertEquals("-di", DesktopStayAwake.command(DesktopOs.MACOS, StayAwake(cpu = true, screen = true), 4242, tools::get)!![1])

        val windows = DesktopStayAwake.command(DesktopOs.WINDOWS, StayAwake(cpu = true, screen = true), 4242, tools::get)!!.last()
        assertTrue("SetThreadExecutionState(0x80000003)" in windows && "-Id 4242" in windows)

        // Nothing to hold, or no way to hold it: no helper.
        assertNull(DesktopStayAwake.command(DesktopOs.LINUX, StayAwake.NONE, 4242, tools::get))
        assertNull(DesktopStayAwake.command(DesktopOs.LINUX, StayAwake(cpu = true, screen = false), 4242) { null })
    }

    @Test
    fun oneHelperWhileWorkingAndNoneOnceDone() {
        val started = ArrayList<FakeProcess>()
        val awake = DesktopStayAwake(DesktopOs.LINUX, tools::get) { FakeProcess().also(started::add) }
        awake.apply(StayAwake(cpu = true, screen = false))
        awake.apply(StayAwake(cpu = true, screen = false))
        assertEquals(1, started.size)
        // Standby comes up: the lock is taken again with the screen.
        awake.apply(StayAwake(cpu = true, screen = true))
        assertEquals(2, started.size)
        assertFalse(started[0].isAlive)
        awake.apply(StayAwake.NONE)
        assertTrue(started.none { it.isAlive })
    }

    private class FakeProcess : Process() {
        private var alive = true
        override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
        override fun getInputStream() = java.io.InputStream.nullInputStream()
        override fun getErrorStream() = java.io.InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() { alive = false }
        override fun isAlive() = alive
    }
}
