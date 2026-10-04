package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fuseline's own math, checked against closed forms, and against the numbers Fuse's animations
 * had before Fuseline, so moving to it changed nothing anyone can see.
 */
class MotionMathTest {
    private val curves = mapOf(
        "standard" to (Curves.Standard to CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        "enter" to (Curves.Enter to CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)),
        "exit" to (Curves.Exit to CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)),
        "fade" to (Curves.Fade to CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
        "sweep" to (Curves.Sweep to CubicBezierEasing(0.45f, 0f, 0.25f, 1f)),
    )

    @Test
    fun curvesMatchTheEasingsFuseUsedBefore() {
        for ((name, pair) in curves) {
            val (ours, before) = pair
            for (i in 0..200) {
                val x = i / 200f
                assertEquals(before.transform(x), ours.transform(x), 0.002f, "$name at $x")
            }
        }
    }

    @Test
    fun curvesStartAndEndInPlace() {
        for ((_, pair) in curves) {
            assertEquals(0f, pair.first.transform(0f))
            assertEquals(1f, pair.first.transform(1f))
        }
        assertEquals(0.37f, Curves.Linear.transform(0.37f))
    }

    @Test
    fun aCriticallyDampedSpringFollowsItsClosedForm() {
        val stiffness = 400f
        val track = Track.of(Spring(1f, stiffness), 0f, 100f, 0f, 0.01f)
        val w = kotlin.math.sqrt(stiffness.toDouble())
        for (ms in listOf(10, 50, 100, 200, 400)) {
            val t = ms / 1000.0
            // x(t) = (x0 + (v0 + w x0) t) e^(-w t), x0 = -100, v0 = 0
            val expected = 100 + (-100 + (w * -100) * t) * exp(-w * t)
            assertEquals(expected.toFloat(), track.valueAt(ms * NANOS_PER_MS), 0.01f, "at $ms ms")
        }
    }

    @Test
    fun springsMatchTheSpringsFuseUsedBefore() {
        val springs = listOf(Spring(1f, 520f), Spring(0.82f, 900f), Spring(0.55f, 520f), Spring(0.5f, 600f), Spring(1.4f, 300f))
        for (s in springs) {
            val before = FloatSpringSpec(s.dampingRatio, s.stiffness, 0.01f)
            val ours = Track.of(s, 0f, 1f, 0f, 0.01f)
            for (ms in 0..600 step 16) {
                val nanos = ms * NANOS_PER_MS
                // Within the spring's own threshold: the two may call it arrived a frame apart.
                assertEquals(before.getValueFromNanos(nanos, 0f, 1f, 0f), ours.valueAt(nanos), 0.01f, "$s at $ms ms")
            }
        }
    }

    @Test
    fun aSpringRetargetedMidFlightKeepsItsSpeed() {
        val first = Track.of(Spring(1f, 520f), 0f, 1f, 0f, 0.01f)
        val at = 80 * NANOS_PER_MS
        val speed = first.velocityAt(at)
        assertTrue(speed > 0f)
        val second = Track.of(Spring(1f, 520f), first.valueAt(at), 2f, speed, 0.01f)
        // No jolt: the new move starts where and as fast as the old one was going.
        assertEquals(first.valueAt(at), second.valueAt(0), 0.0001f)
        assertEquals(speed, second.velocityAt(0), 0.01f)
    }

    @Test
    fun springsComeToRestAtTheTarget() {
        for (zeta in listOf(0.3f, 1f, 2f)) {
            val track = Track.of(Spring(zeta, 600f), 0f, 1f, 0f, 0.001f)
            assertTrue(track.durationNanos in 1..(5_000 * NANOS_PER_MS), "damping $zeta settles: ${track.durationNanos}")
            assertEquals(1f, track.valueAt(track.durationNanos))
        }
    }

    @Test
    fun tweensRepeatsAndSnaps() {
        val tween = Track.of(Tween(200, 100, Curves.Linear), 0f, 10f, 0f, 0.01f)
        assertEquals(300 * NANOS_PER_MS, tween.durationNanos)
        assertEquals(0f, tween.valueAt(50 * NANOS_PER_MS))
        assertEquals(5f, tween.valueAt(200 * NANOS_PER_MS), 0.001f)
        val loop = Track.of(Repeating(Tween(100, curve = Curves.Linear), mode = RepeatMode.Reverse), 0f, 1f, 0f, 0.01f)
        assertEquals(Long.MAX_VALUE, loop.durationNanos)
        assertEquals(0.5f, loop.valueAt(50 * NANOS_PER_MS), 0.001f)
        assertEquals(0.75f, loop.valueAt(125 * NANOS_PER_MS), 0.001f)
        val snap = Track.of(Snap(40), 0f, 1f, 0f, 0.01f)
        assertEquals(0f, snap.valueAt(39 * NANOS_PER_MS))
        assertEquals(1f, snap.valueAt(40 * NANOS_PER_MS))
    }

    @Test
    fun coloursRoundTripThroughOklab() {
        val samples = listOf(Color(0xFFFF7A59), Color(0xFF0A070B), Color(0xFFECF8F7), Color(0x803FD6C6), Color.White, Color.Black)
        for (c in samples) {
            val v = FloatArray(4)
            ColorConverter.write(c, v)
            val back = ColorConverter.read(v)
            assertTrue(abs(back.red - c.red) < 0.002f && abs(back.green - c.green) < 0.002f && abs(back.blue - c.blue) < 0.002f && abs(back.alpha - c.alpha) < 0.001f, "$c came back as $back")
        }
    }

    @Test
    fun timelinesHoldBetweenKeysAndFollowCurves() {
        val t = Timeline(1_000) {
            track("a") { at(100, 0f); at(300, 1f, Curves.Linear); at(800, 0.5f, Curves.Linear) }
        }
        assertEquals(0f, t.value("a", 0f))
        assertEquals(0.5f, t.value("a", 200f), 0.001f)
        assertEquals(1f, t.value("a", 300f))
        assertEquals(0.75f, t.value("a", 550f), 0.001f)
        assertEquals(0.5f, t.value("a", 1_000f))
        assertEquals(0f, t.value("missing", 500f))
    }
}
