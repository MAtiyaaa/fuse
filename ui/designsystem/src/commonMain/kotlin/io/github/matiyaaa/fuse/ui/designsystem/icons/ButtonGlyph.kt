package io.github.matiyaaa.fuse.ui.designsystem.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.GlyphConfig

/** A controller function shown in hint lines. The glyph drawn depends on the pad style and layout. */
enum class HintButton { CONFIRM, BACK, OPTIONS, SEARCH, MENU, VIEW, PREV, NEXT, PAGE_PREV, PAGE_NEXT, DPAD, HOLD_CONFIRM }

/** Physical face-button positions. */
private enum class Face { SOUTH, EAST, WEST, NORTH }

private fun faceFor(button: HintButton, nintendoLayout: Boolean): Face? = when (button) {
    HintButton.CONFIRM, HintButton.HOLD_CONFIRM -> if (nintendoLayout) Face.EAST else Face.SOUTH
    HintButton.BACK -> if (nintendoLayout) Face.SOUTH else Face.EAST
    HintButton.OPTIONS -> if (nintendoLayout) Face.NORTH else Face.WEST
    HintButton.SEARCH -> if (nintendoLayout) Face.WEST else Face.NORTH
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
    HintButton.OPTIONS -> "Tab"
    HintButton.SEARCH -> "F"
    HintButton.MENU -> "M"
    HintButton.VIEW -> "Tab"
    HintButton.PREV -> "Q"
    HintButton.NEXT -> "E"
    HintButton.PAGE_PREV -> "PgUp"
    HintButton.PAGE_NEXT -> "PgDn"
    HintButton.DPAD -> "Arrows"
}

private fun shoulderLabel(button: HintButton, style: GlyphStyle): String = when (style) {
    GlyphStyle.NINTENDO -> when (button) { HintButton.PREV -> "L"; HintButton.NEXT -> "R"; HintButton.PAGE_PREV -> "ZL"; else -> "ZR" }
    GlyphStyle.PLAYSTATION -> when (button) { HintButton.PREV -> "L1"; HintButton.NEXT -> "R1"; HintButton.PAGE_PREV -> "L2"; else -> "R2" }
    else -> when (button) { HintButton.PREV -> "LB"; HintButton.NEXT -> "RB"; HintButton.PAGE_PREV -> "LT"; else -> "RT" }
}

/**
 * Original controller glyphs: a small disc for face buttons, a keycap for shoulders and keyboard
 * keys. No console maker artwork is used; PlayStation-style shapes are drawn as plain geometry.
 */
@Composable
fun ButtonGlyph(
    button: HintButton,
    glyphs: GlyphConfig = Fuse.glyphs,
    size: Dp = 22.dp,
    color: Color = Fuse.colors.text,
    emphasized: Boolean = false,
) {
    val style = glyphs.style
    val labelStyle = TextStyle(color = if (emphasized) Fuse.colors.ink else color, fontSize = (size.value * 0.5f).sp)
        .merge(Fuse.type.label.copy(fontSize = (size.value * 0.5f).sp, lineHeight = (size.value * 0.5f).sp))
    val fill = if (emphasized) color else color.copy(alpha = 0.14f)
    if (style == GlyphStyle.KEYBOARD) {
        Keycap(keyboardLabel(button), size, color)
        return
    }
    when (button) {
        HintButton.PREV, HintButton.NEXT, HintButton.PAGE_PREV, HintButton.PAGE_NEXT ->
            Keycap(shoulderLabel(button, style), size, color)
        HintButton.MENU, HintButton.VIEW -> Box(
            Modifier.size(size).background(color.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(size * 0.46f)) {
                val w = this.size.width
                val stroke = w * 0.14f
                if (style == GlyphStyle.NINTENDO) {
                    drawLine(color, Offset(0f, w / 2), Offset(w, w / 2), stroke, StrokeCap.Round)
                    if (button == HintButton.MENU) drawLine(color, Offset(w / 2, 0f), Offset(w / 2, w), stroke, StrokeCap.Round)
                } else if (button == HintButton.MENU) {
                    for (i in 0..2) {
                        val y = w * (0.2f + 0.3f * i)
                        drawLine(color, Offset(0f, y), Offset(w, y), stroke, StrokeCap.Round)
                    }
                } else {
                    drawRect(color, Offset(0f, w * 0.1f), androidx.compose.ui.geometry.Size(w * 0.62f, w * 0.62f), style = Stroke(stroke))
                    drawRect(color, Offset(w * 0.38f, w * 0.38f), androidx.compose.ui.geometry.Size(w * 0.62f, w * 0.62f), style = Stroke(stroke))
                }
            }
        }
        HintButton.DPAD -> Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size * 0.82f)) {
                val w = this.size.width
                val t = w * 0.34f
                val o = (w - t) / 2
                val path = Path().apply {
                    moveTo(o, 0f); lineTo(o + t, 0f); lineTo(o + t, o); lineTo(w, o); lineTo(w, o + t)
                    lineTo(o + t, o + t); lineTo(o + t, w); lineTo(o, w); lineTo(o, o + t); lineTo(0f, o + t)
                    lineTo(0f, o); lineTo(o, o); close()
                }
                drawPath(path, color.copy(alpha = 0.85f), style = Stroke(w * 0.07f))
            }
        }
        else -> {
            val face = faceFor(button, glyphs.nintendoLayout) ?: Face.SOUTH
            Box(
                Modifier.size(size).background(fill, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (style == GlyphStyle.PLAYSTATION) {
                    val shapeColor = if (emphasized) Fuse.colors.ink else color
                    Canvas(Modifier.size(size * 0.44f)) {
                        val w = this.size.width
                        val stroke = Stroke(w * 0.14f, cap = StrokeCap.Round)
                        when (face) {
                            Face.SOUTH -> {
                                drawLine(shapeColor, Offset(0f, 0f), Offset(w, w), w * 0.14f, StrokeCap.Round)
                                drawLine(shapeColor, Offset(w, 0f), Offset(0f, w), w * 0.14f, StrokeCap.Round)
                            }
                            Face.EAST -> drawCircle(shapeColor, w / 2, style = stroke)
                            Face.WEST -> drawRect(shapeColor, style = stroke)
                            Face.NORTH -> drawPath(
                                Path().apply { moveTo(w / 2, 0f); lineTo(w, w * 0.9f); lineTo(0f, w * 0.9f); close() },
                                shapeColor, style = stroke,
                            )
                        }
                    }
                } else {
                    BasicText(faceLabel(face, style), style = labelStyle)
                }
            }
        }
    }
}

@Composable
private fun Keycap(label: String, size: Dp, color: Color) {
    Box(
        Modifier
            .height(size)
            .widthIn(min = size * 1.3f)
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(size * 0.28f))
            .padding(horizontal = size * 0.22f),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = Fuse.type.label.copy(color = color, fontSize = (size.value * 0.42f).sp, lineHeight = (size.value * 0.42f).sp),
        )
    }
}
