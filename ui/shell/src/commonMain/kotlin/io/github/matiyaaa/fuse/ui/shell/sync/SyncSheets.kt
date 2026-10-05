package io.github.matiyaaa.fuse.ui.shell.sync

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.RevisionReason
import io.github.matiyaaa.fuse.sync.SaveKind
import io.github.matiyaaa.fuse.sync.SaveQuery
import io.github.matiyaaa.fuse.sync.SaveVersion
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.ViewTab
import io.github.matiyaaa.fuse.ui.shell.components.ViewTabs
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import kotlinx.coroutines.launch

/**
 * "Add a device": the host's code, large, with how to use it. A fresh code each time it opens; B,
 * A or a tap outside closes it.
 */
@Composable
internal fun PairingOverlay(app: AppState) {
    val open = app.pairing
    val svc = app.store.sync.service
    var code by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open) {
        if (!open || svc == null) return@LaunchedEffect
        app.platform.sounds.play(SoundCue.OPEN)
        code = null
        code = svc.newPairingCode()
    }
    if (open) {
        InputLayer(priority = LayerPriority.DIALOG + 1, modal = true) { e ->
            when (e.action) {
                NavAction.BACK, NavAction.SELECT -> { app.pairing = false; app.platform.sounds.play(SoundCue.CLOSE); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }
    Overlay(visible = open && svc != null, onDismiss = { app.pairing = false }, edge = OverlayEdge.CENTER) {
        val host by (svc ?: return@Overlay).host.collectAsState()
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val room = maxWidth - Space.l * 2
            val compact = maxHeight < 520.dp
            Panel(Modifier.widthIn(min = minOf(440.dp, room), max = minOf(620.dp, room))) {
                Column(Modifier.padding(if (compact) Space.l else Space.xl)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SyncMark(if (compact) 40.dp else 48.dp)
                        Spacer(Modifier.width(Space.l))
                        Column {
                            FText("Add a Device", Fuse.type.title, maxLines = 1, modifier = Modifier.semantics { heading() })
                            FText("To ${host?.name ?: "this host"}", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(Space.l))
                    PairingCard(app, code, host?.addresses.orEmpty(), compact)
                    Spacer(Modifier.height(Space.l))
                    FuseButton("Done", selected = true, onClick = { app.pairing = false }, icon = FuseIcons.Check, modifier = Modifier.align(Alignment.End))
                }
            }
        }
    }
}

/** A version of a save, as its row reads: when, where, and how far in. */
private fun versionDetail(v: SaveVersion, now: Long, offset: Long): String = listOfNotNull(
    "On ${v.device}",
    v.playSeconds.takeIf { it > 0 }?.let { "${playtimeText(it)} in" },
    sizeText(v.bytes),
).joinToString("  ·  ")

/** The page's own state while it is open. */
private class HistoryState {
    var kind by mutableStateOf(SaveKind.SAVE)
    val sel = LinearSelection()
}

/**
 * A game's saves through time, from every device: each version with when and where it was saved
 * and how long the game had been played by then. The one in use is marked; any other can be put
 * back (what is here now is kept as a version first), and any can be kept for good, past the
 * tidying that keeps recent versions, then one a day, then one a week.
 */
@Composable
internal fun SaveHistoryScreen(app: AppState, game: GameId, title: String) {
    val svc = app.store.sync.service ?: return
    val page = rememberRouteState(app.navigator, "sync.history.${game.value}") { HistoryState() }
    var query by remember { mutableStateOf<SaveQuery?>(null) }
    var versions by remember { mutableStateOf<List<SaveVersion>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    val c = Fuse.colors
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Options"), Hint(HintButton.PREV, "Saves"), Hint(HintButton.NEXT, "States"), Hint(HintButton.BACK, "Back"))
        query = app.store.sync.saveQuery(game)
    }
    LaunchedEffect(query, page.kind, round) {
        val q = query ?: return@LaunchedEffect
        versions = null
        versions = svc.versions(q, page.kind)
    }
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val offset = localOffsetMillis(now)
    val rows = versions.orEmpty().map { v ->
        MenuAction(
            v.id,
            TimeWords.relative(v.at, now, offset).replaceFirstChar { it.uppercase() },
            when {
                v.current -> FuseIcons.CircleCheck
                v.reason == RevisionReason.MILESTONE -> FuseIcons.Bookmark
                v.reason == RevisionReason.CONFLICT_COPY -> FuseIcons.GitCompare
                v.reason == RevisionReason.BEFORE_RESTORE -> FuseIcons.Undo
                else -> FuseIcons.Save
            },
            detail = versionDetail(v, now, offset),
            trailing = when {
                v.current -> Trailing.Value("In use")
                v.reason == RevisionReason.MILESTONE -> Trailing.Value("Kept")
                v.reason == RevisionReason.CONFLICT_COPY -> Trailing.Value("Other side of a conflict")
                v.reason == RevisionReason.BEFORE_RESTORE -> Trailing.Value("Before a restore")
                else -> Trailing.Chevron
            },
            onSelect = { versionMenu(app, query ?: return@MenuAction, page.kind, v, title) { round++ } },
        )
    }
    page.sel.clamp(rows.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.PREVIOUS_SECTION -> { page.kind = SaveKind.SAVE; NavResult.MOVED }
            NavAction.NEXT_SECTION -> { page.kind = SaveKind.STATE; NavResult.MOVED }
            NavAction.LEFT -> NavResult.BLOCKED
            else -> handleMenuAction(e, rows, page.sel)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(Size.hudHeight + Space.l))
        Row(Modifier.padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(Size.thumbL).clip(RoundedCornerShape(Size.thumbL * 0.28f)).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.History, size = Size.iconL, tint = c.accent)
            }
            Spacer(Modifier.width(Space.l))
            Column {
                FText("Save History", Fuse.type.display, maxLines = 1, modifier = Modifier.semantics { heading() })
                FText(title, Fuse.type.body, color = c.textMuted, maxLines = 1)
            }
        }
        Spacer(Modifier.height(Space.l))
        ViewTabs(
            listOf(ViewTab("Saves", icon = FuseIcons.Save), ViewTab("Save States", icon = FuseIcons.Layers)),
            active = if (page.kind == SaveKind.SAVE) 0 else 1,
            focused = null,
            onSelect = { page.kind = if (it == 0) SaveKind.SAVE else SaveKind.STATE },
        )
        Spacer(Modifier.height(Space.m))
        Panel(Modifier.padding(horizontal = Space.gutter).widthIn(max = Size.touch * 18).weight(1f).fillMaxHeight().padding(bottom = Size.hintHeight + Space.s)) {
            when {
                query == null && versions == null -> Center { Spinner(size = 24.dp, color = c.textMuted) }
                versions == null -> Center { Spinner(size = 24.dp, color = c.textMuted) }
                rows.isEmpty() -> EmptyState(
                    FuseIcons.History,
                    if (page.kind == SaveKind.SAVE) "No saves yet" else "No save states yet",
                    message = "Play it with Fuse Sync on, and each time it closes its save is kept here, from every device.",
                )
                else -> MenuList(rows, page.sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
            }
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) { content() }
}

private fun versionMenu(app: AppState, query: SaveQuery, kind: SaveKind, v: SaveVersion, title: String, changed: () -> Unit) {
    val svc = app.store.sync.service ?: return
    val kept = v.reason == RevisionReason.MILESTONE
    app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
        title = "${kind.label} from ${v.device}",
        icon = FuseIcons.History,
        options = listOfNotNull(
            MenuAction("restore", "Use This ${kind.label}", FuseIcons.ArchiveRestore, detail = "What is here now is kept as a version first", onSelect = {
                app.choice = null
                app.confirm = ConfirmSpec(
                    "Use this ${kind.label.lowercase()} of $title?",
                    "The ${kind.label.lowercase()} on this device now is kept in the history first, so you can come back to it.",
                    "Use It",
                ) {
                    app.scope.launch {
                        svc.restore(query, kind, v.id)
                            .onSuccess { app.toasts.show("$title will start from it", ToastKind.SUCCESS); changed() }
                            .onFailure { app.toasts.show(it.message ?: "Couldn't put it back", ToastKind.ERROR) }
                    }
                }
            }).takeIf { !v.current },
            MenuAction(
                "keep", if (kept) "Stop Keeping for Good" else "Keep for Good", FuseIcons.Bookmark,
                detail = if (kept) "It goes back to being tidied away in time, like the rest" else "Never tidied away, however many come after",
                onSelect = {
                    app.choice = null
                    app.scope.launch { svc.keepVersion(v.id, !kept).onSuccess { changed() } }
                },
            ),
        ),
    )
}
