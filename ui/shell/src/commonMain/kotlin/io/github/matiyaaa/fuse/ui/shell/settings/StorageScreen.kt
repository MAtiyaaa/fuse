package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuArt
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Skeleton
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonRow
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonText
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.platform.StorageState
import io.github.matiyaaa.fuse.ui.shell.store.StorageUsage
import io.github.matiyaaa.fuse.ui.shell.store.VolumeUsage
import kotlinx.coroutines.launch

/**
 * Storage: each drive the library is on (games by system, other files, free space) and every game
 * by what it takes, largest first. Games can be picked and their files deleted, after a
 * confirmation naming what goes. Only here, on the device; Phone Link can't delete anything.
 */
@Composable
fun StorageScreen(app: AppState) {
    val store = app.store
    val usage by store.storage.usage.collectAsState()
    val access by app.platform.storage.state.collectAsState()
    val canDelete = access == StorageState.GRANTED || access == StorageState.NOT_NEEDED
    val sel = rememberRouteState(app.navigator, "storage") { LinearSelection() }
    var system by remember { mutableStateOf<PlatformId?>(null) }
    var picked by remember { mutableStateOf(setOf<GameId>()) }

    LaunchedEffect(Unit) {
        store.storage.refresh()
        app.hero = null
    }
    LaunchedEffect(picked.isEmpty()) {
        app.hints = listOf(Hint(HintButton.CONFIRM, "Select"), Hint(HintButton.OPTIONS, "Game page"), Hint(HintButton.BACK, "Back"))
    }

    val u = usage
    val shown = u?.games.orEmpty().filter { system == null || it.card.platformId == system }
    val chosen = u?.games.orEmpty().filter { it.card.id in picked }
    // The rows above the games never come and go while you pick, so the focus stays on its game.
    val rows = buildList {
        if (!canDelete) {
            add(MenuAction(
                "access", "Deleting needs All files access", FuseIcons.Lock,
                detail = "Fuse can show sizes, but deleting files needs permission. Select to allow",
                trailing = Trailing.Chevron,
                onSelect = { app.platform.storage.request() },
            ))
        }
        val bytes = chosen.sumOf { it.bytes }
        add(MenuAction(
            "delete",
            if (chosen.isEmpty()) "Delete selected games" else "Delete ${chosen.size} ${if (chosen.size == 1) "game" else "games"} (${bytesText(bytes)})",
            FuseIcons.Trash,
            destructive = chosen.isNotEmpty(),
            detail = if (chosen.isEmpty()) null else "Their files go from your storage. You'll be asked first. X clears the selection",
            unavailableReason = when {
                chosen.isEmpty() -> "Select games below, then delete their files here"
                !canDelete -> "Allow All files access first"
                else -> null
            },
            onSelect = { confirmDelete(app, chosen.map { it.card.id to it.card.title }, chosen.sumOf { it.files }, bytes) { picked = emptySet() } },
        ))
        val systems = u?.games.orEmpty().map { it.card.platformId }.distinct()
        add(MenuAction(
            "filter", "Showing", FuseIcons.Filter,
            trailing = Trailing.Value(system?.let { store.library.platforms.value.firstOrNull { p -> p.platform.id == it }?.platform?.shortName ?: it.value } ?: "All systems"),
            onSelect = {
                app.choice = ChoiceSpec(
                    title = "Show games from",
                    options = listOf(MenuAction("all", "All systems", FuseIcons.Layers, trailing = Trailing.Check(system == null), onSelect = { system = null; app.choice = null })) +
                        systems.map { id ->
                            val name = store.library.platforms.value.firstOrNull { it.platform.id == id }?.platform?.name ?: id.value
                            MenuAction("s.${id.value}", name, null, trailing = Trailing.Check(system == id), onSelect = { system = id; app.choice = null; sel.index = 0 })
                        },
                )
            },
        ))
        shown.forEach { g ->
            val on = g.card.id in picked
            add(MenuAction(
                "g${g.card.id.value}", g.card.title, if (on) FuseIcons.SquareCheck else FuseIcons.Square,
                detail = "${g.card.platformShort}  ·  ${g.files} ${if (g.files == 1) "file" else "files"}",
                trailing = Trailing.Value(bytesText(g.bytes)),
                // Square art for every game, so titles line up and the list stays compact.
                art = MenuArt(g.card.art.tile, square = true, fallbackTitle = g.card.title, accent = g.card.accent, wide = false),
                onSelect = { picked = if (on) picked - g.card.id else picked + g.card.id },
            ))
        }
    }
    sel.clamp(rows.size)

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.CONTEXT -> {
                // X on a game opens its page; anywhere else it clears the selection.
                val id = rows.getOrNull(sel.index)?.id?.takeIf { it.startsWith("g") }?.removePrefix("g")?.toLongOrNull()
                if (id != null) app.go(Route.GameInfo(GameId(id))) else picked = emptySet()
                NavResult.ACTIVATED
            }
            else -> handleMenuAction(e, rows, sel)
        }
    }

    val empty = u != null && u.finished && u.games.isEmpty()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > WIDE_FROM
        val short = maxHeight < SHORT_BELOW
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) Space.gutter else Space.gutterCompact)) {
            Spacer(Modifier.height(Size.hudHeight + if (short) Space.s else Space.l))
            SettingsPageHeading("Storage", summaryLine(u), short, Modifier.reveal(0)) {
                if (u != null && !u.finished) {
                    ProgressBar(if (u.total > 0) u.measured.toFloat() / u.total else null, Modifier.width(MEASURE_BAR))
                }
            }
            Spacer(Modifier.height(if (short) Space.m else Space.l))
            val list: @Composable (Modifier, (@Composable () -> Unit)?) -> Unit = { m, header ->
                Panel(m) {
                    Column(Modifier.fillMaxSize()) {
                        MenuList(
                            rows, sel,
                            showSelection = app.focusZone == FocusZone.CONTENT,
                            header = header,
                            fill = !(u == null || empty),
                            modifier = Modifier.padding(Space.s).menuEdges(rows, sel.index),
                        )
                        when {
                            // Rows shaped like the games still being measured.
                            u == null -> Column(Modifier.padding(horizontal = Space.s)) { repeat(SKELETON_ROWS) { SkeletonRow() } }
                            empty -> Box(Modifier.fillMaxSize().padding(Space.l), contentAlignment = Alignment.Center) {
                                EmptyState(
                                    FuseIcons.HardDrive, "No games to measure",
                                    message = "Games show here, largest first, once Fuse finds them in your folders.",
                                    compact = true,
                                )
                            }
                        }
                    }
                }
            }
            if (wide) {
                // The drives scroll along with the list: moving down the games moves down the drives
                // by the same share, so every part of them can be read without touching the screen.
                val drives = rememberScrollState()
                LaunchedEffect(sel.index, rows.size, drives.maxValue) {
                    val share = if (rows.size <= 1) 0f else sel.index.toFloat() / (rows.size - 1)
                    drives.animateScrollTo((drives.maxValue * share).toInt())
                }
                Row(Modifier.fillMaxSize().padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    Column(
                        Modifier.weight(0.4f).fillMaxHeight().fadingEdges(drives, top = Space.l, bottom = Space.xl).verticalScroll(drives).reveal(1),
                        verticalArrangement = Arrangement.spacedBy(Space.l),
                    ) {
                        Volumes(u, nested = false)
                    }
                    list(Modifier.weight(0.6f).fillMaxHeight().reveal(2), null)
                }
            } else {
                list(
                    Modifier.fillMaxSize().padding(bottom = Size.hintHeight + Space.s).reveal(1),
                ) { Column(Modifier.padding(top = Space.s, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) { Volumes(u, nested = true) } }
            }
        }
    }
}

private fun summaryLine(u: StorageUsage?): String {
    if (u == null) return "Measuring your games"
    if (!u.finished) return "Measuring ${u.measured} of ${u.total} games"
    val games = u.games.sumOf { it.bytes }
    return "${u.games.size} games take ${bytesText(games)}"
}

/** Every drive the library is on, or what stands in for them while they're unknown. */
@Composable
private fun Volumes(u: StorageUsage?, nested: Boolean) {
    val volumes = u?.volumes.orEmpty()
    when {
        u == null -> VolumeSkeleton(nested)
        volumes.isEmpty() -> Panel(Modifier.fillMaxWidth(), raised = nested, shadow = !nested) {
            Box(Modifier.fillMaxWidth().padding(Space.l), contentAlignment = Alignment.Center) {
                EmptyState(
                    FuseIcons.HardDrive, "Drive space unknown",
                    message = "This system doesn't say how big these drives are. Each game's size is still below.",
                    compact = true,
                )
            }
        }
        else -> volumes.forEach { VolumeCard(it, nested) }
    }
}

/**
 * A drive: how much is free, a bar of what fills it (games, everything else, free space), then the
 * games on it by system, each with a bar against the largest, in the system's own colour.
 */
@Composable
private fun VolumeCard(v: VolumeUsage, nested: Boolean) {
    val c = Fuse.colors
    val used = (v.totalBytes - v.freeBytes).coerceAtLeast(0)
    val other = (used - v.gamesBytes).coerceAtLeast(0)
    val games = c.accent
    val rest = c.text.copy(alpha = if (c.isDark) 0.34f else 0.3f)
    Panel(Modifier.fillMaxWidth(), raised = nested, shadow = !nested) {
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SectionLabel(v.label, icon = FuseIcons.HardDrive)
            Row(verticalAlignment = Alignment.Bottom) {
                FText(bytesText(v.freeBytes), Fuse.type.title.tabular(), maxLines = 1, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(Space.s))
                FText("free of ${bytesText(v.totalBytes)}", Fuse.type.label, color = c.textMuted, maxLines = 1, modifier = Modifier.alignByBaseline())
            }
            UsageBar(listOf(v.gamesBytes to games, other to rest), v.totalBytes, Modifier.fillMaxWidth().padding(vertical = Space.xxs))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
                Key(games, "Games", v.gamesBytes)
                Key(rest, "Everything else", other)
                Key(null, "Free", v.freeBytes)
            }
            if (v.systems.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                SectionLabel("Games by system", count = v.systems.size.toString(), rule = true)
                Spacer(Modifier.height(Space.xxs))
                val shown = v.systems.take(SYSTEMS_SHOWN)
                val others = v.systems.drop(SYSTEMS_SHOWN).sumOf { it.bytes }
                val largest = (shown.maxOfOrNull { it.bytes } ?: 0L).coerceAtLeast(others).coerceAtLeast(1)
                shown.forEach { SystemBar(it.accent.toColor(), it.name, it.bytes, largest) }
                if (others > 0) SystemBar(rest, "Other systems", others, largest)
            }
        }
    }
}

/** One system's share of the games on a drive: its name and size over a bar in its colour. */
@Composable
private fun SystemBar(color: Color, name: String, bytes: Long, largest: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xxs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FText(name, Fuse.type.label, color = Fuse.colors.text, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.s))
            FText(bytesText(bytes), Fuse.type.numericSmall, color = Fuse.colors.textMuted, maxLines = 1)
        }
        UsageBar(listOf(bytes to color), largest, Modifier.fillMaxWidth())
    }
}

/**
 * A rounded track with [parts] laid along it in order, each [bytes] of [total]. Every part shows at
 * least as a dot, so a few kilobytes of games on a large drive still read as something. The parts
 * ease to new sizes.
 */
@Composable
private fun UsageBar(parts: List<Pair<Long, Color>>, total: Long, modifier: Modifier) {
    val c = Fuse.colors
    val track = c.text.copy(alpha = if (c.isDark) 0.1f else 0.08f)
    val shares = parts.map { (b, _) -> (b.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) }
    val eased = shares.mapIndexed { i, f ->
        animateFloatAsState(f, Fuse.motion.value(), label = "usage$i")
    }
    val colors = parts.map { it.second }
    val height = Size.track * if (parts.size > 1) 2 else 1
    Spacer(
        modifier.height(height).drawBehind {
            val h = size.height
            val r = CornerRadius(h / 2)
            drawRoundRect(track, cornerRadius = r)
            var x = 0f
            eased.forEachIndexed { i, f ->
                val share = f.value
                if (shares[i] <= 0f) return@forEachIndexed
                val w = (size.width * share).coerceAtLeast(h).coerceAtMost(size.width - x)
                if (w <= 0f) return@forEachIndexed
                drawRoundRect(colors[i], Offset(x, 0f), androidx.compose.ui.geometry.Size(w, h), r)
                // Neighbouring parts meet edge to edge, with a hairline of the track between them.
                x += w + if (i < eased.lastIndex) 1.dp.toPx() else 0f
            }
        },
    )
}

/** A legend entry: a dot (a ring for free space) and what it stands for, with its size. */
@Composable
private fun Key(color: Color?, label: String, bytes: Long) {
    val c = Fuse.colors
    val ring = c.text.copy(alpha = 0.4f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier.size(Size.dot).drawBehind {
                if (color != null) drawCircle(color) else drawCircle(ring, size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            },
        )
        Spacer(Modifier.width(Space.xs + Space.xxs))
        FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        Spacer(Modifier.width(Space.xs))
        FText(bytesText(bytes), Fuse.type.numericSmall, color = c.text, maxLines = 1)
    }
}

/** A drive card's shape while Fuse looks at the drives. */
@Composable
private fun VolumeSkeleton(nested: Boolean) {
    Panel(Modifier.fillMaxWidth(), raised = nested, shadow = !nested) {
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Skeleton(Modifier.fillMaxWidth(0.4f).height(Space.m))
            Skeleton(Modifier.fillMaxWidth(0.6f).height(Space.xl))
            Skeleton(Modifier.fillMaxWidth().height(Size.track * 2))
            SkeletonText(lines = 3, style = Fuse.type.label, lastLineFraction = 0.5f)
        }
    }
}

/** Asks before deleting, naming what goes. */
private fun confirmDelete(app: AppState, games: List<Pair<GameId, String>>, files: Int, bytes: Long, onDone: () -> Unit) {
    val names = games.take(4).joinToString(", ") { it.second } + if (games.size > 4) " and ${games.size - 4} more" else ""
    app.confirm = ConfirmSpec(
        title = "Delete ${games.size} ${if (games.size == 1) "game" else "games"}?",
        message = "$names. Deletes $files ${if (files == 1) "file" else "files"} (${bytesText(bytes)}): every disc, track and folder of " +
            "${if (games.size == 1) "this game" else "these games"}. Saves your emulators keep elsewhere stay. This can't be undone.",
        confirmLabel = "Delete files",
        destructive = true,
    ) {
        app.scope.launch {
            val report = app.store.storage.delete(games.map { it.first })
            onDone()
            app.toasts.show(
                when {
                    report.failed.isEmpty() -> "Deleted ${report.deleted} ${if (report.deleted == 1) "game" else "games"} and freed ${bytesText(report.freedBytes)}"
                    report.deleted == 0 -> "Couldn't delete ${report.failed.joinToString(", ")}. Check that Fuse may change these folders"
                    else -> "Deleted ${report.deleted}; couldn't delete ${report.failed.joinToString(", ")}"
                },
            )
        }
    }
}

/** Drives sit beside the games from this width; the measuring bar's length; rows shown while loading. */
private val WIDE_FROM = 760.dp
private val SHORT_BELOW = 560.dp
private val MEASURE_BAR = 140.dp
private const val SKELETON_ROWS = 6

/** Systems listed by name on each drive; the rest share one bar. */
private const val SYSTEMS_SHOWN = 6
