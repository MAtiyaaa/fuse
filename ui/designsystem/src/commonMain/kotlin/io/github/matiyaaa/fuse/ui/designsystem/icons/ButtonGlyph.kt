package io.github.matiyaaa.fuse.ui.designsystem.icons

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.GlyphConfig
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** A controller function shown in hint lines. The glyph drawn depends on the pad style and layout. */
enum class HintButton {
    CONFIRM, BACK, OPTIONS, SEARCH, MENU, VIEW, PREV, NEXT, PAGE_PREV, PAGE_NEXT, DPAD, HOLD_CONFIRM,

    /** Options held down (resizing a widget with the D-pad). */
    HOLD_OPTIONS,

    /** The left stick (moving, scrolling). Keyboards show the arrow keys. */
    LEFT_STICK,

    /** The right stick. Keyboards show the arrow keys. */
    RIGHT_STICK,
}

/** Sizes for [ButtonGlyph] and [PadGlyph], matched optically to the text they sit beside. */
object ButtonGlyphDefaults {
    /** Beside `label` text, as in the hint line. */
    val Size: Dp = 22.dp

    /** Beside `caption` text: keyboard shortcuts, the shoulder hints either side of the HUD tabs. */
    val SmallSize: Dp = 18.dp
}

/** Physical face-button positions. */
private enum class Face { SOUTH, EAST, WEST, NORTH }

private fun faceFor(button: HintButton, confirmOnRight: Boolean): Face? = when (button) {
    HintButton.CONFIRM, HintButton.HOLD_CONFIRM -> if (confirmOnRight) Face.EAST else Face.SOUTH
    HintButton.BACK -> if (confirmOnRight) Face.SOUTH else Face.EAST
    HintButton.OPTIONS, HintButton.HOLD_OPTIONS -> if (confirmOnRight) Face.NORTH else Face.WEST
    HintButton.SEARCH -> if (confirmOnRight) Face.WEST else Face.NORTH
    else -> null
}

private fun faceLabel(face: Face, style: GlyphStyle): String = when (style) {
    GlyphStyle.XBOX, GlyphStyle.KEYBOARD -> when (face) { Face.SOUTH -> "A"; Face.EAST -> "B"; Face.WEST -> "X"; Face.NORTH -> "Y" }
    GlyphStyle.NINTENDO -> when (face) { Face.SOUTH -> "B"; Face.EAST -> "A"; Face.WEST -> "Y"; Face.NORTH -> "X" }
    GlyphStyle.PLAYSTATION -> ""
}

private fun keyboardLabel(button: HintButton): String = when (button) {
    HintButton.CONFIRM, HintButton.HOLD_CONFIRM -> "Enter"
    HintButton.BACK -> "Esc"
    HintButton.OPTIONS, HintButton.HOLD_OPTIONS -> "Tab"
    HintButton.SEARCH -> "F"
    HintButton.MENU -> "M"
    HintButton.VIEW -> "Tab"
    HintButton.PREV -> "Q"
    HintButton.NEXT -> "E"
    HintButton.PAGE_PREV -> "PgUp"
    HintButton.PAGE_NEXT -> "PgDn"
    HintButton.DPAD, HintButton.LEFT_STICK, HintButton.RIGHT_STICK -> ""
}

private fun shoulderLabel(left: Boolean, trigger: Boolean, style: GlyphStyle): String = when (style) {
    GlyphStyle.NINTENDO -> if (trigger) (if (left) "ZL" else "ZR") else (if (left) "L" else "R")
    GlyphStyle.PLAYSTATION -> if (trigger) (if (left) "L2" else "R2") else (if (left) "L1" else "R1")
    else -> if (trigger) (if (left) "LT" else "RT") else (if (left) "LB" else "RB")
}

/** The silhouette a glyph is drawn on. */
private enum class Body { DISC, HOLD, CAPSULE, BUMPER, TRIGGER, KEYCAP, DPAD, STICK, ARROWS }

/** Line drawings inside a body, all plain geometry. */
private enum class Mark { NONE, CROSS, CIRCLE, SQUARE, TRIANGLE, LINES, PANES, PLUS, MINUS, RING }

private enum class Dir { UP, DOWN, LEFT, RIGHT }

/** What to draw: a body, a label or a mark on it, which side it belongs to and an optional lit direction. */
@Immutable
private data class Glyph(
    val body: Body,
    val label: String = "",
    val mark: Mark = Mark.NONE,
    val left: Boolean = true,
    val lit: Dir? = null,
)

private fun faceGlyph(face: Face, style: GlyphStyle, hold: Boolean = false): Glyph {
    val body = if (hold) Body.HOLD else Body.DISC
    return if (style == GlyphStyle.PLAYSTATION) {
        Glyph(body, mark = when (face) { Face.SOUTH -> Mark.CROSS; Face.EAST -> Mark.CIRCLE; Face.WEST -> Mark.SQUARE; Face.NORTH -> Mark.TRIANGLE })
    } else {
        Glyph(body, faceLabel(face, style))
    }
}

private fun hintGlyph(button: HintButton, glyphs: GlyphConfig): Glyph {
    val style = glyphs.style
    if (style == GlyphStyle.KEYBOARD) {
        return when (button) {
            HintButton.DPAD, HintButton.LEFT_STICK, HintButton.RIGHT_STICK -> Glyph(Body.ARROWS)
            else -> Glyph(Body.KEYCAP, keyboardLabel(button))
        }
    }
    val nintendo = style == GlyphStyle.NINTENDO
    return when (button) {
        HintButton.PREV, HintButton.NEXT ->
            Glyph(Body.BUMPER, shoulderLabel(button == HintButton.PREV, trigger = false, style), left = button == HintButton.PREV)
        HintButton.PAGE_PREV, HintButton.PAGE_NEXT ->
            Glyph(Body.TRIGGER, shoulderLabel(button == HintButton.PAGE_PREV, trigger = true, style), left = button == HintButton.PAGE_PREV)
        HintButton.MENU -> Glyph(Body.CAPSULE, mark = if (nintendo) Mark.PLUS else Mark.LINES)
        HintButton.VIEW -> Glyph(Body.CAPSULE, mark = if (nintendo) Mark.MINUS else Mark.PANES)
        HintButton.DPAD -> Glyph(Body.DPAD)
        HintButton.LEFT_STICK -> Glyph(Body.STICK, "L", left = true)
        HintButton.RIGHT_STICK -> Glyph(Body.STICK, "R", left = false)
        else -> faceGlyph(faceFor(button, glyphs.confirmOnRight) ?: Face.SOUTH, style, hold = button == HintButton.HOLD_CONFIRM || button == HintButton.HOLD_OPTIONS)
    }
}

private fun padGlyph(button: PadButton, glyphStyle: GlyphStyle): Glyph {
    // A pad button is a pad button: the keyboard style still letters the pad.
    val style = if (glyphStyle == GlyphStyle.KEYBOARD) GlyphStyle.XBOX else glyphStyle
    val nintendo = style == GlyphStyle.NINTENDO
    fun face(face: Face, letter: String) =
        if (style == GlyphStyle.PLAYSTATION) faceGlyph(face, style) else Glyph(Body.DISC, letter)
    return when (button) {
        // Android reports the PlayStation-style cross as A, circle as B, square as X and triangle as Y.
        PadButton.A -> face(Face.SOUTH, "A")
        PadButton.B -> face(Face.EAST, "B")
        PadButton.X -> face(Face.WEST, "X")
        PadButton.Y -> face(Face.NORTH, "Y")
        PadButton.L1 -> Glyph(Body.BUMPER, shoulderLabel(true, trigger = false, style), left = true)
        PadButton.R1 -> Glyph(Body.BUMPER, shoulderLabel(false, trigger = false, style), left = false)
        PadButton.L2 -> Glyph(Body.TRIGGER, shoulderLabel(true, trigger = true, style), left = true)
        PadButton.R2 -> Glyph(Body.TRIGGER, shoulderLabel(false, trigger = true, style), left = false)
        PadButton.L3 -> Glyph(Body.STICK, "L", left = true)
        PadButton.R3 -> Glyph(Body.STICK, "R", left = false)
        PadButton.START -> Glyph(Body.CAPSULE, mark = if (nintendo) Mark.PLUS else Mark.LINES)
        PadButton.SELECT -> Glyph(Body.CAPSULE, mark = if (nintendo) Mark.MINUS else Mark.PANES)
        PadButton.MODE -> Glyph(Body.DISC, mark = Mark.RING)
        PadButton.DPAD_UP -> Glyph(Body.DPAD, lit = Dir.UP)
        PadButton.DPAD_DOWN -> Glyph(Body.DPAD, lit = Dir.DOWN)
        PadButton.DPAD_LEFT -> Glyph(Body.DPAD, lit = Dir.LEFT)
        PadButton.DPAD_RIGHT -> Glyph(Body.DPAD, lit = Dir.RIGHT)
        PadButton.KEY_UP -> Glyph(Body.ARROWS, lit = Dir.UP)
        PadButton.KEY_DOWN -> Glyph(Body.ARROWS, lit = Dir.DOWN)
        PadButton.KEY_LEFT -> Glyph(Body.ARROWS, lit = Dir.LEFT)
        PadButton.KEY_RIGHT -> Glyph(Body.ARROWS, lit = Dir.RIGHT)
        PadButton.KEY_ENTER -> Glyph(Body.KEYCAP, "Enter")
        PadButton.KEY_ESCAPE -> Glyph(Body.KEYCAP, "Esc")
        PadButton.KEY_BACKSPACE -> Glyph(Body.KEYCAP, "Bksp")
        PadButton.KEY_TAB -> Glyph(Body.KEYCAP, "Tab")
        PadButton.KEY_SPACE -> Glyph(Body.KEYCAP, "Space")
        PadButton.KEY_SLASH -> Glyph(Body.KEYCAP, "/")
        PadButton.KEY_F -> Glyph(Body.KEYCAP, "F")
        PadButton.KEY_Q -> Glyph(Body.KEYCAP, "Q")
        PadButton.KEY_E -> Glyph(Body.KEYCAP, "E")
        PadButton.KEY_M -> Glyph(Body.KEYCAP, "M")
        PadButton.KEY_HOME -> Glyph(Body.KEYCAP, "Home")
        PadButton.KEY_PAGE_UP -> Glyph(Body.KEYCAP, "PgUp")
        PadButton.KEY_PAGE_DOWN -> Glyph(Body.KEYCAP, "PgDn")
        PadButton.RSTICK_LEFT, PadButton.RSTICK_RIGHT, PadButton.RSTICK_UP, PadButton.RSTICK_DOWN -> Glyph(Body.STICK, "R", left = false)
        PadButton.KEY_BRACKET_LEFT -> Glyph(Body.KEYCAP, "[")
        PadButton.KEY_BRACKET_RIGHT -> Glyph(Body.KEYCAP, "]")
    }
}

/**
 * The glyph for a controller function in hint lines, in the pad's own style: a disc with a letter
 * (Xbox or Nintendo lettering, following the layout swap) or a plain shape (PlayStation style) for
 * face buttons, a ringed disc for holding confirm, swept bumpers and domed triggers for the
 * shoulders, a small capsule for the centre buttons, a cross for the D-pad, a capped well for the
 * sticks, and keycaps (with the arrow cluster for movement) for keyboards.
 *
 * Every glyph is drawn in code as an object, like Fuse's tiles: a soft fill, a light top edge and a
 * little shade underneath. [emphasized] fills it solid (the hint line's confirm). Letters are
 * centred on their cap height, so they sit in the optical middle. Nothing here is console maker
 * artwork: the shapes are plain geometry.
 */
@Composable
fun ButtonGlyph(
    button: HintButton,
    glyphs: GlyphConfig = Fuse.glyphs,
    size: Dp = ButtonGlyphDefaults.Size,
    color: Color = Fuse.colors.text,
    emphasized: Boolean = false,
    modifier: Modifier = Modifier,
) {
    GlyphView(hintGlyph(button, glyphs), size, color, emphasized, modifier)
}

/**
 * The glyph for one physical button ([PadButton]) in the pad's style, for screens that talk about
 * the hardware itself (a controller test, input settings) rather than about an action. D-pad and
 * arrow-key buttons light their own direction. See [ButtonGlyph] for the look.
 */
@Composable
fun PadGlyph(
    button: PadButton,
    modifier: Modifier = Modifier,
    style: GlyphStyle = Fuse.glyphs.style,
    size: Dp = ButtonGlyphDefaults.Size,
    color: Color = Fuse.colors.text,
    emphasized: Boolean = false,
) {
    GlyphView(padGlyph(button, style), size, color, emphasized, modifier)
}

/** Cap heights (fraction of the em) of Sora and Manrope, measured from the bundled fonts. */
private const val SORA_CAP = 0.73f
private const val MANROPE_CAP = 0.72f

@Composable
private fun GlyphView(glyph: Glyph, size: Dp, color: Color, emphasized: Boolean, modifier: Modifier) {
    val colors = Fuse.colors
    val type = Fuse.type
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val keycap = glyph.body == Body.KEYCAP
    // Letters: Sora (the display and numbers face) on pad buttons, Manrope on keycaps. Two-letter
    // shoulder labels get a touch more size at small sizes, where they would otherwise blur.
    val ratio = when (glyph.body) {
        Body.DISC -> 0.5f
        Body.HOLD -> 0.38f
        Body.STICK -> 0.3f
        Body.KEYCAP -> 0.42f
        Body.BUMPER, Body.TRIGGER -> if (size < ButtonGlyphDefaults.Size) 0.44f else 0.4f
        else -> 0.4f
    }
    val family = if (keycap) type.label.fontFamily else type.title.fontFamily
    val fontSize = with(density) { (size * ratio).toSp() }
    val layout: TextLayoutResult? = if (glyph.label.isEmpty()) null else remember(glyph.label, family, fontSize, density) {
        measurer.measure(
            glyph.label,
            TextStyle(
                fontFamily = family,
                fontWeight = if (keycap) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = fontSize,
                letterSpacing = if (glyph.label.length > 1) 0.02.em else 0.em,
            ),
            softWrap = false,
            maxLines = 1,
        )
    }
    val capHeight = with(density) { fontSize.toPx() } * (if (keycap) MANROPE_CAP else SORA_CAP)
    val sizePx = with(density) { size.toPx() }
    val textWidth = layout?.size?.width?.toFloat() ?: 0f
    val minWidth = sizePx * when (glyph.body) {
        Body.CAPSULE -> 1.22f
        Body.BUMPER -> 1.5f
        Body.TRIGGER -> 1.24f
        Body.KEYCAP -> 1.3f
        Body.ARROWS -> 1.56f
        else -> 1f
    }
    val padding = sizePx * when (glyph.body) {
        Body.KEYCAP -> 0.3f
        Body.BUMPER -> 0.36f
        Body.TRIGGER -> 0.3f
        else -> 0f
    }
    val width = with(density) { ceil(max(minWidth, textWidth + padding * 2)).toDp() }

    val a = color.alpha
    val dark = colors.isDark
    // Light rooms need less fill and shade for the same weight: dark on paper reads heavier.
    val paint = GlyphPaint(
        fill = if (emphasized) color else color.copy(alpha = a * if (dark) 0.18f else 0.12f),
        lip = if (emphasized) lerp(color, Color.Black, 0.3f) else color.copy(alpha = a * if (dark) 0.11f else 0.09f),
        ink = if (emphasized) colors.ink else color,
        light = Color.White.copy(alpha = a * if (dark) (if (emphasized) 0.55f else 0.32f) else 0.75f),
        shade = Color.Black.copy(alpha = a * if (emphasized) 0.16f else if (dark) 0.1f else 0.05f),
        track = color.copy(alpha = a * 0.24f),
        solid = color,
        onSolid = colors.ink,
    )

    Spacer(
        modifier
            .size(width, size)
            .drawWithCache {
                val ops = GlyphBuilder(this.size.width, this.size.height, density.density, paint, layout, capHeight).build(glyph)
                onDrawBehind { for (i in ops.indices) ops[i].draw(this) }
            },
    )
}

/** The colours one glyph is drawn with. */
@Immutable
private class GlyphPaint(
    val fill: Color,
    val lip: Color,
    val ink: Color,
    val light: Color,
    val shade: Color,
    val track: Color,
    /** A lit part (the pressed D-pad arm, the hold ring) is drawn solid in the glyph's colour. */
    val solid: Color,
    val onSolid: Color,
)

/**
 * One prepared drawing step. Glyphs are built into a list of these once per size (in drawWithCache),
 * so drawing a frame only replays them and never allocates.
 */
private class Op(
    val path: Path?,
    val color: Color,
    val brush: Brush? = null,
    val style: DrawStyle = Fill,
    /** Clip to [path] first, so a stroke stays inside the shape (light edges). */
    val inside: Boolean = false,
    val text: TextLayoutResult? = null,
    val at: Offset = Offset.Zero,
) {
    fun draw(scope: DrawScope) = with(scope) {
        when {
            text != null -> drawText(text, color = color, topLeft = at)
            inside -> clipPath(path!!) { paint(this) }
            else -> paint(this)
        }
    }

    private fun paint(scope: DrawScope) = with(scope) {
        if (brush != null) drawPath(path!!, brush, style = style) else drawPath(path!!, color, style = style)
    }
}

private fun oval(cx: Float, cy: Float, r: Float) = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }

private fun roundRect(r: RoundRect) = Path().apply { addRoundRect(r) }

/** Builds the drawing steps for one glyph at one size. */
private class GlyphBuilder(
    val w: Float,
    val h: Float,
    val density: Float,
    val p: GlyphPaint,
    val layout: TextLayoutResult?,
    val capHeight: Float,
) {
    private val ops = ArrayList<Op>()

    /** The light edge: a little under a dp, inside the shape. */
    private val edge = max(1f, density * 0.9f)

    /** Line weight for marks, matched to the icon set's 1.8 on 24. */
    private val stroke = max(1.25f * density, h * 0.075f)

    fun build(glyph: Glyph): List<Op> {
        val cx = w / 2
        val cy = h / 2
        when (glyph.body) {
            Body.DISC -> {
                objectOf(oval(cx, cy, h / 2), 0f, h)
                content(glyph, cx, cy, h * 0.4f)
            }
            Body.HOLD -> {
                // A three-quarter ring around a smaller disc: hold it, and it fills.
                val ring = stroke
                val r = h / 2 - ring / 2
                ops += Op(oval(cx, cy, r), p.track, style = Stroke(ring))
                ops += Op(Path().apply { addArc(Rect(cx - r, cy - r, cx + r, cy + r), -90f, 270f) }, p.solid, style = Stroke(ring, cap = StrokeCap.Round))
                val inner = h / 2 - ring - max(ring * 0.75f, 1f)
                objectOf(oval(cx, cy, inner), cy - inner, cy + inner)
                content(glyph, cx, cy, inner * 0.95f)
            }
            Body.CAPSULE -> {
                val ch = h * 0.76f
                val top = (h - ch) / 2
                objectOf(roundRect(RoundRect(0f, top, w, top + ch, CornerRadius(ch / 2))), top, top + ch)
                content(glyph, cx, cy, ch * 0.56f)
            }
            Body.BUMPER -> {
                // Swept on the outer top corner, like the shoulder it stands for.
                val bh = h * 0.8f
                val top = (h - bh) / 2
                val outer = CornerRadius(bh * 0.9f, bh * 0.62f)
                val inner = CornerRadius(bh * 0.3f)
                val shape = RoundRect(
                    0f, top, w, top + bh,
                    topLeftCornerRadius = if (glyph.left) outer else inner,
                    topRightCornerRadius = if (glyph.left) inner else outer,
                    bottomRightCornerRadius = inner,
                    bottomLeftCornerRadius = inner,
                )
                objectOf(roundRect(shape), top, top + bh)
                content(glyph, cx, cy + bh * 0.03f, bh * 0.4f)
            }
            Body.TRIGGER -> {
                // Taller than a bumper, with a domed top that moves its optical middle down.
                val outer = CornerRadius(w * 0.5f, h * 0.6f)
                val inner = CornerRadius(h * 0.32f)
                val foot = CornerRadius(h * 0.2f)
                val shape = RoundRect(
                    0f, 0f, w, h,
                    topLeftCornerRadius = if (glyph.left) outer else inner,
                    topRightCornerRadius = if (glyph.left) inner else outer,
                    bottomRightCornerRadius = foot,
                    bottomLeftCornerRadius = foot,
                )
                objectOf(roundRect(shape), 0f, h)
                content(glyph, cx, h * 0.55f, h * 0.4f)
            }
            Body.KEYCAP -> {
                // A face on a darker lip: a key you could press. The face is centred on the line, so
                // its label sits level with the text beside it, and the lip hangs below.
                val lip = max(1.5f * density, h * 0.09f)
                val r = CornerRadius(h * 0.26f)
                ops += Op(roundRect(RoundRect(0f, lip, w, h, r)), p.lip)
                objectOf(roundRect(RoundRect(0f, lip, w, h - lip, r)), lip, h - lip)
                content(glyph, cx, cy, h * 0.4f)
            }
            Body.ARROWS -> {
                val gap = max(density, h * 0.09f)
                val k = (h - gap) / 2
                val x0 = (w - (3 * k + 2 * gap)) / 2
                val keyLip = max(density, k * 0.13f)
                val r = CornerRadius(k * 0.26f)
                val keys = listOf(
                    Dir.UP to Offset(x0 + k + gap, 0f),
                    Dir.LEFT to Offset(x0, k + gap),
                    Dir.DOWN to Offset(x0 + k + gap, k + gap),
                    Dir.RIGHT to Offset(x0 + 2 * (k + gap), k + gap),
                )
                for ((dir, o) in keys) {
                    val lit = glyph.lit == dir
                    ops += Op(roundRect(RoundRect(o.x, o.y, o.x + k, o.y + k, r)), if (lit) lerp(p.solid, Color.Black, 0.3f) else p.lip)
                    val face = roundRect(RoundRect(o.x, o.y, o.x + k, o.y + k - keyLip, r))
                    objectOf(face, o.y, o.y + k - keyLip, fill = if (lit) p.solid else p.fill)
                    arrow(dir, Offset(o.x + k / 2, o.y + (k - keyLip) / 2), k * 0.4f, if (lit) p.onSolid else p.ink)
                }
            }
            Body.DPAD -> {
                val t = h * 0.36f
                val o = (h - t) / 2
                val x0 = (w - h) / 2
                val r = CornerRadius(t * 0.28f)
                val cross = Path().apply {
                    op(roundRect(RoundRect(x0, o, x0 + h, o + t, r)), roundRect(RoundRect(x0 + o, 0f, x0 + o + t, h, r)), PathOperation.Union)
                }
                objectOf(cross, 0f, h)
                glyph.lit?.let { dir ->
                    val arm = when (dir) {
                        Dir.UP -> RoundRect(x0 + o, 0f, x0 + o + t, cy, r)
                        Dir.DOWN -> RoundRect(x0 + o, cy, x0 + o + t, h, r)
                        Dir.LEFT -> RoundRect(x0, o, cx, o + t, r)
                        Dir.RIGHT -> RoundRect(cx, o, x0 + h, o + t, r)
                    }
                    ops += Op(roundRect(arm), p.solid)
                }
                val reach = h * 0.33f
                for (dir in Dir.entries) {
                    val ink = if (glyph.lit == dir) p.onSolid else p.ink.copy(alpha = p.ink.alpha * 0.85f)
                    arrow(dir, toward(dir, cx, cy, reach), t * 0.42f, ink)
                }
            }
            Body.STICK -> {
                // The well, a lit cap in it, and four small arrows for the ways it tilts.
                val well = oval(cx, cy, h / 2)
                ops += Op(well, p.lip)
                lightEdge(well, p.light.copy(alpha = p.light.alpha * 0.6f), 0f, h)
                val r = h * 0.31f
                objectOf(oval(cx, cy, r), cy - r, cy + r)
                val ink = p.ink.copy(alpha = p.ink.alpha * 0.7f)
                for (dir in Dir.entries) arrow(dir, toward(dir, cx, cy, h * 0.415f), h * 0.11f, ink)
                content(glyph, cx, cy, r)
            }
        }
        return ops
    }

    private fun toward(dir: Dir, cx: Float, cy: Float, reach: Float) = when (dir) {
        Dir.UP -> Offset(cx, cy - reach)
        Dir.DOWN -> Offset(cx, cy + reach)
        Dir.LEFT -> Offset(cx - reach, cy)
        Dir.RIGHT -> Offset(cx + reach, cy)
    }

    /** A filled shape with a light top edge and a little shade low down: an object, not a sticker. */
    private fun objectOf(path: Path, top: Float, bottom: Float, fill: Color = p.fill) {
        ops += Op(path, fill)
        ops += Op(path, Color.Unspecified, Brush.verticalGradient(0.5f to Color.Transparent, 1f to p.shade, startY = top, endY = bottom))
        lightEdge(path, p.light, top, bottom)
    }

    private fun lightEdge(path: Path, light: Color, top: Float, bottom: Float) {
        val brush = Brush.verticalGradient(0f to light, 0.42f to light.copy(alpha = 0f), startY = top, endY = bottom)
        ops += Op(path, Color.Unspecified, brush, Stroke(edge * 2), inside = true)
    }

    /** A small solid triangle pointing [dir], centred on [at], its corners softened. */
    private fun arrow(dir: Dir, at: Offset, side: Float, color: Color) {
        val half = side / 2
        val depth = side * 0.62f
        val path = Path().apply {
            when (dir) {
                Dir.UP -> { moveTo(at.x, at.y - depth / 2); lineTo(at.x + half, at.y + depth / 2); lineTo(at.x - half, at.y + depth / 2) }
                Dir.DOWN -> { moveTo(at.x, at.y + depth / 2); lineTo(at.x + half, at.y - depth / 2); lineTo(at.x - half, at.y - depth / 2) }
                Dir.LEFT -> { moveTo(at.x - depth / 2, at.y); lineTo(at.x + depth / 2, at.y - half); lineTo(at.x + depth / 2, at.y + half) }
                Dir.RIGHT -> { moveTo(at.x + depth / 2, at.y); lineTo(at.x - depth / 2, at.y - half); lineTo(at.x - depth / 2, at.y + half) }
            }
            close()
        }
        ops += Op(path, color)
        ops += Op(path, color, style = Stroke(side * 0.2f, join = StrokeJoin.Round))
    }

    private fun line(path: Path) {
        ops += Op(path, p.ink, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /** The letter or mark, centred on ([cx], [cy]); letters on their cap height. [m] is the mark's box. */
    private fun content(glyph: Glyph, cx: Float, cy: Float, m: Float) {
        if (layout != null) {
            val x = (cx - layout.size.width / 2f).roundToInt().toFloat()
            val y = (cy + capHeight / 2f - layout.firstBaseline).roundToInt().toFloat()
            ops += Op(null, p.ink, text = layout, at = Offset(x, y))
            return
        }
        when (glyph.mark) {
            Mark.NONE -> Unit
            Mark.CROSS -> {
                val d = m * 0.4f
                line(Path().apply { moveTo(cx - d, cy - d); lineTo(cx + d, cy + d); moveTo(cx + d, cy - d); lineTo(cx - d, cy + d) })
            }
            Mark.CIRCLE -> line(oval(cx, cy, m * 0.47f))
            Mark.SQUARE -> {
                val side = m * 0.82f
                line(roundRect(RoundRect(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2, CornerRadius(stroke * 0.5f))))
            }
            Mark.TRIANGLE -> {
                // Centred between its centroid and its box, which is where the eye puts its middle.
                val side = m * 1.04f
                val th = side * 0.866f
                val c = cy + th * 0.06f
                line(Path().apply { moveTo(cx, c - th * 0.607f); lineTo(cx + side / 2, c + th * 0.393f); lineTo(cx - side / 2, c + th * 0.393f); close() })
            }
            Mark.LINES -> {
                val half = m * 0.46f
                val gap = max(stroke * 1.7f, m * 0.3f)
                line(Path().apply { for (i in -1..1) { moveTo(cx - half, cy + gap * i); lineTo(cx + half, cy + gap * i) } })
            }
            Mark.PANES -> {
                val pw = m * 1.04f
                val ph = m * 0.78f
                line(roundRect(RoundRect(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2, CornerRadius(stroke))))
                line(Path().apply { moveTo(cx - pw * 0.1f, cy - ph / 2); lineTo(cx - pw * 0.1f, cy + ph / 2) })
            }
            Mark.PLUS -> {
                val d = m * 0.44f
                line(Path().apply { moveTo(cx - d, cy); lineTo(cx + d, cy); moveTo(cx, cy - d); lineTo(cx, cy + d) })
            }
            Mark.MINUS -> {
                val d = m * 0.44f
                line(Path().apply { moveTo(cx - d, cy); lineTo(cx + d, cy) })
            }
            Mark.RING -> line(oval(cx, cy, m * 0.36f))
        }
    }
}
