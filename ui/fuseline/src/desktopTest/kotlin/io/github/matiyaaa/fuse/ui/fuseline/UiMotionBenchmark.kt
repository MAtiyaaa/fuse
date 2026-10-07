package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import io.github.matiyaaa.fuse.ui.fuseline.v3.motionBounds as v3MotionBounds
import io.github.matiyaaa.fuse.ui.fuseline.v3.sharedMotion as v3SharedMotion
import androidx.compose.animation.core.spring as cSpring
import androidx.compose.animation.fadeIn as cFadeIn
import androidx.compose.animation.fadeOut as cFadeOut
import androidx.compose.animation.slideInHorizontally as cSlideIn
import androidx.compose.animation.slideOutHorizontally as cSlideOut

/**
 * Whole interface paths, composition, layout and drawing included: tiles reflowing as their window
 * changes width (layout motion), an element shared between two screens whose destination keeps
 * moving, rapid tab switching, pages of tiles with following values, and a decorative room. Fuseline
 * 3.1, Fuseline 3, 2 and 1 (frozen in these tests) where each has the capability, and Compose
 * (animateBounds in a LookaheadScope, SharedTransitionLayout, AnimatedContent, animate*AsState).
 * The whole run of frames is timed, and the bytes allocated counted, after a warm-up; rounds rotate
 * between engines and the median counts. Runs only with -Pfuse.bench=true.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
class UiMotionBenchmark {
    private val enabled = System.getProperty("fuse.bench").orEmpty().toBoolean()
    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private class Result(val nanos: Double, val bytes: Double)

    /** Sets [content] up, settles it, then times [frames] frames ([frameMs] apart) after [change], per frame. */
    private fun run(frames: Int, change: (Int) -> Unit, frameMs: Long = 0L, content: @Composable () -> Unit): Result {
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
                if (frameMs > 0L) mainClock.advanceTimeBy(frameMs) else mainClock.advanceTimeByFrame()
            }
            val spent = System.nanoTime() - t0
            result = Result(spent.toDouble() / frames, (bean.totalThreadAllocatedBytes - before).toDouble() / frames)
        }
        return result
    }

    /** [f3] is the engine in use (Fuseline 3.1); [v3] and [v1] the frozen Fuseline 3 and Fuseline 1. */
    private class Row(val case: String, val f3: Result, val f2: Result?, val compose: Result?, val v3: Result?, val v1: Result?)

    private val rows = ArrayList<Row>()

    private fun compare(case: String, f3: () -> Result, f2: (() -> Result)?, compose: (() -> Result)?, v3: (() -> Result)? = null, v1: (() -> Result)? = null) {
        val engines = listOfNotNull(f3, v3, f2, v1, compose)
        repeat(3) { for (e in engines) e() }
        val results = HashMap<() -> Result, MutableList<Result>>()
        repeat(7) { r -> for (i in engines.indices) { val e = engines[(i + r) % engines.size]; results.getOrPut(e) { ArrayList() } += e() } }
        fun median(e: (() -> Result)?): Result? {
            val rs = results[e ?: return null] ?: return null
            return Result(rs.map { it.nanos }.sorted()[rs.size / 2], rs.map { it.bytes }.sorted()[rs.size / 2])
        }
        rows += Row(case, median(f3)!!, median(f2), median(compose), median(v3), median(v1))
    }

    @Test
    fun interfacePaths() {
        if (!enabled) return
        layoutMotion()
        sharedElement()
        tabSwitching()
        followers()
        decoration()
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
            v3 = {
                var w by mutableIntStateOf(700)
                run(80, { f -> w = widthAt(f) }) {
                    FlowRow(Modifier.width(w.dp)) { repeat(50) { Box(Modifier.v3MotionBounds(io.github.matiyaaa.fuse.ui.fuseline.v3.Spring(1f, 400f)).size(60.dp)) } }
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
            v3 = {
                var s by mutableIntStateOf(0)
                var drift by mutableIntStateOf(0)
                val shared = io.github.matiyaaa.fuse.ui.fuseline.v3.SharedMotion(io.github.matiyaaa.fuse.ui.fuseline.v3.Spring(1f, 400f))
                run(90, { f -> s = stateAt(f); drift = f * 3 }) {
                    io.github.matiyaaa.fuse.ui.fuseline.v3.Swap(s, transitionSpec = { io.github.matiyaaa.fuse.ui.fuseline.v3.SwapTransform(io.github.matiyaaa.fuse.ui.fuseline.v3.fadeIn(io.github.matiyaaa.fuse.ui.fuseline.v3.tween(200)), io.github.matiyaaa.fuse.ui.fuseline.v3.fadeOut(io.github.matiyaaa.fuse.ui.fuseline.v3.tween(200)), null) }) { which ->
                        if (which == 0) Box(Modifier.offset(20.dp, 20.dp).v3SharedMotion(shared, "g").size(60.dp))
                        else Box(Modifier.offset { IntOffset(300 + drift, 200) }.v3SharedMotion(shared, "g").size(300.dp))
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
            v3 = {
                var tab by mutableIntStateOf(0)
                run(60, { f -> tab = tabAt(f) }) {
                    val t = io.github.matiyaaa.fuse.ui.fuseline.v3.rememberMotionTransition(tab, io.github.matiyaaa.fuse.ui.fuseline.v3.Spring(1f, 900f), order = { it })
                    io.github.matiyaaa.fuse.ui.fuseline.v3.MotionTransitionLayout(t, distance = 24.dp) { Page(it) }
                }
            },
            v1 = {
                var tab by mutableIntStateOf(0)
                run(60, { f -> tab = tabAt(f) }) {
                    io.github.matiyaaa.fuse.ui.fuseline.v1.Swap(
                        tab,
                        transitionSpec = {
                            val dir = if (targetState > initialState) 1 else -1
                            io.github.matiyaaa.fuse.ui.fuseline.v1.SwapTransform(
                                io.github.matiyaaa.fuse.ui.fuseline.v1.slideInHorizontally(io.github.matiyaaa.fuse.ui.fuseline.v1.Spring(1f, 900f)) { 24 * dir } + io.github.matiyaaa.fuse.ui.fuseline.v1.fadeIn(io.github.matiyaaa.fuse.ui.fuseline.v1.Spring(1f, 900f)),
                                io.github.matiyaaa.fuse.ui.fuseline.v1.slideOutHorizontally(io.github.matiyaaa.fuse.ui.fuseline.v1.Spring(1f, 900f)) { -24 * dir } + io.github.matiyaaa.fuse.ui.fuseline.v1.fadeOut(io.github.matiyaaa.fuse.ui.fuseline.v1.Spring(1f, 900f)),
                                null,
                            )
                        },
                    ) { Page(it) }
                }
            },
        )
        ease.hashCode()
    }


    /** A tile of a page: three values following its selection, read only while drawing (as Fuse's tiles do). */
    @Composable
    private fun FollowTile31(selected: Boolean) {
        val lift by fuselineFloat(if (selected) 1f else 0f, Spring(1f, 700f))
        val tint by fuselineColor(if (selected) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Gray, Spring(1f, 700f))
        val edge by fuselineDp(if (selected) 4.dp else 0.dp, Spring(1f, 700f))
        Box(Modifier.size(40.dp).drawBehind { drawRect(tint, alpha = 0.5f + lift / 2, size = this.size.copy(width = this.size.width - edge.toPx())) })
    }

    @Composable
    private fun FollowTile3(selected: Boolean) {
        val lift by io.github.matiyaaa.fuse.ui.fuseline.v3.fuselineFloat(if (selected) 1f else 0f, io.github.matiyaaa.fuse.ui.fuseline.v3.Spring(1f, 700f))
        val tint by io.github.matiyaaa.fuse.ui.fuseline.v3.fuselineColor(if (selected) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Gray, io.github.matiyaaa.fuse.ui.fuseline.v3.Spring(1f, 700f))
        val edge by io.github.matiyaaa.fuse.ui.fuseline.v3.fuselineDp(if (selected) 4.dp else 0.dp, io.github.matiyaaa.fuse.ui.fuseline.v3.Spring(1f, 700f))
        Box(Modifier.size(40.dp).drawBehind { drawRect(tint, alpha = 0.5f + lift / 2, size = this.size.copy(width = this.size.width - edge.toPx())) })
    }

    @Composable
    private fun FollowTileCompose(selected: Boolean) {
        val lift by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, cSpring(1f, 700f))
        val tint by androidx.compose.animation.animateColorAsState(if (selected) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Gray, cSpring(1f, 700f))
        val edge by androidx.compose.animation.core.animateDpAsState(if (selected) 4.dp else 0.dp, cSpring(1f, 700f))
        Box(Modifier.size(40.dp).drawBehind { drawRect(tint, alpha = 0.5f + lift / 2, size = this.size.copy(width = this.size.width - edge.toPx())) })
    }

    private fun followers() {
        // A page of 120 tiles with three following values each, a new page every four frames: what
        // composing a screen of tiles costs (a tab switch, new rows scrolling in).
        fun pages(tile: @Composable (Boolean) -> Unit): () -> Result = {
            var page by mutableIntStateOf(0)
            run(48, { f -> page = f / 4 }) {
                androidx.compose.runtime.key(page) { FlowRow { repeat(120) { i -> tile(i == page % 120) } } }
            }
        }
        compare("a page of 120 tiles, 3 values each, composed", pages { FollowTile31(it) }, null, pages { FollowTileCompose(it) }, v3 = pages { FollowTile3(it) })
        // The selection moving along 120 tiles every three frames: each move retargets two tiles.
        fun moving(tile: @Composable (Boolean) -> Unit): () -> Result = {
            var sel by mutableIntStateOf(0)
            run(90, { f -> sel = f / 3 }) {
                FlowRow { repeat(120) { i -> tile(i == sel) } }
            }
        }
        compare("selection moving across 120 tiles", moving { FollowTile31(it) }, null, moving { FollowTileCompose(it) }, v3 = moving { FollowTile3(it) })
    }

    /** What a decorative room asks of the device each second: frame callbacks woken, and redraws. */
    private class Work(val wakeups: Double, val redraws: Double)

    private val decorationRows = ArrayList<Pair<String, List<Work?>>>()

    private fun decoration() {
        // A room's light that needs 30 updates a second, on a 120 Hz screen: how often it wakes for a
        // frame and how often it is drawn again, per second, at rest and while buttons are pressed
        // (a press every 50 ms). Counted, not timed: the work a phone would do for it.
        var wakes = 0
        var draws = 0
        val fill = Modifier.size(24.dp).drawBehind { draws++ }
        fun room31(): @Composable () -> Unit = {
            var t by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            androidx.compose.runtime.LaunchedEffect(Unit) { decorationFrames(30, infinite = false) { t = it / 1e9f } }
            Box(fill.graphicsLayerAlpha { 0.5f + kotlin.math.sin(t) / 2 })
        }
        fun room3(): @Composable () -> Unit = {
            // Fuseline 3's way (0.3.7.4's room): a frame wait every frame, an update when one is due.
            var t by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                var last = 0L
                val start = androidx.compose.runtime.withFrameMillis { it }
                while (true) androidx.compose.runtime.withFrameMillis { now -> wakes++; if (now - last >= 33) { t = (now - start) / 1000f; last = now } }
            }
            Box(fill.graphicsLayerAlpha { 0.5f + kotlin.math.sin(t) / 2 })
        }
        fun roomCompose(): @Composable () -> Unit = {
            val t by androidx.compose.animation.core.rememberInfiniteTransition().animateFloat(0f, 1000f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_000_000, easing = androidx.compose.animation.core.LinearEasing)))
            Box(fill.graphicsLayerAlpha { 0.5f + kotlin.math.sin(t) / 2 })
        }
        fun count(input: Boolean, wakesOf31: Boolean, room: @Composable () -> Unit): Work {
            FramePacing.reset()
            wakes = 0
            draws = 0
            val before = FramePacing.decorationWakeups
            run(240, { f -> if (input && f % 6 == 0) FramePacing.input() }, frameMs = 8L) { room() }
            val w = if (wakesOf31) (FramePacing.decorationWakeups - before).toDouble() else wakes.toDouble()
            // 240 frames 8 ms apart: 1.92 seconds.
            return Work(w / 1.92, draws / 1.92)
        }
        for (input in listOf(false, true)) {
            decorationRows += (if (input) "while buttons are pressed" else "at rest") to listOf(
                count(input, true, room31()),
                count(input, false, room3()),
                // Compose's loop wakes on every frame of the display.
                count(input, false, roomCompose()).let { Work(120.0, it.redraws) },
            )
        }
    }

    /** Alpha read while drawing a layer, so a room changing only redraws itself. */
    private fun Modifier.graphicsLayerAlpha(alpha: () -> Float) = this.then(Modifier.drawBehind { drawRect(androidx.compose.ui.graphics.Color.Gray, alpha = alpha().coerceIn(0f, 1f)) })

    private fun report() {
        val losses = ArrayList<String>()
        val table = buildString {
            appendLine("| Interface path | Fuseline 3.1 | Fuseline 3 | Fuseline 2 | Fuseline 1 | Compose | Fuseline 3.1 memory | Fuseline 3 memory | Fuseline 2 memory | Fuseline 1 memory | Compose memory | First |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
            for (r in rows) {
                val others = listOfNotNull(r.v3, r.f2, r.v1, r.compose)
                val first = others.all { r.f3.nanos < it.nanos } && others.all { r.f3.bytes <= it.bytes * 1.02 }
                if (!first) losses += r.case
                fun us(x: Result?) = x?.let { "%.0f µs".format(it.nanos / 1000) } ?: "n/a"
                fun kb(x: Result?) = x?.let { "%.1f KB".format(it.bytes / 1024) } ?: "n/a"
                appendLine("| ${r.case} | ${us(r.f3)} | ${us(r.v3)} | ${us(r.f2)} | ${us(r.v1)} | ${us(r.compose)} | ${kb(r.f3)} | ${kb(r.v3)} | ${kb(r.f2)} | ${kb(r.v1)} | ${kb(r.compose)} | ${if (first) "Fuseline 3.1" else "NOT FIRST"} |")
            }
        }
        val decorationTable = buildString {
            appendLine()
            appendLine("A room needing 30 updates a second on a 120 Hz screen, per second:")
            appendLine()
            appendLine("| Room | Fuseline 3.1 wakes | Fuseline 3 wakes | Compose wakes | Fuseline 3.1 redraws | Fuseline 3 redraws | Compose redraws |")
            appendLine("|---|---|---|---|---|---|---|")
            for ((name, w) in decorationRows) {
                fun n(x: Work?, f: (Work) -> Double) = x?.let { "%.0f".format(f(it)) } ?: "n/a"
                appendLine("| $name | ${n(w[0]) { it.wakeups }} | ${n(w[1]) { it.wakeups }} | ${n(w[2]) { it.wakeups }} | ${n(w[0]) { it.redraws }} | ${n(w[1]) { it.redraws }} | ${n(w[2]) { it.redraws }} |")
                val f31 = w[0]!!
                if (w.drop(1).any { o -> o != null && (f31.wakeups > o.wakeups || f31.redraws > o.redraws) }) losses += "room $name"
            }
        }
        val method = "Per frame, composition, layout and drawing included (Compose's test clock), after a settled start; memory is what every thread allocated; 3 warm-up runs, then 7 rounds in rotating order, median."
        val report = "$table\n$method\n$decorationTable"
        println(report)
        File("build/ui-motion-bench.md").apply { parentFile.mkdirs() }.writeText(report)
        assertTrue(losses.isEmpty(), "Fuseline 3.1 is not first in: $losses")
    }
}
