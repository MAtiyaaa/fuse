package io.github.matiyaaa.fuse.ui.shell.romm

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberPageState
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.store.RommGame
import io.github.matiyaaa.fuse.ui.shell.store.RommPresence

/**
 * A RomM system's games (or a collection's, or every game) in the grid Fuse's own systems use: the
 * same tiles and art rules, a quiet mark on what isn't here yet. The title scrolls away with the
 * grid; art loads as tiles come into view and stays loaded as they leave.
 */
@Composable
fun RommGamesScreen(app: AppState, slug: String?, name: String, collection: String?) {
    val ops = app.store.romm
    val flow = remember(slug, collection) { if (collection != null) ops.collection(collection) else ops.games(slug) }
    val games by flow.collectAsState(initial = null)
    val list = games.orEmpty()
    val sel = rememberPageState(app.navigator, "romm.grid.${slug ?: collection ?: "all"}") { GridSelection() }
    sel.clamp(list.size)
    val grid = rememberLazyGridState()
    val focused = app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    val tile = LocalTileMetrics.current.icon
    val here = list.count { it.presence == RommPresence.INSTALLED || it.presence == RommPresence.PARTLY_INSTALLED }
    PageEffect(focused) {
        if (focused) app.hints = listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.BACK, "Back"))
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("romm.grid")) {
        val gap = LocalTileMetrics.current.gap
        val usable = maxWidth - Space.gutter * 2
        val columns = ((usable + gap) / (tile + gap)).toInt().coerceAtLeast(2)
        InputLayer(enabled = focused) { e ->
            when (e.action) {
                NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> sel.move(e.action, list.size, columns)
                NavAction.SELECT -> { list.getOrNull(sel.index)?.let { g -> app.go(Route.GameInfo(g.game ?: g.card.id)) }; NavResult.ACTIVATED }
                NavAction.CONTEXT -> { list.getOrNull(sel.index)?.let { g -> app.openContextMenu(gameMenu(app, g)) }; NavResult.ACTIVATED }
                else -> NavResult.IGNORED
            }
        }
        LaunchedEffect(sel.index, columns) {
            // The chosen row keeps the one above in view; the title folds away under it.
            val row = sel.index / columns
            grid.animateScrollToItem(if (row == 0) 0 else 1 + (row - 1) * columns)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.fillMaxSize().padding(top = Size.hudHeight),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.m, bottom = Size.hintHeight + Space.l),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            item("title", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    RommMark(40.dp)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        FText(name, Fuse.type.title, maxLines = 1)
                        FText(
                            if (games == null) "Reading your RomM library..." else listOfNotNull("${list.size} games on RomM", "$here here".takeIf { here > 0 }).joinToString("  ·  "),
                            Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
                        )
                    }
                }
            }
            if (games == null) {
                item("wait", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) { Spinner(size = 24.dp, color = Fuse.colors.textMuted) } }
            } else if (list.isEmpty()) {
                item("none", span = { GridItemSpan(maxLineSpan) }) { Quiet("Nothing here on the server.") }
            }
            itemsIndexed(list, key = { _, g: RommGame -> g.romId }) { i, g ->
                RommTile(
                    g, focused && sel.index == i, tile,
                    onClick = { sel.index = i; app.go(Route.GameInfo(g.game ?: g.card.id)) },
                    onLongClick = { sel.index = i; app.openContextMenu(gameMenu(app, g)) },
                )
            }
        }
    }
}
