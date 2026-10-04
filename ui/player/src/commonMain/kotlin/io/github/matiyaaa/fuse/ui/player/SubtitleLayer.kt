package io.github.matiyaaa.fuse.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.playback.BitmapCue
import io.github.matiyaaa.fuse.playback.Cue
import io.github.matiyaaa.fuse.playback.CueTimeline
import io.github.matiyaaa.fuse.playback.TextCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/**
 * Subtitles, drawn by Fuse the same way on every platform, over the picture's own rectangle (give
 * this the picture's bounds). Text cues get the reader's size and lift and keep a file's own
 * styling (ASS colours, placement, outline, boxes); picture cues (PGS, DVD) are scaled into place.
 *
 * [engineCues] are the ones the engine decodes from the stream; [fileCues] come from a file of
 * their own and are looked up at [positionMs] (minus the reader's delay). The cues shown change
 * only when the set changes, so the layer recomposes a few times a cue, not every frame.
 */
@Composable
fun SubtitleLayer(
    engineCues: List<Cue>,
    fileCues: CueTimeline,
    positionMs: () -> Long,
    settings: PlayerSettings,
    modifier: Modifier = Modifier,
) {
    var fromFile by remember { mutableStateOf<List<Cue>>(emptyList()) }
    val position by rememberUpdatedState(positionMs)
    LaunchedEffect(fileCues, settings.subtitleDelayMs) {
        fromFile = emptyList()
        if (fileCues.isEmpty) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            val now = fileCues.at(position() - settings.subtitleDelayMs)
            if (now != fromFile) fromFile = now
        }
    }
    val shown = engineCues + fromFile
    if (shown.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val height = maxHeight
        val width = maxWidth
        for (cue in shown.filterIsInstance<BitmapCue>()) PictureCue(cue, width, height)
        val text = shown.filterIsInstance<TextCue>().sortedBy { it.layer }
        // Pinned cues sit at their point; the rest stack in their corner of the picture.
        for (cue in text.filter { it.posX != null && it.posY != null }) {
            PinnedCue(cue, width, height, settings)
        }
        text.filter { it.posX == null || it.posY == null }.groupBy { it.align }.forEach { (align, cues) ->
            val vertical = (align - 1) / 3
            val horizontal = (align - 1) % 3
            val margin = height * (cues.firstNotNullOfOrNull { it.style.marginV } ?: DEFAULT_MARGIN)
            val lift = if (vertical == 0) height * settings.subtitleLift else 0.dp
            Column(
                Modifier
                    .align(alignmentOf(vertical, horizontal))
                    .padding(horizontal = width * SIDE_MARGIN)
                    .padding(top = if (vertical == 2) margin else 0.dp, bottom = if (vertical == 0) margin + lift else 0.dp)
                    .widthIn(max = width * MAX_WIDTH),
                horizontalAlignment = when (horizontal) {
                    0 -> Alignment.Start
                    2 -> Alignment.End
                    else -> Alignment.CenterHorizontally
                },
            ) {
                for (cue in cues) CueText(cue, height, settings, horizontal)
            }
        }
    }
}

@Composable
private fun PinnedCue(cue: TextCue, width: Dp, height: Dp, settings: PlayerSettings) {
    val horizontal = (cue.align - 1) % 3
    val vertical = (cue.align - 1) / 3
    Box(
        Modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val x = (cue.posX!! * width.toPx()) - p.width * (horizontal / 2f)
            val y = (cue.posY!! * height.toPx()) - p.height * (1f - vertical / 2f)
            layout(constraints.maxWidth, constraints.maxHeight) { p.place(IntOffset(x.toInt(), y.toInt())) }
        },
    ) { CueText(cue, height, settings, horizontal) }
}

/** One cue's lines: an outline drawn under the text (or a box behind it), in the cue's own colours where it has them. */
@Composable
private fun CueText(cue: TextCue, height: Dp, settings: PlayerSettings, horizontal: Int) {
    val density = LocalDensity.current
    val sizePx = with(density) { (height * (cue.style.size ?: DEFAULT_SIZE) * settings.subtitleScale).toPx() }
    val fontSize = with(density) { (sizePx / fontScale).toSp() }
    val outlinePx = with(density) { (height * (cue.style.outline ?: DEFAULT_OUTLINE)).toPx() }.coerceAtLeast(1f)
    val boxColor = cue.style.box?.let { Color(it) } ?: if (settings.subtitleBackground) Color.Black.copy(alpha = 0.72f) else null
    val base = TextStyle(
        fontFamily = Fuse.type.body.fontFamily,
        fontWeight = if (cue.style.bold) FontWeight.Bold else FontWeight.SemiBold,
        fontStyle = if (cue.style.italic) FontStyle.Italic else FontStyle.Normal,
        fontSize = fontSize,
        lineHeight = fontSize * 1.18f,
        textAlign = when (horizontal) {
            0 -> TextAlign.Start
            2 -> TextAlign.End
            else -> TextAlign.Center
        },
    )
    val text = remember(cue) { annotated(cue) }
    val fill = cue.style.color?.let { Color(it) } ?: Color.White
    Box(
        if (boxColor != null) {
            Modifier.background(boxColor, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
        } else {
            Modifier
        },
    ) {
        if (boxColor == null) {
            // The outline: the same text stroked, under the fill, with a soft shadow for light scenes.
            BasicText(
                text,
                style = base.copy(
                    color = cue.style.outlineColor?.let { Color(it) } ?: Color.Black,
                    drawStyle = Stroke(width = outlinePx * 2, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                    shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, outlinePx), blurRadius = outlinePx * 2),
                ),
            )
        }
        BasicText(text, style = base.copy(color = fill))
    }
}

private fun annotated(cue: TextCue): AnnotatedString = buildAnnotatedString {
    cue.lines.forEachIndexed { i, line ->
        if (i > 0) append('\n')
        for (span in line) {
            withStyle(
                SpanStyle(
                    color = span.color?.let { Color(it) } ?: Color.Unspecified,
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = if (span.underline) TextDecoration.Underline else null,
                ),
            ) { append(span.text) }
        }
    }
}

@Composable
private fun PictureCue(cue: BitmapCue, width: Dp, height: Dp) {
    val image: ImageBitmap = remember(cue) { argbBitmap(cue.width, cue.height, cue.pixels) }
    Image(
        image, contentDescription = null, contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .offset(x = width * cue.left, y = height * cue.top)
            .size(width * (cue.right - cue.left), height * (cue.bottom - cue.top)),
    )
}

private fun alignmentOf(vertical: Int, horizontal: Int): Alignment = when (vertical) {
    0 -> when (horizontal) { 0 -> Alignment.BottomStart; 2 -> Alignment.BottomEnd; else -> Alignment.BottomCenter }
    1 -> when (horizontal) { 0 -> Alignment.CenterStart; 2 -> Alignment.CenterEnd; else -> Alignment.Center }
    else -> when (horizontal) { 0 -> Alignment.TopStart; 2 -> Alignment.TopEnd; else -> Alignment.TopCenter }
}

/** A picture from ARGB pixels (bitmap subtitles). */
internal expect fun argbBitmap(width: Int, height: Int, pixels: IntArray): ImageBitmap

/** Text a twentieth of the picture tall, set in from the bottom and sides, at most this wide, outlined this much. */
private const val DEFAULT_SIZE = 0.05f
private const val DEFAULT_MARGIN = 0.06f
private const val SIDE_MARGIN = 0.05f
private const val MAX_WIDTH = 0.9f
private const val DEFAULT_OUTLINE = 0.0022f
