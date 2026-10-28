package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt
import io.github.matiyaaa.fuse.ui.shell.components.gameMenuArt
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.settings.SettingsPageHeading
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.PlayRecap
import io.github.matiyaaa.fuse.ui.shell.store.RecapPeriod

/**
 * The month now, from [periods] as [io.github.matiyaaa.fuse.ui.shell.store.LibraryOps.recapPeriods]
 * gives them (this year, then this month, then the rest); null when they don't say.
 */
internal fun recapNow(periods: List<RecapPeriod>): RecapPeriod? = periods.getOrNull(1)?.takeIf { it.month != null && it.year == periods[0].year }

/** "Your 2026", "October", "October 2025": what a recap is called, in lists and on its own page ([now] is this month). */
internal fun recapName(p: RecapPeriod, now: RecapPeriod?): String = when {
    p.month == null -> "Your ${p.year}"
    else -> monthWord(p.month) + if (now != null && p.year == now.year) "" else " ${p.year}"
}

/** What a recap row says beneath its name: so far, while the period is still going ([now] is this month). */
internal fun recapDetail(p: RecapPeriod, now: RecapPeriod?): String = when {
    now != null && p.month == null && p.year == now.year -> "This year so far"
    now != null && p == now -> "This month so far"
    p.month == null -> "The year in play"
    else -> "The month in play"
}

/** Below this height the moments are rows in the list rather than tiles. */
private val SHORT_SCREEN = 560.dp

private fun monthWord(m: Int) = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")[m - 1]

private fun share(part: Long, whole: Long): String = if (whole <= 0) "" else "${(part * 100 / whole).coerceIn(1, 100)}%"

private fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"

/**
 * Your Year: a month or a year of play on one page. The time in large numbers over its months (or
 * days) as bars, its moments (the most played game, the longest session, the longest run of days,
 * the games new to you, those finished in the Journal and when you play most), and beside them a
 * list of its games, systems and the other recaps, each opening what it names. It can be saved as a
 * picture, made for keeping and sharing, where screenshots go.
 */
@Composable
fun RecapScreen(app: AppState, period: RecapPeriod) {
    val recap by remember(period) { app.store.library.recap(period) }.collectAsState(PlayRecap(period))
    val periods by produceState(emptyList<RecapPeriod>()) { value = runCatching { app.store.library.recapPeriods() }.getOrDefault(emptyList()) }
    val now = recapNow(periods)
    val sel = remember(period) { LinearSelection() }
    PageEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.BACK, "Back"))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        RecapPage(app, period, recap, periods, now, sel, narrow = maxWidth < 720.dp, short = maxHeight < SHORT_SCREEN)
    }
}

/**
 * The recap laid out for the room it has: on a [short] screen (a handheld's) or a [narrow] one (a
 * phone held upright) its moments are rows at the top of the list, elsewhere tiles under the hero.
 */
@Composable
private fun RecapPage(app: AppState, period: RecapPeriod, r: PlayRecap, periods: List<RecapPeriod>, now: RecapPeriod?, sel: LinearSelection, narrow: Boolean, short: Boolean) {
    val art = app.store.prefs.value.gameArt
    val momentRows = short || narrow
    var drawing by remember(period) { mutableStateOf(false) }
    val rows = buildList {
        if (app.capture != null && r.loaded && r.seconds > 0) add(MenuAction(
            "picture", "Save as a picture", FuseIcons.Image,
            detail = "This recap drawn to keep and share, saved with your screenshots",
            trailing = Trailing.Chevron, onSelect = { drawing = true },
        ))
        if (momentRows) momentsOf(r).forEach { m ->
            add(MenuAction(
                "m${m.label}", m.label, m.icon, detail = m.detail, trailing = Trailing.Value(m.value),
                section = "Moments", onSelect = { m.game?.let { app.activateGame(it) } },
            ))
        }
        r.games.forEach { (g, seconds) ->
            add(MenuAction(
                "g${g.id.value}", g.title, null,
                detail = listOf(g.platformShort, share(seconds, r.seconds)).filter { it.isNotEmpty() }.joinToString("  ·  "),
                trailing = Trailing.Value(playtimeText(seconds)),
                art = gameMenuArt(g.art, g.title, g.accent, art),
                section = "Most played",
                onSelect = { app.activateGame(g) },
            ))
        }
        r.systems.forEach { (p, seconds) ->
            add(MenuAction(
                "p${p.platform.id.value}", p.platform.name, FuseIcons.Chip,
                detail = "${share(seconds, r.seconds)} of the time",
                trailing = Trailing.Value(playtimeText(seconds)),
                section = "By system",
                onSelect = { app.go(Route.PlatformGames(p.platform.id)) },
            ))
        }
        r.firsts.forEach { g ->
            add(MenuAction(
                "f${g.id.value}", g.title, null, detail = g.platformShort,
                art = gameMenuArt(g.art, g.title, g.accent, art),
                section = "New to you", onSelect = { app.activateGame(g) },
            ))
        }
        r.finished.forEach { g ->
            add(MenuAction(
                "d${g.id.value}", g.title, null,
                detail = listOfNotNull(g.platformShort, g.status?.let { io.github.matiyaaa.fuse.ui.shell.store.JournalWords.label(it) }).joinToString("  ·  "),
                art = gameMenuArt(g.art, g.title, g.accent, art),
                section = "Finished", onSelect = { app.activateGame(g) },
            ))
        }
        periods.filter { it != period }.forEach { p ->
            add(MenuAction(
                "r${p.year}.${p.month ?: 0}", recapName(p, now), if (p.month == null) FuseIcons.Sparkles else FuseIcons.CalendarDays,
                detail = recapDetail(p, now), trailing = Trailing.Chevron,
                section = "Other recaps", onSelect = { app.go(Route.Recap(p)) },
            ))
        }
    }
    sel.clamp(rows.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e -> handleMenuAction(e, rows, sel) }

    Box(Modifier.fillMaxSize()) {
        if (drawing) PosterCapture(r, recapName(period, null), recapDetail(period, now)) { picture ->
            drawing = false
            val capture = app.capture
            if (picture == null || capture == null) app.toasts.show("The picture couldn't be made") else capture.savePicture("Fuse ${recapName(period, null)}", picture)
        }
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) Space.gutterCompact else Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (short) Space.s else Space.l))
            SettingsPageHeading(recapName(period, null), "What you played, as Fuse saw it on this device", short, Modifier.reveal(0))
            Spacer(Modifier.height(if (short) Space.m else Space.l))
            // Beside the list the hero takes the height the moments leave, its bars growing into it;
            // above the list (narrow) everything keeps its own height.
            val story = @Composable { m: Modifier, flex: Boolean ->
                Column(m, verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Panel(if (flex) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth()) {
                        RecapHero(r, (if (flex) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).padding(if (short) Space.m else Space.l), compact = short, flex = flex)
                    }
                    if (r.loaded && r.seconds > 0 && !momentRows) Moments(r, columns = 3, onGame = { app.activateGame(it) })
                }
            }
            val list = @Composable { m: Modifier ->
                Panel(m) {
                    if (r.games.isEmpty() && rows.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(Space.xl), contentAlignment = Alignment.Center) {
                            EmptyState(
                                FuseIcons.Sparkles, if (r.loaded) "Nothing played here yet" else "Adding it up",
                                message = if (r.loaded) "Play a game from Fuse and ${if (period.month == null) "the year" else "the month"} fills in here." else null,
                                compact = true,
                            )
                        }
                    } else {
                        MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT, fadeEdges = true)
                    }
                }
            }
            if (narrow) {
                story(Modifier.fillMaxWidth().reveal(1), false)
                Spacer(Modifier.height(Space.m))
                list(Modifier.fillMaxWidth().weight(1f).padding(bottom = Size.hintHeight).reveal(2))
            } else {
                Row(Modifier.fillMaxWidth().weight(1f).padding(bottom = Size.hintHeight + Space.s)) {
                    story(Modifier.weight(1.3f).fillMaxHeight().reveal(1), true)
                    Spacer(Modifier.width(Space.l))
                    list(Modifier.weight(1f).fillMaxHeight().reveal(2))
                }
            }
        }
    }
}

/**
 * The period's time in large numbers, its facts as pills, and its months (or days) as bars; with
 * [flex] the bars take whatever height is left.
 */
@Composable
private fun RecapHero(r: PlayRecap, modifier: Modifier, compact: Boolean, flex: Boolean) {
    val c = Fuse.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FuseIcon(FuseIcons.Sparkles, size = 14.dp, tint = c.accent)
            Spacer(Modifier.width(Space.xs))
            FText("TIME PLAYED", Fuse.type.overline, color = c.accent, maxLines = 1)
        }
        Spacer(Modifier.height(Space.s))
        FText(if (r.seconds > 0) playtimeText(r.seconds) else if (r.loaded) "Nothing yet" else " ", (if (compact) Fuse.type.display else Fuse.type.hero).tabular(), maxLines = 1)
        Spacer(Modifier.height(Space.s))
        val facts = listOfNotNull(
            r.sessions.takeIf { it > 0 }?.let { plural(it, "session") },
            r.days.takeIf { it > 0 }?.let { plural(it, "day") + " played" },
            r.gameCount.takeIf { it > 0 }?.let { plural(it, "game") },
        )
        if (facts.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { facts.forEach { Pill(it) } }
        } else {
            FText(if (r.loaded) "Play something and it fills in here" else "Adding it up", Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        if (r.bars.isNotEmpty()) {
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            RecapBars(r.bars, r.barNow, if (flex) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(if (compact) 72.dp else 112.dp))
            Spacer(Modifier.height(Space.xs))
            BarLabels(r.barLabels, monthly = r.period.month == null)
        }
    }
}

/** A fact on a soft pill. */
@Composable
private fun Pill(text: String) {
    val c = Fuse.colors
    FText(
        text, Fuse.type.label, color = c.text, maxLines = 1,
        modifier = Modifier.clip(PillShape).background(c.text.copy(alpha = 0.07f)).padding(horizontal = Space.s + 2.dp, vertical = 3.dp),
    )
}

/** The names under the bars: every month's letter, or a month's days every week (1, 8, 15, 22, 29). */
@Composable
private fun BarLabels(labels: List<String>, monthly: Boolean) {
    val c = Fuse.colors
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // A day's cell is narrower than "22": the label sits centred over it, wider than it.
                if (monthly || i % 7 == 0) FText(l, Fuse.type.caption.tabular(), color = c.textMuted, maxLines = 1, modifier = Modifier.wrapContentWidth(unbounded = true))
            }
        }
    }
}

/**
 * The bars, each from a soft top to a fainter base; the busiest in the accent, the one for now
 * outlined while the period is still going. They grow in once, or appear at once with reduced motion.
 */
@Composable
private fun RecapBars(bars: List<Long>, now: Int?, modifier: Modifier, animate: Boolean = true) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val still = motion.reduced || !animate
    val grow = remember { FuselineValue(if (still) 1f else 0f) }
    PageEffect(bars.isNotEmpty()) { if (!still) grow.animateTo(1f, motion.tween(700)) }
    val restTop = c.text.copy(alpha = if (c.isDark) 0.3f else 0.2f)
    val restBottom = c.text.copy(alpha = if (c.isDark) 0.1f else 0.07f)
    val track = c.text.copy(alpha = if (c.isDark) 0.05f else 0.04f)
    val best = bars.indices.maxByOrNull { bars[it] }?.takeIf { bars[it] > 0 }
    val accentTop = androidx.compose.ui.graphics.lerp(c.accent, Color.White, 0.18f)
    Canvas(modifier) {
        val n = bars.size
        val gap = (if (n > 16) 3.dp else 8.dp).toPx()
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val corner = CornerRadius(minOf(w / 2, 6.dp.toPx()))
        val max = bars.max().coerceAtLeast(1).toFloat()
        val g = grow.value
        bars.forEachIndexed { i, v ->
            val x = i * (w + gap)
            drawRoundRect(track, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height), corner)
            // Each bar starts a beat after the one before it, so the period fills from the left.
            val p = ((g - i * (0.5f / n)) / 0.6f).coerceIn(0f, 1f)
            if (v > 0) {
                val h = (size.height * (v / max) * p).coerceAtLeast(3.dp.toPx())
                val top = if (i == best) accentTop else restTop
                val bottom = if (i == best) c.accent else restBottom
                drawRoundRect(Brush.verticalGradient(listOf(top, bottom), startY = size.height - h, endY = size.height), Offset(x, size.height - h), androidx.compose.ui.geometry.Size(w, h), corner)
            }
            if (i == now) {
                drawRoundRect(c.accent.copy(alpha = 0.55f), Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height), corner, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** One moment of the period: what it is, the figure in large type, a line about it, and its game's art when [art]. */
private data class Moment(val icon: ImageVector, val label: String, val value: String, val detail: String, val game: GameCard? = null, val art: Boolean = true)

/** The period's moments, only those it has: the most played game, the longest session and so on. */
private fun momentsOf(r: PlayRecap): List<Moment> {
    val top = r.games.firstOrNull()
    return listOfNotNull(
        top?.let { (g, s) -> Moment(FuseIcons.Crown, "Most played", playtimeText(s), "${g.title}  ·  ${share(s, r.seconds)}", g) },
        r.longest?.let { Moment(FuseIcons.Timer, "Longest session", playtimeText(it.seconds), "${it.game.title}, ${it.day}", it.game, art = false) },
        r.streakDays.takeIf { it > 1 }?.let { Moment(FuseIcons.Flame, "Longest streak", "$it days", r.streakWords ?: "In a row") },
        r.firstCount.takeIf { it > 0 }?.let { Moment(FuseIcons.Rocket, "New to you", plural(it, "game"), "First played ${if (r.period.month == null) "this year" else "this month"}") },
        r.finished.size.takeIf { it > 0 }?.let { Moment(FuseIcons.Flag, "Finished", plural(it, "game"), "Beaten or completed") },
        r.weekday?.let { Moment(if (r.partOfDay == "late at night" || r.partOfDay == "evenings") FuseIcons.Moon else FuseIcons.Sun, "When you play", it, "Mostly ${r.partOfDay ?: "any time"}") },
    )
}

/** The period's moments in a grid of [columns]. */
@Composable
private fun Moments(r: PlayRecap, columns: Int, onGame: (GameCard) -> Unit) {
    val moments = momentsOf(r)
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        moments.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                row.forEach { m -> MomentTile(m, Modifier.weight(1f)) { m.game?.let(onGame) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MomentTile(m: Moment, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    // A moment of a game opens it on a touch; the list beside opens everything with the controller.
    val touch = if (m.game != null) Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onClick) else Modifier
    Panel(modifier.then(touch)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(m.icon, size = Size.iconS, tint = c.textMuted)
                    Spacer(Modifier.width(Space.xs))
                    FText(m.label.uppercase(), Fuse.type.overline, color = c.textMuted, maxLines = 1)
                }
                Spacer(Modifier.height(Space.xs))
                FText(m.value, Fuse.type.title.tabular(), maxLines = 1)
                FText(m.detail, Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
            if (m.game != null && m.art) {
                Spacer(Modifier.width(Space.s))
                val shape = RoundedCornerShape(Fuse.geometry.control * 0.7f)
                Box(Modifier.size(44.dp).clip(shape)) {
                    SquareGameArt(m.game.art, Modifier.size(44.dp), fallback = { GeneratedArt(m.game.title, Color(m.game.accent), Modifier.size(44.dp)) })
                }
            }
        }
    }
}

/** A moment's name as the picture's narrower tiles have room for it. */
private fun posterLabel(label: String): String = when (label) {
    "Longest session" -> "Best session"
    "Longest streak" -> "Streak"
    "When you play" -> "Mostly"
    else -> label
}

/** A recap's picture in pixels: a 4:5 portrait, the shape galleries and feeds show whole. */
private const val POSTER_W = 1080
private const val POSTER_H = 1350

/** The density it is drawn at whatever the screen's, so the picture is the same from every device: 480 by 600. */
private const val POSTER_DENSITY = 2.25f

/** How long the picture waits for its game's art before it is taken as drawn. */
private const val POSTER_SETTLE_MS = 450L

/**
 * Draws [r]'s picture where nothing shows it, into a layer of its own, and gives it to [onDrawn]
 * once its art has had a moment to arrive (null when it couldn't be made).
 */
@Composable
private fun PosterCapture(r: PlayRecap, name: String, detail: String, onDrawn: (ImageBitmap?) -> Unit) {
    val layer = rememberGraphicsLayer()
    val done by rememberUpdatedState(onDrawn)
    CompositionLocalProvider(LocalDensity provides Density(POSTER_DENSITY, 1f)) {
        val d = LocalDensity.current
        // Recorded, never drawn to the screen.
        Box(Modifier.requiredSize(with(d) { POSTER_W.toDp() }, with(d) { POSTER_H.toDp() }).drawWithContent { layer.record { this@drawWithContent.drawContent() } }) {
            RecapPoster(r, name, detail)
        }
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(POSTER_SETTLE_MS)
        withFrameNanos { }
        done(runCatching { layer.toImageBitmap() }.getOrNull())
    }
}

/**
 * A recap made to keep: the theme's spark glowing from a corner of its room, the period and its
 * time in large numbers with its facts, its bars, the most played game with its art, and up to
 * three of its moments beside each other.
 */
@Composable
private fun RecapPoster(r: PlayRecap, name: String, detail: String) {
    val c = Fuse.colors
    val t = Fuse.type
    val card = Modifier.clip(RoundedCornerShape(18.dp)).background(c.surface.copy(alpha = if (c.isDark) 0.72f else 0.86f))
    Box(Modifier.fillMaxSize().background(c.ink)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(c.accent.copy(alpha = if (c.isDark) 0.46f else 0.3f), Color.Transparent), center = Offset(size.width * 0.95f, 0f), radius = size.width * 1.1f))
            drawRect(Brush.radialGradient(listOf(c.accent.copy(alpha = if (c.isDark) 0.14f else 0.1f), Color.Transparent), center = Offset(0f, size.height), radius = size.width * 0.9f))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(FuseIcons.Sparkles, size = 14.dp, tint = c.accent)
                Spacer(Modifier.width(6.dp))
                FText("FUSE", t.overline, color = c.accent, maxLines = 1)
                Spacer(Modifier.weight(1f))
                FText(detail.uppercase(), t.overline, color = c.textMuted, maxLines = 1)
            }
            Spacer(Modifier.height(20.dp))
            FText(name, t.display.copy(fontSize = 34.sp, lineHeight = 40.sp), maxLines = 1)
            Spacer(Modifier.height(18.dp))
            FText("TIME PLAYED", t.overline, color = c.accent, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            FText(playtimeText(r.seconds), t.hero.copy(fontSize = 58.sp, lineHeight = 64.sp).tabular(), maxLines = 1)
            Spacer(Modifier.height(8.dp))
            val facts = listOfNotNull(
                r.sessions.takeIf { it > 0 }?.let { plural(it, "session") },
                r.days.takeIf { it > 0 }?.let { plural(it, "day") + " played" },
                r.gameCount.takeIf { it > 0 }?.let { plural(it, "game") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { facts.forEach { Pill(it) } }
            if (r.bars.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                RecapBars(r.bars, null, Modifier.fillMaxWidth().height(76.dp), animate = false)
                Spacer(Modifier.height(4.dp))
                BarLabels(r.barLabels, monthly = r.period.month == null)
            }
            Spacer(Modifier.height(22.dp))
            r.games.firstOrNull()?.let { (g, seconds) ->
                Row(card.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val shape = RoundedCornerShape(12.dp)
                    Box(Modifier.size(64.dp).clip(shape)) {
                        SquareGameArt(g.art, Modifier.size(64.dp), fallback = { GeneratedArt(g.title, Color(g.accent), Modifier.size(64.dp)) })
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FuseIcon(FuseIcons.Crown, size = 12.dp, tint = c.accent)
                            Spacer(Modifier.width(4.dp))
                            FText("MOST PLAYED", t.overline, color = c.accent, maxLines = 1)
                        }
                        Spacer(Modifier.height(2.dp))
                        FText(g.title, t.titleSmall, maxLines = 1)
                        FText(listOf(playtimeText(seconds), share(seconds, r.seconds), g.platformShort).filter { it.isNotEmpty() }.joinToString("  ·  "), t.caption, color = c.textMuted, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            val moments = momentsOf(r).drop(if (r.games.isNotEmpty()) 1 else 0).take(3)
            if (moments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    moments.forEach { m ->
                        Column(card.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FuseIcon(m.icon, size = 12.dp, tint = c.textMuted)
                                Spacer(Modifier.width(4.dp))
                                FText(posterLabel(m.label).uppercase(), t.overline.copy(fontSize = 9.sp), color = c.textMuted, maxLines = 1)
                            }
                            Spacer(Modifier.height(2.dp))
                            FText(m.value, t.titleSmall.tabular(), maxLines = 1)
                        }
                    }
                    repeat(3 - moments.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.weight(1f))
            FText("Made with Fuse", t.caption, color = c.textFaint, maxLines = 1, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
