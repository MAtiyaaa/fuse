package io.github.matiyaaa.fuse.ui.fuseline.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ----------------------------------------------------------------------------------------------
// The parts of an entrance or exit. Each moves on its own motion; together they make one.

/** Fading between [alpha] (hidden) and fully shown. */
@Immutable
class FadePart internal constructor(val alpha: Float, val motion: Motion)

/** Sliding between [offset] (of the content's full size, hidden) and its place. */
@Immutable
class SlidePart internal constructor(val offset: (IntSize) -> IntOffset, val motion: Motion)

/** Scaling between [scale] (hidden) and full size, about [origin]. */
@Immutable
class ScalePart internal constructor(val scale: Float, val origin: TransformOrigin, val motion: Motion)

/**
 * Growing or shrinking the room the content takes, between [size] (of its full size, hidden) and
 * its full size, with the content held to [alignment] in that room; [clip] trims what doesn't fit.
 */
@Immutable
class SizePart internal constructor(val alignment: Alignment, val size: (IntSize) -> IntSize, val clip: Boolean, val motion: Motion)

/** How something arrives: any of a fade, a slide, a scale and a size change, joined with `+`. */
@Immutable
class Enter internal constructor(
    internal val fade: FadePart? = null,
    internal val slide: SlidePart? = null,
    internal val scale: ScalePart? = null,
    internal val size: SizePart? = null,
) {
    /** Both together; where both have the same part, this one's is kept. */
    operator fun plus(other: Enter): Enter = Enter(fade ?: other.fade, slide ?: other.slide, scale ?: other.scale, size ?: other.size)

    companion object {
        /** Arrives as it is. */
        val None = Enter()
    }
}

/** How something leaves: any of a fade, a slide, a scale and a size change, joined with `+`. */
@Immutable
class Exit internal constructor(
    internal val fade: FadePart? = null,
    internal val slide: SlidePart? = null,
    internal val scale: ScalePart? = null,
    internal val size: SizePart? = null,
) {
    operator fun plus(other: Exit): Exit = Exit(fade ?: other.fade, slide ?: other.slide, scale ?: other.scale, size ?: other.size)

    companion object {
        /** Leaves at once. */
        val None = Exit()
    }
}

private val gentle = Spring(stiffness = Spring.StiffnessMediumLow)

// Parts move as shares of the way (0 hidden, 1 shown), so a slide or size uses the same spring.
private val gentlePixels = gentle

/** Fading in from [initialAlpha]. */
fun fadeIn(animationSpec: Motion = gentle, initialAlpha: Float = 0f): Enter = Enter(fade = FadePart(initialAlpha, animationSpec))

/** Fading out to [targetAlpha]. */
fun fadeOut(animationSpec: Motion = gentle, targetAlpha: Float = 0f): Exit = Exit(fade = FadePart(targetAlpha, animationSpec))

/** Sliding in from [initialOffset] (given the content's full size). */
fun slideIn(animationSpec: Motion = gentlePixels, initialOffset: (IntSize) -> IntOffset): Enter = Enter(slide = SlidePart(initialOffset, animationSpec))

/** Sliding out to [targetOffset] (given the content's full size). */
fun slideOut(animationSpec: Motion = gentlePixels, targetOffset: (IntSize) -> IntOffset): Exit = Exit(slide = SlidePart(targetOffset, animationSpec))

/** Sliding in sideways from [initialOffsetX] (given the content's full width; half of it from the left by default). */
fun slideInHorizontally(animationSpec: Motion = gentlePixels, initialOffsetX: (fullWidth: Int) -> Int = { -it / 2 }): Enter =
    slideIn(animationSpec) { IntOffset(initialOffsetX(it.width), 0) }

/** Sliding out sideways to [targetOffsetX]. */
fun slideOutHorizontally(animationSpec: Motion = gentlePixels, targetOffsetX: (fullWidth: Int) -> Int = { -it / 2 }): Exit =
    slideOut(animationSpec) { IntOffset(targetOffsetX(it.width), 0) }

/** Sliding in vertically from [initialOffsetY] (given the content's full height; half of it from above by default). */
fun slideInVertically(animationSpec: Motion = gentlePixels, initialOffsetY: (fullHeight: Int) -> Int = { -it / 2 }): Enter =
    slideIn(animationSpec) { IntOffset(0, initialOffsetY(it.height)) }

/** Sliding out vertically to [targetOffsetY]. */
fun slideOutVertically(animationSpec: Motion = gentlePixels, targetOffsetY: (fullHeight: Int) -> Int = { -it / 2 }): Exit =
    slideOut(animationSpec) { IntOffset(0, targetOffsetY(it.height)) }

/** Growing from [initialScale] about [transformOrigin]. */
fun scaleIn(animationSpec: Motion = gentle, initialScale: Float = 0f, transformOrigin: TransformOrigin = TransformOrigin.Center): Enter =
    Enter(scale = ScalePart(initialScale, transformOrigin, animationSpec))

/** Shrinking to [targetScale] about [transformOrigin]. */
fun scaleOut(animationSpec: Motion = gentle, targetScale: Float = 0f, transformOrigin: TransformOrigin = TransformOrigin.Center): Exit =
    Exit(scale = ScalePart(targetScale, transformOrigin, animationSpec))

/** Its room growing from [initialSize], from [expandFrom]. */
fun expandIn(
    animationSpec: Motion = gentlePixels,
    expandFrom: Alignment = Alignment.BottomEnd,
    clip: Boolean = true,
    initialSize: (fullSize: IntSize) -> IntSize = { IntSize(0, 0) },
): Enter = Enter(size = SizePart(expandFrom, initialSize, clip, animationSpec))

/** Its room shrinking to [targetSize], towards [shrinkTowards]. */
fun shrinkOut(
    animationSpec: Motion = gentlePixels,
    shrinkTowards: Alignment = Alignment.BottomEnd,
    clip: Boolean = true,
    targetSize: (fullSize: IntSize) -> IntSize = { IntSize(0, 0) },
): Exit = Exit(size = SizePart(shrinkTowards, targetSize, clip, animationSpec))

/** Its width growing from [initialWidth], from [expandFrom]. */
fun expandHorizontally(
    animationSpec: Motion = gentlePixels,
    expandFrom: Alignment.Horizontal = Alignment.End,
    clip: Boolean = true,
    initialWidth: (fullWidth: Int) -> Int = { 0 },
): Enter = expandIn(animationSpec, horizontal(expandFrom), clip) { IntSize(initialWidth(it.width), it.height) }

/** Its width shrinking to [targetWidth], towards [shrinkTowards]. */
fun shrinkHorizontally(
    animationSpec: Motion = gentlePixels,
    shrinkTowards: Alignment.Horizontal = Alignment.End,
    clip: Boolean = true,
    targetWidth: (fullWidth: Int) -> Int = { 0 },
): Exit = shrinkOut(animationSpec, horizontal(shrinkTowards), clip) { IntSize(targetWidth(it.width), it.height) }

/** Its height growing from [initialHeight], from [expandFrom]. */
fun expandVertically(
    animationSpec: Motion = gentlePixels,
    expandFrom: Alignment.Vertical = Alignment.Bottom,
    clip: Boolean = true,
    initialHeight: (fullHeight: Int) -> Int = { 0 },
): Enter = expandIn(animationSpec, vertical(expandFrom), clip) { IntSize(it.width, initialHeight(it.height)) }

/** Its height shrinking to [targetHeight], towards [shrinkTowards]. */
fun shrinkVertically(
    animationSpec: Motion = gentlePixels,
    shrinkTowards: Alignment.Vertical = Alignment.Bottom,
    clip: Boolean = true,
    targetHeight: (fullHeight: Int) -> Int = { 0 },
): Exit = shrinkOut(animationSpec, vertical(shrinkTowards), clip) { IntSize(it.width, targetHeight(it.height)) }

private fun horizontal(a: Alignment.Horizontal): Alignment = when (a) {
    Alignment.Start -> Alignment.CenterStart
    Alignment.CenterHorizontally -> Alignment.Center
    else -> Alignment.CenterEnd
}

private fun vertical(a: Alignment.Vertical): Alignment = when (a) {
    Alignment.Top -> Alignment.TopCenter
    Alignment.CenterVertically -> Alignment.Center
    else -> Alignment.BottomCenter
}

// ----------------------------------------------------------------------------------------------

/**
 * Whether something is shown, held outside composition: [targetState] is where it is going,
 * [currentState] where it last arrived. [isIdle] once it has arrived. Lists use it to let an item
 * leave before forgetting it.
 */
@Stable
class AppearState(initialState: Boolean) {
    var targetState: Boolean by mutableStateOf(initialState)
    var currentState: Boolean by mutableStateOf(initialState)
        internal set
    internal var running: Boolean by mutableStateOf(false)
    val isIdle: Boolean get() = !running && currentState == targetState
}

/** What [Appear] content runs in. */
interface AppearScope

private object AppearScopeImpl : AppearScope

/**
 * Shows [content] while [visible], arriving under [enter] and leaving under [exit]; once it has
 * left it is no longer composed and takes no room. Shown from the start, it doesn't animate in.
 */
@Composable
fun Appear(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: Enter = fadeIn() + expandIn(),
    exit: Exit = shrinkOut() + fadeOut(),
    label: String = "Appear",
    content: @Composable AppearScope.() -> Unit,
) {
    val state = remember { AppearState(visible) }
    state.targetState = visible
    Appear(state, modifier, enter, exit, label, content)
}

/** [Appear] following an [AppearState] kept by the caller. */
@Composable
fun Appear(
    visibleState: AppearState,
    modifier: Modifier = Modifier,
    enter: Enter = fadeIn() + expandIn(),
    exit: Exit = shrinkOut() + fadeOut(),
    label: String = "Appear",
    content: @Composable AppearScope.() -> Unit,
) {
    val shownNow = visibleState.currentState
    val parts = remember { AppearParts(if (shownNow) 1f else 0f) }
    val enterNow by rememberUpdatedState(enter)
    val exitNow by rememberUpdatedState(exit)
    val target = visibleState.targetState
    LaunchedEffect(visibleState, target) {
        if (target == visibleState.currentState && !visibleState.running && parts.settled(target)) return@LaunchedEffect
        visibleState.running = true
        try {
            parts.showing = target
            parts.run(target, enterNow, exitNow)
            visibleState.currentState = target
        } finally {
            visibleState.running = false
        }
    }
    if (!visibleState.currentState && !visibleState.targetState && !visibleState.running) return
    val showing = parts.showing
    val fadeTo = if (showing) enter.fade else exit.fade
    val slideTo = if (showing) enter.slide else exit.slide
    val scaleTo = if (showing) enter.scale else exit.scale
    val sizeTo = if (showing) enter.size else exit.size
    androidx.compose.foundation.layout.Box(
        modifier
            .appearSize(sizeTo, parts)
            .graphicsLayer {
                val f = parts.fade.value
                alpha = if (fadeTo != null) fadeTo.alpha + (1f - fadeTo.alpha) * f else 1f
                if (scaleTo != null) {
                    val s = scaleTo.scale + (1f - scaleTo.scale) * parts.scale.value
                    scaleX = s
                    scaleY = s
                    transformOrigin = scaleTo.origin
                }
                if (slideTo != null) {
                    val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    val off = slideTo.offset(full)
                    val left = 1f - parts.slide.value
                    translationX = off.x * left
                    translationY = off.y * left
                }
            },
        propagateMinConstraints = true,
    ) {
        AppearScopeImpl.content()
    }
}

/** How far each part is shown (1) or hidden (0). */
@Stable
internal class AppearParts(start: Float) {
    val fade = FuselineValue(start, SHARE_THRESHOLD)
    val slide = FuselineValue(start, SHARE_THRESHOLD)
    val scale = FuselineValue(start, SHARE_THRESHOLD)
    val size = FuselineValue(start, SHARE_THRESHOLD)
    var showing by mutableStateOf(start > 0.5f)

    fun settled(target: Boolean): Boolean {
        val goal = if (target) 1f else 0f
        return fade.value == goal && slide.value == goal && scale.value == goal && size.value == goal
    }

    /**
     * Runs every part to shown or hidden at once, each on its own motion; a part the transition
     * doesn't have changes straight away.
     */
    suspend fun run(show: Boolean, enter: Enter, exit: Exit) = coroutineScope {
        val goal = if (show) 1f else 0f
        fun go(value: FuselineValue<Float>, motion: Motion?) {
            // A spring's own threshold is in pixels or units; these are shares of the way.
            val m = if (motion is Spring && motion.threshold != null) motion.copy(threshold = null) else motion
            launch { if (m == null) value.snapTo(goal) else value.animateTo(goal, m) }
        }
        if (show) {
            go(fade, enter.fade?.motion)
            go(slide, enter.slide?.motion)
            go(scale, enter.scale?.motion)
            go(size, enter.size?.motion)
        } else {
            // Parts the exit doesn't use stay shown until it has gone.
            go(fade, exit.fade?.motion)
            go(slide, exit.slide?.motion)
            go(scale, exit.scale?.motion)
            go(size, exit.size?.motion)
        }
    }
}

/** Close enough to shown or hidden, as a share of the way. */
private const val SHARE_THRESHOLD = 0.001f

/** The room the content takes while its size part runs, with the content held to its alignment. */
private fun Modifier.appearSize(part: SizePart?, parts: AppearParts): Modifier {
    if (part == null) return this
    // The clip goes outside the layout, so it trims to the room as it grows, not to the full content.
    val room = if (part.clip) this.clipToBounds() else this
    return room.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val full = IntSize(placeable.width, placeable.height)
        val small = part.size(full)
        val f = parts.size.value
        val w = (small.width + (full.width - small.width) * f).roundToInt().coerceAtLeast(0)
        val h = (small.height + (full.height - small.height) * f).roundToInt().coerceAtLeast(0)
        val at = part.alignment.align(full, IntSize(w, h), LayoutDirection.Ltr)
        layout(w, h) { placeable.place(at) }
    }
}
