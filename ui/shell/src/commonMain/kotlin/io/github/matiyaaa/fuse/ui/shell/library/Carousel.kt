package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.components.GameCoverTile
import io.github.matiyaaa.fuse.ui.shell.components.GameTileSkeleton
import io.github.matiyaaa.fuse.ui.shell.components.coverCornerFraction
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Capsule Mode's strip of covers. The selected cover always sits at the same spot ([anchor] of the
 * width) and the strip glides beneath it on a spring, so holding a direction flows instead of
 * stepping. Only covers near the window are composed, so a 5,000-game system costs the same as ten.
 * Neighbours shrink and fade with distance; nothing is laid out or recomposed per animation frame.
 *
 * A swipe glides the strip and a flick carries on with the finger's speed; where it comes to rest
 * is chosen with [onSettle], never [onTap], so a swipe can't start a game.
 *
 * With [start] the selected cover's left edge sits that far in (the page gutter), so it lines up
 * with the stage's title above it; covers already passed slide out past the edge and fade as they
 * go, and the ones to come wait on the right.
 */
@Composable
fun CoverCarousel(
    items: List<GameCard>,
    selected: Int,
    itemWidth: Dp,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onSettle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    anchor: Float = 0.16f,
    focused: Boolean = true,
    start: Dp? = null,
) {
    val motion = Fuse.motion
    val position = remember { Animatable(selected.toFloat()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selected) { position.animateTo(selected.toFloat(), motion.followSpring()) }
    val latestSettle by rememberUpdatedState(onSettle)

    BoxWithConstraints(modifier.fillMaxWidth().height(itemWidth * STRIP_HEIGHT)) {
        val width = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val step = with(density) { (itemWidth + Space.l).toPx() }
        // Where the selected cover's left edge sits.
        val origin = start?.let { with(density) { it.toPx() } } ?: (width * anchor)
        // How many covers fit either side of it, plus one so edges never pop in.
        val before = ceil(origin / step).toInt() + 1
        val after = ceil((width - origin) / step).toInt() + 1
        val center by remember { derivedStateOf { position.value.roundToInt() } }
        val window = (center - before).coerceAtLeast(0)..(center + after).coerceAtMost(items.lastIndex)

        Box(
            Modifier
                .fillMaxWidth()
                .pointerInput(items.size) {
                    val tracker = VelocityTracker()
                    fun settle(fling: Float) {
                        // The strip carries on for a moment at the finger's speed, then rests on a cover.
                        val target = (position.value - fling / step * FLING_SECONDS).roundToInt().coerceIn(0, items.lastIndex)
                        latestSettle(target)
                        scope.launch { position.animateTo(target.toFloat(), motion.followSpring()) }
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = { settle(tracker.calculateVelocity().x) },
                        onDragCancel = { settle(0f) },
                    ) { change, drag ->
                        tracker.addPointerInputChange(change)
                        scope.launch { position.snapTo((position.value - drag / step).coerceIn(0f, items.lastIndex.toFloat())) }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            for (i in window) {
                key(items[i].id) {
                    val card = items[i]
                    GameCoverTile(
                        card = card,
                        selected = focused && i == selected,
                        width = itemWidth,
                        onClick = { onTap(i) },
                        onLongClick = { onLongPress(i) },
                        modifier = Modifier
                            .offset {
                                val d = i - position.value
                                // Extra breathing room right next to the selected cover.
                                val push = when {
                                    d > 0 -> minOf(d, 1f) * step * PUSH
                                    d < 0 -> maxOf(d, -1f) * step * PUSH
                                    else -> 0f
                                }
                                IntOffset((origin + d * step + push).roundToInt(), 0)
                            }
                            .graphicsLayer {
                                val signed = i - position.value
                                val d = abs(signed)
                                val s = 1f - NEIGHBOUR_SHRINK * minOf(d, 1.5f)
                                scaleX = s
                                scaleY = s
                                // Covers to come dim with distance; with a fixed start, covers already
                                // passed fade out quickly as they slide past the edge.
                                val ahead = (1f - DISTANCE_DIM * (d - 1f).coerceAtLeast(0f)).coerceIn(0.25f, 1f)
                                alpha = if (start != null && signed < 0f) (1f + signed * PASSED_FADE).coerceIn(0f, 1f) else ahead
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            },
                    )
                }
            }
        }
    }
}

/**
 * Capsule Mode's strip while its games are on their way: cover outlines in the places and sizes the
 * covers will take (the first where the selected cover rests, the ones after it smaller and dimmer),
 * with the calm shared shimmer over them, so nothing moves when the games arrive.
 */
@Composable
internal fun CarouselSkeleton(itemWidth: Dp, start: Dp, modifier: Modifier = Modifier) {
    val corner = coverCornerFraction()
    BoxWithConstraints(modifier.fillMaxWidth().height(itemWidth * STRIP_HEIGHT)) {
        val density = LocalDensity.current
        val step = with(density) { (itemWidth + Space.l).toPx() }
        val origin = with(density) { start.toPx() }
        val count = (ceil((constraints.maxWidth - origin) / step).toInt() + 1).coerceAtLeast(1)
        for (i in 0 until count) {
            GameTileSkeleton(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((origin + i * step + if (i > 0) step * PUSH else 0f).roundToInt(), 0) }
                    .graphicsLayer {
                        val s = 1f - NEIGHBOUR_SHRINK * minOf(i.toFloat(), 1.5f)
                        scaleX = s
                        scaleY = s
                        alpha = (1f - DISTANCE_DIM * (i - 1f).coerceAtLeast(0f)).coerceIn(0.25f, 1f)
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .width(itemWidth)
                    .aspectRatio(Aspect.CAPSULE),
                corner,
            )
        }
    }
}

/** The strip's height against a cover's width: a 2:3 cover with room for its lift and spark. */
private const val STRIP_HEIGHT = 1.5f * 1.22f

/** Extra room either side of the selected cover, as a share of one step. */
private const val PUSH = 0.18f

/** How far a flick carries the strip: the covers it would pass in this long at release speed. */
private const val FLING_SECONDS = 0.18f

/** Neighbours are this much smaller than the selected cover (up to one and a half covers away). */
private const val NEIGHBOUR_SHRINK = 0.12f

/** Each cover beyond the next one dims by this much. */
private const val DISTANCE_DIM = 0.18f

/** How quickly a passed cover fades as it leaves (fully gone a little before one cover away). */
private const val PASSED_FADE = 1.25f
