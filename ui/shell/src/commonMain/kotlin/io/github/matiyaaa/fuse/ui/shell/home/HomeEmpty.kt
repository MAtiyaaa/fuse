package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.Skeleton
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonText
import io.github.matiyaaa.fuse.ui.designsystem.effects.Reveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.lightEdge
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.shimmer
import io.github.matiyaaa.fuse.ui.designsystem.effects.skeleton
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.ambientOn
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which of Home's empty states is showing. */
private enum class EmptyKind { FIRST_RUN, NOTHING_FOUND, SCANNING }

/** From this width the words and the illustration sit side by side; narrower, the art goes on top. */
private val SIDE_BY_SIDE_FROM = 720.dp

/** Shorter than this (a handheld), the title is set smaller and the steps keep to one line each. */
private val SHORT_BELOW = 440.dp

/**
 * Home before any games are found. It welcomes you, says in three short steps how Fuse gets your
 * games onto Home, and offers the two ways forward, beside an illustration of the shelf waiting to
 * be filled: empty slots around a lit tile with the spark under it. When a folder was added but
 * held nothing Fuse knows, it says where it looked and what to check. While a scan runs it shows
 * the count rising, and the slots shimmer as the games come in.
 */
@Composable
fun HomeEmpty(app: AppState) {
    val scan by app.store.sources.scan.collectAsState()
    val sources by app.store.sources.sources.collectAsState()
    val scanning = scan.phase == ScanPhase.DISCOVERING || scan.phase == ScanPhase.SCANNING || scan.phase == ScanPhase.SAVING
    val kind = when {
        scanning -> EmptyKind.SCANNING
        sources.isEmpty() -> EmptyKind.FIRST_RUN
        else -> EmptyKind.NOTHING_FOUND
    }
    val sel = remember { LinearSelection() }
    val focused = app.focusZone == FocusZone.CONTENT

    fun addFolder() {
        app.scope.launch {
            val path = app.platform.storage.pickFolder("Choose your games folder") ?: return@launch
            app.store.sources.add(path)
            app.store.sources.rescan()
        }
    }

    val actions = listOf(
        Triple("Add a games folder", FuseIcons.FolderSearch) { addFolder() },
        Triple("Run setup", FuseIcons.Sparkles) { app.go(Route.Onboarding) },
    )

    LaunchedEffect(scanning) {
        app.hero = null
        // While a scan runs there is nothing to choose, so the hint line stays quiet.
        app.hints = if (scanning) emptyList() else listOf(Hint(HintButton.CONFIRM, "Choose"))
    }

    InputLayer(enabled = focused && !app.overlayOpen && !scanning) { e ->
        when (e.action) {
            NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, actions.size)
            NavAction.SELECT -> { actions[sel.index].third(); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    // Each state is a new step, so its words rise in again.
    val reveal = rememberReveal(kind)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(top = Size.hudHeight, bottom = Size.hintHeight)
            .padding(horizontal = Space.gutter),
    ) {
        val short = maxHeight < SHORT_BELOW
        val artHeight = maxHeight * 0.3f
        val words: @Composable (Modifier) -> Unit = { m ->
            Column(m.widthIn(max = WORDS_MAX)) {
                EmptyWords(kind, short, reveal, sources.map { it.path }, scan.gamesFound, scan.currentPath)
                if (kind != EmptyKind.SCANNING) {
                    Spacer(Modifier.height(if (short) Space.l else Space.xl))
                    // On a phone held upright the second button wraps under the first.
                    FlowRow(
                        Modifier.reveal(reveal, 4),
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        actions.forEachIndexed { i, (label, icon, run) ->
                            FuseButton(
                                label = label,
                                icon = icon,
                                selected = i == sel.index && focused,
                                kind = if (i == 0) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                                onClick = { sel.index = i; run() },
                            )
                        }
                    }
                }
            }
        }
        if (maxWidth >= SIDE_BY_SIDE_FROM) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1.15f)) { words(Modifier) }
                Spacer(Modifier.width(Space.xxl))
                ShelfArt(kind, reveal, Modifier.weight(0.85f).fillMaxHeight(0.86f))
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                ShelfArt(kind, reveal, Modifier.fillMaxWidth().height(artHeight))
                Spacer(Modifier.height(Space.xl))
                words(Modifier)
            }
        }
    }
}

private val WORDS_MAX = 560.dp
private val BODY_MAX = 460.dp

@Composable
private fun EmptyWords(kind: EmptyKind, short: Boolean, reveal: Reveal, folders: List<String>, found: Int, path: String?) {
    val c = Fuse.colors
    Eyebrow(
        when (kind) {
            EmptyKind.FIRST_RUN -> "Welcome to Fuse"
            EmptyKind.NOTHING_FOUND -> "Your library"
            EmptyKind.SCANNING -> "Scanning"
        },
        Modifier.reveal(reveal, 0),
    )
    Spacer(Modifier.height(Space.s))
    FText(
        when (kind) {
            EmptyKind.FIRST_RUN -> "Let's find your games"
            EmptyKind.NOTHING_FOUND -> "No games found yet"
            EmptyKind.SCANNING -> "Looking for your games"
        },
        if (short) Fuse.type.display else Fuse.type.hero,
        maxLines = 2,
        modifier = Modifier.reveal(reveal, 1),
    )
    Spacer(Modifier.height(Space.m))
    FText(
        when (kind) {
            EmptyKind.FIRST_RUN -> "Fuse plays the games you already have. Show it where they live and it does the rest."
            EmptyKind.NOTHING_FOUND -> "Fuse looked in ${folders.size} ${if (folders.size == 1) "folder" else "folders"} and found nothing it recognises. Check that each system has its own folder, like ROMs/snes or roms/ps2."
            EmptyKind.SCANNING -> "Fuse is reading your folders. Your games appear here as soon as it is done."
        },
        Fuse.type.body,
        color = c.textMuted,
        maxLines = 3,
        // A reading measure, so the sentence breaks into even lines.
        modifier = Modifier.widthIn(max = BODY_MAX).reveal(reveal, 2),
    )
    when (kind) {
        EmptyKind.FIRST_RUN -> {
            Spacer(Modifier.height(if (short) Space.l else Space.xl))
            Steps(short, Modifier.reveal(reveal, 3))
        }
        EmptyKind.NOTHING_FOUND -> {
            Spacer(Modifier.height(Space.l))
            Column(Modifier.reveal(reveal, 3), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                for (folder in folders.take(if (short) 1 else 3)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FuseIcon(FuseIcons.Folder, size = Size.iconS, tint = c.textFaint)
                        Spacer(Modifier.width(Space.s))
                        // The end of a path says the most, so a long one gives way in the middle.
                        FText(folder, Fuse.type.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            }
        }
        EmptyKind.SCANNING -> {
            Spacer(Modifier.height(Space.xl))
            ScanCount(found, path, Modifier.reveal(reveal, 3))
        }
    }
}

/** The small label over the title, made like the stage's: a dot in the accent and an overline. */
@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Size.dot).background(Fuse.colors.accent, CircleShape))
        Spacer(Modifier.width(Space.s))
        FText(text.uppercase(), Fuse.type.overline, color = Fuse.colors.textMuted, maxLines = 1)
    }
}

/** How Fuse gets games onto Home, in three steps; the first, where you are, in the accent. */
@Composable
private fun Steps(short: Boolean, modifier: Modifier = Modifier) {
    val steps = listOf(
        "Add your games folder" to "One folder per system, like roms/snes, or a RomM library",
        "Fuse finds the games and their art" to "It only reads: nothing is moved or renamed",
        "Pick one and play" to "Home fills with your games, systems and playtime",
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (short) Space.s else Space.m)) {
        steps.forEachIndexed { i, (title, detail) -> StepRow(i + 1, title, detail.takeUnless { short }, now = i == 0) }
    }
}

@Composable
private fun StepRow(number: Int, title: String, detail: String?, now: Boolean) {
    val c = Fuse.colors
    Row(verticalAlignment = if (detail == null) Alignment.CenterVertically else Alignment.Top) {
        Box(
            Modifier.size(Size.badge).clip(CircleShape).background(if (now) c.accent else c.text.copy(alpha = if (c.isDark) 0.08f else 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            FText("$number", Fuse.type.numericSmall, color = if (now) c.onAccent else c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.width(Space.m))
        Column {
            FText(title, Fuse.type.bodyStrong, color = if (now) c.text else c.textMuted, maxLines = 1)
            if (detail != null) FText(detail, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
    }
}

/** The games found so far, counting up, over a running bar and the folder being read. */
@Composable
private fun ScanCount(found: Int, path: String?, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val shown by animateIntAsState(found, Fuse.motion.value(), label = "found")
    Column(modifier.widthIn(max = SCAN_BAR_MAX)) {
        Row {
            FText("$shown", Fuse.type.display.tabular(), maxLines = 1, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(Space.s))
            FText(if (found == 1) "game so far" else "games so far", Fuse.type.body, color = c.textMuted, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(Space.m))
        ProgressBar(null, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.s))
        FText(path ?: "Scanning", Fuse.type.caption, color = c.textFaint, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

private val SCAN_BAR_MAX = 420.dp

/**
 * The welcome's illustration: a shelf waiting for games. Two empty slots lean in either side of a
 * lit tile, which floats gently (when ambient motion is on) with the spark bar under it, the same
 * focus every tile in Fuse has. While a scan runs the slots shimmer like loading tiles.
 */
@Composable
private fun ShelfArt(kind: EmptyKind, reveal: Reveal, modifier: Modifier) {
    val c = Fuse.colors
    val glow = c.accent.copy(alpha = if (c.isDark) 0.16f else 0.12f)
    val ambient = Fuse.motion.ambientOn(Fuse.quality)
    val float = if (ambient) {
        rememberInfiniteTransition(label = "shelf art").animateFloat(
            0f, 1f, infiniteRepeatable(tween(FLOAT_MS, easing = Easings.Fade), RepeatMode.Reverse), label = "float",
        )
    } else null
    BoxWithConstraints(
        modifier.drawWithCache {
            val brush = Brush.radialGradient(
                0f to glow,
                0.5f to glow.copy(alpha = glow.alpha * 0.3f),
                1f to Color.Transparent,
                center = Offset(size.width / 2, size.height / 2),
                radius = maxOf(size.width, size.height) * 0.55f,
            )
            onDrawBehind { drawRect(brush) }
        },
        contentAlignment = Alignment.Center,
    ) {
        val tile = minOf(maxWidth / 2.8f, maxHeight * 0.52f)
        val side = tile * 0.74f
        val fraction = Fuse.geometry.tileCornerFraction
        val shape = remember(fraction) { SquircleShape.fraction(fraction) }
        // The slots tuck just behind the lit tile, so the three read as one shelf.
        val reach = tile * 0.5f + side * 0.42f
        val bob = Space.xs
        Slot(kind, shape, Modifier.offset(x = -reach, y = side * 0.1f).size(side).graphicsLayer { rotationZ = -LEAN }.reveal(reveal, 2))
        Slot(kind, shape, Modifier.offset(x = reach, y = side * 0.1f).size(side).graphicsLayer { rotationZ = LEAN }.reveal(reveal, 3))
        Box(Modifier.reveal(reveal, 1), contentAlignment = Alignment.Center) {
            LitTile(
                kind, shape, tile,
                Modifier.graphicsLayer { translationY = -(float?.value ?: 0f) * bob.toPx() },
            )
            // The spark bar, where a focused tile has it.
            Box(
                Modifier
                    .offset(y = tile / 2 + Size.sparkGap + Size.sparkHeight / 2)
                    .size(Size.sparkWidth, Size.sparkHeight)
                    .background(c.accent, CircleShape),
            )
        }
    }
}

/** How far the side slots lean, in degrees. */
private const val LEAN = 7f

/** One slow breath of the lit tile's float. */
private const val FLOAT_MS = 2600

/** An empty slot on the shelf: a dashed well, or a shimmering placeholder while games come in. */
@Composable
private fun Slot(kind: EmptyKind, shape: Shape, modifier: Modifier) {
    if (kind == EmptyKind.SCANNING) {
        Box(modifier.skeleton(shape).lightEdge(shape, Elevation.tile.edgeAlpha(Fuse.colors.isDark)))
    } else {
        Box(modifier.dropWell({ 1f }, shape))
    }
}

/** The lit tile in the middle: generated art in the accent with what Home is doing laid on it. */
@Composable
private fun LitTile(kind: EmptyKind, shape: Shape, size: Dp, modifier: Modifier) {
    val c = Fuse.colors
    // The lit tile's shadow is tinted with its colour, as a focused tile's is.
    val shadow = lerp(c.accent, c.shadow.copy(alpha = 1f), 0.35f)
    val icon: ImageVector = when (kind) {
        EmptyKind.FIRST_RUN -> FuseIcons.FolderSearch
        EmptyKind.NOTHING_FOUND -> FuseIcons.SearchX
        EmptyKind.SCANNING -> FuseIcons.ScanSearch
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                this.shape = shape
                clip = true
                shadowElevation = Elevation.tileFocused.shadow.toPx()
                spotShadowColor = shadow
                ambientShadowColor = shadow.copy(alpha = 0.3f)
            }
            .lightEdge(shape, Elevation.tileFocused.edgeAlpha(true))
            .shimmer(shape, active = kind == EmptyKind.SCANNING),
        contentAlignment = Alignment.Center,
    ) {
        GeneratedArt("Fuse", c.accent, slot = ArtSlot.ICON, showText = false)
        FuseIcon(icon, size = size * 0.3f, tint = c.onArt)
    }
}

/**
 * Whether Home's first feed is still on its way. The library loads in a moment after Fuse starts;
 * until then Home shows its own outline (see [HomeSkeleton]) rather than saying there are no games.
 * Settles for good once the feed arrives or a short grace passes, so later visits never wait.
 */
@Composable
internal fun rememberHomeLoading(app: AppState, feed: HomeFeed, empty: Boolean): Boolean {
    val load = rememberRouteState(app.navigator, "home.load") { HomeLoad(app.store.library.home.value) }
    if (load.settled) return false
    if (!empty || feed !== load.first) {
        load.settled = true
        return false
    }
    val left = LOAD_GRACE_MS - load.since.elapsedNow().inWholeMilliseconds
    var waiting by remember { mutableStateOf(left > 0) }
    LaunchedEffect(Unit) {
        if (left > 0) delay(left)
        waiting = false
    }
    if (!waiting) load.settled = true
    return waiting
}

/** What [rememberHomeLoading] remembers: the feed Home first saw, and when. */
private class HomeLoad(val first: HomeFeed) {
    val since = TimeSource.Monotonic.markNow()
    var settled = false
}

/** How long Home waits for its first feed before saying the library is empty. */
private const val LOAD_GRACE_MS = 900L

/**
 * Home's outline while the library loads: the stage's lines and two shelves of tiles (or the
 * board's channels), as calm shimmering placeholders in the places the real things will take.
 */
@Composable
internal fun HomeSkeleton(app: AppState, channels: Boolean) {
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = emptyList()
    }
    val metrics = LocalTileMetrics.current
    val fraction = Fuse.geometry.tileCornerFraction
    val tileShape = remember(fraction) { SquircleShape.fraction(fraction) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < SKELETON_COMPACT_BELOW
        val stage = (maxHeight * 0.3f).coerceIn(150.dp, 280.dp)
        // The board's own measures: two columns on a phone held upright, four otherwise.
        val columns = if (maxWidth < SKELETON_NARROW_BELOW) 2 else 4
        val unit = ((maxWidth - Space.gutter * 2 - Space.l * (columns - 1)) / columns).coerceAtMost(maxHeight * 0.26f)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight))
            Column(Modifier.fillMaxWidth().height(stage).padding(horizontal = Space.gutter), verticalArrangement = Arrangement.Bottom) {
                SkeletonText(Modifier.fillMaxWidth(0.12f), lines = 1, style = Fuse.type.overline)
                Spacer(Modifier.height(Space.m))
                SkeletonText(Modifier.fillMaxWidth(0.36f), lines = 1, style = if (compact) Fuse.type.display else Fuse.type.hero)
                Spacer(Modifier.height(Space.m))
                SkeletonText(Modifier.fillMaxWidth(0.24f), lines = 1, style = Fuse.type.body)
            }
            Spacer(Modifier.height(Space.xl))
            val row = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), enabled = false).padding(horizontal = Space.gutter)
            if (channels) {
                val channel = SquircleShape.fraction(fraction * 0.6f)
                // A wide channel and two small ones, as a fresh board starts.
                val rows = if (columns == 2) listOf(listOf(2), listOf(1, 1)) else listOf(listOf(2, 1, 1))
                Column(Modifier.padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    for (spans in rows) {
                        Row(row, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                            spans.forEach { span ->
                                Column {
                                    Skeleton(Modifier.size(unit * span + Space.l * (span - 1), unit * 0.78f), shape = channel)
                                    Spacer(Modifier.height(Size.sparkClearance))
                                    SkeletonText(Modifier.width(unit * 0.5f), lines = 1, style = Fuse.type.label)
                                }
                            }
                        }
                    }
                }
            } else {
                ShelfSkeleton(row, metrics.icon * 1.25f * 1.78f, metrics.icon * 1.25f, metrics.gap, SquircleShape.fraction(fraction * 0.7f))
                ShelfSkeleton(row, metrics.icon, metrics.icon, metrics.gap, tileShape)
            }
        }
    }
}

private val SKELETON_COMPACT_BELOW = 560.dp
private val SKELETON_NARROW_BELOW = 600.dp

@Composable
private fun ShelfSkeleton(row: Modifier, width: Dp, height: Dp, gap: Dp, shape: Shape) {
    Column(Modifier.fillMaxWidth().padding(top = Space.s)) {
        Box(Modifier.height(Size.badge).padding(horizontal = Space.gutter), contentAlignment = Alignment.CenterStart) {
            SkeletonText(Modifier.width(width * 0.4f), lines = 1, style = Fuse.type.overline)
        }
        Spacer(Modifier.height(Space.s))
        Row(row, horizontalArrangement = Arrangement.spacedBy(gap)) {
            repeat(SKELETON_TILES) { Skeleton(Modifier.size(width, height), shape = shape) }
        }
        Spacer(Modifier.height(Size.sparkClearance))
    }
}

/** Enough placeholder tiles to fill the widest screen's shelf. */
private const val SKELETON_TILES = 10
