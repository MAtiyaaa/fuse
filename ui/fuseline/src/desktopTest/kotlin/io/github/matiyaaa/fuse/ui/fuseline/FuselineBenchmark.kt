package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import androidx.compose.animation.core.spring as composeSpring
import androidx.compose.animation.core.tween as composeTween

/**
 * Fuseline by Fuse against Compose's own animation engine, under the same conditions: one thread,
 * one manual frame clock, the same number of values and frames. It measures the time and memory a
 * frame costs once every value is under way (after warm-up rounds, the median of several).
 *
 * Run with `./gradlew :ui:fuseline:desktopTest --tests '*FuselineBenchmark*' -Pfuse.bench=true`;
 * results go to `ui/fuseline/build/fuseline-bench.txt`. Without the property it does nothing, so
 * ordinary test runs stay quick and never depend on the machine's speed.
 */
class FuselineBenchmark {
    private val enabled = System.getProperty("fuse.bench").orEmpty().toBoolean()

    private class Result(val nanosPerFrame: Double, val bytesPerFrame: Double)

    /** Runs [start] for [count] values, then [frames] frames, [rounds] times; the median round counts. */
    private fun measure(count: Int, frames: Int = 120, rounds: Int = 9, perFrame: (CoroutineScope.(Int) -> Unit)? = null, start: CoroutineScope.(Int) -> Unit): Result {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val times = ArrayList<Double>()
        val bytes = ArrayList<Double>()
        repeat(rounds + WARM) { round ->
            val clock = BroadcastFrameClock()
            // Unconfined: every move runs inline as its frame arrives, so only the engines' own work is timed.
            val scope = CoroutineScope(Dispatchers.Unconfined + clock + Job())
            repeat(count) { scope.start(it) }
            // The first frame starts every value; it is not counted.
            var t = 1_000_000_000L
            clock.sendFrame(t)
            var spent = 0L
            val before = bean.getThreadAllocatedBytes(thread)
            for (f in 0 until frames) {
                t += 16_666_667L
                val t0 = System.nanoTime()
                perFrame?.invoke(scope, f)
                clock.sendFrame(t)
                    spent += System.nanoTime() - t0
            }
            val allocated = bean.getThreadAllocatedBytes(thread) - before
            scope.cancel()
            // The first rounds warm the JIT up.
            if (round >= WARM) {
                times += spent.toDouble() / frames
                bytes += allocated.toDouble() / frames
            }
        }
        return Result(times.sorted()[times.size / 2], bytes.sorted()[bytes.size / 2])
    }

    private companion object {
        /** Rounds run before measuring, for the JIT. */
        const val WARM = 5
    }

    private val lines = ArrayList<String>()
    private val ratios = ArrayList<Pair<String, Double>>()
    private val v2lines = ArrayList<String>()
    private val speedups = ArrayList<Pair<String, Double>>()

    /** Both engines, each measured twice in turn so neither gains from going second; the better of each counts. */
    private fun compare(case: String, fuselineRun: () -> Result, composeRun: () -> Result) {
        val c1 = composeRun()
        val f1 = fuselineRun()
        val c2 = composeRun()
        val f2 = fuselineRun()
        val fuseline = if (f1.nanosPerFrame <= f2.nanosPerFrame) f1 else f2
        val compose = if (c1.nanosPerFrame <= c2.nanosPerFrame) c1 else c2
        val ratio = fuseline.nanosPerFrame / compose.nanosPerFrame
        ratios += case to ratio
        lines += String.format(
            "| %s | %.1f us | %.1f us | %.2fx | %.0f B | %.0f B |",
            case, fuseline.nanosPerFrame / 1000, compose.nanosPerFrame / 1000, ratio, fuseline.bytesPerFrame, compose.bytesPerFrame,
        )
    }

    @Test
    fun fuselineAgainstCompose() {
        if (!enabled) return
        val long = 60_000 // Long enough that every value is still moving in every measured frame.
        // Both engines warmed up first, so the JIT has compiled each before anything is measured.
        repeat(3) {
            measure(200) { launch { FuselineValue(0f).animateTo(1f, tween(long, easing = Curves.Standard)) }; launch { FuselineValue(0f).animateTo(1f, spring(0.5f, 50f)) } }
            measure(200) { launch { Animatable(0f).animateTo(1f, composeTween(long)) }; launch { Animatable(0f).animateTo(1f, composeSpring(0.5f, 50f)) } }
        }
        for (n in listOf(1, 100, 1000)) {
            compare(
                "$n tweens",
                { measure(n) { launch { FuselineValue(0f).animateTo(1f, tween(long, easing = Curves.Standard)) } } },
                { measure(n) { launch { Animatable(0f).animateTo(1f, composeTween(long, easing = androidx.compose.animation.core.FastOutSlowInEasing)) } } },
            )
            compare(
                "$n springs",
                { measure(n) { launch { FuselineValue(0f).animateTo(1000f, spring(0.15f, 2f)) } } },
                { measure(n) { launch { Animatable(0f).animateTo(1000f, composeSpring(0.15f, 2f)) } } },
            )
        }
        // A spring given a new target every frame (a value following a finger or a scroll).
        run {
            val fl = List(100) { FuselineValue(0f) }
            val co = List(100) { Animatable(0f) }
            compare(
                "100 springs retargeted every frame",
                { measure(0, perFrame = { f -> fl.forEach { v -> launch { v.animateTo(f * 10f, spring(0.8f, 300f)) } } }) {} },
                { measure(0, perFrame = { f -> co.forEach { v -> launch { v.animateTo(f * 10f, composeSpring(0.8f, 300f)) } } }) {} },
            )
        }
        // Fuseline 2: the same retargeting, in place (FuselineValue.retarget), against Fuseline 1's
        // way (a new move per target) and Compose's.
        for (n in listOf(100, 1000)) {
            val one = List(n) { FuselineValue(0f) }
            val two = List(n) { FuselineValue(0f) }
            val co = List(n) { Animatable(0f) }
            val s = spring(0.8f, 300f)
            val r1 = measure(0, perFrame = { f -> one.forEach { v -> launch { v.animateTo(f * 10f, s) } } }) {}
            val r2 = measure(0, perFrame = { f -> two.forEach { v -> if (!v.retarget(f * 10f, s)) launch { v.animateTo(f * 10f, s) } } }) {}
            val rc = measure(0, perFrame = { f -> co.forEach { v -> launch { v.animateTo(f * 10f, composeSpring(0.8f, 300f)) } } }) {}
            // Each measured again in turn, the better of each kept, as [compare] does.
            val r1b = measure(0, perFrame = { f -> one.forEach { v -> launch { v.animateTo(f * 10f, s) } } }) {}
            val r2b = measure(0, perFrame = { f -> two.forEach { v -> if (!v.retarget(f * 10f, s)) launch { v.animateTo(f * 10f, s) } } }) {}
            val best1 = if (r1.nanosPerFrame <= r1b.nanosPerFrame) r1 else r1b
            val best2 = if (r2.nanosPerFrame <= r2b.nanosPerFrame) r2 else r2b
            val speedup = best1.nanosPerFrame / best2.nanosPerFrame
            speedups += "$n springs retargeted every frame" to speedup
            v2lines += String.format(
                "| %d springs retargeted every frame | %.1f us | %.1f us | %.1f us | %.1fx | %.0f B | %.0f B |",
                n, best2.nanosPerFrame / 1000, best1.nanosPerFrame / 1000, rc.nanosPerFrame / 1000, speedup, best2.bytesPerFrame, best1.bytesPerFrame,
            )
        }
        compare(
            "100 colour fades",
            { measure(100) { launch { FuselineValue(Color.Red).animateTo(Color.Blue, tween(long, easing = Curves.Linear)) } } },
            { measure(100) { launch { Animatable(Color.Red).animateTo(Color.Blue, composeTween(long, easing = LinearEasing)) } } },
        )
        val report = buildString {
            appendLine("| Case | Fuseline | Compose | Fuseline / Compose | Fuseline memory | Compose memory |")
            appendLine("|---|---|---|---|---|---|")
            lines.forEach { appendLine(it) }
            appendLine()
            appendLine("| Fuseline 2 case | Fuseline 2 | Fuseline 1 | Compose | Fuseline 2 speed-up | Fuseline 2 memory | Fuseline 1 memory |")
            appendLine("|---|---|---|---|---|---|---|")
            v2lines.forEach { appendLine(it) }
        }
        println(report)
        File("build/fuseline-bench.txt").apply { parentFile.mkdirs() }.writeText(report)
        // Fuseline is at least as fast in every case (a little slack for a busy machine).
        for ((case, ratio) in ratios) assertTrue(ratio <= 1.1, "$case: Fuseline took ${"%.2f".format(ratio)}x Compose's time")
        // Retargeting in place is much faster than a new move per target (the reason it exists).
        for ((case, s) in speedups) assertTrue(s >= 4.0, "$case: Fuseline 2 only ${"%.1f".format(s)}x Fuseline 1")
    }
}
