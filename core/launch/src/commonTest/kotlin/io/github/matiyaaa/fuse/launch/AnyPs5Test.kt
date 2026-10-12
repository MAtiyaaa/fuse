package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LocationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnyPs5Test {
    private val resolver = LaunchResolver()

    private fun port(program: String) = game(
        "ps5", program.substringBeforeLast('/'), kind = LocationKind.FOLDER, launchPath = program,
        interpretation = FolderInterpretation.FOLDER_IS_GAME, serial = "PPSA02929", title = "Dreaming Sarah",
    )

    @Test
    fun aPortStartsAsItsOwnProgramFromItsFolder() {
        val cases = listOf(
            Triple(Host.LINUX, "/games/ps5/Dreaming Sarah/app.elf", "linux.anyps5"),
            Triple(Host.WINDOWS, "D:/Games/PS5/Dreaming Sarah/app.exe", "windows.anyps5"),
            Triple(Host.MACOS, "/Users/mo/PS5/Dreaming Sarah/eboot", "macos.anyps5"),
        )
        for ((host, program, id) in cases) {
            // Nothing to install: AnyPS5 is built in, and SharpEmu being there doesn't take a port.
            val sharp = linuxEmu("${id.substringBefore('.')}.sharpemu", "/opt/SharpEmu/SharpEmu").copy(host = host)
            val r = resolver.resolve(port(program), null, listOf(sharp), host)
            assertEquals(EmulatorId(id), r.adapter?.id, "$host")
            val plan = assertIs<LaunchPlan.Command>(r.plan, "$host")
            assertEquals(listOf(program), plan.argv)
            // Its paths and its saves (_sd) are relative to the folder it starts in.
            assertEquals(program.substringBeforeLast('/'), plan.workingDir)
        }
    }

    @Test
    fun aDumpStillGoesToAnEmulatorAndAloneSaysWhatAnyPs5Needs() {
        val dump = game("ps5", "/games/ps5/Astro Bot/eboot.bin", serial = "PPSA01234")
        val sharp = linuxEmu("linux.sharpemu", "/opt/SharpEmu/SharpEmu")
        assertEquals(EmulatorId("linux.sharpemu"), resolver.resolve(dump, null, listOf(sharp), Host.LINUX).adapter?.id)
        // Even with AnyPS5 chosen for the system, eboot.bin goes on to SharpEmu.
        assertEquals(EmulatorId("linux.sharpemu"), resolver.resolve(dump, EmulatorId("linux.anyps5"), listOf(sharp), Host.LINUX).adapter?.id)
        // With nothing else here, the answer says how AnyPS5 plays it.
        val alone = assertIs<LaunchPlan.Unsupported>(resolver.resolve(dump, null, emptyList(), Host.LINUX).plan)
        assertTrue("relinker" in alone.reason, alone.reason)
        // A Windows port isn't started on Linux.
        val windowsPort = port("/games/ps5/Dreaming Sarah/app.exe")
        assertIs<LaunchPlan.Unsupported>(resolver.resolve(windowsPort, null, emptyList(), Host.LINUX).plan)
    }

    @Test
    fun anyPs5IsFirstForThePs5OnEveryComputer() {
        for ((host, id) in listOf(Host.LINUX to "linux.anyps5", Host.WINDOWS to "windows.anyps5", Host.MACOS to "macos.anyps5")) {
            assertEquals(EmulatorId(id), AdapterRegistry.Default.forPlatform(io.github.matiyaaa.fuse.model.PlatformId("ps5"), host).first().id, "$host")
        }
        assertTrue(AdapterRegistry.Default.forHost(Host.ANDROID).none { it.id.value.endsWith("anyps5") })
    }
}
