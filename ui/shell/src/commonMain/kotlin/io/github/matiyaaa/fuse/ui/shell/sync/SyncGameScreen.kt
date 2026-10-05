package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.GameReport
import io.github.matiyaaa.fuse.sync.RevisionReason
import io.github.matiyaaa.fuse.sync.VersionReport
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
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
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import kotlinx.coroutines.launch

/**
 * One game as the Fuse Sync host keeps it: its play time, in all and by device, sessions and Last
 * Played, and every version of each of its saves (saves, save states, memory cards), newest first:
 * when, on which device, how far in, how big, why it was kept. A version opens into its files,
 * each with its size and where the host keeps it, and can be kept for good.
 */
@Composable
internal fun SyncGameScreen(app: AppState, game: String, name: String) {
    val svc = app.store.sync.service ?: return
    val report = app.syncReport
    val g = report?.games?.firstOrNull { it.game == game }
    val devices by svc.devices.collectAsState()
    val names = devices.associate { it.id to it.name }
    val sel = remember(game) { LinearSelection() }
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Files"), Hint(HintButton.BACK, "Back"))
    }
    val rows = g?.let { versionRows(app, it, report.storePath) }.orEmpty()
    sel.clamp(rows.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (e.action == NavAction.LEFT) NavResult.BLOCKED else handleMenuAction(e, rows, sel)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 980.dp
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val offset = localOffsetMillis(now)
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + Space.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SyncMark(Size.thumbL)
                Spacer(Modifier.width(Space.l))
                Column {
                    FText(g?.name ?: name, Fuse.type.display, maxLines = 1, modifier = Modifier.semantics { heading() })
                    FText(
                        listOfNotNull(
                            g?.platform?.uppercase()?.takeIf { it.isNotEmpty() },
                            g?.playSeconds?.takeIf { it > 0 }?.let { "${playtimeText(it)} played" },
                            g?.sessions?.takeIf { it > 0 }?.let { count(it, "session") },
                            g?.lastPlayed?.let { "last played ${TimeWords.relative(it, now, offset)}" },
                        ).joinToString("  ·  ").ifEmpty { "On the host" },
                        Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(Space.l))
            Row(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    if (g == null || rows.isEmpty()) {
                        FText(if (g == null) "This game isn't on the host any more." else "No saves kept for it yet. Play it with Fuse Sync on and its save is kept here after it closes.", Fuse.type.body, color = Fuse.colors.textMuted, modifier = Modifier.padding(Space.l))
                    } else {
                        MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
                    }
                }
                if (wide && g != null) {
                    Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        if (g.devicePlay.isNotEmpty()) Card("Play time by device", FuseIcons.Clock) { PlayBars(g.devicePlay, names) }
                        Card("Kept on the host", FuseIcons.HardDrive) {
                            for (s in g.slots) {
                                Row {
                                    FText("${s.kind.label}s", Fuse.type.label, maxLines = 1, modifier = Modifier.weight(1f))
                                    FText("${count(s.versions.size, "version")}  ·  ${sizeText(s.bytes)}", Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 1)
                                }
                            }
                            FText("Each file is kept once, by its contents, in ${report.storePath}", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 4)
                        }
                    }
                }
            }
        }
    }
}

/** Every version of every save of [g], grouped by kind, newest first. */
private fun versionRows(app: AppState, g: GameReport, storePath: String): List<MenuAction> {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val offset = localOffsetMillis(now)
    return g.slots.flatMap { s ->
        val section = "${s.kind.label}s  ·  ${count(s.versions.size, "version")}  ·  ${sizeText(s.bytes)}"
        s.versions.map { v ->
            MenuAction(
                "${s.kind.name}.${v.id}",
                TimeWords.relative(v.at, now, offset).replaceFirstChar { it.uppercase() } + " on ${v.device}",
                when {
                    v.current -> FuseIcons.CircleCheck
                    v.kept || v.reason == RevisionReason.MILESTONE -> FuseIcons.Bookmark
                    v.reason == RevisionReason.CONFLICT_COPY -> FuseIcons.GitCompare
                    v.reason == RevisionReason.BEFORE_RESTORE -> FuseIcons.Undo
                    else -> FuseIcons.Save
                },
                detail = listOfNotNull(
                    v.playSeconds.takeIf { it > 0 }?.let { "${playtimeText(it)} in" },
                    count(v.files.size, "file"),
                    reasonText(v),
                ).joinToString("  ·  "),
                trailing = Trailing.Value(if (v.current) "In use  ·  ${sizeText(v.bytes)}" else sizeText(v.bytes)),
                section = section,
                onSelect = { versionFiles(app, g, v, storePath) },
            )
        }
    }
}

private fun reasonText(v: VersionReport): String? = when (v.reason) {
    RevisionReason.PLAYED -> "kept for good".takeIf { v.kept }
    RevisionReason.CONFLICT_COPY -> "the other side of a conflict" + if (v.kept) ", kept for good" else ""
    RevisionReason.BEFORE_RESTORE -> "kept before a restore" + if (v.kept) ", kept for good" else ""
    RevisionReason.MILESTONE -> "kept for good"
}

/** A version's files: each one's name in the save, its size, and where the host keeps it. */
private fun versionFiles(app: AppState, g: GameReport, v: VersionReport, storePath: String) {
    val svc = app.store.sync.service ?: return
    val kept = v.kept || v.reason == RevisionReason.MILESTONE
    app.choice = ChoiceSpec(
        title = "${g.name}, saved on ${v.device}",
        icon = FuseIcons.FileText,
        message = "Kept on the host in $storePath, each file once by its contents",
        options = v.files.map { f ->
            MenuAction("f.${f.path}", f.path, FuseIcons.File, detail = "Kept as ${f.stored}", trailing = Trailing.Value(sizeText(f.bytes)), onSelect = {})
        } + MenuAction(
            "keep", if (kept) "Stop Keeping for Good" else "Keep for Good", FuseIcons.Bookmark,
            detail = if (kept) "It goes back to being tidied away in time" else "Never tidied away, however many come after",
            section = "",
            onSelect = {
                app.choice = null
                app.scope.launch {
                    svc.keepVersion(v.id, !kept)
                        .onSuccess { app.toasts.show(if (kept) "Back to being tidied in time" else "Kept for good", ToastKind.SUCCESS); app.syncReport = svc.report() ?: app.syncReport }
                        .onFailure { app.toasts.show(it.message ?: "Couldn't change that", ToastKind.ERROR) }
                }
            },
        ),
    )
}
