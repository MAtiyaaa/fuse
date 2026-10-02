package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BackgroundStyle
import io.github.matiyaaa.fuse.model.CornerFamily
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.FocusStyle
import io.github.matiyaaa.fuse.model.GameArtStyle
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.model.ThemePalette
import io.github.matiyaaa.fuse.model.ThemeSpec
import io.github.matiyaaa.fuse.ui.designsystem.background.AmbientBackground
import io.github.matiyaaa.fuse.ui.designsystem.background.CrtOverlay
import io.github.matiyaaa.fuse.ui.designsystem.components.Badge
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.HintBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.effects.elevated
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.TileMetrics
import io.github.matiyaaa.fuse.ui.designsystem.theme.flourishOn
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.Hud
import io.github.matiyaaa.fuse.ui.shell.app.HudScrim
import io.github.matiyaaa.fuse.ui.shell.app.offers
import io.github.matiyaaa.fuse.ui.shell.app.sections
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.GameWideTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalGameArt
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.Stage
import io.github.matiyaaa.fuse.ui.shell.components.StageInfo
import io.github.matiyaaa.fuse.ui.shell.components.SystemTile
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.components.stage
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlinx.coroutines.delay

/**
 * The screen the stage's miniature is laid out on before it is scaled to fit: Fuse's main target,
 * so the miniature is Home exactly as it is drawn there, only smaller.
 */
private val VirtualWidth = 1280.dp
private val VirtualHeight = 720.dp

/** The share of the screen's height Home gives its stage (the title above the shelves). */
private const val HOME_STAGE_SHARE = 0.3f

/** How long the selection must rest on a card before the stage changes to its theme. */
private const val STAGE_REST_MS = 90L

/** Games and systems the miniature shows: enough for its shelves to run off the edge. */
private const val STAGE_ITEMS = 6

/**
 * What the stage's miniature of Home is made of: the player's own games and systems, top line and
 * settings, so a theme is seen the way it will look with their library in it.
 */
@Immutable
internal data class StageScene(
    val games: List<GameCard>,
    val systems: List<PlatformCard>,
    val status: SystemStatus,
    val tabs: List<Destination>,
    val sections: io.github.matiyaaa.fuse.ui.shell.app.Sections,
    val clock24h: Boolean,
    val showWifi: Boolean,
    val showBluetooth: Boolean,
    val showLogo: Boolean,
    /** The player's own Motion setting, which wins over a theme's (null follows the theme). */
    val motion: MotionProfile?,
    val gameArt: GameArtStyle,
)

@Composable
internal fun rememberStageScene(app: AppState): StageScene {
    val prefs by app.store.prefs.collectAsState()
    val feed by app.store.library.home.collectAsState()
    val platforms by app.store.library.platforms.collectAsState()
    val status by app.platform.status.collectAsState()
    val cartridge by app.store.cartridge.status.collectAsState()
    val games = remember(feed) {
        (feed.continuePlaying + feed.recentlyPlayed + feed.pinnedGames + feed.favorites + feed.recentlyAdded)
            .distinctBy { it.id }
            .take(STAGE_ITEMS)
    }
    val systems = remember(feed, platforms) { feed.systems.ifEmpty { platforms.filter { it.gameCount > 0 } }.take(STAGE_ITEMS + 2) }
    val sections = app.sections
    val tabs = (listOf(Destination.HOME) + prefs.destinations.filter { it != Destination.HOME })
        .filter { sections.showsTab(it, app.offers(it), cartridge) }
    return StageScene(
        games = games,
        systems = systems,
        status = status,
        tabs = tabs,
        sections = sections,
        clock24h = prefs.clock24h,
        showWifi = prefs.showWifi,
        showBluetooth = prefs.showBluetooth,
        showLogo = prefs.showLogo,
        motion = prefs.motion,
        gameArt = prefs.gameArt,
    )
}

/**
 * The stage: a large, live picture of Fuse in [spec]. It is the real thing, not a drawing of it:
 * Home's own top line, title, shelves of the player's games and hint line, composed in the theme on
 * a 1280 x 720 screen and scaled to fit, over the theme's own moving background (or, for themes lit
 * by game art, a room lit in the theme's accent, never one game's art). Each new theme arrives with
 * its focused tile lifting, so its focus style is seen in motion.
 *
 * While the gallery's selection runs across cards the stage waits for it to rest, then crossfades;
 * changes to the same theme (the studio's) show at once, in place. [flourish] counts the times a
 * theme was put to use: each one sends a single light across the stage. The miniature is a picture,
 * so it takes no touches and reads to a screen reader as one image.
 */
@Composable
internal fun ThemeStage(
    spec: ThemeSpec,
    scene: StageScene,
    modifier: Modifier = Modifier,
    flourish: Int = 0,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val quality = Fuse.quality
    val glyphs = Fuse.glyphs
    val contrast = Fuse.look.highContrastFocus
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    var shown by remember { mutableStateOf(spec) }
    LaunchedEffect(spec) {
        if (spec.id != shown.id) delay(STAGE_REST_MS)
        shown = spec
    }
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(flourish) {
        if (flourish > 0 && motion.flourishOn(quality)) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(Durations.SWEEP + Durations.BASE, easing = Easings.Sweep))
        }
    }
    Box(
        modifier
            .aspectRatio(VirtualWidth / VirtualHeight)
            .elevated(Elevation.raised, shape, fill = c.ink)
            .clearAndSetSemantics { contentDescription = "A preview of Fuse in ${shown.name}" }
            .drawWithCache {
                // One band of light, slanted like the tiles' sweep, crossing the stage once.
                val band = size.height * 0.9f
                val brush = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.35f to Color.White.copy(alpha = 0.06f),
                    0.5f to Color.White.copy(alpha = 0.2f),
                    0.65f to Color.White.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                    start = Offset(-band / 2, -band * 0.18f),
                    end = Offset(band / 2, band * 0.18f),
                )
                onDrawWithContent {
                    drawContent()
                    val s = sweep.value
                    if (s > 0f && s < 1f) {
                        val x = -band + (size.width + band * 2) * s
                        translate(left = x) { drawRect(brush, topLeft = Offset(-band, 0f), size = size.copy(width = band * 2)) }
                    }
                }
            },
    ) {
        AnimatedContent(
            targetState = shown,
            contentKey = { it.id },
            transitionSpec = {
                // The new theme fades in over the old, which stays until it is covered: no dip.
                fadeIn(motion.fade(Durations.BASE)) togetherWith fadeOut(snap(motion.ms(Durations.BASE)))
            },
            label = "stage",
        ) { s ->
            VirtualScreen(Modifier.fillMaxSize()) {
                FuseTheme(spec = s, motion = scene.motion, quality = quality, glyphs = glyphs, highContrastFocus = contrast) {
                    CompositionLocalProvider(
                        LocalTileMetrics provides TileMetrics.forHeight(VirtualHeight, VirtualWidth),
                        LocalGameArt provides scene.gameArt,
                    ) {
                        MiniHome(s, scene)
                    }
                }
            }
        }
        // A picture, not a second Fuse: touches and the pointer stop here.
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}

/** Lays [content] out on the virtual screen and scales it down (never up) to the space it is given. */
@Composable
private fun VirtualScreen(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clipToBounds()) {
        Box(
            Modifier.layout { measurable, constraints ->
                val vw = VirtualWidth.roundToPx()
                val vh = VirtualHeight.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(vw, vh))
                val w = if (constraints.hasBoundedWidth) constraints.maxWidth else vw
                val h = if (constraints.hasBoundedHeight) constraints.maxHeight else vh
                val s = minOf(w.toFloat() / vw, h.toFloat() / vh, 1f)
                layout(w, h) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            },
            content = content,
        )
    }
}

/**
 * Home in the current theme, the way Flow lays it out: the top line, the stage with the first game's
 * title, a shelf of wide game tiles with the first one focused, the systems below, and the hint line.
 * With no games yet it shows the theme's own name over generated tiles.
 */
@Composable
private fun MiniHome(spec: ThemeSpec, scene: StageScene) {
    val c = Fuse.colors
    val metrics = LocalTileMetrics.current
    val game = scene.games.firstOrNull()
    val heroRoom = spec.background == BackgroundStyle.HERO
    // Themes lit by game art preview their own room: the theme's accent as the light, never your
    // games' art (which would make every art theme look like the game you happened to be on).
    val art = false
    Box(Modifier.fillMaxSize().background(c.ink)) {
        AmbientBackground(
            if (heroRoom) BackgroundStyle.SOLID else spec.background,
            c.accent,
            Modifier.fillMaxSize(),
            ambient = spec.ambient,
            fps = 24,
        )
        HudScrim(art = art)
        Hud(
            destinations = scene.tabs,
            sections = scene.sections,
            active = Destination.HOME,
            tabsFocused = false,
            status = scene.status,
            clock24h = scene.clock24h,
            showWifi = scene.showWifi,
            showBluetooth = scene.showBluetooth,
            onSelect = {},
            onStatusClick = {},
        )
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight))
            Box(
                Modifier.fillMaxWidth().height(VirtualHeight * HOME_STAGE_SHARE).padding(horizontal = Space.gutter),
                contentAlignment = Alignment.BottomStart,
            ) {
                Stage(
                    game?.stage() ?: StageInfo(key = spec.id, title = spec.name, meta = listOfNotNull(spec.tagline.takeIf { it.isNotBlank() }), accent = spec.palette.accent),
                    showLogo = scene.showLogo,
                )
            }
            Spacer(Modifier.height(Space.m))
            // A new focus style or corner family lifts the focused tile again, so it is seen at work.
            key(spec.focus, spec.geometry) {
                val wide = metrics.icon * 1.25f
                MiniShelf("Continue playing", count = scene.games.size.takeIf { it > 1 }) {
                    if (scene.games.isEmpty()) {
                        repeat(4) { i -> PlaceholderTile(spec, i, selected = i == 0, width = wide * 1.78f, height = wide) }
                    } else {
                        scene.games.forEachIndexed { i, g ->
                            GameWideTile(g, selected = i == 0, height = wide, caption = g.lastPlayedAt?.let { "Played ${agoText(it)}" })
                        }
                    }
                }
                when {
                    scene.systems.isNotEmpty() -> MiniShelf("Systems", count = null) {
                        scene.systems.forEach { SystemTile(it, selected = false, size = metrics.icon) }
                    }
                    scene.games.size > 1 -> MiniShelf("Recently added", count = null) {
                        scene.games.reversed().forEach { GameIconTile(it, selected = false, size = metrics.icon) }
                    }
                    else -> MiniShelf("Pinned", count = null) {
                        repeat(8) { i -> PlaceholderTile(spec, i + 4, selected = false, width = metrics.icon, height = metrics.icon) }
                    }
                }
            }
        }
        // Content fades out under the hint line, as it does on every page.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(Size.hintHeight + Space.xxl)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to c.ink.copy(alpha = 0.78f), 1f to c.ink.copy(alpha = 0.94f))),
        )
        HintBar(MINI_HINTS, Modifier.align(Alignment.BottomEnd).padding(horizontal = Space.gutter, vertical = Space.s))
        if (spec.crt.enabled && Fuse.quality.crtShader) CrtOverlay(spec.crt)
    }
}

private val MINI_HINTS = listOf(Hint(HintButton.CONFIRM, "Play"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.SEARCH, "Search"))

/** A shelf of the miniature: its title, then a row of tiles running off the right edge. */
@Composable
private fun MiniShelf(title: String, count: Int?, content: @Composable RowScope.() -> Unit) {
    val metrics = LocalTileMetrics.current
    Column(Modifier.fillMaxWidth().padding(top = Space.s)) {
        Row(Modifier.heightIn(min = Size.badge).padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(title, count = count?.toString())
        }
        Spacer(Modifier.height(Space.s))
        Row(
            Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .padding(start = Space.gutter, bottom = Size.sparkClearance + Space.xs),
            horizontalArrangement = Arrangement.spacedBy(metrics.gap),
            content = content,
        )
    }
}

/** A tile for a library with no games yet: generated art in the theme's colour, honest about being a stand-in. */
@Composable
private fun PlaceholderTile(spec: ThemeSpec, index: Int, selected: Boolean, width: Dp, height: Dp) {
    val accent = Fuse.colors.accent
    Tile(selected = selected, modifier = Modifier.size(width, height), glow = accent) {
        GeneratedArt("${spec.name} $index", accent, Modifier.fillMaxSize(), slot = if (width > height) ArtSlot.WIDE else ArtSlot.ICON, showText = false)
    }
}

// ------------------------------------------------------------------------------------- facts

/** One trait of a theme, as a small labelled chip under the stage. */
@Immutable
internal data class Trait(val icon: ImageVector, val label: String)

/**
 * A theme's traits in the order they matter to how it looks: its background, corners and focus,
 * then glass and CRT when it has them, then its motion and sounds.
 */
internal fun traitsOf(spec: ThemeSpec): List<Trait> = buildList {
    add(Trait(spec.background.icon(), spec.background.label()))
    add(Trait(FuseIcons.Corners, "${spec.geometry.label()} corners"))
    add(Trait(spec.focus.icon(), "${spec.focus.label()} focus"))
    if (spec.glass.enabled) add(Trait(FuseIcons.Layers, "Glass panels"))
    if (spec.crt.enabled) add(Trait(FuseIcons.Tv, "CRT"))
    add(Trait(FuseIcons.Activity, "${spec.motion.label()} motion"))
    add(Trait(spec.sound.icon(), if (spec.sound == SoundProfile.OFF) "No sounds" else "${spec.sound.label()} sounds"))
}

/**
 * What the block under the stage says: a [title] with its marks ([inUse], [yours]), the theme's
 * [palette] (and [secondary] light), a quiet [line], and its [traits]. [key] tells one subject
 * from the next, so the block crossfades between themes but updates in place as the studio's
 * draft changes.
 */
@Immutable
internal data class Facts(
    val key: Any,
    val title: String,
    val line: String?,
    val palette: ThemePalette?,
    val secondary: Long?,
    val traits: List<Trait>,
    val inUse: Boolean = false,
    val yours: Boolean = false,
)

/** [Facts] for a theme. */
internal fun factsOf(spec: ThemeSpec, key: Any = spec.id, title: String = spec.name, line: String? = spec.tagline, inUse: Boolean = false, yours: Boolean = false) =
    Facts(key, title, line, spec.palette, spec.ambient.secondary, traitsOf(spec), inUse, yours)

/**
 * What sits under the stage: the title with its marks and the theme's palette, a quiet line under
 * it, then the traits as chips (as many as fit in two lines, most telling first). A new subject
 * fades in as the old one fades out, quicker, with no movement. [compact] drops the line, keeps the
 * chips to one line and tightens the title, for short screens.
 */
@Composable
internal fun ThemeFacts(facts: Facts, modifier: Modifier = Modifier, compact: Boolean = false) {
    val motion = Fuse.motion
    AnimatedContent(
        targetState = facts,
        modifier = modifier,
        contentKey = { it.key },
        transitionSpec = { (fadeIn(motion.fade(Durations.FAST)) togetherWith fadeOut(motion.exit(Durations.INSTANT))).using(SizeTransform(clip = false)) },
        contentAlignment = Alignment.TopStart,
        label = "themeFacts",
    ) { f -> FactsBody(f, compact) }
}

@Composable
private fun FactsBody(f: Facts, compact: Boolean) {
    val c = Fuse.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = Size.badge), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                FText(f.title, if (compact) Fuse.type.titleSmall else Fuse.type.title, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (f.inUse) {
                    Spacer(Modifier.width(Space.s))
                    Badge("In use", icon = FuseIcons.Check)
                }
                if (f.yours) {
                    Spacer(Modifier.width(Space.s))
                    Badge("Yours", color = c.textMuted, filled = false)
                }
            }
            if (f.palette != null) {
                Spacer(Modifier.width(Space.m))
                PaletteSwatches(f.palette, f.secondary)
            }
        }
        if (!compact && !f.line.isNullOrBlank()) {
            Spacer(Modifier.height(Space.xxs))
            FText(f.line, Fuse.type.body, color = c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.height(if (compact) Space.s else Space.m))
        FittingRow(gap = Space.s, lines = if (compact) 1 else 2, modifier = Modifier.fillMaxWidth()) {
            for (t in f.traits) TraitChip(t)
        }
    }
}

/** One trait as a small quiet chip: its icon and its name, in caption type so a full set fits a line. */
@Composable
private fun TraitChip(trait: Trait) {
    val c = Fuse.colors
    Row(
        Modifier
            .height(Size.chipCompact)
            .clip(PillShape)
            .background(c.text.copy(alpha = if (c.isDark) 0.07f else 0.06f))
            .padding(horizontal = Space.m - Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(trait.icon, size = Size.iconXS, tint = c.textMuted)
        Spacer(Modifier.width(Space.xs + Space.xxs))
        FText(trait.label, Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/**
 * A row that shows as many of its children as fit in [lines] lines, in order, and leaves the rest
 * out rather than squeezing them, so the block under the stage never grows past its room. Lines are
 * [gap] apart, and so are the children on a line.
 */
@Composable
private fun FittingRow(gap: Dp, lines: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val space = gap.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val xs = ArrayList<Int>(placeables.size)
        val rows = ArrayList<Int>(placeables.size)
        var line = 0
        var end = 0
        for (p in placeables) {
            var x = if (end == 0 && (xs.isEmpty() || rows.last() != line)) 0 else end + space
            if (x + p.width > constraints.maxWidth) {
                if (line + 1 >= lines || xs.isEmpty()) break
                line++
                x = 0
            }
            xs += x
            rows += line
            end = x + p.width
        }
        val lineHeight = placeables.take(xs.size).maxOfOrNull { it.height } ?: 0
        val used = if (xs.isEmpty()) 0 else rows.last() + 1
        val height = if (used == 0) 0 else lineHeight * used + space * (used - 1)
        layout(if (constraints.hasBoundedWidth) constraints.maxWidth else end, height) {
            xs.forEachIndexed { i, x -> placeables[i].place(x, rows[i] * (lineHeight + space) + (lineHeight - placeables[i].height) / 2) }
        }
    }
}

/**
 * A theme's palette as overlapping discs: the room, its raised panels, its text, its accent and
 * its second light. Each disc is cut out of the one before by a ring of the page's own room colour,
 * and edged with a hairline so a room as dark as this page's still shows.
 */
@Composable
internal fun PaletteSwatches(palette: ThemePalette, secondary: Long?, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val colors = remember(palette, secondary) {
        listOfNotNull(palette.background, palette.surfaceRaised, palette.textPrimary, palette.accent, secondary).map { Color(it) }
    }
    val ring = c.ink
    val edge = c.hairlineStrong
    val disc = Size.badge
    val step = disc - Space.s
    Spacer(
        modifier
            .semantics { contentDescription = "Palette" }
            .size(width = disc + step * (colors.size - 1), height = disc)
            .drawBehind {
                val r = size.height / 2
                val cut = 2.dp.toPx()
                val hair = Stroke(Size.stroke.toPx())
                colors.forEachIndexed { i, col ->
                    val center = Offset(r + step.toPx() * i, r)
                    drawCircle(ring, r, center)
                    drawCircle(col, r - cut, center)
                    drawCircle(edge, r - cut, center, style = hair)
                }
            },
    )
}

// ------------------------------------------------------------------------------------- names

/** What a background is called in the gallery and the studio. */
internal fun BackgroundStyle.label(): String = when (this) {
    BackgroundStyle.HERO -> "Game art"
    BackgroundStyle.SOLID -> "Plain"
    BackgroundStyle.WAVE -> "Wave"
    BackgroundStyle.AURORA -> "Aurora"
    BackgroundStyle.ORBITAL -> "Orbits"
    BackgroundStyle.GRID -> "Grid"
    BackgroundStyle.STRIPES -> "Stripes"
    BackgroundStyle.STARS -> "Stars"
    BackgroundStyle.PETALS -> "Petals"
    BackgroundStyle.HORIZON -> "Horizon"
    BackgroundStyle.FIREFLIES -> "Fireflies"
    BackgroundStyle.CAUSTICS -> "Water"
    BackgroundStyle.LCD -> "Dot matrix"
    BackgroundStyle.MESH -> "Pearl"
    BackgroundStyle.CONTOURS -> "Contours"
    BackgroundStyle.DUNES -> "Dunes"
}

internal fun BackgroundStyle.icon(): ImageVector = when (this) {
    BackgroundStyle.HERO -> FuseIcons.Image
    BackgroundStyle.SOLID -> FuseIcons.Square
    BackgroundStyle.WAVE -> FuseIcons.Waves
    BackgroundStyle.AURORA -> FuseIcons.Sparkles
    BackgroundStyle.ORBITAL -> FuseIcons.Orbit
    BackgroundStyle.GRID -> FuseIcons.Grid3
    BackgroundStyle.STRIPES -> FuseIcons.Rows
    BackgroundStyle.STARS -> FuseIcons.Star
    BackgroundStyle.PETALS -> FuseIcons.Flower
    BackgroundStyle.HORIZON -> FuseIcons.Sunset
    BackgroundStyle.FIREFLIES -> FuseIcons.Trees
    BackgroundStyle.CAUSTICS -> FuseIcons.Droplet
    BackgroundStyle.LCD -> FuseIcons.Grid2
    BackgroundStyle.MESH -> FuseIcons.Blend
    BackgroundStyle.CONTOURS -> FuseIcons.Mountain
    BackgroundStyle.DUNES -> FuseIcons.SunDim
}

internal fun CornerFamily.label(): String = when (this) {
    CornerFamily.SOFT -> "Soft"
    CornerFamily.ROUND -> "Round"
    CornerFamily.SHARP -> "Sharp"
    CornerFamily.PILL -> "Pill"
}

internal fun FocusStyle.label(): String = when (this) {
    FocusStyle.GLOW -> "Glow"
    FocusStyle.RING -> "Ring"
    FocusStyle.BAR -> "Bar"
}

internal fun FocusStyle.icon(): ImageVector = when (this) {
    FocusStyle.GLOW -> FuseIcons.Sparkle
    FocusStyle.RING -> FuseIcons.CircleDot
    FocusStyle.BAR -> FuseIcons.Minus
}

/** The same names Settings, Accessibility, Motion uses. */
internal fun MotionProfile.label(): String = when (this) {
    MotionProfile.REDUCED -> "Reduced"
    MotionProfile.MINIMAL -> "Minimal"
    MotionProfile.STANDARD -> "Standard"
    MotionProfile.ENHANCED -> "Enhanced"
}

/** The same names Settings, Screen and sound uses. */
internal fun SoundProfile.label(): String = when (this) {
    SoundProfile.OFF -> "Off"
    SoundProfile.SOFT -> "Soft"
    SoundProfile.CLICK -> "Crisp"
    SoundProfile.CHIME -> "Chime"
}

internal fun SoundProfile.icon(): ImageVector = if (this == SoundProfile.OFF) FuseIcons.VolumeOff else FuseIcons.Volume
