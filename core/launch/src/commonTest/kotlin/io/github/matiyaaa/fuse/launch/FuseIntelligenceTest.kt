package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.intelligence.Check
import io.github.matiyaaa.fuse.launch.intelligence.CheckState
import io.github.matiyaaa.fuse.launch.intelligence.FuseIntelligence
import io.github.matiyaaa.fuse.launch.intelligence.KnowledgeStore
import io.github.matiyaaa.fuse.launch.intelligence.ReadOnlyLook
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuse Intelligence: it answers from what Fuse knows first, does next to nothing for installs that
 * haven't changed, looks again only at what changed, never learns a rejected answer again, and
 * counts a launch as verified only for the install it ran in.
 */
class FuseIntelligenceTest {
    private class Disk : ReadOnlyLook {
        val dirs = HashSet<String>()
        val programs = HashMap<String, Long>()
        override val home = "/home/mo"
        override fun env(name: String): String? = null
        override fun exists(path: String) = path in dirs || path in programs
        override fun isDirectory(path: String) = path in dirs
        override fun canExecute(path: String) = path in programs
        override fun modified(path: String): Long? = programs[path] ?: if (path in dirs) 1L else null
        override fun names(path: String): List<String> = (dirs + programs.keys).filter { it.substringBeforeLast('/') == path }.map { it.substringAfterLast('/') }
    }

    private class Memory : KnowledgeStore {
        var text: String? = null
        var writes = 0
        override fun read() = text
        override fun write(text: String) { this.text = text; writes++ }
    }

    private var now = 1_000L
    private val disk = Disk()
    private val memory = Memory()
    private fun brain() = FuseIntelligence(memory, disk) { now }

    private fun emu(id: String, name: String, appId: String, via: String, version: String? = null) =
        InstalledEmulator(EmulatorId(id), name, Host.LINUX, appId, version, setOf(PlatformId("psp")), via)

    private val ppsspp = emu("linux.ppsspp", "PPSSPP", "/usr/bin/PPSSPPSDL", "PATH", "1.17")
    private val dolphin = emu("linux.dolphin", "Dolphin", "org.DolphinEmu.dolphin-emu", "Flatpak")
    private val unknown = emu("linux.newemu", "New Emu", "/home/mo/Apps/NewEmu.AppImage", "AppImage")

    @Test
    fun whatFuseKnowsComesFirstAndAnUnchangedInstallCostsAlmostNothing() {
        disk.programs["/usr/bin/PPSSPPSDL"] = 50
        disk.dirs += "/home/mo/.config/ppsspp"
        disk.dirs += "/home/mo/.local/share/flatpak/app/org.DolphinEmu.dolphin-emu"
        disk.dirs += "/home/mo/.local/share/flatpak/app/org.DolphinEmu.dolphin-emu/current/active/files"
        disk.dirs += "/home/mo/.var/app/org.DolphinEmu.dolphin-emu"
        val brain = brain()
        val first = brain.learn(listOf(ppsspp, dolphin))
        assertEquals(2, first.investigated)
        assertEquals("/home/mo/.config/ppsspp", brain.known.emulators["linux.ppsspp"]?.config?.value)
        assertEquals("Where Fuse knows it keeps its settings", brain.known.emulators["linux.ppsspp"]?.config?.how)
        assertEquals("/home/mo/.var/app/org.DolphinEmu.dolphin-emu", brain.known.emulators["linux.dolphin"]?.config?.value)

        // Nothing changed: both answers are reused, a handful of looks, nothing written.
        val writes = memory.writes
        val again = brain().learn(listOf(ppsspp, dolphin))
        assertEquals(2, again.reused)
        assertEquals(0, again.investigated)
        assertTrue(again.looks <= 8, "only confirming: ${again.looks} looks")
        assertEquals(writes, memory.writes)
    }

    @Test
    fun anUnknownEmulatorIsUnderstoodByWhereItsKindKeepsSettings() {
        disk.programs["/home/mo/Apps/NewEmu.AppImage"] = 10
        disk.dirs += "/home/mo/Apps/NewEmu.AppImage.config"
        val brain = brain()
        brain.learn(listOf(unknown))
        val learned = assertNotNull(brain.known.emulators["linux.newemu"]?.config)
        assertEquals("/home/mo/Apps/NewEmu.AppImage.config", learned.value)
    }

    @Test
    fun aChangedInstallIsLookedAtAgainAndOnlyThen() {
        disk.programs["/usr/bin/PPSSPPSDL"] = 50
        val brain = brain()
        brain.learn(listOf(ppsspp))
        assertTrue(brain.known.emulators["linux.ppsspp"]!!.configNotFound)
        // Its folder appears, but the install is the same: not looked for again.
        disk.dirs += "/home/mo/.config/ppsspp"
        assertEquals(1, brain.learn(listOf(ppsspp)).reused)
        assertNull(brain.known.emulators["linux.ppsspp"]?.config)
        // Updated: looked into, and found.
        now = 5_000
        val updated = ppsspp.copy(version = "1.18")
        assertEquals(1, brain.learn(listOf(updated)).investigated)
        assertEquals("/home/mo/.config/ppsspp", brain.known.emulators["linux.ppsspp"]?.config?.value)
        assertEquals(5_000, brain.known.emulators["linux.ppsspp"]?.since)
    }

    @Test
    fun aRejectedDiscoveryIsNeverMadeAgain() {
        disk.programs["/usr/bin/PPSSPPSDL"] = 50
        disk.dirs += "/home/mo/.config/ppsspp"
        val brain = brain()
        brain.learn(listOf(ppsspp))
        brain.reject(ppsspp.id)
        brain.learn(listOf(ppsspp))
        assertNull(brain.known.emulators["linux.ppsspp"]?.config)
        assertEquals(listOf("/home/mo/.config/ppsspp"), brain.known.rejected["linux.ppsspp"])
    }

    @Test
    fun aLaunchVerifiesOnlyTheInstallItRanIn() {
        disk.programs["/usr/bin/PPSSPPSDL"] = 50
        disk.dirs += "/home/mo/.config/ppsspp"
        now = 10_000
        val brain = brain()
        brain.learn(listOf(ppsspp))
        val none = brain.verify(ppsspp, null)
        assertEquals(CheckState.NOT_YET, none[Check.LAUNCH_VERIFIED]?.state)
        assertEquals(Check.CONFIGURED, none.furthest)
        assertEquals(CheckState.DONE, none[Check.EXECUTABLE]?.state)
        // A game that ran before this install appeared doesn't count.
        assertEquals(CheckState.NOT_YET, brain.verify(ppsspp, 9_000L to 3L)[Check.LAUNCH_VERIFIED]?.state)
        val ran = brain.verify(ppsspp, 12_000L to 2L)
        assertEquals(CheckState.DONE, ran[Check.LAUNCH_VERIFIED]?.state)
        assertEquals(Check.LAUNCH_VERIFIED, ran.furthest)
    }

    @Test
    fun aProgramThatCantRunIsNeverCalledReady() {
        disk.dirs += "/usr/bin/PPSSPPSDL" // there, but not runnable
        val v = brain().verify(ppsspp, null)
        assertEquals(CheckState.NOT_YET, v[Check.EXECUTABLE]?.state)
    }
}
