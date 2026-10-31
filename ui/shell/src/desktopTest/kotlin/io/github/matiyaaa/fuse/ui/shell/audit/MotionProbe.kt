package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.fuseline.MotionInspector
import java.io.File

/** How many values move at once while running along a page: the inspector's count, per press, at its peak. */
internal fun AuditDriver.motionProbe(tag: String) {
    scenario("probe", "motion $tag") {
        useLibrary()
        val report = StringBuilder()
        fun run(name: String, button: PadButton, presses: Int) {
            MotionInspector.enabled = true
            var peak = 0
            var peakLabels: Map<String, Int> = emptyMap()
            val perPress = ArrayList<Int>()
            repeat(presses) {
                router.press(button, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
                router.release(button, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
                var pressPeak = 0
                repeat(10) {
                    advanceExactly(16)
                    val n = MotionInspector.count
                    if (n > pressPeak) pressPeak = n
                    if (n > peak) {
                        peak = n
                        peakLabels = MotionInspector.snapshot().groupingBy { it.label }.eachCount()
                    }
                }
                perPress += pressPeak
            }
            settle(900)
            MotionInspector.enabled = false
            report.appendLine("$name: peak $peak, per press $perPress")
            peakLabels.entries.sortedByDescending { it.value }.take(14).forEach { (k, v) -> report.appendLine("    $v x $k") }
        }
        // A collection like a real one: a folder for every console and handheld Fuse knows.
        val big = File(cache, "probe-systems").apply { deleteRecursively(); mkdirs() }
        io.github.matiyaaa.fuse.library.PlatformCatalog.all
            .filter { it.kind == io.github.matiyaaa.fuse.model.PlatformKind.CONSOLE || it.kind == io.github.matiyaaa.fuse.model.PlatformKind.HANDHELD }
            .forEachIndexed { i, p ->
                val ext = p.extensions.firstOrNull { it != "zip" && it != "7z" } ?: return@forEachIndexed
                val dir = File(big, p.id.value).apply { mkdirs() }
                repeat(3) { n -> File(dir, "${p.shortName} Probe ${n + 1}.$ext").writeBytes(ByteArray(300 + i * 3 + n)) }
            }
        kotlinx.coroutines.runBlocking { libraryStore.sources.add(big.absolutePath, io.github.matiyaaa.fuse.model.LibrarySourceKind.ROMS_ROOT) }
        pumpUntil("many systems", 180_000) { libraryStore.library.platforms.value.count { it.gameCount > 0 } >= 40 }
        report.appendLine("systems with games: ${libraryStore.library.platforms.value.count { it.gameCount > 0 }}")
        tab(Destination.SYSTEMS)
        settle(3_000)
        run("systems open", PadButton.DPAD_RIGHT, 1)
        run("systems right", PadButton.DPAD_RIGHT, 8)
        run("systems down", PadButton.DPAD_DOWN, 4)
        run("systems up", PadButton.DPAD_UP, 4)
        tab(Destination.LIBRARY)
        settle(2_500)
        run("library right", PadButton.DPAD_RIGHT, 6)
        run("library down", PadButton.DPAD_DOWN, 6)
        home()
        settle(2_500)
        run("home right", PadButton.DPAD_RIGHT, 6)
        run("tabs", PadButton.R1, 5)
        File(Audit.dir, "motion-$tag.txt").writeText(report.toString())
        println(report)
    }
}
