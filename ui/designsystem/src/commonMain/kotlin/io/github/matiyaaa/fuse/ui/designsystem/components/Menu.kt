package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.CornerFamily
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.NavEvent
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseColors
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseMotion
import io.github.matiyaaa.fuse.ui.designsystem.theme.flourishOn
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import io.github.matiyaaa.fuse.ui.designsystem.focus.KeepSelectionInView

/** What the right side of a menu row shows. */
sealed interface Trailing {
    data object None : Trailing
    data object Chevron : Trailing
    data class Value(val text: String) : Trailing
    data class Switch(val on: Boolean) : Trailing

    /**
     * A choice mark: a filled check when [on], a faint empty ring when not, so a list of choices
     * reads as one you pick from.
     */
    data class Check(val on: Boolean) : Trailing
    /** Marks a setting as coming from a parent scope ("Global", "PlayStation 2"). */
    data class Inherited(val from: String) : Trailing
    data class Badge(val text: String) : Trailing
    /** A running job: [text] over a small bar ([fraction] null while its size isn't known). */
    data class Progress(val fraction: Float?, val text: String) : Trailing
    /** A group that opens in place: a quiet [summary] and a chevron that turns when [open]. */
    data class Disclosure(val open: Boolean, val summary: String? = null) : Trailing

    /**
     * A level setting (volume, dimming, opacity): a short meter filled to [fraction] (0..1) and its
     * value as [text] ("30%"), so the row shows how much at a glance and the fill eases on change.
     */
    data class Level(val fraction: Float, val text: String) : Trailing
}

@Immutable
data class MenuAction(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val detail: String? = null,
    val trailing: Trailing = Trailing.None,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    /** Shown dimmed with this note instead of doing anything (integration unfinished, not supported here). */
    val unavailableReason: String? = null,
    /** Artwork before the label, such as a game's logo. */
    val art: MenuArt? = null,
    /** How deep in an open group the row sits; each level steps it in. */
    val indent: Int = 0,
    /**
     * The group this row belongs to in a [MenuList]. Where it changes from the row above, the list
     * draws a divider, with the group's name as a small label when it isn't blank. Null rows form no
     * group of their own.
     */
    val section: String? = null,
    val onSelect: () -> Unit = {},
)

/**
 * Artwork in a menu row: a logo fitted into a wide slot, or square art at the slot's start. Every
 * row with art uses the same slot, so labels line up whether a game has a logo or not.
 */
data class MenuArt(
    val model: Any?,
    val square: Boolean = false,
    /** With no artwork (or while it can't load), a generated square with this title's initials. */
    val fallbackTitle: String? = null,
    /** ARGB colour for the generated square. */
    val accent: Long = 0xFF8A93A6,
    /** The slot is logo-wide, so labels line up with rows that show a logo; off in lists of square art only. */
    val wide: Boolean = true,
)

/**
 * One row in a menu or settings list. Selection is shown with a raised background, a short accent
 * bar at its start and brighter text, so it's visible in any colour theme and to colour-blind users.
 * Icons sit in small wells shaped like the theme's tiles, so every row's icon has the same weight;
 * destructive rows tint the well, the label and their highlight with the danger colour.
 *
 * [highlight] false leaves the background and bar to a parent that draws one highlight gliding from
 * row to row ([MenuList] does); the row keeps the bar's room so nothing shifts.
 */
@Composable
fun MenuRow(
    action: MenuAction,
    selected: Boolean,
    modifier: Modifier = Modifier,
    /** Shown as the current choice without controller focus (a quieter background, no bar). */
    marked: Boolean = false,
    onClick: () -> Unit = action.onSelect,
    highlight: Boolean = true,
) {
    val c = Fuse.colors
    val t = Fuse.type
    val motion = Fuse.motion
    val available = action.enabled && action.unavailableReason == null
    val interaction = remember { MutableInteractionSource() }
    val press = rememberAtomPress(interaction, available)
    val sel by animateFloatAsState(if (selected) 1f else 0f, motion.tween(Durations.FAST), label = "row")
    val bg by animateColorAsState(
        when {
            !highlight -> Color.Transparent
            selected -> c.rowHighlight(action.destructive)
            marked -> c.rowMarked()
            else -> Color.Transparent
        },
        motion.tween(Durations.FAST),
        label = "rowbg",
    )
    val tint = when {
        !available -> c.textFaint
        action.destructive -> c.danger
        else -> c.text
    }
    val hover = c.hoverOverlay()
    val shape = RoundedCornerShape(Fuse.geometry.control)
    val outline = if (highlight && selected && Fuse.look.highContrastFocus) c.focus else null
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Size.row)
            .graphicsLayer {
                val s = if (motion.reduced) 1f else 1f - 0.015f * press.pressed
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(bg)
            .then(if (outline != null) Modifier.border(Size.focusStroke, outline, shape) else Modifier)
            .drawBehind { if (!selected && press.hovered > 0f) drawRect(hover, alpha = press.hovered) }
            .clickable(interaction, null, enabled = available, onClick = onClick)
            // Screen readers (and the UI audit) can tell which row the controller is on.
            .semantics { this.selected = selected }
            .padding(end = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (action.indent > 0) {
            // Rows inside an open group step in behind a faint rule, so the group reads as one.
            Spacer(Modifier.width(Space.m))
            Box(Modifier.width(1.dp).height(Size.row - Space.m).background(c.text.copy(alpha = 0.1f)))
            Spacer(Modifier.width(Space.l * action.indent - Space.m - 1.dp))
        }
        Box(
            Modifier
                .width(BAR_WIDTH)
                .heightIn(min = BAR_HEIGHT)
                .graphicsLayer {
                    val on = if (highlight) sel else 0f
                    scaleY = on
                    alpha = on
                }
                .clip(RoundedCornerShape(2.dp))
                .background(if (action.destructive) c.danger else c.accent),
        )
        Spacer(Modifier.width(Space.m))
        if (action.icon != null) {
            IconWell(action.icon, tint = tint, selected = selected, destructive = action.destructive, available = available)
            Spacer(Modifier.width(Space.m))
        }
        action.art?.let { art ->
            Box(Modifier.width(if (art.wide) MENU_ART_WIDTH else MENU_ART_HEIGHT).height(MENU_ART_HEIGHT), contentAlignment = Alignment.CenterStart) {
                val square = Modifier.size(MENU_ART_HEIGHT).clip(RoundedCornerShape(Fuse.geometry.control))
                val generated: @Composable () -> Unit = {
                    art.fallbackTitle?.let { GeneratedArt(it, Color(art.accent), square, slot = ArtSlot.ICON) }
                }
                if (art.model != null) {
                    Artwork(
                        art.model,
                        if (art.square) square else Modifier.fillMaxSize(),
                        contentScale = if (art.square) ContentScale.Crop else ContentScale.Fit,
                        focusX = 0f,
                        fallback = generated,
                    )
                } else {
                    generated()
                }
            }
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f).padding(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            FText(action.label, t.bodyStrong, color = if (selected) tint else tint.copy(alpha = 0.92f), maxLines = 2)
            val detail = action.unavailableReason ?: action.detail
            if (detail != null) FText(detail, t.caption, color = c.textMuted, maxLines = 3)
        }
        Spacer(Modifier.width(Space.m))
        RowTrailing(action.trailing, selected = selected, available = available, sel = { sel })
    }
}

/** The icon of a row, in a small well shaped like the theme's tiles. */
@Composable
private fun IconWell(icon: ImageVector, tint: Color, selected: Boolean, destructive: Boolean, available: Boolean) {
    val c = Fuse.colors
    val fill by animateColorAsState(
        when {
            destructive -> c.danger.copy(alpha = if (selected) 0.2f else 0.12f)
            selected -> c.text.copy(alpha = if (c.isDark) 0.13f else 0.1f)
            else -> c.text.copy(alpha = if (c.isDark) 0.07f else 0.055f)
        },
        Fuse.motion.tween(Durations.FAST),
        label = "well",
    )
    val shape = wellShape()
    Box(
        Modifier
            .size(ICON_WELL)
            .clip(shape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = WELL_ICON, tint = if (selected || destructive) tint else tint.copy(alpha = if (available) 0.82f else 0.5f))
    }
}

/** Wells take the tiles' continuous corners, so a row's icon is a small cousin of a tile. */
@Composable
@ReadOnlyComposable
private fun wellShape(): Shape = when (Fuse.geometry.family) {
    CornerFamily.PILL -> CircleShape
    else -> SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.12f) + 0.06f)
}

@Composable
private fun RowTrailing(trailing: Trailing, selected: Boolean, available: Boolean, sel: () -> Float) {
    val c = Fuse.colors
    val t = Fuse.type
    val motion = Fuse.motion
    when (trailing) {
        Trailing.None -> Unit
        // The chevron leans the way it leads when its row is selected.
        Trailing.Chevron -> FuseIcon(
            FuseIcons.ChevronRight,
            size = TRAILING_ICON,
            tint = if (selected) c.text else c.textMuted,
            modifier = Modifier.graphicsLayer { translationX = (if (motion.reduced) 0f else 2.dp.toPx()) * sel() },
        )
        is Trailing.Value -> FText(trailing.text, t.label, color = if (selected) c.text else c.textMuted, maxLines = 1)
        is Trailing.Switch -> Toggle(trailing.on, enabled = available)
        is Trailing.Check -> CheckMark(trailing.on)
        is Trailing.Inherited -> Chip(trailing.from, icon = FuseIcons.Layers, color = c.textMuted)
        is Trailing.Badge -> Badge(trailing.text, color = c.accent, filled = false)
        is Trailing.Progress -> Column(Modifier.width(PROGRESS_WIDTH), horizontalAlignment = Alignment.End) {
            FText(trailing.text, t.label, color = c.textMuted, maxLines = 1)
            Spacer(Modifier.height(Space.xs))
            ProgressBar(trailing.fraction, Modifier.fillMaxWidth())
        }
        is Trailing.Disclosure -> Row(verticalAlignment = Alignment.CenterVertically) {
            val turn by animateFloatAsState(if (trailing.open) 180f else 0f, motion.focusSpring(), label = "disclosure")
            if (trailing.summary != null) {
                FText(trailing.summary, t.label, color = c.textMuted, maxLines = 1)
                Spacer(Modifier.width(Space.s))
            }
            FuseIcon(FuseIcons.ChevronDown, size = TRAILING_ICON, tint = if (trailing.open || selected) c.text else c.textMuted, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        is Trailing.Level -> Row(verticalAlignment = Alignment.CenterVertically) {
            LevelMeter(trailing.fraction, selected)
            Spacer(Modifier.width(Space.m))
            FText(
                trailing.text,
                t.label.copy(fontFeatureSettings = "tnum"),
                color = if (selected) c.text else c.textMuted,
                maxLines = 1,
                align = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.width(LEVEL_TEXT),
            )
        }
    }
}

/** The current choice: an accent disc with a check that pops in; other choices a faint ring. */
@Composable
private fun CheckMark(on: Boolean) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val k by animateFloatAsState(
        if (on) 1f else 0f,
        if (motion.reduced) motion.tween(Durations.INSTANT) else spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "check",
    )
    val ring = c.text.copy(alpha = if (c.isDark) 0.22f else 0.26f)
    val pop = !motion.reduced
    val disc = c.accent
    val mark = c.onAccent
    Spacer(
        Modifier.size(CHECK_SIZE).drawWithCache {
            val d = size.minDimension
            val check = Path().apply {
                moveTo(d * 0.29f, d * 0.52f)
                lineTo(d * 0.44f, d * 0.67f)
                lineTo(d * 0.72f, d * 0.37f)
            }
            val stroke = Stroke(d * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val ringStroke = Stroke(1.5.dp.toPx())
            onDrawBehind {
                val r = d / 2
                if (k < 1f) drawCircle(ring, r - ringStroke.width / 2, alpha = 1f - k.coerceIn(0f, 1f), style = ringStroke)
                if (k > 0f) {
                    if (pop) drawCircle(disc, r * k.coerceAtMost(1.08f)) else drawCircle(disc, r, alpha = k.coerceIn(0f, 1f))
                    drawPath(check, mark, alpha = ((k - 0.4f) / 0.6f).coerceIn(0f, 1f), style = stroke)
                }
            }
        },
    )
}

/** A short meter for [Trailing.Level]. */
@Composable
private fun LevelMeter(fraction: Float, selected: Boolean) {
    val c = Fuse.colors
    val v by animateFloatAsState(fraction.coerceIn(0f, 1f), Fuse.motion.value(), label = "level")
    val track = c.text.copy(alpha = 0.12f)
    val fill = if (selected) c.accent else c.text.copy(alpha = 0.55f)
    Spacer(
        Modifier.width(LEVEL_METER).height(Size.track).drawBehind {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (v > 0f) drawRoundRect(fill, size = size.copy(width = (size.width * v).coerceAtLeast(size.height)), cornerRadius = r)
        },
    )
}

/**
 * The top of a menu or choice list: what it is about, as an [icon] in a well (or [leading] art,
 * such as a game's cover, clipped to the same well), a [title] and a quiet [subtitle], with a
 * hairline under it. Its icon lines up with the rows' icons below when both sit in the same padded
 * column as the [MenuList].
 */
@Composable
fun MenuHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    divider: Boolean = true,
    /** Choice lists that explain themselves may let the subtitle run longer. */
    subtitleMaxLines: Int = 2,
) {
    val c = Fuse.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = ROW_CONTENT_START, end = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> {
                    Box(Modifier.size(HEADER_ART).clip(wellShape()), contentAlignment = Alignment.Center) { leading() }
                    Spacer(Modifier.width(Space.m))
                }
                icon != null -> {
                    Box(
                        Modifier.size(HEADER_ART).clip(wellShape()).background(c.text.copy(alpha = if (c.isDark) 0.08f else 0.06f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        FuseIcon(icon, size = Size.iconL, tint = c.text)
                    }
                    Spacer(Modifier.width(Space.m))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                FText(title, Fuse.type.titleSmall, maxLines = 2)
                if (subtitle != null) FText(subtitle, Fuse.type.caption, color = c.textMuted, maxLines = subtitleMaxLines)
            }
        }
        if (divider) {
            Spacer(Modifier.height(Space.m))
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.xs).height(Size.divider).background(c.hairline))
            Spacer(Modifier.height(Space.s))
        }
    }
}

/** A group's divider and name inside a [MenuList]. */
@Composable
private fun MenuSectionHeader(label: String, first: Boolean) {
    val c = Fuse.colors
    Column(Modifier.fillMaxWidth().padding(top = if (first) 0.dp else Space.xs, bottom = Space.xxs)) {
        if (!first) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.xs).height(Size.divider).background(c.hairline))
        }
        if (label.isNotBlank()) {
            SectionLabel(label, Modifier.padding(start = ROW_CONTENT_START, top = if (first) Space.xs else Space.m, bottom = Space.xs))
        } else if (!first) {
            Spacer(Modifier.height(Space.xs))
        }
    }
}

/**
 * A vertical list of [actions] driven by [selection]. Handles its own Up/Down/Select; call from
 * inside an [io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer] via [handleMenuAction].
 *
 * One highlight glides from row to row as the selection moves (stretching a little on the way, and
 * reshaping to rows of different heights), instead of rows lighting up one after another. It stays
 * on its row while the list scrolls, quietens to a marker when [dimSelection] and fades away when
 * [showSelection] is off. Rows with a [MenuAction.section] form groups with a divider between them.
 */
@Composable
fun MenuList(
    actions: List<MenuAction>,
    selection: LinearSelection,
    modifier: Modifier = Modifier,
    /** False while focus is elsewhere (another pane), so no row looks selected. */
    showSelection: Boolean = true,
    /** Keeps a quiet marker on the selected row while focus is in another pane (settings sections). */
    dimSelection: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    /** Takes all the height it may; off for lists in dialogs, which are only as tall as their rows. */
    fill: Boolean = true,
    /**
     * Fades the top and bottom while there are rows beyond them, in the colour of the [Panel] the
     * list sits on (a plain gradient, no offscreen layer). Off outside a panel.
     */
    fadeEdges: Boolean = false,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val state = rememberLazyListState()
    val headerOffset = if (header != null) 1 else 0
    // Where each action sits among the list's items, after the header and any group dividers. Keyed
    // on what the rows are, not on the list object, which callers often rebuild every composition.
    val layoutKey = actions.map { Triple(it.id, it.section, it.indent) }
    val layout = remember(layoutKey, headerOffset) { MenuLayout.of(actions, headerOffset) }
    val currentLayout by rememberUpdatedState(layout)
    // The row a finger just chose: the highlight lands on it at once and the list doesn't move
    // under the finger. A controller or keyboard move clears it.
    val touched = remember { intArrayOf(-1) }
    var touchedIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(selection.index) { if (selection.index != touchedIndex) touchedIndex = -1 }
    // The list scrolls only as far as it takes to show the chosen row whole, with about a row to
    // spare, the way menus scroll; on the first row it shows the very top, so a group name above
    // that row stays in view (a [header] keeps the first row followed instead).
    val margin = with(LocalDensity.current) { MENU_SCROLL_MARGIN.roundToPx() }
    KeepSelectionInView(
        state,
        { if (selection.index == 0 && headerOffset == 0) 0 else currentLayout.itemOf(selection.index) },
        margin,
        enabled = { touchedIndex < 0 || touchedIndex != selection.index },
    )

    // The highlight: its top and bottom edges glide (in action index space) on their own springs.
    // The selection is read in the effect and in drawing only, never in composition, so moving
    // along a long list (Storage has hundreds of rows) doesn't recompose the list itself.
    val currentActions by rememberUpdatedState(actions)
    val start = remember { selection.index.coerceIn(0, (actions.size - 1).coerceAtLeast(0)).toFloat() }
    val top = remember { Animatable(start) }
    val bottom = remember { Animatable(start) }
    val visible = showSelection && actions.isNotEmpty()
    val shown by animateFloatAsState(if (visible) 1f else 0f, motion.tween(if (visible) Durations.FAST else Durations.INSTANT), label = "hl")
    val quiet by animateFloatAsState(if (dimSelection) 1f else 0f, motion.tween(Durations.BASE), label = "hlQuiet")
    val dangerMix = remember { Animatable(0f) }
    val ids = layout.ids
    val lastIds = remember { arrayOfNulls<List<String>>(1) }
    LaunchedEffect(ids) {
        // A new list starts its highlight in place instead of travelling.
        var newList = lastIds[0] != null && lastIds[0] != ids
        lastIds[0] = ids
        snapshotFlow { selection.index.coerceIn(0, (currentActions.size - 1).coerceAtLeast(0)) }.collect { index ->
            val target = index.toFloat()
            launch { dangerMix.animateTo(if (currentActions.getOrNull(index)?.destructive == true) 1f else 0f, motion.tween(Durations.FAST)) }
            // A highlight nobody can see, or a row a finger chose, lands at once (a tap must never
            // show a neighbouring row first).
            val tapped = touched[0] == index
            touched[0] = -1
            if (motion.reduced || shown < 0.05f || newList || tapped) {
                newList = false
                top.snapTo(target)
                bottom.snapTo(target)
                return@collect
            }
            // A long jump (a page, a held direction) glides in from the neighbouring row only: the list
            // is scrolling to follow already, and a highlight sweeping past many rows would distract.
            if (abs(target - top.value) > 1.5f || abs(target - bottom.value) > 1.5f) {
                val from = if (target > top.value) target - 1f else target + 1f
                top.snapTo(from)
                bottom.snapTo(from)
            }
            val down = target >= bottom.value
            val lead = spring<Float>(dampingRatio = 0.9f, stiffness = 1400f)
            val trail = spring<Float>(dampingRatio = 0.95f, stiffness = 700f)
            launch { top.animateTo(target, if (down) trail else lead) }
            launch { bottom.animateTo(target, if (down) lead else trail) }
        }
    }
    val fillSelected = c.rowHighlight(false)
    val fillDanger = c.rowHighlight(true)
    val fillQuiet = c.rowMarked()
    val accent = c.accent
    val danger = c.danger
    val corner = Fuse.geometry.control
    val outline = if (Fuse.look.highContrastFocus) c.focus else null
    // Rows rise into place and fade in once, a little after one another, when the list first
    // appears (never again on changes or moves). Off under Reduced motion and in Low Power Mode.
    // The rise and stagger are the motion profile's, the same as every other reveal.
    val revealOn = motion.flourishOn(Fuse.quality)
    val riseMs = motion.ms(REVEAL_RISE_MS)
    val staggerMs = motion.staggerMs
    val revealMs = riseMs + staggerMs * (REVEAL_ROWS - 1)
    val reveal = remember { Animatable(if (revealOn) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (reveal.value < 1f) reveal.animateTo(1f, tween(revealMs, easing = LinearEasing))
    }
    val revealAt: (Int) -> Float = { i ->
        if (reveal.value >= 1f) {
            1f
        } else {
            val elapsed = reveal.value * revealMs
            val t = ((elapsed - staggerMs * i.coerceAtMost(REVEAL_ROWS - 1)) / riseMs).coerceIn(0f, 1f)
            Easings.Enter.transform(t)
        }
    }
    val rise = motion.revealRise

    val panelFill = if (fadeEdges) LocalPanelFill.current else null
    val edgeTop = remember { Animatable(0f) }
    val edgeBottom = remember { Animatable(0f) }
    if (panelFill != null) {
        LaunchedEffect(state) {
            snapshotFlow { state.canScrollBackward to state.canScrollForward }.collect { (up, down) ->
                launch { edgeTop.animateTo(if (up) 1f else 0f, motion.tween(Durations.FAST)) }
                launch { edgeBottom.animateTo(if (down) 1f else 0f, motion.tween(Durations.FAST)) }
            }
        }
    }
    val edges = if (panelFill == null) Modifier else Modifier.drawWithCache {
        val top = MENU_EDGE_TOP.toPx().coerceAtMost(size.height / 3)
        val bottom = MENU_EDGE_BOTTOM.toPx().coerceAtMost(size.height / 3)
        val topBrush = Brush.verticalGradient(0f to panelFill, 1f to panelFill.copy(alpha = 0f), startY = 0f, endY = top)
        val bottomBrush = Brush.verticalGradient(0f to panelFill.copy(alpha = 0f), 1f to panelFill, startY = size.height - bottom, endY = size.height)
        onDrawWithContent {
            drawContent()
            if (edgeTop.value > 0f) drawRect(topBrush, size = androidx.compose.ui.geometry.Size(size.width, top), alpha = edgeTop.value)
            if (edgeBottom.value > 0f) drawRect(bottomBrush, topLeft = Offset(0f, size.height - bottom), size = androidx.compose.ui.geometry.Size(size.width, bottom), alpha = edgeBottom.value)
        }
    }
    LazyColumn(
        modifier = (if (fill) modifier.fillMaxHeight() else modifier).then(edges).drawWithCache {
            val outlineWidth = Size.focusStroke.toPx()
            val outlineStroke = Stroke(outlineWidth)
            onDrawBehind {
            if (shown <= 0.01f || actions.isEmpty()) return@onDrawBehind
            val info = state.layoutInfo
            val t = layout.edge(top.value, info, gapPx = Space.xxs.toPx(), bottom = false) ?: return@onDrawBehind
            val b = layout.edge(bottom.value, info, gapPx = Space.xxs.toPx(), bottom = true) ?: return@onDrawBehind
            if (b <= t) return@onDrawBehind
            val h = b - t
            val r = corner.toPx().coerceAtMost(h / 2)
            val danger01 = dangerMix.value
            val fillColor = lerp(lerp(fillSelected, fillDanger, danger01), fillQuiet, quiet)
            val arrived = revealAt(selection.index)
            clipRect {
                drawRoundRect(fillColor, Offset(0f, t), size.copy(height = h), CornerRadius(r), alpha = shown * arrived)
                // High contrast focus outlines the selected row as well (not the quiet marker).
                if (outline != null) {
                    val sw = outlineWidth
                    drawRoundRect(
                        outline,
                        Offset(sw / 2, t + sw / 2),
                        androidx.compose.ui.geometry.Size(size.width - sw, h - sw),
                        CornerRadius((r - sw / 2).coerceAtLeast(0f)),
                        alpha = shown * arrived * (1f - quiet),
                        style = outlineStroke,
                    )
                }
                val barAlpha = shown * arrived * (1f - quiet)
                if (barAlpha > 0.01f) {
                    val bh = BAR_HEIGHT.toPx().coerceAtMost(h - 8.dp.toPx())
                    // On strongly rounded highlights (pill themes) the bar steps in to stay inside the curve.
                    val curve = if (r > bh / 2) r - sqrt(r * r - (bh / 2) * (bh / 2)) else 0f
                    val x = layout.indentAt((top.value + bottom.value) / 2) * Space.l.toPx() + curve
                    drawRoundRect(
                        lerp(accent, danger, danger01),
                        Offset(x, t + (h - bh) / 2),
                        androidx.compose.ui.geometry.Size(BAR_WIDTH.toPx(), bh),
                        CornerRadius(2.dp.toPx()),
                        alpha = barAlpha,
                    )
                }
            }
            }
        },
        state = state,
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        if (header != null) item(key = "menu.header") { header() }
        actions.forEachIndexed { i, a ->
            val arrive = Modifier.graphicsLayer {
                val p = revealAt(i)
                alpha = p
                translationY = (1f - p) * rise.toPx()
            }
            layout.sectionBefore(i)?.let { label ->
                item(key = "menu.section.$i.${a.id}") { Box(arrive) { MenuSectionHeader(label, first = i == 0) } }
            }
            item(key = a.id) {
                MenuRow(
                    a,
                    modifier = arrive,
                    selected = showSelection && !dimSelection && i == selection.index,
                    marked = showSelection && dimSelection && i == selection.index,
                    highlight = false,
                    onClick = {
                        touched[0] = i
                        touchedIndex = i
                        selection.select(i, actions.size)
                        if (a.enabled && a.unavailableReason == null) a.onSelect()
                    },
                )
            }
        }
    }
}

/**
 * How a [MenuList]'s actions map onto its lazy items (a header and group dividers sit between them)
 * and where an action's row is, for the gliding highlight.
 */
private class MenuLayout(
    val ids: List<String>,
    private val items: IntArray,
    private val sections: Array<String?>,
    private val indents: IntArray,
) {
    fun itemOf(action: Int): Int = if (items.isEmpty()) 0 else items[action.coerceIn(0, items.size - 1)]

    fun sectionBefore(action: Int): String? = sections.getOrNull(action)

    /** The indent at a (fractional) action position, blended between rows. */
    fun indentAt(pos: Float): Float {
        if (indents.isEmpty()) return 0f
        val i = pos.toInt().coerceIn(0, indents.size - 1)
        val j = (i + 1).coerceAtMost(indents.size - 1)
        val f = (pos - i).coerceIn(0f, 1f)
        return indents[i] + (indents[j] - indents[i]) * f
    }

    /**
     * The top (or [bottom]) edge, in the list's own pixels, of the row at a fractional action
     * position, blended between the two rows either side. Rows out of view are estimated from the
     * ones in view, so the highlight can travel in from off screen.
     */
    fun edge(pos: Float, info: LazyListLayoutInfo, gapPx: Float, bottom: Boolean): Float? {
        if (items.isEmpty()) return null
        val i = pos.toInt().coerceIn(0, items.size - 1)
        val j = (i + 1).coerceAtMost(items.size - 1)
        val f = (pos - i).coerceIn(0f, 1f)
        val a = span(items[i], info, gapPx) ?: return null
        val b = if (f > 0f && j != i) span(items[j], info, gapPx) ?: a else a
        val ea = if (bottom) a.second else a.first
        val eb = if (bottom) b.second else b.first
        return ea + (eb - ea) * f
    }

    private fun span(item: Int, info: LazyListLayoutInfo, gapPx: Float): Pair<Float, Float>? {
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return null
        visible.firstOrNull { it.index == item }?.let { return it.offset.toFloat() to (it.offset + it.size).toFloat() }
        val first = visible.first()
        val last = visible.last()
        val step = visible.sumOf { it.size }.toFloat() / visible.size + gapPx
        return if (item < first.index) {
            val top = first.offset - (first.index - item) * step
            top to top + step - gapPx
        } else {
            val top = last.offset + last.size + gapPx + (item - last.index - 1) * step
            top to top + step - gapPx
        }
    }

    companion object {
        fun of(actions: List<MenuAction>, headerOffset: Int): MenuLayout {
            val items = IntArray(actions.size)
            val sections = arrayOfNulls<String>(actions.size)
            var next = headerOffset
            var previous: String? = null
            actions.forEachIndexed { i, a ->
                val s = a.section
                if (s != null && (i == 0 || s != previous)) {
                    sections[i] = s
                    next++
                }
                previous = s
                items[i] = next++
            }
            return MenuLayout(actions.map { it.id }, items, sections, IntArray(actions.size) { actions[it].indent })
        }
    }
}

/** Standard controller handling for a [MenuList]. */
fun handleMenuAction(event: NavEvent, actions: List<MenuAction>, selection: LinearSelection): NavResult {
    return when (event.action) {
        NavAction.UP, NavAction.DOWN, NavAction.PAGE_UP, NavAction.PAGE_DOWN ->
            selection.move(event.action, actions.size, vertical = true).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
        NavAction.SELECT -> {
            val a = actions.getOrNull(selection.index) ?: return NavResult.BLOCKED
            if (!a.enabled || a.unavailableReason != null) return NavResult.BLOCKED
            a.onSelect()
            NavResult.ACTIVATED
        }
        else -> NavResult.IGNORED
    }
}

/** The selected row's fill: a raised wash of the text colour, or of the danger colour. */
private fun FuseColors.rowHighlight(destructive: Boolean): Color =
    if (destructive) danger.copy(alpha = if (isDark) 0.14f else 0.1f) else text.copy(alpha = if (isDark) 0.1f else 0.07f)

/** The quieter fill of a row marked as current while focus is elsewhere. */
private fun FuseColors.rowMarked(): Color = text.copy(alpha = if (isDark) 0.05f else 0.04f)

private const val REVEAL_RISE_MS = Durations.BASE
private const val REVEAL_ROWS = FuseMotion.STAGGER_MAX

private val MENU_ART_WIDTH = 104.dp
private val MENU_ART_HEIGHT = 44.dp
private val BAR_WIDTH = 3.dp
private val BAR_HEIGHT = 22.dp
private val ICON_WELL = 32.dp
private val WELL_ICON = 18.dp
private val HEADER_ART = 48.dp
private val TRAILING_ICON = 18.dp
private val CHECK_SIZE = 22.dp
private val PROGRESS_WIDTH = 112.dp
private val LEVEL_METER = 64.dp
private val LEVEL_TEXT = 40.dp

/** Where a row's content (its icon) starts: after the bar and its gap. Headers line up with it. */
private val ROW_CONTENT_START: Dp = BAR_WIDTH + Space.m

/** About a row of room kept beyond the chosen row as a menu scrolls, so the next row shows. */
private val MENU_SCROLL_MARGIN = 40.dp

/** How far the panel-coloured fades reach into a list at its top and bottom. */
private val MENU_EDGE_TOP = 24.dp
private val MENU_EDGE_BOTTOM = 32.dp
