package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.components.GameCoverTile
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
) {
    val motion = Fuse.motion
    val position = remember { Animatable(selected.toFloat()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selected) { position.animateTo(selected.toFloat(), motion.followSpring()) }
    val latestSettle by rememberUpdatedState(onSettle)

    BoxWithConstraints(modifier.fillMaxWidth().height(itemWidth * 1.5f * 1.22f)) {
        val width = constraints.maxWidth.toFloat()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val step = with(density) { (itemWidth + Space.l).toPx() }
        // How many covers fit either side of the anchor, plus one so edges never pop in.
        val before = ceil(width * anchor / step).toInt() + 1
        val after = ceil(width * (1 - anchor) / step).toInt() + 1
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
                                    d > 0 -> minOf(d, 1f) * step * 0.18f
                                    d < 0 -> maxOf(d, -1f) * step * 0.18f
                                    else -> 0f
                                }
                                IntOffset((width * anchor + d * step + push).roundToInt(), 0)
                            }
                            .graphicsLayer {
                                val d = abs(i - position.value)
                                val s = 1f - 0.12f * minOf(d, 1.5f)
                                scaleX = s
                                scaleY = s
                                alpha = (1f - 0.18f * (d - 1f).coerceAtLeast(0f)).coerceIn(0.25f, 1f)
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                            },
                    )
                }
            }
        }
    }
}

/** How far a flick carries the strip: the covers it would pass in this long at release speed. */
private const val FLING_SECONDS = 0.18f
