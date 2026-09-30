package io.github.matiyaaa.fuse.ui.shell.systems

import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
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
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ScanScope
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
import io.github.matiyaaa.fuse.ui.designsystem.media.PrefetchArt
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
import io.github.matiyaaa.fuse.ui.shell.components.SystemCardArt
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlinx.coroutines.launch

/**
 * Every system Fuse found games for, as a compact grid under a slim header: the focused system's
 * logo or name, its game count and emulator. Its artwork panel from the system art pack stands on
 * the right as the backdrop (a background the user chose fills the screen instead). Firmware and
 * emulator details live on the system's page and settings; the grid only marks a system with no
 * emulator.
 */
@Composable
fun SystemsScreen(app: AppState) {
    val platforms by app.store.library.platforms.collectAsState()
    val systems = platforms.filter { it.gameCount > 0 }
    val sel = rememberRouteState(app.navigator, "systems") { GridSelection() }
    sel.clamp(systems.size)
    val current = systems.getOrNull(sel.index)
    var columns = 5
    // Holding confirm picks a system up; the D-pad moves it and the order is saved for Home too.
    var moving by remember { mutableStateOf(false) }

    LaunchedEffect(current?.platform?.id) {
        app.hero = current?.let { HeroSource(it.platform.id, it.art.hero, it.platform.accent.toColor()) }
    }
    // Logos and art panels of the neighbouring systems are decoded ahead, so the header never waits.
    PrefetchArt(remember(systems) { systems.map { it.art.logo } }, sel.index, size = 360.dp)
    PrefetchArt(remember(systems) { systems.map { it.art.boxart } }, sel.index, size = 480.dp)
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

    BoxWithConstraints(
        Modifier.fillMaxSize()
            // A tap outside the cards puts a carried system down.
            .pointerInput(moving) { if (moving) detectTapGestures { moving = false } },
    ) {
        val gap = Space.m
        val usable = maxWidth - Space.gutter * 2
        // About six cards across, never smaller than a thumb.
        val target = (maxWidth * 0.135f).coerceAtLeast(112.dp)
        columns = ((usable + gap) / (target + gap)).toInt().coerceIn(3, 8)
        val compactHeader = maxHeight < 560.dp
        // The art pack's panel stands on the right, unless the user chose a background for the system.
        if (current?.art?.hero == null) SystemShowcase(current, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(maxHeight * 0.46f))
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + if (compactHeader) Space.s else Space.xl))
            SystemHeader(current, compactHeader, Modifier.padding(horizontal = Space.gutter))
            Spacer(Modifier.height(if (compactHeader) Space.xs else Space.m))
            if (systems.isEmpty()) {
                FText("Systems appear here once Fuse finds games for them.", Fuse.type.body, color = Fuse.colors.textMuted, modifier = Modifier.padding(horizontal = Space.gutter))
            }
            val grid = rememberLazyGridState()
            FollowSelection(grid, { sel.index }, anchor = 0.08f)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                modifier = Modifier.fadingEdges(top = if (grid.canScrollBackward) 20.dp else 0.dp),
                // Room above the first row for a lifted or carried card.
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Size.hintHeight + Space.x4),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                itemsIndexed(systems, key = { _, p -> p.platform.id.value }) { i, card ->
                    val selected = i == sel.index && app.focusZone == FocusZone.CONTENT
                    val carried = moving && i == sel.index
                    val lifted by animateFloatAsState(if (carried) 1f else 0f, Fuse.motion.focusSpring(), label = "carry")
                    Tile(
                        selected = selected,
                        glow = card.platform.accent.toColor(),
                        modifier = Modifier
                            .animateItem(fadeInSpec = null, fadeOutSpec = null)
                            .zIndex(if (carried) 1f else 0f)
                            .graphicsLayer {
                                // A carried system floats a little above the others.
                                val s = 1f + 0.05f * lifted
                                scaleX = s
                                scaleY = s
                                translationY = -6.dp.toPx() * lifted
                            }
                            .fillMaxWidth()
                            .aspectRatio(Aspect.SYSTEM_CARD),
                        onClick = {
                            app.focusZone = FocusZone.CONTENT
                            when {
                                moving -> moving = false
                                sel.index == i -> app.go(Route.PlatformGames(card.platform.id))
                                else -> sel.index = i
                            }
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

/** The focused system: logo (or name), then games and emulator. No firmware details here. */
@Composable
internal fun SystemHeader(card: PlatformCard?, compact: Boolean, modifier: Modifier = Modifier, widthFraction: Float = 0.62f) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val logoHeight = if (compact) 40.dp else 64.dp
    AnimatedContent(
        targetState = card,
        modifier = modifier.fillMaxWidth(widthFraction),
        contentKey = { it?.platform?.id },
        transitionSpec = { fadeIn(motion.fade(Durations.BASE)) togetherWith fadeOut(motion.fade(Durations.INSTANT)) },
        contentAlignment = Alignment.BottomStart,
        label = "system header",
    ) { s ->
        if (s == null) {
            Spacer(Modifier.height(logoHeight))
            return@AnimatedContent
        }
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) Space.xs else Space.s)) {
            val name: @Composable () -> Unit = {
                FText(s.platform.name, if (compact) Fuse.type.title else Fuse.type.display, color = c.text, maxLines = 1)
            }
            if (s.art.logo != null) {
                Artwork(
                    s.art.logo,
                    Modifier.height(logoHeight).fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                    focusX = 0f,
                    focusY = 0.5f,
                    tint = Color.White,
                    fallback = name,
                )
            } else {
                name()
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Box(Modifier.size(7.dp).background(s.platform.accent.toColor(), CircleShape))
                FText("${s.gameCount} ${if (s.gameCount == 1) "game" else "games"}", Fuse.type.body, color = c.textMuted, maxLines = 1)
                FText("·", Fuse.type.body, color = c.textFaint)
                if (s.emulatorInstalled && s.emulatorName != null) {
                    FText(s.emulatorName, Fuse.type.body, color = c.textMuted, maxLines = 1)
                } else {
                    FuseIcon(FuseIcons.Warning, size = 14.dp, tint = c.warning)
                    FText("No emulator installed", Fuse.type.body, color = c.warning, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The system art pack's tall artwork panel (made for the right side of a frontend's system view),
 * fading into the background on its left and toward the bottom so the grid stays calm.
 */
@Composable
internal fun SystemShowcase(card: PlatformCard?, modifier: Modifier) {
    val art = card?.art?.boxart
    Crossfade(targetState = art, modifier = modifier, animationSpec = Fuse.motion.fade(Durations.SLOW), label = "showcase") { model ->
        if (model == null) return@Crossfade
        Artwork(
            model,
            Modifier.fillMaxSize().panelFade(),
            contentScale = ContentScale.Crop,
            focusX = 0.5f,
            focusY = 0.3f,
        )
    }
}

/**
 * Fades a side panel into the background: from nothing on its left to full on its right, clear of
 * the status bar at the top and quieter toward the bottom.
 */
internal fun Modifier.panelFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.55f to Color.Black), blendMode = BlendMode.DstIn)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.2f to Color.Black.copy(alpha = 0.8f), 0.5f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.15f)),
            blendMode = BlendMode.DstIn,
        )
    }

/** Options for a system (Context button or long press on its card). */
fun AppState.systemMenu(card: PlatformCard): ContextMenuSpec {
    val p = card.platform
    val owner = MediaOwner.OfPlatform(p.id)
    return ContextMenuSpec(
        title = p.name,
        subtitle = "${card.gameCount} games",
        actions = listOfNotNull(
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
            if (!store.cartridge.status.value.installed) null else MenuAction("cartridge", "Browse in Cartridge", FuseIcons.CloudDownload, onSelect = {
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
