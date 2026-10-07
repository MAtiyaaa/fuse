package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import androidx.compose.animation.core.spring as cSpring
import androidx.compose.animation.core.tween as cTween
import io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue as V2Value

/**
 * Fuseline 3 against Fuseline 2 (its frozen engine, kept in these tests) and Compose's own animation,
 * on every comparable workload. Run with
 * `./gradlew :ui:fuseline:desktopTest --tests '*MotionBenchmark*' -Pfuse.bench=true`; the table and
 * its method go to `ui/fuseline/build/motion-bench.md`. Without the property it does nothing.
 *
 * Method: one thread; for each round, a fresh manual frame clock and a scope that runs every move
 * inline as its frame arrives, so only the engines' own work is timed. Every engine gets the same
 * values, motions, curves, counts and frame times. Each engine is warmed up first (the JIT compiles
 * its paths), then the engines are measured in rounds that rotate their order; the median round
 * counts. Time is per frame (System.nanoTime around the frame and its per-frame work); memory is the
 * bytes the thread allocated per frame. Where an engine has no equivalent, its cell stays empty.
 */
class MotionBenchmark {
    private val enabled = System.getProperty("fuse.bench").orEmpty().toBoolean()

    private class Result(val nanos: Double, val bytes: Double)

    /** One engine's way of running a case: starts what it needs in the scope and returns its per-frame work. */
    private fun interface Workload {
        fun CoroutineScope.start(): ((frame: Int, timeNanos: Long) -> Unit)?
    }

    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val thread = Thread.currentThread().id

    private fun round(w: Workload, frames: Int, hz: Int): Result {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + clock + Job())
        val perFrame = with(w) { scope.start() }
        val step = 1_000_000_000L / hz
        var t = 1_000_000_000L
        // The first frame starts everything; it is not counted.
        clock.sendFrame(t)
        var spent = 0L
        val before = bean.getThreadAllocatedBytes(thread)
        for (f in 0 until frames) {
            t += step
            val t0 = System.nanoTime()
            perFrame?.invoke(f, t)
            clock.sendFrame(t)
            spent += System.nanoTime() - t0
        }
        val bytes = bean.getThreadAllocatedBytes(thread) - before
        scope.cancel()
        // One more frame, not counted, so every engine's frame loop sees its moves gone and lets go.
        clock.sendFrame(t + step)
        return Result(spent.toDouble() / frames, bytes.toDouble() / frames)
    }

    private class Row(val case: String, val f3: Result, val f2: Result?, val compose: Result?)

    private val rows = ArrayList<Row>()

    /** The harness's own cost per frame (the frame clock, the per-frame call), measured with no workload, at each rate. */
    private val harness = HashMap<Pair<Int, Int>, Result>()

    private fun baseline(frames: Int, hz: Int): Result = harness.getOrPut(frames to hz) {
        val empty = Workload { { _, _ -> } }
        repeat(WARM) { round(empty, frames, hz) }
        val rs = List(ROUNDS) { round(empty, frames, hz) }
        Result(rs.map { it.nanos }.sorted()[rs.size / 2], rs.map { it.bytes }.sorted()[rs.size / 2])
    }

    /** Only the cases whose names hold this (-Pfuse.bench.only=tween), for working on one row. */
    private val only = System.getProperty("fuse.bench.only").orEmpty()

    /** True while the JVM itself is being warmed: rows are measured and thrown away. */
    private var warming = false

    private fun compare(case: String, f3: Workload, f2: Workload?, compose: Workload?, frames: Int = FRAMES, hz: Int = 60) {
        if (only.isNotEmpty() && !case.contains(only)) return
        val base = baseline(frames, hz)
        val engines = listOfNotNull(f3, f2, compose)
        // Warm-up: every engine's paths compiled before anything counts.
        repeat(WARM) { for (e in engines) round(e, frames, hz) }
        val times = HashMap<Workload, MutableList<Result>>()
        repeat(ROUNDS) { r ->
            // The order rotates, so no engine always goes first or last.
            for (i in engines.indices) {
                val e = engines[(i + r) % engines.size]
                times.getOrPut(e) { ArrayList() } += round(e, frames, hz)
            }
        }
        // Each engine's own cost: the median round, less what the harness costs with nothing in it.
        fun median(e: Workload?): Result? {
            val rs = times[e ?: return null] ?: return null
            val ns = rs.map { it.nanos }.sorted()
            val bs = rs.map { it.bytes }.sorted()
            return Result((ns[ns.size / 2] - base.nanos).coerceAtLeast(0.0), (bs[bs.size / 2] - base.bytes).coerceAtLeast(0.0))
        }
        if (!warming) rows += Row(case, median(f3)!!, median(f2), median(compose))
    }

    private companion object {
        const val WARM = 4
        const val ROUNDS = 9

        /** Below 50 ns a frame, a cost is lost in the measuring itself. */
        const val TIME_FLOOR = 50.0
        const val LONG = 60_000

        /** Frames per round: long enough that a round is milliseconds, not microseconds, of work. */
        const val FRAMES = 600

        /** Frames per round where a frame's work is small (one value, a few readings): more of them, for the same reason. */
        const val SMALL = 3000

        /** The decays' friction multiplier: a slow coast that outlasts a round. */
        const val DRAG = 0.05f
        val STANDARD3: Curve = CubicCurve(0.2f, 0f, 0f, 1f)
        val STANDARD2 = io.github.matiyaaa.fuse.ui.fuseline.v2.CubicCurve(0.2f, 0f, 0f, 1f)
        val STANDARD_C = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }

    @Test
    fun theWholeMatrix() {
        if (!enabled) return
        // The first rows would otherwise run while the JVM still compiles the harness and every
        // engine's common paths: one pass first, thrown away (the value cases, or for a chosen few
        // rows, those rows themselves).
        warming = true
        if (only.isEmpty()) valuesInMotion() else everyCase()
        warming = false
        everyCase()
        report()
    }

    private fun everyCase() {
        valuesInMotion()
        retargeting()
        velocityAndDecay()
        transitionsAndTimelines()
        scheduling()
        refreshRates()
    }

    // ------------------------------------------------------------------ cases

    private fun valuesInMotion() {
        for (n in listOf(1, 100, 1000)) {
            val frames = if (n == 1) SMALL else FRAMES
            compare(
                "$n tweens",
                { repeat(n) { launch { FuselineValue(0f).animateTo(1f, Tween(LONG, curve = STANDARD3)) } }; null },
                { repeat(n) { launch { V2Value(0f).animateTo(1f, io.github.matiyaaa.fuse.ui.fuseline.v2.Tween(LONG, curve = STANDARD2)) } }; null },
                { repeat(n) { launch { Animatable(0f).animateTo(1f, cTween(LONG, easing = STANDARD_C)) } }; null },
                frames = frames,
            )
            compare(
                "$n springs",
                { repeat(n) { launch { FuselineValue(0f).animateTo(1000f, Spring(0.15f, 2f)) } }; null },
                { repeat(n) { launch { V2Value(0f).animateTo(1000f, io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(0.15f, 2f)) } }; null },
                { repeat(n) { launch { Animatable(0f).animateTo(1000f, cSpring(0.15f, 2f)) } }; null },
                frames = frames,
            )
            compare(
                "$n vector springs",
                { repeat(n) { launch { FuselineValue(Offset.Zero).animateTo(Offset(1000f, 500f), Spring(0.15f, 2f)) } }; null },
                { repeat(n) { launch { V2Value(Offset.Zero).animateTo(Offset(1000f, 500f), io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(0.15f, 2f)) } }; null },
                { repeat(n) { launch { Animatable(Offset.Zero, Offset.VectorConverter).animateTo(Offset(1000f, 500f), cSpring(0.15f, 2f)) } }; null },
                frames = frames,
            )
            if (n <= 100) compare(
                "$n colours",
                { repeat(n) { launch { FuselineValue(Color.Red).animateTo(Color.Blue, Tween(LONG, curve = Curves.Linear)) } }; null },
                { repeat(n) { launch { V2Value(Color.Red).animateTo(Color.Blue, io.github.matiyaaa.fuse.ui.fuseline.v2.Tween(LONG, curve = io.github.matiyaaa.fuse.ui.fuseline.v2.Curves.Linear)) } }; null },
                { repeat(n) { launch { Animatable(Color.Red).animateTo(Color.Blue, cTween(LONG, easing = LinearEasing)) } }; null },
                frames = frames,
            )
        }
    }

    private fun retargeting() {
        // Once: a hundred springs each given one new target halfway through.
        compare(
            "100 springs retargeted once",
            { val vs = List(100) { FuselineValue(0f) }; vs.forEach { v -> launch { v.animateTo(1000f, Spring(0.8f, 30f)) } }; { f, _ -> if (f == 60) vs.forEach { it.retargetFloat(-500f) } } },
            { val vs = List(100) { V2Value(0f) }; val s = io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(0.8f, 30f); vs.forEach { v -> launch { v.animateTo(1000f, s) } }; { f, _ -> if (f == 60) vs.forEach { it.retarget(-500f, s) } } },
            { val vs = List(100) { Animatable(0f) }; val s = cSpring(0.8f, 30f, null as Float?); vs.forEach { v -> launch { v.animateTo(1000f, s) } }; { f, _ -> if (f == 60) vs.forEach { v -> launch { v.animateTo(-500f, s) } } } },
        )
        for (n in listOf(100, 1000)) compare(
            "$n springs retargeted every frame",
            { val vs = List(n) { FuselineValue(0f) }; vs.forEach { v -> launch { v.animateTo(0.5f, Spring(0.8f, 300f)) } }; { f, _ -> for (v in vs) v.retargetFloat(f * 10f) } },
            { val vs = List(n) { V2Value(0f) }; val s = io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(0.8f, 300f); vs.forEach { v -> launch { v.animateTo(0.5f, s) } }; { f, _ -> for (v in vs) if (!v.retarget(f * 10f, s)) launch { v.animateTo(f * 10f, s) } } },
            { val vs = List(n) { Animatable(0f) }; val s = cSpring(0.8f, 300f, null as Float?); { f, _ -> for (v in vs) launch { v.animateTo(f * 10f, s) } } },
        )
        for (n in listOf(100, 1000)) compare(
            "$n vector springs retargeted every frame",
            { val vs = List(n) { FuselineValue(Offset.Zero) }; vs.forEach { v -> launch { v.animateTo(Offset(1f, 1f), Spring(0.8f, 300f)) } }; { f, _ -> for (v in vs) v.retargetXY(f * 10f, f * 5f) } },
            { val vs = List(n) { V2Value(Offset.Zero) }; val s = io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(0.8f, 300f); vs.forEach { v -> launch { v.animateTo(Offset(1f, 1f), s) } }; { f, _ -> for (v in vs) if (!v.retarget(Offset(f * 10f, f * 5f), s)) launch { v.animateTo(Offset(f * 10f, f * 5f), s) } } },
            { val vs = List(n) { Animatable(Offset.Zero, Offset.VectorConverter) }; val s = cSpring(0.8f, 300f, null as Offset?); { f, _ -> for (v in vs) launch { v.animateTo(Offset(f * 10f, f * 5f), s) } } },
        )
    }

    private fun velocityAndDecay() {
        // A tween's velocity asked for at a thousand moments (what a handoff reads), each engine's own way.
        val times = LongArray(1000) { it * 300_000L }
        compare(
            "tween velocity, 1000 readings",
            { val t = TweenTrack(Tween(300, curve = STANDARD3), 0f, 500f, 0f); var sink = 0f; { _, _ -> for (x in times) sink += t.velocityAt(x); if (sink == 42f) println() } },
            { val t = io.github.matiyaaa.fuse.ui.fuseline.v2.TweenTrack(io.github.matiyaaa.fuse.ui.fuseline.v2.Tween(300, curve = STANDARD2), 0f, 500f); var sink = 0f; { _, _ -> for (x in times) sink += t.velocityAt(x); if (sink == 42f) println() } },
            {
                val spec = cTween<Float>(300, easing = STANDARD_C).vectorize(Float.VectorConverter)
                val a = androidx.compose.animation.core.AnimationVector1D(0f)
                val b = androidx.compose.animation.core.AnimationVector1D(500f)
                val v = androidx.compose.animation.core.AnimationVector1D(0f)
                var sink = 0f
                { _, _ -> for (x in times) sink += spec.getVelocityFromNanos(x, a, b, v).value; if (sink == 42f) println() }
            },
        )
        // Coasting, long enough to last every round (a twentieth of the usual friction): both stop below 0.1 units/s.
        for (n in listOf(1, 100)) compare(
            "$n decays",
            { repeat(n) { launch { FuselineValue(0f).animateDecay(2000f, Decay(4.2f * DRAG, 0.1f / (4.2f * DRAG))) } }; null },
            null,
            { repeat(n) { launch { Animatable(0f).animateDecay(2000f, exponentialDecay(DRAG, 0.1f)) } }; null },
            frames = if (n == 1) SMALL else FRAMES,
        )
        // Following a finger: 100 values moved every frame, their velocity tracked, read at the end.
        compare(
            "100 values tracking a gesture",
            { val vs = List(100) { FuselineValue(0f) }; { f, t -> for (v in vs) v.dragBy(3f, t); if (f == 119) for (v in vs) v.releaseVelocity(t) } },
            null,
            {
                val vs = List(100) { Animatable(0f) }
                val trackers = List(100) { VelocityTracker1D(false) }
                val self = this
                { f, t ->
                    for (i in vs.indices) {
                        val x = f * 3f + 3f
                        self.launch { vs[i].snapTo(x) }
                        trackers[i].addDataPoint(t / 1_000_000L, x)
                    }
                    if (f == 119) for (tr in trackers) tr.calculateVelocity()
                }
            },
        )
    }

    private fun transitionsAndTimelines() {
        val tabs = listOf("Home", "Library", "Systems", "Apps", "Addons")
        // A state change moves every state's presence and position: the same ten values in each engine.
        fun f3(every: Int, pattern: (Int) -> Int): Workload = Workload {
            val t = MotionTransition("Home", order = { tabs.indexOf(it) })
            val s = this
            { f, _ -> if (f % every == 0) t.go(tabs[pattern(f / every)], s, Spring(1f, 900f)) }
        }
        fun f2(every: Int, pattern: (Int) -> Int): Workload = Workload {
            val presence = List(5) { V2Value(if (it == 0) 1f else 0f) }
            val place = List(5) { V2Value(0f) }
            val sp = io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(1f, 900f)
            var at = 0
            val s = this
            { f, _ ->
                if (f % every == 0) {
                    val to = pattern(f / every)
                    val dir = if (to > at) -1f else 1f
                    for (i in 0 until 5) {
                        val p = presence[i]
                        val q = place[i]
                        val pg = if (i == to) 1f else 0f
                        val qg = if (i == to) 0f else dir
                        if (!p.retarget(pg, sp)) s.launch { p.animateTo(pg, sp) }
                        if (!q.retarget(qg, sp)) s.launch { q.animateTo(qg, sp) }
                    }
                    at = to
                }
            }
        }
        fun compose(every: Int, pattern: (Int) -> Int): Workload = Workload {
            val presence = List(5) { Animatable(if (it == 0) 1f else 0f) }
            val place = List(5) { Animatable(0f) }
            val sp = cSpring(1f, 900f, null as Float?)
            var at = 0
            val s = this
            { f, _ ->
                if (f % every == 0) {
                    val to = pattern(f / every)
                    val dir = if (to > at) -1f else 1f
                    for (i in 0 until 5) {
                        val pg = if (i == to) 1f else 0f
                        val qg = if (i == to) 0f else dir
                        s.launch { presence[i].animateTo(pg, sp) }
                        s.launch { place[i].animateTo(qg, sp) }
                    }
                    at = to
                }
            }
        }
        compare("tab transitions", f3(30) { (it % 4) + 1 }, f2(30) { (it % 4) + 1 }, compose(30) { (it % 4) + 1 }, frames = SMALL)
        compare("tab changes every 3 frames", f3(3) { (it % 4) + 1 }, f2(3) { (it % 4) + 1 }, compose(3) { (it % 4) + 1 }, frames = SMALL)
        compare("tab reversal every 4 frames", f3(4) { it % 2 }, f2(4) { it % 2 }, compose(4) { it % 2 }, frames = SMALL)

        // Twenty keyframed tracks read every frame.
        val names = List(20) { "t$it" }
        val tl3 = Timeline(4000) { for (n in names) track(n) { at(0, 0f); at(1500, 1f, Curves.Enter); at(4000, 0.5f, Curves.Standard) } }
        val tl2 = io.github.matiyaaa.fuse.ui.fuseline.v2.Timeline(4000) { for (n in names) track(n) { at(0, 0f); at(1500, 1f, io.github.matiyaaa.fuse.ui.fuseline.v2.Curves.Enter); at(4000, 0.5f, io.github.matiyaaa.fuse.ui.fuseline.v2.Curves.Standard) } }
        compare(
            "timeline, 20 tracks",
            { val p = TimelinePlayer(tl3); var sink = 0f; { _, t -> p.tick(t, 1f); for (n in names) sink += p[n]; if (sink == 42f) println() } },
            { val p = io.github.matiyaaa.fuse.ui.fuseline.v2.TimelinePlayer(tl2); var sink = 0f; { _, t -> p.tick(t, 1f); for (n in names) sink += p[n]; if (sink == 42f) println() } },
            {
                val vs = List(20) { Animatable(0f) }
                vs.forEach { v -> launch { v.animateTo(0.5f, keyframes { durationMillis = 4000; 0f at 0; 1f at 1500 using CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) }) } }
                var sink = 0f
                { _, _ -> for (v in vs) sink += v.value; if (sink == 42f) println() }
            },
            frames = SMALL,
        )
        compare(
            "timeline seek, 20 tracks",
            { val p = TimelinePlayer(tl3); var sink = 0f; { f, _ -> p.seek((f * 37) % 4000); for (n in names) sink += p[n]; if (sink == 42f) println() } },
            { val p = io.github.matiyaaa.fuse.ui.fuseline.v2.TimelinePlayer(tl2); var sink = 0f; { f, _ -> p.seek((f * 37) % 4000); for (n in names) sink += p[n]; if (sink == 42f) println() } },
            null,
            frames = SMALL,
        )
        compare(
            "timeline reverse, 20 tracks",
            { val p = TimelinePlayer(tl3); var sink = 0f; { f, t -> if (f % 30 == 0) p.reverse(); p.tick(t, 1f); for (n in names) sink += p[n]; if (sink == 42f) println() } },
            null,
            null,
            frames = SMALL,
        )
    }

    private fun scheduling() {
        // Short motions starting and ending all the time: a tenth of [n] new 100 ms tweens every frame.
        for (n in listOf(10, 100, 1000)) {
            val per = maxOf(1, n / 10)
            compare(
                "driver churn, $n",
                { val s = this; { _, _ -> repeat(per) { s.launch { FuselineValue(0f).animateTo(1f, Tween(100)) } } } },
                { val s = this; { _, _ -> repeat(per) { s.launch { V2Value(0f).animateTo(1f, io.github.matiyaaa.fuse.ui.fuseline.v2.Tween(100)) } } } },
                { val s = this; { _, _ -> repeat(per) { s.launch { Animatable(0f).animateTo(1f, cTween(100, easing = STANDARD_C)) } } } },
            )
        }
        // Idle: a thousand values settled, frames still arriving.
        compare(
            "idle, 1000 settled values",
            { repeat(1000) { val v = FuselineValue(0f); launch { v.snapTo(1f) } }; null },
            { repeat(1000) { val v = V2Value(0f); launch { v.snapTo(1f) } }; null },
            { repeat(1000) { val v = Animatable(0f); launch { v.snapTo(1f) } }; null },
        )
    }

    private fun refreshRates() {
        for (hz in listOf(30, 60, 90, 120, 144, 165, 240)) compare(
            "1000 springs at $hz Hz",
            { repeat(1000) { launch { FuselineValue(0f).animateTo(1000f, Spring(0.15f, 2f)) } }; null },
            { repeat(1000) { launch { V2Value(0f).animateTo(1000f, io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(0.15f, 2f)) } }; null },
            { repeat(1000) { launch { Animatable(0f).animateTo(1000f, cSpring(0.15f, 2f)) } }; null },
            frames = hz * 2, hz = hz,
        )
    }

    // ------------------------------------------------------------------ the table

    private fun us(r: Result?) = r?.let { "%.2f µs".format(it.nanos / 1000) } ?: "n/a"
    private fun b(r: Result?) = r?.let { "%.0f B".format(it.bytes) } ?: "n/a"

    private fun report() {
        val losses = ArrayList<String>()
        val table = buildString {
            appendLine("| Case | Fuseline 3 | Fuseline 2 | Compose | Fuseline 3 memory | Fuseline 2 memory | Compose memory | First |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for (r in rows) {
                val others = listOfNotNull(r.f2, r.compose)
                // Below the measuring floor everything is nothing: a tie at zero is not a loss.
                val fastest = others.all { r.f3.nanos < it.nanos || (r.f3.nanos <= TIME_FLOOR && it.nanos <= TIME_FLOOR) }
                // Memory: less, or the same where the others make nothing either.
                val leanest = others.all { r.f3.bytes < it.bytes || (r.f3.bytes <= 0.5 && it.bytes <= 0.5) }
                val first = when {
                    others.isEmpty() -> "Fuseline 3 only"
                    fastest && leanest -> "Fuseline 3"
                    else -> "NOT FIRST"
                }
                if (others.isNotEmpty() && !(fastest && leanest)) losses += r.case
                appendLine("| ${r.case} | ${us(r.f3)} | ${us(r.f2)} | ${us(r.compose)} | ${b(r.f3)} | ${b(r.f2)} | ${b(r.compose)} | $first |")
            }
        }
        val method = """
            Measured on one thread with a manual frame clock (moves run inline as each frame arrives),
            the same values, motions, curves, counts and frame times for every engine; each engine
            warmed up $WARM rounds, then $ROUNDS rounds in rotating order; the median round counts.
            Time and bytes allocated are per frame, less the harness's own cost (an empty workload
            measured the same way), so a row shows only what the engine itself does; below 50 ns or
            half a byte is nothing. Fuseline 2 is the 0.3.6 engine kept unchanged in
            the tests. "n/a" is a workload the engine has no equivalent for. Who is first is decided on
            the unrounded numbers.
            JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} processors, ${System.getProperty("os.name")} ${System.getProperty("os.arch")}.
        """.trimIndent()
        val report = "$table\n$method\n"
        println(report)
        File("build/motion-bench.md").apply { parentFile.mkdirs() }.writeText(report)
        assertTrue(losses.isEmpty(), "Fuseline 3 is not first in: $losses")
    }
}
