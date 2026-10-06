package io.github.matiyaaa.fuse.ui.fuseline

import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Semantic motion, accessibility and policy: every intent at every motion level is pinned in a
 * snapshot (changing a tuning is a deliberate edit here); real motions under each level complete,
 * and under Reduced never overshoot or travel; the environment trims decoration only; a policy
 * changed mid-motion never jumps. And the inspector and the trace: what they record, in order,
 * and nothing at all while off.
 */
class SemanticMotionTest {
    @AfterTest
    fun off() {
        MotionTrace.enabled = false
        MotionTrace.clear()
        MotionInspector.enabled = false
        MotionInspector.clear()
    }

    private fun intents(m: FuselineMotion) = listOf(
        "focus" to m.focus(), "selection" to m.selection(), "follow" to m.follow(), "settle" to m.settle(),
        "enter" to m.enter(), "exit" to m.exit(), "dismiss" to m.dismiss(), "reveal" to m.reveal(),
        "navigation" to m.navigation(), "press" to m.press(true), "release" to m.press(false),
        "hover" to m.hover(true), "shared" to m.sharedElement(), "overscroll" to m.overscroll(),
    )

    /** The tuning of every intent at every level. Change a tuning, change this: deliberately. */
    private val snapshot = """
        REDUCED focus=Snap selection=Snap follow=Tween 72ms Standard settle=Tween 72ms Standard enter=Tween 72ms Enter exit=Tween 72ms Exit dismiss=Tween 72ms Exit reveal=Tween 72ms Enter navigation=Tween 72ms Fade press=Tween 54ms Standard release=Tween 72ms Standard hover=Tween 54ms Standard shared=Snap overscroll=Snap
        MINIMAL focus=Spring ζ=0.82 k=900 selection=Spring ζ=0.90 k=1400 follow=Spring ζ=1.00 k=600 settle=Spring ζ=1.00 k=500 enter=Tween 165ms Enter exit=Tween 113ms Exit dismiss=Spring ζ=1.00 k=600 reveal=Tween 165ms Enter navigation=Spring ζ=1.00 k=900 press=Tween 68ms Standard release=Spring ζ=0.50 k=600 hover=Tween 68ms Standard shared=Spring ζ=1.00 k=450 overscroll=Spring ζ=1.00 k=700
        STANDARD focus=Spring ζ=0.82 k=900 selection=Spring ζ=0.90 k=1400 follow=Spring ζ=1.00 k=600 settle=Spring ζ=1.00 k=500 enter=Tween 220ms Enter exit=Tween 150ms Exit dismiss=Spring ζ=1.00 k=600 reveal=Tween 220ms Enter navigation=Spring ζ=1.00 k=900 press=Tween 90ms Standard release=Spring ζ=0.50 k=600 hover=Tween 90ms Standard shared=Spring ζ=1.00 k=450 overscroll=Spring ζ=1.00 k=700
        ENHANCED focus=Spring ζ=0.82 k=900 selection=Spring ζ=0.90 k=1400 follow=Spring ζ=1.00 k=600 settle=Spring ζ=1.00 k=500 enter=Tween 220ms Enter exit=Tween 150ms Exit dismiss=Spring ζ=1.00 k=600 reveal=Tween 220ms Enter navigation=Spring ζ=1.00 k=900 press=Tween 90ms Standard release=Spring ζ=0.50 k=600 hover=Tween 90ms Standard shared=Spring ζ=1.00 k=450 overscroll=Spring ζ=1.00 k=700
    """.trimIndent()

    @Test
    fun everyIntentAtEveryLevelIsPinned() {
        val actual = MotionLevel.entries.joinToString("\n") { level ->
            level.name + " " + intents(FuselineMotion(level)).joinToString(" ") { (n, m) -> "$n=${MotionInspector.describe(m)}" }
        }
        assertEquals(snapshot, actual)
    }

    @Test
    fun underEveryLevelMotionsCompleteAndReducedNeverOvershootsOrTravels() {
        for (level in MotionLevel.entries) {
            val m = FuselineMotion(level)
            for ((name, motion) in intents(m)) {
                val c = MotionClock()
                val v = FuselineValue(0f)
                val adapted = m.adapt(motion)
                c.launch { v.animateTo(100f, adapted) }
                var peak = 0f
                repeat(400) { c.advance(16_666_667L); peak = maxOf(peak, v.value) }
                assertEquals(100f, v.value, "$level $name completes")
                if (level == MotionLevel.REDUCED) assertTrue(peak <= 100f + 1e-3f, "$level $name overshot to $peak")
                assertFalse(c.busy)
                c.close()
            }
            if (level == MotionLevel.REDUCED) {
                assertEquals(0f, m.travel(500f))
                assertFalse(m.parallax || m.ambient || m.drift || m.sweep)
                assertEquals(1f, m.focusScale)
            }
        }
        // Overshoot: removed under Reduced, gentled under Minimal, kept otherwise.
        assertEquals(1f, (FuselineMotion(MotionLevel.REDUCED).adapt(Spring(0.3f, 400f)) as Spring).dampingRatio)
        assertEquals(0.85f, (FuselineMotion(MotionLevel.MINIMAL).adapt(Spring(0.3f, 400f)) as Spring).dampingRatio)
        assertEquals(0.3f, (FuselineMotion(MotionLevel.ENHANCED).adapt(Spring(0.3f, 400f)) as Spring).dampingRatio)
    }

    @Test
    fun aReducedTransitionFadesInPlaceAndCompletes() {
        val m = FuselineMotion(MotionLevel.REDUCED)
        val c = MotionClock()
        val t = MotionTransition("Home", order = { listOf("Home", "Library").indexOf(it) })
        t.go("Library", c.scope, m.navigation())
        c.runFor(1_000_000_000L)
        assertEquals(listOf("Library"), t.parts.map { it.state })
        // Its travel is the motion's to give: zero under Reduced.
        assertEquals(0f, t.parts.single().offset(m.travel(32f)))
        c.close()
    }

    @Test
    fun theEnvironmentTrimsDecorationOnlyAndPhysicsNeverChanges() {
        val saving = FuselineMotion(MotionLevel.ENHANCED, MotionEnvironment(lowPower = true))
        val normal = FuselineMotion(MotionLevel.ENHANCED)
        assertFalse(saving.ambient || saving.sweep || saving.drift || saving.parallax)
        assertTrue(normal.ambient && normal.sweep && normal.drift && normal.parallax)
        // Interaction is untouched: the same intents, the same motions.
        assertEquals(intents(normal).map { MotionInspector.describe(it.second) }, intents(saving).map { MotionInspector.describe(it.second) })
        assertEquals(normal.focusScale, saving.focusScale)
    }

    @Test
    fun aPolicyChangedMidMotionNeverJumps() {
        val c = MotionClock()
        val v = FuselineValue(0f)
        c.launch { v.animateTo(500f, FuselineMotion(MotionLevel.ENHANCED).navigation()) }
        c.advance(16_666_667L)
        c.run(5)
        val at = v.value
        val speed = v.velocity
        // The person switches to Reduced motion now: the move takes the new motion from where it is.
        assertTrue(v.retarget(500f, FuselineMotion(MotionLevel.REDUCED).navigation()))
        assertEquals(at, v.value)
        assertEquals(speed, v.velocity)
        c.runFor(1_000_000_000L)
        assertEquals(500f, v.value)
        c.close()
    }

    // ------------------------------------------------------------------ trace and inspector

    @Test
    fun theTraceRecordsMotionInOrder() {
        MotionTrace.enabled = true
        MotionTrace.clear()
        val c = MotionClock()
        val v = FuselineValue(0f, FloatConverter.threshold)
        c.launch { v.animateTo(100f, Spring(1f, 400f)) }
        c.advance(16_666_667L); c.run(3)
        v.retarget(200f)
        v.seek(10_000_000L)
        var t = c.now
        v.dragBy(5f, t)
        repeat(5) { t += 8_000_000L; v.dragBy(5f, t) }
        c.launch { v.flingTo({ p -> p }, Decay(), Spring(1f, 400f), t) }
        c.runFor(4_000_000_000L)
        val kinds = MotionTrace.events().map { it.kind }
        assertEquals(
            listOf(
                MotionTrace.Kind.START, MotionTrace.Kind.RETARGET, MotionTrace.Kind.SEEK, MotionTrace.Kind.GESTURE_TAKEOVER,
                MotionTrace.Kind.DESTINATION, MotionTrace.Kind.RELEASE, MotionTrace.Kind.SETTLE,
            ),
            kinds,
        )
        // Transitions record their changes and reversals.
        MotionTrace.clear()
        val tr = MotionTransition("a", order = { listOf("a", "b").indexOf(it) })
        tr.go("b", c.scope)
        c.run(3)
        tr.go("a", c.scope)
        // The transition's own changes (its parts' values record their moves around them).
        val changes = setOf(MotionTrace.Kind.TRANSITION, MotionTrace.Kind.REVERSAL)
        assertEquals(listOf(MotionTrace.Kind.TRANSITION, MotionTrace.Kind.REVERSAL), MotionTrace.events().map { it.kind }.filter { it in changes })
        assertTrue(MotionTrace.events().none { it.kind == MotionTrace.Kind.GESTURE_TAKEOVER }, "no gesture took part")
        c.close()
    }

    @Test
    fun offMeansNothingIsRecordedOrWatched() {
        MotionTrace.clear()
        val c = MotionClock()
        val v = FuselineValue(0f)
        c.launch { v.animateTo(1f, Spring()) }
        c.runFor(1_000_000_000L)
        assertTrue(MotionTrace.events().isEmpty())
        assertEquals(0, MotionInspector.count)
        assertEquals(0L, MotionInspector.frameCostNanos)
        c.close()
    }

    @Test
    fun theInspectorWatchesFormatsAndLetsGo() {
        MotionInspector.enabled = true
        val c = MotionClock()
        val a = FuselineValue(0f)
        val b = FuselineValue(androidx.compose.ui.geometry.Offset.Zero)
        c.launch { a.animateTo(100f, Spring(0.8f, 300f)) }
        c.launch { b.animateTo(androidx.compose.ui.geometry.Offset(3f, -4f), Tween(400, curve = Curves.Linear)) }
        c.advance(16_666_667L); c.run(5)
        val infos = MotionInspector.snapshot()
        assertEquals(2, infos.size)
        val pos = infos.single { it.target.startsWith("(") }
        assertEquals("(3.00, -4.00)", pos.target)
        assertTrue(pos.motion == "Tween 400ms Linear")
        assertTrue(infos.single { !it.target.startsWith("(") }.motion.startsWith("Spring ζ=0.80 k=300"))
        assertTrue(pos.progress in 0.01f..0.99f)
        assertTrue(pos.history.size >= 5)
        assertTrue(MotionInspector.frameCostNanos > 0L)
        // Settled values leave the inspector.
        c.runFor(3_000_000_000L)
        c.advance(16_666_667L)
        assertEquals(0, MotionInspector.count)
        c.close()
        assertEquals("-0.50", fmt(-0.5f, 2))
        assertEquals("12.346", fmt(12.3456f, 3))
        assertTrue(abs(0f) == 0f)
    }
}
