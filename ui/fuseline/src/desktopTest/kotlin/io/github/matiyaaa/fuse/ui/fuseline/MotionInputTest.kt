package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every kind of input hands a value to and from motion the same way. Pointer drags (touch and mouse,
 * which is also how a trackpad held down arrives) run through [dragMotion] in a real composition with
 * injected events; the stick, the D-pad and keyboard, the scroll wheel and a trackpad's scroll stream
 * run on the deterministic [MotionClock].
 */
@OptIn(ExperimentalTestApi::class)
class MotionInputTest {
    // ------------------------------------------------------------------ pointers

    @Test
    fun touchTakesMovesAndFlings() = runComposeUiTest {
        val v = FuselineValue(0f)
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(400.dp).testTag("pad").dragMotion(v, Orientation.Horizontal)) }
        onNodeWithTag("pad").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(12f, 0f), delayMillis = 8) }
            up()
        }
        mainClock.advanceTimeByFrame()
        // Moved under the finger (the touch slop aside), then flung onward at the finger's speed.
        assertTrue(v.value >= 100f, "dragged to ${v.value}")
        assertEquals(MotionOwner.ANIMATION, v.owner)
        assertTrue(v.velocity > 500f, "flung at ${v.velocity}")
        val flungTo = v.targetValue
        mainClock.advanceTimeBy(3_000)
        assertEquals(flungTo, v.value)
    }

    /**
     * A pointer the system takes away arrives as a release that is already used (a parent took it, or
     * the window lost it). Here a parent takes the release, the way the system does: the drag lets go
     * where it is, with no fling.
     */
    @Test
    fun aCancelledTouchLetsGoWhereItIs() = runComposeUiTest {
        val v = FuselineValue(0f)
        mainClock.autoAdvance = false
        setContent {
            Box(
                Modifier.size(400.dp).pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            e.changes.forEach { if (!it.pressed) it.consume() }
                        }
                    }
                },
            ) { Box(Modifier.size(400.dp).testTag("pad").dragMotion(v, Orientation.Vertical)) }
        }
        onNodeWithTag("pad").performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(0f, 20f), delayMillis = 8) }
            up()
        }
        mainClock.advanceTimeByFrame()
        assertEquals(MotionOwner.IDLE, v.owner)
        val at = v.value
        assertTrue(at >= 100f, "it had been dragged: $at")
        mainClock.advanceTimeBy(500)
        assertEquals(at, v.value, "nothing moves it after a cancel")
    }

    @Test
    fun theMouseAndATrackpadHeldDownDragTheSameWay() = runComposeUiTest {
        val v = FuselineValue(0f)
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(400.dp).testTag("pad").dragMotion(v, Orientation.Horizontal) { t -> release(0f, Spring(1f, 400f), t) }) }
        onNodeWithTag("pad").performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(-15f, 0f), delayMillis = 8) }
            release()
        }
        mainClock.advanceTimeByFrame()
        assertTrue(v.value < -100f, "dragged left to ${v.value}")
        // Released back home with its leftward velocity: it carries on left a moment, then returns.
        assertTrue(v.velocity < 0f)
        mainClock.advanceTimeBy(3_000)
        assertEquals(0f, v.value)
    }

    @Test
    fun aFingerCatchesASpringAndADecayWhereTheyAre() = runComposeUiTest {
        val v = FuselineValue(0f)
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(400.dp).testTag("pad").dragMotion(v, Orientation.Horizontal)) }
        // A fling under way...
        onNodeWithTag("pad").performTouchInput { down(center); repeat(6) { moveBy(Offset(25f, 0f), delayMillis = 8) }; up() }
        mainClock.advanceTimeBy(48)
        assertEquals(MotionOwner.ANIMATION, v.owner)
        // ...is caught mid-flight: the value stays exactly where it was caught.
        val caught = v.value
        onNodeWithTag("pad").performTouchInput { down(center) }
        assertEquals(MotionOwner.GESTURE, v.owner)
        assertEquals(caught, v.value)
        // A held finger that doesn't move lets go with no speed: it stays.
        onNodeWithTag("pad").performTouchInput { up() }
        mainClock.advanceTimeBy(1_000)
        assertTrue(abs(v.value - caught) < 0.5f, "${v.value} vs $caught")
    }

    // ------------------------------------------------------------------ steps: D-pad, keys, wheel

    @Test
    fun quickStepsAddUpFromTheTargetAndReverseSmoothly() {
        val c = MotionClock()
        val v = FuselineValue(0f)
        // Three presses before the value gets anywhere: they add up to three steps.
        c.launch { v.nudge(100f) }
        c.launch { v.nudge(100f) }
        c.launch { v.nudge(100f) }
        assertEquals(300f, v.targetValue)
        c.advance(16_666_667L)
        c.run(5)
        // Reversed mid-way: from where it is going, at the speed it has.
        val speed = v.velocity
        c.launch { v.nudge(-200f) }
        assertEquals(100f, v.targetValue)
        assertEquals(speed, v.velocity, "a step keeps the speed it had")
        c.runFor(3_000_000_000L)
        assertEquals(100f, v.value)
        // A wheel notch while a fling coasts: it takes over from the fling's place and speed.
        c.launch { v.animateDecay(2000f) }
        c.advance(16_666_667L)
        c.run(3)
        val coasting = v.velocity
        c.launch { v.nudge(50f) }
        assertEquals(coasting, v.velocity)
        c.runFor(4_000_000_000L)
        assertEquals(v.targetValue, v.value)
        c.close()
    }

    // ------------------------------------------------------------------ the stick

    @Test
    fun theStickDrivesLikeAFingerAndLetsGoWithItsSpeed() {
        val c = MotionClock(0)
        val v = FuselineValue(0f)
        // A spring under way when the stick is pushed: the stick takes it where it is.
        c.launch { v.animateTo(1000f, Spring(1f, 100f)) }
        c.advance(16_666_667L)
        c.run(3)
        val caught = v.value
        val stick = StickDrive(v, unitsPerSecond = 800f)
        stick.push(1f, c.now)
        assertEquals(caught, v.value)
        assertEquals(MotionOwner.GESTURE, v.owner)
        // Held fully right for half a second: 400 units, at 800 a second.
        repeat(30) { c.advance(16_666_667L); stick.push(1f, c.now) }
        assertEquals(caught + 400f, v.value, 1f)
        assertEquals(800f, v.releaseVelocity(c.now), 2f)
        // Let go: a fling at the stick's speed.
        c.launch { stick.letGo(c.now) }
        assertEquals(800f, v.velocity, 2f)
        c.runFor(3_000_000_000L)
        assertEquals(projectDecay(caught + 400f, 800f), v.value, 1f)
        // Pushed again: taken back; held at zero: still.
        stick.push(0f, c.now)
        repeat(10) { c.advance(16_666_667L); stick.push(0f, c.now) }
        assertEquals(0f, v.releaseVelocity(c.now), 1e-3f)
        c.close()
    }

    // ------------------------------------------------------------------ a trackpad's scroll stream

    @Test
    fun aScrollStreamFlingsWhenItGoesQuiet() {
        val c = MotionClock(0)
        val v = FuselineValue(0f)
        val drive = ScrollDrive(v)
        var t = 0L
        repeat(12) { t += 8_000_000L; drive.scroll(6f, t) }
        assertEquals(72f, v.value, 1e-3f)
        assertTrue(!drive.quiet(t + 10_000_000L))
        assertTrue(drive.quiet(t + 60_000_000L))
        c.frameAt(t + 60_000_000L)
        // Lifting is only heard as silence: it flings at the stream's speed as of its last event, 750 a second.
        c.launch { drive.settle() }
        assertEquals(750f, v.velocity, 2f)
        // A stream that slowed to a stop before going quiet carries on hardly at all.
        val w = FuselineValue(0f)
        val d2 = ScrollDrive(w)
        t = 0L
        for (delta in floatArrayOf(8f, 7f, 6f, 5f, 4f, 3f, 2f, 1f, 0.5f, 0.25f, 0.1f, 0.05f)) { t += 8_000_000L; d2.scroll(delta, t) }
        c.launch { d2.settle() }
        assertTrue(abs(w.velocity) < 0.25f * 750f, "slowed stream flings at ${w.velocity}, against 750 for a steady one")
        c.close()
    }
}
