package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import androidx.compose.animation.core.spring as cSpring
import androidx.compose.animation.fadeIn as cFadeIn
import androidx.compose.animation.fadeOut as cFadeOut
import androidx.compose.animation.slideInHorizontally as cSlideIn
import androidx.compose.animation.slideOutHorizontally as cSlideOut

/**
 * Whole interface paths, composition, layout and drawing included: tiles reflowing as their window
 * changes width (layout motion), an element shared between two screens whose destination keeps
 * moving, and rapid tab switching. Fuseline 3, Fuseline 2 (its frozen Swap) where it has the
 * capability, and Compose (animateBounds in a LookaheadScope, SharedTransitionLayout, AnimatedContent).
 * The whole run of frames is timed, and the bytes allocated counted, after a warm-up; rounds rotate
 * between engines and the median counts. Runs only with -Pfuse.bench=true.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
class UiMotionBenchmark {
    private val enabled = System.getProperty("fuse.bench").orEmpty().toBoolean()
    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private class Result(val nanos: Double, val bytes: Double)

    /** Sets [content] up, settles it, then times [frames] frames after [change], per frame. */
    private fun run(frames: Int, change: (Int) -> Unit, content: @Composable () -> Unit): Result {
        var result = Result(0.0, 0.0)
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent { content() }
            repeat(10) { mainClock.advanceTimeByFrame() }
            // Every thread's allocation: Compose may compose, lay out and draw off the test's thread.
            val before = bean.totalThreadAllocatedBytes
            val t0 = System.nanoTime()
            for (f in 0 until frames) {
                change(f)
                mainClock.advanceTimeByFrame()
            }
            val spent = System.nanoTime() - t0
            result = Result(spent.toDouble() / frames, (bean.totalThreadAllocatedBytes - before).toDouble() / frames)
        }
        return result
    }

    private class Row(val case: String, val f3: Result, val f2: Result?, val compose: Result?)

    private val rows = ArrayList<Row>()

    private fun compare(case: String, f3: () -> Result, f2: (() -> Result)?, compose: (() -> Result)?) {
        val engines = listOfNotNull(f3, f2, compose)
        repeat(2) { for (e in engines) e() }
        val results = HashMap<() -> Result, MutableList<Result>>()
        repeat(5) { r -> for (i in engines.indices) { val e = engines[(i + r) % engines.size]; results.getOrPut(e) { ArrayList() } += e() } }
        fun median(e: (() -> Result)?): Result? {
            val rs = results[e ?: return null] ?: return null
            return Result(rs.map { it.nanos }.sorted()[rs.size / 2], rs.map { it.bytes }.sorted()[rs.size / 2])
        }
        rows += Row(case, median(f3)!!, median(f2), median(compose))
    }

    @Test
    fun interfacePaths() {
        if (!enabled) return
        layoutMotion()
        sharedElement()
        tabSwitching()
        report()
    }

    private fun layoutMotion() {
        // Fifty tiles reflowing as the window narrows and widens, each moving to its new place.
        fun widthAt(f: Int) = if ((f / 20) % 2 == 0) 400 else 700
        compare(
            "50 tiles reflowing (layout motion)",
            {
                var w by mutableIntStateOf(700)
                run(80, { f -> w = widthAt(f) }) {
                    FlowRow(Modifier.width(w.dp)) { repeat(50) { Box(Modifier.motionBounds(Spring(1f, 400f)).size(60.dp)) } }
                }
            },
            null,
            {
                var w by mutableIntStateOf(700)
                run(80, { f -> w = widthAt(f) }) {
                    LookaheadScope {
                        FlowRow(Modifier.width(w.dp)) { repeat(50) { Box(Modifier.animateBounds(this@LookaheadScope).size(60.dp)) } }
                    }
                }
            },
        )
    }

    private fun sharedElement() {
        // A tile opening into a hero whose place keeps moving, then back, with the screens crossfading.
        fun stateAt(f: Int) = (f / 30) % 2
        compare(
            "shared element, moving destination",
            {
                var s by mutableIntStateOf(0)
                var drift by mutableIntStateOf(0)
                val shared = SharedMotion(Spring(1f, 400f))
                run(90, { f -> s = stateAt(f); drift = f * 3 }) {
                    Swap(s, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }) { which ->
                        if (which == 0) Box(Modifier.offset(20.dp, 20.dp).sharedMotion(shared, "g").size(60.dp))
                        else Box(Modifier.offset { IntOffset(300 + drift, 200) }.sharedMotion(shared, "g").size(300.dp))
                    }
                }
            },
            null,
            {
                var s by mutableIntStateOf(0)
                var drift by mutableIntStateOf(0)
                run(90, { f -> s = stateAt(f); drift = f * 3 }) {
                    SharedTransitionLayout {
                        AnimatedContent(s, transitionSpec = { cFadeIn(androidx.compose.animation.core.tween(200)) togetherWith cFadeOut(androidx.compose.animation.core.tween(200)) }) { which ->
                            val state = rememberSharedContentState("g")
                            if (which == 0) Box(Modifier.offset(20.dp, 20.dp).sharedElement(state, this@AnimatedContent, boundsTransform = { _, _ -> cSpring(1f, 400f) }).size(60.dp))
                            else Box(Modifier.offset { IntOffset(300 + drift, 200) }.sharedElement(state, this@AnimatedContent, boundsTransform = { _, _ -> cSpring(1f, 400f) }).size(300.dp))
                        }
                    }
                }
            },
        )
    }

    @Composable
    private fun Page(tab: Int) = Column { repeat(40) { BasicText("Tab $tab item $it") } }

    private fun tabSwitching() {
        // Rapid tab changes: a new tab every three frames, each a page of forty lines.
        fun tabAt(f: Int) = (f / 3) % 5
        val ease = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        compare(
            "rapid tab switching",
            {
                var tab by mutableIntStateOf(0)
                run(60, { f -> tab = tabAt(f) }) {
                    val t = rememberMotionTransition(tab, Spring(1f, 900f), order = { it })
                    MotionTransitionLayout(t, distance = 24.dp) { Page(it) }
                }
            },
            {
                var tab by mutableIntStateOf(0)
                run(60, { f -> tab = tabAt(f) }) {
                    io.github.matiyaaa.fuse.ui.fuseline.v2.Swap(
                        tab,
                        transitionSpec = {
                            val dir = if (targetState > initialState) 1 else -1
                            (io.github.matiyaaa.fuse.ui.fuseline.v2.slideInHorizontally(io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(1f, 900f)) { 24 * dir } +
                                io.github.matiyaaa.fuse.ui.fuseline.v2.fadeIn(io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(1f, 900f))) .let { enter ->
                                io.github.matiyaaa.fuse.ui.fuseline.v2.SwapTransform(
                                    enter,
                                    io.github.matiyaaa.fuse.ui.fuseline.v2.slideOutHorizontally(io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(1f, 900f)) { -24 * dir } +
                                        io.github.matiyaaa.fuse.ui.fuseline.v2.fadeOut(io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(1f, 900f)),
                                    null,
                                )
                            }
                        },
                    ) { Page(it) }
                }
            },
            {
                var tab by mutableIntStateOf(0)
                run(60, { f -> tab = tabAt(f) }) {
                    AnimatedContent(
                        tab,
                        transitionSpec = {
                            val dir = if (targetState > initialState) 1 else -1
                            (cSlideIn(cSpring(1f, 900f)) { 24 * dir } + cFadeIn(cSpring(1f, 900f))) togetherWith
                                (cSlideOut(cSpring(1f, 900f)) { -24 * dir } + cFadeOut(cSpring(1f, 900f)))
                        },
                    ) { Page(it) }
                }
            },
        )
        ease.hashCode()
    }

    private fun report() {
        val losses = ArrayList<String>()
        val table = buildString {
            appendLine("| Interface path | Fuseline 3 | Fuseline 2 | Compose | Fuseline 3 memory | Fuseline 2 memory | Compose memory | First |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for (r in rows) {
                val others = listOfNotNull(r.f2, r.compose)
                val first = others.all { r.f3.nanos < it.nanos } && others.all { r.f3.bytes < it.bytes }
                if (!first) losses += r.case
                fun us(x: Result?) = x?.let { "%.0f µs".format(it.nanos / 1000) } ?: "n/a"
                fun kb(x: Result?) = x?.let { "%.1f KB".format(it.bytes / 1024) } ?: "n/a"
                appendLine("| ${r.case} | ${us(r.f3)} | ${us(r.f2)} | ${us(r.compose)} | ${kb(r.f3)} | ${kb(r.f2)} | ${kb(r.compose)} | ${if (first) "Fuseline 3" else "NOT FIRST"} |")
            }
        }
        val method = "Per frame, composition, layout and drawing included (Compose's test clock), after a settled start; memory is what every thread allocated; 2 warm-up runs, then 5 rounds in rotating order, median."
        val report = "$table\n$method\n"
        println(report)
        File("build/ui-motion-bench.md").apply { parentFile.mkdirs() }.writeText(report)
        assertTrue(losses.isEmpty(), "Fuseline 3 is not first in: $losses")
    }
}
