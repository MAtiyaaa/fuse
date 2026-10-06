package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.system.Processes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What Fuse's children get: started by Steam (Game Mode), Fuse's environment has Steam's runtime
 * libraries first and Steam's tools on PATH, which break the system's own programs (flatpak,
 * emulators' AppImages); they get back what the system had. Steam's game id stays.
 */
class HostEnvironmentTest {
    @Test
    fun steamsRuntimeIsTakenOffWhatChildrenGet() {
        val env = mutableMapOf(
            "LD_LIBRARY_PATH" to "/home/deck/.local/share/Steam/ubuntu12_32/steam-runtime/pinned_libs_64:/home/deck/.local/share/Steam/ubuntu12_32/steam-runtime/lib/x86_64-linux-gnu",
            "SYSTEM_LD_LIBRARY_PATH" to "",
            "PATH" to "/home/deck/.local/share/Steam/ubuntu12_32/steam-runtime/amd64/usr/bin:/usr/bin",
            "SYSTEM_PATH" to "/usr/local/bin:/usr/bin",
            "STEAM_RUNTIME_LIBRARY_PATH" to "/x",
            "SteamGameId" to "123",
            "APPIMAGE" to "/home/deck/Applications/Fuse.AppImage",
            "APPDIR" to "/tmp/.mount_Fuse",
        )
        Processes.hostEnvironment(env)
        assertFalse("LD_LIBRARY_PATH" in env, "the system had none")
        assertEquals("/usr/local/bin:/usr/bin", env["PATH"])
        assertFalse("STEAM_RUNTIME_LIBRARY_PATH" in env)
        assertFalse("APPIMAGE" in env || "APPDIR" in env)
        assertEquals("123", env["SteamGameId"], "Game Mode still counts the program as Fuse's")
    }

    @Test
    fun withoutSteamsCopyOnlyItsRuntimeFoldersGo() {
        val env = mutableMapOf("LD_LIBRARY_PATH" to "/opt/mine/lib:/home/deck/.steam/root/ubuntu12_32/steam-runtime/lib", "PATH" to "/usr/bin")
        Processes.hostEnvironment(env)
        assertEquals("/opt/mine/lib", env["LD_LIBRARY_PATH"])
        assertEquals("/usr/bin", env["PATH"])
    }

    @Test
    fun anOrdinaryDesktopIsLeftAsItIs() {
        val env = mutableMapOf("LD_LIBRARY_PATH" to "/opt/mine/lib", "PATH" to "/usr/bin:/bin")
        Processes.hostEnvironment(env)
        assertEquals(mapOf("LD_LIBRARY_PATH" to "/opt/mine/lib", "PATH" to "/usr/bin:/bin"), env)
    }
}
