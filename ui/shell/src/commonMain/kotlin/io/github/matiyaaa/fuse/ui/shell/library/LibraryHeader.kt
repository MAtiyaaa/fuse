package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.SortOrder
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.components.CountPill
import io.github.matiyaaa.fuse.ui.shell.components.ViewTab
import io.github.matiyaaa.fuse.ui.shell.components.ViewTabs
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.systems.SystemHeader
import kotlin.math.roundToInt

/** One item of the header row, for controller focus: a view, or a button. */
internal sealed interface HeaderItem {
    data class View(val segment: LibrarySegment) : HeaderItem
    data class Button(val button: LibraryButton) : HeaderItem
}

/**
 * The top of a library view. The whole library shows its views as tabs with a gliding underline and
 * a count on each (All, Favourites, Recently played and, while they have games, Missing, Hidden and
 * Removed). A system shows its own header, which folds away by [collapse] as you move down its games,
 * and a collection shows its name with its count.
 *
 * On the right, one toolbar holds the buttons: Collections (or a collection's own Add or remove
 * games) set apart by a hairline from the view's options (system filter, Sort, View), and on a system
 * without an emulator a warning button that leads to its settings. Focus outlines a button and never
 * fills it with colour, so its value stays readable; a filter that is on is tinted with the accent.
 *
 * With [foldTools] a folded system header gives back its whole height: the toolbar stays where it
 * is and hangs over the row below, which keeps room for it.
 */
@Composable
internal fun LibraryHeader(
    app: AppState,
    scope: LibraryScope,
    platform: PlatformCard?,
    compact: Boolean,
    collapse: Float,
    system: PlatformCard?,
    count: Int?,
    header: List<HeaderItem>,
    sort: SortOrder,
    layout: LibraryLayout,
    state: LibraryViewState,
    onView: (Int, LibrarySegment) -> Unit,
    onButton: (Int, LibraryButton) -> Unit,
    foldTools: Boolean = false,
    /** Games in each view, shown on its tab (the active view shows [count]). */
    counts: Map<LibrarySegment, Int> = emptyMap(),
) {
    fun focused(i: Int) = state.inHeader && app.focusZone == FocusZone.CONTENT && i == state.headerIndex
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Every button names its value when there is room; on narrow screens only the focused one does.
        val roomy = maxWidth >= ROOMY_WIDTH
        // On a portrait phone the tabs keep the whole width and the toolbar takes a line of its own.
        val stacked = maxWidth < STACK_WIDTH
        val title: @Composable () -> Unit = {
            when {
                scope == LibraryScope.All -> LibraryTabs(header, state.segment, count, counts, ::focused, onView)
                // The same header as the Systems screen, in the same place, so the logo stays put.
                // Inside a system its name says it all: smaller, without the count and emulator.
                platform != null -> SystemHeader(
                    platform, compact, Modifier.padding(start = Space.gutter), widthFraction = 0.9f, collapse = collapse,
                    showMeta = false,
                    logoHeight = if (compact) Space.xxl else Space.x3 - Space.xs,
                    nameStyle = if (compact) Fuse.type.titleSmall else Fuse.type.title,
                )
                else -> CollectionTitle(scope, count)
            }
        }
        val tools = header.withIndex().filter { it.value is HeaderItem.Button }
        val toolbar: @Composable () -> Unit = {
            Toolbar {
                var previous: LibraryButton? = null
                for ((i, item) in tools) {
                    val button = (item as HeaderItem.Button).button
                    // Where to go sits apart from how this view is shown.
                    if (previous != null && previous.goes != button.goes) ToolbarRule()
                    previous = button
                    val (label, icon) = when (button) {
                        LibraryButton.COLLECTIONS -> "Collections" to FuseIcons.LibraryBig
                        LibraryButton.ADD_GAMES -> "Add or remove games" to FuseIcons.ListPlus
                        LibraryButton.EMULATOR -> "No emulator" to FuseIcons.Warning
                        LibraryButton.SYSTEM -> (system?.platform?.shortName ?: "All systems") to FuseIcons.Filter
                        LibraryButton.SORT -> sortLabel(sort) to FuseIcons.Sort
                        LibraryButton.VIEW -> layoutLabel(layout) to layoutIcon(layout)
                    }
                    val tone = when {
                        button == LibraryButton.EMULATOR -> ToolTone.WARNING
                        button == LibraryButton.SYSTEM && system != null -> ToolTone.ON
                        else -> ToolTone.PLAIN
                    }
                    ToolButton(
                        label, icon, focused = focused(i), tone = tone,
                        showLabel = roomy || focused(i) || tone != ToolTone.PLAIN,
                    ) { onButton(i, button) }
                }
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(top = if (platform != null) 0.dp else Space.xxs, bottom = Space.xxs)) {
                Box(Modifier.fillMaxWidth().padding(end = Space.gutter)) { title() }
                if (tools.isNotEmpty()) {
                    Spacer(Modifier.height(Space.s))
                    Box(Modifier.padding(start = Space.gutter - Space.xs)) { toolbar() }
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (foldTools && platform != null) Modifier.foldKeepingTop(collapse) else Modifier)
                    .padding(end = Space.gutter, top = if (platform != null) 0.dp else Space.xxs, bottom = Space.xxs),
                verticalAlignment = if (platform != null) Alignment.Bottom else Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { title() }
                if (tools.isNotEmpty()) {
                    Spacer(Modifier.width(Space.m))
                    toolbar()
                }
            }
        }
    }
}

/** Whether a button leads somewhere else (Collections, a collection's games, a system's settings). */
private val LibraryButton.goes: Boolean
    get() = this == LibraryButton.COLLECTIONS || this == LibraryButton.ADD_GAMES || this == LibraryButton.EMULATOR

/** A collection's name and how many games it holds. */
@Composable
private fun CollectionTitle(scope: LibraryScope, count: Int?) {
    val c = Fuse.colors
    Row(Modifier.padding(start = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
        val title = when (scope) {
            is LibraryScope.OfPlatform -> scope.platform.value
            is LibraryScope.OfCollection -> scope.name
            LibraryScope.All -> "Library"
        }
        if (scope is LibraryScope.OfCollection) {
            FuseIcon(FuseIcons.Bookmark, size = Size.iconM, tint = c.textMuted)
            Spacer(Modifier.width(Space.s + Space.xxs))
        }
        FText(title, Fuse.type.title, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        if (count != null) {
            Spacer(Modifier.width(Space.m))
            CountPill(count.toString(), emphasised = false)
        }
    }
}

/** Reports [fraction] less of the height while drawing everything in place, unclipped. */
private fun Modifier.foldKeepingTop(fraction: Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, (p.height * (1f - fraction.coerceIn(0f, 1f))).roundToInt()) { p.place(0, 0) }
}

/** The views of the whole library, as tabs. Each carries its count; the active one the live [count]. */
@Composable
private fun LibraryTabs(
    header: List<HeaderItem>,
    active: LibrarySegment,
    count: Int?,
    counts: Map<LibrarySegment, Int>,
    focused: (Int) -> Boolean,
    onView: (Int, LibrarySegment) -> Unit,
) {
    // The views always come first in the header, so a tab's index is its header index.
    val views = header.filterIsInstance<HeaderItem.View>().map { it.segment }
    ViewTabs(
        items = views.map { s ->
            ViewTab(
                label = s.label,
                icon = if (s == LibrarySegment.MISSING) FuseIcons.FileQuestion else null,
                warn = s == LibrarySegment.MISSING,
                badge = (if (s == active) count else counts[s])?.toString(),
            )
        },
        active = views.indexOf(active),
        focused = views.indices.firstOrNull { focused(it) },
        onSelect = { i -> onView(i, views[i]) },
    )
}

/**
 * The toolbar's group: one quiet pill with a hairline edge, so its buttons read as a set of controls
 * rather than loose words on the art.
 */
@Composable
private fun Toolbar(content: @Composable () -> Unit) {
    val c = Fuse.colors
    Row(
        Modifier
            .clip(PillShape)
            .background(c.text.copy(alpha = if (c.isDark) GROUP_FILL else GROUP_FILL_LIGHT))
            .border(Size.stroke, c.hairline, PillShape)
            .padding(Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** A short hairline between the toolbar's groups. */
@Composable
private fun ToolbarRule() {
    val c = Fuse.colors
    Box(
        Modifier
            .padding(horizontal = Space.xs)
            .width(Size.divider)
            .height(Size.iconM)
            .background(c.hairlineStrong),
    )
}

/** How a toolbar button is tinted: plain, on (a filter in use) or a warning. */
private enum class ToolTone { PLAIN, ON, WARNING }

/**
 * A button in the header toolbar: an icon and its current value. Hover and press come from
 * [fuseClickable]; controller focus fills it softly and outlines it in the focus colour.
 */
@Composable
private fun ToolButton(label: String, icon: ImageVector, focused: Boolean, tone: ToolTone, showLabel: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val toneColor = when (tone) {
        ToolTone.PLAIN -> c.text
        ToolTone.ON -> c.accent
        ToolTone.WARNING -> c.warning
    }
    val bg by animateColorAsState(
        when {
            focused -> c.text.copy(alpha = FOCUS_FILL)
            tone != ToolTone.PLAIN -> toneColor.copy(alpha = TONE_FILL)
            else -> Color.Transparent
        },
        motion.tween(Durations.FAST),
        label = "tool bg",
    )
    val tint by animateColorAsState(
        when {
            tone != ToolTone.PLAIN -> toneColor
            focused -> c.text
            else -> c.textMuted
        },
        motion.tween(Durations.FAST),
        label = "tool tint",
    )
    val ring by animateFloatAsState(if (focused) 1f else 0f, motion.focusSpring(), label = "tool ring")
    val focus = c.focus
    Row(
        Modifier
            .height(Size.chip)
            .drawBehind {
                drawRoundRect(bg, cornerRadius = CornerRadius(size.height / 2))
                if (ring > 0.01f) {
                    val w = Size.focusStroke.toPx()
                    drawRoundRect(
                        focus,
                        topLeft = Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius((size.height - w) / 2),
                        alpha = ring.coerceIn(0f, 1f),
                        style = Stroke(w),
                    )
                }
            }
            .fuseClickable(shape = PillShape, role = Role.Button, onClick = onClick)
            .semantics { this.selected = focused }
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = Size.iconS, tint = tint)
        AnimatedVisibility(
            visible = showLabel,
            enter = expandHorizontally(motion.enter(Durations.BASE)) + fadeIn(motion.fade(Durations.BASE)),
            exit = shrinkHorizontally(motion.exit(Durations.FAST)) + fadeOut(motion.fade(Durations.INSTANT)),
        ) {
            Row {
                Spacer(Modifier.width(Space.s))
                FText(label, Fuse.type.label, color = tint, maxLines = 1)
            }
        }
    }
}

/** Below this width only the focused (or tinted) toolbar button names its value. */
private val ROOMY_WIDTH = 1000.dp

/** Below this width the toolbar moves under the tabs (and a folded system page needn't keep room for it). */
internal val STACK_WIDTH = 600.dp

/** The toolbar group's fill in dark and light themes. */
private const val GROUP_FILL = 0.06f
private const val GROUP_FILL_LIGHT = 0.05f

/** Fill behind a focused button, and behind a tinted one. */
private const val FOCUS_FILL = 0.12f
private const val TONE_FILL = 0.14f
