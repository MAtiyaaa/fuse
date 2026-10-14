package io.github.matiyaaa.fuse.ui.shell.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.openStore
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.app.showEmulator
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.ViewTab
import io.github.matiyaaa.fuse.ui.shell.components.ViewTabs
import io.github.matiyaaa.fuse.ui.shell.store.StoreApp
import kotlinx.coroutines.launch

/** The Emulators section's two lists. */
enum class EmulatorList(val label: String) { INSTALLED("Installed"), NOT_INSTALLED("Not Installed") }

/** One emulator as the Emulators section shows it: what it is, whether it is here, and the Store's app for it. */
data class EmulatorTile(
    val id: EmulatorId,
    val name: String,
    val installed: Boolean,
    /** Its version, when it is here and says. */
    val version: String?,
    /** The Store's app for it on this computer, when the Store offers it. */
    val storeKey: String?,
    /** Its icon: the installed program's own, or the one its project publishes. */
    val icon: Any?,
)

@Stable
class EmulatorsViewState {
    var list by mutableStateOf(EmulatorList.INSTALLED)
    var inTabs by mutableStateOf(false)
    private val selections = mutableMapOf<EmulatorList, GridSelection>()
    fun selection(l: EmulatorList): GridSelection = selections.getOrPut(l) { GridSelection() }
}

/**
 * The Apps section on a computer: every emulator Fuse can start here and nothing else. Installed
 * ones open with Confirm; those not installed lead to the Store's page to install them (or their
 * website where the Store doesn't offer them). Options shows an emulator's own page: the systems it
 * runs, how Fuse starts it, and what Fuse has checked.
 */
@Composable
fun EmulatorsScreen(app: AppState) {
    val store = app.store
    val installed by store.emulators.installed.collectAsState()
    val storeState by store.appStore.state.collectAsState()
    // The catalogue, for icons and the way to install what isn't here.
    LaunchedEffect(Unit) { if (store.appStore.supported && storeState.catalogue == null) store.appStore.open() }
    val catalogue = storeState.catalogue?.apps.orEmpty()
    val all = remember(installed, catalogue, storeState.installed, storeState.icons) {
        val versions = installed.associateBy { it.id }
        store.emulators.known().map { o ->
            val key = if (store.appStore.supported) storeAppFor(o.name, catalogue)?.key else null
            EmulatorTile(
                id = o.id,
                name = o.name,
                installed = o.installed,
                version = versions[o.id]?.version,
                storeKey = key,
                icon = key?.let { k -> (if (k in storeState.installed) store.appStore.iconModel(k) else null) ?: storeState.icons[k] },
            )
        }
    }
    val view = rememberRouteState(app.navigator, "emulators") { EmulatorsViewState() }
    val lists = EmulatorList.entries
    val listIndex = lists.indexOf(view.list)
    val shown = all.filter { it.installed == (view.list == EmulatorList.INSTALLED) }
    val sel = view.selection(view.list)
    sel.clamp(shown.size)
    var columns by remember { mutableIntStateOf(7) }

    PageEffect(Unit) { app.hero = null }
    val current = shown.getOrNull(sel.index)
    PageEffect(current?.installed, current?.storeKey) {
        app.hints = when {
            current == null -> emptyList()
            current.installed -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Details"))
            else -> listOf(Hint(HintButton.CONFIRM, if (current.storeKey != null) "Install" else "Get It"), Hint(HintButton.OPTIONS, "Details"))
        }
    }

    fun activate(e: EmulatorTile) {
        when {
            e.installed -> app.scope.launch { store.emulators.openEmulator(e.id) }
            e.storeKey != null -> app.openStore(e.storeKey)
            else -> store.emulators.homepage(e.id)?.let { app.platform.openUrl(it) } ?: app.showEmulator(e.id)
        }
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (view.inTabs) {
            return@InputLayer when (e.action) {
                NavAction.LEFT -> if (listIndex > 0) { view.list = lists[listIndex - 1]; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (listIndex < lists.lastIndex) { view.list = lists[listIndex + 1]; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN, NavAction.SELECT -> { view.inTabs = false; NavResult.MOVED }
                else -> NavResult.IGNORED
            }
        }
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                val r = sel.move(e.action, shown.size, columns)
                if (r == NavResult.IGNORED && e.action == NavAction.UP) { view.inTabs = true; NavResult.MOVED } else r
            }
            NavAction.SELECT -> { shown.getOrNull(sel.index)?.let(::activate); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { shown.getOrNull(sel.index)?.let { app.showEmulator(it.id) }; NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = LocalTileMetrics.current.icon * 0.82f
        columns = ((maxWidth - Space.gutter * 2 + Space.xl) / (size + Space.xl)).toInt().coerceAtLeast(3)
        val reveal = rememberReveal(view.list)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + Space.m))
            ViewTabs(
                items = lists.map { l -> ViewTab(l.label, badge = all.count { it.installed == (l == EmulatorList.INSTALLED) }.toString()) },
                active = listIndex,
                focused = listIndex.takeIf { view.inTabs && app.focusZone == FocusZone.CONTENT },
                onSelect = { i ->
                    app.focusZone = FocusZone.CONTENT
                    view.list = lists[i]
                    view.inTabs = false
                },
                modifier = Modifier.reveal(reveal, 0),
            )
            Spacer(Modifier.height(Space.xs))
            if (shown.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.gutter).padding(bottom = Size.hintHeight + Space.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    when (view.list) {
                        EmulatorList.INSTALLED -> EmptyState(FuseIcons.Joystick, "No emulators found", message = "Install one from Not Installed, or show Fuse where one is in Settings, Systems.")
                        EmulatorList.NOT_INSTALLED -> EmptyState(FuseIcons.CircleCheck, "Every emulator is installed")
                    }
                }
            } else {
                val grid = remember(view.list) { LazyGridState() }
                FollowSelection(grid, { sel.index }, anchor = 0.1f)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    modifier = Modifier.fadingEdges(grid, top = Space.l, bottom = 0.dp).dragScroll(grid),
                    contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Size.hintHeight + Space.x4),
                    horizontalArrangement = Arrangement.spacedBy(Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.xl),
                ) {
                    itemsIndexed(shown, key = { _, e -> e.id.value }) { i, e ->
                        val selected = io.github.matiyaaa.fuse.ui.designsystem.components.isSelected { !view.inTabs && i == sel.index && app.focusZone == FocusZone.CONTENT }
                        EmulatorItem(
                            e, selected, size,
                            modifier = Modifier.reveal(reveal, 1 + i / columns + i % columns),
                            onClick = {
                                app.focusZone = FocusZone.CONTENT
                                view.inTabs = false
                                sel.index = i
                                activate(e)
                            },
                            onLongClick = { sel.index = i; app.showEmulator(e.id) },
                        )
                    }
                }
            }
        }
    }
}

/** One emulator: its icon on the tile's plate, its name under it, and its version or how to get it. */
@Composable
private fun EmulatorItem(
    tile: EmulatorTile,
    selected: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = Fuse.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Tile(selected = selected, glow = c.accent, modifier = Modifier.size(size), onClick = onClick, onLongClick = onLongClick) {
            Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                Artwork(
                    model = tile.icon,
                    modifier = Modifier.fillMaxSize(ICON_SHARE),
                    contentScale = ContentScale.Fit,
                    fallback = {
                        GeneratedArt(tile.name, c.accent, Modifier.fillMaxSize().clip(SquircleShape.fraction(ICON_CORNER)), slot = ArtSlot.ICON)
                    },
                )
            }
        }
        Spacer(Modifier.height(Size.sparkClearance))
        FText(
            tile.name, Fuse.type.label,
            color = if (selected) c.text else c.textMuted,
            maxLines = 2,
            modifier = Modifier.width(size + Space.l + Space.xs),
            align = TextAlign.Center,
        )
        val note = when {
            tile.installed -> tile.version
            tile.storeKey != null -> "In the Store"
            else -> null
        }
        if (note != null) {
            FText(
                note, Fuse.type.caption,
                color = c.textMuted,
                maxLines = 1,
                modifier = Modifier.width(size + Space.l + Space.xs),
                align = TextAlign.Center,
            )
        }
    }
}

/** How much of the plate an emulator's icon covers, as an app's in the Apps section. */
private const val ICON_SHARE = 0.62f

private const val ICON_CORNER = 0.24f

/**
 * The Store's app for the emulator called [name]: the same name once letters and digits are
 * compared ("Dolphin" and "Dolphin Emulator", "PCSX2" and "PCSX2-Qt"), never a guess between two.
 */
fun storeAppFor(name: String, apps: List<StoreApp>): StoreApp? {
    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }.removeSuffix("emulator")
    val n = norm(name)
    if (n.length < 3) return null
    apps.firstOrNull { norm(it.name) == n }?.let { return it }
    val near = apps.filter { a -> norm(a.name).let { it.length >= 3 && (it.startsWith(n) || n.startsWith(it)) } }
    return near.singleOrNull()
}
