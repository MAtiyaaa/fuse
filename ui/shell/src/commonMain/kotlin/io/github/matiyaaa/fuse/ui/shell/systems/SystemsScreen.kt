package io.github.matiyaaa.fuse.ui.shell.systems

import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.Stage
import io.github.matiyaaa.fuse.ui.shell.components.SystemCardArt
import io.github.matiyaaa.fuse.ui.shell.components.stage
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlinx.coroutines.launch

/**
 * Every system Fuse found games for, as large cards. A card shows the system's own art when set,
 * otherwise an original typographic design in its colour; a warning mark means no emulator is
 * installed or firmware is missing.
 */
@Composable
fun SystemsScreen(app: AppState) {
    val platforms by app.store.library.platforms.collectAsState()
    val systems = platforms.filter { it.gameCount > 0 }
    val sel = rememberRouteState(app.navigator, "systems") { GridSelection() }
    sel.clamp(systems.size)
    val current = systems.getOrNull(sel.index)
    var columns = 4
    // Holding confirm picks a system up; the D-pad moves it and the order is saved for Home too.
    var moving by remember { mutableStateOf(false) }

    LaunchedEffect(current?.platform?.id) {
        app.hero = current?.let { HeroSource(it.platform.id, it.art.hero, it.platform.accent.toColor()) }
    }
    LaunchedEffect(moving) {
        app.hints = if (moving) {
            listOf(Hint(HintButton.DPAD, "Move"), Hint(HintButton.CONFIRM, "Done"))
        } else {
            listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.HOLD_CONFIRM, "Hold to move"), Hint(HintButton.OPTIONS, "System options"))
        }
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen, longPress = true) { e ->
        if (moving) {
            val delta = when (e.action) {
                NavAction.LEFT -> -1
                NavAction.RIGHT -> 1
                NavAction.UP -> -columns
                NavAction.DOWN -> columns
                NavAction.SELECT, NavAction.BACK, NavAction.REORDER -> { moving = false; return@InputLayer NavResult.CONSUMED }
                else -> return@InputLayer NavResult.CONSUMED
            }
            val to = app.moveSystem(systems, sel.index, delta)
            return@InputLayer if (to == sel.index) NavResult.BLOCKED else { sel.index = to; NavResult.MOVED }
        }
        when (e.action) {
            NavAction.REORDER -> { if (current != null) moving = true; NavResult.ACTIVATED }
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT, NavAction.PAGE_UP, NavAction.PAGE_DOWN ->
                sel.move(e.action, systems.size, columns).let { if (it == NavResult.IGNORED && e.action != NavAction.UP) NavResult.BLOCKED else it }
            NavAction.SELECT -> { current?.let { app.go(Route.PlatformGames(it.platform.id)) }; NavResult.ACTIVATED }
            NavAction.CONTEXT -> { current?.let { app.openContextMenu(app.systemMenu(it)) }; NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardWidth = ((maxWidth - Space.gutter * 2) / 4.2f).coerceIn(180.dp, 320.dp)
        columns = ((maxWidth - Space.gutter * 2 + Space.l) / (cardWidth + Space.l)).toInt().coerceAtLeast(2)
        val maxH = maxHeight
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight))
            Box(Modifier.fillMaxWidth().height((maxH * 0.24f).coerceIn(120.dp, 210.dp)).padding(horizontal = Space.gutter), contentAlignment = Alignment.BottomStart) {
                Stage(current?.stage())
            }
            current?.let { StatusLine(it) }
            Spacer(Modifier.height(Space.l))
            if (systems.isEmpty()) {
                FText("Systems appear here once Fuse finds games for them.", Fuse.type.body, color = Fuse.colors.textMuted, modifier = Modifier.padding(horizontal = Space.gutter))
            }
            val grid = rememberLazyGridState()
            FollowSelection(grid, { sel.index }, anchor = 0.08f)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                modifier = Modifier.fadingEdges(top = if (grid.canScrollBackward) 24.dp else 0.dp),
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.s, bottom = Size.hintHeight + Space.x4),
                horizontalArrangement = Arrangement.spacedBy(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.xl),
            ) {
                itemsIndexed(systems, key = { _, p -> p.platform.id.value }) { i, card ->
                    val selected = i == sel.index && app.focusZone == FocusZone.CONTENT
                    Tile(
                        selected = selected,
                        glow = card.platform.accent.toColor(),
                        modifier = Modifier.fillMaxWidth().aspectRatio(Aspect.SYSTEM_CARD),
                        onClick = {
                            app.focusZone = FocusZone.CONTENT
                            if (sel.index == i) app.go(Route.PlatformGames(card.platform.id)) else sel.index = i
                        },
                        onLongClick = { sel.index = i; app.openContextMenu(app.systemMenu(card)) },
                    ) {
                        SystemCardArt(card)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(card: PlatformCard) {
    val c = Fuse.colors
    Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        Chip(card.emulatorName ?: "No emulator", icon = FuseIcons.Chip, color = if (card.emulatorInstalled) c.text else c.warning)
        val (label, color) = when (card.bios.state) {
            BiosState.READY -> "BIOS ready" to c.success
            BiosState.PARTIAL -> "BIOS partly found" to c.warning
            BiosState.MISSING -> "BIOS missing" to c.danger
            BiosState.UNKNOWN -> "BIOS: check in emulator" to c.textMuted
            BiosState.NOT_REQUIRED -> null to c.text
        }
        if (label != null) Chip(label, icon = FuseIcons.Key, color = color)
        if (card.installedEmulators > 1) Chip("${card.installedEmulators} emulators", color = c.textMuted)
    }
}

/** Options for a system (Context button or long press on its card). */
fun AppState.systemMenu(card: PlatformCard): ContextMenuSpec {
    val p = card.platform
    val owner = MediaOwner.OfPlatform(p.id)
    return ContextMenuSpec(
        title = p.name,
        subtitle = "${card.gameCount} games",
        actions = listOf(
            MenuAction("open", "Open", FuseIcons.Grid, onSelect = { closeOverlays(); go(Route.PlatformGames(p.id)) }),
            MenuAction("settings", "System Settings", FuseIcons.Settings, trailing = Trailing.Chevron, onSelect = { closeOverlays(); go(Route.PlatformSettings(p.id)) }),
            MenuAction("media", "Change System Media", FuseIcons.Image, detail = "Icon, background and logo for ${p.shortName}", trailing = Trailing.Chevron, onSelect = {
                closeOverlays(); go(Route.Media(owner, p.name))
            }),
            MenuAction("fill", "Fill Missing Game Art", FuseIcons.Wand, detail = "Only games without art; your custom art is never replaced", onSelect = {
                closeOverlays()
                store.media.fill(MediaFillMode.FILL_MISSING, setOf(MediaKind.ICON, MediaKind.BOXART, MediaKind.HERO, MediaKind.LOGO, MediaKind.GRID), platform = p.id)
                toasts.show("Finding missing art for ${p.shortName}")
            }),
            MenuAction("emulator", "Emulator", FuseIcons.Chip, trailing = Trailing.Value(card.emulatorName ?: "None"), onSelect = { platformEmulatorPicker(card) }),
            MenuAction("openemu", "Open Emulator", FuseIcons.External, unavailableReason = if (!card.emulatorInstalled) "No emulator installed" else null, onSelect = {
                closeOverlays()
                val id = store.emulators.installed.value.firstOrNull { it.name == card.emulatorName }?.id
                if (id != null) scope.launch { store.emulators.openEmulator(id) }
            }),
            MenuAction("folder", "ROM Folders", FuseIcons.Folder, detail = card.romFolders.joinToString("\n").ifBlank { "None found" }, onSelect = {}),
            MenuAction("bios", "BIOS and Firmware", FuseIcons.Key, trailing = Trailing.Chevron, onSelect = { closeOverlays(); go(Route.PlatformSettings(p.id)) }),
            MenuAction("rescan", "Rescan", FuseIcons.Refresh, onSelect = {
                closeOverlays(); store.sources.rescan(ScanScope.PLATFORM, p.id); toasts.show("Rescanning ${p.shortName}")
            }),
            MenuAction("cartridge", "Browse in Cartridge", FuseIcons.CloudDownload, onSelect = {
                closeOverlays(); store.cartridge.open(CartridgeRoute.Platform(p.id.value))
            }),
        ),
    )
}

fun AppState.platformEmulatorPicker(card: PlatformCard) {
    contextMenu = null
    val options = store.emulators.optionsFor(card.platform.id)
    choice = ChoiceSpec(
        title = "Emulator for ${card.platform.name}",
        message = if (options.none { it.installed }) "None installed yet. Fuse notices when you install one." else "Games use this unless they have their own choice.",
        options = listOf(
            MenuAction("auto", "Automatic", FuseIcons.Sparkles, detail = "The first installed emulator in Fuse's recommended order", onSelect = {
                scope.launch { store.emulators.setPlatformEmulator(card.platform.id, null) }
                choice = null
            }),
        ) + options.map { o ->
            MenuAction(
                "e${o.id}", o.name, FuseIcons.Chip,
                detail = o.note,
                unavailableReason = if (o.installed) null else "Not installed",
                onSelect = {
                    scope.launch { store.emulators.setPlatformEmulator(card.platform.id, o.id) }
                    choice = null
                },
            )
        },
    )
}

/**
 * Moves the system at [index] of [systems] by [delta] places and saves the whole order, so Home,
 * Systems and the Library's system picker all follow it. Returns the system's new index.
 */
fun AppState.moveSystem(systems: List<PlatformCard>, index: Int, delta: Int): Int {
    val target = index + delta
    if (index !in systems.indices || target !in systems.indices) return index
    val ids = systems.map { it.platform.id.value }.toMutableList()
    ids.add(target, ids.removeAt(index))
    store.updatePrefs { it.copy(systemOrder = ids + it.systemOrder.filterNot { id -> id in ids }) }
    return target
}
