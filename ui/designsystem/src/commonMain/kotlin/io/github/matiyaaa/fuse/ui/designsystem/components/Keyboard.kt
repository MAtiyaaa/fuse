package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.NavEvent
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a key does. GAP keys only take up space (the half-key indents). */
internal enum class KeyKind { CHAR, SHIFT, SPACE, PASTE, BACKSPACE, DONE, PAGE, GAP }

/** The keyboard's pages: letters, then numbers and punctuation, then more symbols. */
enum class KeyPage { LETTERS, NUMBERS, SYMBOLS }

/** Shift: off, for the next letter, or locked on (double tap). */
enum class ShiftState { OFF, ONCE, LOCK }

/** A key on the on-screen keyboard. [weight] is its width in letter-key units. */
internal data class Key(
    val label: String,
    val weight: Float = 1f,
    val kind: KeyKind = KeyKind.CHAR,
    val icon: ImageVector? = null,
    val page: KeyPage? = null,
)

private fun chars(s: String, weight: Float = 1f) = s.map { Key(it.toString(), weight) }
private fun gap(weight: Float) = Key("", weight, KeyKind.GAP)

private val shiftKey = Key("Shift", 1.25f, KeyKind.SHIFT, FuseIcons.Shift)
private val deleteKey = Key("Delete", 1.25f, KeyKind.BACKSPACE, FuseIcons.Backspace)
private val pasteKey = Key("Paste", 1.2f, KeyKind.PASTE, FuseIcons.ClipboardPaste)
private val doneKey = Key("Done", 2f, KeyKind.DONE)
private fun space(weight: Float) = Key("space", weight, KeyKind.SPACE)
private fun page(label: String, to: KeyPage, weight: Float) = Key(label, weight, KeyKind.PAGE, page = to)

/** Every row adds up to ten letter keys, so the rows line up like a phone keyboard. */
internal fun keyRows(page: KeyPage): List<List<Key>> = when (page) {
    KeyPage.LETTERS -> listOf(
        chars("qwertyuiop"),
        listOf(gap(0.5f)) + chars("asdfghjkl") + gap(0.5f),
        listOf(shiftKey, gap(0.25f)) + chars("zxcvbnm") + listOf(gap(0.25f), deleteKey),
        listOf(page("123", KeyPage.NUMBERS, 1.5f), pasteKey, Key("-"), space(3.3f), Key("'"), doneKey),
    )
    KeyPage.NUMBERS -> listOf(
        chars("1234567890"),
        chars("-/:;()$&@\""),
        listOf(page("#+=", KeyPage.SYMBOLS, 1.25f), gap(0.25f)) + chars(".,?!'", 1.4f) + listOf(gap(0.25f), deleteKey),
        listOf(page("ABC", KeyPage.LETTERS, 1.5f), pasteKey, space(5.3f), doneKey),
    )
    KeyPage.SYMBOLS -> listOf(
        chars("[]{}#%^*+="),
        chars("_\\|~<>€£¥•"),
        listOf(page("123", KeyPage.NUMBERS, 1.25f), gap(0.25f)) + chars(".,?!'", 1.4f) + listOf(gap(0.25f), deleteKey),
        listOf(page("ABC", KeyPage.LETTERS, 1.5f), pasteKey, space(5.3f), doneKey),
    )
}

/** Holding Delete (or X) this many repeats in starts deleting whole words. */
private const val WORD_DELETE_AFTER = 10

/**
 * The on-screen keyboard's state: the page, Shift, and which key the controller is on. Moving
 * vertically keeps the closest horizontal position.
 *
 * With [autoCapitalize] the first letter of the text (and of each sentence) is a capital, and two
 * spaces in a row become a full stop, like a phone keyboard. Search leaves it off.
 */
@Stable
class KeyboardState(val autoCapitalize: Boolean = false) {
    var page by mutableStateOf(KeyPage.LETTERS)
    var shift by mutableStateOf(ShiftState.OFF)
    var row by mutableIntStateOf(0)
    var column by mutableIntStateOf(0)

    /** True while a finger drags on Space to move the caret; the keys dim like a trackpad. */
    var trackpad by mutableStateOf(false)

    private var lastShiftTap: TimeSource.Monotonic.ValueTimeMark? = null
    private var lastSpace: TimeSource.Monotonic.ValueTimeMark? = null

    internal val rows: List<List<Key>> get() = keyRows(page)

    /** Sets Shift for the text as it is (a capital at the start when [autoCapitalize]). */
    fun prepare(field: EditableText) {
        page = KeyPage.LETTERS
        shift = if (autoCapitalize && sentenceStart(field)) ShiftState.ONCE else ShiftState.OFF
    }

    private fun startOf(r: Int, c: Int): Float = rows[r].take(c).sumOf { it.weight.toDouble() }.toFloat()

    /** The key in row [r] under horizontal position [x] (in key units), skipping the gaps. */
    private fun columnAt(r: Int, x: Float): Int {
        val keys = rows[r]
        var acc = 0f
        var best = 0
        var bestDistance = Float.MAX_VALUE
        keys.forEachIndexed { i, k ->
            if (k.kind != KeyKind.GAP) {
                val center = acc + k.weight / 2
                val d = abs(center - x)
                if (x >= acc && x < acc + k.weight) return i
                if (d < bestDistance) { bestDistance = d; best = i }
            }
            acc += k.weight
        }
        return best
    }

    private fun clampFocus() {
        row = row.coerceIn(0, rows.lastIndex)
        column = column.coerceIn(0, rows[row].lastIndex)
        if (rows[row][column].kind == KeyKind.GAP) column = columnAt(row, startOf(row, column) + 0.5f)
    }

    /**
     * Controller keys: the D-pad moves between keys and A presses one; X deletes (held, it speeds
     * up to whole words), Y types a space, LB and RB move the caret, LT and RT jump a word, and
     * Start finishes.
     */
    fun handle(event: NavEvent, field: EditableText, onDone: () -> Unit, onPaste: (() -> Unit)? = null): NavResult {
        clampFocus()
        when (event.action) {
            NavAction.LEFT, NavAction.RIGHT -> {
                val step = if (event.action == NavAction.LEFT) -1 else 1
                var c = column + step
                while (c in rows[row].indices && rows[row][c].kind == KeyKind.GAP) c += step
                if (c !in rows[row].indices) return NavResult.BLOCKED
                column = c
                return NavResult.MOVED
            }
            NavAction.UP, NavAction.DOWN -> {
                val target = row + if (event.action == NavAction.UP) -1 else 1
                if (target !in rows.indices) return NavResult.BLOCKED
                val x = startOf(row, column) + rows[row][column].weight / 2
                row = target
                column = columnAt(target, x)
                return NavResult.MOVED
            }
            NavAction.SELECT -> {
                press(rows[row][column], field, onDone, onPaste)
                return NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> {
                if (event.repeat >= WORD_DELETE_AFTER) field.deleteWordBack() else field.backspace()
                edited(field)
                return NavResult.ACTIVATED
            }
            NavAction.SEARCH -> { typeSpace(field); return NavResult.ACTIVATED }
            NavAction.PREVIOUS_SECTION -> { field.moveCaret(-1); edited(field); return NavResult.MOVED }
            NavAction.NEXT_SECTION -> { field.moveCaret(1); edited(field); return NavResult.MOVED }
            NavAction.PAGE_UP -> { field.moveWord(-1); edited(field); return NavResult.MOVED }
            NavAction.PAGE_DOWN -> { field.moveWord(1); edited(field); return NavResult.MOVED }
            NavAction.QUICK_MENU -> { onDone(); return NavResult.ACTIVATED }
            else -> return NavResult.IGNORED
        }
    }

    internal fun press(key: Key, field: EditableText, onDone: () -> Unit, onPaste: (() -> Unit)? = null) {
        when (key.kind) {
            KeyKind.CHAR -> {
                field.insert(if (shift != ShiftState.OFF) key.label.uppercase() else key.label)
                if (shift == ShiftState.ONCE) shift = ShiftState.OFF
                edited(field)
            }
            KeyKind.SHIFT -> {
                val quick = lastShiftTap?.let { it.elapsedNow().inWholeMilliseconds < 380 } == true
                shift = when {
                    quick && shift == ShiftState.ONCE -> ShiftState.LOCK
                    shift == ShiftState.OFF -> ShiftState.ONCE
                    else -> ShiftState.OFF
                }
                lastShiftTap = TimeSource.Monotonic.markNow()
            }
            KeyKind.SPACE -> typeSpace(field)
            KeyKind.PASTE -> onPaste?.invoke()
            KeyKind.BACKSPACE -> { field.backspace(); edited(field) }
            KeyKind.DONE -> onDone()
            KeyKind.PAGE -> {
                val x = startOf(row, column.coerceIn(0, rows[row].lastIndex))
                page = key.page ?: KeyPage.LETTERS
                row = row.coerceIn(0, rows.lastIndex)
                column = columnAt(row, x + 0.5f)
            }
            KeyKind.GAP -> Unit
        }
    }

    /** Holding Delete: characters first, then whole words. */
    internal fun deleteHeld(field: EditableText, repeat: Int) {
        if (repeat >= WORD_DELETE_AFTER) field.deleteWordBack() else field.backspace()
        edited(field)
    }

    private fun typeSpace(field: EditableText) {
        val text = field.text
        val caret = field.selection.min
        val quick = lastSpace?.let { it.elapsedNow().inWholeMilliseconds < 450 } == true
        // Two quick spaces after a word end the sentence, as on a phone.
        if (autoCapitalize && quick && field.selection.collapsed && caret >= 2 && text[caret - 1] == ' ' && text[caret - 2].isLetterOrDigit()) {
            field.backspace()
            field.insert(". ")
            lastSpace = null
        } else {
            field.insert(" ")
            lastSpace = TimeSource.Monotonic.markNow()
        }
        edited(field)
    }

    /** After an edit or a caret move: Shift follows the sentence (unless locked). */
    internal fun edited(field: EditableText) {
        if (!autoCapitalize || shift == ShiftState.LOCK) return
        shift = if (sentenceStart(field)) ShiftState.ONCE else ShiftState.OFF
    }

    private fun sentenceStart(field: EditableText): Boolean {
        val caret = field.selection.min
        if (caret == 0) return true
        val text = field.text
        if (text[caret - 1] != ' ') return false
        val before = text.substring(0, caret).trimEnd()
        return before.isEmpty() || before.last() in ".!?"
    }
}

/**
 * A controller-first keyboard laid out like a phone's: letters, then 123 and #+= pages. Every key is
 * reachable with the D-pad (see [KeyboardState.handle]). On a touch screen, keys show what they type
 * as they are pressed, holding Delete speeds up to whole words, dragging along Space moves the
 * caret, and a double tap on Shift locks capitals. Hardware keyboards type directly.
 * [onPaste] inserts the clipboard; without it the Paste key is dimmed. [onKey] plays feedback for
 * touch presses (controller presses already get it from the input router).
 */
@Composable
fun OnScreenKeyboard(
    state: KeyboardState,
    field: EditableText,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 44.dp,
    doneLabel: String = "Done",
    showFocus: Boolean = true,
    onPaste: (() -> Unit)? = null,
    onKey: () -> Unit = {},
) {
    val rows = state.rows
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        rows.forEachIndexed { r, keys ->
            Row(Modifier.fillMaxWidth()) {
                keys.forEachIndexed { col, key ->
                    if (key.kind == KeyKind.GAP) {
                        Spacer(Modifier.weight(key.weight))
                    } else {
                        Box(Modifier.weight(key.weight).padding(horizontal = 3.dp)) {
                            KeyCap(
                                key = key,
                                state = state,
                                field = field,
                                doneLabel = doneLabel,
                                selected = showFocus && r == state.row && col == state.column,
                                enabled = key.kind != KeyKind.PASTE || onPaste != null,
                                height = keyHeight,
                                onFocus = { state.row = r; state.column = col },
                                onPress = { onKey(); state.press(key, field, onDone, onPaste) },
                                onKey = onKey,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(
    key: Key,
    state: KeyboardState,
    field: EditableText,
    doneLabel: String,
    selected: Boolean,
    enabled: Boolean,
    height: Dp,
    onFocus: () -> Unit,
    onPress: () -> Unit,
    onKey: () -> Unit,
) {
    val c = Fuse.colors
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    val function = key.kind in setOf(KeyKind.SHIFT, KeyKind.BACKSPACE, KeyKind.PAGE, KeyKind.PASTE)
    val shiftOn = key.kind == KeyKind.SHIFT && state.shift != ShiftState.OFF
    val bg by animateColorAsState(
        when {
            selected -> c.text
            pressed -> c.text.copy(alpha = 0.26f)
            key.kind == KeyKind.DONE -> c.accent
            shiftOn -> c.text.copy(alpha = 0.85f)
            function -> c.text.copy(alpha = 0.05f)
            else -> c.text.copy(alpha = 0.11f)
        },
        Fuse.motion.tween(Durations.INSTANT),
        label = "key",
    )
    val fg = when {
        selected || shiftOn -> c.ink
        !enabled -> c.textFaint
        key.kind == KeyKind.DONE -> c.onAccent
        else -> c.text
    }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, Fuse.motion.tween(Durations.INSTANT), label = "keyScale")
    val label = when {
        key.kind == KeyKind.DONE -> doneLabel
        key.kind == KeyKind.CHAR && state.shift != ShiftState.OFF -> key.label.uppercase()
        else -> key.label
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .zIndex(if (pressed) 1f else 0f)
            .pointerInput(key, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onFocus()
                    when (key.kind) {
                        KeyKind.BACKSPACE -> {
                            // Deletes at once, repeats after a moment and then takes whole words.
                            onKey()
                            state.deleteHeld(field, 0)
                            val repeat = scope.launch {
                                delay(420)
                                var n = 1
                                while (true) {
                                    state.deleteHeld(field, n++)
                                    delay(if (n > WORD_DELETE_AFTER) 170 else 75)
                                }
                            }
                            waitForUpOrCancellation()
                            repeat.cancel()
                        }
                        KeyKind.SPACE -> {
                            // Dragging along Space moves the caret, like a trackpad.
                            val step = 9.dp.toPx()
                            var moved = 0f
                            var travelled = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val dx = change.positionChange().x
                                travelled += abs(dx)
                                if (!state.trackpad && travelled > viewConfiguration.touchSlop) state.trackpad = true
                                if (state.trackpad) {
                                    moved += dx
                                    while (moved >= step) { field.moveCaret(1); moved -= step }
                                    while (moved <= -step) { field.moveCaret(-1); moved += step }
                                    change.consume()
                                }
                            }
                            if (state.trackpad) {
                                state.trackpad = false
                                state.edited(field)
                            } else {
                                press()
                            }
                        }
                        else -> if (waitForUpOrCancellation() != null) press()
                    }
                    pressed = false
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (state.trackpad && key.kind != KeyKind.SPACE) 0.35f else 1f }
                .clip(RoundedCornerShape(Fuse.geometry.control))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            when {
                key.kind == KeyKind.SPACE && state.trackpad -> FuseIcon(FuseIcons.MoveHorizontal, size = 18.dp, tint = fg)
                key.kind == KeyKind.SHIFT -> FuseIcon(if (state.shift == ShiftState.LOCK) FuseIcons.CapsLock else FuseIcons.Shift, size = 20.dp, tint = fg)
                key.icon != null -> FuseIcon(key.icon, size = 20.dp, tint = fg)
                key.kind == KeyKind.CHAR -> FText(label, Fuse.type.titleSmall, color = fg, maxLines = 1)
                else -> FText(label, Fuse.type.label, color = fg, maxLines = 1)
            }
        }
        // The pressed letter pops up above the finger.
        if (pressed && key.kind == KeyKind.CHAR) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = -height - Space.xs)
                    .size(width = height * 1.05f, height = height * 1.1f)
                    .shadow(12.dp, RoundedCornerShape(Fuse.geometry.control))
                    .clip(RoundedCornerShape(Fuse.geometry.control))
                    .background(c.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                FText(label, Fuse.type.title, color = c.text, maxLines = 1)
            }
        }
    }
}

/**
 * The line being typed: a caret that blinks while you pause, the selection, and text that scrolls
 * to keep the caret in view. Tap to place the caret, drag to move it, double tap a word to select
 * it. [secret] shows dots instead of the text. [onClear] adds a clear button once there is text.
 */
@Composable
fun KeyboardField(
    field: EditableText,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    style: TextStyle = Fuse.type.title,
    leading: ImageVector? = null,
    focused: Boolean = true,
    secret: Boolean = false,
    onClear: (() -> Unit)? = null,
) {
    val c = Fuse.colors
    val value = field.value
    val shown = if (secret) "•".repeat(value.text.length) else value.text
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState()
    val blink = remember { Animatable(1f) }
    LaunchedEffect(value.text, value.selection, focused) {
        blink.snapTo(1f)
        if (!focused) return@LaunchedEffect
        delay(650)
        while (true) {
            blink.animateTo(0f, tween(160))
            delay(340)
            blink.animateTo(1f, tween(160))
            delay(540)
        }
    }
    Row(
        modifier
            .clip(RoundedCornerShape(Fuse.geometry.control))
            .background(c.text.copy(alpha = 0.08f))
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            FuseIcon(leading, tint = c.textMuted)
            Spacer(Modifier.width(Space.m))
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val viewport = constraints.maxWidth
            val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
            LaunchedEffect(value.selection, layout, viewport) {
                val l = layout ?: return@LaunchedEffect
                val x = l.getCursorRect(value.selection.end.coerceIn(0, shown.length)).left
                when {
                    x - scroll.value > viewport - margin -> scroll.scrollTo((x - viewport + margin).toInt())
                    x - scroll.value < margin -> scroll.scrollTo((x - margin).toInt().coerceAtLeast(0))
                }
            }
            fun offsetAt(p: Offset): Int = layout?.getOffsetForPosition(p)?.coerceIn(0, value.text.length) ?: value.text.length
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scroll, enabled = false)
                    .pointerInput(field) {
                        detectTapGestures(
                            onTap = { field.setCaret(offsetAt(it)) },
                            onDoubleTap = { if (!secret) field.selectWordAt(offsetAt(it)) },
                        )
                    }
                    .pointerInput(field) {
                        detectHorizontalDragGestures(
                            onDragStart = { field.setCaret(offsetAt(it)) },
                            onHorizontalDrag = { change, _ -> field.setCaret(offsetAt(change.position)); change.consume() },
                        )
                    },
            ) {
                if (value.text.isEmpty()) FText(placeholder.ifEmpty { " " }, style, color = c.textFaint, maxLines = 1)
                BasicText(
                    shown,
                    style = style.copy(color = c.text),
                    maxLines = 1,
                    softWrap = false,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .drawBehind {
                            val l = layout ?: return@drawBehind
                            val sel = value.selection
                            if (!sel.collapsed && sel.max <= shown.length) drawPath(l.getPathForRange(sel.min, sel.max), c.accent.copy(alpha = 0.35f))
                        }
                        .drawWithContent {
                            drawContent()
                            val l = layout ?: return@drawWithContent
                            if (focused && value.selection.collapsed) {
                                val r = l.getCursorRect(value.selection.start.coerceIn(0, shown.length))
                                val w = 2.dp.toPx()
                                drawRect(c.accent, topLeft = Offset(r.left - w / 2, r.top), size = Size(w, r.height), alpha = blink.value)
                            }
                        },
                )
            }
        }
        if (onClear != null && value.text.isNotEmpty()) {
            Spacer(Modifier.width(Space.s))
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(c.text.copy(alpha = 0.16f))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(FuseIcons.Close, size = 14.dp, tint = c.text)
            }
        }
    }
}
