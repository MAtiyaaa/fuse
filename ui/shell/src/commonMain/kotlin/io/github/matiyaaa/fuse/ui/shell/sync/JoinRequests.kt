package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.JoinAsk
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import kotlinx.coroutines.launch

/**
 * A device asking to join Fuse Sync, on the host and on every device already connected: who asks,
 * and the six digits its screen shows. Whoever is here checks the two match, then lets it in or
 * turns it away. Not Now (or B) only puts this card away; the request runs out by itself after a
 * few minutes, or someone on another device answers it.
 */
@Composable
internal fun JoinRequestOverlay(app: AppState) {
    val svc = app.store.sync.service ?: return
    val requests by svc.joinRequests.collectAsState()
    var putAway by remember { mutableStateOf(emptySet<String>()) }
    val ask = requests.firstOrNull { it.id !in putAway }.takeIf { app.store.sync.inUse }
    var shown by remember { mutableStateOf<JoinAsk?>(null) }
    if (ask != null) shown = ask
    var choice by remember(ask?.id) { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(ask?.id) { if (ask != null) app.platform.sounds.play(SoundCue.OPEN) }

    fun answer(allow: Boolean) {
        val a = ask ?: return
        if (working) return
        working = true
        app.scope.launch {
            svc.answerJoin(a.id, allow)
                .onSuccess {
                    if (allow) app.toasts.show("${a.deviceName} is in", ToastKind.SUCCESS, icon = FuseIcons.CircleCheck)
                    else app.toasts.show("${a.deviceName} was turned away")
                }
                .onFailure { app.toasts.show(it.message ?: "That request ran out", ToastKind.WARNING) }
            putAway = putAway + a.id
            working = false
        }
    }

    fun later() {
        val a = ask ?: return
        putAway = putAway + a.id
        app.platform.sounds.play(SoundCue.CLOSE)
    }

    if (ask != null) {
        InputLayer(priority = LayerPriority.DIALOG + 2, modal = true) { e ->
            when (e.action) {
                NavAction.LEFT -> if (choice > 0) { choice--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (choice < 2) { choice++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> {
                    when (choice) {
                        0 -> answer(true)
                        1 -> answer(false)
                        else -> later()
                    }
                    NavResult.CONSUMED
                }
                NavAction.BACK -> { later(); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }
    Overlay(visible = ask != null, onDismiss = { later() }, edge = OverlayEdge.CENTER) {
        val a = shown ?: return@Overlay
        val c = Fuse.colors
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val room = maxWidth - Space.l * 2
            val compact = maxHeight < 520.dp
            Panel(Modifier.widthIn(min = minOf(440.dp, room), max = minOf(600.dp, room))) {
                Column(Modifier.padding(if (compact) Space.l else Space.xl)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(if (compact) 40.dp else 48.dp).clip(RoundedCornerShape(14.dp)).background(c.accent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) { FuseIcon(platformIcon(a.platform), size = Size.iconM, tint = c.accent) }
                        Spacer(Modifier.width(Space.l))
                        Column(Modifier.weight(1f)) {
                            FText("${a.deviceName} wants to join", Fuse.type.title, maxLines = 2, modifier = Modifier.semantics { heading() })
                            FText("Fuse Sync, from ${platformWord(a.platform)}", Fuse.type.body, color = c.textMuted, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(Space.l))
                    FText("Let it in only if ${a.deviceName} shows this same number:", Fuse.type.body, color = c.textMuted, maxLines = 3)
                    Spacer(Modifier.height(Space.m))
                    MatchNumber(a.match, compact)
                    Spacer(Modifier.height(Space.xl))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                        FuseButton("Let It In", selected = choice == 0, onClick = { choice = 0; answer(true) }, icon = FuseIcons.Check, kind = ButtonKind.PRIMARY, loading = working && choice == 0)
                        FuseButton("Turn Away", selected = choice == 1, onClick = { choice = 1; answer(false) }, icon = FuseIcons.CircleX)
                        FuseButton("Not Now", selected = choice == 2, onClick = { later() })
                    }
                }
            }
        }
    }
}

/** The six digits both screens show, set large in two groups of three. */
@Composable
internal fun MatchNumber(match: String, compact: Boolean, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) Space.xs else Space.s), verticalAlignment = Alignment.CenterVertically) {
        match.forEach { ch ->
            if (ch == ' ') {
                Spacer(Modifier.width(if (compact) Space.s else Space.m))
            } else {
                Box(
                    Modifier.size(if (compact) 42.dp else 54.dp, if (compact) 52.dp else 66.dp).clip(RoundedCornerShape(12.dp))
                        .background(c.text.copy(alpha = 0.07f)).border(1.dp, c.hairline, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    FText(ch.toString(), Fuse.type.numericLarge.tabular(), maxLines = 1, fit = true, fitMin = 0.6f)
                }
            }
        }
    }
}

private fun platformIcon(platform: String) = when (platform.uppercase()) {
    "ANDROID" -> FuseIcons.Smartphone
    else -> FuseIcons.MonitorSmartphone
}

private fun platformWord(platform: String) = when (platform.uppercase()) {
    "ANDROID" -> "an Android device"
    "WINDOWS" -> "a Windows computer"
    "MACOS" -> "a Mac"
    "LINUX" -> "a Linux computer"
    else -> "a device"
}
