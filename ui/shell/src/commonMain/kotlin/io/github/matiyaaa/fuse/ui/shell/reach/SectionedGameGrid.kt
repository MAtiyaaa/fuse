package io.github.matiyaaa.fuse.ui.shell.reach

import io.github.matiyaaa.fuse.ui.designsystem.focus.glideTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.focus.SectionedGridSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll

/** One game in a [SectionedGameGrid]: its card (for the room behind the page), its tile, and what opening it does. */
class SectionItem(
    val key: Any,
    val card: GameCard,
    val tile: @Composable (selected: Boolean, size: Dp, onClick: () -> Unit, onLongClick: () -> Unit) -> Unit,
    val open: () -> Unit,
    val options: () -> Unit,
)

/** A titled run of games in a [SectionedGameGrid]. */
class GameSection(val key: String, val title: String, val icon: ImageVector, val trailing: String?, val items: List<SectionItem>)

/**
 * A system's games in the grid Fuse's own systems use, in sections that each start on a row of
 * their own under a heading (RomM's games, the ones here RomM hasn't got, other devices'). The
 * title scrolls away with the grid; up and down move between sections in the same column; the
 * chosen game's room is behind the page and on the second screen, as in the Library.
 */
@Composable
fun SectionedGameGrid(
    app: AppState,
    sel: SectionedGridSelection,
    sections: List<GameSection>,
    loading: Boolean,
    empty: String,
    tag: String,
    /** The room while no game is chosen (a system's page shows the system's). */
    room: io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource? = null,
    /** What the page itself offers (Download All), beside its title: up from the first row reaches them. */
    actions: List<io.github.matiyaaa.fuse.ui.shell.downloads.TopAction> = emptyList(),
    title: @Composable () -> Unit,
) {
    val shown = sections.filter { it.items.isNotEmpty() }
    val sizes = shown.map { it.items.size }
    sel.clamp(sizes)
    val grid = rememberLazyGridState()
    val focused = app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    val tile = LocalTileMetrics.current.icon
    val chosen = shown.getOrNull(sel.section)?.items?.getOrNull(sel.index)
    // On the page's own actions rather than a game (always, with no games to be on): [action] is the one chosen.
    var onActions by remember { mutableStateOf(false) }
    var picked by remember { mutableIntStateOf(0) }
    val action = picked.coerceIn(0, (actions.size - 1).coerceAtLeast(0))
    val atActions = actions.isNotEmpty() && (onActions || shown.isEmpty())
    PageEffect(focused, atActions, actions.getOrNull(action)?.label) {
        if (focused) app.hints = if (atActions) {
            listOfNotNull(Hint(HintButton.CONFIRM, actions.getOrNull(action)?.label ?: "Choose"), Hint(HintButton.DPAD, "Choose").takeIf { actions.size > 1 }, Hint(HintButton.BACK, "Back"))
        } else {
            listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.BACK, "Back"))
        }
    }
    val systems by app.store.library.platforms.collectAsState()
    PageEffect(focused, chosen?.card?.id, chosen?.card?.art, room, atActions) {
        val c = chosen?.card?.takeIf { !atActions }
        if (focused) app.hero = c?.room(systems.firstOrNull { it.platform.id == c.shownPlatform }) ?: room
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(tag)) {
        val gap = LocalTileMetrics.current.gap
        val usable = maxWidth - Space.gutter * 2
        val columns = ((usable + gap) / (tile + gap)).toInt().coerceAtLeast(2)
        InputLayer(enabled = focused) { e ->
            if (atActions) return@InputLayer when (e.action) {
                NavAction.LEFT -> if (action > 0) { picked = action - 1; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (action < actions.lastIndex) { picked = action + 1; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN -> if (shown.isNotEmpty()) { onActions = false; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.UP -> NavResult.BLOCKED
                NavAction.SELECT -> { actions.getOrNull(action)?.run?.invoke(); NavResult.ACTIVATED }
                else -> NavResult.IGNORED
            }
            when (e.action) {
                NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                    val moved = sel.move(e.action, sizes, columns)
                    // Up from the first row: the page's own actions.
                    if (moved == NavResult.IGNORED && e.action == NavAction.UP && actions.isNotEmpty()) { onActions = true; NavResult.MOVED } else moved
                }
                NavAction.SELECT -> { chosen?.open?.invoke(); NavResult.ACTIVATED }
                NavAction.CONTEXT -> { chosen?.options?.invoke(); NavResult.ACTIVATED }
                else -> NavResult.IGNORED
            }
        }
        // Where the chosen game sits among the grid's items: the title, then each section's heading and games.
        val position = run {
            var at = 1
            for ((s, sec) in shown.withIndex()) {
                at += 1
                if (s == sel.section) return@run at + sel.index
                at += sec.items.size
            }
            0
        }
        LaunchedEffect(sel.section, sel.index, columns, atActions) {
            if (atActions) {
                grid.glideTo(0)
                return@LaunchedEffect
            }
            val first = shown.getOrNull(sel.section)?.let { sel.index < columns } ?: true
            // The chosen row keeps the line above in view: its heading, on the first row of a section.
            val target = if (sel.section == 0 && first) 0 else if (first) position - sel.index - 1 else position - (sel.index % columns) - columns
            grid.glideTo(target.coerceAtLeast(0))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.fillMaxSize().padding(top = Size.hudHeight).dragScroll(grid),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.m, bottom = Size.hintHeight + Space.l),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            item("title", span = { GridItemSpan(maxLineSpan) }) {
                val pills: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        actions.forEachIndexed { i, a ->
                            val chosenHere = focused && atActions && i == action
                            io.github.matiyaaa.fuse.ui.shell.downloads.ActionPill(a, chosenHere, label = this@BoxWithConstraints.maxWidth >= 720.dp || chosenHere || actions.size == 1) {
                                picked = i
                                a.run()
                            }
                        }
                    }
                }
                // Beside the title where there is room; under it on a phone held upright, so the name stays whole.
                if (actions.isNotEmpty() && this@BoxWithConstraints.maxWidth < NARROW) {
                    Column(Modifier.fillMaxWidth().padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        title()
                        pills()
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { title() }
                        if (actions.isNotEmpty()) {
                            Spacer(Modifier.width(Space.s))
                            pills()
                        }
                    }
                }
            }
            if (loading) {
                item("wait", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) { Spinner(size = 24.dp, color = Fuse.colors.textMuted) } }
            } else if (shown.isEmpty()) {
                item("none", span = { GridItemSpan(maxLineSpan) }) { FText(empty, Fuse.type.body, color = Fuse.colors.textMuted, modifier = Modifier.padding(vertical = Space.l)) }
            }
            shown.forEachIndexed { s, sec ->
                item("head:${sec.key}", span = { GridItemSpan(maxLineSpan) }) {
                    // Only a page with more than one section needs to say which is which.
                    if (shown.size > 1 || sec.key != "main") SectionHeading(sec, Modifier.padding(top = if (s == 0) 0.dp else Space.l))
                }
                itemsIndexed(sec.items, key = { _, it -> "${sec.key}:${it.key}" }) { i, item ->
                    item.tile(focused && !atActions && sel.section == s && sel.index == i, tile, { onActions = false; sel.select(s, i); item.open() }, { onActions = false; sel.select(s, i); item.options() })
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(sec: GameSection, modifier: Modifier) {
    val c = Fuse.colors
    Row(modifier.fillMaxWidth().padding(bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        FuseIcon(sec.icon, size = Size.iconS, tint = c.textMuted)
        Spacer(Modifier.width(Space.s))
        FText(sec.title, Fuse.type.bodyStrong, maxLines = 1)
        sec.trailing?.let {
            Spacer(Modifier.width(Space.s))
            FText(it, Fuse.type.caption, color = c.textFaint, maxLines = 1)
        }
    }
}

/** Narrower than this, the page's actions go under its title rather than beside it. */
private val NARROW = 600.dp
