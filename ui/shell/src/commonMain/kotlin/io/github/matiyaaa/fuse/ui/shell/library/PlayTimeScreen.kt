package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuArt
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.settings.SettingsPageHeading
import io.github.matiyaaa.fuse.ui.shell.store.PlayTimeReport

/**
 * Play time: today, this week, this month and in all as four figures, the last thirty days as bars,
 * and the games of this month and the systems of all time as a list that opens what it names. Only
 * time Fuse saw counts toward the days; imported time (Steam, RetroAchievements) joins the total.
 */
@Composable
fun PlayTimeScreen(app: AppState) {
    val report by remember { app.store.library.playTime() }.collectAsState(PlayTimeReport())
    val sel = remember { LinearSelection() }
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.BACK, "Back"))
    }
    val r = report
    val longestGame = r.games.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val longestSystem = r.systems.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val rows = buildList {
        r.games.forEach { (g, seconds) ->
            add(MenuAction(
                "g${g.id.value}", g.title, null,
                detail = "${g.platformShort}  ·  ${share(seconds, r.monthSeconds)} of ${r.month}",
                trailing = Trailing.Value(playtimeText(seconds)),
                art = MenuArt(g.art.tile, square = true, fallbackTitle = g.title, accent = g.accent, wide = false),
                section = "Most played in ${r.month}",
                onSelect = { app.activateGame(g) },
            ))
        }
        r.systems.forEach { (p, seconds) ->
            add(MenuAction(
                "p${p.platform.id.value}", p.platform.name, FuseIcons.Chip,
                detail = "${share(seconds, r.totalSeconds)} of all your play time",
                trailing = Trailing.Value(playtimeText(seconds)),
                section = "By system, all time",
                onSelect = { app.go(Route.PlatformGames(p.platform.id)) },
            ))
        }
    }
    sel.clamp(rows.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e -> handleMenuAction(e, rows, sel) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 640.dp
        val short = maxHeight < 560.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) Space.gutterCompact else Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (short) Space.s else Space.l))
            SettingsPageHeading("Play time", "Time Fuse saw you play, and time you brought from elsewhere", short, Modifier.reveal(0))
            Spacer(Modifier.height(if (short) Space.m else Space.l))
            val figures = @Composable { m: Modifier ->
                Column(m, verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        Figure(FuseIcons.Sun, "Today", r.todaySeconds, Modifier.weight(1f))
                        Figure(FuseIcons.Calendar, "This week", r.weekSeconds, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        Figure(FuseIcons.CalendarDays, r.month.ifEmpty { "This month" }, r.monthSeconds, Modifier.weight(1f))
                        Figure(
                            FuseIcons.Hourglass, "All time", r.totalSeconds, Modifier.weight(1f),
                            note = if (r.importedSeconds > 0) "${playtimeText(r.importedSeconds)} imported" else null,
                        )
                    }
                    Panel(Modifier.fillMaxWidth().weight(1f, fill = !narrow)) {
                        Column(Modifier.fillMaxSize().padding(Space.l)) {
                            val busiest = r.days.maxOrNull() ?: 0
                            SectionLabel("Last 30 days", icon = FuseIcons.Activity)
                            Spacer(Modifier.height(Space.xs))
                            FText(
                                if (busiest > 0) "Busiest day: ${playtimeText(busiest)}" else "Days you play show here",
                                Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
                            )
                            Spacer(Modifier.height(Space.m))
                            DayBars(r.days, if (narrow) Modifier.fillMaxWidth().height(120.dp) else Modifier.fillMaxWidth().weight(1f))
                            Spacer(Modifier.height(Space.xs))
                            Row(Modifier.fillMaxWidth()) {
                                FText("30 days ago", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
                                Spacer(Modifier.weight(1f))
                                FText("Today", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
                            }
                        }
                    }
                }
            }
            val list = @Composable { m: Modifier ->
                Panel(m) {
                    if (rows.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(Space.xl), contentAlignment = Alignment.Center) {
                            io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState(
                                FuseIcons.Hourglass, if (r.loaded) "Nothing played yet" else "Adding it up",
                                message = if (r.loaded) "Play a game from Fuse and its time shows here, by game and by system." else null,
                                compact = true,
                            )
                        }
                    } else {
                        MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT, fadeEdges = true)
                    }
                }
            }
            if (narrow) {
                figures(Modifier.fillMaxWidth().reveal(1))
                Spacer(Modifier.height(Space.m))
                list(Modifier.fillMaxWidth().weight(1f).padding(bottom = Size.hintHeight).reveal(2))
            } else {
                Row(Modifier.fillMaxWidth().weight(1f).padding(bottom = Size.hintHeight + Space.s)) {
                    figures(Modifier.weight(1f).fillMaxHeight().reveal(1))
                    Spacer(Modifier.width(Space.l))
                    list(Modifier.weight(1.1f).fillMaxHeight().reveal(2))
                }
            }
        }
    }
}

private fun share(part: Long, whole: Long): String {
    if (whole <= 0) return "0%"
    val pct = (part * 100 / whole).toInt()
    return if (pct < 1) "under 1%" else "$pct%"
}

/** One figure: an icon, what it counts, and the time in large numbers. */
@Composable
private fun Figure(icon: ImageVector, label: String, seconds: Long, modifier: Modifier, note: String? = null) {
    val c = Fuse.colors
    Panel(modifier) {
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(icon, size = Size.iconS, tint = c.textMuted)
                Spacer(Modifier.width(Space.xs))
                FText(label.uppercase(), Fuse.type.overline, color = c.textMuted, maxLines = 1)
            }
            FText(if (seconds <= 0) "None" else playtimeText(seconds), Fuse.type.title.tabular(), maxLines = 1)
            if (note != null) FText(note, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
    }
}

/** The days as bars, today in the accent; they grow in once, or appear at once with reduced motion. */
@Composable
private fun DayBars(days: List<Long>, modifier: Modifier) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val grow = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(days.isNotEmpty()) {
        if (days.isNotEmpty() && !motion.reduced) grow.animateTo(1f, motion.tween(600))
    }
    val track = c.text.copy(alpha = if (c.isDark) 0.06f else 0.05f)
    val bar = c.text.copy(alpha = if (c.isDark) 0.38f else 0.3f)
    val today = c.accent
    Canvas(modifier) {
        if (days.isEmpty()) return@Canvas
        val n = days.size
        val gap = 3.dp.toPx()
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val max = days.max().coerceAtLeast(1).toFloat()
        val radius = CornerRadius(minOf(w / 2, 3.dp.toPx()))
        days.forEachIndexed { i, s ->
            val x = i * (w + gap)
            drawRoundRect(track, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height), radius)
            if (s > 0) {
                val h = (size.height * (s / max) * grow.value).coerceAtLeast(2.dp.toPx())
                drawRoundRect(if (i == n - 1) today else bar, Offset(x, size.height - h), androidx.compose.ui.geometry.Size(w, h), radius)
            }
        }
    }
}
