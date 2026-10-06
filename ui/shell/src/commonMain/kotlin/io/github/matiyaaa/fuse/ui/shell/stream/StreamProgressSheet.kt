package io.github.matiyaaa.fuse.ui.shell.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.store.StreamProgress

/**
 * Starting a stream: asking the computer, waking it (with the seconds it has left), and handing
 * over to Moonlight. Back cancels at any point; a computer that didn't answer says why and offers to
 * try again. It closes by itself once Moonlight starts.
 */
@Composable
internal fun StreamProgressOverlay(app: AppState) {
    val ops = app.store.streaming
    val progress by ops.progress.collectAsState()
    var shown by remember { mutableStateOf(progress) }
    if (progress != null) shown = progress
    var button by remember(progress?.let { it::class }) { mutableIntStateOf(0) }
    val failed = progress as? StreamProgress.Failed
    fun retry() {
        val f = failed ?: return
        val host = ops.hosts.value.firstOrNull { it.host.name == f.host } ?: return
        ops.stream(host.host.id, f.app)
    }
    // Once Moonlight has it, the sheet goes.
    LaunchedEffect(progress) {
        if (progress is StreamProgress.Starting) {
            kotlinx.coroutines.delay(1_500)
            if (ops.progress.value is StreamProgress.Starting) ops.cancel()
        }
    }
    if (progress != null) {
        InputLayer(priority = LayerPriority.DIALOG + 1, modal = true) { e ->
            when (e.action) {
                NavAction.BACK -> { ops.cancel(); NavResult.CONSUMED }
                NavAction.LEFT -> { button = 0; NavResult.CONSUMED }
                NavAction.RIGHT -> { if (failed?.canRetry == true) button = 1; NavResult.CONSUMED }
                NavAction.SELECT -> { if (failed?.canRetry == true && button == 1) retry() else ops.cancel(); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }
    Overlay(visible = progress != null, onDismiss = { ops.cancel() }, edge = OverlayEdge.CENTER) {
        val p = shown ?: return@Overlay
        val c = Fuse.colors
        Panel(Modifier.widthIn(min = 320.dp, max = 520.dp)) {
            Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(Fuse.geometry.control)).background((if (p is StreamProgress.Failed) c.warning else c.accent).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        if (p is StreamProgress.Failed) FuseIcon(FuseIcons.Warning, size = Size.iconL, tint = c.warning)
                        else if (p is StreamProgress.Waking) FuseIcon(FuseIcons.Power, size = Size.iconL, tint = c.accent)
                        else Spinner(size = 26.dp, color = c.accent)
                    }
                    Spacer(Modifier.width(Space.l))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        FText(
                            when (p) {
                                is StreamProgress.Checking -> "Finding ${p.host}"
                                is StreamProgress.Waking -> "Waking ${p.host}"
                                is StreamProgress.Starting -> "Starting ${p.app}"
                                is StreamProgress.Failed -> "${p.app} didn't start"
                            },
                            Fuse.type.title, maxLines = 2, modifier = Modifier.semantics { heading() },
                        )
                        FText(
                            when (p) {
                                is StreamProgress.Checking -> "Asking it whether it's ready to stream."
                                is StreamProgress.Waking -> "It was asleep. Waiting for it to come up: ${p.secondsLeft} seconds at most."
                                is StreamProgress.Starting -> "Handing over to Moonlight."
                                is StreamProgress.Failed -> p.message
                            },
                            Fuse.type.body, color = c.textMuted, maxLines = 5,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    FuseButton(if (failed != null) "Close" else "Cancel", selected = button == 0, onClick = { ops.cancel() }, kind = ButtonKind.SECONDARY)
                    if (failed?.canRetry == true) FuseButton("Try Again", selected = button == 1, onClick = { retry() }, icon = FuseIcons.RefreshCcw, kind = ButtonKind.PRIMARY)
                }
            }
        }
    }
}
