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
import androidx.compose.foundation.layout.height
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import io.github.matiyaaa.fuse.ui.shell.downloads.sizeOf
import io.github.matiyaaa.fuse.ui.shell.store.RommCollectionCard
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
    io.github.matiyaaa.fuse.ui.shell.app.CompanionShowsCopies(true)
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
    // Every game on RomM: its collections lead, each opening as a page of its own.
    val everything = slug == null && collection == null
    val collections by ops.collections.collectAsState()
    val notHere = list.filter { it.presence == RommPresence.ON_SERVER }
    // A system's or a collection's games that aren't here, all at once (every game on RomM is too many to mean).
    val actions = if (everything || notHere.isEmpty()) emptyList() else listOf(
        io.github.matiyaaa.fuse.ui.shell.downloads.TopAction("Download All", io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.CloudDownload) {
            app.rommDownloadAll(name, notHere.map { it.romId })
        },
    )
    val sections = listOfNotNull(
        io.github.matiyaaa.fuse.ui.shell.reach.GameSection(
            "collections", "Collections", io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.Bookmark, "${collections.size}",
            collections.mapNotNull { col ->
                val lead = col.covers.firstOrNull() ?: return@mapNotNull null
                io.github.matiyaaa.fuse.ui.shell.reach.SectionItem(
                    "collection:${col.id}", lead,
                    tile = { selected, size, onClick, onLong -> RommCollectionTile(col, selected, size, onClick = onClick, onLongClick = onLong) },
                    open = { app.go(Route.RommGames(null, col.name, col.id)) },
                    options = { app.openContextMenu(collectionMenu(app, col)) },
                )
            },
        ).takeIf { everything },
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
        app, sel, sections, loading = games == null, empty = "Nothing here on the server.", tag = "romm.grid", actions = actions,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RommMark(40.dp)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(name, Fuse.type.title, maxLines = 1)
                FText(
                    if (games == null) "Reading your RomM library..." else listOfNotNull(
                        "${list.size} games on RomM", "$onDevice here".takeIf { onDevice > 0 },
                        "${sizeOf(notHere.sumOf { it.sizeBytes })} not here yet".takeIf { actions.isNotEmpty() && notHere.any { it.sizeBytes > 0 } },
                        "${here.size + theirs.size} not on RomM".takeIf { here.size + theirs.size > 0 },
                    ).joinToString("  ·  "),
                    Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
                )
            }
        }
    }
}

/**
 * A RomM collection as Fuse's own collections are drawn: its games' covers on its art, its name and
 * how many it holds beneath.
 */
@Composable
private fun RommCollectionTile(col: RommCollectionCard, selected: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val fraction = Fuse.geometry.tileCornerFraction * 0.6f
    val shape = remember(fraction) { io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape.fraction(fraction) }
    Column(Modifier.width(size)) {
        io.github.matiyaaa.fuse.ui.designsystem.components.Tile(
            selected = selected,
            modifier = Modifier.width(size).height(size),
            shape = shape,
            cornerFraction = fraction,
            glow = col.covers.firstOrNull()?.accent?.toColor() ?: c.accent,
            onClick = onClick,
            onLongClick = onLongClick,
        ) {
            io.github.matiyaaa.fuse.ui.shell.components.CoverCollage(col.covers, size)
        }
        Spacer(Modifier.height(Size.sparkClearance))
        FText(col.name, Fuse.type.bodyStrong, color = if (selected) c.text else c.text.copy(alpha = 0.88f), maxLines = 1)
        Spacer(Modifier.height(Space.xxs))
        FText("${col.games} ${if (col.games == 1) "game" else "games"}", Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/** A RomM collection's options: open it, or download every game of it that isn't here. */
private fun collectionMenu(app: AppState, col: RommCollectionCard): io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec =
    io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec(
        title = col.name,
        subtitle = "${col.games} ${if (col.games == 1) "game" else "games"} on RomM",
        icon = io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.Bookmark,
        actions = listOf(
            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("romm.col.open", "Open", io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.Info, onSelect = {
                app.closeOverlays()
                app.go(Route.RommGames(null, col.name, col.id))
            }),
            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                "romm.col.all", "Download All", io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons.CloudDownload,
                detail = "Every game in it that isn't here, shown before it goes",
                onSelect = {
                    app.closeOverlays()
                    app.scope.launch {
                        val games = app.store.romm.collection(col.id).first().filter { it.presence == RommPresence.ON_SERVER }
                        if (games.isEmpty()) app.toasts.show("Everything in ${col.name} is here or on its way")
                        else app.rommDownloadAll(col.name, games.map { it.romId })
                    }
                },
            ),
        ),
    )
