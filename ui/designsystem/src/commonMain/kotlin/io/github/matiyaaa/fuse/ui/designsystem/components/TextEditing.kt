package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/**
 * Text being edited on the on-screen keyboard: the text plus its selection (a caret when the
 * selection is empty). Every edit goes through [TextEdits], so typing replaces a selection, Delete
 * removes it, and the caret never lands inside a surrogate pair.
 */
@Stable
class EditableText(initial: String = "") {
    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))

    val text: String get() = value.text
    val selection: TextRange get() = value.selection

    /** Replaces everything and puts the caret at the end. */
    fun replaceAll(text: String) {
        value = TextFieldValue(text, TextRange(text.length))
    }

    fun insert(s: String) { value = TextEdits.insert(value, s) }
    fun backspace() { value = TextEdits.backspace(value) }
    fun deleteForward() { value = TextEdits.deleteForward(value) }
    fun deleteWordBack() { value = TextEdits.deleteWordBack(value) }
    fun moveCaret(delta: Int) { value = TextEdits.moveCaret(value, delta) }
    fun moveWord(direction: Int) { value = TextEdits.moveWord(value, direction) }
    fun setCaret(offset: Int) { value = value.copy(selection = TextRange(offset.coerceIn(0, text.length))) }
    fun select(start: Int, end: Int) {
        value = value.copy(selection = TextRange(start.coerceIn(0, text.length), end.coerceIn(0, text.length)))
    }
    fun selectWordAt(offset: Int) { value = TextEdits.selectWordAt(value, offset) }
    fun selectAll() { value = value.copy(selection = TextRange(0, text.length)) }
}

/** Pure editing steps on a [TextFieldValue]. */
object TextEdits {

    fun insert(v: TextFieldValue, s: String): TextFieldValue {
        val start = v.selection.min
        val end = v.selection.max
        val text = v.text.substring(0, start) + s + v.text.substring(end)
        return TextFieldValue(text, TextRange(start + s.length))
    }

    /** Deletes the selection, or the character before the caret. */
    fun backspace(v: TextFieldValue): TextFieldValue {
        if (!v.selection.collapsed) return insert(v, "")
        val caret = v.selection.start
        if (caret == 0) return v
        val from = previousBoundary(v.text, caret)
        return TextFieldValue(v.text.removeRange(from, caret), TextRange(from))
    }

    /** Deletes the selection, or the character after the caret. */
    fun deleteForward(v: TextFieldValue): TextFieldValue {
        if (!v.selection.collapsed) return insert(v, "")
        val caret = v.selection.start
        if (caret >= v.text.length) return v
        val to = nextBoundary(v.text, caret)
        return TextFieldValue(v.text.removeRange(caret, to), TextRange(caret))
    }

    /** Deletes the selection, or the word before the caret with the spaces after it. */
    fun deleteWordBack(v: TextFieldValue): TextFieldValue {
        if (!v.selection.collapsed) return insert(v, "")
        val caret = v.selection.start
        val from = wordStartBefore(v.text, caret)
        return TextFieldValue(v.text.removeRange(from, caret), TextRange(from))
    }

    /** Moves the caret by characters; a selection collapses to its edge in that direction first. */
    fun moveCaret(v: TextFieldValue, delta: Int): TextFieldValue {
        if (!v.selection.collapsed) {
            return v.copy(selection = TextRange(if (delta < 0) v.selection.min else v.selection.max))
        }
        var caret = v.selection.start
        repeat(kotlin.math.abs(delta)) {
            caret = if (delta < 0) previousBoundary(v.text, caret) else nextBoundary(v.text, caret)
        }
        return v.copy(selection = TextRange(caret))
    }

    /** Moves the caret to the start of the previous word (-1) or the end of the next one (1). */
    fun moveWord(v: TextFieldValue, direction: Int): TextFieldValue {
        val from = if (direction < 0) v.selection.min else v.selection.max
        val to = if (direction < 0) wordStartBefore(v.text, from) else wordEndAfter(v.text, from)
        return v.copy(selection = TextRange(to))
    }

    /** Selects the word at [offset] (or the run of spaces or symbols there). */
    fun selectWordAt(v: TextFieldValue, offset: Int): TextFieldValue {
        val text = v.text
        if (text.isEmpty()) return v
        val at = offset.coerceIn(0, text.length)
        // Past the end, or just after a word: take the word to the left.
        val probe = if (at == text.length || (at > 0 && !isWordChar(text[at]) && isWordChar(text[at - 1]))) at - 1 else at
        val word = isWordChar(text[probe])
        var start = probe
        var end = probe + 1
        while (start > 0 && isWordChar(text[start - 1]) == word && (word || text[start - 1] == text[probe])) start--
        while (end < text.length && isWordChar(text[end]) == word && (word || text[end] == text[probe])) end++
        return v.copy(selection = TextRange(start, end))
    }

    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '\u2019'

    private fun wordStartBefore(text: String, caret: Int): Int {
        var i = caret
        while (i > 0 && !isWordChar(text[i - 1])) i--
        while (i > 0 && isWordChar(text[i - 1])) i--
        return i
    }

    private fun wordEndAfter(text: String, caret: Int): Int {
        var i = caret
        while (i < text.length && !isWordChar(text[i])) i++
        while (i < text.length && isWordChar(text[i])) i++
        return i
    }

    private fun previousBoundary(text: String, caret: Int): Int =
        if (caret >= 2 && text[caret - 1].isLowSurrogate() && text[caret - 2].isHighSurrogate()) caret - 2 else (caret - 1).coerceAtLeast(0)

    private fun nextBoundary(text: String, caret: Int): Int =
        if (caret + 1 < text.length && text[caret].isHighSurrogate() && text[caret + 1].isLowSurrogate()) caret + 2 else (caret + 1).coerceAtMost(text.length)
}

/**
 * Draws [selection] behind a line of text as one softly rounded block per line, in [color] (the
 * accent at a third by default), instead of a hard-edged path. Pair it with [editingCaret] on the
 * same text; [layout] is the text's latest layout.
 */
@Composable
fun Modifier.editingSelection(
    layout: () -> TextLayoutResult?,
    selection: TextRange,
    color: Color = Fuse.colors.accent.copy(alpha = 0.32f),
): Modifier = drawBehind {
    val l = layout() ?: return@drawBehind
    if (selection.collapsed) return@drawBehind
    val length = l.layoutInput.text.length
    val from = selection.min.coerceIn(0, length)
    val to = selection.max.coerceIn(0, length)
    if (to <= from) return@drawBehind
    val radius = CornerRadius(4.dp.toPx())
    val pad = 1.dp.toPx()
    for (line in l.getLineForOffset(from)..l.getLineForOffset(to)) {
        val start = maxOf(from, l.getLineStart(line))
        val end = minOf(to, l.getLineEnd(line))
        if (end <= start) continue
        val left = l.getHorizontalPosition(start, usePrimaryDirection = true)
        val right = l.getHorizontalPosition(end, usePrimaryDirection = true)
        val top = l.getLineTop(line)
        val bottom = l.getLineBottom(line)
        drawRoundRect(color, Offset(minOf(left, right) - pad, top), Size(kotlin.math.abs(right - left) + pad * 2, bottom - top), radius)
    }
}

/**
 * Draws the caret of an edited line over the text: a rounded bar the height of the text's own
 * glyphs (not the whole line box), that glides to its new place on a quick spring when it moves
 * (it jumps under Reduced motion). [alpha] is read while drawing, so a blink costs no
 * recomposition. Nothing is drawn while [visible] is false or a range is selected.
 */
@Composable
fun Modifier.editingCaret(
    layout: () -> TextLayoutResult?,
    offset: Int,
    visible: Boolean,
    alpha: () -> Float = { 1f },
    color: Color = Fuse.colors.accent,
): Modifier {
    val reduced = Fuse.motion.reduced
    val x = remember { Animatable(Float.NaN) }
    val l = layout()
    val target = l?.let { it.getCursorRect(offset.coerceIn(0, it.layoutInput.text.length)).left }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        if (x.value.isNaN() || reduced) x.snapTo(target) else x.animateTo(target, spring(dampingRatio = 0.9f, stiffness = 1600f))
    }
    return drawWithContent {
        drawContent()
        val res = layout() ?: return@drawWithContent
        if (!visible) return@drawWithContent
        val a = alpha().coerceIn(0f, 1f)
        if (a <= 0f) return@drawWithContent
        val r = res.getCursorRect(offset.coerceIn(0, res.layoutInput.text.length))
        val w = 2.dp.toPx()
        // The glyphs' height, centred on the line: about 0.8 of the line box.
        val h = r.height * 0.8f
        val px = if (x.value.isNaN()) r.left else x.value
        drawRoundRect(color, Offset(px - w / 2, r.top + (r.height - h) / 2), Size(w, h), CornerRadius(w / 2), alpha = a)
    }
}

