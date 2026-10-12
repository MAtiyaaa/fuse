package io.github.matiyaaa.fuse.ui.shell.perf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import java.io.File
import kotlin.test.Test

/**
 * What a screen of Series cards costs to draw from generated art alone: twelve cards, each a wide
 * background and three covers, drawn over generated background art (as before) and over a plain
 * gradient in its colour (as now: the background sits under the dimming and the covers). Runs alternate
 * so the machine's drift falls on both. Part of `desktopPerf`, written to its folder.
 */
@OptIn(ExperimentalTestApi::class)
class CollageDrawBench {
    @Test
    fun seriesCardsDrawnOverGeneratedArtAndOverAPlainGradient() {
        val out = System.getProperty("fuse.perf.dir")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        if (System.getProperty("fuse.perf.only").orEmpty().split(',').none { it.isBlank() || "collage".startsWith(it.trim()) || it.trim().startsWith("collage") }) return
        var generatedBackground by mutableStateOf(true)
        val times = mapOf(true to ArrayList<Double>(), false to ArrayList<Double>())
        runDesktopComposeUiTest(1920, 1080) {
            setContent {
                FuseTheme {
                    Column(Modifier.fillMaxSize()) {
                        repeat(3) { row ->
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                repeat(4) { col ->
                                    val i = row * 4 + col
                                    Box(Modifier.weight(1f).fillMaxSize().padding(12.dp)) {
                                        if (generatedBackground) GeneratedArt("Series $i", Color(0xFF5B8DEF), slot = ArtSlot.WIDE, showText = false)
                                        else Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF2B3F6B), Color(0xFF0B0E14)))))
                                        Row(Modifier.fillMaxSize().padding(24.dp)) {
                                            repeat(3) { k ->
                                                Box(Modifier.weight(1f).fillMaxSize().padding(4.dp)) {
                                                    GeneratedArt("Series $i game $k", Color(0xFF8E6CEF), slot = ArtSlot.BOX, showText = k == 0, )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            repeat(ROUNDS) { round ->
                for (mode in listOf(round % 2 == 0, round % 2 != 0)) {
                    generatedBackground = mode
                    waitForIdle()
                    onRoot().captureToImage()
                    repeat(FRAMES) {
                        val t0 = System.nanoTime()
                        onRoot().captureToImage()
                        times.getValue(mode) += (System.nanoTime() - t0) / 1e6
                    }
                }
            }
        }
        fun line(name: String, t: List<Double>): String {
            val s = t.sorted()
            return "| $name | ${s.size} | %.1f | %.1f | %.1f |".format(s.average(), s[s.size / 2], s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)])
        }
        val report = buildString {
            appendLine("Twelve Series cards of generated art, 1920 by 1080, Skia software target; capture-inclusive ms per frame.")
            appendLine("| Drawing | Frames | Mean | p50 | p95 |")
            appendLine("|---|---|---|---|---|")
            appendLine(line("Generated background art (before)", times.getValue(true)))
            appendLine(line("A plain gradient behind the covers (after)", times.getValue(false)))
        }
        out.mkdirs()
        File(out, "collage.md").writeText(report)
        println(report)
    }

    private companion object {
        const val ROUNDS = 6
        const val FRAMES = 15
    }
}
