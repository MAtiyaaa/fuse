package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.DesktopFuseServices
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * Fast Start as Steam runs it: a new process, `--run <id>`, and the time from that process starting
 * to the emulator's own process starting. A stand-in for mGBA on `PATH` notes when it starts and
 * exits. Linux only (where Steam's Game Mode is).
 *
 * Built as tests build it (Fuse's classes in folders, so no class data sharing archive), it takes
 * about 1.3 to 1.9 seconds on a Linux build container, and the budget here only catches it getting
 * much slower. A release build is timed with `-Pfuse.faststart.launcher=<its bin/fuse>`: with the
 * archive `scripts/train-cds.sh` writes, the same container measured 0.74 to 0.87 seconds from the
 * process starting to the emulator, Fuse's own part of it about 0.4 seconds.
 */
class FastStartTimingTest {
    @Test
    fun steamsPlayReachesTheEmulatorQuickly() {
        assumeTrue(DesktopOs.current == DesktopOs.LINUX)
        val home = Files.createTempDirectory("fuse-faststart").toFile()
        try {
            val bin = File(home, "bin").apply { mkdirs() }
            val started = File(home, "emulator-started")
            File(bin, "mgba").apply {
                writeText("#!/bin/sh\ndate +%s%3N > '${started.absolutePath}'\nexit 0\n")
                setExecutable(true)
            }
            File(home, "Games/gba").mkdirs()
            File(home, "Games/gba/Golden Sun (USA).gba").writeBytes(ByteArray(2048) { it.toByte() })
            val env = mapOf("HOME" to home.absolutePath, "PATH" to "${bin.absolutePath}:/usr/bin:/bin")
            val id = seed(dirs(env), File(home, "Games"))

            // The packaged Fuse when given (its own runtime and options), else Fuse's classes as built.
            val packaged = System.getProperty("fuse.faststart.launcher")?.takeIf { it.isNotBlank() }
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val command = if (packaged != null) {
                listOf(packaged, "--run", id.toString())
            } else {
                listOf(java, "-cp", System.getProperty("java.class.path"), "io.github.matiyaaa.fuse.desktop.MainKt", "--run", id.toString())
            }
            val runs = (1..RUNS).map {
                started.delete()
                val launched = System.currentTimeMillis()
                val p = ProcessBuilder(command).apply {
                    environment().clear()
                    environment().putAll(env)
                    redirectErrorStream(true)
                    redirectOutput(File(home, "run.log"))
                }.start()
                assertTrue(p.waitFor(60, TimeUnit.SECONDS), "Fast Start didn't end")
                assertEquals(0, p.exitValue(), File(home, "run.log").readText())
                started.readText().trim().toLong() - launched
            }
            // Fuse's own marks for the last run: when its code ran, services and store were ready, the game started.
            File(dirs(env).data, "logs").listFiles()?.flatMap { it.readLines() }?.filter { "Fast Start" in it }?.takeLast(4)?.forEach(::println)
            val best = runs.min()
            println("Fast Start, process start to the emulator: ${runs.joinToString()} ms (best $best ms)")
            // Generous on a shared CI runner: the Java runtime's own start is in it too.
            assertTrue(best < BUDGET_MS, "Fast Start took $best ms to reach the emulator (runs: $runs)")
        } finally {
            home.deleteRecursively()
        }
    }

    /** Fuse as it runs normally adds the folder and scans it once; the game's id comes back. */
    private fun seed(dirs: FuseDirs, games: File): Long = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val services = DesktopFuseServices.create(dirs, scope)
        try {
            val store = createFuseStore(services, scope)
            // Setup done, so the game plays at once.
            store.updatePrefs { it.copy(onboardingDone = true) }
            store.sources.add(games.absolutePath, LibrarySourceKind.ROMS_ROOT)
            withTimeout(60_000) {
                store.sources.scan.first { it.phase == ScanPhase.DONE }
                store.library.games(GameQuery(platform = PlatformId("gba"))).first { it.isNotEmpty() }.single().id.value
            }
        } finally {
            scope.cancel()
            services.close()
        }
    }

    private fun dirs(env: Map<String, String>) = FuseDirs.linux(env)

    private companion object {
        const val BUDGET_MS = 4_000L
        const val RUNS = 4
    }
}
