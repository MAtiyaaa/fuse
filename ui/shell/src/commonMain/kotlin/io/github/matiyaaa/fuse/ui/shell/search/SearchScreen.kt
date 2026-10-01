package io.github.matiyaaa.fuse.ui.shell.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import io.github.matiyaaa.fuse.model.CollectionKind
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.EditableText
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.KeyboardField
import io.github.matiyaaa.fuse.ui.designsystem.components.KeyboardState
import io.github.matiyaaa.fuse.ui.designsystem.components.OnScreenKeyboard
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.KeyboardTarget
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.openApp
import io.github.matiyaaa.fuse.ui.shell.app.pasteInto
import io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.store.AppCard
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.SearchResults
import io.github.matiyaaa.fuse.ui.shell.systems.SystemMark
import io.github.matiyaaa.fuse.ui.shell.systems.gamesText
import kotlin.math.abs
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The kinds of result, in the order their groups are listed. */
private enum class HitKind(val label: String, val icon: ImageVector) {
    GAME("Games", FuseIcons.Gamepad),
    SYSTEM("Systems", FuseIcons.Chip),
    APP("Apps", FuseIcons.AppWindow),
    COLLECTION("Collections", FuseIcons.Bookmark),
}

private sealed interface Hit {
    val key: String
    val title: String
    val detail: String
    val kind: HitKind

    data class Game(val card: GameCard) : Hit {
        override val key = "g${card.id.value}"
        override val title = card.title
        override val kind = HitKind.GAME
        override val detail = listOfNotNull(card.platformShort, card.lastPlayedAt?.let { "Played ${agoText(it)}" } ?: card.year?.toString())
            .joinToString("  ·  ")
    }

    data class System(val card: PlatformCard) : Hit {
        override val key = "p${card.platform.id}"
        override val title = card.platform.name
        override val kind = HitKind.SYSTEM
        override val detail = listOfNotNull(card.platform.shortName, gamesText(card.gameCount), card.emulatorName?.takeIf { card.emulatorInstalled })
            .joinToString("  ·  ")
    }

    data class App(val card: AppCard) : Hit {
        override val key = "a${card.entry.id}"
        override val title = card.entry.displayTitle
        override val kind = HitKind.APP
        override val detail = if (card.entry.isGame) "Game app" else "App"
    }

    data class Collection(val c: io.github.matiyaaa.fuse.model.GameCollection) : Hit {
        override val key = "c${c.id.value}"
        override val title = c.name
        override val kind = HitKind.COLLECTION
        override val detail = listOfNotNull(if (c.kind == CollectionKind.SERIES) "Series" else null, gamesText(c.gameCount)).joinToString("  ·  ")
    }
}

/**
 * Global search: games, systems, apps and collections, entirely on the device (nothing goes online).
 * Type with the on-screen keyboard or any hardware keyboard; results update as you type, grouped by
 * kind with the part of each name that matches picked out. Right (or Menu) moves into the results,
 * where one highlight glides from row to row; Left goes back to the keys.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun SearchScreen(app: AppState) {
    val field = remember { EditableText() }
    val query = field.text
    var inResults by remember { mutableStateOf(false) }
    val keyboard = remember { KeyboardState() }
    // Typing brings the focus back to the keyboard.
    LaunchedEffect(query) { inResults = false }
    val sel = remember { LinearSelection() }
    val flow = remember {
        androidx.compose.runtime.snapshotFlow { field.text }
            .debounce(90)
            .flatMapLatest { q -> if (q.isBlank()) flowOf(SearchResults()) else app.store.library.search(q.trim()) }
    }
    val results by flow.collectAsState(initial = SearchResults())
    val hits = remember(results) {
        results.games.map { Hit.Game(it) } + results.platforms.map { Hit.System(it) } +
            results.apps.map { Hit.App(it) } + results.collections.filter { app.store.prefs.value.collectionsEnabled }.map { Hit.Collection(it) }
    }
    sel.clamp(hits.size)
    // The results belong to what was typed a moment ago; until they arrive the old ones stay.
    val settledQuery = results.query

    DisposableEffect(Unit) {
        app.keyboardTarget = KeyboardTarget(field) { if (hits.isNotEmpty()) inResults = true }
        onDispose { app.keyboardTarget = null }
    }
    val current = hits.getOrNull(sel.index)
    LaunchedEffect(inResults, current is Hit.Game) {
        app.hero = null
        app.hints = if (inResults) {
            listOfNotNull(Hint(HintButton.CONFIRM, "Open"), if (current is Hit.Game) Hint(HintButton.OPTIONS, "Options") else null, Hint(HintButton.BACK, "Back"))
        } else {
            listOf(Hint(HintButton.CONFIRM, "Type"), Hint(HintButton.OPTIONS, "Delete"), Hint(HintButton.SEARCH, "Space"), Hint(HintButton.NEXT, "Cursor"), Hint(HintButton.MENU, "Results"))
        }
    }

    fun open(hit: Hit) {
        when (hit) {
            is Hit.Game -> app.activateGame(hit.card)
            is Hit.System -> app.go(Route.PlatformGames(hit.card.platform.id))
            is Hit.App -> app.openApp(hit.card)
            is Hit.Collection -> app.go(Route.CollectionGames(hit.c.id, hit.c.name))
        }
    }

    InputLayer(
        enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen,
        repeats = if (inResults) emptySet() else setOf(NavAction.CONTEXT, NavAction.PREVIOUS_SECTION, NavAction.NEXT_SECTION),
    ) { e ->
        if (inResults) {
            when (e.action) {
                NavAction.UP, NavAction.DOWN, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> sel.move(e.action, hits.size, vertical = true).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
                NavAction.LEFT -> { inResults = false; NavResult.MOVED }
                NavAction.SELECT -> { hits.getOrNull(sel.index)?.let(::open); NavResult.ACTIVATED }
                NavAction.CONTEXT -> {
                    (hits.getOrNull(sel.index) as? Hit.Game)?.let { app.openContextMenu(app.gameMenu(it.card)) }
                    NavResult.ACTIVATED
                }
                else -> NavResult.IGNORED
            }
        } else {
            val r = keyboard.handle(e, field, { if (hits.isNotEmpty()) inResults = true }, onPaste = { app.pasteInto(field) })
            if (r == NavResult.BLOCKED && e.action == NavAction.RIGHT && hits.isNotEmpty()) { inResults = true; NavResult.MOVED } else r
        }
    }

    val reveal = rememberReveal()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A phone held upright stacks the keys over the results; anything wider sets them side by side.
        val stacked = maxWidth < Size.touch * 14
        val compact = maxHeight < Size.touch * 12
        val keyHeight = if (compact || stacked) Size.touch - Space.s else Size.touch - Space.xs
        val inputs: @Composable ColumnScope.() -> Unit = {
            KeyboardField(
                field,
                Modifier.fillMaxWidth().reveal(reveal, 0),
                placeholder = "Games, systems, apps",
                leading = FuseIcons.Search,
                focused = !inResults,
                onClear = { field.replaceAll("") },
            )
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            OnScreenKeyboard(
                keyboard, field, { if (hits.isNotEmpty()) inResults = true },
                modifier = Modifier.reveal(reveal, 1),
                keyHeight = keyHeight,
                doneLabel = "Results",
                showFocus = !inResults && app.focusZone == FocusZone.CONTENT,
                onPaste = { app.pasteInto(field) },
                onKey = { app.platform.haptics.tick() },
            )
        }
        val pane: @Composable ColumnScope.() -> Unit = {
            ResultsPane(
                app, hits, query, settledQuery, sel,
                showSelection = inResults && app.focusZone == FocusZone.CONTENT,
                compact = compact || stacked,
                onTap = { i, h -> sel.index = i; inResults = true; open(h) },
                modifier = Modifier.reveal(reveal, 2),
            )
        }
        val top = Size.hudHeight + if (compact) Space.s else Space.l
        if (stacked) {
            Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
                Spacer(Modifier.height(top))
                inputs()
                Spacer(Modifier.height(Space.xl))
                pane()
            }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Spacer(Modifier.height(top))
                    inputs()
                }
                Spacer(Modifier.width(Space.xxl))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Spacer(Modifier.height(top))
                    pane()
                }
            }
        }
    }
}

/**
 * The results side: what to search for while nothing is typed, the results grouped by kind (a
 * small label with each group's count), or a clear "nothing matches" with what to try instead.
 */
@Composable
private fun ColumnScope.ResultsPane(
    app: AppState,
    hits: List<Hit>,
    query: String,
    settledQuery: String,
    sel: LinearSelection,
    showSelection: Boolean,
    compact: Boolean,
    onTap: (Int, Hit) -> Unit,
    modifier: Modifier,
) {
    val blank = query.isBlank()
    val nothing = !blank && hits.isEmpty() && settledQuery.isNotBlank()
    Box(modifier.weight(1f).fillMaxWidth()) {
        when {
            blank -> Box(Modifier.fillMaxSize().padding(bottom = Size.hintHeight + Space.xl), contentAlignment = Alignment.Center) {
                EmptyState(
                    FuseIcons.Search,
                    "Search your library",
                    message = "Games, systems, apps and collections, all on this device. Results appear as you type.",
                    compact = true,
                )
            }
            nothing -> Box(Modifier.fillMaxSize().padding(bottom = Size.hintHeight + Space.xl), contentAlignment = Alignment.Center) {
                EmptyState(
                    FuseIcons.SearchX,
                    "Nothing matches “${query.trim()}”",
                    message = "Check the spelling, or try a shorter part of a title or a system's name.",
                    compact = true,
                )
            }
            else -> ResultList(app, hits, query, sel, showSelection, compact, onTap)
        }
    }
}

@Composable
private fun ResultList(
    app: AppState,
    hits: List<Hit>,
    query: String,
    sel: LinearSelection,
    showSelection: Boolean,
    compact: Boolean,
    onTap: (Int, Hit) -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val list = rememberLazyListState()
    // The list's items: a label before each kind's first result, then the results themselves.
    val layout = remember(hits) { ResultLayout.of(hits) }
    FollowSelection(list, { layout.itemOf(sel.index).let { if (sel.index == layout.firstOfGroup(sel.index)) it - 1 else it }.coerceAtLeast(0) }, anchor = 0.3f)

    // One highlight glides from row to row (in result space), stretching a little on the way.
    val target = sel.index.coerceIn(0, (hits.size - 1).coerceAtLeast(0)).toFloat()
    val top = remember { Animatable(target) }
    val bottom = remember { Animatable(target) }
    val shown by animateFloatAsState(if (showSelection && hits.isNotEmpty()) 1f else 0f, motion.tween(if (showSelection) Durations.FAST else Durations.INSTANT), label = "hl")
    val lastKeys = remember { arrayOfNulls<List<String>>(1) }
    LaunchedEffect(target, layout.keys) {
        val fresh = lastKeys[0] != layout.keys
        lastKeys[0] = layout.keys
        if (motion.reduced || fresh || shown < 0.05f) {
            top.snapTo(target)
            bottom.snapTo(target)
            return@LaunchedEffect
        }
        // A long jump comes in from the neighbouring row only; the list is already scrolling.
        if (abs(target - top.value) > 1.5f) {
            val from = if (target > top.value) target - 1f else target + 1f
            top.snapTo(from)
            bottom.snapTo(from)
        }
        val down = target >= bottom.value
        launch { top.animateTo(target, if (down) motion.glideTrail() else motion.glide()) }
        launch { bottom.animateTo(target, if (down) motion.glide() else motion.glideTrail()) }
    }
    val fill = c.text.copy(alpha = if (c.isDark) 0.1f else 0.07f)
    val accent = c.accent
    val corner = Fuse.geometry.control
    val outline = if (Fuse.look.highContrastFocus) c.focus else null
    val rowShape = androidx.compose.foundation.shape.RoundedCornerShape(corner)

    LazyColumn(
        state = list,
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
        contentPadding = PaddingValues(bottom = Size.hintHeight + Space.xl),
        modifier = Modifier
            .fillMaxSize()
            .fadingEdges(list, top = Space.l, bottom = Space.x3)
            .drawBehind {
                if (shown <= 0.01f || hits.isEmpty()) return@drawBehind
                val info = list.layoutInfo
                val t = layout.edge(top.value, info, bottom = false) ?: return@drawBehind
                val b = layout.edge(bottom.value, info, bottom = true) ?: return@drawBehind
                if (b <= t) return@drawBehind
                val h = b - t
                val r = corner.toPx().coerceAtMost(h / 2)
                clipRect {
                    drawRoundRect(fill, Offset(0f, t), size.copy(height = h), CornerRadius(r), alpha = shown)
                    if (outline != null) {
                        val sw = Size.focusStroke.toPx()
                        drawRoundRect(outline, Offset(sw / 2, t + sw / 2), androidx.compose.ui.geometry.Size(size.width - sw, h - sw), CornerRadius((r - sw / 2).coerceAtLeast(0f)), alpha = shown, style = Stroke(sw))
                    }
                    val bh = (Size.iconL - Space.xxs).toPx().coerceAtMost(h - Space.s.toPx())
                    val x = if (r > bh / 2) r - kotlin.math.sqrt(r * r - (bh / 2) * (bh / 2)) else 0f
                    drawRoundRect(accent, Offset(x, t + (h - bh) / 2), androidx.compose.ui.geometry.Size(BAR.toPx(), bh), CornerRadius(BAR.toPx()), alpha = shown)
                }
            },
    ) {
        hits.forEachIndexed { i, h ->
            if (layout.firstOfGroup(i) == i) {
                item(key = "kind.${h.kind}", contentType = "label") {
                    val count = layout.countOf(h.kind)
                    SectionLabel(
                        h.kind.label,
                        Modifier.padding(start = BAR + Space.m, top = if (i == 0) Space.xs else Space.l, bottom = Space.xs),
                        count = count.toString(),
                        icon = h.kind.icon,
                    )
                }
            }
            item(key = h.key, contentType = "hit") {
                val selected = showSelection && i == sel.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (compact) Size.rowCompact + Space.s else Size.row)
                        .clip(rowShape)
                        .fuseClickable(shape = rowShape, scale = false) { onTap(i, h) }
                        .semantics { this.selected = selected }
                        .padding(start = BAR + Space.m, end = Space.m, top = Space.xs, bottom = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HitThumb(h, if (compact) Size.thumb - Space.xs else Size.thumb)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                        val titleMatches = remember(h.title, query) { highlight(h.title, query, Color.Unspecified).spanStyles.isNotEmpty() }
                        Highlighted(h.title, query, Fuse.type.bodyStrong, base = if (selected) c.text else c.text.copy(alpha = 0.86f), mark = c.text, maxLines = 1, underline = c.accent)
                        // The detail is picked out only when it is why the result is here (a system found by its short name).
                        Highlighted(h.detail, if (titleMatches) "" else query, Fuse.type.caption, base = c.textMuted, mark = c.text, maxLines = 1)
                    }
                    if (h is Hit.Game && h.card.favorite) {
                        Spacer(Modifier.width(Space.s))
                        FuseIcon(FuseIcons.Heart, size = Size.iconXS, tint = c.accent)
                    }
                }
            }
        }
    }
}

/** A result's picture: the game's art, the system's mark, the app's icon, or a collection's sign. */
@Composable
private fun HitThumb(h: Hit, size: androidx.compose.ui.unit.Dp) {
    val c = Fuse.colors
    val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.12f) + 0.06f)
    when (h) {
        is Hit.Game -> SquareGameArt(h.card.art, Modifier.size(size).clip(shape), fallback = { GeneratedArt(h.title, h.card.accent.toColor(), slot = ArtSlot.ICON) })
        is Hit.System -> SystemMark(h.card, size)
        is Hit.App -> Artwork(h.card.icon, Modifier.size(size).clip(shape), contentScale = androidx.compose.ui.layout.ContentScale.Fit, fallback = {
            GeneratedArt(h.title, c.accent, slot = ArtSlot.ICON)
        })
        is Hit.Collection -> Box(Modifier.size(size).clip(shape).background(c.text.copy(alpha = if (c.isDark) 0.08f else 0.06f)), contentAlignment = Alignment.Center) {
            FuseIcon(if (h.c.kind == CollectionKind.SERIES) FuseIcons.Sparkles else FuseIcons.Bookmark, size = Size.iconM, tint = c.textMuted)
        }
    }
}

/**
 * [text] with every part that matches a word of [query] picked out in [mark] (and a touch bolder),
 * so it is clear why each result is here. With an [underline] colour each match also sits on a
 * short rounded stroke under its letters: clear at a glance, without painting whole words in the
 * accent. Matches hidden by the ellipsis get no stroke.
 */
@Composable
private fun Highlighted(text: String, query: String, style: TextStyle, base: Color, mark: Color, maxLines: Int, underline: Color? = null) {
    val annotated = remember(text, query, base, mark) { highlight(text, query, mark) }
    val ranges = remember(annotated) { annotated.spanStyles.map { it.start until it.end } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val strokes = if (underline == null || ranges.isEmpty()) Modifier else Modifier.drawBehind {
        val l = layout ?: return@drawBehind
        val h = Size.focusStroke.toPx()
        val below = Space.xxs.toPx()
        for (r in ranges) {
            val line = l.getLineForOffset(r.first)
            val visibleEnd = l.getLineEnd(line, visibleEnd = true)
            if (r.first >= visibleEnd) continue
            val end = minOf(r.last + 1, visibleEnd)
            val left = l.getHorizontalPosition(r.first, usePrimaryDirection = true)
            val right = l.getHorizontalPosition(end, usePrimaryDirection = true)
            drawRoundRect(underline, Offset(minOf(left, right), l.getLineBaseline(line) + below), androidx.compose.ui.geometry.Size(abs(right - left), h), CornerRadius(h / 2))
        }
    }
    BasicText(annotated, strokes, style = style.copy(color = base), maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = { layout = it })
}

private fun highlight(text: String, query: String, mark: Color): AnnotatedString {
    val words = query.trim().split(' ', '\t').filter { it.isNotEmpty() }
    if (words.isEmpty()) return AnnotatedString(text)
    // Every occurrence of every word, merged where they overlap.
    val ranges = words.flatMap { w ->
        generateSequence(text.indexOf(w, ignoreCase = true).takeIf { it >= 0 }) { from ->
            text.indexOf(w, from + w.length, ignoreCase = true).takeIf { it >= 0 }
        }.map { it until it + w.length }.toList()
    }.sortedBy { it.first }
    if (ranges.isEmpty()) return AnnotatedString(text)
    val merged = mutableListOf<IntRange>()
    for (r in ranges) {
        val last = merged.lastOrNull()
        if (last != null && r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last) else merged += r
    }
    return buildAnnotatedString {
        var at = 0
        for (r in merged) {
            append(text.substring(at, r.first))
            withStyle(SpanStyle(color = mark, fontWeight = FontWeight.Bold)) { append(text.substring(r.first, r.last + 1)) }
            at = r.last + 1
        }
        append(text.substring(at))
    }
}

/** Where each result sits among the list's items (a group label comes before each kind). */
private class ResultLayout(val keys: List<String>, private val items: IntArray, private val groupStart: IntArray, private val counts: Map<HitKind, Int>) {
    fun itemOf(hit: Int): Int = if (items.isEmpty()) 0 else items[hit.coerceIn(0, items.size - 1)]

    /** The index of the first result in [hit]'s group. */
    fun firstOfGroup(hit: Int): Int = if (groupStart.isEmpty()) 0 else groupStart[hit.coerceIn(0, groupStart.size - 1)]

    fun countOf(kind: HitKind): Int = counts[kind] ?: 0

    /** The top (or [bottom]) edge of the row at a fractional result position, blended between rows. */
    fun edge(pos: Float, info: LazyListLayoutInfo, bottom: Boolean): Float? {
        if (items.isEmpty()) return null
        val i = pos.toInt().coerceIn(0, items.size - 1)
        val j = (i + 1).coerceAtMost(items.size - 1)
        val f = (pos - i).coerceIn(0f, 1f)
        val a = span(items[i], info) ?: return null
        val b = if (f > 0f && j != i) span(items[j], info) ?: a else a
        val ea = if (bottom) a.second else a.first
        val eb = if (bottom) b.second else b.first
        return ea + (eb - ea) * f
    }

    private fun span(item: Int, info: LazyListLayoutInfo): Pair<Float, Float>? {
        val visible = info.visibleItemsInfo
        val hit = visible.firstOrNull { it.index == item } ?: return null
        return hit.offset.toFloat() to (hit.offset + hit.size).toFloat()
    }

    companion object {
        fun of(hits: List<Hit>): ResultLayout {
            val items = IntArray(hits.size)
            val starts = IntArray(hits.size)
            var next = 0
            var start = 0
            hits.forEachIndexed { i, h ->
                if (i == 0 || hits[i - 1].kind != h.kind) {
                    next++
                    start = i
                }
                starts[i] = start
                items[i] = next++
            }
            return ResultLayout(hits.map { it.key }, items, starts, hits.groupingBy { it.kind }.eachCount())
        }
    }
}

/** The accent bar at the start of the selected row. */
private val BAR = Size.track - Space.hair
