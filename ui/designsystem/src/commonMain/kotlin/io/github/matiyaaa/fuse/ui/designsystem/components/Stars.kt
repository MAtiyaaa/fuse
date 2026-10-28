package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A rating out of ten as five stars in halves: solid stars, the rated ones in [tint], a half star
 * filled to its middle, the rest a quiet shape of the same star. Not rated (0) is five quiet stars,
 * so a row of stars always says what it is for. [size] is one star's.
 */
@Composable
fun Stars(
    rating: Int,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = Fuse.colors.accent,
    empty: Color = Fuse.colors.text.copy(alpha = if (Fuse.colors.isDark) 0.16f else 0.18f),
) {
    Canvas(modifier.size(size * STARS + size * STAR_GAP * (STARS - 1), size)) {
        drawStars(rating, size.toPx(), tint, empty)
    }
}

/**
 * Five stars to set a rating by, as on Letterboxd: each half of a star is a step. A touch, a click
 * or a drag across them sets it ([onRate] as the finger or button lets go), a mouse over them shows
 * what a click would give, and touching the rating already given takes it away (0). A controller or
 * the keyboard steps it a half at a time where it is shown ([RatingWords] says it in words).
 */
@Composable
fun StarRating(
    rating: Int,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = Fuse.colors.accent,
    empty: Color = Fuse.colors.text.copy(alpha = if (Fuse.colors.isDark) 0.16f else 0.18f),
    /** What a pointer over the stars would set, as it moves (null when it leaves): for words beside them. */
    onPreview: (Int?) -> Unit = {},
) {
    val current by rememberUpdatedState(rating)
    val rate by rememberUpdatedState(onRate)
    val preview by rememberUpdatedState(onPreview)
    var hover by remember { mutableStateOf<Int?>(null) }
    val shown = hover ?: rating
    Canvas(
        modifier
            .size(size * STARS + size * STAR_GAP * (STARS - 1), size)
            .semantics {
                contentDescription = "Rating"
                stateDescription = RatingWords.stars(shown) ?: "Not rated"
            }
            .pointerInput(Unit) {
                val star = size.toPx()
                val step = star * (1 + STAR_GAP)
                // The half of the star under x: its left half is a half star, its right half a whole one.
                fun valueAt(x: Float): Int {
                    val i = (x / step).toInt().coerceIn(0, STARS - 1)
                    val within = (x - i * step) / star
                    return (i * 2 + if (within < 0.5f) 1 else 2).coerceIn(1, STARS * 2)
                }
                fun show(v: Int?) {
                    if (hover != v) {
                        hover = v
                        preview(v)
                    }
                }
                awaitPointerEventScope {
                    var pressed = false
                    var moved = false
                    var start = 0
                    while (true) {
                        val e = awaitPointerEvent()
                        val p = e.changes.firstOrNull() ?: continue
                        val v = valueAt(p.position.x)
                        when (e.type) {
                            PointerEventType.Press -> {
                                pressed = true
                                moved = false
                                start = v
                                show(v)
                                p.consume()
                            }
                            PointerEventType.Move -> when {
                                pressed -> {
                                    if (v != start) moved = true
                                    show(v)
                                    p.consume()
                                }
                                // A mouse over the stars shows what a click would set.
                                p.type == PointerType.Mouse -> show(v)
                            }
                            PointerEventType.Release -> if (pressed) {
                                pressed = false
                                // A tap on the rating already given takes it away; a drag never does.
                                rate(if (!moved && v == current) 0 else v)
                                p.consume()
                                show(if (p.type == PointerType.Mouse) v else null)
                            }
                            PointerEventType.Exit -> if (!pressed) show(null)
                        }
                    }
                }
            },
    ) {
        drawStars(shown, size.toPx(), if (hover != null && hover != rating) tint.copy(alpha = 0.78f) else tint, empty)
    }
}

/** A rating in words: "3½ stars", "1 star"; null when not rated. */
object RatingWords {
    fun stars(rating: Int): String? {
        if (rating <= 0) return null
        val whole = rating / 2
        val half = rating % 2 == 1
        return when {
            whole == 0 -> "Half a star"
            half -> "$whole½ stars"
            whole == 1 -> "1 star"
            else -> "$whole stars"
        }
    }
}

private fun DrawScope.drawStars(rating: Int, star: Float, tint: Color, empty: Color) {
    val path = starPath(star)
    // The tips rounded a little, as a drawn star's are, in the one fill: a quiet colour drawn twice
    // over itself would show a ring.
    val paint = Paint().apply { pathEffect = PathEffect.cornerPathEffect(star * ROUNDING) }
    drawIntoCanvas { canvas ->
        for (i in 0 until STARS) {
            val left = i * star * (1 + STAR_GAP)
            canvas.save()
            canvas.translate(left, 0f)
            paint.color = empty
            canvas.drawPath(path, paint)
            val filled = (rating - i * 2).coerceIn(0, 2)
            if (filled > 0) {
                // A half star is the star's left half.
                canvas.save()
                canvas.clipRect(0f, 0f, if (filled == 2) star else star / 2, star)
                paint.color = tint
                canvas.drawPath(path, paint)
                canvas.restore()
            }
            canvas.restore()
        }
    }
}

/** A five-pointed star filling a [size] square, points up. */
private fun starPath(size: Float): Path {
    val outer = size / 2
    val inner = outer * INNER
    // Centred on the square's middle, nudged down so the star sits evenly (its point is taller than its feet).
    val c = Offset(size / 2, size / 2 + outer * 0.06f)
    return Path().apply {
        for (k in 0 until 10) {
            val r = if (k % 2 == 0) outer else inner
            val a = -PI / 2 + k * PI / 5
            val x = c.x + (r * cos(a)).toFloat()
            val y = c.y + (r * sin(a)).toFloat()
            if (k == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

private const val STARS = 5

/** The space between stars, as a share of one star. */
private const val STAR_GAP = 0.18f

/** The inner points' radius as a share of the outer: a full, friendly star. */
private const val INNER = 0.5f

/** How round the tips are: the corner's radius as a share of the star. */
private const val ROUNDING = 0.08f
