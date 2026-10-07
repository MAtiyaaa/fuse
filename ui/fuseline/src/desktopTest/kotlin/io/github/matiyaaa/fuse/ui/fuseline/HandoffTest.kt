package io.github.matiyaaa.fuse.ui.fuseline

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Motion never breaks continuity. When one motion takes over from another (a tween redirected, a
 * spring retargeted, a fling settling), the new one starts exactly where the old one was and exactly
 * as fast: here every handoff the engine makes is checked, at fuzzed moments, and handed over again
 * and again. Velocities are checked against the exact formula, never against nearby samples.
 */
class HandoffTest {
    private val motions: List<Pair<String, Motion>> = listOf(
        "tween" to Tween(300, curve = Curves.Standard),
        "tween enter" to Tween(220, curve = Curves.Enter),
        "tween linear" to Tween(400, curve = Curves.Linear),
        "spring" to Spring(0.8f, 600f),
        "bouncy spring" to Spring(0.3f, 300f),
        "critical spring" to Spring(1f, 900f),
        "overdamped spring" to Spring(2.5f, 400f),
        "decay" to Decay(),
    )

    /** At [t] into [from], hand over to [to] heading for [target]: same place, same speed. */
    private fun handOver(what: String, from: Track, t: Long, to: Motion, target: Float): Track {
        from.sample(t)
        val next = Track.of(to, from.sampledValue, target, from.sampledVelocity, 0.01f)
        next.sample(0)
        val scale = max(1f, abs(from.sampledValue))
        assertEquals(from.sampledValue, next.sampledValue, 1e-5f * scale, "$what: position at the handoff")
        // A decay or a spring starts with the velocity given. A tween on a gently leaving curve
        // inherits it exactly; one that leaves at a dash adds it to its dash; from rest, as drawn.
        val vs = max(1f, abs(from.sampledVelocity))
        val expected = when {
            to is Snap -> null
            to !is Tween -> from.sampledVelocity
            from.sampledVelocity == 0f -> null
            abs(to.curve.derivative(0f)) > TweenTrack.GENTLE_SLOPE -> from.sampledVelocity + (target - from.sampledValue) * to.curve.derivative(0f) / (to.durationMs / 1000f)
            else -> from.sampledVelocity
        }
        if (expected != null) assertEquals(expected, next.sampledVelocity, 2e-4f * max(vs, abs(expected)), "$what: velocity at the handoff")
        return next
    }

    @Test
    fun everyHandoffKeepsPositionAndVelocity() {
        val seed = 0xC0417L
        val rnd = Random(seed)
        for ((fromName, fromMotion) in motions) for ((toName, toMotion) in motions) repeat(60) { n ->
            val start = rnd.nextFloat() * 2000f - 1000f
            val target = rnd.nextFloat() * 2000f - 1000f
            val v = rnd.nextFloat() * 6000f - 3000f
            val from = Track.of(fromMotion, start, target, v, 0.01f)
            val end = from.durationNanos.coerceAtMost(2_000_000_000L)
            val t = (rnd.nextDouble() * end).toLong()
            handOver("seed $seed $fromName -> $toName #$n at $t", from, t, toMotion, rnd.nextFloat() * 2000f - 1000f)
        }
    }

    @Test
    fun repeatedInterruptionsNeverJumpOrStall() {
        val seed = 0x1A7EL
        val rnd = Random(seed)
        repeat(200) { run ->
            var track = Track.of(motions[rnd.nextInt(motions.size)].second, 0f, 500f, 0f, 0.01f)
            repeat(40) { hop ->
                val (name, m) = motions[rnd.nextInt(motions.size)]
                val t = (rnd.nextDouble() * 0.1 * 1e9).toLong()
                track = handOver("seed $seed run $run hop $hop -> $name", track, t, m, rnd.nextFloat() * 2000f - 1000f)
                track.sample(0)
                assertTrue(track.sampledValue.isFinite() && track.sampledVelocity.isFinite())
            }
            // However it was pushed around, it ends where its last motion says and stops.
            val d = track.durationNanos
            assertEquals(track.endValue, track.valueAt(d))
        }
    }

    @Test
    fun tweenVelocityIsExact() {
        val seed = 0x7EE4L
        val rnd = Random(seed)
        val curves = listOf(Curves.Standard, Curves.Enter, Curves.Exit, Curves.Fade, Curves.Sweep, Curves.Linear)
        repeat(500) { n ->
            val curve = curves[n % curves.size]
            val ms = rnd.nextInt(1, 1000)
            val start = rnd.nextFloat() * 1000f - 500f
            val target = rnd.nextFloat() * 1000f - 500f
            val track = TweenTrack(Tween(ms, curve = curve, inheritVelocity = false), start, target, 0f)
            val t = (rnd.nextDouble() * ms * 1e6).toLong()
            val f = track.fraction(t)
            // distance × slope ÷ length, the slope from the reference-checked curve.
            val expect = (target - start) * curve.derivative(f) / (ms / 1000.0)
            assertEquals(expect, track.velocityAt(t).toDouble(), 1e-3 * max(1.0, abs(expect)), "seed $seed case $n")
        }
    }

    @Test
    fun tweenEdgesAreSound() {
        // Zero length: there at once, never a division by zero.
        val zero = TweenTrack(Tween(0), 0f, 10f, 50f)
        assertEquals(0L, zero.durationNanos)
        assertEquals(10f, zero.valueAt(0))
        assertEquals(0f, zero.velocityAt(0))
        // Zero distance with a speed: carries out and comes back, arriving still.
        val back = TweenTrack(Tween(300, curve = Curves.Standard), 5f, 5f, 200f)
        assertEquals(200f, back.velocityAt(0), 1e-3f)
        assertTrue(back.valueAt(50_000_000) > 5f)
        assertEquals(5f, back.valueAt(300_000_000))
        assertEquals(0f, back.velocityAt(300_000_000), 1e-3f)
        // A delayed tween waits still: no inheriting across a pause.
        val delayed = TweenTrack(Tween(200, delayMs = 100), 0f, 1f, 999f)
        assertEquals(0f, delayed.valueAt(50_000_000))
        assertEquals(0f, delayed.velocityAt(50_000_000))
        // It lands exactly where the curve does, however fast it began.
        val fast = TweenTrack(Tween(300, curve = Curves.Standard), 0f, 100f, 5000f)
        assertEquals(100f, fast.valueAt(300_000_000))
    }

    @Test
    fun decayProjectsItsOwnEnd() {
        for (v in floatArrayOf(-50_000f, -800f, -0.5f, 0f, 1e-4f, 0.5f, 800f, 50_000f)) for (friction in floatArrayOf(1f, 4.2f, 12f)) {
            val track = DecayTrack(friction, 100f, v, 0.01f)
            val projected = projectDecay(100f, v, Decay(friction), 0.01f)
            assertEquals(projected, track.valueAt(track.durationNanos), "v=$v friction=$friction")
            assertEquals(projected, track.endValue)
            // It never travels further than the asymptote v/k, and gets within the threshold of it.
            val asymptote = 100f + v / friction
            assertTrue(abs(asymptote - projected) <= 0.01f + 1e-3f * abs(asymptote), "v=$v: $projected vs $asymptote")
            // Exact speed: v e^(−kt).
            val t = track.durationNanos / 2
            assertEquals(v * kotlin.math.exp(-friction * t / 1e9).toFloat(), track.velocityAt(t), 1e-3f * max(1f, abs(v)))
            if (v == 0f || abs(v) / friction <= 0.01f) assertEquals(0L, track.durationNanos)
        }
    }

    @Test
    fun aFlingSettlesOnItsTargetWithoutLosingSpeed() {
        // Decay then spring: the spring takes over at the decay's end speed, and lands on the target.
        val fling = Track.of(Sequence(Decay(), Spring(1f, 400f)), 0f, 1200f, 4000f, 0.01f)
        val decay = DecayTrack(Decay.DEFAULT_FRICTION, 0f, 4000f, 0.01f)
        val handoff = decay.durationNanos
        assertEquals(decay.valueAt(handoff), fling.valueAt(handoff), 1e-3f)
        fling.sample(handoff - 1)
        val before = fling.sampledVelocity
        fling.sample(handoff + 1)
        assertEquals(before, fling.sampledVelocity, 1e-2f)
        assertEquals(1200f, fling.valueAt(fling.durationNanos))
    }

    @Test
    fun keyframesHitEveryKeyExactly() {
        val kf = keyframes(1000) {
            at(0, 0f)
            at(200, -0.1f, Curves.Standard) // anticipation
            at(500, 1.1f, Curves.Enter) // overshoot
            at(500, 0.9f) // a jump at the same moment: the later key wins after it
            at(1000, 1f, Curves.Linear)
        }
        val t = KeyframesTrack(kf, 100f, 200f)
        assertEquals(100f, t.valueAt(0))
        assertEquals(90f, t.valueAt(200_000_000), 1e-3f)
        assertEquals(190f, t.valueAt(500_000_000), 1e-3f)
        assertEquals(200f, t.valueAt(1_000_000_000))
        assertEquals(200f, t.endValue)
        // Between keys: along the arriving key's curve, with its exact slope.
        val mid = 750_000_000L
        assertEquals(195f, t.valueAt(mid), 1e-3f)
        assertEquals(20f, t.velocityAt(mid), 1e-2f) // 100 × 0.1 over 0.5 s, linear
        // Implied start and end keys, and a zero-length run.
        val bare = KeyframesTrack(keyframes(400) { at(200, 0.5f, Curves.Linear) }, 0f, 10f)
        assertEquals(0f, bare.valueAt(0))
        assertEquals(5f, bare.valueAt(200_000_000), 1e-4f)
        assertEquals(10f, bare.valueAt(400_000_000))
        val instant = KeyframesTrack(keyframes(0) {}, 0f, 10f)
        assertEquals(10f, instant.valueAt(0))
    }

    @Test
    fun compositionsMeanWhatTheySay() {
        // Repeat(Spring): the spring again each time; Reverse plays back with the speed turned round.
        val spring = Spring(0.5f, 300f)
        val once = Track.of(spring, 0f, 1f, 0f, 0.01f)
        val rep = Track.of(Repeating(spring, 3, RepeatMode.Reverse), 0f, 1f, 0f, 0.01f)
        assertEquals(once.durationNanos * 3, rep.durationNanos)
        val t = once.durationNanos / 3
        assertEquals(once.valueAt(t), rep.valueAt(t))
        assertEquals(once.valueAt(once.durationNanos - t), rep.valueAt(once.durationNanos + t))
        assertEquals(-once.velocityAt(once.durationNanos - t), rep.velocityAt(once.durationNanos + t), 1e-4f)
        // Delay(Spring): still for the delay, then the spring from rest.
        val delayed = Track.of(Delayed(100, spring), 0f, 1f, 50f, 0.01f)
        assertEquals(0f, delayed.valueAt(99_000_000))
        assertEquals(0f, delayed.velocityAt(99_000_000))
        assertEquals(once.valueAt(t), delayed.valueAt(100_000_000 + t))
        assertEquals(100_000_000 + once.durationNanos, delayed.durationNanos)
        // Parallel(Spring, Tween): the first component springs, the second tweens.
        val par = Parallel(spring, Tween(200, curve = Curves.Linear))
        assertEquals(once.valueAt(t), Track.of(par, 0f, 1f, 0f, 0.01f, component = 0).valueAt(t))
        assertEquals(0.5f, Track.of(par, 0f, 1f, 0f, 0.01f, component = 1).valueAt(100_000_000), 1e-4f)
        assertEquals(0.5f, Track.of(par, 0f, 1f, 0f, 0.01f, component = 3).valueAt(100_000_000), 1e-4f)
        // Sequence(Tween, Spring): the spring picks up the tween's landing exactly.
        val seq = Track.of(Sequence(Tween(100, curve = Curves.Linear), spring), 0f, 1f, 0f, 0.01f)
        assertEquals(1f, seq.valueAt(100_000_000), 1e-5f)
        assertEquals(1f, seq.valueAt(seq.durationNanos))
        // Loops never end, and only the last leg of a sequence may loop.
        assertEquals(Long.MAX_VALUE, Track.of(Sequence(spring, Repeating(Tween(100))), 0f, 1f, 0f, 0.01f).durationNanos)
        assertFailsWith<IllegalArgumentException> { Sequence(Repeating(Tween(100)), spring) }
        assertFailsWith<IllegalArgumentException> { Repeating(Repeating(Tween(100))) }
        assertFailsWith<IllegalArgumentException> { Repeating(Decay()) }
        assertFailsWith<IllegalArgumentException> { Sequence(emptyList()) }
        assertFailsWith<IllegalArgumentException> { Keyframes(100, listOf(Keyframe(200, 1f))) }
        assertFailsWith<IllegalArgumentException> { Spring(threshold = 0f) }
    }

    /**
     * The same motion read at the same moments gives the same answer however often it is asked and
     * in whatever order (forward, backward, jumping about): a track is a function of time, so any
     * refresh rate sees the same motion at the moments they share.
     */
    @Test
    fun aTrackIsAFunctionOfTimeAtEveryRefreshRate() {
        val rates = intArrayOf(30, 40, 48, 50, 60, 72, 90, 100, 120, 144, 165, 180, 240)
        for ((name, m) in motions + listOf("keyframes" to keyframes(600) { at(300, 0.8f) }, "repeat" to Repeating(Spring(0.4f, 200f), 2, RepeatMode.Reverse))) {
            val reference = Track.of(m, 0f, 300f, 900f, 0.01f)
            for (hz in rates) {
                val track = Track.of(m, 0f, 300f, 900f, 0.01f)
                val frame = 1_000_000_000L / hz
                var t = 0L
                while (t < 1_200_000_000L) {
                    track.sample(t)
                    assertEquals(reference.valueAt(t), track.sampledValue, "$name at $hz Hz, $t ns")
                    assertEquals(reference.velocityAt(t), track.sampledVelocity, "$name at $hz Hz, $t ns")
                    t += frame
                }
            }
            // Out of order: the same.
            val jumpy = Track.of(m, 0f, 300f, 900f, 0.01f)
            for (t in longArrayOf(900_000_000, 10_000_000, 500_000_000, 0, 1_100_000_000, 450_000_000)) {
                assertEquals(reference.valueAt(t), jumpy.valueAt(t), "$name seeking to $t")
            }
        }
    }
}
