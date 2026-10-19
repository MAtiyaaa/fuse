package io.github.matiyaaa.fuse.launch.ports

import io.github.matiyaaa.fuse.launch.LaunchResolver
import io.github.matiyaaa.fuse.launch.ScopedLaunchChoice
import io.github.matiyaaa.fuse.launch.game
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.Port
import io.github.matiyaaa.fuse.model.PortBuild
import io.github.matiyaaa.fuse.model.PortData
import io.github.matiyaaa.fuse.model.PortFileKind
import io.github.matiyaaa.fuse.model.PortOverlay
import io.github.matiyaaa.fuse.model.PortRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Made-up ports in the shapes the real ones take: a folder handed over by its path, an overlay, a Windows build through umu. */
class PortPlanTest {
    private fun build(os: String, runner: PortRunner = PortRunner.NATIVE, run: String = "skyport") =
        PortBuild(os = os, url = "https://example.org/$os.zip", size = 10, sha256 = "0".repeat(64), kind = PortFileKind.ZIP, run = run, runner = runner)

    private val byPath = Port(
        id = "skyport", name = "SkyPort", game = "Sky Racer", about = "", homepage = "https://example.org", version = "2.1",
        builds = listOf(build("linux"), build("windows", run = "skyport.exe")),
        data = listOf(PortData("game", "Sky Racer's files", "", needs = listOf(listOf("SKY.DAT", "sky.dat", "demo.dat")), exact = true)),
        args = listOf("--data-dir", "{data:game}", "--save-dir", "{home}/saves", "--log={install}/log.txt"),
        saves = "saves", source = "",
    )

    private val windowsOnly = byPath.copy(id = "winport", builds = listOf(build("windows", PortRunner.UMU, "bin/winport.exe")))

    @Test
    fun theSystemsOwnBuildIsChosenAndAWindowsOneOnlyThroughUmu() {
        assertEquals("linux", PortPlan.build(byPath, "linux", "x86_64", umu = false)?.os)
        assertEquals("windows", PortPlan.build(byPath, "windows", "x86_64", umu = false)?.os)
        assertNull(PortPlan.build(byPath, "macos", "x86_64", umu = true), "no macOS build, and umu is Linux's")
        assertNull(PortPlan.build(byPath, "linux", "arm64", umu = false), "no build for this processor")
        assertNull(PortPlan.build(windowsOnly, "linux", "x86_64", umu = false))
        assertEquals(PortRunner.UMU, PortPlan.build(windowsOnly, "linux", "x86_64", umu = true)?.runner)
        assertTrue("umu-launcher" in PortPlan.noBuild(windowsOnly, "linux"))
        assertEquals("SkyPort isn't published for this computer.", PortPlan.noBuild(byPath, "macos"))
    }

    @Test
    fun whatTheChosenFolderLacksIsSaidByName() {
        val data = byPath.data.single()
        assertEquals(listOf(listOf("SKY.DAT", "sky.dat", "demo.dat")), PortPlan.missing(data) { false })
        assertEquals(emptyList(), PortPlan.missing(data) { it == "demo.dat" }, "any one of the names will do")
        assertEquals("SKY.DAT or sky.dat or demo.dat", PortPlan.describe(PortPlan.missing(data) { false }))
    }

    @Test
    fun aNativeBuildStartsFromItsOwnFolderWithEveryPlaceholderFilled() {
        val layout = PortLayout("/home/ana/.local/share/fuse/ports/skyport", "skyport")
        val launcher = PortPlan.launcher(byPath, byPath.builds[0], layout, mapOf("game" to "/home/ana/Games/Sky Racer"), umuRun = null)!!
        assertEquals(
            listOf(
                "/home/ana/.local/share/fuse/ports/skyport/build/skyport",
                "--data-dir", "/home/ana/Games/Sky Racer",
                "--save-dir", "/home/ana/.local/share/fuse/ports/skyport/home/saves",
                "--log=/home/ana/.local/share/fuse/ports/skyport/build/log.txt",
            ),
            launcher.argv,
        )
        assertEquals(layout.install, launcher.cwd)
        assertEquals(emptyMap(), launcher.env)
        assertEquals("/home/ana/.local/share/fuse/ports/skyport/home/saves", launcher.saves)
        assertEquals("/home/ana/.local/share/fuse/ports/skyport/skyport.fuseport", layout.launcher)
        assertNull(PortPlan.launcher(byPath, byPath.builds[0], layout, emptyMap(), umuRun = null), "the game's folder isn't chosen yet")
    }

    @Test
    fun aWindowsBuildOnLinuxGoesThroughUmuWithItsOwnPrefixAndWindowsPaths() {
        val layout = PortLayout("/home/ana/ports/winport", "winport")
        val build = windowsOnly.builds.single()
        assertNull(PortPlan.launcher(windowsOnly, build, layout, mapOf("game" to "/g"), umuRun = null), "nothing without umu-run")
        val launcher = PortPlan.launcher(windowsOnly, build, layout, mapOf("game" to "/home/ana/Games/Sky Racer"), umuRun = "/usr/bin/umu-run")!!
        assertEquals("/usr/bin/umu-run", launcher.argv[0])
        assertEquals("/home/ana/ports/winport/build/bin/winport.exe", launcher.argv[1], "umu-run is given the Linux path of the program")
        assertEquals(listOf("--data-dir", "Z:\\home\\ana\\Games\\Sky Racer", "--save-dir", "Z:\\home\\ana\\ports\\winport\\home\\saves"), launcher.argv.subList(2, 6))
        assertEquals(mapOf("WINEPREFIX" to "/home/ana/ports/winport/home/prefix", "GAMEID" to "umu-default"), launcher.env)

        // At its start: the Proton the person uses, for umu-launcher's PROTONPATH; nothing else changes.
        val plan = io.github.matiyaaa.fuse.model.LaunchPlan.Command(io.github.matiyaaa.fuse.model.EmulatorId("port"), launcher.argv, launcher.cwd, launcher.env, io.github.matiyaaa.fuse.model.LaunchTarget.File("/g/winport.fuseport"))
        val proton = "/home/ana/.local/share/Steam/compatibilitytools.d/GE-Proton9-20"
        assertEquals(launcher.env + ("PROTONPATH" to proton), (PortPlan.withProton(plan, proton) as io.github.matiyaaa.fuse.model.LaunchPlan.Command).env)
        assertEquals(plan, PortPlan.withProton(plan, null), "none found: umu-launcher's own")
        val native = plan.copy(env = emptyMap())
        assertEquals(native, PortPlan.withProton(native, proton), "only a build through umu-launcher")
    }

    @Test
    fun theLauncherFileReadsBackAndADamagedOneIsRefused() {
        val launcher = PortLauncher("skyport", "2.1", listOf("/p/skyport", "-x"), "/p", mapOf("A" to "1"), "/p/saves")
        assertEquals(launcher, PortLauncher.decode(launcher.encode()))
        assertNull(PortLauncher.decode("{\"port\":\"x\",\"version\":\"1\",\"argv\":[],\"cwd\":\"/\"}"), "nothing to run")
        assertNull(PortLauncher.decode("not json"))
    }

    @Test
    fun anOverlayLinksTheGamesFilesAndKeepsWhatThePortWritesItsOwn() {
        // fallout2-ce's own paths: its settings file, and the folders its engine writes under data.
        val own = listOf("fallout2.cfg", "data/SAVEGAME/", "data/MAPS/", "data/proto/")
        assertEquals(OverlayStep.LINK, PortPlan.step("master.dat", isFolder = false, own))
        assertEquals(OverlayStep.LINK, PortPlan.step("sound", isFolder = true, own))
        assertEquals(OverlayStep.SKIP, PortPlan.step("FALLOUT2.CFG", isFolder = false, own), "the person's own settings are never linked")
        assertEquals(OverlayStep.DESCEND, PortPlan.step("DATA", isFolder = true, own), "a folder holding own folders is Fuse's, its other entries linked")
        assertEquals(OverlayStep.OWN_FOLDER, PortPlan.step("data/savegame", isFolder = true, own))
        assertEquals(OverlayStep.LINK, PortPlan.step("data/text", isFolder = true, own))
        assertEquals(OverlayStep.SKIP, PortPlan.step("data/MAPS", isFolder = false, own), "a file where an own folder goes is left out")
        assertEquals(listOf("data/SAVEGAME", "data/MAPS", "data/proto"), PortPlan.ownFolders(own))
        assertEquals(OverlayStep.LINK, PortPlan.step("data", isFolder = true, PortOverlay().own))
    }

    @Test
    fun playStartsExactlyWhatTheLauncherSays() {
        val layout = PortLayout("/home/ana/ports/skyport", "skyport")
        val launcher = PortPlan.launcher(byPath, byPath.builds[0], layout, mapOf("game" to "/home/ana/Games/Sky Racer"), umuRun = null)!!
        val g = game("ports", layout.launcher, title = "Sky Racer")
        val resolved = LaunchResolver().resolve(g, null, emptyList(), Host.LINUX, ScopedLaunchChoice(injectedText = launcher.encode()))
        assertEquals(EmulatorId("linux.port"), resolved.adapter?.id, "Fuse Ports is built in: nothing to detect")
        val plan = resolved.plan as LaunchPlan.Command
        assertEquals(launcher.argv, plan.argv)
        assertEquals(layout.install, plan.workingDir)
        val unread = LaunchResolver().resolve(g, null, emptyList(), Host.WINDOWS, ScopedLaunchChoice())
        assertTrue(unread.plan is LaunchPlan.Unsupported, "a launcher Fuse couldn't read is said, not guessed at")
    }
}
