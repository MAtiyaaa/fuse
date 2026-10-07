package io.github.matiyaaa.fuse.ui.fuseline.v31

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** How one state relates to the one before it, so a transition knows which way to move. */
enum class TransitionDirection {
    NONE, FORWARD, BACK, LEFT, RIGHT, UP, DOWN, EXPAND, COLLAPSE, ENTER, RETURN;

    /** Which way along its axis the new state arrives from: 1 from the far side (forward, right, down), −1 the near side, 0 in place. */
    val sign: Int
        get() = when (this) {
            FORWARD, RIGHT, DOWN, ENTER -> 1
            BACK, LEFT, UP, RETURN -> -1
            NONE, EXPAND, COLLAPSE -> 0
        }

    /** The same relationship the other way round. */
    val reversed: TransitionDirection
        get() = when (this) {
            FORWARD -> BACK; BACK -> FORWARD; LEFT -> RIGHT; RIGHT -> LEFT; UP -> DOWN; DOWN -> UP
            EXPAND -> COLLAPSE; COLLAPSE -> EXPAND; ENTER -> RETURN; RETURN -> ENTER; NONE -> NONE
        }
}

/**
 * Animate the transition, not only the destination. A state change (Home to Library, one page of a
 * carousel to the next, a menu opening) is one motion that knows where it came from, where it is
 * going, which way, and how far it has got, and every state still on screen takes part in it.
 *
 * Each state on screen is a [Part] with two values that move like any [FuselineValue], with real
 * velocities: its presence (1 fully shown, 0 gone) and its position along the transition's axis (0 in
 * place, 1 off toward the far side, −1 toward the near side). A new target never restarts anything:
 * every part moves on from where it is and how fast it is going. Home → Library → Apps retargets
 * continuously (Library, still sliding in from the right, carries on leftward and out); Home →
 * Library → Home reverses (each goes back the way it came). Direction comes from [order] (each
 * state's place: tabs, pages, a carousel) when states have one, or is given with each change.
 *
 * The transition can be driven by time ([go]), by a gesture ([scrub] and [release], for interactive
 * back or a swiped page) or both in turn, and progress may move any way, forward or back. A state
 * that has fully left is let go: only the states still in motion are kept.
 */
@Stable
class MotionTransition<S>(
    initial: S,
    /** Each state's place in order, or null for states without one. */
    private val order: (S) -> Int? = { null },
    /** With [count], places wrap around (the last tab's next is the first): the shorter way round wins. */
    private val wraps: Boolean = false,
    private val count: () -> Int = { 0 },
) {
    /** One state on screen. */
    @Stable
    class Part<S> internal constructor(val state: S, present: Float, position: Float) {
        internal val presence = FuselineValue(present, PRESENCE_THRESHOLD)
        internal val place = FuselineValue(position, PRESENCE_THRESHOLD)

        /** How present it is: 1 shown, 0 gone (a little beyond while a spring overshoots). */
        val presenceValue: Float get() = presence.floatValue

        /** How fast its presence changes, per second. */
        val presenceVelocity: Float get() = presence.velocity

        /** Where it is along the axis: 0 in place, ±1 a full travel off to either side. */
        val position: Float get() = place.floatValue

        /** How fast it moves along the axis, in travels per second. */
        val positionVelocity: Float get() = place.velocity

        /** True once it is fully shown or gone and still. */
        val settled: Boolean get() = !presence.isRunning && !presence.isDragging && !place.isRunning && !place.isDragging

        override fun toString(): String = "Part($state, presence ${presence.floatValue}, position ${place.floatValue})"
    }

    private val list = mutableStateListOf(Part(initial, 1f, 0f))

    /** Every state taking part, oldest first. */
    val parts: List<Part<S>> get() = list

    /** Where the transition is going. */
    var targetState: S by mutableStateOf(initial)
        private set

    /** The state it was at before the latest change (null before any). */
    var previousState: S? by mutableStateOf(null)
        private set

    /** Which way the latest change went. */
    var direction: TransitionDirection by mutableStateOf(TransitionDirection.NONE)
        private set

    /** How far the target has arrived, 0 to 1. */
    val progress: Float get() = partOf(targetState)?.presenceValue ?: 1f

    /** True once every part has arrived and only the target is left, still. */
    val isSettled: Boolean get() = list.size == 1 && list[0].settled && list[0].presenceValue == 1f

    fun partOf(state: S): Part<S>? = list.firstOrNull { it.state == state }

    /** The direction from [from] to [to] by their places in [order] (none when either has no place). */
    fun directionOf(from: S, to: S): TransitionDirection {
        val a = order(from) ?: return TransitionDirection.NONE
        val b = order(to) ?: return TransitionDirection.NONE
        var d = b - a
        val n = count()
        if (wraps && n > 1) {
            // The shorter way round.
            if (d > n / 2) d -= n else if (d < -(n / 2)) d += n
        }
        return when {
            d > 0 -> TransitionDirection.FORWARD
            d < 0 -> TransitionDirection.BACK
            else -> TransitionDirection.NONE
        }
    }

    /** Makes [target] the target, going [dir]: a part not on screen yet waits on the side it arrives from. */
    private fun aim(target: S, dir: TransitionDirection): Part<S> {
        direction = dir
        previousState = targetState
        targetState = target
        val incoming = partOf(target) ?: Part(target, 0f, dir.sign.toFloat()).also { list.add(it) }
        // Not on screen (fully gone and still): placed at its arrival side, unseen, so placing it moves nothing visible.
        if (incoming.presenceValue <= 0f && incoming.settled) incoming.place.jumpTo(dir.sign.toFloat())
        return incoming
    }

    /**
     * Goes to [target] under [motion] (in [scope]), from wherever every part is and however fast it is
     * moving. [towards] overrides the direction worked out from [order].
     */
    fun go(target: S, scope: CoroutineScope, motion: Motion = Spring(1f, 500f), towards: TransitionDirection? = null) {
        if (target == targetState && partOf(target)?.presence?.targetValue == 1f) return
        val dir = towards ?: directionOf(targetState, target)
        if (MotionTrace.enabled) {
            val reversing = target == previousState && partOf(target) != null
            MotionTrace.record(if (reversing) MotionTrace.Kind.REVERSAL else MotionTrace.Kind.TRANSITION, "$targetState → $target", progress, partOf(targetState)?.presenceVelocity ?: 0f, dir.name)
        }
        val incoming = aim(target, dir)
        // The target comes to its place; everything else leaves toward the side opposite the arrival
        // (or, with no direction, fades where it is), each from its own place and speed.
        val away = -dir.sign.toFloat()
        for (p in list) {
            if (p === incoming) {
                drive(p, p.presence, 1f, motion, scope)
                drive(p, p.place, 0f, motion, scope)
            } else {
                drive(p, p.presence, 0f, motion, scope)
                drive(p, p.place, if (dir.sign == 0) p.place.floatValue else away, motion, scope)
            }
        }
    }

    private fun drive(p: Part<S>, value: FuselineValue<Float>, goal: Float, motion: Motion, scope: CoroutineScope) {
        if (value.isRunning && value.retargetFloat(goal, motion)) return
        if (value.floatValue == goal && !value.isDragging) {
            settle(p)
            return
        }
        scope.launch {
            value.animateTo(goal, motion)
            settle(p)
        }
    }

    /** A part that has arrived: fully shown and in place; or gone (and let go). */
    private fun settle(p: Part<S>) {
        if (p.state == targetState) {
            // Rebuilding the parts list is a snapshot write: only when something has really gone.
            if (p.settled && p.presenceValue == 1f && list.size > 1) {
                var gone = false
                for (o in list) if (o !== p && o.presenceValue == 0f && !o.presence.isRunning && !o.presence.isDragging) gone = true
                if (gone) list.removeAll { it !== p && it.presenceValue == 0f && !it.presence.isRunning && !it.presence.isDragging }
            }
        } else if (p.presenceValue == 0f && !p.presence.isRunning && !p.presence.isDragging) {
            // Invisible: whatever its position still does doesn't show, so it goes now.
            p.place.jumpTo(p.place.floatValue)
            list.remove(p)
        }
    }

    // ---------------------------------------------------------------------------- scrubbing

    /**
     * Drives the change to [toward] by hand: [fraction] of the way there (0 the state it starts from,
     * 1 arrived), any way, any number of times, at [timeNanos]. The parts are held by the gesture, so
     * [release] hands them back to motion at the gesture's own speed.
     */
    fun scrub(toward: S, fraction: Float, timeNanos: Long = monotonicNanos(), towards: TransitionDirection? = null) {
        if (toward != targetState) aim(toward, towards ?: directionOf(targetState, toward))
        val f = fraction.coerceIn(0f, 1f)
        val from = previousState
        val s = direction.sign.toFloat()
        for (p in list) {
            when (p.state) {
                toward -> { p.presence.dragTo(f, timeNanos); p.place.dragTo(s * (1f - f), timeNanos) }
                from -> { p.presence.dragTo(1f - f, timeNanos); p.place.dragTo(-s * f, timeNanos) }
                else -> { p.presence.dragTo(0f, timeNanos) }
            }
        }
    }

    /**
     * Lets go of a [scrub] at [timeNanos]: the change completes if the gesture was past halfway or
     * moving on toward the target fast enough to get there, and goes back otherwise, every part
     * starting from the gesture's own speed. Returns the state it settles on.
     */
    fun release(scope: CoroutineScope, motion: Motion = Spring(1f, 500f), timeNanos: Long = monotonicNanos()): S {
        val target = partOf(targetState) ?: return targetState
        val v = target.presence.releaseVelocity(timeNanos)
        val projected = target.presenceValue + v / Decay.DEFAULT_FRICTION
        val back = previousState
        val complete = projected >= 0.5f || back == null
        if (!complete) {
            // Back where it started: the state it came from is the target again, the other way.
            previousState = targetState
            targetState = back!!
            direction = direction.reversed
        }
        val settleOn = targetState
        val s = direction.sign.toFloat()
        for (p in list) {
            val shown = p.state == settleOn
            val presenceSpeed = p.presence.releaseVelocity(timeNanos)
            val placeSpeed = p.place.releaseVelocity(timeNanos)
            val placeGoal = when {
                shown -> 0f
                complete -> -s
                else -> s
            }
            scope.launch { p.presence.animateTo(if (shown) 1f else 0f, motion, presenceSpeed); settle(p) }
            scope.launch { p.place.animateTo(placeGoal, motion, placeSpeed); settle(p) }
        }
        return settleOn
    }

    private companion object {
        const val PRESENCE_THRESHOLD = 0.001f
    }
}

/**
 * A [MotionTransition] that follows [target]: each new target is a [MotionTransition.go] under
 * [motion]. Rapid changes (a held direction, quick taps) retarget it in place every time.
 */
@Composable
fun <S> rememberMotionTransition(
    target: S,
    motion: Motion = Spring(1f, 500f),
    order: (S) -> Int? = { null },
    wraps: Boolean = false,
    count: () -> Int = { 0 },
): MotionTransition<S> {
    val transition = remember { MotionTransition(target, order, wraps, count) }
    val scope = rememberCoroutineScope()
    if (transition.targetState != target) transition.go(target, scope, motion)
    return transition
}

/**
 * Shows every state of [transition] still on screen, newest on top, each placed along the horizontal
 * (or, with [vertical], the vertical) at its position times [distance], fading with its presence. A
 * state that has left is no longer composed.
 */
@Composable
fun <S> MotionTransitionLayout(
    transition: MotionTransition<S>,
    modifier: Modifier = Modifier,
    distance: Dp = 32.dp,
    vertical: Boolean = false,
    fade: Boolean = true,
    content: @Composable (S) -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            for (p in transition.parts) key(p.state) {
                androidx.compose.foundation.layout.Box(
                    Modifier.graphicsLayer {
                        val off = p.position * distance.toPx()
                        if (vertical) translationY = off else translationX = off
                        if (fade) alpha = p.presenceValue.coerceIn(0f, 1f)
                    },
                ) { content(p.state) }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val w = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val h = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(w, h) { placeables.forEach { it.place(0, 0) } }
    }
}

/** The visible offset of [this] part along its axis for a travel of [distance] (what [MotionTransitionLayout] draws). */
fun MotionTransition.Part<*>.offset(distance: Float): Float = position * distance
