package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.matiyaaa.fuse.model.CornerFamily
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.effects.lightEdge
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.flourishOn
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.tween
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/**
 * A square-ish control: its icon in a well at the top, label and state at the bottom, used by the
 * quick menu and the second screen's controls. The caller sizes it.
 *
 * On and off read the same way on every tile: while [active] the well fills with the accent (its
 * icon in the accent's own text colour), the tile takes a soft wash of the accent and the state line
 * turns accent. The change eases over, and the well pops once as it lights, so pressing a toggle is
 * felt. Focus never repaints the tile: it lifts a little inside an outline ring, so on or off still
 * reads while focused. Hover and press answer like every other control.
 *
 * [toggle] makes the state line "On" or "Off"; otherwise it shows [detail]. Labels sit on the same
 * line in every tile whether or not there is a state line. [compact] fits a short tile (about 80 dp
 * tall). An [unavailable] tile is dimmed but still answers a press, so it can say why.
 */
@Composable
fun ControlTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    detail: String? = null,
    toggle: Boolean = false,
    compact: Boolean = false,
    unavailable: Boolean = false,
    /** What a long press does (the Screenshot tile records); null leaves long presses alone. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val shape = controlTileShape()
    val bg by fuselineColor(
        when {
            active -> c.accent.copy(alpha = if (c.isDark) 0.13f else 0.1f)
            selected -> c.text.copy(alpha = if (c.isDark) 0.11f else 0.08f)
            else -> c.text.copy(alpha = if (c.isDark) 0.06f else 0.045f)
        },
        motion.tween(Durations.BASE),
        label = "tile",
    )
    val lift by fuselineFloat(if (selected) 1f else 0f, motion.focusSpring(), label = "tile lift")
    val stateColor by fuselineColor(if (active) c.accent else c.textMuted, motion.tween(Durations.BASE), label = "tile state")
    val ring = c.focus
    val edgeRest = Elevation.tile.edgeAlpha(c.isDark)
    val edgeLit = Elevation.tileFocused.edgeAlpha(c.isDark)
    val gap = Size.focusGap
    val ringWidth = Size.focusStroke
    Box(
        modifier
            .graphicsLayer {
                val s = if (motion.reduced) 1f else 1f + 0.04f * lift
                scaleX = s
                scaleY = s
            }
            // The ring sits a gap outside the tile in the tile's own shape, so focusing never moves
            // anything; the path is built once per size and only its strength changes.
            .drawWithCache {
                val g = gap.toPx() + ringWidth.toPx() / 2
                val outer = androidx.compose.ui.geometry.Size(size.width + g * 2, size.height + g * 2)
                val path = Path().apply { addOutline(shape.createOutline(outer, layoutDirection, this@drawWithCache)) }
                val stroke = Stroke(ringWidth.toPx())
                onDrawBehind {
                    if (lift > 0.01f) translate(-g, -g) { drawPath(path, ring, alpha = lift.coerceIn(0f, 1f), style = stroke) }
                }
            }
            // Before the surface, so a press pushes the whole tile down, not just what is on it.
            .fuseClickable(shape = shape, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .graphicsLayer {
                this.shape = shape
                clip = true
            }
            .background(bg)
            .lightEdge(shape, { edgeRest + (edgeLit - edgeRest) * lift.coerceIn(0f, 1f) })
            .semantics { this.selected = selected }
            .padding(if (compact) Space.s else Space.m),
    ) {
        val dim = Modifier.alpha(if (unavailable) 0.45f else 1f)
        // The well above the words when the tile is tall enough for both, else beside them: a short
        // tile (the second screen of a small handheld) never lets the icon ride over the label.
        Layout(
            content = {
                ControlWell(icon, active, compact, dim)
                Column(dim) {
                    FText(label, Fuse.type.label, color = c.text, maxLines = 1)
                    // The state line is always laid out, so every label sits on the same line.
                    val state = if (toggle) (if (active) "On" else "Off") else detail
                    FText(state.orEmpty(), Fuse.type.caption, color = stateColor, maxLines = 1)
                }
            },
            modifier = Modifier.matchParentSize(),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val well = measurables[0].measure(loose)
            val gap = Space.s.roundToPx()
            val wordsHeight = measurables[1].minIntrinsicHeight(constraints.maxWidth)
            val stacked = well.height + gap + wordsHeight <= constraints.maxHeight
            val side = measurables[1].measure(if (stacked) loose else loose.copy(maxWidth = (constraints.maxWidth - well.width - gap).coerceAtLeast(0)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (stacked) {
                    well.place(0, 0)
                    side.place(0, constraints.maxHeight - side.height)
                } else {
                    well.place(0, (constraints.maxHeight - well.height) / 2)
                    side.place(well.width + gap, (constraints.maxHeight - side.height) / 2)
                }
            }
        }
    }
}

/**
 * The icon of a control: a well in the theme's control shape, lit with the accent while [active].
 * It pops once as it lights (not under Reduced motion or in Low Power Mode).
 */
@Composable
internal fun ControlWell(icon: ImageVector, active: Boolean, compact: Boolean = false, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val flourish = motion.flourishOn(Fuse.quality)
    val fill by fuselineColor(
        if (active) c.accent else c.text.copy(alpha = if (c.isDark) 0.09f else 0.07f),
        motion.tween(Durations.BASE),
        label = "well",
    )
    val tint by fuselineColor(if (active) c.onAccent else c.text, motion.tween(Durations.BASE), label = "well icon")
    val pop = remember { FuselineValue(0f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(active) {
        // Only a change pops, never the first frame.
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (active && flourish) {
            pop.snapTo(1f)
            pop.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 520f))
        }
    }
    val shape = controlWellShape()
    Box(
        modifier
            .size(if (compact) Size.chipCompact else Size.chip)
            .graphicsLayer {
                val s = 1f + 0.14f * pop.value
                scaleX = s
                scaleY = s
                this.shape = shape
                clip = true
            }
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = if (compact) Size.iconS else Size.iconM, tint = tint)
    }
}

/** Control tiles take the tiles' continuous corners, so they read as small objects like tiles. */
@Composable
@ReadOnlyComposable
internal fun controlTileShape(): Shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.12f))

/**
 * Icon wells match the menu rows' wells (a small cousin of a tile, round in pill themes), so every
 * icon in the quick menu has the same weight and shape.
 */
@Composable
@ReadOnlyComposable
internal fun controlWellShape(): Shape = when (Fuse.geometry.family) {
    CornerFamily.PILL -> CircleShape
    else -> SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.12f) + 0.06f)
}

/**
 * One highlight for a column of rows that are not a [io.github.matiyaaa.fuse.ui.designsystem.components.MenuList]
 * (the quick menu's sliders and actions, Manage media's slots and matches), gliding from row to
 * row the way a MenuList's does: its leading edge first, the other following, with the accent bar
 * at its start. It fades out while focus is elsewhere (a tile, another pane) and comes back in place,
 * without travelling, on the row focus moves to.
 *
 * Rows report where they sit with [place] (from `onPlaced`, relative to the column), [Follow] picks
 * the target, and [drawModifier] goes on the column after its scroll and padding, so it draws in
 * the same coordinates as the rows. Rows then draw no highlight of their own.
 */
@Stable
internal class RowHighlight {
    val top = FuselineValue(0f)
    val bottom = FuselineValue(0f)
    val alpha = FuselineValue(0f)

    /** Each row's top and bottom in the list, in pixels. */
    val bounds = mutableStateMapOf<Int, Pair<Float, Float>>()

    fun place(row: Int, top: Float, bottom: Float) {
        val b = top to bottom
        if (bounds[row] != b) bounds[row] = b
    }

    /** Glides to [target] (a row's top and bottom), or fades away when it is null. */
    @Composable
    fun Follow(target: Pair<Float, Float>?) {
        val motion = Fuse.motion
        val visible = target != null
        LaunchedEffect(visible) {
            alpha.animateTo(if (visible) 1f else 0f, motion.tween(if (visible) Durations.FAST else Durations.INSTANT))
        }
        LaunchedEffect(target) {
            val t = target ?: return@LaunchedEffect
            if (motion.reduced || alpha.value < 0.05f) {
                top.snapTo(t.first)
                bottom.snapTo(t.second)
                return@LaunchedEffect
            }
            val down = t.first >= top.value
            launch { top.animateTo(t.first, if (down) motion.glideTrail() else motion.glide()) }
            launch { bottom.animateTo(t.second, if (down) motion.glide() else motion.glideTrail()) }
        }
    }

    /** Draws the highlight and its accent bar behind the rows; the values are read only while drawing. */
    @Composable
    fun drawModifier(): Modifier {
        val c = Fuse.colors
        val fill = c.text.copy(alpha = if (c.isDark) 0.1f else 0.07f)
        val accent = c.accent
        val outline = if (Fuse.look.highContrastFocus) c.focus else null
        val corner = Fuse.geometry.control
        return Modifier.drawBehind {
            val a = alpha.value
            if (a <= 0.01f) return@drawBehind
            val t = top.value
            val h = bottom.value - t
            if (h <= 0f) return@drawBehind
            val r = corner.toPx().coerceAtMost(h / 2)
            drawRoundRect(fill, Offset(0f, t), GeoSize(size.width, h), CornerRadius(r), alpha = a)
            if (outline != null) {
                val sw = Size.focusStroke.toPx()
                drawRoundRect(outline, Offset(sw / 2, t + sw / 2), GeoSize(size.width - sw, h - sw), CornerRadius((r - sw / 2).coerceAtLeast(0f)), alpha = a, style = Stroke(sw))
            }
            val bh = BAR_HEIGHT.toPx().coerceAtMost(h - Space.s.toPx())
            // On strongly rounded highlights (pill themes) the bar steps in to stay inside the curve.
            val curve = if (r > bh / 2) r - sqrt(r * r - (bh / 2) * (bh / 2)) else 0f
            val bw = BAR_WIDTH.toPx()
            drawRoundRect(accent, Offset(curve, t + (h - bh) / 2), GeoSize(bw, bh), CornerRadius(bw / 2), alpha = a)
        }
    }
}

@Composable
internal fun rememberRowHighlight(): RowHighlight = remember { RowHighlight() }

/** The selection bar of a row, as [io.github.matiyaaa.fuse.ui.designsystem.components.MenuRow] draws it. */
private val BAR_WIDTH = Size.sparkHeight
private val BAR_HEIGHT = Space.xl - Space.xxs

/** Where a row's content starts after the selection bar and its gap, as in a menu row. */
internal val ROW_CONTENT_START = Size.sparkHeight + Space.m
