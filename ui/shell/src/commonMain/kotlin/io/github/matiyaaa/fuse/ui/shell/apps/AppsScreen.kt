package io.github.matiyaaa.fuse.ui.shell.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
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
import io.github.matiyaaa.fuse.model.AppFilter
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.appScreenPicker
import io.github.matiyaaa.fuse.ui.shell.app.hasTwoScreens
import io.github.matiyaaa.fuse.ui.shell.app.openApp
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.AppTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.store.AppCard
import kotlinx.coroutines.launch

private val filters = listOf(AppFilter.PINNED to "Pinned", AppFilter.GAMES to "Games", AppFilter.ALL to "All apps")

/**
 * Android apps (or Linux applications) Fuse can open, so you rarely need the system launcher:
 * Pinned, Games and All. Names sit under each icon because app icons alone are ambiguous.
 */
@Composable
fun AppsScreen(app: AppState) {
    val store = app.store
    if (!store.apps.supported) {
        Box(Modifier.fillMaxSize().padding(horizontal = Space.gutter), contentAlignment = Alignment.CenterStart) {
            FText("Apps aren't available on this system.", Fuse.type.display)
        }
        return
    }
    var filterIndex by remember { mutableIntStateOf(1) }
    var inFilters by remember { mutableStateOf(false) }
    val filter = filters[filterIndex].first
    val flow = remember(filter) { store.apps.apps(filter) }
    val apps by flow.collectAsState(initial = emptyList())
    val sel = rememberRouteState(app.navigator, "apps.$filter") { GridSelection() }
    sel.clamp(apps.size)
    var columns by remember { mutableIntStateOf(7) }

    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"))
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (inFilters) {
            return@InputLayer when (e.action) {
                NavAction.LEFT -> if (filterIndex > 0) { filterIndex--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (filterIndex < filters.lastIndex) { filterIndex++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN, NavAction.SELECT -> { inFilters = false; NavResult.MOVED }
                else -> NavResult.IGNORED
            }
        }
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                val r = sel.move(e.action, apps.size, columns)
                if (r == NavResult.IGNORED && e.action == NavAction.UP) { inFilters = true; NavResult.MOVED } else r
            }
            NavAction.SELECT -> { apps.getOrNull(sel.index)?.let { a -> app.openApp(a) }; NavResult.ACTIVATED }
            NavAction.CONTEXT -> { apps.getOrNull(sel.index)?.let { app.openContextMenu(app.appMenu(it)) }; NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = LocalTileMetrics.current.icon * 0.82f
        columns = ((maxWidth - Space.gutter * 2 + Space.xl) / (size + Space.xl)).toInt().coerceAtLeast(3)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + Space.m))
            Row(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                filters.forEachIndexed { i, (_, label) ->
                    val focused = inFilters && app.focusZone == FocusZone.CONTENT && i == filterIndex
                    Chip(
                        label,
                        selected = i == filterIndex,
                        focused = focused,
                        color = Fuse.colors.textMuted,
                        background = Fuse.colors.text.copy(alpha = 0.08f),
                        onClick = {
                            app.focusZone = FocusZone.CONTENT
                            filterIndex = i
                            inFilters = false
                        },
                    )
                }
            }
            Spacer(Modifier.height(Space.xl))
            if (apps.isEmpty()) {
                FText(
                    if (filter == AppFilter.PINNED) "Pin apps from their options to keep them here." else "No apps found.",
                    Fuse.type.body, color = Fuse.colors.textMuted, modifier = Modifier.padding(horizontal = Space.gutter),
                )
            }
            val grid = rememberLazyGridState()
            FollowSelection(grid, { sel.index }, anchor = 0.1f)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Size.hintHeight + Space.x4),
                horizontalArrangement = Arrangement.spacedBy(Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.xl),
            ) {
                itemsIndexed(apps, key = { _, a -> a.entry.id }) { i, a ->
                    val selected = !inFilters && i == sel.index && app.focusZone == FocusZone.CONTENT
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AppTile(
                            a, selected, size = size,
                            onClick = {
                                app.focusZone = FocusZone.CONTENT
                                inFilters = false
                                // A tap opens the app straight away, like any launcher.
                                sel.index = i
                                app.openApp(a)
                            },
                            onLongClick = { sel.index = i; app.openContextMenu(app.appMenu(a)) },
                        )
                        Spacer(Modifier.height(Space.m))
                        FText(
                            a.entry.displayTitle, Fuse.type.label,
                            color = if (selected) Fuse.colors.text else Fuse.colors.textMuted,
                            maxLines = 1,
                            modifier = Modifier.width(size + Space.l),
                            align = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

fun AppState.appMenu(app: AppCard): ContextMenuSpec {
    val ops = store.apps
    return ContextMenuSpec(
        title = app.entry.displayTitle,
        subtitle = app.entry.packageName,
        art = app.icon,
        actions = listOf(
            MenuAction("launch", "Open", FuseIcons.Play, onSelect = { closeOverlays(); openApp(app) }),
        ) + listOfNotNull(
            if (hasTwoScreens) MenuAction("screen", "Screen", FuseIcons.DualScreen, detail = "Top, bottom, or ask when it opens", trailing = Trailing.Chevron, onSelect = { appScreenPicker(app) }) else null,
        ) + listOf(
            MenuAction("pin", if (app.entry.pinned) "Unpin" else "Pin", FuseIcons.Pin, onSelect = {
                closeOverlays(); scope.launch { ops.setPinned(app, !app.entry.pinned) }
            }),
            MenuAction("rename", "Rename", FuseIcons.TextCursor, onSelect = {
                closeOverlays()
                textInput = TextInputSpec("App name", app.entry.displayTitle) { t -> scope.launch { ops.rename(app, t.ifBlank { null }) } }
            }),
            MenuAction("art", "Customise Artwork", FuseIcons.Image, onSelect = {
                closeOverlays()
                go(io.github.matiyaaa.fuse.ui.shell.app.Route.Media(io.github.matiyaaa.fuse.model.MediaOwner.OfApp(app.entry.id), app.entry.displayTitle))
            }),
            MenuAction("hide", if (app.entry.hidden) "Show in Fuse" else "Hide from Fuse", FuseIcons.EyeOff, onSelect = {
                closeOverlays(); scope.launch { ops.setHidden(app, !app.entry.hidden) }
            }),
            MenuAction("info", "App Info", FuseIcons.Info, detail = "Opens the system's page for this app", onSelect = {
                closeOverlays(); scope.launch { ops.openInfo(app) }
            }),
        ),
    )
}
