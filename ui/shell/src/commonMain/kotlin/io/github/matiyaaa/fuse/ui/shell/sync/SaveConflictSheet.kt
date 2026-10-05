package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.SaveConflict
import io.github.matiyaaa.fuse.sync.SaveSide
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
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import kotlinx.coroutines.launch
import kotlinx.datetime.offsetAt

/**
 * A save conflict on screen: the game it is about, and what to do once the person picks
 * ([onSettled] starts the game; nothing happens on Cancel).
 */
class SaveConflictSpec(val conflict: SaveConflict, val onSettled: () -> Unit)

/**
 * "Which save should it use?": both sides of a save conflict, side by side (stacked when narrow),
 * each with where and when it was saved and how long the game had been played by then. Nothing is
 * lost either way: the one not picked stays in the game's save history. Left and Right choose, A
 * picks, B cancels (the game doesn't start).
 */
@Composable
internal fun SaveConflictOverlay(app: AppState) {
    val spec = app.saveConflict
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    var choice by remember(spec) { mutableIntStateOf(newerSide(spec?.conflict)) }
    var busy by remember(spec) { mutableStateOf(false) }
    LaunchedEffect(spec != null) { if (spec != null) app.platform.sounds.play(SoundCue.OPEN) }

    fun settle(keepHere: Boolean) {
        val s = app.saveConflict ?: return
        val svc = app.store.sync.service ?: return
        if (busy) return
        busy = true
        app.scope.launch {
            val result = svc.settle(s.conflict, keepHere)
            busy = false
            app.saveConflict = null
            if (result.isSuccess) {
                app.toasts.show(if (keepHere) "Kept this device's save. The other is in its history" else "Using the save from ${s.conflict.host.device}. This device's is in its history", ToastKind.SUCCESS)
                s.onSettled()
            } else {
                app.toasts.show(result.exceptionOrNull()?.message ?: "Couldn't settle the save", ToastKind.ERROR)
            }
        }
    }

    if (spec != null) {
        InputLayer(priority = LayerPriority.DIALOG + 1, modal = true) { e ->
            when (e.action) {
                NavAction.BACK -> { app.saveConflict = null; app.platform.sounds.play(SoundCue.CLOSE); NavResult.CONSUMED }
                NavAction.LEFT, NavAction.UP -> { if (choice != 0) { choice = 0; app.platform.sounds.play(SoundCue.MOVE) }; NavResult.CONSUMED }
                NavAction.RIGHT, NavAction.DOWN -> { if (choice != 1) { choice = 1; app.platform.sounds.play(SoundCue.MOVE) }; NavResult.CONSUMED }
                NavAction.SELECT -> { settle(keepHere = choice == 0); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }
    Overlay(visible = spec != null, onDismiss = { if (!busy) app.saveConflict = null }, edge = OverlayEdge.CENTER) {
        val s = shown ?: return@Overlay
        val conflict = s.conflict
        val c = Fuse.colors
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val room = maxWidth - Space.l * 2
            val stacked = room < 560.dp
            val compact = maxHeight < 520.dp
            Panel(
                Modifier
                    .widthIn(min = minOf(440.dp, room), max = minOf(720.dp, room))
                    .heightIn(max = maxHeight - Space.l * 2),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(if (compact) Space.l else Space.xl)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(if (compact) 44.dp else 52.dp).clip(RoundedCornerShape(Fuse.geometry.control)).background(c.accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            FuseIcon(FuseIcons.GitCompare, size = Size.iconL, tint = c.accent)
                        }
                        Spacer(Modifier.width(Space.l))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            FText("Which save should ${conflict.title.ifBlank { "this game" }} use?", Fuse.type.title, maxLines = 2, modifier = Modifier.semantics { heading() })
                            FText(
                                "You played on two devices since they last synced. Pick one to play with; the other stays in this game's save history, so nothing is lost.",
                                Fuse.type.body, color = c.textMuted, maxLines = 4,
                            )
                        }
                    }
                    Spacer(Modifier.height(if (compact) Space.m else Space.l))
                    val now = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
                    val offset = remember { localOffsetMillis(now) }
                    val newer = newerSide(conflict)
                    val morePlayed = if (conflict.here.playSeconds == conflict.host.playSeconds) -1 else if (conflict.here.playSeconds > conflict.host.playSeconds) 0 else 1
                    val cards = @Composable { i: Int, mod: Modifier ->
                        val side = if (i == 0) conflict.here else conflict.host
                        SideCard(
                            title = if (i == 0) "This Device" else "Fuse Sync",
                            from = if (i == 0) side.device else "From ${side.device}",
                            icon = if (i == 0) FuseIcons.MonitorSmartphone else FuseIcons.Cloud,
                            side = side,
                            savedAt = describe(side.at, now, offset),
                            badges = listOfNotNull("Newer".takeIf { newer == i }, "Played longer".takeIf { morePlayed == i }),
                            action = if (i == 0) "Use This Device" else "Use Fuse Sync",
                            selected = choice == i,
                            busy = busy && choice == i,
                            onClick = { choice = i; settle(keepHere = i == 0) },
                            modifier = mod,
                        )
                    }
                    if (stacked) {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            cards(0, Modifier.fillMaxWidth())
                            cards(1, Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            cards(0, Modifier.weight(1f))
                            cards(1, Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(Space.m))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.control)).background(c.success.copy(alpha = 0.08f))
                            .padding(horizontal = Space.m, vertical = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FuseIcon(FuseIcons.ShieldCheck, size = Size.iconS, tint = c.success)
                        Spacer(Modifier.width(Space.s))
                        FText("Both saves are kept. You can go back to the other any time from the game's save history.", Fuse.type.caption, color = c.text, maxLines = 3)
                    }
                }
            }
        }
    }
}

@Composable
private fun SideCard(
    title: String,
    from: String,
    icon: ImageVector,
    side: SaveSide,
    savedAt: String,
    badges: List<String>,
    action: String,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    val lift by fuselineFloat(if (selected) 1f else 0f, motion.focusSpring(), label = "side")
    val edge by fuselineColor(if (selected) c.focus else c.hairline, motion.tween(Durations.FAST), label = "edge")
    Column(
        modifier
            .graphicsLayer {
                val s = if (motion.reduced) 1f else 1f + 0.015f * lift
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(if (selected) c.text.copy(alpha = 0.07f) else c.text.copy(alpha = 0.035f))
            .border(if (selected) 2.dp else 1.dp, edge, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(Fuse.geometry.control)).background(c.text.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                FuseIcon(icon, size = Size.iconM, tint = c.text)
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(title, Fuse.type.bodyStrong, maxLines = 1)
                FText(from, Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
        }
        if (badges.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                badges.forEach { b ->
                    FText(
                        b, Fuse.type.caption, color = c.accent, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.accent.copy(alpha = 0.14f)).padding(horizontal = Space.s, vertical = 2.dp),
                    )
                }
            }
        }
        Fact(FuseIcons.Save, "Saved $savedAt")
        Fact(FuseIcons.Clock, if (side.playSeconds > 0) "${playtimeText(side.playSeconds)} played by then" else "Play time not known")
        Fact(FuseIcons.File, "${sizeText(side.bytes)}${if (side.files > 1) " in ${side.files} files" else ""}")
        Spacer(Modifier.height(Space.xs))
        FuseButton(
            action, selected = selected, onClick = onClick,
            kind = if (selected) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
            icon = FuseIcons.Check, loading = busy, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FuseIcon(icon, size = Size.iconS, tint = Fuse.colors.textMuted)
        Spacer(Modifier.width(Space.s))
        FText(text, Fuse.type.caption, color = Fuse.colors.text, maxLines = 1)
    }
}

/** The side saved last: 0 this device, 1 Fuse Sync. */
private fun newerSide(conflict: SaveConflict?): Int = if (conflict == null || conflict.here.at >= conflict.host.at) 0 else 1

private fun describe(at: Long, now: Long, offset: Long): String =
    io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords.relative(at, now, offset)

/** This device's offset from UTC at [at], for "today at 2:14 PM". */
internal fun localOffsetMillis(at: Long): Long = runCatching {
    kotlinx.datetime.TimeZone.currentSystemDefault().offsetAt(kotlin.time.Instant.fromEpochMilliseconds(at)).totalSeconds * 1000L
}.getOrDefault(0L)

/** "64 KB", "1.2 MB". */
internal fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${((bytes * 10) / (1024 * 1024)) / 10.0} MB".replace(".0 MB", " MB")
    else -> "${((bytes * 10) / (1024L * 1024 * 1024)) / 10.0} GB".replace(".0 GB", " GB")
}
