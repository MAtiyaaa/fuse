package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseColors
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.shell.app.BrandArt

/**
 * A controller seen from the front, drawn in the theme's own colours: a solid body with a clean rim,
 * the bumpers and triggers along its shoulders, an outlined D-pad, the face buttons in their diamond,
 * View and Menu as small pills, two sticks, and Fuse's own mark as the button between them. Every
 * part that is held fills with the accent and glows; [marked] parts (the ones a remap is waiting on)
 * take the accent's outline.
 *
 * Shoulder, trigger and face labels follow the pad's [style] (LB and LT, L1 and L2, L and ZL; the
 * PlayStation shapes); [nintendoKeys] puts the face buttons where a pad sending Nintendo keycodes
 * has them (A on the right).
 */
@Composable
internal fun PadArt(
    pressed: Set<PadButton>,
    style: GlyphStyle,
    nintendoKeys: Boolean,
    modifier: Modifier = Modifier,
    marked: Set<PadButton> = emptySet(),
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val measurer = rememberTextMeasurer()
    // Each part's light, eased in and out so a quick tap still shows.
    val lit = PadParts.associateWith { b -> fuselineFloat(if (b in pressed) 1f else 0f, motion.tween(Durations.FAST), label = "pad").value }
    val geometry = remember { PadGeometry() }
    Canvas(modifier.aspectRatio(PAD_W / PAD_H)) {
        val s = size.width / PAD_W
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawPad(geometry, c, lit, marked, style, nintendoKeys, measurer, s)
        }
    }
}

private const val PAD_W = 400f
private const val PAD_H = 262f

/** The buttons the drawing shows. */
private val PadParts = listOf(
    PadButton.A, PadButton.B, PadButton.X, PadButton.Y,
    PadButton.L1, PadButton.R1, PadButton.L2, PadButton.R2, PadButton.L3, PadButton.R3,
    PadButton.START, PadButton.SELECT, PadButton.MODE,
    PadButton.DPAD_UP, PadButton.DPAD_DOWN, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT,
)

/** The pad's shapes, in a 400 by 262 box, built once: drawn for the left and mirrored for the right. */
private class PadGeometry {
    /** A broad, flat top that rounds over the shoulders and flares into two grips. */
    val body = Path().apply {
        moveTo(200f, 40f)
        lineTo(122f, 40f)
        cubicTo(74f, 40f, 44f, 50f, 30f, 88f)
        cubicTo(18f, 122f, 8f, 176f, 5f, 212f)
        cubicTo(2f, 242f, 22f, 258f, 44f, 257f)
        cubicTo(64f, 256f, 78f, 244f, 92f, 218f)
        cubicTo(104f, 196f, 114f, 186f, 140f, 186f)
        lineTo(260f, 186f)
        cubicTo(286f, 186f, 296f, 196f, 308f, 218f)
        cubicTo(322f, 244f, 336f, 256f, 356f, 257f)
        cubicTo(378f, 258f, 398f, 242f, 395f, 212f)
        cubicTo(392f, 176f, 382f, 122f, 370f, 88f)
        cubicTo(356f, 50f, 326f, 40f, 278f, 40f)
        close()
    }

    /** The top edge, for the light along it. */
    val ridge = Path().apply {
        moveTo(36f, 76f)
        cubicTo(50f, 48f, 78f, 40f, 122f, 40f)
        lineTo(278f, 40f)
        cubicTo(322f, 40f, 350f, 48f, 364f, 76f)
    }

    val leftBumper = bumper { it }
    val rightBumper = bumper { PAD_W - it }
    val leftTrigger = trigger { it }
    val rightTrigger = trigger { PAD_W - it }

    /** A bumper hugging the shoulder; only its top shows above the body. [x] places it. */
    private fun bumper(x: (Float) -> Float) = Path().apply {
        moveTo(x(33f), 82f)
        cubicTo(x(40f), 48f, x(68f), 24f, x(118f), 24f)
        lineTo(x(148f), 24f)
        cubicTo(x(154f), 24f, x(157f), 28f, x(157f), 34f)
        lineTo(x(157f), 60f)
        lineTo(x(60f), 72f)
        close()
    }

    /** A trigger standing behind the bumper. */
    private fun trigger(x: (Float) -> Float) = Path().apply {
        moveTo(x(78f), 36f)
        cubicTo(x(80f), 14f, x(96f), 4f, x(116f), 4f)
        lineTo(x(136f), 4f)
        cubicTo(x(143f), 4f, x(146f), 8f, x(146f), 15f)
        lineTo(x(146f), 36f)
        close()
    }

    /** The D-pad: two rounded bars, joined. */
    val dpad = Path().apply {
        op(
            Path().apply { addRoundRect(RoundRect(Rect(DPad.x - DPAD_HALF, DPad.y - DPAD_ARM, DPad.x + DPAD_HALF, DPad.y + DPAD_ARM), CornerRadius(DPAD_ROUND))) },
            Path().apply { addRoundRect(RoundRect(Rect(DPad.x - DPAD_ARM, DPad.y - DPAD_HALF, DPad.x + DPAD_ARM, DPad.y + DPAD_HALF), CornerRadius(DPAD_ROUND))) },
            PathOperation.Union,
        )
    }
}

private val DPad = Offset(86f, 100f)
private const val DPAD_ARM = 30f
private const val DPAD_HALF = 10.5f
private const val DPAD_ROUND = 6f
private val Face = Offset(316f, 100f)
private const val FACE_SPREAD = 24f
private const val FACE_R = 12f
private val LeftStick = Offset(138f, 150f)
private val RightStick = Offset(262f, 150f)
private val Guide = Offset(200f, 150f)
private val View = Offset(158f, 76f)
private val Menu = Offset(242f, 76f)

/** The colours one drawing uses, worked out once per frame from the theme. */
private class PadInk(c: FuseColors) {
    val accent = c.accent
    val onAccent = c.onAccent
    /** The body: solid, never see-through, whatever the theme's surfaces are. */
    val shell = solid(mix(c.surfaceRaised, c.text, 0.05f), c.ink)
    val shellLow = solid(mix(c.surfaceRaised, c.ink, 0.35f), c.ink)
    /** Wells the controls sit in: a step darker than the body. */
    val well = solid(mix(c.surfaceRaised, c.ink, 0.55f), c.ink)
    val rim = solid(mix(c.surfaceRaised, c.text, 0.32f), c.ink)
    val line = c.text.copy(alpha = 0.55f)
    val label = c.text.copy(alpha = 0.85f)
    val faint = c.text.copy(alpha = 0.14f)
}

private fun DrawScope.drawPad(
    g: PadGeometry,
    c: FuseColors,
    lit: Map<PadButton, Float>,
    marked: Set<PadButton>,
    style: GlyphStyle,
    nintendoKeys: Boolean,
    measurer: TextMeasurer,
    scale: Float,
) {
    val k = PadInk(c)
    val labels = PadLabels.of(style)

    // A soft shadow on the floor under it.
    drawOval(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent), center = Offset(200f, 254f), radius = 190f),
        topLeft = Offset(10f, 240f), size = Size(380f, 22f),
    )

    // Triggers, then bumpers, behind the shoulders: solid, outlined like the body.
    for ((path, b, label, at) in listOf(
        Quad(g.leftTrigger, PadButton.L2, labels.leftTrigger, Offset(118f, 13.5f)),
        Quad(g.rightTrigger, PadButton.R2, labels.rightTrigger, Offset(PAD_W - 118f, 13.5f)),
        Quad(g.leftBumper, PadButton.L1, labels.leftBumper, Offset(114f, 31.5f)),
        Quad(g.rightBumper, PadButton.R1, labels.rightBumper, Offset(PAD_W - 114f, 31.5f)),
    )) {
        val l = lit[b] ?: 0f
        withTransform({ translate(0f, 2f * l) }) {
            if (l > 0f) drawPath(path, k.accent.copy(alpha = 0.3f * l), style = Stroke(7f, join = StrokeJoin.Round))
            drawPath(path, mix(k.shellLow, k.accent, l))
            drawPath(path, if (b in marked && l < 0.5f) k.accent else mix(k.rim, k.accent, l), style = Stroke(2f, join = StrokeJoin.Round))
            centredText(measurer, label, at, 8f, if (l > 0.5f) k.onAccent else k.label, scale, bold = true)
        }
    }

    // The body: solid, lit a little from above, with a clean rim and a light along its top.
    drawPath(g.body, Brush.verticalGradient(listOf(k.shell, k.shellLow), startY = 40f, endY = 258f))
    clipPath(g.body) {
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), center = Offset(200f, 40f), radius = 230f),
            radius = 230f, center = Offset(200f, 40f),
        )
    }
    drawPath(g.body, k.rim, style = Stroke(2.6f, join = StrokeJoin.Round))
    drawPath(g.ridge, Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.14f), Color.Transparent), startX = 36f, endX = 364f), style = Stroke(1.4f, cap = StrokeCap.Round))

    dpad(g, lit, marked, k)
    face(lit, marked, k, style, nintendoKeys, measurer, scale)
    pill(View, PadButton.SELECT, lit, marked, k) { at, ink ->
        drawRoundRect(ink, Offset(at.x - 4f, at.y - 2.6f), Size(5.4f, 4f), CornerRadius(1f), style = Stroke(1.1f))
        drawRoundRect(ink, Offset(at.x - 1.4f, at.y - 0.8f), Size(5.4f, 4f), CornerRadius(1f), style = Stroke(1.1f))
    }
    pill(Menu, PadButton.START, lit, marked, k) { at, ink ->
        for (i in -1..1) drawLine(ink, Offset(at.x - 3.6f, at.y + i * 2.1f), Offset(at.x + 3.6f, at.y + i * 2.1f), 1.1f, StrokeCap.Round)
    }
    stick(LeftStick, PadButton.L3, lit, marked, k)
    stick(RightStick, PadButton.R3, lit, marked, k)
    guide(Guide, lit[PadButton.MODE] ?: 0f, PadButton.MODE in marked, k)
}

private data class Quad(val path: Path, val button: PadButton, val label: String, val at: Offset)

/** A held part's glow, under it. */
private fun DrawScope.glow(at: Offset, radius: Float, l: Float, k: PadInk) {
    if (l <= 0f) return
    drawCircle(Brush.radialGradient(listOf(k.accent.copy(alpha = 0.45f * l), Color.Transparent), center = at, radius = radius), radius = radius, center = at)
}

/** The outline of a part: the accent while held or marked, the line colour otherwise. */
private fun outline(l: Float, marked: Boolean, k: PadInk): Color = if (marked && l < 0.5f) k.accent else mix(k.line, k.accent, l)

/** The D-pad: an outlined cross in its well; a held direction fills its arm with the accent. */
private fun DrawScope.dpad(g: PadGeometry, lit: Map<PadButton, Float>, marked: Set<PadButton>, k: PadInk) {
    val arms = listOf(PadButton.DPAD_UP to Offset(0f, -1f), PadButton.DPAD_DOWN to Offset(0f, 1f), PadButton.DPAD_LEFT to Offset(-1f, 0f), PadButton.DPAD_RIGHT to Offset(1f, 0f))
    for ((b, dir) in arms) glow(DPad + dir * (DPAD_ARM - 8f), 26f, lit[b] ?: 0f, k)
    drawPath(g.dpad, k.well)
    clipPath(g.dpad) {
        for ((b, dir) in arms) {
            val l = lit[b] ?: 0f
            if (l <= 0f) continue
            val from = DPad + dir * DPAD_HALF
            val tip = DPad + dir * DPAD_ARM
            drawRect(
                k.accent.copy(alpha = l),
                topLeft = Offset(minOf(from.x, tip.x) - if (dir.x == 0f) DPAD_HALF else 0f, minOf(from.y, tip.y) - if (dir.y == 0f) DPAD_HALF else 0f),
                size = Size(if (dir.x == 0f) DPAD_HALF * 2 else DPAD_ARM - DPAD_HALF, if (dir.y == 0f) DPAD_HALF * 2 else DPAD_ARM - DPAD_HALF),
            )
        }
    }
    val anyMarked = arms.any { it.first in marked }
    val anyLit = arms.maxOf { lit[it.first] ?: 0f }
    drawPath(g.dpad, outline(anyLit, anyMarked, k), style = Stroke(1.8f, join = StrokeJoin.Round))
    // An arrow in each arm.
    for ((b, dir) in arms) {
        val l = lit[b] ?: 0f
        val tip = DPad + dir * (DPAD_ARM - 6f)
        val side = Offset(dir.y, dir.x) * 3.4f
        val back = tip - dir * 4.4f
        val arrow = Path().apply { moveTo(tip.x, tip.y); lineTo(back.x + side.x, back.y + side.y); lineTo(back.x - side.x, back.y - side.y); close() }
        drawPath(arrow, if (l > 0.5f) k.onAccent else k.line)
    }
}

/** The face buttons in their diamond: outlined circles with their letter (or PlayStation shape). */
private fun DrawScope.face(lit: Map<PadButton, Float>, marked: Set<PadButton>, k: PadInk, style: GlyphStyle, nintendoKeys: Boolean, measurer: TextMeasurer, scale: Float) {
    val d = FACE_SPREAD
    val places = if (nintendoKeys) {
        listOf(PadButton.X to Offset(0f, -d), PadButton.Y to Offset(-d, 0f), PadButton.A to Offset(d, 0f), PadButton.B to Offset(0f, d))
    } else {
        listOf(PadButton.Y to Offset(0f, -d), PadButton.X to Offset(-d, 0f), PadButton.B to Offset(d, 0f), PadButton.A to Offset(0f, d))
    }
    for ((b, off) in places) {
        val l = lit[b] ?: 0f
        val p = Face + off
        glow(p, FACE_R * 2.3f, l, k)
        drawCircle(mix(k.well, k.accent, l), radius = FACE_R, center = p)
        drawCircle(outline(l, b in marked, k), radius = FACE_R, center = p, style = Stroke(1.8f))
        val ink = if (l > 0.5f) k.onAccent else k.label
        if (style == GlyphStyle.PLAYSTATION) shape(b, p, ink) else centredText(measurer, b.name, p, 10.5f, ink, scale, bold = true)
    }
}

/** A PlayStation face button's shape: triangle (Y), square (X), circle (B), cross (A). */
private fun DrawScope.shape(b: PadButton, at: Offset, ink: Color) {
    val stroke = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (b) {
        PadButton.Y -> drawPath(Path().apply { moveTo(at.x, at.y - 5.2f); lineTo(at.x + 5.2f, at.y + 3.6f); lineTo(at.x - 5.2f, at.y + 3.6f); close() }, ink, style = stroke)
        PadButton.X -> drawRect(ink, Offset(at.x - 4.4f, at.y - 4.4f), Size(8.8f, 8.8f), style = stroke)
        PadButton.B -> drawCircle(ink, radius = 5f, center = at, style = stroke)
        else -> {
            drawLine(ink, Offset(at.x - 4.4f, at.y - 4.4f), Offset(at.x + 4.4f, at.y + 4.4f), 1.6f, StrokeCap.Round)
            drawLine(ink, Offset(at.x + 4.4f, at.y - 4.4f), Offset(at.x - 4.4f, at.y + 4.4f), 1.6f, StrokeCap.Round)
        }
    }
}

/** View or Menu: a small outlined pill with its symbol. */
private fun DrawScope.pill(at: Offset, b: PadButton, lit: Map<PadButton, Float>, marked: Set<PadButton>, k: PadInk, symbol: DrawScope.(Offset, Color) -> Unit) {
    val l = lit[b] ?: 0f
    glow(at, 22f, l, k)
    val topLeft = Offset(at.x - 13f, at.y - 6.5f)
    val size = Size(26f, 13f)
    drawRoundRect(mix(k.well, k.accent, l), topLeft, size, CornerRadius(6.5f))
    drawRoundRect(outline(l, b in marked, k), topLeft, size, CornerRadius(6.5f), style = Stroke(1.6f))
    symbol(at, if (l > 0.5f) k.onAccent else k.label)
}

/** A stick: its well, then the cap's ring; pressed in (L3, R3), the cap fills with the accent. */
private fun DrawScope.stick(at: Offset, b: PadButton, lit: Map<PadButton, Float>, marked: Set<PadButton>, k: PadInk) {
    val l = lit[b] ?: 0f
    drawCircle(k.well, radius = 25f, center = at)
    drawCircle(k.faint, radius = 25f, center = at, style = Stroke(1.2f))
    glow(at, 34f, l, k)
    drawCircle(mix(k.shellLow, k.accent, l), radius = 17.5f, center = at)
    drawCircle(outline(l, b in marked, k), radius = 17.5f, center = at, style = Stroke(1.8f))
}

/** The guide button: Fuse's own mark in an outlined circle, its spark lit in the accent. */
private fun DrawScope.guide(at: Offset, l: Float, marked: Boolean, k: PadInk) {
    val r = 14f
    glow(at, 32f, 0.35f + 0.65f * l, k)
    drawCircle(mix(k.well, k.accent, l * 0.85f), radius = r, center = at)
    drawCircle(outline(l, marked, k), radius = r, center = at, style = Stroke(1.8f))
    val mark = 16f
    val u = mark / BrandArt.MARK
    withTransform({
        translate(at.x - mark / 2, at.y - mark / 2)
        scale(u, u, pivot = Offset.Zero)
    }) {
        val ink = if (l > 0.5f) k.onAccent else k.label
        drawPath(BrandArt.frame, ink, style = Stroke(BrandArt.MARK_STROKE))
        drawPath(BrandArt.fuse, ink, style = Stroke(BrandArt.MARK_STROKE, cap = StrokeCap.Round))
        drawCircle(BrandArt.glow(k.accent), radius = BrandArt.GLOW_R * (0.8f + 0.5f * l), center = BrandArt.SPARK)
        drawCircle(BrandArt.core(k.accent), radius = BrandArt.CORE_R, center = BrandArt.SPARK)
    }
}

/** [text] centred on [at], in pad units ([size]), drawn crisp at the canvas's real scale. */
private fun DrawScope.centredText(measurer: TextMeasurer, text: String, at: Offset, size: Float, color: Color, scale: Float, bold: Boolean = false) {
    // Laid out at the real pixel size and drawn without the pad's scale, so letters stay sharp.
    withTransform({ scale(1f / scale, 1f / scale, pivot = Offset.Zero) }) {
        val layout = measurer.measure(
            text,
            TextStyle(color = color, fontSize = (size * scale).toSp(), fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold),
        )
        drawText(layout, topLeft = Offset(at.x * scale - layout.size.width / 2f, at.y * scale - layout.size.height / 2f))
    }
}

/** What the shoulders and triggers are called on this pad. */
private class PadLabels(val leftBumper: String, val rightBumper: String, val leftTrigger: String, val rightTrigger: String) {
    companion object {
        fun of(style: GlyphStyle) = when (style) {
            GlyphStyle.PLAYSTATION -> PadLabels("L1", "R1", "L2", "R2")
            GlyphStyle.NINTENDO -> PadLabels("L", "R", "ZL", "ZR")
            else -> PadLabels("LB", "RB", "LT", "RT")
        }
    }
}

private fun mix(a: Color, b: Color, f: Float): Color {
    val t = f.coerceIn(0f, 1f)
    return Color(
        a.red + (b.red - a.red) * t,
        a.green + (b.green - a.green) * t,
        a.blue + (b.blue - a.blue) * t,
        a.alpha + (b.alpha - a.alpha) * t,
    )
}

/** [color] laid over [under] and made fully opaque, so the drawing never shows what is behind it. */
private fun solid(color: Color, under: Color): Color {
    val base = under.copy(alpha = 1f)
    val a = color.alpha
    return Color(
        color.red * a + base.red * (1f - a),
        color.green * a + base.green * (1f - a),
        color.blue * a + base.blue * (1f - a),
        1f,
    )
}
