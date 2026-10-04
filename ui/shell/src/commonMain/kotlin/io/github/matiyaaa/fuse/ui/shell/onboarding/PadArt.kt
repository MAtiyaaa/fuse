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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
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
 * A controller, drawn whole in the theme's own colours: a sculpted body with its grips, the
 * bumpers and triggers tucked behind its shoulders, two sticks in their wells, a D-pad, the face
 * buttons in their diamond, View and Menu, and Fuse's own mark as the guide button, with a thin
 * light between them. Every part that is held lights in the accent, sinks a little and glows, and
 * the light above the guide button wakes while anything is held.
 *
 * Shoulder, trigger and face labels follow the pad's [style] (LB and LT, L1 and L2, L and ZL; the
 * PlayStation shapes); [nintendoKeys] puts the face buttons where a pad sending Nintendo keycodes
 * has them (A on the right).
 */
@Composable
internal fun PadArt(pressed: Set<PadButton>, style: GlyphStyle, nintendoKeys: Boolean, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val measurer = rememberTextMeasurer()
    // Each part's light, eased in and out so a quick tap still shows.
    val lit = PadParts.associateWith { b -> fuselineFloat(if (b in pressed) 1f else 0f, motion.tween(Durations.FAST), label = "pad").value }
    val any = fuselineFloat(if (pressed.any { it in PadParts }) 1f else 0f, motion.tween(Durations.BASE), label = "padAny").value
    val geometry = remember { PadGeometry() }
    Canvas(modifier.aspectRatio(PAD_W / PAD_H)) {
        val s = size.width / PAD_W
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawPad(geometry, c, lit, any, style, nintendoKeys, measurer, s)
        }
    }
}

private const val PAD_W = 400f
private const val PAD_H = 276f

/** The buttons the drawing shows. */
private val PadParts = listOf(
    PadButton.A, PadButton.B, PadButton.X, PadButton.Y,
    PadButton.L1, PadButton.R1, PadButton.L2, PadButton.R2, PadButton.L3, PadButton.R3,
    PadButton.START, PadButton.SELECT, PadButton.MODE,
    PadButton.DPAD_UP, PadButton.DPAD_DOWN, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT,
)

/** The pad's shapes, in a 400 by 276 box, built once. */
private class PadGeometry {
    val body = Path().apply {
        moveTo(95f, 42f)
        cubicTo(140f, 38f, 168f, 52f, 200f, 52f)
        cubicTo(232f, 52f, 260f, 38f, 305f, 42f)
        cubicTo(350f, 38f, 372f, 62f, 380f, 97f)
        cubicTo(392f, 142f, 400f, 192f, 398f, 227f)
        cubicTo(396f, 262f, 364f, 272f, 344f, 252f)
        cubicTo(324f, 234f, 300f, 198f, 268f, 192f)
        cubicTo(240f, 188f, 160f, 188f, 132f, 192f)
        cubicTo(100f, 198f, 76f, 234f, 56f, 252f)
        cubicTo(36f, 272f, 4f, 262f, 2f, 227f)
        cubicTo(0f, 192f, 8f, 142f, 20f, 97f)
        cubicTo(28f, 62f, 50f, 38f, 95f, 42f)
        close()
    }

    /** The top edge alone, for the light along it. */
    val ridge = Path().apply {
        moveTo(24f, 90f)
        cubicTo(32f, 60f, 54f, 40f, 95f, 42f)
        cubicTo(140f, 38f, 168f, 52f, 200f, 52f)
        cubicTo(232f, 52f, 260f, 38f, 305f, 42f)
        cubicTo(346f, 40f, 368f, 60f, 376f, 90f)
    }
    val leftBumper = bumper { it }
    val leftTrigger = trigger { it }
    val rightBumper = bumper { PAD_W - it }
    val rightTrigger = trigger { PAD_W - it }

    /** A bumper's shape; [x] places it (as it is for the left, mirrored for the right). */
    private fun bumper(x: (Float) -> Float) = Path().apply {
        moveTo(x(44f), 86f)
        cubicTo(x(46f), 48f, x(78f), 24f, x(124f), 22f)
        lineTo(x(146f), 22f)
        cubicTo(x(157f), 22f, x(161f), 30f, x(159f), 41f)
        lineTo(x(156f), 76f)
        close()
    }

    private fun trigger(x: (Float) -> Float) = Path().apply {
        moveTo(x(82f), 48f)
        cubicTo(x(82f), 22f, x(98f), 6f, x(120f), 6f)
        cubicTo(x(138f), 6f, x(146f), 16f, x(146f), 32f)
        lineTo(x(146f), 52f)
        close()
    }
}

private val LeftStick = Offset(110f, 102f)
private val RightStick = Offset(254f, 158f)
private val DPad = Offset(146f, 158f)
private val Face = Offset(290f, 102f)
private val Guide = Offset(200f, 132f)

private fun DrawScope.drawPad(
    g: PadGeometry,
    c: FuseColors,
    lit: Map<PadButton, Float>,
    any: Float,
    style: GlyphStyle,
    nintendoKeys: Boolean,
    measurer: TextMeasurer,
    scale: Float,
) {
    val accent = c.accent
    val shell = c.surfaceRaised
    val deep = c.surface
    val rim = c.text.copy(alpha = 0.16f)
    val labels = PadLabels.of(style)

    // A soft shadow on the floor under it.
    drawOval(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), center = Offset(200f, 262f), radius = 180f),
        topLeft = Offset(20f, 248f), size = Size(360f, 28f),
    )

    // Triggers, then bumpers, behind the shoulders.
    for ((path, b, label, at) in listOf(
        Quad(g.leftTrigger, PadButton.L2, labels.leftTrigger, Offset(116f, 16f)),
        Quad(g.rightTrigger, PadButton.R2, labels.rightTrigger, Offset(284f, 16f)),
    )) {
        val l = lit[b] ?: 0f
        withTransform({ translate(0f, 2.5f * l) }) {
            drawPath(path, Brush.verticalGradient(listOf(mix(deep, accent, l), mix(Color.Black.copy(alpha = 0.6f).compositeOver(deep), accent, l * 0.8f)), startY = 6f, endY = 52f))
            drawPath(path, mix(rim, accent, l), style = Stroke(1.2f))
            centredText(measurer, label, at, 7.5f, if (l > 0.5f) c.onAccent else c.textMuted, scale)
        }
    }
    for ((path, b, label, at) in listOf(
        Quad(g.leftBumper, PadButton.L1, labels.leftBumper, Offset(110f, 31f)),
        Quad(g.rightBumper, PadButton.R1, labels.rightBumper, Offset(290f, 31f)),
    )) {
        val l = lit[b] ?: 0f
        withTransform({ translate(0f, 2.5f * l) }) {
            drawPath(path, Brush.verticalGradient(listOf(mix(shell, accent, l), mix(deep, accent, l * 0.85f)), startY = 22f, endY = 80f))
            drawPath(path, mix(rim, accent, l), style = Stroke(1.2f))
            centredText(measurer, label, at, 8.5f, if (l > 0.5f) c.onAccent else c.text.copy(alpha = 0.75f), scale)
        }
    }

    // The body: lit from above, darker towards the grips, with a rim and a light along its top.
    drawPath(g.body, Brush.verticalGradient(listOf(mix(shell, c.text, 0.08f), mix(shell, deep, 0.5f), mix(deep, Color.Black, 0.35f)), startY = 40f, endY = 270f))
    clipPath(g.body) {
        // A broad sheen across the top, as light falls on a curved shell.
        withTransform({ scale(1f, 0.42f, pivot = Offset(200f, 60f)) }) {
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.09f), Color.Transparent), center = Offset(200f, 60f), radius = 210f), radius = 210f, center = Offset(200f, 60f))
        }
        // An inner bevel: darker just inside the edge, so the shell reads rounded.
        drawPath(g.body, Color.Black.copy(alpha = 0.28f), style = Stroke(9f, join = StrokeJoin.Round))
        drawPath(g.body, mix(shell, c.text, 0.06f).copy(alpha = 0.5f), style = Stroke(3f, join = StrokeJoin.Round))
        // The faintest wash of the accent from inside, stronger while something is held.
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.06f + 0.08f * any), Color.Transparent), center = Offset(200f, 120f), radius = 190f), radius = 190f, center = Offset(200f, 120f))
        // The grips' inner shadow.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)), startY = 170f, endY = 270f), topLeft = Offset(0f, 170f), size = Size(PAD_W, 106f))
    }
    drawPath(g.body, rim, style = Stroke(1.4f))
    drawPath(g.ridge, Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.16f), Color.Transparent), startX = 20f, endX = 380f), style = Stroke(1.6f, cap = StrokeCap.Round))

    // The light between the sticks: a thin line that wakes while anything is held.
    val led = Offset(200f, 72f)
    drawRoundRect(c.ink.copy(alpha = 0.6f), Offset(led.x - 20f, led.y - 2f), Size(40f, 4f), CornerRadius(2f))
    drawRoundRect(accent.copy(alpha = 0.35f + 0.65f * any), Offset(led.x - 20f * (0.4f + 0.6f * any), led.y - 1.5f), Size(40f * (0.4f + 0.6f * any), 3f), CornerRadius(1.5f))
    if (any > 0f) drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f * any), Color.Transparent), center = led, radius = 30f), radius = 30f, center = led)

    stick(LeftStick, lit[PadButton.L3] ?: 0f, c)
    stick(RightStick, lit[PadButton.R3] ?: 0f, c)
    dpad(DPad, lit, c)
    face(Face, lit, c, style, nintendoKeys, measurer, scale)

    // View and Menu.
    small(Offset(172f, 100f), lit[PadButton.SELECT] ?: 0f, c) { at, ink ->
        drawRoundRect(ink, Offset(at.x - 3.6f, at.y - 2.2f), Size(5f, 3.6f), CornerRadius(0.8f), style = Stroke(0.9f))
        drawRoundRect(ink, Offset(at.x - 1.4f, at.y - 0.6f), Size(5f, 3.6f), CornerRadius(0.8f), style = Stroke(0.9f))
    }
    small(Offset(228f, 100f), lit[PadButton.START] ?: 0f, c) { at, ink ->
        for (i in -1..1) drawLine(ink, Offset(at.x - 3.2f, at.y + i * 1.9f), Offset(at.x + 3.2f, at.y + i * 1.9f), 0.9f, StrokeCap.Round)
    }
    guide(Guide, lit[PadButton.MODE] ?: 0f, c)
}

private data class Quad(val path: Path, val button: PadButton, val label: String, val at: Offset)

/** A stick: its well, then the cap with its grip ring; held in (L3, R3), it sinks and lights. */
private fun DrawScope.stick(at: Offset, l: Float, c: FuseColors) {
    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), c.ink.copy(alpha = 0.35f)), center = at + Offset(0f, -3f), radius = 30f), radius = 28f, center = at)
    drawCircle(c.text.copy(alpha = 0.10f), radius = 28f, center = at, style = Stroke(1f))
    val cap = at + Offset(0f, 1.5f * l)
    val r = 20f - 1.2f * l
    if (l > 0f) drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.45f * l), Color.Transparent), center = cap, radius = 36f), radius = 36f, center = cap)
    drawCircle(Color.Black.copy(alpha = 0.35f), radius = r + 1.5f, center = cap + Offset(0f, 2.5f))
    drawCircle(Brush.verticalGradient(listOf(mix(c.surfaceRaised, c.text, 0.10f), c.surface), startY = cap.y - r, endY = cap.y + r), radius = r, center = cap)
    // The dished top and its textured ring.
    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent), center = cap + Offset(0f, 2f), radius = r * 0.72f), radius = r * 0.72f, center = cap)
    drawCircle(mix(c.text.copy(alpha = 0.14f), c.accent, l), radius = r - 2.5f, center = cap, style = Stroke(1.6f + 0.6f * l))
    drawCircle(c.text.copy(alpha = 0.10f + 0.1f * (1f - l)), radius = r, center = cap, style = Stroke(1f))
}

/** The D-pad: one raised cross in its well; a held direction lights its arm and tips it down. */
private fun DrawScope.dpad(at: Offset, lit: Map<PadButton, Float>, c: FuseColors) {
    val arm = 23f
    val half = 8.5f
    val cross = Path().apply {
        addRoundRect(RoundRect(Rect(at.x - half, at.y - arm, at.x + half, at.y + arm), CornerRadius(3.5f)))
        addRoundRect(RoundRect(Rect(at.x - arm, at.y - half, at.x + arm, at.y + half), CornerRadius(3.5f)))
    }
    val outline = Path().apply { op(
        Path().apply { addRoundRect(RoundRect(Rect(at.x - half, at.y - arm, at.x + half, at.y + arm), CornerRadius(3.5f))) },
        Path().apply { addRoundRect(RoundRect(Rect(at.x - arm, at.y - half, at.x + arm, at.y + half), CornerRadius(3.5f))) },
        androidx.compose.ui.graphics.PathOperation.Union,
    ) }
    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.45f), c.ink.copy(alpha = 0.25f)), center = at, radius = 32f), radius = 30f, center = at)
    translate(0f, 2.5f) { drawPath(outline, Color.Black.copy(alpha = 0.4f)) }
    drawPath(outline, Brush.verticalGradient(listOf(mix(c.surfaceRaised, c.text, 0.08f), c.surface), startY = at.y - arm, endY = at.y + arm))
    clipPath(cross) {
        for ((b, dir) in listOf(PadButton.DPAD_UP to Offset(0f, -1f), PadButton.DPAD_DOWN to Offset(0f, 1f), PadButton.DPAD_LEFT to Offset(-1f, 0f), PadButton.DPAD_RIGHT to Offset(1f, 0f))) {
            val l = lit[b] ?: 0f
            if (l <= 0f) continue
            val tip = at + dir * arm
            val from = at + dir * half
            drawRect(
                c.accent.copy(alpha = l),
                topLeft = Offset(minOf(from.x, tip.x) - if (dir.x == 0f) half else 0f, minOf(from.y, tip.y) - if (dir.y == 0f) half else 0f),
                size = Size(if (dir.x == 0f) half * 2 else arm - half, if (dir.y == 0f) half * 2 else arm - half),
            )
        }
    }
    drawPath(outline, c.text.copy(alpha = 0.14f), style = Stroke(1f, join = StrokeJoin.Round))
    // An arrow pressed into each arm, and a dimple in the middle.
    for ((b, dir) in listOf(PadButton.DPAD_UP to Offset(0f, -1f), PadButton.DPAD_DOWN to Offset(0f, 1f), PadButton.DPAD_LEFT to Offset(-1f, 0f), PadButton.DPAD_RIGHT to Offset(1f, 0f))) {
        val l = lit[b] ?: 0f
        val tip = at + dir * (arm - 5f)
        val side = Offset(dir.y, dir.x) * 3.2f
        val back = tip - dir * 4f
        val arrow = Path().apply { moveTo(tip.x, tip.y); lineTo(back.x + side.x, back.y + side.y); lineTo(back.x - side.x, back.y - side.y); close() }
        drawPath(arrow, if (l > 0.5f) c.onAccent.copy(alpha = 0.9f) else c.text.copy(alpha = 0.32f))
        if (l > 0f) drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.4f * l), Color.Transparent), center = tip, radius = 22f), radius = 22f, center = tip)
    }
    drawCircle(Color.Black.copy(alpha = 0.22f), radius = 4.5f, center = at)
}

/** The face buttons in their diamond, each with its letter (or its PlayStation shape). */
private fun DrawScope.face(at: Offset, lit: Map<PadButton, Float>, c: FuseColors, style: GlyphStyle, nintendoKeys: Boolean, measurer: TextMeasurer, scale: Float) {
    val d = 23f
    val places = if (nintendoKeys) {
        listOf(PadButton.X to Offset(0f, -d), PadButton.Y to Offset(-d, 0f), PadButton.A to Offset(d, 0f), PadButton.B to Offset(0f, d))
    } else {
        listOf(PadButton.Y to Offset(0f, -d), PadButton.X to Offset(-d, 0f), PadButton.B to Offset(d, 0f), PadButton.A to Offset(0f, d))
    }
    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent), center = at, radius = 46f), radius = 46f, center = at)
    for ((b, off) in places) {
        val l = lit[b] ?: 0f
        val p = at + off + Offset(0f, 1.5f * l)
        val r = 12.5f
        if (l > 0f) drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.55f * l), Color.Transparent), center = p, radius = 30f), radius = 30f, center = p)
        drawCircle(Color.Black.copy(alpha = 0.4f * (1f - l * 0.6f)), radius = r + 0.5f, center = p + Offset(0f, 2.5f - 1.5f * l))
        drawCircle(Brush.verticalGradient(listOf(mix(mix(c.surfaceRaised, c.text, 0.1f), c.accent, l), mix(c.surface, c.accent, l * 0.85f)), startY = p.y - r, endY = p.y + r), radius = r, center = p)
        drawCircle(mix(c.text.copy(alpha = 0.16f), Color.White.copy(alpha = 0.5f), l), radius = r, center = p, style = Stroke(1f))
        val ink = if (l > 0.5f) c.onAccent else c.text.copy(alpha = 0.85f)
        if (style == GlyphStyle.PLAYSTATION) {
            shape(b, p, ink)
        } else {
            centredText(measurer, b.name, p, 10f, ink, scale, bold = true)
        }
    }
}

/** A PlayStation face button's shape: triangle (Y), square (X), circle (B), cross (A). */
private fun DrawScope.shape(b: PadButton, at: Offset, ink: Color) {
    val stroke = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (b) {
        PadButton.Y -> drawPath(Path().apply { moveTo(at.x, at.y - 5f); lineTo(at.x + 5f, at.y + 3.5f); lineTo(at.x - 5f, at.y + 3.5f); close() }, ink, style = stroke)
        PadButton.X -> drawRect(ink, Offset(at.x - 4.2f, at.y - 4.2f), Size(8.4f, 8.4f), style = stroke)
        PadButton.B -> drawCircle(ink, radius = 4.8f, center = at, style = stroke)
        else -> {
            drawLine(ink, Offset(at.x - 4.2f, at.y - 4.2f), Offset(at.x + 4.2f, at.y + 4.2f), 1.5f, StrokeCap.Round)
            drawLine(ink, Offset(at.x + 4.2f, at.y - 4.2f), Offset(at.x - 4.2f, at.y + 4.2f), 1.5f, StrokeCap.Round)
        }
    }
}

/** View or Menu: a small pill with its symbol. */
private fun DrawScope.small(at: Offset, l: Float, c: FuseColors, symbol: DrawScope.(Offset, Color) -> Unit) {
    val p = at + Offset(0f, 1f * l)
    if (l > 0f) drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.45f * l), Color.Transparent), center = p, radius = 20f), radius = 20f, center = p)
    drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(p.x - 9f, p.y - 4.5f + 1.8f), Size(18f, 9f), CornerRadius(4.5f))
    drawRoundRect(mix(mix(c.surfaceRaised, c.text, 0.08f), c.accent, l), Offset(p.x - 9f, p.y - 4.5f), Size(18f, 9f), CornerRadius(4.5f))
    drawRoundRect(c.text.copy(alpha = 0.14f), Offset(p.x - 9f, p.y - 4.5f), Size(18f, 9f), CornerRadius(4.5f), style = Stroke(0.8f))
    symbol(p, if (l > 0.5f) c.onAccent else c.text.copy(alpha = 0.6f))
}

/** The guide button: Fuse's own mark, its spark lit in the accent, glowing while held. */
private fun DrawScope.guide(at: Offset, l: Float, c: FuseColors) {
    val r = 13.5f
    drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.18f + 0.4f * l), Color.Transparent), center = at, radius = 30f), radius = 30f, center = at)
    drawCircle(Color.Black.copy(alpha = 0.4f), radius = r + 0.5f, center = at + Offset(0f, 2.2f))
    drawCircle(Brush.verticalGradient(listOf(mix(c.surfaceRaised, c.text, 0.1f), c.surface), startY = at.y - r, endY = at.y + r), radius = r, center = at)
    drawCircle(mix(c.text.copy(alpha = 0.18f), c.accent, l), radius = r, center = at, style = Stroke(1f + 0.6f * l))
    val mark = 15f
    val u = mark / BrandArt.MARK
    withTransform({
        translate(at.x - mark / 2, at.y - mark / 2)
        scale(u, u, pivot = Offset.Zero)
    }) {
        val ink = mix(c.text.copy(alpha = 0.85f), Color.White, l)
        drawPath(BrandArt.frame, ink, style = Stroke(BrandArt.MARK_STROKE))
        drawPath(BrandArt.fuse, ink, style = Stroke(BrandArt.MARK_STROKE, cap = StrokeCap.Round))
        drawCircle(BrandArt.glow(c.accent), radius = BrandArt.GLOW_R * (0.8f + 0.5f * l), center = BrandArt.SPARK)
        drawCircle(BrandArt.core(c.accent), radius = BrandArt.CORE_R, center = BrandArt.SPARK)
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

private fun Color.compositeOver(under: Color): Color {
    val a = alpha + under.alpha * (1f - alpha)
    if (a == 0f) return Color.Transparent
    return Color(
        (red * alpha + under.red * under.alpha * (1f - alpha)) / a,
        (green * alpha + under.green * under.alpha * (1f - alpha)) / a,
        (blue * alpha + under.blue * under.alpha * (1f - alpha)) / a,
        a,
    )
}
