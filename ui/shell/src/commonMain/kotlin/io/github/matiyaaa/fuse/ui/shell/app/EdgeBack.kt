package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.spring
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Where an edge swipe is: which side (1 the left, -1 the right, 0 none), how far, and at what height. */
private class EdgeSwipe {
    var side by mutableIntStateOf(0)
    var y by mutableFloatStateOf(0f)
    val progress = FuselineValue(0f)
}

/**
 * Swipe in from the left or right edge to go back, as on a phone, for touch screens on Linux,
 * Windows and macOS (Android has the system's own). A drag that starts at the very edge and runs
 * inward is taken before the page sees it; a bubble with an arrow follows the finger and lights in
 * the accent once letting go will go back, with a tick. Released early, or turned into an up or
 * down drag, it gives the page its touch back untouched. Back goes through the input router like B,
 * so whatever is on top (a sheet, a menu, a page) closes first.
 */
@Composable
internal fun Modifier.edgeSwipeBack(enabled: Boolean, onTick: () -> Unit, onBack: () -> Unit): Modifier {
    if (!enabled) return this
    val swipe = remember { EdgeSwipe() }
    val scope = rememberCoroutineScope()
    val back by rememberUpdatedState(onBack)
    val tick by rememberUpdatedState(onTick)
    val c = Fuse.colors
    val bubble = c.surfaceOverlay
    val lit = c.accent
    val arrow = c.text
    val arrowLit = c.onAccent
    val reduced = Fuse.motion.reduced
    return this
        .pointerInput(Unit) {
            val edge = EDGE.toPx()
            val reach = REACH.toPx()
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val side = when {
                    down.position.x <= edge -> 1
                    down.position.x >= size.width - edge -> -1
                    else -> return@awaitEachGesture
                }
                val start = down.position
                var engaged = false
                var armed = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val dx = (change.position.x - start.x) * side
                    val dy = change.position.y - start.y
                    if (!change.pressed) {
                        if (engaged) {
                            change.consume()
                            if (armed) back()
                        }
                        break
                    }
                    if (!engaged) {
                        // An up or down drag along the edge is the page's (a list scrolling).
                        if (abs(dy) > slop * 2 && abs(dy) > dx) break
                        if (dx > slop && dx > abs(dy) * 1.2f) {
                            engaged = true
                            swipe.side = side
                        } else {
                            continue
                        }
                    }
                    change.consume()
                    swipe.y = change.position.y
                    val p = (dx / reach).coerceIn(0f, 1.25f)
                    scope.launch { swipe.progress.snapTo(p) }
                    if ((p >= 1f) != armed) {
                        armed = p >= 1f
                        if (armed) tick()
                    }
                }
                if (engaged) {
                    scope.launch {
                        swipe.progress.animateTo(0f, if (reduced) spring(stiffness = 4000f) else spring(dampingRatio = 0.9f, stiffness = 700f))
                        swipe.side = 0
                    }
                }
            }
        }
        .drawWithContent {
            drawContent()
            val side = swipe.side
            val p = swipe.progress.value
            if (side == 0 || p <= 0.01f) return@drawWithContent
            val shown = p.coerceAtMost(1f)
            val radius = BUBBLE.toPx() * (0.55f + 0.45f * shown)
            // Out from the edge with the finger, a little past the edge's own line at full reach.
            val travel = radius * 2f * shown - radius + 6.dp.toPx() * shown
            val cx = if (side == 1) travel else size.width - travel
            val cy = swipe.y.coerceIn(radius, size.height - radius)
            val on = p >= 1f
            drawCircle(if (on) lit else bubble.copy(alpha = 0.92f), radius, Offset(cx, cy), alpha = shown)
            val a = radius * 0.32f
            val tint = if (on) arrowLit else lerp(arrow.copy(alpha = 0.5f), arrow, shown)
            val path = Path().apply {
                // A chevron pointing back toward the edge it came from.
                moveTo(cx + a * 0.45f * side, cy - a)
                lineTo(cx - a * 0.55f * side, cy)
                lineTo(cx + a * 0.45f * side, cy + a)
            }
            drawPath(path, tint, alpha = shown, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
}

/** How close to the edge a swipe must start. */
private val EDGE = 20.dp

/** How far in it must travel to go back. */
private val REACH = 88.dp

/** The bubble's size at full reach. */
private val BUBBLE = 26.dp
