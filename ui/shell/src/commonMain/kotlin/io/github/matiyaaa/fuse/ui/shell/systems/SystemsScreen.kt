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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.IconBadge
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ReorderDefaults
import io.github.matiyaaa.fuse.ui.designsystem.focus.carried
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.dragReorder
import io.github.matiyaaa.fuse.ui.designsystem.focus.rememberDragReorderState
import io.github.matiyaaa.fuse.ui.designsystem.focus.reorderItem
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.media.PrefetchArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
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
            val key = current?.platform?.id?.value ?: return@InputLayer NavResult.BLOCKED
            val to = app.moveSystemBy(key, delta)
            return@InputLayer if (to < 0 || to == sel.index) NavResult.BLOCKED else { sel.index = to; NavResult.MOVED }
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

    val reveal = rememberReveal()
    BoxWithConstraints(
        Modifier.fillMaxSize()
            // A tap outside the cards puts a carried system down.
            .pointerInput(moving) { if (moving) detectTapGestures { moving = false } },
    ) {
        val gap = Space.m
        val usable = maxWidth - Space.gutter * 2
        // About six cards across, never smaller than a thumb; two across on a phone held upright.
        val target = (maxWidth * 0.135f).coerceAtLeast(Size.touch * 2 + Space.l)
        columns = ((usable + gap) / (target + gap)).toInt().coerceIn(if (maxWidth < Size.touch * 12) 2 else 3, 8)
        val cardHeight = (usable - gap * (columns - 1)) / columns / Aspect.SYSTEM_CARD
        val compactHeader = maxHeight < Size.touch * 12
        // A phone held upright gives the header the whole width; wider screens keep the art's side free.
        val headerWidth = if (maxWidth < Size.touch * 14) 1f else 0.62f
        // The art pack's panel stands on the right, unless the user chose a background for the system.
        if (current?.art?.hero == null) SystemShowcase(current, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(maxHeight * 0.46f))
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + if (compactHeader) Space.s else Space.xl))
            SystemHeader(
                current, compactHeader, Modifier.padding(horizontal = Space.gutter).reveal(reveal, 0),
                widthFraction = headerWidth,
                moving = if (moving && systems.isNotEmpty()) "Moving, place ${sel.index + 1} of ${systems.size}" else null,
            )
            Spacer(Modifier.height(if (compactHeader) Space.xs else Space.m))
            if (systems.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xl), contentAlignment = Alignment.Center) {
                    EmptyState(
                        FuseIcons.Gamepad,
                        "No systems yet",
                        message = "Systems appear here once Fuse finds games for them. Add the folder your games are in from Settings, Library.",
                        compact = compactHeader,
                        modifier = Modifier.padding(bottom = Size.hintHeight),
                    )
                }
                return@Column
            }
            val grid = rememberLazyGridState()
            // While a system is held the grid stays where the finger left it.
            FollowSelection(grid, { sel.index }, anchor = 0.08f, enabled = { drag.heldKey == null })
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                modifier = Modifier
                    .fadingEdges(grid, top = Space.xl, bottom = Size.hintHeight + Space.l)
                    .dragReorder(
                        drag,
                        visibleKeys = { grid.layoutInfo.visibleItemsInfo.map { it.key } },
                        scrollBy = { grid.scrollBy(it) },
                        keepScroll = { grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) },
                        enabled = !moving,
                        longPressMs = ReorderDefaults.liftMs(app.store.prefs.value.input.longPressMs.toLong()),
                        endInset = Size.hintHeight,
                        onLift = { key ->
                            app.focusZone = FocusZone.CONTENT
                            sel.index = shown.indexOfFirst { it.platform.id.value == key }.coerceAtLeast(0)
                            app.platform.haptics.lift()
                        },
                        onTarget = { app.platform.haptics.slot() },
                        // A hold let go where it started still opens the options, as it always did.
                        onHoldReleased = { key ->
                            val i = systems.indexOfFirst { it.platform.id.value == key }
                            if (i >= 0) {
                                sel.index = i
                                app.openContextMenu(menu(systems[i], i))
                            }
                        },
                        onDrop = { key, to ->
                            val placed = app.moveSystem(key.toString(), to)
                            if (placed >= 0) sel.index = placed
                            app.platform.haptics.drop()
                        },
                    ),
                // Room above the first row for a lifted or carried card.
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Size.hintHeight + Space.x4),
                horizontalArrangement = Arrangement.spacedBy(gap),
                // Rows stay clear of the spark under a lifted card.
                verticalArrangement = Arrangement.spacedBy(Size.sparkClearance),
            ) {
                itemsIndexed(shown, key = { _, p -> p.platform.id.value }) { i, card ->
                    val key = card.platform.id.value
                    // While held, the selection stays on the held system wherever it would land.
                    val selected = (drag.heldKey?.let { it == key } ?: (i == sel.index)) && app.focusZone == FocusZone.CONTENT
                    val carried = moving && i == sel.index
                    val lifted by animateFloatAsState(if (carried) 1f else 0f, Fuse.motion.focusSpring(), label = "carry")
                    val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction)
                    Tile(
                        selected = selected,
                        glow = card.platform.accent.toColor(),
                        modifier = Modifier
                            // The held system follows the finger, never an animation behind it.
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (drag.heldKey == key) null else ReorderDefaults.Placement)
                            // Row by row as the screen opens.
                            .reveal(reveal, 1 + i / columns)
                            .reorderItem(drag, key, shape = shape)
                            .zIndex(if (carried) 1f else 0f)
                            // Carried with the controller, a system floats the same as one held by touch.
                            .carried({ lifted }, shape)
                            .fillMaxWidth()
                            .aspectRatio(Aspect.SYSTEM_CARD),
                        onClick = {
                            app.focusZone = FocusZone.CONTENT
                            when {
                                // Carrying with the controller, a tap on another system puts it there.
                                moving -> {
                                    val carriedKey = systems.getOrNull(sel.index)?.platform?.id?.value
                                    if (i != sel.index && carriedKey != null) app.moveSystem(carriedKey, i).takeIf { it >= 0 }?.let { sel.index = it }
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
                        SystemCardFace(card, cardHeight)
                        // A carried system says so: it moves with the D-pad until confirmed.
                        if (carried) IconBadge(FuseIcons.Move, Modifier.align(Alignment.TopEnd).padding(Space.s), tint = Fuse.colors.onArt, background = Fuse.colors.artScrim)
                    }
                }
            }
        }
    }
}

/**
 * A system's face on the Systems grid. Systems with their own square art or icon show it whole.
 * Otherwise the card is the system's colour (or its art pack's panel and logo) with its maker on
 * top, its name (or logo) and, at the foot, how many games it has and the emulator they start in,
 * so the grid answers "what runs this?" without opening anything. Small cards keep the name and
 * count only.
 */
@Composable
private fun SystemCardFace(card: PlatformCard, height: Dp) {
    val art = card.art
    if ((art.square ?: art.icon) != null) {
        SystemCardArt(card)
        return
    }
    val c = Fuse.colors
    val accent = card.platform.accent.toColor()
    // Roomy cards carry the maker and the emulator too; small ones (phones) the name and count.
    val roomy = height >= Size.touch * 2
    val tiny = height < Size.touch + Space.l
    Box(Modifier.fillMaxSize()) {
        GeneratedArt(title = card.platform.name, accent = accent, slot = ArtSlot.WIDE, showText = false)
        if (art.boxart != null) {
            // The art pack's tall panel stands at the right end, blended into the card's colour.
            Artwork(
                art.boxart,
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.42f).panelBlend(),
                contentScale = ContentScale.Crop,
                focusX = 0.5f,
                focusY = 0.35f,
            )
        }
        // A floor under the words, so they read on any colour.
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(0.4f to Color.Transparent, 1f to c.artScrim.copy(alpha = c.artScrim.alpha * 0.7f))) })
        Column(
            Modifier.fillMaxSize().padding(if (roomy) Space.m else Space.s + Space.xxs),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val maker = card.platform.manufacturer?.uppercase()
            if (roomy && maker != null) FText(maker, Fuse.type.overline, color = c.onArtMuted, maxLines = 1) else Spacer(Modifier.height(Space.hair))
            Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                val name: @Composable () -> Unit = {
                    FText(card.platform.shortName, if (roomy) Fuse.type.title else Fuse.type.titleSmall, color = c.onArt, maxLines = 1)
                }
                if (art.logo != null) {
                    Artwork(
                        art.logo,
                        Modifier.fillMaxWidth(0.62f).height((height * 0.24f).coerceIn(Space.l + Space.xxs, Size.touch + Space.xl)),
                        contentScale = ContentScale.Fit,
                        focusX = 0f,
                        focusY = 1f,
                        tint = c.onArt,
                        fallback = name,
                    )
                } else {
                    name()
                }
                if (!tiny) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FText(gamesText(card.gameCount), Fuse.type.caption.tabular(), color = c.onArt.copy(alpha = 0.9f), maxLines = 1)
                        if (roomy) {
                            val emulator = card.emulatorName?.takeIf { card.emulatorInstalled }
                            FText("  ·  ", Fuse.type.caption, color = c.onArtMuted, maxLines = 1)
                            FText(emulator ?: "No emulator", Fuse.type.caption, color = if (emulator == null) c.warning else c.onArtMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                }
            }
        }
        if (!card.emulatorInstalled) {
            IconBadge(FuseIcons.Warning, Modifier.align(Alignment.TopEnd).padding(Space.s), tint = c.warning, background = c.artScrim, size = Size.badge)
        }
    }
}

/**
 * A small square standing for a system in lists, pickers and page headers: its own square art or
 * icon where it has one, else its colour with its short name set as large as fits ("PS2",
 * "Switch"), so systems never become ambiguous initials.
 */
@Composable
internal fun SystemMark(card: PlatformCard, size: Dp, modifier: Modifier = Modifier) {
    val accent = card.platform.accent.toColor()
    val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.12f) + 0.06f)
    val generated: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GeneratedArt(title = card.platform.name, accent = accent, slot = ArtSlot.WIDE, showText = false)
            val style = Fuse.type.titleSmall
            BasicText(
                card.platform.shortName,
                Modifier.padding(horizontal = size * 0.12f),
                style = style.copy(color = Fuse.colors.onArt, textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.45f, maxFontSize = style.fontSize * (size / Size.thumbL).coerceIn(0.7f, 1.6f)),
            )
        }
    }
    Box(modifier.size(size).clip(shape)) {
        val own = card.art.square ?: card.art.icon
        if (own != null) Artwork(own, Modifier.fillMaxSize(), fallback = generated) else generated()
    }
}

/** Fades an art pack panel in from its left edge, so it melts into the card's colour. */
private fun Modifier.panelBlend(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.6f to Color.Black), blendMode = BlendMode.DstIn)
    }

internal fun gamesText(n: Int) = "$n ${if (n == 1) "game" else "games"}"

/** What is wrong with a system's firmware, when Fuse looked and knows: missing or partly found. */
private fun firmwareProblem(card: PlatformCard): String? = when (card.bios.state) {
    BiosState.MISSING -> "Firmware missing"
    BiosState.PARTIAL -> "Firmware partly found"
    else -> null
}

/**
 * The focused system, told big: its maker and year as a small line on top, its logo (or name), then
 * how many games it has and the emulator they start in, or a warning when none is installed.
 * Firmware details live on the system's page. [collapse] folds it away upwards (0 shows it all, 1
 * hides it), for a system's page scrolled down its games.
 *
 * With [showMeta] (the Systems screen) the name sits in a slot as tall as a logo, so moving between
 * systems with and without a logo never moves the grid below.
 */
@Composable
internal fun SystemHeader(
    card: PlatformCard?,
    compact: Boolean,
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.62f,
    collapse: Float = 0f,
    /** The maker line, game count and emulator around the logo; the system's own page leaves them out. */
    showMeta: Boolean = true,
    logoHeight: Dp = if (compact) Size.touch - Space.s else Size.touch + Space.l,
    nameStyle: TextStyle = if (compact) Fuse.type.title else Fuse.type.display,
    /** Said in place of the maker line while the system is being moved ("Moving, place 3 of 11"). */
    moving: String? = null,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    AnimatedContent(
        targetState = card,
        modifier = modifier.fillMaxWidth(widthFraction).foldAway(collapse),
        contentKey = { it?.platform?.id },
        transitionSpec = { fadeIn(motion.fade(Durations.BASE)) togetherWith fadeOut(motion.exit(Durations.INSTANT)) },
        contentAlignment = Alignment.BottomStart,
        label = "system header",
    ) { s ->
        if (s == null) {
            Spacer(Modifier.height(logoHeight))
            return@AnimatedContent
        }
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) Space.xs else Space.s)) {
            if (showMeta) {
                val eyebrow = listOfNotNull(s.platform.manufacturer?.uppercase(), s.platform.releaseYear?.toString()).joinToString("  ·  ")
                Row(Modifier.height(Size.iconS), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (moving != null) {
                        // Carried: the line says where it is now, so each step of the D-pad is felt.
                        FuseIcon(FuseIcons.Move, size = Size.iconS, tint = c.text)
                        FText(moving.uppercase(), Fuse.type.overline.tabular(), color = c.text, maxLines = 1)
                    } else {
                        Box(Modifier.size(Size.dot).background(s.platform.accent.toColor(), CircleShape))
                        FText(eyebrow.ifEmpty { "SYSTEM" }, Fuse.type.overline.tabular(), color = c.textMuted, maxLines = 1)
                    }
                }
            }
            val name: @Composable () -> Unit = {
                FText(s.platform.name, nameStyle, color = c.text, maxLines = 1)
            }
            Box(if (showMeta) Modifier.height(logoHeight) else Modifier, contentAlignment = Alignment.BottomStart) {
                if (s.art.logo != null) {
                    Artwork(
                        s.art.logo,
                        Modifier.height(logoHeight).fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                        focusX = 0f,
                        focusY = if (showMeta) 1f else 0.5f,
                        tint = c.text,
                        fallback = name,
                    )
                } else {
                    name()
                }
            }
            if (showMeta) BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Only firmware Fuse knows is missing is told; one it can't check is never a warning.
                val problem = firmwareProblem(s)
                // Where the line would crowd (a phone held upright), the firmware gets its own line.
                val apart = problem != null && maxWidth < Size.touch * 8
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        FText(gamesText(s.gameCount), Fuse.type.bodyStrong.tabular(), color = c.text, maxLines = 1)
                        FText("·", Fuse.type.body, color = c.textFaint)
                        if (s.emulatorInstalled && s.emulatorName != null) {
                            FuseIcon(FuseIcons.Chip, size = Size.iconS, tint = c.textMuted)
                            FText(s.emulatorName, Fuse.type.body, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        } else {
                            FuseIcon(FuseIcons.Warning, size = Size.iconS, tint = c.warning)
                            FText("No emulator installed", Fuse.type.body, color = c.warning, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        }
                        if (problem != null && !apart) {
                            FText("·", Fuse.type.body, color = c.textFaint)
                            FirmwareProblem(problem)
                        }
                    }
                    if (problem != null && apart) FirmwareProblem(problem)
                }
            }
        }
    }
}

/** A firmware warning in the header: a key and what is wrong, in the warning colour. */
@Composable
private fun FirmwareProblem(problem: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        FuseIcon(FuseIcons.Key, size = Size.iconS, tint = Fuse.colors.warning)
        FText(problem, Fuse.type.body, color = Fuse.colors.warning, maxLines = 1)
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

/**
 * Options for a system (Context button or long press on its card). [onMove] adds "Move this system"
 * where systems can be arranged.
 */
fun AppState.systemMenu(card: PlatformCard, onMove: (() -> Unit)? = null): ContextMenuSpec {
    val p = card.platform
    val owner = MediaOwner.OfPlatform(p.id)
    return ContextMenuSpec(
        title = p.name,
        subtitle = listOfNotNull(gamesText(card.gameCount), card.emulatorName?.takeIf { card.emulatorInstalled }).joinToString("  ·  "),
        icon = FuseIcons.Gamepad,
        art = card.art.square ?: card.art.icon,
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
 * Moves the system [key] to place [to] and saves the whole order, so Home, Systems and the
 * Library's system picker all follow it. The order is worked out from the saved order as it is at
 * this moment, never from a list a screen kept, so a move can't undo the one before it. Returns
 * the system's new place, or -1 when it isn't shown.
 */
fun AppState.moveSystem(key: String, to: Int): Int {
    var placed = -1
    store.updatePrefs { p ->
        val ids = SystemOrder.shown(store.library.platforms.value.map { it.platform.id.value }, p.systemOrder).toMutableList()
        val from = ids.indexOf(key)
        if (from < 0) return@updatePrefs p
        placed = to.coerceIn(0, ids.lastIndex)
        if (placed == from) return@updatePrefs p
        ids.add(placed, ids.removeAt(from))
        p.copy(systemOrder = SystemOrder.save(ids, p.systemOrder))
    }
    return placed
}

/** Moves the system [key] by [delta] places; its place when it can't go further, -1 when it isn't shown. */
fun AppState.moveSystemBy(key: String, delta: Int): Int {
    val ids = SystemOrder.shown(store.library.platforms.value.map { it.platform.id.value }, store.prefs.value.systemOrder)
    val from = ids.indexOf(key)
    if (from < 0) return -1
    if (from + delta !in ids.indices) return from
    return moveSystem(key, from + delta)
}

/** The rules for the systems' order, kept apart so they can be tested. */
internal object SystemOrder {
    /**
     * [shown] (the systems on screen, as the store listed them) in the order [saved] gives them:
     * saved ones first, the rest after them in the order they are listed, as the store does.
     */
    fun shown(shown: List<String>, saved: List<String>): List<String> {
        val rank = saved.withIndex().associate { (i, id) -> id to i }
        return shown.withIndex().sortedBy { (i, id) -> rank[id] ?: (saved.size + i) }.map { it.value }
    }

    /** The order to save: every shown system in [ids] order, then systems hidden right now as they were. */
    fun save(ids: List<String>, saved: List<String>): List<String> = ids + saved.filterNot { it in ids }
}
