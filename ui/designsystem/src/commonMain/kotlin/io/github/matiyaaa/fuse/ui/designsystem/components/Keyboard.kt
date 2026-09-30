package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.NavEvent
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/** A key on the on-screen keyboard. [span] is its width in letter-key units. */
internal data class Key(val label: String, val span: Int = 1, val icon: ImageVector? = null, val kind: KeyKind = KeyKind.CHAR)

internal enum class KeyKind { CHAR, SHIFT, SPACE, PASTE, BACKSPACE, DONE }

private val rows: List<List<Key>> = listOf(
    "1234567890".map { Key(it.toString()) },
    "qwertyuiop".map { Key(it.toString()) },
    "asdfghjkl'".map { Key(it.toString()) },
    "zxcvbnm-:.".map { Key(it.toString()) },
    listOf(
        Key("Shift", 2, FuseIcons.ChevronUp, KeyKind.SHIFT),
        Key("Paste", 2, FuseIcons.ClipboardPaste, KeyKind.PASTE),
        Key("Space", 2, null, KeyKind.SPACE),
        Key("Delete", 2, FuseIcons.ArrowLeft, KeyKind.BACKSPACE),
        Key("Done", 2, FuseIcons.Check, KeyKind.DONE),
    ),
)

/** Selection state for [OnScreenKeyboard]. Moving vertically keeps the closest horizontal position. */
@Stable
class KeyboardState {
    var row by mutableIntStateOf(1)
    var column by mutableIntStateOf(0)
    var shift by mutableStateOf(false)

    private fun startOf(r: Int, c: Int): Int = rows[r].take(c).sumOf { it.span }

    private fun columnAt(r: Int, x: Int): Int {
        var acc = 0
        rows[r].forEachIndexed { i, k ->
            if (x < acc + k.span) return i
            acc += k.span
        }
        return rows[r].lastIndex
    }

    /**
     * Handles navigation and typing. Calls [onText] with the new text; [onDone] when Done/Start is
     * chosen. X deletes, Y types a space and R pastes from anywhere, like console keyboards.
     */
    fun handle(event: NavEvent, text: String, onText: (String) -> Unit, onDone: () -> Unit, onPaste: (() -> Unit)? = null): NavResult {
        when (event.action) {
            NavAction.LEFT -> return if (column > 0) { column--; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.RIGHT -> return if (column < rows[row].lastIndex) { column++; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.UP, NavAction.DOWN -> {
                val target = row + if (event.action == NavAction.UP) -1 else 1
                if (target !in rows.indices) return NavResult.BLOCKED
                val x = startOf(row, column) + rows[row][column].span / 2
                row = target
                column = columnAt(target, x)
                return NavResult.MOVED
            }
            NavAction.SELECT -> {
                press(rows[row][column], text, onText, onDone, onPaste)
                return NavResult.ACTIVATED
            }
            NavAction.NEXT_SECTION -> return if (onPaste != null) { onPaste(); NavResult.ACTIVATED } else NavResult.IGNORED
            NavAction.CONTEXT -> { onText(text.dropLast(1)); return NavResult.ACTIVATED }
            NavAction.SEARCH -> { onText("$text "); return NavResult.ACTIVATED }
            NavAction.QUICK_MENU -> { onDone(); return NavResult.ACTIVATED }
            else -> return NavResult.IGNORED
        }
    }

    internal fun press(key: Key, text: String, onText: (String) -> Unit, onDone: () -> Unit, onPaste: (() -> Unit)? = null) {
        when (key.kind) {
            KeyKind.CHAR -> {
                onText(text + if (shift) key.label.uppercase() else key.label)
                shift = false
            }
            KeyKind.SHIFT -> shift = !shift
            KeyKind.SPACE -> onText("$text ")
            KeyKind.PASTE -> onPaste?.invoke()
            KeyKind.BACKSPACE -> onText(text.dropLast(1))
            KeyKind.DONE -> onDone()
        }
    }
}

/**
 * A controller-first keyboard: every key is reachable with the D-pad, X deletes, Y adds a space, R
 * pastes and Start finishes. Touch works on every key too. Hardware keyboards type directly.
 * [onPaste] inserts the clipboard; without it the Paste key is dimmed.
 */
@Composable
fun OnScreenKeyboard(
    state: KeyboardState,
    text: String,
    onText: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: androidx.compose.ui.unit.Dp = 44.dp,
    onPaste: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        rows.forEachIndexed { r, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                keys.forEachIndexed { col, key ->
                    val selected = r == state.row && col == state.column
                    KeyCap(
                        key = key,
                        shift = state.shift,
                        selected = selected,
                        enabled = key.kind != KeyKind.PASTE || onPaste != null,
                        modifier = Modifier.weight(key.span.toFloat()).height(keyHeight),
                        onClick = {
                            state.row = r
                            state.column = col
                            state.press(key, text, onText, onDone, onPaste)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyCap(key: Key, shift: Boolean, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val accentKey = key.kind == KeyKind.DONE || (key.kind == KeyKind.SHIFT && shift)
    val bg by animateColorAsState(
        when {
            selected -> c.text
            accentKey -> c.accentSoft
            else -> c.text.copy(alpha = 0.07f)
        },
        Fuse.motion.tween(Durations.INSTANT),
        label = "key",
    )
    val fg = when {
        selected -> c.ink
        !enabled -> c.textFaint
        else -> c.text
    }
    Box(
        modifier
            .clip(RoundedCornerShape(Fuse.geometry.control))
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (key.icon != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(key.icon, size = 18.dp, tint = fg)
                FText(key.label, Fuse.type.label, color = fg)
            }
        } else {
            val label = if (key.kind == KeyKind.CHAR && shift) key.label.uppercase() else key.label
            FText(label, if (key.kind == KeyKind.CHAR) Fuse.type.titleSmall else Fuse.type.label, color = fg)
        }
    }
}
