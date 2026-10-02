package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import kotlin.math.roundToInt

/**
 * A developer's frame-time graph (developer options): the last [FRAMES] frames as bars, a line at
 * one frame of a 60 Hz screen and one at two, and the average and worst frame underneath. Bars over
 * the line are frames that took too long, which is what lag looks like. It redraws only itself,
 * in its own layer, so measuring costs the app almost nothing.
 */
@Composable
fun FrameTimeOverlay(modifier: Modifier = Modifier) {
    val times = remember { FloatArray(FRAMES) }
    var drawn by remember { mutableIntStateOf(0) }
    // The words change a few times a second; the graph redraws every frame without recomposing.
    var summary by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var head = 0
        while (true) {
            withFrameNanos { now ->
                times[head % FRAMES] = (now - last) / 1_000_000f
                last = now
                head++
                drawn = head
            }
            if (head % SUMMARY_EVERY == 0) {
                val count = minOf(head, FRAMES)
                var sum = 0f
                var worst = 0f
                for (i in 0 until count) {
                    val ms = times[(head - 1 - i).mod(FRAMES)]
                    sum += ms
                    if (ms > worst) worst = ms
                }
                summary = "avg ${tenths(sum / count)} ms  ·  worst ${tenths(worst)} ms"
            }
        }
    }
    Column(
        modifier
            .clip(RoundedCornerShape(Space.s))
            .graphicsLayer()
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.62f)) }
            .padding(Space.s),
    ) {
        Spacer(
            Modifier.size(GRAPH_WIDTH, GRAPH_HEIGHT).drawBehind {
                // Read here so only the graph redraws each frame.
                val count = minOf(drawn, FRAMES)
                val barW = size.width / FRAMES
                fun y(ms: Float) = size.height - (ms / SCALE_MS).coerceIn(0f, 1f) * size.height
                for (i in 0 until count) {
                    val ms = times[(drawn - 1 - i).mod(FRAMES)]
                    val colour = when {
                        ms > BUDGET_MS * 2 -> Color(0xFFFF5D6C)
                        ms > BUDGET_MS * 1.2f -> Color(0xFFFFB547)
                        else -> Color(0xFF3DD68C)
                    }
                    val x = size.width - (i + 1) * barW
                    drawRect(colour, Offset(x, y(ms)), GeoSize(barW * 0.8f, size.height - y(ms)))
                }
                drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, y(BUDGET_MS)), Offset(size.width, y(BUDGET_MS)), strokeWidth = 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, y(BUDGET_MS * 2)), Offset(size.width, y(BUDGET_MS * 2)), strokeWidth = 1.dp.toPx())
            },
        )
        Spacer(Modifier.height(Space.xs))
        FText(
            summary,
            Fuse.type.caption.tabular(),
            color = Color.White,
            maxLines = 1,
        )
    }
}

private const val FRAMES = 120
private const val BUDGET_MS = 16.7f
private const val SCALE_MS = 50f
private val GRAPH_WIDTH = 180.dp
private val GRAPH_HEIGHT = 48.dp
private const val SUMMARY_EVERY = 15

/** [ms] with one decimal, the way the graph's words show it. */
private fun tenths(ms: Float): String {
    val t = (ms * 10).roundToInt()
    return "${t / 10}.${t % 10}"
}
