package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** KytyPS5 on every computer, as its README starts it: `kyty_emulator --game <game folder or ZArchive dump>`. */
class KytyPs5Test {
    private val resolver = LaunchResolver()

    @Test
    fun aFolderDumpStartsByItsFolderAndAZArchiveByItsFile() {
        val cases = listOf(
            Triple(Host.LINUX, "/home/mo/Applications/KytyPS5/kyty_emulator", "/games/ps5/Astro Bot"),
            Triple(Host.WINDOWS, "C:/Emulators/KytyPS5/kyty_emulator.exe", "D:/Games/PS5/Astro Bot"),
            Triple(Host.MACOS, "/Applications/KytyPS5.app/Contents/MacOS/kyty_emulator", "/Users/mo/PS5/Astro Bot"),
        )
        for ((host, program, folder) in cases) {
            val id = "${if (host == Host.MACOS) "macos" else host.name.lowercase()}.kytyps5"
            val kyty = linuxEmu(id, program).copy(host = host)
            val dump = resolver.resolve(game("ps5", "$folder/eboot.bin", serial = "PPSA01234"), EmulatorId(id), listOf(kyty), host)
            assertEquals(EmulatorId(id), dump.adapter?.id, "$host")
            val plan = assertIs<LaunchPlan.Command>(dump.plan, "$host")
            assertEquals(listOf(program, "--game", folder), plan.argv, "$host: the dump's folder, eboot.bin's own")
            // Its settings and saves are beside the program.
            assertEquals(program.substringBeforeLast('/'), plan.workingDir, "$host")
            val zar = resolver.resolve(game("ps5", "$folder.zar", serial = "PPSA01234"), EmulatorId(id), listOf(kyty), host)
            assertEquals(listOf(program, "--game", "$folder.zar"), assertIs<LaunchPlan.Command>(zar.plan, "$host").argv, "$host: a ZArchive dump by its file")
        }
    }

    @Test
    fun itComesAfterAnyPs5AndSharpEmuForThePs5OnEveryComputer() {
        for (host in listOf(Host.LINUX, Host.WINDOWS, Host.MACOS)) {
            val ids = AdapterRegistry.Default.forPlatform(PlatformId("ps5"), host).map { it.id.value.substringAfter('.') }
            assertTrue("kytyps5" in ids, "$host: $ids")
            assertTrue(ids.indexOf("anyps5") < ids.indexOf("kytyps5") && ids.indexOf("sharpemu") < ids.indexOf("kytyps5"), "$host: $ids")
        }
        assertTrue(AdapterRegistry.Default.forHost(Host.ANDROID).none { it.id.value.endsWith("kytyps5") })
    }
}
