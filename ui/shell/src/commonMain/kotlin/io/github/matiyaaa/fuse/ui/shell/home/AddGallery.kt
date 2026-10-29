package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BoardSize
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.isAchievements
import io.github.matiyaaa.fuse.model.isJellyfin
import io.github.matiyaaa.fuse.model.isSync
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.shell.app.AppState

/**
 * Adding to a board, as a gallery rather than a list of names: everything the board can take, each
 * shown as it will look (its own live face, in its own shape, smaller), grouped under headings,
 * with what it shows in a line under it. It is laid out as the board is, on the same number of
 * columns, so a wide widget is wide here too. The D-pad moves through them as they sit, A adds the
 * chosen one and B closes; a tap or a click adds.
 */
@Composable
internal fun AddGallery(
    app: AppState,
    space: BoardSpace,
    items: List<Addable>,
    columns: Int,
    /** One cell of the board, and the gaps between cells, for drawing each face at its real size. */
    cellW: Dp,
    cellH: Dp,
    gapX: Dp,
    gapY: Dp,
    onAdd: (Addable) -> Unit,
    onClose: () -> Unit,
) {
    val c = Fuse.colors
    // Each heading's items together, the headings in the order they first come.
    val items = remember(items) { items.groupBy { it.section }.values.flatten() }
    var chosen by remember { mutableIntStateOf(0) }
    chosen = chosen.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    // Laid out as the grid lays them out: headings take a line, then items fill lines by their width.
    val sections = items.groupBy { it.section }
    val spans = items.map { it.widget.boardSize.width.coerceIn(1, columns) }
    val placed = remember(items, columns) { placeInLines(items, spans, columns) }
    // The grid's own index of each item, past the headings before it.
    val gridIndex = remember(items) {
        var heads = 0
        var last: String? = "\u0000"
        items.map { a -> if (a.section != null && a.section != last) heads++; last = a.section; heads }.mapIndexed { i, h -> i + h }
    }
    LaunchedEffect(chosen) {
        app.hints = listOf(Hint(HintButton.CONFIRM, "Add"), Hint(HintButton.BACK, "Close"))
    }
    InputLayer(priority = LayerPriority.DIALOG, modal = true) { e ->
        when (e.action) {
            NavAction.LEFT -> if (chosen > 0 && placed[chosen - 1].line == placed[chosen].line) { chosen--; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.RIGHT -> if (chosen < items.lastIndex && placed[chosen + 1].line == placed[chosen].line) { chosen++; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.UP, NavAction.DOWN -> {
                val here = placed[chosen]
                val line = here.line + if (e.action == NavAction.DOWN) 1 else -1
                // The one in that line nearest the chosen one's place, as the eye would go.
                val to = placed.withIndex().filter { it.value.line == line }
                    .minByOrNull { kotlin.math.abs((it.value.column + it.value.span / 2f) - (here.column + here.span / 2f)) }?.index
                if (to != null) { chosen = to; NavResult.MOVED } else NavResult.BLOCKED
            }
            NavAction.SELECT -> { items.getOrNull(chosen)?.let(onAdd); NavResult.ACTIVATED }
            NavAction.BACK, NavAction.CONTEXT -> { onClose(); NavResult.CONSUMED }
            else -> NavResult.CONSUMED
        }
    }
    Box(Modifier.fillMaxSize().background(c.scrim).fuseClickable(scale = false, onClickLabel = "Close", onClick = onClose)) {
        Panel(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = Size.hudHeight, bottom = Size.hintHeight, start = Space.gutterCompact, end = Space.gutterCompact)
                .widthIn(max = MAX_WIDTH)
                .fillMaxSize()
                // Taps on the sheet's own empty space stay on it.
                .fuseClickable(scale = false, onClick = {}),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pad = if (maxWidth < 600.dp) Space.l else Space.xl
                val gap = Space.l
                val galleryCell = (maxWidth - pad * 2 - gap * (columns - 1)) / columns
                // Each face is drawn at its size on the board, then scaled to the gallery's cells.
                val scale = (galleryCell / cellW).coerceAtMost(1f)
                val grid = rememberLazyGridState()
                FollowSelection(grid, { gridIndex.getOrElse(chosen) { 0 } + 1 }, anchor = 0.2f)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    modifier = Modifier.fillMaxSize().fadingEdges(top = if (grid.canScrollBackward) Space.l else 0.dp, bottom = Space.l).dragScroll(grid),
                    contentPadding = PaddingValues(start = pad, end = pad, top = pad, bottom = pad),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(Space.l),
                ) {
                    item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Space.s)) {
                            Box(Modifier.size(Size.touch).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                                FuseIcon(FuseIcons.CirclePlus, size = Size.iconM, tint = c.accent)
                            }
                            Spacer(Modifier.width(Space.m))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                                    FText("Add a ${space.item}", Fuse.type.title, maxLines = 1)
                                    FText(items.size.toString(), Fuse.type.label.tabular(), color = c.textMuted, maxLines = 1)
                                }
                                FText("Shown as each will look. It takes the first free place; then drag it anywhere or resize it", Fuse.type.caption, color = c.textMuted, maxLines = 2)
                            }
                        }
                    }
                    var at = 0
                    for ((section, group) in sections) {
                        if (section != null) {
                            item(key = "s.$section", span = { GridItemSpan(maxLineSpan) }) {
                                SectionLabel(section, Modifier.padding(top = Space.s), count = group.size.toString(), rule = true)
                            }
                        }
                        for (a in group) {
                            val index = at++
                            val span = spans[index]
                            item(key = a.key, span = { GridItemSpan(span) }) {
                                GalleryItem(
                                    space, a, selected = index == chosen,
                                    realW = cellW * span + gapX * (span - 1),
                                    realH = cellH * a.widget.boardSize.height + gapY * (a.widget.boardSize.height - 1),
                                    scale = scale,
                                    onClick = { chosen = index; onAdd(a) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One widget in the gallery: its live face at [scale], what it is called, and what it shows. */
@Composable
private fun GalleryItem(space: BoardSpace, a: Addable, selected: Boolean, realW: Dp, realH: Dp, scale: Float, onClick: () -> Unit) {
    val c = Fuse.colors
    val fraction = Fuse.geometry.tileCornerFraction
    val shape = remember(fraction) { SquircleShape.fraction(fraction) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Tile(
            selected = selected,
            modifier = Modifier.size(realW * scale, realH * scale),
            shape = shape,
            cornerFraction = fraction,
            label = a.title,
            surface = !space.carousel(a.widget),
            onClick = onClick,
        ) {
            // The face as the board draws it, laid out at its own size and shrunk from its corner.
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(realW, realH)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) {
                space.Face(a.widget, a.widget.boardSize, 0)
            }
            // Over the face, so a press anywhere on it adds it rather than turning a carousel.
            Box(Modifier.matchParentSize().fuseClickable(scale = false, onClickLabel = "Add ${a.title}", onClick = onClick))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FuseIcon(a.icon, size = Size.iconS, tint = if (selected) c.accent else c.textMuted)
            FText(a.title, Fuse.type.label, color = if (selected) c.text else c.text.copy(alpha = 0.9f), maxLines = 1)
        }
        a.detail?.let { FText(it, Fuse.type.caption, color = c.textMuted, maxLines = 2) }
    }
}

/** Where an item sits in the gallery: its line, its first column and how many it spans. */
internal data class GallerySpot(val line: Int, val column: Int, val span: Int)

/**
 * The gallery's lines, as a grid lays them out: each heading starts a line of its own, and items
 * fill a line left to right until the next does not fit.
 */
internal fun placeInLines(items: List<Addable>, spans: List<Int>, columns: Int): List<GallerySpot> {
    var line = -1
    var column = columns
    var section: String? = "\u0000"
    return items.mapIndexed { i, a ->
        val span = spans[i]
        if (a.section != section) {
            section = a.section
            line++
            column = 0
        } else if (column + span > columns) {
            line++
            column = 0
        }
        GallerySpot(line, column, span).also { column += span }
    }
}

/** The heading a widget sits under in the gallery. */
internal fun widgetSection(kind: WidgetKind): String = when {
    kind.isJellyfin -> "Films and music"
    kind.isAchievements -> "Achievements"
    kind.isSync -> "This device"
    kind == WidgetKind.PLAYTIME_TOTAL || kind == WidgetKind.PLAYTIME_WEEK || kind == WidgetKind.MOST_PLAYED -> "Play time"
    kind == WidgetKind.PINNED_APPS || kind == WidgetKind.CARTRIDGE_DOWNLOADS || kind == WidgetKind.STORAGE || kind == WidgetKind.CLOCK -> "This device"
    else -> "Your games"
}

/** What a widget shows, in a line under it in the gallery. */
internal fun widgetBlurb(kind: WidgetKind): String = when (kind) {
    WidgetKind.CONTINUE_PLAYING -> "Where you left off, a game at a time"
    WidgetKind.RECENTLY_PLAYED -> "What you've played lately"
    WidgetKind.FAVORITES -> "The games you love"
    WidgetKind.RECENTLY_ADDED -> "What came into your library last"
    WidgetKind.PINNED_GAMES -> "Games you pinned, a press away"
    WidgetKind.PINNED_APPS -> "Apps you pinned, a press away"
    WidgetKind.COLLECTIONS -> "Your collections and series as covers"
    WidgetKind.SYSTEMS -> "Your systems, the last played first"
    WidgetKind.RECENT_ACHIEVEMENT -> "Your latest unlock, large"
    WidgetKind.RECENT_ACHIEVEMENTS -> "Your latest unlocks in a row"
    WidgetKind.ACHIEVEMENT_PROGRESS -> "How close you are to mastering a set"
    WidgetKind.RECENTLY_MASTERED -> "The sets you mastered last"
    WidgetKind.PLAYTIME_TOTAL -> "All your play time in one number"
    WidgetKind.PLAYTIME_WEEK -> "This week's play, day by day"
    WidgetKind.MOST_PLAYED -> "The games you've played most"
    WidgetKind.CURRENT_GAME -> "The game running now"
    WidgetKind.FOR_YOU -> "Picks from your own games, each with why"
    WidgetKind.UP_NEXT -> "What your Journal says you'll play next"
    WidgetKind.SPOTLIGHT -> "One game a day, with why it's worth it"
    WidgetKind.CARTRIDGE_DOWNLOADS -> "What Cartridge is bringing in"
    WidgetKind.STORAGE -> "How full your drives are"
    WidgetKind.CLOCK -> "The time, large and calm"
    WidgetKind.JELLYFIN_CONTINUE -> "Films and episodes you stopped part way"
    WidgetKind.JELLYFIN_NEXT_UP -> "The next episode of what you watch"
    WidgetKind.JELLYFIN_RECENTLY_ADDED -> "What's new on your server"
    WidgetKind.JELLYFIN_FAVORITES -> "Films and shows you love"
    WidgetKind.JELLYFIN_MOVIES -> "The newest films"
    WidgetKind.JELLYFIN_MUSIC -> "The newest albums"
    WidgetKind.SYNC_STATUS -> "How Fuse Sync stands"
    WidgetKind.SYNC_DEVICES -> "Your devices on Fuse Sync"
}

/** The widest the gallery sheet gets, on a television. */
private val MAX_WIDTH = 1400.dp
