package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.KeyboardState
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.OnScreenKeyboard
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/** Draws whichever overlay is open. Each overlay is modal: nothing underneath reacts while it's up. */
@Composable
fun OverlayHost(app: AppState) {
    ContextMenuOverlay(app)
    ChoiceOverlay(app)
    ConfirmOverlay(app)
    TextInputOverlay(app)
    io.github.matiyaaa.fuse.ui.shell.settings.ButtonDetectOverlay(app)
}

@Composable
private fun ContextMenuOverlay(app: AppState) {
    val spec = app.contextMenu
    // Keep the last spec while the exit animation runs.
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    val sel = remember(spec) { LinearSelection() }
    LaunchedEffect(spec != null) { if (spec != null) app.platform.sounds.play(SoundCue.OPEN) }
    if (spec != null) {
        InputLayer(priority = LayerPriority.DIALOG, modal = true) { e ->
            when (e.action) {
                NavAction.BACK, NavAction.CONTEXT -> { app.contextMenu = null; app.platform.sounds.play(SoundCue.CLOSE); NavResult.CONSUMED }
                else -> handleMenuAction(e, spec.actions, sel)
            }
        }
    }
    Overlay(visible = spec != null, onDismiss = { app.contextMenu = null }, edge = OverlayEdge.END) {
        val s = shown ?: return@Overlay
        Panel(Modifier.width(420.dp).fillMaxHeight().padding(vertical = Space.l).padding(end = Space.l)) {
            Column(Modifier.padding(Space.l)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.art != null) {
                        Artwork(s.art, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(Space.m))
                    } else if (s.icon != null) {
                        FuseIcon(s.icon, size = 28.dp)
                        Spacer(Modifier.width(Space.m))
                    }
                    Column {
                        FText(s.title, Fuse.type.titleSmall, maxLines = 2)
                        s.subtitle?.let { FText(it, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1) }
                    }
                }
                Spacer(Modifier.height(Space.l))
                MenuList(s.actions, sel)
            }
        }
    }
}

@Composable
private fun ChoiceOverlay(app: AppState) {
    val spec = app.choice
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    val sel = remember(spec) { LinearSelection() }
    if (spec != null) {
        InputLayer(priority = LayerPriority.DIALOG + 1, modal = true) { e ->
            when (e.action) {
                NavAction.BACK -> { app.choice = null; NavResult.CONSUMED }
                else -> handleMenuAction(e, spec.options, sel)
            }
        }
    }
    Overlay(visible = spec != null, onDismiss = { app.choice = null }, edge = OverlayEdge.CENTER) {
        val s = shown ?: return@Overlay
        Panel(Modifier.widthIn(min = 420.dp, max = 560.dp).heightIn(max = 560.dp)) {
            Column(Modifier.padding(Space.xl)) {
                FText(s.title, Fuse.type.title, maxLines = 2)
                s.message?.let {
                    Spacer(Modifier.height(Space.s))
                    FText(it, Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 5)
                }
                Spacer(Modifier.height(Space.l))
                MenuList(s.options, sel, modifier = Modifier.heightIn(max = 420.dp))
            }
        }
    }
}

@Composable
private fun ConfirmOverlay(app: AppState) {
    val spec = app.confirm
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    // Destructive confirmations start on Cancel so a hurried press never deletes anything.
    var index by remember(spec) { mutableIntStateOf(if (spec?.destructive == true) 0 else 1) }
    fun finish(ok: Boolean) {
        val s = spec ?: return
        app.confirm = null
        if (ok) s.onConfirm()
    }
    if (spec != null) {
        InputLayer(priority = LayerPriority.DIALOG + 2, modal = true) { e ->
            when (e.action) {
                NavAction.LEFT -> if (index > 0) { index = 0; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (index < 1) { index = 1; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> { finish(index == 1); NavResult.ACTIVATED }
                NavAction.BACK -> { finish(false); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }
    Overlay(visible = spec != null, onDismiss = { app.confirm = null }, edge = OverlayEdge.CENTER) {
        val s = shown ?: return@Overlay
        Panel(Modifier.widthIn(min = 420.dp, max = 560.dp)) {
            Column(Modifier.padding(Space.xl)) {
                FText(s.title, Fuse.type.title, maxLines = 3)
                Spacer(Modifier.height(Space.s))
                FText(s.message, Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 8)
                Spacer(Modifier.height(Space.xl))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m, Alignment.End)) {
                    FuseButton("Cancel", selected = index == 0, onClick = { finish(false) }, kind = ButtonKind.GHOST)
                    FuseButton(
                        s.confirmLabel, selected = index == 1, onClick = { finish(true) },
                        kind = if (s.destructive) ButtonKind.DANGER else ButtonKind.PRIMARY,
                    )
                }
            }
        }
    }
}

@Composable
private fun TextInputOverlay(app: AppState) {
    val spec = app.textInput
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    val keyboard = remember(spec) { KeyboardState() }
    LaunchedEffect(spec) {
        if (spec != null) {
            app.textDraft = spec.initial
            app.keyboardTarget = KeyboardTarget({ app.textDraft }, { app.textDraft = it }, {
                app.textInput = null
                spec.onDone(app.textDraft)
            })
        } else {
            app.keyboardTarget = null
        }
    }
    fun done() {
        val s = spec ?: return
        app.textInput = null
        s.onDone(app.textDraft)
    }
    if (spec != null) {
        InputLayer(priority = LayerPriority.DIALOG + 3, modal = true) { e ->
            when (e.action) {
                NavAction.BACK -> { app.textInput = null; NavResult.CONSUMED }
                else -> keyboard.handle(e, app.textDraft, { app.textDraft = it }, ::done, onPaste = { app.pasteInto({ app.textDraft }, { app.textDraft = it }) })
            }
        }
    }
    Overlay(visible = spec != null, onDismiss = { app.textInput = null }, edge = OverlayEdge.BOTTOM) {
        val s = shown ?: return@Overlay
        Panel(Modifier.widthIn(max = 880.dp).padding(Space.l)) {
            Column(Modifier.padding(Space.l)) {
                FText(s.title, Fuse.type.titleSmall)
                Spacer(Modifier.height(Space.m))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.control))
                        .background(Fuse.colors.text.copy(alpha = 0.08f)).padding(horizontal = Space.l, vertical = Space.m),
                ) {
                    val text = app.textDraft
                    FText(
                        if (text.isEmpty()) s.placeholder.ifEmpty { " " } else "$text|",
                        Fuse.type.title,
                        color = if (text.isEmpty()) Fuse.colors.textFaint else Fuse.colors.text,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(Space.l))
                OnScreenKeyboard(keyboard, app.textDraft, { app.textDraft = it }, ::done, onPaste = { app.pasteInto({ app.textDraft }, { app.textDraft = it }) })
                Spacer(Modifier.height(Space.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
                    KeyHint(HintButton.OPTIONS, "Delete")
                    KeyHint(HintButton.SEARCH, "Space")
                    KeyHint(HintButton.NEXT, "Paste")
                    KeyHint(HintButton.MENU, "Done")
                }
            }
        }
    }
}

/** A small glyph and label under the keyboard, in the pad's own button style. */
@Composable
private fun KeyHint(button: HintButton, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ButtonGlyph(button, size = 18.dp, color = Fuse.colors.textFaint)
        Spacer(Modifier.width(Space.xs))
        FText(label, Fuse.type.caption, color = Fuse.colors.textFaint)
    }
}
