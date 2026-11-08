package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Lichii, the Eden fork that starts Switch 2 games, on every system Fuse runs on. */
class LichiiTest {
    private val resolver = LaunchResolver()

    @Test
    fun switch2IsAKnownSystem() {
        assertTrue(Platforms.isKnown(PlatformId("switch-2")))
    }

    @Test
    fun aSwitch2GameStartsInLichiiAsEdenStartsOne() {
        val cases = listOf(
            Triple(Host.LINUX, "/home/mo/Applications/Lichii/lichii", "/games/switch-2/Ocarina of Time.xci"),
            Triple(Host.WINDOWS, "C:/Emulators/Lichii/lichii.exe", "D:/Games/Switch 2/Ocarina of Time.xci"),
            Triple(Host.MACOS, "/Applications/lichii.app/Contents/MacOS/lichii", "/Users/mo/Switch 2/Ocarina of Time.xci"),
        )
        for ((host, program, rom) in cases) {
            val id = "${if (host == Host.MACOS) "macos" else host.name.lowercase()}.lichii"
            val lichii = linuxEmu(id, program).copy(host = host)
            val r = resolver.resolve(game("switch-2", rom), EmulatorId(id), listOf(lichii), host)
            assertEquals(EmulatorId(id), r.adapter?.id, "$host")
            assertEquals(listOf(program, "-f", "-g", rom), assertIs<LaunchPlan.Command>(r.plan, "$host").argv, "$host")
        }
    }

    @Test
    fun aNupStartsInLichiiOnEverySystem() {
        for ((host, program) in listOf(Host.LINUX to "/opt/lichii/lichii", Host.WINDOWS to "C:/Lichii/lichii.exe", Host.MACOS to "/Applications/lichii.app/Contents/MacOS/lichii")) {
            val id = "${if (host == Host.MACOS) "macos" else host.name.lowercase()}.lichii"
            val lichii = linuxEmu(id, program).copy(host = host)
            val r = resolver.resolve(game("switch-2", "/games/switch-2/Ocarina of Time.nup"), EmulatorId(id), listOf(lichii), host)
            assertEquals(listOf(program, "-f", "-g", "/games/switch-2/Ocarina of Time.nup"), assertIs<LaunchPlan.Command>(r.plan, "$host").argv, "$host")
        }
    }

    @Test
    fun itIsTheSwitch2EmulatorOnEverySystemAndPlaysSwitchGamesAfterTheOthers() {
        for (host in listOf(Host.LINUX, Host.WINDOWS, Host.MACOS, Host.ANDROID)) {
            val two = AdapterRegistry.Default.forPlatform(PlatformId("switch-2"), host).map { it.id.value.substringAfter('.') }
            assertEquals("lichii", two.firstOrNull(), "$host: $two")
            val one = AdapterRegistry.Default.forPlatform(PlatformId("switch"), host).map { it.id.value.substringAfter('.') }
            assertTrue("lichii" in one && one.first() != "lichii", "$host: $one")
        }
    }
}
