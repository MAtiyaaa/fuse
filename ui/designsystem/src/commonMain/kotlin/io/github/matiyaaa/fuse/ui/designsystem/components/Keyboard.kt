package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyphDefaults
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseMotion
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import kotlin.math.roundToInt
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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.geometry.Size as GeometrySize
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
import kotlinx.coroutines.Job
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

/** A key the controller pressed: which one, and a serial so pressing it twice shows twice. */
internal data class KeyPulse(val page: KeyPage, val row: Int, val column: Int, val serial: Int)

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

    /**
     * The last key the controller pressed (A on a key, X for Delete, Y for Space, Start for Done),
     * so that key can be seen going down. Purely visual: nothing reads it to decide what to type.
     */
    internal var pulse by mutableStateOf<KeyPulse?>(null)
        private set
    private var pulses = 0

    /** True while a finger holds the focused key down, so the focus highlight dips with it. */
    internal var touching by mutableStateOf(false)

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
                showPress(row, column)
                press(rows[row][column], field, onDone, onPaste)
                return NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> {
                showPress(KeyKind.BACKSPACE)
                if (event.repeat >= WORD_DELETE_AFTER) field.deleteWordBack() else field.backspace()
                edited(field)
                return NavResult.ACTIVATED
            }
            NavAction.SEARCH -> { showPress(KeyKind.SPACE); typeSpace(field); return NavResult.ACTIVATED }
            NavAction.PREVIOUS_SECTION -> { field.moveCaret(-1); edited(field); return NavResult.MOVED }
            NavAction.NEXT_SECTION -> { field.moveCaret(1); edited(field); return NavResult.MOVED }
            NavAction.PAGE_UP -> { field.moveWord(-1); edited(field); return NavResult.MOVED }
            NavAction.PAGE_DOWN -> { field.moveWord(1); edited(field); return NavResult.MOVED }
            NavAction.QUICK_MENU -> { showPress(KeyKind.DONE); onDone(); return NavResult.ACTIVATED }
            else -> return NavResult.IGNORED
        }
    }

    private fun showPress(r: Int, c: Int) {
        pulse = KeyPulse(page, r, c, ++pulses)
    }

    private fun showPress(kind: KeyKind) {
        rows.forEachIndexed { r, keys ->
            val c = keys.indexOfFirst { it.kind == kind }
            if (c >= 0) return showPress(r, c)
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
 *
 * Keys are caps with a face, a light top edge and a darker lip, so they read as things you press.
 * Focus is one highlight that glides from key to key and lifts with the spark (a tinted glow and
 * the accent bar underneath); a pressed key dips, whether a finger or the controller pressed it
 * (X dips Delete, Y dips Space, Start dips Done). Under Reduced motion the highlight moves at once
 * and nothing scales.
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
    var width by remember { mutableIntStateOf(0) }
    val rowGap = rowGapFor(keyHeight)
    Box(modifier.onSizeChanged { width = it.width }) {
        if (width > 0) KeyHighlight(state, width, keyHeight, rowGap, visible = showFocus)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(rowGap)) {
            rows.forEachIndexed { r, keys ->
                Row(Modifier.fillMaxWidth()) {
                    keys.forEachIndexed { col, key ->
                        if (key.kind == KeyKind.GAP) {
                            Spacer(Modifier.weight(key.weight))
                        } else {
                            Box(Modifier.weight(key.weight).padding(horizontal = KeyGap / 2)) {
                                KeyCap(
                                    key = key,
                                    state = state,
                                    field = field,
                                    position = KeyPosition(state.page, r, col),
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
}

/**
 * The controller shortcuts for typing, to sit under an [OnScreenKeyboard]: X deletes, Y types a
 * space, LB and RB move the cursor and Start finishes. Glyphs follow the pad's style.
 */
@Composable
fun OnScreenKeyboardHints(modifier: Modifier = Modifier, doneLabel: String = "Done") {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
        KeyboardHint(listOf(HintButton.OPTIONS), "Delete")
        KeyboardHint(listOf(HintButton.SEARCH), "Space")
        KeyboardHint(listOf(HintButton.PREV, HintButton.NEXT), "Move cursor")
        KeyboardHint(listOf(HintButton.MENU), doneLabel)
    }
}

@Composable
private fun KeyboardHint(buttons: List<HintButton>, label: String) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        buttons.forEachIndexed { i, b ->
            if (i > 0) Spacer(Modifier.width(Space.xxs))
            ButtonGlyph(b, size = ButtonGlyphDefaults.SmallSize, color = c.textMuted)
        }
        Spacer(Modifier.width(Space.s))
        FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/** The gap between keys in a row. */
private val KeyGap = Space.s

/**
 * The gap between rows: a little more than across, as on a phone, which leaves room for the
 * spark's bar under a focused key. Short keyboards (handhelds) keep the rows tight.
 */
private fun rowGapFor(keyHeight: Dp): Dp = if (keyHeight >= 40.dp) Space.m else Space.s

/** Which key a cap is, so a controller press can find it. */
private data class KeyPosition(val page: KeyPage, val row: Int, val column: Int)

/** Where key ([r], [c]) sits in a keyboard [width] wide, from the same weights the rows lay out with. */
private fun keyRect(rows: List<List<Key>>, r: Int, c: Int, width: Float, keyHeight: Float, gap: Float, rowGap: Float): Rect {
    val row = rows[r]
    val total = row.sumOf { it.weight.toDouble() }.toFloat()
    val start = row.take(c).sumOf { it.weight.toDouble() }.toFloat()
    val left = width * start / total + gap / 2
    val right = width * (start + row[c].weight) / total - gap / 2
    val top = r * (keyHeight + rowGap)
    return Rect(left, top, right, top + keyHeight)
}

/** How far a focused key lifts, a little less than a tile: keys sit close together. */
private fun FuseMotion.keyLift(): Float = 1f + (focusScale - 1f) * 0.7f

/**
 * The focus: one lit cap under the keys that glides to the focused key, lifted, with a glow tinted
 * by the accent and the spark's accent bar beneath. The focused key's own face fades away above it,
 * so its label sits on the highlight.
 */
@Composable
private fun BoxScope.KeyHighlight(state: KeyboardState, width: Int, keyHeight: Dp, rowGap: Dp, visible: Boolean) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val localDensity = LocalDensity.current
    val rows = state.rows
    val r = state.row.coerceIn(0, rows.lastIndex)
    val col = state.column.coerceIn(0, rows[r].lastIndex)
    val target = with(localDensity) { keyRect(rows, r, col, width.toFloat(), keyHeight.toPx(), KeyGap.toPx(), rowGap.toPx()) }
    val rect = remember { Animatable(target, Rect.VectorConverter) }
    val shown by animateFloatAsState(if (visible) 1f else 0f, motion.fade(Durations.FAST), label = "keyFocusShown")
    val lift by animateFloatAsState(if (visible) 1f else 0f, motion.focusSpring(), label = "keyFocusLift")
    LaunchedEffect(target, visible) {
        // Glide from key to key; appear where the focus is (never fly in from somewhere else).
        if (!visible || shown < 0.5f || motion.reduced) rect.snapTo(target)
        else rect.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 1100f))
    }
    val dip = rememberKeyDip(state, KeyPosition(state.page, r, col), state.touching)
    val accent = c.accent
    val face = c.text
    val lip = lerp(c.text, c.ink, 0.38f)
    val light = Color.White.copy(alpha = if (c.isDark) 0f else 0.28f)
    val radius = Fuse.geometry.control
    val shape = RoundedCornerShape(radius)
    val scaleTo = motion.keyLift()
    val reduced = motion.reduced
    Box(
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val box = rect.value
                val placeable = measurable.measure(Constraints.fixed(box.width.roundToInt().coerceAtLeast(0), box.height.roundToInt().coerceAtLeast(0)))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(box.left.toInt(), box.top.toInt()) }
            }
            .graphicsLayer {
                val box = rect.value
                val d = dip.value
                // Whole pixels come from layout; the rest glides here, so slow moves stay smooth.
                translationX = box.left - box.left.toInt()
                translationY = box.top - box.top.toInt() + if (reduced) 0f else d * KeyLip.toPx()
                // Every key lifts by the same few dp, so the long Space bar doesn't balloon.
                val grow = (scaleTo - 1f) * lift * box.height
                val press = if (reduced) 1f else 1f - 0.04f * d
                scaleX = (1f + grow / box.width.coerceAtLeast(1f)) * press
                scaleY = (1f + grow / box.height.coerceAtLeast(1f)) * press
                alpha = shown
                shadowElevation = (2f + 10f * lift * (1f - d)) * density
                this.shape = shape
                clip = false
                spotShadowColor = lerp(accent, Color.Black, 0.45f)
                ambientShadowColor = lerp(accent, Color.Black, 0.7f).copy(alpha = 0.35f)
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val lipPx = KeyLip.toPx()
                val corner = CornerRadius(radius.toPx().coerceAtMost(h / 2))
                val body = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, corner)) }
                val top = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h - lipPx, corner)) }
                val edge = Brush.verticalGradient(0f to light, 0.4f to Color.Transparent, endY = h)
                val edgeStroke = Stroke(2.dp.toPx())
                // The bar sits a quarter of the way into the gap below; tight rows get a slimmer one.
                val barGap = rowGap.toPx() / 4
                val barH = Size.sparkHeight.toPx() * (if (rowGap < Space.m) 0.8f else 1f)
                onDrawBehind {
                    drawPath(body, lip)
                    drawPath(top, face)
                    clipPath(top) { drawPath(top, edge, style = edgeStroke) }
                    // The spark's accent bar, grown from the middle as the key lifts.
                    val barW = Size.sparkWidth.toPx() * 0.8f * lift
                    if (barW > 0.5f) {
                        drawRoundRect(accent, Offset((w - barW) / 2, h + barGap), GeometrySize(barW, barH), CornerRadius(barH / 2), alpha = lift)
                    }
                }
            },
    )
}

/** The lip under a key's face. */
private val KeyLip = 2.dp

/** Keys that do something rather than type: a quieter face, so the letters lead. */
private val FunctionKeys = setOf(KeyKind.SHIFT, KeyKind.BACKSPACE, KeyKind.PAGE, KeyKind.PASTE, KeyKind.SPACE)

/**
 * How far down a key is pressed, 0 to 1: held by a finger ([held]), or a quick dip when the
 * controller pressed it (the keyboard's [KeyboardState.pulse]).
 */
@Composable
private fun rememberKeyDip(state: KeyboardState, at: KeyPosition, held: Boolean): Animatable<Float, AnimationVector1D> {
    val motion = Fuse.motion
    val dip = remember { Animatable(0f) }
    val pulse = state.pulse
    // The last press this key has shown, so a press is shown once (and none on first appearing).
    val seen = remember { intArrayOf(pulse?.serial ?: -1) }
    LaunchedEffect(pulse, held, at) {
        val down = tween<Float>(motion.ms(Durations.INSTANT) / 2, easing = Easings.Standard)
        if (held) {
            dip.animateTo(1f, down)
            return@LaunchedEffect
        }
        val mine = pulse != null && pulse.serial != seen[0] && pulse.page == at.page && pulse.row == at.row && pulse.column == at.column
        if (pulse != null) seen[0] = pulse.serial
        if (mine) dip.animateTo(1f, down)
        // Always come back up, even when another press interrupted this one.
        dip.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 700f))
    }
    return dip
}

@Composable
private fun KeyCap(
    key: Key,
    state: KeyboardState,
    field: EditableText,
    position: KeyPosition,
    doneLabel: String,
    selected: Boolean,
    enabled: Boolean,
    height: Dp,
    onFocus: () -> Unit,
    onPress: () -> Unit,
    onKey: () -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val function = key.kind in FunctionKeys
    val shiftOnce = key.kind == KeyKind.SHIFT && state.shift == ShiftState.ONCE
    val shiftLock = key.kind == KeyKind.SHIFT && state.shift == ShiftState.LOCK
    val solid = key.kind == KeyKind.DONE || shiftLock
    val dark = c.isDark
    val faceColor = when {
        solid -> c.accent
        shiftOnce -> c.accent.copy(alpha = 0.2f)
        function -> c.text.copy(alpha = if (dark) 0.065f else 0.075f)
        else -> c.text.copy(alpha = if (dark) 0.115f else 0.11f)
    }
    val face by animateColorAsState(
        when {
            // The glide highlight shows through where the focused key's face was.
            selected -> faceColor.copy(alpha = 0f)
            (hovered || pressed) && !solid && enabled -> lerp(faceColor, c.text, 0.08f).copy(alpha = faceColor.alpha + 0.06f)
            else -> faceColor
        },
        motion.tween(Durations.INSTANT),
        label = "keyFace",
    )
    val fg by animateColorAsState(
        when {
            selected -> c.ink
            !enabled -> c.textFaint
            solid -> c.onAccent
            shiftOnce -> c.accent
            else -> c.text
        },
        motion.tween(Durations.INSTANT),
        label = "keyInk",
    )
    val lipColor = when {
        selected -> Color.Transparent
        solid -> lerp(c.accent, Color.Black, 0.32f)
        else -> Color.Black.copy(alpha = if (dark) 0.3f else 0.1f)
    }
    val lift by animateFloatAsState(if (selected) 1f else 0f, motion.focusSpring(), label = "keyLift")
    val dim by animateFloatAsState(if (state.trackpad && key.kind != KeyKind.SPACE) 0.35f else 1f, motion.fade(Durations.FAST), label = "keyDim")
    val dip = rememberKeyDip(state, position, held = pressed)
    val light = Color.White.copy(alpha = if (dark) 0.09f else 0.6f)
    val radius = Fuse.geometry.control
    val scaleTo = motion.keyLift()
    val reduced = motion.reduced
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
            .hoverable(hover, enabled)
            .pointerInput(key, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onFocus()
                    state.touching = true
                    // Released however the gesture ends, so no key (or the highlight) stays down
                    // and Delete never keeps repeating.
                    var repeat: Job? = null
                    try {
                        when (key.kind) {
                            KeyKind.BACKSPACE -> {
                                // Deletes at once, repeats after a moment and then takes whole words.
                                onKey()
                                state.deleteHeld(field, 0)
                                repeat = scope.launch {
                                    delay(420)
                                    var n = 1
                                    while (true) {
                                        state.deleteHeld(field, n++)
                                        delay(if (n > WORD_DELETE_AFTER) 170 else 75)
                                    }
                                }
                                waitForUpOrCancellation()
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
                    } finally {
                        repeat?.cancel()
                        pressed = false
                        state.touching = false
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = dim
                    if (!reduced) {
                        val s = 1f - 0.04f * dip.value
                        scaleX = s
                        scaleY = s
                    }
                }
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val lipPx = KeyLip.toPx()
                    val corner = CornerRadius(radius.toPx().coerceAtMost(h / 2))
                    val top = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h - lipPx, corner)) }
                    val under = Path().apply { addRoundRect(RoundRect(0f, lipPx, w, h, corner)) }
                    val edge = Brush.verticalGradient(0f to light, 0.4f to Color.Transparent, endY = h)
                    val edgeStroke = Stroke(2.dp.toPx())
                    onDrawBehind {
                        // The lip shows below the face; pressing slides the face down over it.
                        if (lipColor.alpha > 0f) clipPath(top, ClipOp.Difference) { drawPath(under, lipColor) }
                        if (face.alpha > 0f) {
                            translate(top = if (reduced) 0f else dip.value * lipPx) {
                                drawPath(top, face)
                                clipPath(top) { drawPath(top, edge, style = edgeStroke, alpha = (face.alpha / faceColor.alpha.coerceAtLeast(0.01f)).coerceIn(0f, 1f)) }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(bottom = KeyLip)
                    .graphicsLayer {
                        if (!reduced) {
                            val s = 1f + (scaleTo - 1f) * lift
                            scaleX = s
                            scaleY = s
                            translationY = dip.value * KeyLip.toPx()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                KeyLabel(key, label, state, fg)
            }
        }
        // The pressed letter pops up above the finger.
        if (pressed && key.kind == KeyKind.CHAR) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = -height - Space.xs)
                    .size(width = height * 1.05f, height = height * 1.1f)
                    .shadow(12.dp, RoundedCornerShape(radius))
                    .clip(RoundedCornerShape(radius))
                    .background(c.surfaceRaised)
                    .drawBehind {
                        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.08f else 0f), 0.5f to Color.Transparent))
                    },
                contentAlignment = Alignment.Center,
            ) {
                FText(label, Fuse.type.title, color = c.text, maxLines = 1)
            }
        }
    }
}

/** What a key shows: its character, or an icon for the keys that do something. */
@Composable
private fun KeyLabel(key: Key, label: String, state: KeyboardState, fg: Color) {
    when {
        key.kind == KeyKind.SPACE && state.trackpad -> FuseIcon(FuseIcons.MoveHorizontal, size = Size.iconM, tint = fg)
        // Lucide's space bar sits low in its box; lift it to the key's optical middle.
        key.kind == KeyKind.SPACE -> FuseIcon(FuseIcons.Space, Modifier.offset(y = -Size.iconL * 0.22f), size = Size.iconL, tint = fg)
        key.kind == KeyKind.SHIFT -> FuseIcon(if (state.shift == ShiftState.LOCK) FuseIcons.CapsLock else FuseIcons.Shift, size = Size.iconM, tint = fg)
        key.kind == KeyKind.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
            FuseIcon(FuseIcons.Return, size = Size.iconS, tint = fg)
            Spacer(Modifier.width(Space.s))
            FText(label, Fuse.type.label, color = fg, maxLines = 1)
        }
        key.icon != null -> FuseIcon(key.icon, size = Size.iconM, tint = fg)
        key.kind == KeyKind.CHAR -> FText(label, Fuse.type.titleSmall, color = fg, maxLines = 1)
        else -> FText(label, Fuse.type.label, color = fg, maxLines = 1)
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
    val motion = Fuse.motion
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
    val shape = RoundedCornerShape(Fuse.geometry.control)
    // The edge firms up while typing goes here, so it is clear where the keys write.
    val edge by animateColorAsState(
        if (focused) c.text.copy(alpha = if (c.isDark) 0.2f else 0.26f) else c.hairline,
        motion.tween(Durations.FAST),
        label = "fieldEdge",
    )
    val icon by animateColorAsState(if (focused) c.text else c.textMuted, motion.tween(Durations.FAST), label = "fieldIcon")
    Row(
        modifier
            .clip(shape)
            .background(c.text.copy(alpha = if (c.isDark) 0.07f else 0.06f))
            // A well, the opposite of a key: a soft shade just inside the top edge.
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = if (c.isDark) 0.2f else 0.05f), 0.3f to Color.Transparent))
            .border(Size.stroke, edge, shape)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            FuseIcon(leading, tint = icon)
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
                                drawRoundRect(c.accent, Offset(r.left - w / 2, r.top), GeometrySize(w, r.height), CornerRadius(w / 2), alpha = blink.value)
                            }
                        },
                )
            }
        }
        if (onClear != null) {
            // The clear button pops in with the first character and out with the last.
            AnimatedVisibility(
                visible = value.text.isNotEmpty(),
                enter = fadeIn(motion.fade(Durations.FAST)) + if (motion.reduced) EnterTransition.None else scaleIn(motion.tween(Durations.FAST, Easings.Enter), initialScale = 0.6f),
                exit = fadeOut(motion.fade(Durations.INSTANT)) + if (motion.reduced) ExitTransition.None else scaleOut(motion.tween(Durations.INSTANT, Easings.Exit), targetScale = 0.6f),
            ) {
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val pressed by interaction.collectIsPressedAsState()
                val fill by animateColorAsState(
                    c.text.copy(alpha = if (pressed) 0.28f else if (hovered) 0.22f else 0.16f),
                    motion.tween(Durations.INSTANT),
                    label = "clearFill",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(Space.s))
                    Box(
                        Modifier
                            .size(26.dp)
                            .graphicsLayer { if (!motion.reduced) { val k = if (pressed) 0.92f else 1f; scaleX = k; scaleY = k } }
                            .clip(CircleShape)
                            .background(fill)
                            .hoverable(interaction)
                            .clickable(interaction, null, onClick = onClear),
                        contentAlignment = Alignment.Center,
                    ) {
                        FuseIcon(FuseIcons.Close, size = 14.dp, tint = c.text)
                    }
                }
            }
        }
    }
}
