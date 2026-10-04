package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.KeyboardField
import io.github.matiyaaa.fuse.ui.designsystem.components.KeyboardState
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuHeader
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.OnScreenKeyboard
import io.github.matiyaaa.fuse.ui.designsystem.components.OnScreenKeyboardHints
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ReorderList
import io.github.matiyaaa.fuse.ui.designsystem.components.ReorderListState
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ReorderDefaults
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor

/**
 * Draws whichever overlay is open. Each overlay is modal: nothing underneath reacts while it's up.
 * They all arrive and leave the same way ([Overlay]: the scrim fades, the panel grows or slides in
 * and leaves quicker than it came) with the same opening sound, and closing one leaves the page
 * underneath exactly where it was.
 */
@Composable
fun OverlayHost(app: AppState) {
    ContextMenuOverlay(app)
    ChoiceOverlay(app)
    ProblemOverlay(app)
    TextPreviewOverlay(app)
    ReorderOverlay(app)
    ScreenPromptOverlay(app)
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
        BoxWithConstraints {
            // A side sheet the height of the screen; a narrow screen gives it all but a margin.
            val width = minOf(SHEET_WIDTH, maxWidth - Space.l)
            Panel(Modifier.width(width).fillMaxHeight().padding(vertical = Space.l).padding(end = Space.l)) {
                Column(Modifier.padding(Space.l)) {
                    // Art, or for something without art its generated tile, else the icon.
                    val accent = s.accent
                    val leading: (@Composable () -> Unit)? = when {
                        s.art != null || (accent != null && s.icon == null) -> {
                            {
                                Artwork(
                                    s.art,
                                    Modifier.fillMaxSize(),
                                    fallback = { accent?.let { GeneratedArt(s.title, it.toColor(), slot = ArtSlot.ICON) } },
                                )
                            }
                        }
                        else -> null
                    }
                    MenuHeader(s.title, subtitle = s.subtitle, icon = s.icon, leading = leading)
                    MenuList(s.actions, sel)
                }
            }
        }
    }
}

@Composable
private fun ChoiceOverlay(app: AppState) {
    val spec = app.choice
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    // Keyed by the title, so a list that updates itself (checks toggled in place) keeps its place.
    val sel = remember(spec?.title) { LinearSelection() }
    LaunchedEffect(spec != null) { if (spec != null) app.platform.sounds.play(SoundCue.OPEN) }
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
        DialogPanel { compact ->
            Column(Modifier.padding(if (compact) Space.l else Space.xl)) {
                MenuHeader(s.title, subtitle = s.message, icon = s.icon, subtitleMaxLines = 6)
                // As tall as its rows, scrolling within whatever height the dialog has left.
                MenuList(s.options, sel, modifier = Modifier.weight(1f, fill = false), fill = false)
            }
        }
    }
}

@Composable
private fun ReorderOverlay(app: AppState) {
    val spec = app.reorder
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    val keys = (spec ?: shown)?.entries?.map { it.key }.orEmpty()
    // A fresh list each time one opens, starting on its first row that can move.
    val list = remember(spec?.title) { ReorderListState(keys).also { s -> s.selection.index = (spec?.entries?.indexOfFirst { !it.locked } ?: 0).coerceAtLeast(0) } }
    list.sync(keys)
    val locked: (String) -> Boolean = { k -> spec?.entries?.firstOrNull { it.key == k }?.locked == true }
    val haptics = app.platform.haptics
    fun close() {
        list.cancel()
        app.reorder = null
        app.platform.sounds.play(SoundCue.CLOSE)
    }
    LaunchedEffect(spec != null) { if (spec != null) app.platform.sounds.play(SoundCue.OPEN) }
    if (spec != null) {
        InputLayer(priority = LayerPriority.DIALOG + 1, modal = true) { e ->
            list.handle(e, locked, onMoved = { spec.onMoved(it); haptics.drop() }, onDone = ::close, onLift = { haptics.lift() }, onSlot = { haptics.slot() })
        }
    }
    Overlay(visible = spec != null, onDismiss = ::close, edge = OverlayEdge.CENTER) {
        val s = shown ?: return@Overlay
        DialogPanel { compact ->
            Column(Modifier.padding(if (compact) Space.l else Space.xl)) {
                MenuHeader(
                    s.title,
                    subtitle = s.message ?: "Drag a row by its grip, or press A to pick it up and move it with the D-pad",
                    icon = s.icon,
                    subtitleMaxLines = 4,
                )
                ReorderList(
                    s.entries,
                    list,
                    onMoved = { order -> s.onMoved(order) },
                    onDone = ::close,
                    modifier = Modifier.weight(1f, fill = false),
                    longPressMs = ReorderDefaults.liftMs(app.store.prefs.value.input.longPressMs.toLong()),
                    onLift = { haptics.lift() },
                    onSlot = { haptics.slot() },
                    onDrop = { haptics.drop() },
                )
            }
        }
    }
}

/**
 * A centred dialog panel that fits the screen it is on: between [DIALOG_MIN_WIDTH] and
 * [DIALOG_MAX_WIDTH] wide and at most [DIALOG_MAX_HEIGHT] tall, always leaving a margin, so a
 * handheld's short screen or a phone held upright never clips it. [content] learns whether the
 * screen is short ([compact]) so it can tighten its padding.
 */
@Composable
private fun DialogPanel(content: @Composable ColumnScope.(compact: Boolean) -> Unit) {
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val room = maxWidth - Space.l * 2
        val compact = maxHeight < COMPACT_HEIGHT
        Panel(
            Modifier
                .widthIn(min = minOf(DIALOG_MIN_WIDTH, room), max = minOf(DIALOG_MAX_WIDTH, room))
                .heightIn(max = minOf(DIALOG_MAX_HEIGHT, maxHeight - Space.l * 2)),
        ) {
            Column { content(compact) }
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
    LaunchedEffect(spec != null) { if (spec != null) app.platform.sounds.play(SoundCue.OPEN) }
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
        DialogPanel { compact ->
            Column(Modifier.padding(if (compact) Space.l else Space.xl)) {
                FText(s.title, Fuse.type.title, maxLines = 3)
                Spacer(Modifier.height(Space.s))
                FText(s.message, Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 8)
                Spacer(Modifier.height(if (compact) Space.l else Space.xl))
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
    val field = app.textDraft
    val keyboard = remember(spec) { KeyboardState(autoCapitalize = spec?.capitalize == true && spec.secret.not()) }
    LaunchedEffect(spec) {
        if (spec != null) {
            field.replaceAll(spec.initial)
            keyboard.prepare(field)
            app.keyboardTarget = KeyboardTarget(field) {
                app.textInput = null
                spec.onDone(field.text)
            }
        } else {
            app.keyboardTarget = null
        }
    }
    fun done() {
        val s = spec ?: return
        app.textInput = null
        s.onDone(field.text)
    }
    val paste = { app.pasteInto(field) }
    if (spec != null) {
        InputLayer(
            priority = LayerPriority.DIALOG + 3,
            modal = true,
            repeats = keyboard.repeats,
        ) { e ->
            when (e.action) {
                NavAction.BACK -> { app.textInput = null; NavResult.CONSUMED }
                else -> keyboard.handle(e, field, ::done, onPaste = paste)
            }
        }
    }
    Overlay(visible = spec != null, onDismiss = { app.textInput = null }, edge = OverlayEdge.BOTTOM) {
        val s = shown ?: return@Overlay
        BoxWithConstraints {
            // Short screens (handhelds) get shorter keys, so the field and hints still fit.
            val keyHeight = if (maxHeight < COMPACT_HEIGHT) 38.dp else 46.dp
            Panel(Modifier.widthIn(max = 880.dp).padding(Space.l)) {
                Column(Modifier.padding(Space.l)) {
                    FText(s.title, Fuse.type.titleSmall)
                    Spacer(Modifier.height(Space.m))
                    KeyboardField(
                        field,
                        Modifier.fillMaxWidth(),
                        placeholder = s.placeholder,
                        secret = s.secret,
                        onClear = { field.replaceAll(""); keyboard.prepare(field) },
                    )
                    Spacer(Modifier.height(Space.l))
                    OnScreenKeyboard(
                        keyboard, field, ::done,
                        keyHeight = keyHeight,
                        doneLabel = s.doneLabel,
                        onPaste = paste,
                        onKey = { app.platform.haptics.tick() },
                    )
                    Spacer(Modifier.height(Space.m))
                    // Names the finishing key the way the key itself does (Save, Connect, Done).
                    OnScreenKeyboardHints(doneLabel = s.doneLabel)
                }
            }
        }
    }
}

/** The controller shortcuts under a keyboard; kept for callers of the shell's earlier version. */
@Composable
fun KeyboardHints(done: String = "Done") = OnScreenKeyboardHints(doneLabel = done)

/** A context menu sheet's width on screens with room for it. */
private val SHEET_WIDTH = 420.dp

/** Dialog widths and height on screens with room for them. */
private val DIALOG_MIN_WIDTH = 420.dp
private val DIALOG_MAX_WIDTH = 560.dp
private val DIALOG_MAX_HEIGHT = 560.dp

/** Screens shorter than this (handhelds) get tighter dialogs. */
private val COMPACT_HEIGHT = 560.dp
