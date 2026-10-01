package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuArt
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
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
            val logo = g.card.art.logo
            add(MenuAction(
                "g${g.card.id.value}", g.card.title, if (on) FuseIcons.SquareCheck else FuseIcons.Square,
                detail = "${g.card.platformShort}  ·  ${g.files} ${if (g.files == 1) "file" else "files"}",
                trailing = Trailing.Value(bytesText(g.bytes)),
                // The game's logo, or its square art when it has no logo.
                art = MenuArt(logo ?: g.card.art.tile, square = logo == null, fallbackTitle = g.card.title, accent = g.card.accent),
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 760.dp
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + Space.m))
            Row(verticalAlignment = Alignment.Bottom) {
                FText("Storage", Fuse.type.title, maxLines = 1)
                Spacer(Modifier.width(Space.l))
                FText(summaryLine(u), Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
                if (u != null && !u.finished) {
                    Spacer(Modifier.width(Space.l))
                    io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar(
                        if (u.total > 0) u.measured.toFloat() / u.total else null,
                        Modifier.width(140.dp).padding(bottom = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(Space.l))
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    Column(Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                        Volumes(u)
                        Spacer(Modifier.height(Size.hintHeight + Space.l))
                    }
                    MenuList(rows, sel, modifier = Modifier.weight(0.6f), showSelection = app.focusZone == FocusZone.CONTENT)
                }
            } else {
                MenuList(rows, sel, modifier = Modifier.fillMaxWidth(), showSelection = app.focusZone == FocusZone.CONTENT, header = { Column { Volumes(u); Spacer(Modifier.height(Space.l)) } })
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

@Composable
private fun Volumes(u: StorageUsage?) {
    val volumes = u?.volumes.orEmpty()
    if (volumes.isEmpty()) {
        FText(if (u == null) "Looking at your drives" else "Fuse can't tell how much space these drives have.", Fuse.type.body, color = Fuse.colors.textMuted)
        return
    }
    volumes.forEach { VolumeCard(it) }
}

/** A drive as a bar: each system's games in its colour, then other files, then free space. */
@Composable
private fun VolumeCard(v: VolumeUsage) {
    val c = Fuse.colors
    val used = (v.totalBytes - v.freeBytes).coerceAtLeast(0)
    val other = (used - v.gamesBytes).coerceAtLeast(0)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.control)).background(c.text.copy(alpha = 0.06f)).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        SectionLabel(v.label)
        Row(verticalAlignment = Alignment.Bottom) {
            FText("${bytesText(v.freeBytes)} free", Fuse.type.titleSmall, maxLines = 1)
            Spacer(Modifier.width(Space.s))
            FText("of ${bytesText(v.totalBytes)}", Fuse.type.label, color = c.textMuted, maxLines = 1)
        }
        val systems = v.systems.take(6)
        val rest = v.systems.drop(6).sumOf { it.bytes }
        val track = c.text.copy(alpha = 0.1f)
        val otherColor = c.text.copy(alpha = 0.32f)
        Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
            drawRoundRect(track, cornerRadius = CornerRadius(7.dp.toPx()))
            val total = v.totalBytes.coerceAtLeast(1).toFloat()
            var x = 0f
            fun segment(bytes: Long, color: androidx.compose.ui.graphics.Color) {
                val w = size.width * (bytes / total)
                if (w <= 0f) return
                drawRect(color, topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
                x += w
            }
            systems.forEach { segment(it.bytes, it.accent.toColor()) }
            segment(rest, otherColor)
            segment(other, otherColor.copy(alpha = 0.2f))
        }
        Spacer(Modifier.height(Space.xs))
        systems.forEach { Legend(it.accent.toColor(), it.name, it.bytes) }
        if (rest > 0) Legend(otherColor, "Other systems", rest)
        Legend(otherColor.copy(alpha = 0.2f), "Everything else on the drive", other)
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String, bytes: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Space.s))
        FText(label, Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
        FText(bytesText(bytes), Fuse.type.label, maxLines = 1)
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
