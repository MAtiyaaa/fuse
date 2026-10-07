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
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.app.rememberPageState
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.store.RommGame
import io.github.matiyaaa.fuse.ui.shell.store.RommPresence

/**
 * A RomM system's games (or a collection's, or every game) in the grid Fuse's own systems use: the
 * same tiles and art rules, a quiet mark on what isn't here yet. A system's page is in sections, as
 * the Library sorts a system: its games on RomM, then the ones on this device RomM hasn't got, then
 * the ones on the household's other devices RomM hasn't got. The title scrolls away with the grid;
 * art loads as tiles come into view and stays loaded as they leave.
 */
@Composable
fun RommGamesScreen(app: AppState, slug: String?, name: String, collection: String?) {
    val ops = app.store.romm
    val flow = remember(slug, collection) { if (collection != null) ops.collection(collection) else ops.games(slug) }
    val games by flow.collectAsState(initial = null)
    val list = games.orEmpty()
    val systems by ops.systems.collectAsState()
    val platform = if (collection == null && slug != null) systems.firstOrNull { it.slug == slug }?.platform else null
    val hereFlow = remember(platform) { platform?.let { ops.notOnServerOn(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
    val here by hereFlow.collectAsState(initial = emptyList())
    val others by app.store.reach.notOnRomm.collectAsState()
    val theirs = if (platform == null) emptyList() else others.filter { it.card.platformId == platform }
    val state by ops.state.collectAsState()
    val sel = rememberPageState(app.navigator, "romm.grid.${slug ?: collection ?: "all"}") { io.github.matiyaaa.fuse.ui.designsystem.focus.SectionedGridSelection() }
    val onDevice = list.count { it.presence == RommPresence.INSTALLED || it.presence == RommPresence.PARTLY_INSTALLED }
    val sections = listOf(
        io.github.matiyaaa.fuse.ui.shell.reach.GameSection(
            "main", "On RomM", io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.LibraryBig, listOfNotNull("${list.size}", "$onDevice here".takeIf { onDevice > 0 }).joinToString("  ·  "),
            list.map { g ->
                io.github.matiyaaa.fuse.ui.shell.reach.SectionItem(
                    g.romId, g.card,
                    tile = { selected, size, onClick, onLong -> RommTile(g, selected, size, onClick = onClick, onLongClick = onLong) },
                    open = { app.go(Route.GameInfo(g.game ?: g.card.id)) },
                    options = { app.openContextMenu(gameMenu(app, g)) },
                )
            },
        ),
        io.github.matiyaaa.fuse.ui.shell.reach.GameSection(
            "here", "Not on RomM", io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.Upload, "${here.size} on this device",
            here.map { c ->
                io.github.matiyaaa.fuse.ui.shell.reach.SectionItem(
                    c.id.value, c,
                    tile = { selected, size, onClick, onLong -> io.github.matiyaaa.fuse.ui.shell.components.GameIconTile(c, selected, size = size, onClick = onClick, onLongClick = onLong) },
                    open = { app.go(Route.GameInfo(c.id)) },
                    options = { app.openContextMenu(app.gameMenu(c)) },
                )
            },
        ),
        io.github.matiyaaa.fuse.ui.shell.reach.GameSection(
            "others", "Not on RomM, from Another Device", io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.MonitorSmartphone, "${theirs.size}",
            theirs.map { g ->
                io.github.matiyaaa.fuse.ui.shell.reach.SectionItem(
                    g.id.value, g.card,
                    tile = { selected, size, onClick, onLong -> io.github.matiyaaa.fuse.ui.shell.reach.HouseholdTile(g, selected, size, onClick = onClick, onLongClick = onLong) },
                    open = { app.go(Route.GameInfo(g.id)) },
                    options = { app.openContextMenu(io.github.matiyaaa.fuse.ui.shell.reach.householdMenu(app, g, romm = state.canUpload)) },
                )
            },
        ),
    )
    io.github.matiyaaa.fuse.ui.shell.reach.SectionedGameGrid(
        app, sel, sections, loading = games == null, empty = "Nothing here on the server.", tag = "romm.grid",
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RommMark(40.dp)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(name, Fuse.type.title, maxLines = 1)
                FText(
                    if (games == null) "Reading your RomM library..." else listOfNotNull(
                        "${list.size} games on RomM", "$onDevice here".takeIf { onDevice > 0 },
                        "${here.size + theirs.size} not on RomM".takeIf { here.size + theirs.size > 0 },
                    ).joinToString("  ·  "),
                    Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
                )
            }
        }
    }
}
