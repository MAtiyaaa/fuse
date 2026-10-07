package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import io.github.matiyaaa.fuse.ui.fuseline.bench.ComposeEngine
import io.github.matiyaaa.fuse.ui.fuseline.bench.DrawModel
import io.github.matiyaaa.fuse.ui.fuseline.bench.ENGINES
import io.github.matiyaaa.fuse.ui.fuseline.bench.Engine
import io.github.matiyaaa.fuse.ui.fuseline.bench.Fuseline4
import io.github.matiyaaa.fuse.ui.fuseline.bench.Spec
import io.github.matiyaaa.fuse.ui.fuseline.bench.CurveId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fuseline 4 against Fuseline 3.1, 3, 2 and 1 (each frozen as it shipped, kept in these tests:
 * packages `v31`, `v3`, `v2`, `v1`) and Compose's own animation, on every comparable workload. Run with
 * `./gradlew :ui:fuseline:desktopTest --tests '*MotionBenchmark*' -Pfuse.bench=true`; the tables and
 * their method go to `ui/fuseline/build/motion-bench.md`. Without the property it does nothing.
 *
 * Every case is written once against [Engine], so each engine gets the same values, motions, targets,
 * frame times and reads. Most cases run in three ways:
 *
 * - **drawn**: each value has its own reader, re-run whenever what it read changes, the way Compose
 *   redraws a layer ([DrawModel]: reads recorded per reader, changes applied after every frame).
 *   This is what a frame costs in an app, and the comparison that counts most.
 * - **read**: every value read after every frame, whether it changed or not (a canvas that redraws
 *   everything each frame).
 * - **unread**: nothing reads the values (offscreen, or not drawn). An engine that runs motion every
 *   frame pays for it anyway; one that works on demand does not.
 *
 * Method: one thread, a fresh manual frame clock and an inline scope for every round, so only the
 * engines' own work is timed. Each engine is warmed up first, then measured in [rounds] rounds that
 * rotate the engines' order. A round's cost is its mean per frame, less what the harness costs with
 * no engine at all; the median round counts. An engine is first only when its whole 95% bootstrap
 * interval lies below every other engine's: when intervals overlap the row says so ("unresolved"),
 * and the workload is what has to grow, not the verdict.
 */
class MotionBenchmark {
    private val enabled = System.getProperty("fuse.bench").orEmpty().toBoolean()
    private val only = System.getProperty("fuse.bench.only").orEmpty()
    private val rounds = System.getProperty("fuse.bench.rounds")?.toIntOrNull() ?: 15

    enum class Mode(val label: String) { DRAWN("drawn"), READ("read"), UNREAD("unread") }

    enum class Group(val title: String) {
        VALUES("Values in motion"),
        SCALE("Scale and kinds of motion"),
        RETARGET("Retargeting, gestures, decay"),
        CHOREOGRAPHY("Transitions and timelines"),
        SCHEDULING("Scheduling, start and stop"),
        REFRESH("Refresh rates and irregular frames"),
        SUSTAINED("Sustained work"),
    }

    /** What one engine runs for a case: per-frame work, and what a frame reads ([count] readers). */
    class Run(
        val count: Int,
        val readOne: ((Int) -> Float)?,
        val perFrame: ((frame: Int, timeNanos: Long) -> Unit)? = null,
    )

    private class Case(
        val name: String,
        val group: Group,
        val mode: Mode,
        val frames: Int,
        /** The time of each frame after the first, given the frame before. */
        val next: (frame: Int, before: Long) -> Long,
        /** Frames run (unmeasured) before measuring: a motion's tail, or a dormant stretch. */
        val lead: Int,
        val supports: (Engine) -> Boolean,
        val build: (Engine, CoroutineScope) -> Run,
    )

    private class Round(val nanos: Double, val bytes: Double, val requested: Int, val frameNanos: LongArray)

    private class Measured(val e: Engine, val nanos: Double, val bytes: Double, val low: Double, val high: Double, val requested: Double, val p: Percentiles)

    class Percentiles(val mean: Double, val p50: Double, val p90: Double, val p95: Double, val p99: Double, val worst: Double)

    private class Row(val case: Case, val results: List<Measured?>)

    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val thread = Thread.currentThread().id
    private val rows = ArrayList<Row>()
    private var warming = false

    // ------------------------------------------------------------------ measuring

    private fun round(e: Engine?, c: Case): Round {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + clock + Job())
        val run = if (e == null) Run(0, null) else c.build(e, scope)
        val drawn = c.mode == Mode.DRAWN && run.readOne != null
        var sink = 0f
        val model = if (drawn) DrawModel(run.count) { i -> sink += run.readOne!!(i) } else null
        // What was made so far was made as composition makes it: its writes count as changes.
        Snapshot.notifyObjectsInitialized()
        var t = 1_000_000_000L
        // The first frame starts everything; it, and the lead frames, are not counted.
        clock.sendFrame(t)
        if (model != null) for (i in 0 until run.count) model.draw(i)
        for (f in 0 until c.lead) {
            t = c.next(f, t)
            run.perFrame?.invoke(f, t)
            clock.sendFrame(t)
            model?.frame()
        }
        val frameNanos = LongArray(c.frames)
        var requested = 0
        var spent = 0L
        val before = bean.getThreadAllocatedBytes(thread)
        val read = run.readOne
        for (f in 0 until c.frames) {
            val ft = c.next(c.lead + f, t)
            t = ft
            val t0 = System.nanoTime()
            run.perFrame?.invoke(c.lead + f, ft)
            clock.sendFrame(ft)
            when {
                model != null -> model.frame()
                c.mode == Mode.READ && read != null -> for (i in 0 until run.count) sink += read(i)
                else -> Snapshot.sendApplyNotifications()
            }
            val d = System.nanoTime() - t0
            frameNanos[f] = d
            spent += d
            if (clock.hasAwaiters) requested++
        }
        val bytes = bean.getThreadAllocatedBytes(thread) - before
        model?.dispose()
        scope.cancel()
        // One more frame, not counted, so every engine's frame loop sees its moves gone and lets go.
        clock.sendFrame(t + 16_666_667L)
        if (e === ComposeEngine) ComposeEngine.forgetTrackers()
        if (sink == 42.4242f) println()
        return Round(spent.toDouble() / c.frames, bytes.toDouble() / c.frames, requested, frameNanos)
    }

    /** The harness's own cost for a case's shape (no engine), measured the same way. */
    private val harness = HashMap<String, Double>()

    private fun baseline(c: Case): Double = harness.getOrPut("${c.frames}/${c.lead}/${c.mode}/${c.name}") {
        repeat(WARM) { round(null, c) }
        List(rounds) { round(null, c).nanos }.sorted()[rounds / 2]
    }

    private fun case(c: Case) {
        if (only.isNotEmpty() && !c.name.contains(only)) return
        val engines = ENGINES.filter(c.supports)
        val base = baseline(c)
        repeat(WARM) { for (e in engines) round(e, c) }
        val got = HashMap<Engine, MutableList<Round>>()
        repeat(rounds) { r ->
            // The order rotates, so no engine always goes first or last.
            for (i in engines.indices) {
                val e = engines[(i + r) % engines.size]
                got.getOrPut(e) { ArrayList() } += round(e, c)
            }
        }
        if (warming) return
        val results = ENGINES.map { e ->
            val rs = got[e] ?: return@map null
            val ns = rs.map { (it.nanos - base).coerceAtLeast(0.0) }
            val (low, high) = bootstrap(ns)
            val all = rs.flatMap { it.frameNanos.asList() }.map { it.toDouble() }.sorted()
            Measured(
                e,
                nanos = median(ns),
                bytes = median(rs.map { it.bytes }),
                low = low,
                high = high,
                requested = median(rs.map { it.requested.toDouble() }) / c.frames,
                p = Percentiles(all.average(), pct(all, 0.5), pct(all, 0.9), pct(all, 0.95), pct(all, 0.99), all.last()),
            )
        }
        rows += Row(c, results)
        val v4 = results[0]
        println("${c.name} [${c.mode.label}]: " + results.filterNotNull().joinToString { "${it.e.name} ${"%.2f".format(it.nanos / 1000)} µs" } + "  (Fuseline 4 ${v4?.let { "%.0f".format(it.bytes) }} B)")
    }

    private fun median(xs: List<Double>): Double = xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun pct(sorted: List<Double>, q: Double): Double = sorted[((sorted.size - 1) * q).toInt()]

    /** A 95% interval for the median, by resampling the rounds (seeded, so a report can be reproduced). */
    private fun bootstrap(xs: List<Double>): Pair<Double, Double> {
        val rnd = Random(7)
        val meds = DoubleArray(2000) { median(List(xs.size) { xs[rnd.nextInt(xs.size)] }) }.sorted()
        return meds[(meds.size * 0.025).toInt()] to meds[(meds.size * 0.975).toInt().coerceAtMost(meds.size - 1)]
    }

    // ------------------------------------------------------------------ the cases

    private val every60: (Int, Long) -> Long = { _, t -> t + 16_666_667L }
    private fun hz(rate: Double): (Int, Long) -> Long = { _, t -> t + (1e9 / rate).toLong() }

    private fun add(
        name: String,
        group: Group,
        modes: List<Mode> = listOf(Mode.DRAWN, Mode.READ, Mode.UNREAD),
        frames: Int = FRAMES,
        next: (Int, Long) -> Long = every60,
        lead: Int = 0,
        supports: (Engine) -> Boolean = { true },
        build: (Engine, CoroutineScope) -> Run,
    ) {
        for (m in modes) case(Case(if (modes.size > 1) "$name, ${m.label}" else name, group, m, frames, next, lead, supports, build))
    }

    /** [n] float values, each started under [spec] toward [target]. */
    private fun floats(n: Int, spec: Spec, target: Float): (Engine, CoroutineScope) -> Run = { e, s ->
        val m = e.motion(spec)
        val vs = Array(n) { e.float(0f) }
        for (v in vs) e.animate(s, v, target, m)
        Run(n, { i -> e.read(vs[i]) })
    }

    private fun values() {
        for (n in listOf(1, 100, 1000)) {
            val frames = if (n == 1) SMALL else FRAMES
            add("$n tweens", Group.VALUES, frames = frames, build = floats(n, Spec.Tween(LONG, CurveId.STANDARD), 1f))
            add("$n springs", Group.VALUES, frames = frames, build = floats(n, Spec.Spring(0.15f, 2f), 1000f))
            add("$n vector springs", Group.VALUES, frames = frames) { e, s ->
                val m = e.motion(Spec.Spring(0.15f, 2f))
                val vs = Array(n) { e.offset(0f, 0f) }
                for (v in vs) e.animateOffset(s, v, 1000f, 500f, m)
                Run(n, { i -> e.readOffset(vs[i]) })
            }
            add("$n colours", Group.VALUES, frames = frames) { e, s ->
                val m = e.motion(Spec.Tween(LONG, CurveId.LINEAR))
                val vs = Array(n) { e.color(Color.Red) }
                for (v in vs) e.animateColor(s, v, Color.Blue, m)
                Run(n, { i -> e.readColor(vs[i]) })
            }
        }
    }

    private fun scale() {
        // Ten thousand values: the shortest round that still keeps a few hundred frames.
        add("10000 springs", Group.SCALE, frames = 120, build = floats(10_000, Spec.Spring(0.15f, 2f), 1000f))
        // A thousand springs that share nothing: each its own stiffness and damping, started a frame apart.
        add("1000 unrelated springs, staggered", Group.SCALE) { e, s ->
            val rnd = Random(11)
            val ms = Array(1000) { e.motion(Spec.Spring(0.3f + rnd.nextFloat() * 0.7f, 20f + rnd.nextFloat() * 400f)) }
            val vs = Array(1000) { e.float(0f) }
            Run(1000, { i -> e.read(vs[i]) }) { f, _ -> if (f < 1000) { var i = f; while (i < 1000) { e.animate(s, vs[i], 1000f + i, ms[i]); i += 1000 } } }
        }
        // A thousand springs well into their long tails: the motion is long since invisible but has not ended.
        add("1000 springs in their tails", Group.SCALE, lead = 120, build = floats(1000, Spec.Spring(1f, 900f), 1f))
        // A third each of tweens, bouncy springs and linear tweens: mixed motion, nothing shared between kinds.
        add("1000 values, mixed motions", Group.SCALE) { e, s ->
            val a = e.motion(Spec.Tween(LONG, CurveId.STANDARD))
            val b = e.motion(Spec.Spring(0.6f, 40f))
            val c = e.motion(Spec.Tween(LONG / 2, CurveId.LINEAR))
            val vs = Array(1000) { e.float(0f) }
            for ((i, v) in vs.withIndex()) e.animate(s, v, 500f, when (i % 3) { 0 -> a; 1 -> b; else -> c })
            Run(1000, { i -> e.read(vs[i]) })
        }
    }

    private fun dormancy() {
        // The values move unseen for 300 frames (five seconds), then are drawn every frame: what it costs to see them again.
        case(Case("1000 springs seen again after 5 s unseen", Group.SCALE, Mode.DRAWN, FRAMES, every60, 300, { true }) { e, s ->
            val m = e.motion(Spec.Spring(0.15f, 2f))
            val vs = Array(1000) { e.float(0f) }
            for (v in vs) e.animate(s, v, 1000f, m)
            Run(1000, { i -> e.read(vs[i]) })
        })
    }

    private fun retargeting() {
        add("100 springs retargeted once", Group.RETARGET) { e, s ->
            val m = e.motion(Spec.Spring(0.8f, 30f))
            val vs = Array(100) { e.float(0f) }
            for (v in vs) e.animate(s, v, 1000f, m)
            Run(100, { i -> e.read(vs[i]) }) { f, _ -> if (f == 60) for (v in vs) e.retarget(s, v, -500f, m) }
        }
        for (n in listOf(100, 1000)) add("$n springs retargeted every frame", Group.RETARGET) { e, s ->
            val m = e.motion(Spec.Spring(0.8f, 300f))
            val vs = Array(n) { e.float(0f) }
            for (v in vs) e.animate(s, v, 0.5f, m)
            Run(n, { i -> e.read(vs[i]) }) { f, _ -> for (v in vs) e.retarget(s, v, f * 10f, m) }
        }
        for (n in listOf(100, 1000)) add("$n vector springs retargeted every frame", Group.RETARGET) { e, s ->
            val m = e.motion(Spec.Spring(0.8f, 300f))
            val vs = Array(n) { e.offset(0f, 0f) }
            for (v in vs) e.animateOffset(s, v, 1f, 1f, m)
            Run(n, { i -> e.readOffset(vs[i]) }) { f, _ -> for (v in vs) e.retargetXY(s, v, f * 10f, f * 5f, m) }
        }
        val times = LongArray(1000) { it * 300_000L }
        add("tween velocity, 1000 readings", Group.RETARGET, modes = listOf(Mode.READ), frames = SMALL, supports = { it.tweenVelocity }) { e, _ ->
            val t = e.tweenVelocity(300, 500f)
            var sink = 0f
            Run(1, { sink }) { _, _ -> for (x in times) sink += t.at(x) }
        }
        for (n in listOf(1, 100)) add("$n decays", Group.RETARGET, frames = if (n == 1) SMALL else FRAMES, supports = { it.decays }) { e, s ->
            val vs = Array(n) { e.float(0f) }
            for (v in vs) e.decay(s, v, 2000f, 4.2f * DRAG, 0.1f / (4.2f * DRAG))
            Run(n, { i -> e.read(vs[i]) })
        }
        add("100 values tracking a gesture", Group.RETARGET, supports = { it.drags }) { e, s ->
            val vs = Array(100) { e.float(0f) }
            Run(100, { i -> e.read(vs[i]) }) { f, t -> for (v in vs) e.dragBy(s, v, 3f, t); if (f % 120 == 119) for (v in vs) e.releaseVelocity(v, t) }
        }
    }

    private fun choreography() {
        fun tabs(every: Int, pattern: (Int) -> Int): (Engine, CoroutineScope) -> Run = { e, s ->
            val t = e.tabs(s)
            Run(1, { t.read() }) { f, _ -> if (f % every == 0) t.go(pattern(f / every)) }
        }
        add("tab transitions", Group.CHOREOGRAPHY, frames = SMALL, build = tabs(30) { (it % 4) + 1 })
        add("tab changes every 3 frames", Group.CHOREOGRAPHY, frames = SMALL, build = tabs(3) { (it % 4) + 1 })
        add("tab reversal every 4 frames", Group.CHOREOGRAPHY, frames = SMALL, build = tabs(4) { it % 2 })
        val names = List(20) { "t$it" }
        add("timeline, 20 tracks", Group.CHOREOGRAPHY, modes = listOf(Mode.READ), frames = SMALL) { e, s ->
            val p = e.player(names)!!
            if (e === ComposeEngine) ComposeEngine.startTimeline(s)
            Run(1, { p.read() }) { _, t -> p.tick(t) }
        }
        add("timeline seek, 20 tracks", Group.CHOREOGRAPHY, modes = listOf(Mode.READ), frames = SMALL, supports = { it.seeks }) { e, _ ->
            val p = e.player(names)!!
            Run(1, { p.read() }) { f, _ -> p.seek((f * 37) % 4000) }
        }
        add("timeline reverse, 20 tracks", Group.CHOREOGRAPHY, modes = listOf(Mode.READ), frames = SMALL, supports = { it.reverses }) { e, _ ->
            val p = e.player(names)!!
            Run(1, { p.read() }) { f, t -> if (f % 30 == 0) p.reverse(); p.tick(t) }
        }
    }

    private fun scheduling() {
        // Short motions starting and ending all the time: a tenth of [n] new 100 ms tweens every frame.
        for (n in listOf(10, 100, 1000)) {
            val per = maxOf(1, n / 10)
            add("driver churn, $n", Group.SCHEDULING, modes = listOf(Mode.UNREAD)) { e, s ->
                val m = e.motion(Spec.Tween(100, CurveId.STANDARD))
                Run(0, null) { _, _ -> repeat(per) { e.animate(s, e.float(0f), 1f, m) } }
            }
        }
        // A thousand springs started together every 20 frames, and every one of them cancelled 10 frames later.
        add("1000 springs started and cancelled", Group.SCHEDULING, modes = listOf(Mode.UNREAD)) { e, s ->
            val m = e.motion(Spec.Spring(1f, 300f))
            var batch: CoroutineScope? = null
            Run(0, null) { f, _ ->
                when (f % 20) {
                    0 -> {
                        val b = CoroutineScope(s.coroutineContext + Job(s.coroutineContext[Job]))
                        batch = b
                        repeat(1000) { e.animate(b, e.float(0f), 100f, m) }
                    }
                    10 -> batch?.cancel()
                }
            }
        }
        // A thousand springs retargeted to the same place every frame for a second, then left to land.
        add("1000 springs created, run and dropped", Group.SCHEDULING, modes = listOf(Mode.DRAWN)) { e, s ->
            val m = e.motion(Spec.Spring(1f, 900f))
            val vs = Array(1000) { e.float(0f) }
            Run(1000, { i -> e.read(vs[i]) }) { f, _ ->
                if (f % 60 == 0) for (i in vs.indices) { vs[i] = e.float(0f); e.animate(s, vs[i], 10f, m) }
            }
        }
        add("idle, 1000 settled values", Group.SCHEDULING, modes = listOf(Mode.DRAWN)) { e, s ->
            val vs = Array(1000) { e.float(0f) }
            for (v in vs) e.snap(s, v, 1f)
            Run(1000, { i -> e.read(vs[i]) })
        }
    }

    private fun refreshRates() {
        for (rate in listOf(30, 40, 45, 50, 60, 72, 75, 90, 100, 120, 125, 144, 165, 240)) {
            add("1000 springs at $rate Hz", Group.REFRESH, modes = listOf(Mode.DRAWN), frames = rate * 2, next = hz(rate.toDouble()), build = floats(1000, Spec.Spring(0.15f, 2f), 1000f))
        }
        // Irregular frames: the same thousand springs, under each kind of trouble a display or a busy device makes.
        val jitter = Random(3)
        val jitters = LongArray(4096) { (8_333_333L * (0.7 + jitter.nextDouble() * 0.9)).toLong() }
        add("1000 springs, jittered 120 Hz", Group.REFRESH, modes = listOf(Mode.DRAWN), next = { f, t -> t + jitters[f % jitters.size] }, build = floats(1000, Spec.Spring(0.15f, 2f), 1000f))
        add("1000 springs, dropped frames", Group.REFRESH, modes = listOf(Mode.DRAWN), next = { f, t -> t + if (f % 7 == 6) 4 * 16_666_667L else 16_666_667L }, build = floats(1000, Spec.Spring(0.15f, 2f), 1000f))
        add("1000 springs, a 500 ms stall every 2 s", Group.REFRESH, modes = listOf(Mode.DRAWN), next = { f, t -> t + if (f % 120 == 119) 500_000_000L else 16_666_667L }, build = floats(1000, Spec.Spring(0.15f, 2f), 1000f))
        add("1000 springs, 60, 120 and 30 Hz in turn", Group.REFRESH, modes = listOf(Mode.DRAWN), next = { f, t -> t + when ((f / 100) % 3) { 0 -> 16_666_667L; 1 -> 8_333_333L; else -> 33_333_333L } }, build = floats(1000, Spec.Spring(0.15f, 2f), 1000f))
    }

    private fun sustained() {
        // Ten seconds of a busy screen: 200 values following a selection that moves every 20 frames,
        // 100 colours fading in and out, the tabs changing every second, and all of it drawn.
        add("a busy screen for 10 s", Group.SUSTAINED, modes = listOf(Mode.DRAWN), frames = 600) { e, s ->
            val follow = e.motion(Spec.Spring(0.82f, 900f))
            val fade = e.motion(Spec.Tween(220, CurveId.STANDARD))
            val fs = Array(200) { e.float(0f) }
            val cs = Array(100) { e.color(Color.Black) }
            val tabs = e.tabs(s)
            for (v in fs) e.animate(s, v, 0f, follow)
            Run(301, { i -> if (i < 200) e.read(fs[i]) else if (i < 300) e.readColor(cs[i - 200]) else tabs.read() }) { f, _ ->
                if (f % 20 == 0) for ((i, v) in fs.withIndex()) e.retarget(s, v, ((f / 20 + i) % 7) * 40f, follow)
                if (f % 45 == 0) for (c in cs) e.animateColor(s, c, if ((f / 45) % 2 == 0) Color.White else Color.Black, fade)
                if (f % 60 == 0) tabs.go((f / 60) % 5)
            }
        }
    }

    // ------------------------------------------------------------------ the run

    @Test
    fun theWholeMatrix() {
        if (!enabled) return
        // The first cases would otherwise run while the JVM still compiles the harness and every
        // engine's common paths: one pass first, thrown away.
        warming = true
        values()
        warming = false
        values()
        scale()
        dormancy()
        retargeting()
        choreography()
        scheduling()
        refreshRates()
        sustained()
        report()
    }

    // ------------------------------------------------------------------ the tables

    private fun us(m: Measured?): String = when {
        m == null -> "n/a"
        m.nanos < TIME_FLOOR -> "< 0.05 µs"
        else -> "%.2f µs".format(m.nanos / 1000)
    }

    private fun bytes(m: Measured?): String = m?.let { "%.0f B".format(it.bytes) } ?: "n/a"

    /** Who is first by time, honestly: a whole interval below every other's, or "unresolved". */
    private fun winner(r: Row): String {
        val ms = r.results.filterNotNull()
        if (ms.size < 2) return "only ${ms.firstOrNull()?.e?.name}"
        if (ms.all { it.high < TIME_FLOOR }) return "no work (all below 0.05 µs)"
        val best = ms.minBy { it.nanos }
        val rest = ms.filter { it !== best }
        val clear = rest.all { best.high < it.low }
        return when {
            clear -> best.e.name
            else -> "unresolved (${best.e.name} ahead, intervals overlap)"
        }
    }

    private fun memoryWinner(r: Row): String {
        val ms = r.results.filterNotNull()
        val least = ms.minOf { it.bytes }
        val at = ms.filter { it.bytes <= least + 0.5 }
        return if (at.size == 1) at[0].e.name else "tie: " + at.joinToString { it.e.name }
    }

    private fun reduction(r: Row): Pair<String, String> {
        val v4 = r.results[0] ?: return "n/a" to "n/a"
        val v31 = r.results[1] ?: return "n/a" to "n/a"
        if (v31.nanos < TIME_FLOOR) return "both below floor" to "n/a"
        if (v4.nanos < TIME_FLOOR) return "> %.1f%%".format(100 * (1 - TIME_FLOOR / v31.nanos)) to "> %.0f×".format(v31.nanos / TIME_FLOOR)
        return "%.1f%%".format(100 * (1 - v4.nanos / v31.nanos)) to "%.2f×".format(v31.nanos / v4.nanos)
    }

    private fun report() {
        val names = ENGINES.map { it.name }
        val text = buildString {
            appendLine("# Fuseline benchmark")
            appendLine()
            for (g in Group.values()) {
                val rs = rows.filter { it.case.group == g }
                if (rs.isEmpty()) continue
                appendLine("## ${g.title}: time per frame")
                appendLine()
                appendLine("| Test | ${names.joinToString(" | ")} | Reduction vs 3.1 | Speedup vs 3.1 | Winner |")
                appendLine("|---|${names.joinToString("") { "---|" }}---|---|---|")
                for (r in rs) {
                    val (red, up) = reduction(r)
                    appendLine("| ${r.case.name} | ${r.results.joinToString(" | ") { us(it) }} | $red | $up | ${winner(r)} |")
                }
                appendLine()
            }
            appendLine("## Allocations per frame")
            appendLine()
            appendLine("| Test | ${names.joinToString(" | ")} | Least |")
            appendLine("|---|${names.joinToString("") { "---|" }}---|")
            for (r in rows) appendLine("| ${r.case.name} | ${r.results.joinToString(" | ") { bytes(it) }} | ${memoryWinner(r)} |")
            appendLine()
            appendLine("## Frame distribution, Fuseline 4 and Fuseline 3.1 (µs per frame, harness included)")
            appendLine()
            appendLine("| Test | 4 mean | 4 p50 | 4 p90 | 4 p95 | 4 p99 | 4 worst | 3.1 mean | 3.1 p50 | 3.1 p90 | 3.1 p95 | 3.1 p99 | 3.1 worst |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            fun ps(m: Measured?) = m?.p?.let { p -> listOf(p.mean, p.p50, p.p90, p.p95, p.p99, p.worst).joinToString(" | ") { "%.2f".format(it / 1000) } } ?: "n/a | n/a | n/a | n/a | n/a | n/a"
            for (r in rows) appendLine("| ${r.case.name} | ${ps(r.results[0])} | ${ps(r.results[1])} |")
            appendLine()
            appendLine("## Frames requested (share of measured frames after which the engine asked for another)")
            appendLine()
            appendLine("| Test | ${names.joinToString(" | ")} |")
            appendLine("|---|${names.joinToString("") { "---|" }}")
            for (r in rows) appendLine("| ${r.case.name} | ${r.results.joinToString(" | ") { m -> m?.let { "%.0f%%".format(it.requested * 100) } ?: "n/a" }} |")
            appendLine()
            val v4first = rows.count { winner(it) == Fuseline4.name }
            val unresolved = rows.filter { winner(it).startsWith("unresolved") }
            val lost = rows.filter { w -> val x = winner(w); x != Fuseline4.name && !x.startsWith("unresolved") && !x.startsWith("no work") && !x.startsWith("only") }
            appendLine("Rows: ${rows.size}. Fuseline 4 first: $v4first. Unresolved: ${unresolved.size}${if (unresolved.isEmpty()) "" else " (" + unresolved.joinToString { it.case.name } + ")"}. Another engine first: ${lost.size}${if (lost.isEmpty()) "" else " (" + lost.joinToString { "${it.case.name}: ${winner(it)}" } + ")"}.")
            appendLine()
            appendLine("""
                Method: one thread; a fresh manual frame clock and an inline scope for every round, so
                only the engines' own work is timed. Every engine gets the same values, motions, targets,
                frame times and reads (each case is written once, against one interface). $WARM warm-up
                rounds per engine, then $rounds rounds in rotating order. A round's cost is its mean time
                per frame less the harness's own cost with no engine (measured the same way); the median
                round counts. "Winner" needs the winner's whole 95% bootstrap interval (2000 resamples of
                the rounds) below every other engine's; otherwise the row is "unresolved". Below 0.05 µs a
                frame is below what this harness can measure. Percentiles are over every measured frame,
                harness included. "drawn" rows re-read a value only when what it read changed (each
                reader's reads recorded and indexed by state, changes applied after every frame, as
                Compose does before drawing); "read" rows read every value every frame; "unread" rows read
                nothing. Fuseline 3.1 (0.3.7.5), 3 (0.3.7), 2 (0.3.6) and 1 (0.2.7) are those engines
                kept unchanged in the tests.
                JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} processors, ${System.getProperty("os.name")} ${System.getProperty("os.arch")}.
            """.trimIndent())
        }
        println(text)
        File("build/motion-bench.md").apply { parentFile.mkdirs() }.writeText(text)
        val notFirst = rows.filter { winner(it) != Fuseline4.name && !winner(it).startsWith("no work") && !winner(it).startsWith("only") }
        assertTrue(notFirst.isEmpty() || System.getProperty("fuse.bench.lenient").toBoolean(), "Fuseline 4 is not clearly first in: ${notFirst.map { it.case.name }}")
    }

    private companion object {
        const val WARM = 3
        const val TIME_FLOOR = 50.0
        const val LONG = 60_000
        const val FRAMES = 600
        const val SMALL = 3000
        const val DRAG = 0.05f
    }
}
