package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween as composeTween
import androidx.compose.animation.fadeIn as composeFadeIn
import androidx.compose.animation.fadeOut as composeFadeOut
import androidx.compose.animation.slideInHorizontally as composeSlideIn
import androidx.compose.animation.slideOutHorizontally as composeSlideOut
import androidx.compose.animation.togetherWith as composeTogetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fuseline's transitions ([Appear], [Swap]) against Compose's ([AnimatedVisibility],
 * [AnimatedContent]) on the same test clock: 60 items appearing and leaving together, and a page of
 * 120 tiles swapping for another. The time of the whole run is the measure (composition, layout and
 * the animations' frames). Runs only with -Pfuse.bench=true, like [FuselineBenchmark].
 */
@OptIn(ExperimentalTestApi::class)
class TransitionBenchmark {
    private val enabled = System.getProperty("fuse.bench").orEmpty().toBoolean()

    @Composable
    private fun Tiles(page: Int) = FlowRow {
        repeat(120) { i -> Box(Modifier.size(24.dp)) { BasicText("${page * 1000 + i}") } }
    }

    private fun run(rounds: Int, body: @Composable (Boolean, Int) -> Unit): Long {
        var best = Long.MAX_VALUE
        repeat(rounds) {
            runComposeUiTest {
                var shown by mutableStateOf(true)
                var page by mutableStateOf(0)
                mainClock.autoAdvance = false
                setContent { body(shown, page) }
                mainClock.advanceTimeBy(100)
                val t0 = System.nanoTime()
                repeat(6) {
                    shown = !shown
                    page++
                    // Through the whole transition, frame by frame.
                    repeat(30) { mainClock.advanceTimeByFrame() }
                }
                best = minOf(best, System.nanoTime() - t0)
            }
        }
        return best
    }

    @Test
    fun transitionsAgainstCompose() {
        if (!enabled) return
        val ms = 300
        val fuseline: @Composable (Boolean, Int) -> Unit = { shown, page ->
            Column {
                repeat(60) { i ->
                    Appear(shown, enter = fadeIn(tween(ms)) + slideInHorizontally(tween(ms)) { it / 4 }, exit = fadeOut(tween(ms))) { BasicText("Item $i") }
                }
                Swap(page, transitionSpec = { (fadeIn(tween(ms)) + slideInHorizontally(tween(ms)) { it / 8 }) togetherWith fadeOut(tween(ms)) }) { Tiles(it) }
            }
        }
        val compose: @Composable (Boolean, Int) -> Unit = { shown, page ->
            Column {
                repeat(60) { i ->
                    AnimatedVisibility(shown, enter = composeFadeIn(composeTween(ms)) + composeSlideIn(composeTween(ms)) { it / 4 }, exit = composeFadeOut(composeTween(ms))) { BasicText("Item $i") }
                }
                AnimatedContent(page, transitionSpec = { (composeFadeIn(composeTween(ms)) + composeSlideIn(composeTween(ms)) { it / 8 }) composeTogetherWith composeFadeOut(composeTween(ms)) }) { Tiles(it) }
            }
        }
        // Warm both up, then measure each twice in turn; the best run of each counts.
        run(2, fuseline); run(2, compose)
        val c = minOf(run(3, compose), run(3, compose))
        val f = minOf(run(3, fuseline), run(3, fuseline))
        val ratio = f.toDouble() / c
        val line = String.format("| 60 appearing and leaving, and a page of 120 tiles swapping (6 times) | %.1f ms | %.1f ms | %.2fx |", f / 1e6, c / 1e6, ratio)
        println(line)
        File("build/fuseline-bench-transitions.txt").apply { parentFile.mkdirs() }.writeText(line + "\n")
        assertTrue(ratio <= 1.1, "Transitions: Fuseline took ${"%.2f".format(ratio)}x Compose's time")
    }
}
