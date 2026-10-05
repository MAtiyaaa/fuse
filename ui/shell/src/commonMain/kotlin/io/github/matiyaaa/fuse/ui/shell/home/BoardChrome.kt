package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.squirclePath
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat

/**
 * The board's empty places while it is arranged: every cell a quiet, rounded well, so where a widget
 * can go and how much room there is reads at a glance. The cells a held or resized widget would take
 * ([target]) are lit in the accent, solid where it fits; [shown] fades all of it in and out.
 *
 * Drawn behind the widgets in one pass, with corners matching the widgets' own.
 */
@Composable
internal fun GridWells(
    geometry: BoardGeometry,
    rows: Int,
    occupied: BoardLayout,
    target: BoardRect?,
    cornerFraction: Float,
    shown: Float,
    modifier: Modifier = Modifier,
) {
    val c = Fuse.colors
    val well = if (c.isDark) c.text.copy(alpha = 0.045f) else c.text.copy(alpha = 0.05f)
    val edge = c.text.copy(alpha = if (c.isDark) 0.09f else 0.1f)
    val accent = c.accent
    val litTarget by fuselineFloat(if (target != null) 1f else 0f, Fuse.motion.fade(Durations.FAST), label = "target")
    Canvas(modifier) {
        if (shown <= 0.01f) return@Canvas
        val corner = minOf(geometry.cellW, geometry.cellH) * cornerFraction
        val cell = squirclePath(geometry.cellW, geometry.cellH, corner, 0.6f)
        val hair = Stroke(1.dp.toPx())
        for (r in 0 until rows) {
            for (col in 0 until geometry.columns) {
                // Cells under a resting widget are covered by it anyway; only the empty ones are drawn.
                if (occupied.occupant(col, r) != null && target?.contains(col, r) != true) continue
                translate(col * geometry.stepX, r * geometry.stepY) {
                    drawPath(cell, well, alpha = shown)
                    drawPath(cell, edge, alpha = shown, style = hair)
                }
            }
        }
        if (target != null && litTarget > 0.01f) {
            val rect = geometry.rect(target)
            val path = squirclePath(rect.width, rect.height, minOf(rect.width, rect.height) * cornerFraction, 0.6f)
            translate(rect.left, rect.top) {
                drawPath(path, accent.copy(alpha = 0.14f), alpha = litTarget * shown)
                drawPath(
                    path, accent, alpha = litTarget * shown,
                    style = Stroke(Size.focusStroke.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(Space.s.toPx(), Space.xs.toPx()))),
                )
            }
        }
    }
}

/**
 * The handles a widget is resized by while Home is arranged: a grip in the middle of each side and
 * an arc at its bottom right corner, each in a finger-sized target. A side that can't move (the
 * board's edge, or the widget at its largest and smallest) is dimmed. [handle] gives each its gesture.
 */
@Composable
internal fun BoxScope.ResizeHandles(
    name: String,
    rect: BoardRect,
    columns: Int,
    active: ResizeEdge?,
    handle: @Composable (ResizeEdge) -> Modifier,
    onPlaced: (ResizeEdge, Rect?) -> Unit,
) {
    for (edge in ResizeEdge.entries) {
        val enabled = canResize(rect, edge, columns)
        val align = when (edge) {
            ResizeEdge.LEFT -> Alignment.CenterStart
            ResizeEdge.RIGHT -> Alignment.CenterEnd
            ResizeEdge.TOP -> Alignment.TopCenter
            ResizeEdge.BOTTOM -> Alignment.BottomCenter
            ResizeEdge.CORNER -> Alignment.BottomEnd
        }
        val out = HANDLE_TARGET / 2
        val shift = when (edge) {
            ResizeEdge.LEFT -> Modifier.offset(x = -out)
            ResizeEdge.RIGHT -> Modifier.offset(x = out)
            ResizeEdge.TOP -> Modifier.offset(y = -out)
            ResizeEdge.BOTTOM -> Modifier.offset(y = out)
            ResizeEdge.CORNER -> Modifier.offset(x = out * 0.5f, y = out * 0.5f)
        }
        Box(
            Modifier
                .align(align)
                .then(shift)
                .size(HANDLE_TARGET)
                .semantics { contentDescription = "Resize $name from its ${edge.name.lowercase()}" }
                .then(placed { onPlaced(edge, it) })
                .then(if (enabled) handle(edge) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Grip(edge, lit = active == edge, enabled = enabled)
        }
    }
}

/** One handle's mark: a short bar along its side, or an arc for the corner. */
@Composable
private fun Grip(edge: ResizeEdge, lit: Boolean, enabled: Boolean) {
    val c = Fuse.colors
    val tone by fuselineFloat(if (lit) 1f else 0f, Fuse.motion.fade(Durations.FAST), label = "grip")
    val color = lerp(c.onArt, c.accent, tone)
    val shadow = c.shadow
    val alpha = if (enabled) 1f else 0.35f
    Canvas(Modifier.size(HANDLE_TARGET)) {
        val w = 4.5.dp.toPx()
        val len = 26.dp.toPx()
        val mid = Offset(size.width / 2, size.height / 2)
        when (edge) {
            ResizeEdge.CORNER -> {
                val r = 16.dp.toPx()
                val end = Offset(mid.x, mid.y)
                val path = Path().apply {
                    moveTo(end.x, end.y - r)
                    quadraticTo(end.x, end.y, end.x - r, end.y)
                }
                drawPath(path, shadow.copy(alpha = 0.45f * alpha), style = Stroke(w + 3.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, color.copy(alpha = alpha), style = Stroke(w, cap = StrokeCap.Round))
            }
            else -> {
                val vertical = edge == ResizeEdge.LEFT || edge == ResizeEdge.RIGHT
                val size = if (vertical) androidx.compose.ui.geometry.Size(w, len) else androidx.compose.ui.geometry.Size(len, w)
                val topLeft = Offset(mid.x - size.width / 2, mid.y - size.height / 2)
                val radius = CornerRadius(w / 2)
                drawRoundRect(shadow.copy(alpha = 0.45f * alpha), topLeft - Offset(1.5.dp.toPx(), 1.5.dp.toPx()), size.copy(size.width + 3.dp.toPx(), size.height + 3.dp.toPx()), CornerRadius(w))
                drawRoundRect(color.copy(alpha = alpha), topLeft, size, radius)
            }
        }
    }
}

/**
 * How a widget looks while Options is held to resize it with the D-pad: an accent frame just outside
 * it, arrows on the sides it can grow from (dimmed where it can't), and its size. Clearly not the
 * lifted look of a widget being moved.
 */
@Composable
internal fun BoxScope.ResizeFrame(rect: BoardRect, columns: Int, shape: Shape) {
    val c = Fuse.colors
    val accent = c.accent
    Box(
        Modifier
            .matchParentSizeOutset(FRAME_GAP)
            .drawBehind {
                val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
                drawPath(outline, accent, style = Stroke(Size.focusStroke.toPx() * 1.25f))
            },
    )
    val growRight = rect.width < BoardGrid.maxWidth(columns)
    val growDown = rect.height < io.github.matiyaaa.fuse.model.BoardSize.MAX_HEIGHT
    Arrow(FuseIcons.ChevronRight, growRight, Modifier.align(Alignment.CenterEnd).offset(x = ARROW_OUT))
    Arrow(FuseIcons.ChevronDown, growDown, Modifier.align(Alignment.BottomCenter).offset(y = ARROW_OUT))
    Arrow(FuseIcons.ChevronLeft, rect.width > 1, Modifier.align(Alignment.CenterStart).offset(x = -ARROW_OUT))
    Arrow(FuseIcons.ChevronUp, rect.height > 1, Modifier.align(Alignment.TopCenter).offset(y = -ARROW_OUT))
}

/** A direction a resize can go: a small accent disc with a chevron, or a quiet one when it can't. */
@Composable
private fun Arrow(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, modifier: Modifier) {
    val c = Fuse.colors
    Box(
        modifier
            .size(ARROW)
            .graphicsLayer {
                shape = CircleShape
                clip = true
                shadowElevation = if (enabled) Elevation.raised.shadow.toPx() else 0f
                alpha = if (enabled) 1f else 0.45f
            }
            .background(if (enabled) c.accent else c.surfaceOverlay),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = Size.iconS, tint = if (enabled) c.onAccent else c.textMuted)
    }
}

/** Fills the parent and reaches [outset] past each of its sides. */
private fun Modifier.matchParentSizeOutset(outset: Dp): Modifier = layout { measurable, constraints ->
    val extra = outset.roundToPx()
    val w = constraints.maxWidth + extra * 2
    val h = constraints.maxHeight + extra * 2
    val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
    layout(constraints.maxWidth, constraints.maxHeight) { p.place(-extra, -extra) }
}

/** Reports where an element is on screen (or null once it is gone), for touches it should keep. */
@Composable
private fun placed(report: (Rect?) -> Unit): Modifier {
    val current by androidx.compose.runtime.rememberUpdatedState(report)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { current(null) } }
    return Modifier.onGloballyPositioned { current(Rect(it.positionInRoot(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))) }
}

/** The badge that takes a widget off Home while arranging: a minus in a disc, in a target big enough to tap. */
@Composable
internal fun RemoveBadge(name: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    Box(
        modifier
            .size(Size.touch * 0.8f)
            .semantics { contentDescription = "Remove $name" }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Size.badge + Space.xxs)
                .graphicsLayer {
                    shape = CircleShape
                    clip = true
                    shadowElevation = Elevation.raised.shadow.toPx()
                }
                .background(c.surfaceOverlay)
                .border(Size.stroke, c.hairlineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(FuseIcons.Minus, size = Size.iconS, tint = c.text)
        }
    }
}

/** A free place on a board being arranged that adds a widget: a dashed outline with a plus. */
@Composable
internal fun AddTile(selected: Boolean, shape: Shape, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val edge = if (selected) c.accent else c.hairlineStrong
    Box(
        modifier
            .clip(shape)
            .background(c.surfaceDim.copy(alpha = if (c.isDark) 0.5f else 0.7f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { contentDescription = "Add a widget" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@Canvas)) }
            val inset = Size.focusStroke.toPx()
            val scaleX = (size.width - inset * 2) / size.width
            val scaleY = (size.height - inset * 2) / size.height
            // Drawn just inside the outline, so the clip never halves the dashes.
            scale(scaleX, scaleY) {
                drawPath(
                    path,
                    edge,
                    style = Stroke(
                        width = (if (selected) Size.focusStroke else Size.stroke).toPx() * 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(Space.s.toPx(), (Space.xs + Space.xxs).toPx())),
                    ),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(Size.chip).clip(CircleShape).background(c.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.Plus, size = Size.iconM, tint = if (selected) c.accent else c.textMuted)
            }
            Spacer(Modifier.height(Space.s))
            FText("Add widget", Fuse.type.label, color = if (selected) c.text else c.textMuted, maxLines = 1)
        }
    }
}

/**
 * The toolbar floating over the board while it is arranged: what to do, and Add widget and Done
 * for touch (the controller has the same in its hints). On a narrow screen only the buttons.
 */
@Composable
internal fun ArrangeBar(compact: Boolean, onAdd: () -> Unit, onDone: () -> Unit) {
    val c = Fuse.colors
    Panel(raised = true, shape = RoundedCornerShape(Radius.pill)) {
        Row(Modifier.padding(start = if (compact) Space.s else Space.xl, end = Space.s, top = Space.s, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (!compact) {
                Column(Modifier.widthIn(max = ARRANGE_TEXT)) {
                    FText("Arranging Home", Fuse.type.bodyStrong, maxLines = 1)
                    FText("Drag a widget anywhere on the grid, or a handle to resize it", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                }
                Spacer(Modifier.width(Space.xl))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FuseButton("Add widget", selected = false, onClick = onAdd, kind = ButtonKind.SECONDARY, icon = FuseIcons.Plus)
                FuseButton("Done", selected = false, onClick = onDone, kind = ButtonKind.PRIMARY)
            }
        }
    }
}

/**
 * Undo and Reset, floating at the top right while Home is arranged: Undo takes back the last change
 * made while arranging, Reset puts the board back as it came (after asking). [focused] is the one
 * the controller is on (0 Undo, 1 Reset), reached by moving up past the board's top row.
 */
@Composable
internal fun ArrangeTools(
    focused: Int?,
    canUndo: Boolean,
    resetLabel: String = "Reset",
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onNewPage: () -> Unit,
) {
    Panel(raised = true, shape = RoundedCornerShape(Radius.pill)) {
        Row(Modifier.padding(Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            FuseButton("Undo", selected = focused == 0, onClick = onUndo, kind = ButtonKind.SECONDARY, icon = FuseIcons.Undo, enabled = canUndo)
            FuseButton(resetLabel, selected = focused == 1, onClick = onReset, kind = ButtonKind.SECONDARY, icon = FuseIcons.RotateCcw)
            FuseButton("New page", selected = focused == 2, onClick = onNewPage, kind = ButtonKind.SECONDARY, icon = FuseIcons.CopyPlus)
        }
    }
}

/** The room the arranging toolbar takes over the board's bottom, and the widest its words get. */
internal val ARRANGE_BAR = 72.dp
private val ARRANGE_TEXT = 420.dp

/** A resize handle's touch target. */
private val HANDLE_TARGET = 40.dp

/** The resize frame's distance from the widget, and its direction arrows. */
private val FRAME_GAP = 5.dp
private val ARROW = 26.dp
private val ARROW_OUT = 18.dp
