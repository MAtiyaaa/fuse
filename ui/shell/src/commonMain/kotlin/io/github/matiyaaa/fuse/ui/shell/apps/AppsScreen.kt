package io.github.matiyaaa.fuse.ui.shell.apps

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.AppFilter
import io.github.matiyaaa.fuse.model.AppKind
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.IconBadge
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Skeleton
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonText
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.appKindPicker
import io.github.matiyaaa.fuse.ui.shell.app.appScreenPicker
import io.github.matiyaaa.fuse.ui.shell.app.hasTwoScreens
import io.github.matiyaaa.fuse.ui.shell.app.openApp
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.ViewTab
import io.github.matiyaaa.fuse.ui.shell.components.ViewTabs
import io.github.matiyaaa.fuse.ui.shell.store.AppCard
import kotlinx.coroutines.launch

private val allFilters = listOf(
    AppFilter.PINNED to "Pinned", AppFilter.EMULATORS to "Emulators", AppFilter.STREAMING to "Streaming",
    AppFilter.TOOLS to "Tools", AppFilter.ALL to "All apps",
)

/** Streaming and Tools only show when some app is one; the other lists always do. */
private fun filtersFor(kinds: Set<AppKind>) = allFilters.filter { (f, _) ->
    when (f) {
        AppFilter.STREAMING -> AppKind.STREAMING in kinds
        AppFilter.TOOLS -> AppKind.TOOL in kinds
        else -> true
    }
}

/**
 * Where the Apps tab was: which list, whether focus was on the lists, and the selected app in each.
 * Kept by the navigator, so leaving the tab and coming back returns exactly there.
 */
@Stable
class AppsViewState(initial: AppFilter) {
    var filter by mutableStateOf(initial)
    var inFilters by mutableStateOf(false)
    private val selections = mutableMapOf<AppFilter, GridSelection>()
    fun selection(f: AppFilter): GridSelection = selections.getOrPut(f) { GridSelection() }
}

/**
 * Android apps Fuse can open, so you rarely need the system launcher: Pinned, Emulators and All
 * apps (the one it opens on is a setting). Apps played as games are in the Android system instead.
 * Names sit under each icon because app icons alone are ambiguous.
 */
@Composable
fun AppsScreen(app: AppState) {
    val store = app.store
    if (!store.apps.supported) {
        LaunchedEffect(Unit) { app.hints = emptyList() }
        Box(Modifier.fillMaxSize().padding(horizontal = Space.gutter).padding(top = Size.hudHeight, bottom = Size.hintHeight), contentAlignment = Alignment.Center) {
            EmptyState(FuseIcons.AppWindow, "Apps aren't available on this system")
        }
        return
    }
    val everything by remember { store.apps.apps(AppFilter.ALL) }.collectAsState(initial = emptyList())
    val filters = filtersFor(everything.map { it.entry.kind }.toSet())
    val view = rememberRouteState(app.navigator, "apps") { AppsViewState(store.prefs.value.appsFilter.takeIf { f -> allFilters.any { it.first == f } } ?: AppFilter.ALL) }
    val filterIndex = filters.indexOfFirst { it.first == view.filter }.coerceAtLeast(0)
    val filter = filters[filterIndex].first
    val inFilters = view.inFilters
    val flow = remember(filter) { store.apps.apps(filter) }
    // Null until the list has loaded, so a remembered place isn't clamped away by the empty start.
    val loaded by flow.collectAsState(initial = null)
    val apps = loaded.orEmpty()
    val sel = view.selection(filter)
    if (loaded != null) sel.clamp(apps.size)
    var columns by remember { mutableIntStateOf(7) }

    LaunchedEffect(Unit) { app.hero = null }
    // Open and Options only while there is an app to open.
    val any = apps.isNotEmpty()
    LaunchedEffect(any) {
        app.hints = if (any) listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options")) else emptyList()
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (inFilters) {
            return@InputLayer when (e.action) {
                NavAction.LEFT -> if (filterIndex > 0) { view.filter = filters[filterIndex - 1].first; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (filterIndex < filters.lastIndex) { view.filter = filters[filterIndex + 1].first; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN, NavAction.SELECT -> { view.inFilters = false; NavResult.MOVED }
                else -> NavResult.IGNORED
            }
        }
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                val r = sel.move(e.action, apps.size, columns)
                if (r == NavResult.IGNORED && e.action == NavAction.UP) { view.inFilters = true; NavResult.MOVED } else r
            }
            NavAction.SELECT -> { apps.getOrNull(sel.index)?.let { a -> app.openApp(a) }; NavResult.ACTIVATED }
            NavAction.CONTEXT -> { apps.getOrNull(sel.index)?.let { app.openContextMenu(app.appMenu(it)) }; NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = LocalTileMetrics.current.icon * 0.82f
        columns = ((maxWidth - Space.gutter * 2 + Space.xl) / (size + Space.xl)).toInt().coerceAtLeast(3)
        // Each list is its own entry: its first rows rise in as it opens.
        val reveal = rememberReveal(filter)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + Space.m))
            ViewTabs(
                items = filters.map { (f, label) -> ViewTab(label, badge = if (f == filter && loaded != null) apps.size.toString() else null) },
                active = filterIndex,
                focused = filterIndex.takeIf { inFilters && app.focusZone == FocusZone.CONTENT },
                onSelect = { i ->
                    app.focusZone = FocusZone.CONTENT
                    view.filter = filters[i].first
                    view.inFilters = false
                },
                modifier = Modifier.reveal(reveal, 0),
            )
            Spacer(Modifier.height(Space.xs))
            when {
                loaded == null -> AppsLoading(columns, size)
                apps.isEmpty() -> Box(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.gutter).padding(bottom = Size.hintHeight + Space.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    when (filter) {
                        AppFilter.PINNED -> EmptyState(FuseIcons.Pin, "Nothing pinned yet", message = "Pin apps from their options to keep them here.")
                        AppFilter.EMULATORS -> EmptyState(FuseIcons.Chip, "No emulators found", message = "Set an app's Type to Emulator in its options.")
                        AppFilter.STREAMING -> EmptyState(FuseIcons.Cast, "No streaming apps", message = "Moonlight and Artemis are in the Store.")
                        AppFilter.TOOLS -> EmptyState(FuseIcons.Wrench, "No tools yet")
                        else -> EmptyState(FuseIcons.AppWindow, "No apps found")
                    }
                }
                else -> {
                    // A fresh grid per list; following the selection brings back where you were in it.
                    val grid = remember(filter) { LazyGridState() }
                    FollowSelection(grid, { sel.index }, anchor = 0.1f)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = grid,
                        modifier = Modifier.fadingEdges(grid, top = Space.l, bottom = 0.dp),
                        // Room above the first row for a lifted tile, and below the last for the hints.
                        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Size.hintHeight + Space.x4),
                        horizontalArrangement = Arrangement.spacedBy(Space.xl),
                        verticalArrangement = Arrangement.spacedBy(Space.xl),
                    ) {
                        itemsIndexed(apps, key = { _, a -> a.entry.id }) { i, a ->
                            val selected = !inFilters && i == sel.index && app.focusZone == FocusZone.CONTENT
                            AppDrawerItem(
                                a, selected, size,
                                // Pins are marked where they aren't the whole list.
                                pinMark = filter != AppFilter.PINNED && a.entry.pinned,
                                // The first rows arrive as a soft diagonal wave from the top left.
                                modifier = Modifier.reveal(reveal, 1 + i / columns + i % columns),
                                onClick = {
                                    app.focusZone = FocusZone.CONTENT
                                    view.inFilters = false
                                    // A tap opens the app straight away, like any launcher.
                                    sel.index = i
                                    app.openApp(a)
                                },
                                onLongClick = { sel.index = i; app.openContextMenu(app.appMenu(a)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One app in the drawer: its icon centred on the tile's plate (an app without an icon gets a
 * generated one in an icon's own rounded shape, so every plate holds the same kind of mark), and its
 * name under it, clear of the focused tile's spark bar.
 */
@Composable
private fun AppDrawerItem(
    card: AppCard,
    selected: Boolean,
    size: Dp,
    pinMark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = Fuse.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Tile(selected = selected, glow = c.accent, modifier = Modifier.size(size), onClick = onClick, onLongClick = onLongClick) {
            Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                val mark = Modifier.fillMaxSize(APP_ICON_SHARE)
                Artwork(
                    model = card.icon,
                    modifier = mark,
                    contentScale = ContentScale.Fit,
                    fallback = {
                        GeneratedArt(card.entry.displayTitle, c.accent, Modifier.fillMaxSize().clip(SquircleShape.fraction(APP_ICON_CORNER)), slot = ArtSlot.ICON)
                    },
                )
                if (pinMark) IconBadge(FuseIcons.Pin, Modifier.align(Alignment.TopEnd).padding(Space.s), tint = c.onArt, background = c.artScrim, size = Size.badge)
            }
        }
        Spacer(Modifier.height(Size.sparkClearance))
        // The name may use the gap beside the tile, and a second line on small tiles, so it is
        // rarely cut short.
        FText(
            card.entry.displayTitle, Fuse.type.label,
            color = if (selected) c.text else c.textMuted,
            maxLines = 2,
            modifier = Modifier.width(size + Space.l + Space.xs),
            align = TextAlign.Center,
        )
    }
}

/** The drawer's shape while its list loads: plates and name bars where the first rows will be. */
@Composable
private fun AppsLoading(columns: Int, size: Dp) {
    val plate = SquircleShape.fraction(Fuse.geometry.tileCornerFraction)
    Column(
        Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                repeat(columns) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Skeleton(Modifier.size(size), shape = plate)
                        Spacer(Modifier.height(Size.sparkClearance))
                        SkeletonText(Modifier.width(size * 0.7f), lines = 1, style = Fuse.type.label)
                    }
                }
            }
        }
    }
}

/** How much of the plate an app's icon covers. */
private const val APP_ICON_SHARE = 0.62f

/** The rounded square a generated app icon takes, close to the shape launchers give app icons. */
private const val APP_ICON_CORNER = 0.24f

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
            MenuAction(
                "type", "Type", when (app.entry.kind) {
                    AppKind.GAME -> FuseIcons.Gamepad
                    AppKind.APP -> FuseIcons.AppWindow
                    AppKind.EMULATOR -> FuseIcons.Chip
                    AppKind.STREAMING -> FuseIcons.Cast
                    AppKind.TOOL -> FuseIcons.Wrench
                },
                detail = if (store.apps.gamesInLibrary) "Game, app, emulator, streaming or tool. Games join the Android system" else "Game, app, emulator, streaming or tool",
                trailing = Trailing.Value(
                    when (app.entry.kind) {
                        AppKind.GAME -> "Game"
                        AppKind.APP -> "App"
                        AppKind.EMULATOR -> "Emulator"
                        AppKind.STREAMING -> "Streaming"
                        AppKind.TOOL -> "Tool"
                    },
                ),
                onSelect = { appKindPicker(app) },
            ),
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
