package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.BroadcastFrameClock
import io.github.matiyaaa.fuse.ui.fuseline.bench.DrawModel
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Fuseline 4 against Fuseline 3.1 (frozen in `v31`), driven through the same random histories:
 * every kind of motion, retargets, seeks, gestures, takeovers, cancellations, motion-speed scales,
 * moves made from inside other moves' frames, values read and not read, and frames at every rate,
 * with jitter, drops and stalls. After every operation and every frame, everything a caller can
 * see of every value is compared: position, velocity, target, owner, progress, time played and
 * left, which moves have finished and in what order, and what a drawing reader was shown.
 *
 * Positions and velocities may differ by at most one unit in the last place of a float (Fuseline 4
 * advances shared spring solutions incrementally, in double precision, which can round the last bit
 * of a float the other way); everything else must match exactly. Run more histories with
 * `-Pfuse.fuzz.seeds=200000`.
 */
class Fuseline4EquivalenceTest {
    private val seeds = System.getProperty("fuse.fuzz.seeds")?.toIntOrNull() ?: 400
    private val firstSeed = System.getProperty("fuse.fuzz.from")?.toIntOrNull() ?: 0
    private val trailLength = System.getProperty("fuse.fuzz.trail")?.toIntOrNull() ?: 12

    /** Reader runs across every history (both engines): the drawn comparison has to have something to compare. */
    private var reads = 0L

    @Test
    fun fuseline4MovesExactlyAsFuseline31() {
        val failures = ArrayList<String>()
        for (seed in firstSeed until firstSeed + seeds) {
            val problem = runCatching { History(seed).run() }.exceptionOrNull()
            if (problem != null) {
                failures += "seed $seed: ${problem.message}"
                if (failures.size >= 5) break
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        println("Fuseline 4 equivalence: $seeds histories, $reads reader runs")
        kotlin.test.assertTrue(reads > seeds * 40L, "Too few reader runs to compare drawing: $reads")
    }

    // ------------------------------------------------------------------ a motion described once

    private sealed interface Md {
        data class Sp(val d: Float, val k: Float, val th: Float?) : Md
        data class Tw(val ms: Int, val delay: Int, val curve: Int, val inherit: Boolean) : Md
        data class Dc(val friction: Float) : Md
        data class Sn(val delay: Int) : Md
        data class Kf(val ms: Int, val keys: List<Triple<Int, Float, Int>>) : Md
        data class Rp(val inner: Md, val times: Int, val reverse: Boolean, val offset: Int) : Md
        data class Dl(val delay: Int, val inner: Md) : Md
        data class Sq(val legs: List<Md>) : Md
        data class Pl(val parts: List<Md>) : Md
    }

    private fun curve4(i: Int): Curve = listOf(Curves.Standard, Curves.Enter, Curves.Exit, Curves.Fade, Curves.Linear, Curves.Sweep)[i]
    private fun curve31(i: Int): io.github.matiyaaa.fuse.ui.fuseline.v31.Curve = listOf(io.github.matiyaaa.fuse.ui.fuseline.v31.Curves.Standard, io.github.matiyaaa.fuse.ui.fuseline.v31.Curves.Enter, io.github.matiyaaa.fuse.ui.fuseline.v31.Curves.Exit, io.github.matiyaaa.fuse.ui.fuseline.v31.Curves.Fade, io.github.matiyaaa.fuse.ui.fuseline.v31.Curves.Linear, io.github.matiyaaa.fuse.ui.fuseline.v31.Curves.Sweep)[i]

    private fun to4(m: Md): Motion = when (m) {
        is Md.Sp -> Spring(m.d, m.k, m.th)
        is Md.Tw -> Tween(m.ms, m.delay, curve4(m.curve), m.inherit)
        is Md.Dc -> Decay(m.friction)
        is Md.Sn -> Snap(m.delay)
        is Md.Kf -> Keyframes(m.ms, m.keys.map { Keyframe(it.first, it.second, curve4(it.third)) })
        is Md.Rp -> Repeating(to4(m.inner), m.times, if (m.reverse) RepeatMode.Reverse else RepeatMode.Restart, m.offset)
        is Md.Dl -> Delayed(m.delay, to4(m.inner))
        is Md.Sq -> Sequence(m.legs.map { to4(it) })
        is Md.Pl -> Parallel(m.parts.map { to4(it) })
    }

    private fun to31(m: Md): io.github.matiyaaa.fuse.ui.fuseline.v31.Motion = when (m) {
        is Md.Sp -> io.github.matiyaaa.fuse.ui.fuseline.v31.Spring(m.d, m.k, m.th)
        is Md.Tw -> io.github.matiyaaa.fuse.ui.fuseline.v31.Tween(m.ms, m.delay, curve31(m.curve), m.inherit)
        is Md.Dc -> io.github.matiyaaa.fuse.ui.fuseline.v31.Decay(m.friction)
        is Md.Sn -> io.github.matiyaaa.fuse.ui.fuseline.v31.Snap(m.delay)
        is Md.Kf -> io.github.matiyaaa.fuse.ui.fuseline.v31.Keyframes(m.ms, m.keys.map { io.github.matiyaaa.fuse.ui.fuseline.v31.Keyframe(it.first, it.second, curve31(it.third)) })
        is Md.Rp -> io.github.matiyaaa.fuse.ui.fuseline.v31.Repeating(to31(m.inner), m.times, if (m.reverse) io.github.matiyaaa.fuse.ui.fuseline.v31.RepeatMode.Reverse else io.github.matiyaaa.fuse.ui.fuseline.v31.RepeatMode.Restart, m.offset)
        is Md.Dl -> io.github.matiyaaa.fuse.ui.fuseline.v31.Delayed(m.delay, to31(m.inner))
        is Md.Sq -> io.github.matiyaaa.fuse.ui.fuseline.v31.Sequence(m.legs.map { to31(it) })
        is Md.Pl -> io.github.matiyaaa.fuse.ui.fuseline.v31.Parallel(m.parts.map { to31(it) })
    }

    // ------------------------------------------------------------------ one engine, driven by index

    /** What a caller can see of one value, for comparing. */
    private class Seen(val floats: FloatArray, val exact: LongArray, val owner: String)

    private interface Side {
        val scope: CoroutineScope
        val log: StringBuilder
        fun make(kind: Int, init: FloatArray, scale: Float)
        fun animateTo(i: Int, target: FloatArray, m: Md, velocity: FloatArray?, every: ((Int) -> Unit)?)
        fun follow(i: Int, target: FloatArray, m: Md)
        fun retarget(i: Int, target: FloatArray, m: Md?): Boolean
        fun seek(i: Int, play: Long): Boolean
        fun seekProgress(i: Int, f: Float): Boolean
        fun dragBy(i: Int, delta: FloatArray, t: Long)
        fun dragTo(i: Int, at: FloatArray, t: Long)
        fun release(i: Int, target: FloatArray, m: Md, t: Long)
        fun fling(i: Int, friction: Float, t: Long)
        fun flingTo(i: Int, t: Long)
        fun cancelDrag(i: Int)
        fun snapTo(i: Int, target: FloatArray)
        fun stop(i: Int)
        fun halt(i: Int)
        fun cancel(i: Int)
        fun seen(i: Int): Seen
        fun component(i: Int, c: Int): Float
        fun observe(i: Int, on: Boolean)
        fun frame(t: Long)

        /** What reader [i] shows on screen now (not how often it was redrawn), or null when it isn't shown. */
        fun shows(i: Int): FloatArray?
    }

    private fun color(a: FloatArray) = Color(a[0].coerceIn(0f, 1f), a[1].coerceIn(0f, 1f), a[2].coerceIn(0f, 1f), a[3].coerceIn(0f, 1f))

    /** The live engine (Fuseline 4). */
    private inner class Four : Side {
        val clock = BroadcastFrameClock()
        override val scope = CoroutineScope(Dispatchers.Unconfined + clock + Job())
        override val log = StringBuilder()
        val values = ArrayList<FuselineValue<Any>>()
        val kinds = ArrayList<Int>()
        val scales = ArrayList<Float>()
        val jobs = ArrayList<Job?>()
        val display = Array(MAX_VALUES) { FloatArray(4) }
        val draw = DrawModel(MAX_VALUES) { i -> val v = values[i]; reads++; for (c in 0 until v.converter.size) display[i][c] = v.component(c) }
        val shown = BooleanArray(MAX_VALUES)

        @Suppress("UNCHECKED_CAST")
        override fun make(kind: Int, init: FloatArray, scale: Float) {
            val v: FuselineValue<*> = when (kind) {
                0 -> FuselineValue(init[0])
                1 -> FuselineValue(Offset(init[0], init[1]))
                else -> FuselineValue(color(init))
            }
            values += v as FuselineValue<Any>
            kinds += kind
            scales += scale
            jobs += null
        }

        private fun t(i: Int, a: FloatArray): Any = when (kinds[i]) {
            0 -> a[0]
            1 -> Offset(a[0], a[1])
            else -> color(a)
        }

        private fun launch(i: Int, name: String, block: suspend () -> Unit) {
            val n = log.length
            jobs[i] = scope.launch(scaleOf(scales[i])) {
                try {
                    block()
                    log.append("[$i $name done]")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    log.append("[$i $name ended]")
                    throw e
                }
            }
            if (n < 0) println()
        }

        override fun animateTo(i: Int, target: FloatArray, m: Md, velocity: FloatArray?, every: ((Int) -> Unit)?) {
            val v = values[i]
            var calls = 0
            launch(i, "animateTo") { v.animateTo(t(i, target), to4(m), velocity?.let { t(i, it) }, every?.let { e -> { e(calls++) } }) }
        }

        override fun follow(i: Int, target: FloatArray, m: Md) {
            values[i].follow(t(i, target), to4(m), scope.coroutineContext + scaleOf(scales[i])) { log.append("[$i arrived]") }
        }

        override fun retarget(i: Int, target: FloatArray, m: Md?): Boolean = when (kinds[i]) {
            0 -> values[i].retargetFloat(target[0], m?.let { to4(it) })
            1 -> values[i].retargetXY(target[0], target[1], m?.let { to4(it) })
            else -> values[i].retarget(t(i, target), m?.let { to4(it) })
        }

        override fun seek(i: Int, play: Long) = values[i].seek(play)
        override fun seekProgress(i: Int, f: Float) = values[i].seekProgress(f)
        override fun dragBy(i: Int, delta: FloatArray, t: Long) = values[i].dragBy(if (kinds[i] == 2) color(delta) else t(i, delta), t)
        override fun dragTo(i: Int, at: FloatArray, t: Long) = values[i].dragTo(t(i, at), t)
        override fun release(i: Int, target: FloatArray, m: Md, t: Long) = launch(i, "release") { values[i].release(t(i, target), to4(m), t) }
        override fun fling(i: Int, friction: Float, t: Long) = launch(i, "fling") { values[i].fling(Decay(friction), t) }
        override fun flingTo(i: Int, t: Long) = launch(i, "flingTo") { values[i].flingTo({ it }, Decay(), Spring(1f, 500f), t) }
        override fun cancelDrag(i: Int) = values[i].cancelDrag()
        override fun snapTo(i: Int, target: FloatArray) = launch(i, "snapTo") { values[i].snapTo(t(i, target)) }
        override fun stop(i: Int) = launch(i, "stop") { values[i].stop() }
        override fun halt(i: Int) = values[i].halt()
        override fun cancel(i: Int) { jobs[i]?.cancel() }
        override fun component(i: Int, c: Int): Float = values[i].peekComponent(c)

        override fun seen(i: Int): Seen {
            val v = values[i]
            val d = v.converter.size
            val f = FloatArray(d * 3 + 1)
            for (c in 0 until d) {
                f[c] = v.peekComponent(c)
                f[d + c] = v.peekVelocityComponent(c)
                f[2 * d + c] = v.peekTargetComponent(c)
            }
            f[3 * d] = v.peekProgress()
            return Seen(f, longArrayOf(v.peekPlayNanos(), v.peekRemainingNanos()), "${v.owner} ${v.isRunning} ${v.isDragging}")
        }

        override fun observe(i: Int, on: Boolean) {
            if (on == shown[i]) return
            shown[i] = on
            if (on) draw.draw(i) else draw.hide(i)
        }

        override fun frame(t: Long) {
            log.append("{f}")
            clock.sendFrame(t)
            draw.frame()
        }

        override fun shows(i: Int): FloatArray? = if (shown[i]) display[i] else null
    }

    /** The frozen Fuseline 3.1. */
    private inner class ThreeOne : Side {
        val clock = BroadcastFrameClock()
        override val scope = CoroutineScope(Dispatchers.Unconfined + clock + Job())
        override val log = StringBuilder()
        val values = ArrayList<io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Any>>()
        val kinds = ArrayList<Int>()
        val scales = ArrayList<Float>()
        val jobs = ArrayList<Job?>()
        val display = Array(MAX_VALUES) { FloatArray(4) }
        val draw = DrawModel(MAX_VALUES) { i -> val v = values[i]; reads++; for (c in 0 until v.converter.size) display[i][c] = v.component(c) }
        val shown = BooleanArray(MAX_VALUES)

        @Suppress("UNCHECKED_CAST")
        override fun make(kind: Int, init: FloatArray, scale: Float) {
            val v: io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<*> = when (kind) {
                0 -> io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue(init[0])
                1 -> io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue(Offset(init[0], init[1]))
                else -> io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue(color(init))
            }
            values += v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Any>
            kinds += kind
            scales += scale
            jobs += null
        }

        private fun t(i: Int, a: FloatArray): Any = when (kinds[i]) {
            0 -> a[0]
            1 -> Offset(a[0], a[1])
            else -> color(a)
        }

        private fun launch(i: Int, name: String, block: suspend () -> Unit) {
            jobs[i] = scope.launch(scaleOf(scales[i])) {
                try {
                    block()
                    log.append("[$i $name done]")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    log.append("[$i $name ended]")
                    throw e
                }
            }
        }

        override fun animateTo(i: Int, target: FloatArray, m: Md, velocity: FloatArray?, every: ((Int) -> Unit)?) {
            val v = values[i]
            var calls = 0
            launch(i, "animateTo") { v.animateTo(t(i, target), to31(m), velocity?.let { t(i, it) }, every?.let { e -> { e(calls++) } }) }
        }

        override fun follow(i: Int, target: FloatArray, m: Md) {
            values[i].follow(t(i, target), to31(m), scope.coroutineContext + scaleOf(scales[i])) { log.append("[$i arrived]") }
        }

        override fun retarget(i: Int, target: FloatArray, m: Md?): Boolean = when (kinds[i]) {
            0 -> values[i].retargetFloat(target[0], m?.let { to31(it) })
            1 -> values[i].retargetXY(target[0], target[1], m?.let { to31(it) })
            else -> values[i].retarget(t(i, target), m?.let { to31(it) })
        }

        override fun seek(i: Int, play: Long) = values[i].seek(play)
        override fun seekProgress(i: Int, f: Float) = values[i].seekProgress(f)
        override fun dragBy(i: Int, delta: FloatArray, t: Long) = values[i].dragBy(if (kinds[i] == 2) color(delta) else t(i, delta), t)
        override fun dragTo(i: Int, at: FloatArray, t: Long) = values[i].dragTo(t(i, at), t)
        override fun release(i: Int, target: FloatArray, m: Md, t: Long) = launch(i, "release") { values[i].release(t(i, target), to31(m), t) }
        override fun fling(i: Int, friction: Float, t: Long) = launch(i, "fling") { values[i].fling(io.github.matiyaaa.fuse.ui.fuseline.v31.Decay(friction), t) }
        override fun flingTo(i: Int, t: Long) = launch(i, "flingTo") { values[i].flingTo({ it }, io.github.matiyaaa.fuse.ui.fuseline.v31.Decay(), io.github.matiyaaa.fuse.ui.fuseline.v31.Spring(1f, 500f), t) }
        override fun cancelDrag(i: Int) = values[i].cancelDrag()
        override fun snapTo(i: Int, target: FloatArray) = launch(i, "snapTo") { values[i].snapTo(t(i, target)) }
        override fun stop(i: Int) = launch(i, "stop") { values[i].stop() }
        override fun halt(i: Int) = values[i].halt()
        override fun cancel(i: Int) { jobs[i]?.cancel() }
        override fun component(i: Int, c: Int): Float = values[i].component(c)

        override fun seen(i: Int): Seen {
            val v = values[i]
            val d = v.converter.size
            val f = FloatArray(d * 3 + 1)
            for (c in 0 until d) {
                f[c] = v.component(c)
                f[d + c] = v.velocityComponent(c)
                f[2 * d + c] = v.targetComponent(c)
            }
            f[3 * d] = v.progress
            return Seen(f, longArrayOf(v.playNanos, v.remainingNanos), "${v.owner} ${v.isRunning} ${v.isDragging}")
        }

        override fun observe(i: Int, on: Boolean) {
            if (on == shown[i]) return
            shown[i] = on
            if (on) draw.draw(i) else draw.hide(i)
        }

        override fun frame(t: Long) {
            log.append("{f}")
            clock.sendFrame(t)
            draw.frame()
        }

        override fun shows(i: Int): FloatArray? = if (shown[i]) display[i] else null
    }

    // ------------------------------------------------------------------ one random history

    private inner class History(private val seed: Int) {
        private val rnd = Random(seed)
        private val a = Four()
        private val b = ThreeOne()
        private val sides = listOf(a, b)
        private val dims = ArrayList<Int>()
        private var time = 1_000_000_000L
        private var step = 0
        private val trail = ArrayList<String>()

        private fun motion(depth: Int = 0): Md {
            val r = rnd.nextInt(if (depth > 0) 6 else 12)
            return when (r) {
                0, 1, 2 -> Md.Sp(listOf(0.15f, 0.5f, 0.82f, 1f, 1f, 1.3f, 0.999999f, 0f).random(rnd).let { if (it == 0f && rnd.nextInt(4) != 0) 0.3f else it }, listOf(2f, 50f, 300f, 520f, 900f, 1500f, 10_000f).random(rnd), if (rnd.nextInt(5) == 0) 0.05f else null)
                3, 4 -> Md.Tw(listOf(0, 16, 90, 150, 220, 420, 800).random(rnd), if (rnd.nextInt(5) == 0) rnd.nextInt(200) else 0, rnd.nextInt(6), rnd.nextInt(4) != 0)
                5 -> Md.Sn(if (rnd.nextBoolean()) 0 else rnd.nextInt(300))
                6 -> Md.Dc(listOf(1f, 4.2f, 12f).random(rnd))
                7 -> Md.Kf(400, listOf(Triple(0, 0f, 4), Triple(rnd.nextInt(1, 399), rnd.nextFloat() * 1.4f - 0.2f, rnd.nextInt(6)), Triple(400, 1f, rnd.nextInt(6))))
                8 -> Md.Rp(Md.Tw(150 + rnd.nextInt(200), 0, rnd.nextInt(6), true), 1 + rnd.nextInt(3), rnd.nextBoolean(), rnd.nextInt(100))
                9 -> Md.Dl(rnd.nextInt(250), motion(depth + 1).let { if (it is Md.Dc) Md.Sp(1f, 500f, null) else it })
                10 -> Md.Sq(listOf(motion(depth + 1).let { if (it is Md.Dc) Md.Tw(100, 0, 0, true) else it }, Md.Sp(1f, 500f, null)))
                else -> Md.Pl(listOf(motion(depth + 1).let { if (it is Md.Dc) Md.Sp(1f, 300f, null) else it }, Md.Sp(0.7f, 400f, null)))
            }
        }

        private fun point(kind: Int): FloatArray = when (kind) {
            0 -> floatArrayOf(rnd.nextFloat() * 2000f - 1000f)
            1 -> floatArrayOf(rnd.nextFloat() * 2000f - 1000f, rnd.nextFloat() * 800f - 400f)
            else -> floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
        }

        private fun small(kind: Int): FloatArray = when (kind) {
            0 -> floatArrayOf(rnd.nextFloat() * 40f - 20f)
            1 -> floatArrayOf(rnd.nextFloat() * 40f - 20f, rnd.nextFloat() * 40f - 20f)
            else -> floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), 1f)
        }

        private fun both(what: String, op: (Side) -> Any?) {
            trail += "$step $what"
            val r = sides.map { op(it) }
            if (r[0] != r[1]) throw AssertionError("'$what' returned ${r[0]} on Fuseline 4 and ${r[1]} on Fuseline 3.1\n${trail.takeLast(trailLength).joinToString("\n")}")
            compare(what)
        }

        private fun nextFrame(): Long {
            val r = rnd.nextInt(40)
            val dt = when {
                r < 24 -> 16_666_667L
                r < 28 -> 8_333_333L
                r < 31 -> 6_944_444L
                r < 33 -> 33_333_333L
                r < 35 -> 4_166_667L
                r < 37 -> (rnd.nextDouble(2e6, 40e6)).toLong()
                r < 39 -> 16_666_667L * (2 + rnd.nextInt(4))
                else -> rnd.nextLong(100_000_000L, 1_500_000_000L)
            }
            time += dt
            return time
        }

        /** How rarely operations come: some histories are busy, most let motions play out. */
        private val calm = listOf(2, 6, 12, 24).random(rnd)

        fun run() {
            val count = 2 + rnd.nextInt(6)
            for (i in 0 until count) {
                val kind = listOf(0, 0, 0, 1, 1, 2).random(rnd)
                val init = point(kind)
                val scale = listOf(1f, 1f, 1f, 1f, 0.5f, 2f, 5f, 0f).random(rnd)
                dims += kind
                both("make $i") { it.make(kind, init, scale) }
                // Made as composition makes state: its writes count as changes from now on.
                androidx.compose.runtime.snapshots.Snapshot.notifyObjectsInitialized()
                if (rnd.nextInt(4) != 0) both("observe $i") { it.observe(i, true) }
            }
            val frames = 60 + rnd.nextInt(240)
            for (f in 0 until frames) {
                step = f
                val ops = if (rnd.nextInt(calm) == 0) 1 + rnd.nextInt(3) else 0
                repeat(ops) { op() }
                val t = nextFrame()
                trail += "$f frame at ${t - 1_000_000_000L}"
                for (s in sides) s.frame(t)
                if (System.getProperty("fuse.fuzz.debug") == "$seed") for (i in dims.indices) println("DBG f=$f t=${t - 1_000_000_000L} v$i 4: ${a.seen(i).exact.toList()} ${a.seen(i).owner} ${a.seen(i).floats.toList()} shows=${a.shows(i)?.toList()} | 3.1: ${b.seen(i).exact.toList()} ${b.seen(i).owner} ${b.seen(i).floats.toList()} shows=${b.shows(i)?.toList()}")
                compare("frame $f")
            }
            for (s in sides) s.scope.cancel()
            a.draw.dispose()
            b.draw.dispose()
        }

        private fun op() {
            val i = rnd.nextInt(dims.size)
            val kind = dims[i]
            when (rnd.nextInt(26)) {
                in 0..5 -> {
                    val target = point(kind)
                    val m = motion()
                    val velocity = if (rnd.nextInt(5) == 0) small(kind) else null
                    val every = if (rnd.nextInt(5) == 0) inCallback(i) else null
                    both("animateTo $i $m") { it.animateTo(i, target, m, velocity, every?.let { e -> { k -> e(it, k) } }) }
                }
                in 6..8 -> { val target = point(kind); val m = motion(); both("follow $i $m") { it.follow(i, target, m) } }
                in 9..11 -> { val target = point(kind); val m = if (rnd.nextBoolean()) null else motion(); both("retarget $i $m") { it.retarget(i, target, m) } }
                12 -> { val p = rnd.nextLong(0, 900_000_000L); both("seek $i $p") { it.seek(i, p) } }
                13 -> { val p = rnd.nextFloat(); both("seekProgress $i $p") { it.seekProgress(i, p) } }
                14, 15 -> {
                    val strokes = 1 + rnd.nextInt(6)
                    repeat(strokes) { k ->
                        val d = small(kind)
                        val at = time + k * 4_000_000L
                        if (kind != 2) both("dragBy $i") { it.dragBy(i, d, at) } else both("dragTo $i") { it.dragTo(i, d, at) }
                    }
                }
                16 -> { val target = point(kind); val m = motion(); val at = time + rnd.nextLong(0, 30_000_000L); both("release $i $m") { it.release(i, target, m, at) } }
                17 -> if (kind != 2) { val fr = listOf(1f, 4.2f, 9f).random(rnd); val at = time + rnd.nextLong(0, 30_000_000L); both("fling $i") { it.fling(i, fr, at) } }
                18 -> if (kind != 2) { val at = time + rnd.nextLong(0, 30_000_000L); both("flingTo $i") { it.flingTo(i, at) } }
                19 -> both("cancelDrag $i") { it.cancelDrag(i) }
                20 -> { val target = point(kind); both("snapTo $i") { it.snapTo(i, target) } }
                21 -> both("stop $i") { it.stop(i) }
                22 -> both("halt $i") { it.halt(i) }
                23 -> both("cancel $i") { it.cancel(i) }
                24 -> { val on = rnd.nextInt(4) != 0; both("observe $i $on") { it.observe(i, on) } }
                else -> {
                    // A read outside drawing (an event handler, a gesture deciding where to go).
                    both("read $i") { s -> (0 until s.seen(i).floats.size / 3).map { c -> s.component(i, c) } }
                }
            }
        }

        /** Work done from inside a move's frame: read another value, or retarget or seek it, on chosen calls. */
        private fun inCallback(self: Int): (Side, Int) -> Unit {
            val other = rnd.nextInt(dims.size)
            val on = rnd.nextInt(1, 12)
            val what = rnd.nextInt(3)
            val target = point(dims[other])
            val play = rnd.nextLong(0, 300_000_000L)
            return { s, call ->
                if (call == on) {
                    when (what) {
                        0 -> s.log.append("<$self reads $other: ${(0 until s.seen(other).floats.size / 3).joinToString { c -> s.component(other, c).toString() }}>")
                        1 -> s.log.append("<$self retargets $other: ${s.retarget(other, target, null)}>")
                        else -> s.log.append("<$self seeks $other: ${s.seek(other, play)}>")
                    }
                }
            }
        }

        private fun compare(after: String) {
            for (i in dims.indices) {
                val shownA = a.shows(i)
                val shownB = b.shows(i)
                if ((shownA == null) != (shownB == null)) mismatch(after, i, "shown on one engine only")
                if (shownA != null && shownB != null) {
                    // Exact, except for a value brought back from resting unread: it may be told of its
                    // next change a frame sooner or later than 3.1 would have. Each engine's reader then
                    // shows a value within one publishing step (an eighth of a threshold) of the motion's
                    // true place, so the two can differ by up to two steps, never more (see FuselineValue).
                    val room = if (a.values[i].woken > 0) a.values[i].converter.threshold * 0.25f else 0f
                    for (c in 0 until a.values[i].converter.size) {
                        val x = shownA[c]
                        val y = shownB[c]
                        if (!close(x, y) && kotlin.math.abs(x - y) > room * 1.0001f) mismatch(after, i, "reader shows ${x} vs ${y}${if (room > 0f) " (woken, room $room)" else ""}")
                    }
                }
                val x = a.seen(i)
                val y = b.seen(i)
                if (x.owner != y.owner) mismatch(after, i, "owner ${x.owner} vs ${y.owner}")
                for (k in x.exact.indices) if (x.exact[k] != y.exact[k]) mismatch(after, i, "time ${listOf("played", "left")[k]} ${x.exact[k]} vs ${y.exact[k]}")
                for (k in x.floats.indices) if (!close(x.floats[k], y.floats[k])) mismatch(after, i, "number $k (${describe(k, x.floats.size)}) ${x.floats[k]} vs ${y.floats[k]}")
            }
            if (!sameLog(a.log, b.log)) {
                val at = (0 until minOf(a.log.length, b.log.length)).firstOrNull { a.log[it] != b.log[it] } ?: minOf(a.log.length, b.log.length)
                throw AssertionError("after $after the logs part at $at:\n4:   ${a.log.substring(maxOf(0, at - 160), minOf(a.log.length, at + 160))}\n3.1: ${b.log.substring(maxOf(0, at - 160), minOf(b.log.length, at + 160))}\n${trail.takeLast(trailLength).joinToString("\n")}")
            }
        }

        private fun describe(k: Int, size: Int): String {
            val d = (size - 1) / 3
            return when {
                k == size - 1 -> "progress"
                k < d -> "position $k"
                k < 2 * d -> "velocity ${k - d}"
                else -> "target ${k - 2 * d}"
            }
        }

        private fun mismatch(after: String, i: Int, what: String): Nothing =
            throw AssertionError("after $after, value $i: $what\n${trail.takeLast(trailLength).joinToString("\n")}")
    }

    /** Equal, or one float step apart (see the class's notes). */
    private fun close(x: Float, y: Float): Boolean {
        if (x == y || (x.isNaN() && y.isNaN())) return true
        return kotlin.math.abs(x.toRawBits() - y.toRawBits()) <= 1 && (x >= 0f) == (y >= 0f)
    }

    /** The logs agree, numbers in them within one float step (they print readers' and callbacks' reads). */
    private fun sameLog(x: StringBuilder, y: StringBuilder): Boolean {
        if (x.length == y.length && x.toString() == y.toString()) return true
        val a = x.split(' ', '(', ')', '<', '>', ':', ',', '[', ']', '{', '}')
        val b = y.split(' ', '(', ')', '<', '>', ':', ',', '[', ']', '{', '}')
        if (a.size != b.size) return false
        for (k in a.indices) {
            if (a[k] == b[k]) continue
            val fa = a[k].toFloatOrNull() ?: return false
            val fb = b[k].toFloatOrNull() ?: return false
            if (!close(fa, fb)) return false
        }
        return true
    }
}

private const val MAX_VALUES = 8

private fun scaleOf(s: Float): MotionDurationScale = object : MotionDurationScale {
    override val scaleFactor: Float = s
}
