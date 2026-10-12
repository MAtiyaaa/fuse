package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.launch.LaunchDiagnostic
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** An emulator that stops as it starts: the likely reason, from what it wrote, and the fix that goes with it. */
class LaunchDiagnosticStoreTest {
    private val root: File = Files.createTempDirectory("fuse-diag").toFile()
    private val cache: File = Files.createTempDirectory("fuse-diag-cache").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun aGraphicsFailureInAnEmulatorFuseSetsUpOffersItsRenderer(): Unit = runBlocking {
        File(root, "psx").mkdirs()
        File(root, "psx/Racer.cue").writeText("FILE \"Racer.bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n")
        File(root, "psx/Racer.bin").writeBytes(ByteArray(2352 * 20))
        File(root, "gba").mkdirs()
        File(root, "gba/Pilgrims.gba").writeBytes(ByteArray(64))
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val games = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }

        services.runResult = RunResult.Failed(
            "DuckStation stopped right away (exit code 1).",
            LaunchDiagnostic(listOf("/usr/bin/duckstation-qt", "${root.absolutePath}/psx/Racer.cue"), 1, "Vulkan: vkCreateInstance failed: VK_ERROR_INCOMPATIBLE_DRIVER\n"),
        )
        val ps1 = assertIs<LaunchOutcome.Problem>(store.library.launch(games.first { it.platformId.value == "psx" }.id)).problem
        assertTrue("renderer" in ps1.message, ps1.message)
        assertIs<ProblemAction.EmulatorSettings>(ps1.actions.first())
        assertTrue("Exit code: 1" in ps1.details.orEmpty() && "VK_ERROR_INCOMPATIBLE_DRIVER" in ps1.details.orEmpty())

        // An emulator Fuse doesn't set up: the reason still, and no fix it can't offer.
        services.runResult = RunResult.Failed("mGBA stopped right away (exit code 1).", LaunchDiagnostic(listOf("/usr/bin/mgba-qt"), 1, "Failed to create OpenGL context\n"))
        val gba = assertIs<LaunchOutcome.Problem>(store.library.launch(games.first { it.platformId.value == "gba" }.id)).problem
        assertTrue("graphics" in gba.message)
        assertTrue(gba.actions.none { it is ProblemAction.EmulatorSettings })

        // Nothing it wrote says why: the message stays what it was.
        services.runResult = RunResult.Failed("mGBA stopped right away (exit code 1).", LaunchDiagnostic(listOf("/usr/bin/mgba-qt"), 1, "bye\n"))
        val plain = assertIs<LaunchOutcome.Problem>(store.library.launch(games.first { it.platformId.value == "gba" }.id)).problem
        assertEquals("mGBA stopped right away (exit code 1).", plain.message)
    }

    @Test
    fun aRequirementTheComputerLacksIsSaidWhereWhatItWroteGivesNoReason() {
        val shad = io.github.matiyaaa.fuse.model.InstalledEmulator(
            io.github.matiyaaa.fuse.model.EmulatorId("linux.shadps4"), "shadPS4", io.github.matiyaaa.fuse.model.Host.LINUX,
            "/home/mo/Apps/shadPS4.AppImage", null, setOf(io.github.matiyaaa.fuse.model.PlatformId("ps4")), "AppImage",
        )
        val game = io.github.matiyaaa.fuse.model.GameId(1L)
        val lacks = "It needs a processor with x86-64-v3 (Intel Haswell, AMD Excavator or newer), and this one doesn't have it"
        val crash = io.github.matiyaaa.fuse.ui.shell.store.impl.LaunchProblems.refused(
            shad, game, "shadPS4 stopped right away.", android = false, diagnostic = LaunchDiagnostic(listOf(shad.appId), 132, "Illegal instruction\n"), requirement = lacks,
        )
        assertEquals("shadPS4 may not run on this computer. $lacks.", crash.message)
        assertTrue("Requirement not met: $lacks." in crash.details.orEmpty() && "Exit code: 132" in crash.details.orEmpty())
        // A missing BIOS is its own reason, and stays what is said.
        val bios = io.github.matiyaaa.fuse.ui.shell.store.impl.LaunchProblems.refused(
            shad, game, "shadPS4 stopped right away.", android = false, diagnostic = LaunchDiagnostic(listOf(shad.appId), 1, "BIOS not found\n"), requirement = lacks,
        )
        assertTrue("firmware" in bios.message, bios.message)
        // Without a requirement, nothing changes.
        val plain = io.github.matiyaaa.fuse.ui.shell.store.impl.LaunchProblems.refused(shad, game, "shadPS4 stopped right away.", android = false)
        assertEquals("shadPS4 stopped right away.", plain.message)
    }
}
