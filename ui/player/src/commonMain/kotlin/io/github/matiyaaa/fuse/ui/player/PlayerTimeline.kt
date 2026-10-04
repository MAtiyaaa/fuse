package io.github.matiyaaa.fuse.ui.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.playback.Chapter
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat

/**
 * The timeline: what is played (in the accent), what is loaded ahead (lighter), chapters as small
 * breaks, and a thumb that grows when the controller is on it. Drag or tap to seek; while dragging
 * the time to land on rides above the thumb. The played part follows the clock every frame.
 */
@Composable
internal fun PlayerTimeline(
    position: () -> Long,
    durationMs: Long?,
    bufferedMs: () -> Long,
    chapters: List<Chapter>,
    focused: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Fuse.colors
    val accent = c.accent
    val lift by fuselineFloat(if (focused) 1f else 0f, Fuse.motion.focusSpring(), label = "timeline")
    var dragging by remember { mutableStateOf<Float?>(null) }
    var now by remember { mutableFloatStateOf(0f) }
    var loaded by remember { mutableFloatStateOf(0f) }
    val read by rememberUpdatedState(position)
    val readBuffered by rememberUpdatedState(bufferedMs)
    val total = (durationMs ?: 0L).coerceAtLeast(1L)
    LaunchedEffect(total) {
        while (true) {
            withFrameMillis { }
            now = (read().toFloat() / total).coerceIn(0f, 1f)
            loaded = (readBuffered().toFloat() / total).coerceIn(0f, 1f)
        }
    }
    val onSeekNow by rememberUpdatedState(onSeek)
    BoxWithConstraints(modifier.fillMaxWidth().height(TOUCH_HEIGHT)) {
        val width = constraints.maxWidth.toFloat()
        val shown = dragging ?: now
        Box(
            Modifier
                .fillMaxWidth()
                .height(TOUCH_HEIGHT)
                .pointerInput(total) {
                    detectTapGestures { o -> onSeekNow(((o.x / size.width).coerceIn(0f, 1f) * total).toLong()) }
                }
                .pointerInput(total) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragging?.let { onSeekNow((it * total).toLong()) }
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    ) { change, _ ->
                        change.consume()
                        dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .drawBehind {
                    val h = (TRACK + (TRACK_FOCUSED - TRACK) * maxOf(lift, if (dragging != null) 1f else 0f)).toPx()
                    val y = size.height / 2 - h / 2
                    val r = CornerRadius(h / 2)
                    drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, h), r)
                    drawRoundRect(Color.White.copy(alpha = 0.32f), Offset(0f, y), Size(size.width * maxOf(loaded, shown), h), r)
                    drawRoundRect(accent, Offset(0f, y), Size(size.width * shown, h), r)
                    // Chapters break the track with a hairline of the background.
                    for (ch in chapters) {
                        if (ch.startMs <= 0) continue
                        val x = size.width * (ch.startMs.toFloat() / total)
                        drawRect(Color.Black.copy(alpha = 0.55f), Offset(x - 1.dp.toPx(), y), Size(2.dp.toPx(), h))
                    }
                    val thumb = (THUMB + (THUMB_FOCUSED - THUMB) * lift).toPx() / 2
                    if (lift > 0.01f || dragging != null) {
                        drawCircle(accent.copy(alpha = 0.28f * lift), thumb * 1.9f, Offset(size.width * shown, size.height / 2))
                    }
                    drawCircle(Color.White, thumb, Offset(size.width * shown, size.height / 2))
                },
        )
        // The time to land on, above the thumb, while dragging.
        val drag = dragging
        if (drag != null) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .layout { m, cons ->
                        val p = m.measure(cons.copy(minWidth = 0))
                        val x = (drag * width - p.width / 2).coerceIn(0f, width - p.width)
                        layout(cons.maxWidth, p.height) { p.place(x.toInt(), -p.height - 6.dp.roundToPx()) }
                    }
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                FText(clock((drag * total).toLong()), Fuse.type.label.tabular(), color = Color.White, maxLines = 1)
            }
        }
    }
}

/** 1:02:03 or 12:34. */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s / 60) % 60
    val sec = s % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}" else "$m:${sec.toString().padStart(2, '0')}"
}

private val TOUCH_HEIGHT = 32.dp
private val TRACK = 4.dp
private val TRACK_FOCUSED = 7.dp
private val THUMB = 12.dp
private val THUMB_FOCUSED = 18.dp
