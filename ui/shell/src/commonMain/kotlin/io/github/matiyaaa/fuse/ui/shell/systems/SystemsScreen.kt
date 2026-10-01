package io.github.matiyaaa.fuse.ui.shell.systems

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.dragReorder
import io.github.matiyaaa.fuse.ui.designsystem.focus.rememberDragReorderState
import io.github.matiyaaa.fuse.ui.designsystem.focus.reorderItem
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.media.PrefetchArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.LocateRequest
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.app.startLocate
import io.github.matiyaaa.fuse.ui.shell.components.SystemCardArt
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlin.math.roundToInt
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
    // By touch, a held system follows the finger and the others make room; the grid shows that order.
    val drag = rememberDragReorderState()
    val shown = drag.arrange(systems) { it.platform.id.value }
    val current = drag.heldKey?.let { k -> shown.firstOrNull { it.platform.id.value == k } } ?: shown.getOrNull(sel.index)
    var columns = 5
    // Holding confirm picks a system up; the D-pad moves it and the order is saved for Home too.
    var moving by remember { mutableStateOf(false) }
    fun menu(card: PlatformCard, index: Int) = app.systemMenu(card) { sel.index = index; moving = true }

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
            NavAction.CONTEXT -> { current?.let { app.openContextMenu(menu(it, sel.index)) }; NavResult.ACTIVATED }
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
                modifier = Modifier
                    .fadingEdges(top = if (grid.canScrollBackward) 20.dp else 0.dp)
                    .dragReorder(
                        drag,
                        visibleKeys = { grid.layoutInfo.visibleItemsInfo.map { it.key } },
                        scrollBy = { grid.scrollBy(it) },
                        enabled = !moving,
                        longPressMs = app.store.prefs.value.input.longPressMs.toLong(),
                        endInset = Size.hintHeight,
                        onLift = { key ->
                            app.focusZone = FocusZone.CONTENT
                            sel.index = shown.indexOfFirst { it.platform.id.value == key }.coerceAtLeast(0)
                            app.platform.haptics.tick()
                        },
                        onTarget = { app.platform.haptics.tick() },
                        // A hold let go where it started still opens the options, as it always did.
                        onHoldReleased = { key ->
                            val i = systems.indexOfFirst { it.platform.id.value == key }
                            if (i >= 0) {
                                sel.index = i
                                app.openContextMenu(menu(systems[i], i))
                            }
                        },
                        onDrop = { key, to ->
                            val from = systems.indexOfFirst { it.platform.id.value == key }
                            if (from >= 0 && to != from) app.moveSystem(systems, from, to - from)
                            sel.index = to
                        },
                    ),
                // Room above the first row for a lifted or carried card.
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Size.hintHeight + Space.x4),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                itemsIndexed(shown, key = { _, p -> p.platform.id.value }) { i, card ->
                    val key = card.platform.id.value
                    val selected = i == sel.index && app.focusZone == FocusZone.CONTENT
                    val carried = moving && i == sel.index
                    val lifted by animateFloatAsState(if (carried) 1f else 0f, Fuse.motion.focusSpring(), label = "carry")
                    Tile(
                        selected = selected,
                        glow = card.platform.accent.toColor(),
                        modifier = Modifier
                            // The held system follows the finger, never an animation behind it.
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (drag.heldKey == key) null else ItemPlacement)
                            .reorderItem(drag, key)
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
                                // Carrying with the controller, a tap on another system puts it there.
                                moving -> {
                                    if (i != sel.index) sel.index = app.moveSystem(systems, sel.index, i - sel.index)
                                    moving = false
                                }
                                // A tap opens the system at once; only games wait for a second tap.
                                else -> {
                                    sel.index = i
                                    app.go(Route.PlatformGames(card.platform.id))
                                }
                            }
                        },
                    ) {
                        SystemCardArt(card)
                    }
                }
            }
        }
    }
}

/**
 * The focused system: logo (or name), then games and emulator. No firmware details here. [collapse]
 * folds it away upwards (0 shows it all, 1 hides it), for a system's page scrolled down its games.
 */
@Composable
internal fun SystemHeader(
    card: PlatformCard?,
    compact: Boolean,
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.62f,
    collapse: Float = 0f,
    /** The game count and emulator under the logo; the system's own page leaves them out. */
    showMeta: Boolean = true,
    logoHeight: Dp = if (compact) 40.dp else 64.dp,
    nameStyle: TextStyle = if (compact) Fuse.type.title else Fuse.type.display,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    AnimatedContent(
        targetState = card,
        modifier = modifier.fillMaxWidth(widthFraction).foldAway(collapse),
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
                FText(s.platform.name, nameStyle, color = c.text, maxLines = 1)
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
            if (showMeta) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
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
 * Folds content away upwards by [fraction]: it slides up under its own top edge, fades out a little
 * ahead of the slide, and gives its height back to whatever follows.
 */
internal fun Modifier.foldAway(fraction: Float): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val f = fraction.coerceIn(0f, 1f)
        val h = (p.height * (1f - f)).roundToInt()
        layout(p.width, h) {
            p.placeWithLayer(0, h - p.height) { alpha = (1f - f * 1.6f).coerceIn(0f, 1f) }
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

/** How systems slide aside while one is moved. */
private val ItemPlacement = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)

/**
 * Options for a system (Context button or long press on its card). [onMove] adds "Move this system"
 * where systems can be arranged.
 */
fun AppState.systemMenu(card: PlatformCard, onMove: (() -> Unit)? = null): ContextMenuSpec {
    val p = card.platform
    val owner = MediaOwner.OfPlatform(p.id)
    return ContextMenuSpec(
        title = p.name,
        subtitle = "${card.gameCount} games",
        actions = listOfNotNull(
            MenuAction("open", "Open", FuseIcons.Grid, onSelect = { closeOverlays(); go(Route.PlatformGames(p.id)) }),
            onMove?.let { move ->
                MenuAction("move", "Move this system", FuseIcons.Move, detail = "Or hold confirm. By touch, hold it and drag", onSelect = { closeOverlays(); move() })
            },
            MenuAction("settings", "System Settings", FuseIcons.Settings, trailing = Trailing.Chevron, onSelect = { closeOverlays(); go(Route.PlatformSettings(p.id)) }),
            MenuAction("media", "Change System Media", FuseIcons.Image, detail = "Icon, background and logo for ${p.shortName}", trailing = Trailing.Chevron, onSelect = {
                closeOverlays(); go(Route.Media(owner, p.name))
            }),
            MenuAction("fill", "Fill Missing Game Art", FuseIcons.Wand, detail = "Only games without art; your custom art is never replaced", onSelect = {
                closeOverlays()
                store.media.fill(MediaFillMode.FILL_MISSING, MediaKind.Fillable, platform = p.id)
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
            // Where Fuse can be shown an emulator, one it didn't find can be located instead.
            val locate = !o.installed && store.emulators.canLocate
            MenuAction(
                "e${o.id}", o.name, FuseIcons.Chip,
                detail = if (locate) "Not found. Show Fuse where it is" else o.note,
                unavailableReason = if (o.installed || locate) null else "Not installed",
                onSelect = {
                    if (locate) {
                        startLocate(LocateRequest(o.id, o.name, platform = card.platform.id))
                    } else {
                        scope.launch { store.emulators.setPlatformEmulator(card.platform.id, o.id) }
                        choice = null
                    }
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
