package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

/**
 * The two edges of an indicator that glides from place to place: a tab underline, the highlight
 * behind the selected row of a menu, a segmented control's thumb. Read [start], [end] and [size]
 * inside drawing or placement lambdas (`drawBehind`, `offset { }`, `layout`), never in composition,
 * so the glide moves pixels without recomposing anything.
 */
@Stable
class Glide internal constructor(start: Dp, end: Dp) {
    internal val a = FuselineValue(start, DpConverter)
    internal val b = FuselineValue(end, DpConverter)

    /** Which way the last move went, so the trailing edge is the one held close. */
    internal var forward = true

    /**
     * The most the indicator may stretch: its resting length and one length more. Quick runs (a
     * shoulder button tapped again and again) would otherwise leave the trailing edge far behind.
     */
    internal var stretch: Dp = (end - start) * 2

    /** What the indicator last moved to, for [rememberGlide]'s key. */
    internal var key: Any? = Unset

    /** The leading edge (left, or top). */
    val start: Dp get() = if (forward) maxOf(a.value, b.value - stretch) else a.value

    /** The trailing edge (right, or bottom). */
    val end: Dp get() = if (forward) b.value else minOf(b.value, a.value + stretch)

    /** The current length between the edges. */
    val size: Dp get() = end - start

    /** Whether the indicator is still on its way. */
    val moving: Boolean get() = a.isRunning || b.isRunning

    internal object Unset
}

/**
 * Glides an indicator to the span [start]..[end] (along either axis) whenever they change. The edge
 * leading the move travels on [FuselineMotion.glide] and the other follows on the softer
 * [FuselineMotion.glideTrail], so the indicator stretches a little toward where it is going and
 * settles to its new size: it reads as one object moving, not a jump. The trailing edge never
 * falls more than one length behind, so it keeps up with fast runs. The first position is taken
 * without animating. Snaps under Reduced motion.
 *
 * With a [key] (what the indicator marks: a tab, a row), only a new key glides. When the same thing
 * moves (its row scrolls, a label beside it opens, the window resizes) the indicator stays exactly
 * on it, carrying on to it if it is still on its way. Without a key every change glides.
 */
@Composable
fun rememberGlide(start: Dp, end: Dp, key: Any? = Glide.Unset): Glide {
    val glide = remember { Glide(start, end) }
    val motion = LocalFuselineMotion.current
    LaunchedEffect(start, end, key) {
        val keyed = key !== Glide.Unset
        val sameThing = keyed && glide.key == key
        val first = keyed && glide.key === Glide.Unset
        glide.key = key
        glide.stretch = (end - start) * 2
        if (first || (sameThing && !glide.moving)) {
            // The same thing, moved by layout: stay on it exactly.
            launch { glide.a.snapTo(start) }
            launch { glide.b.snapTo(end) }
            return@LaunchedEffect
        }
        val forward = if (sameThing) glide.forward else start >= glide.a.targetValue
        glide.forward = forward
        launch { glide.a.animateTo(start, if (forward) motion.glideTrail() else motion.glide()) }
        launch { glide.b.animateTo(end, if (forward) motion.glide() else motion.glideTrail()) }
    }
    return glide
}
