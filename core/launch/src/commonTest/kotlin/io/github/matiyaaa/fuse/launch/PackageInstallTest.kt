package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.desktop.WindowsCatalog
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageInstallTest {
    private val registry = AdapterRegistry.Default

    @Test
    fun rpcs3InstallsPackagesFromItsCommandLine() {
        val rpcs3 = registry[EmulatorId("linux.rpcs3")]!!
        val flatpak = InstalledEmulator(rpcs3.id, "RPCS3", Host.LINUX, "net.rpcs3.RPCS3", platforms = setOf(PlatformId("ps3")), detectedVia = "Flatpak")
        val plan = rpcs3.packageInstall(flatpak, "/games/ps3/Game Update.pkg")!!
        assertEquals(listOf("flatpak", "run", "net.rpcs3.RPCS3", "--headless", "--installpkg", "/games/ps3/Game Update.pkg"), plan.argv)
        assertNull(rpcs3.packageInstall(flatpak, "/games/ps3/Game.iso"))
        assertEquals(setOf("pkg", "rap", "edat"), rpcs3.packageExtensions)
    }

    @Test
    fun vita3kNeedsTheKeyAndGetsItOnlyInItsArguments() {
        val vita = registry[EmulatorId("linux.vita3k")]!!
        val here = InstalledEmulator(vita.id, "Vita3K", Host.LINUX, "/usr/bin/Vita3K", platforms = setOf(PlatformId("psvita")), detectedVia = "PATH")
        assertTrue(vita.packageNeedsKey)
        assertNull(vita.packageInstall(here, "/v/Game.pkg"), "without its zRIF it can't install")
        val plan = vita.packageInstall(here, "/v/Game.pkg", " KO5ifR1dQ+eHBl ")!!
        assertEquals(listOf("/usr/bin/Vita3K", "--pkg", "/v/Game.pkg", "--zrif", "KO5ifR1dQ+eHBl"), plan.argv)
    }

    @Test
    fun windowsVita3kInstallsFromItsOwnFolder() {
        val vita = WindowsCatalog.adapters.first { it.id == EmulatorId("windows.vita3k") }
        val here = InstalledEmulator(vita.id, "Vita3K", Host.WINDOWS, "C:/Emu/Vita3K/Vita3K.exe", platforms = setOf(PlatformId("psvita")), detectedVia = "Folder")
        assertEquals("C:/Emu/Vita3K", vita.packageInstall(here, "D:/v/Game.pkg", "KEY")!!.workingDir)
    }
}

/** A package game starts what was installed from it, by its title id. */
class InstallOnlyLaunchTest {
    @Test
    fun packagesStartByTitleId() {
        val rpcs3 = AdapterRegistry.Default[EmulatorId("linux.rpcs3")]!!
        val installed = InstalledEmulator(rpcs3.id, "RPCS3", Host.LINUX, "/usr/bin/rpcs3", platforms = setOf(PlatformId("ps3")), detectedVia = "PATH")
        val game = io.github.matiyaaa.fuse.model.Game(
            io.github.matiyaaa.fuse.model.GameId(1), PlatformId("ps3"), io.github.matiyaaa.fuse.model.GameTitles("Demon's Souls"),
            io.github.matiyaaa.fuse.model.GameLocation(io.github.matiyaaa.fuse.model.LibrarySourceId(1), "/g/ds.pkg", io.github.matiyaaa.fuse.model.LocationKind.FILE, "/g/ds.pkg"),
            tags = io.github.matiyaaa.fuse.model.FilenameTags(serial = "BLUS30443"),
        )
        val launch = LaunchResolver(AdapterRegistry.Default).resolve(game, null, listOf(installed), Host.LINUX)
        assertEquals(LaunchTarget.TitleId("BLUS30443"), launch.target)
        assertTrue(InstallOnlyFiles.matches(PlatformId("psvita"), "/v/Game.VPK"))
        assertTrue(!InstallOnlyFiles.matches(PlatformId("gba"), "/g/game.zip"))
    }
}
