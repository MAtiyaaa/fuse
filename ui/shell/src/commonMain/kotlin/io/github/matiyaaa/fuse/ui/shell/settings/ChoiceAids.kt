package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.matiyaaa.fuse.model.GameArtStyle
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.fuseline.RepeatMode
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.tween
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * A picture of what a choice does, shown beside the choice list for the option under focus: an
 * abstract sketch in the theme's own colours, moving gently (a focus travelling over tiles, a page
 * opening, a row drifting by), so the difference between options is seen rather than read. Under
 * Reduced motion it holds still.
 */
@Immutable
sealed interface ChoiceAid {
    /** The Library's views: icons, capsules, covers or a list. */
    data class LibraryView(val layout: LibraryLayout) : ChoiceAid

    /** Square box art or tall posters, wherever games are tiles. */
    data class GameArt(val style: GameArtStyle) : ChoiceAid

    /** The Library opening on every game everywhere, or only this device's. */
    data class OpensOn(val installed: Boolean) : ChoiceAid

    /** Confirm on a game plays it, or opens its page. */
    data class SelectGame(val opensPage: Boolean) : ChoiceAid

    /** Home as a board of tiles, or shelves that run on. */
    data class HomeStyle(val style: io.github.matiyaaa.fuse.model.HomeStyle) : ChoiceAid

    /** How large text is. */
    data class TextSize(val scale: Float) : ChoiceAid

    /** Room kept clear at the screen's edges, in percent. */
    data class ScreenEdges(val percent: Int) : ChoiceAid

    /** How things move (null: automatic, drawn as Fuse's standard). */
    data class Motion(val profile: MotionProfile?) : ChoiceAid
}

@Composable
fun ChoiceAidView(aid: ChoiceAid, description: String, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val still = !Fuse.motion.ambient
    val clock = rememberLoopClock(label = "choice aid")
    val loop = clock.animateFloat(0f, 1f, infiniteRepeatable(tween(LOOP_MS), RepeatMode.Restart), label = "aid loop")
    val colors = AidColors(
        room = c.text.copy(alpha = if (c.isDark) 0.04f else 0.035f),
        tile = c.text.copy(alpha = if (c.isDark) 0.13f else 0.1f),
        line = c.text.copy(alpha = if (c.isDark) 0.22f else 0.18f),
        accent = c.accent,
        focus = c.focus,
        text = c.text,
    )
    Canvas(modifier.semantics { contentDescription = description }) {
        val t = if (still) 0.35f else loop.value
        drawRoundRect(colors.room, cornerRadius = CornerRadius(14.px(this)))
        val pad = 16.px(this)
        // Clipped a little outside the sketch, so a focus ring on its edge tiles shows whole.
        val clip = 8.px(this)
        clipRect(clip, clip, size.width - clip, size.height - clip) {
            translate(left = pad, top = pad) {
                val area = Size(size.width - pad * 2, size.height - pad * 2)
                when (aid) {
                    is ChoiceAid.LibraryView -> libraryView(aid.layout, area, t, colors)
                    is ChoiceAid.GameArt -> gameArt(aid.style, area, t, colors)
                    is ChoiceAid.OpensOn -> opensOn(aid.installed, area, t, colors)
                    is ChoiceAid.SelectGame -> selectGame(aid.opensPage, area, t, colors)
                    is ChoiceAid.HomeStyle -> homeStyle(aid.style, area, t, colors)
                    is ChoiceAid.TextSize -> textSize(aid.scale, area, colors)
                    is ChoiceAid.ScreenEdges -> screenEdges(aid.percent, area, colors)
                    is ChoiceAid.Motion -> motion(aid.profile ?: MotionProfile.STANDARD, area, t, colors)
                }
            }
        }
    }
}

private class AidColors(val room: Color, val tile: Color, val line: Color, val accent: Color, val focus: Color, val text: Color)

/** Density-independent pixels, at this drawing's density. */
private fun Int.px(d: DrawScope): Float = this * d.density

private fun Float.px(d: DrawScope): Float = this * d.density

/** A tile, rounded as the theme rounds small things. */
private fun DrawScope.tile(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float = 6.px(this)) =
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r.coerceAtMost(minOf(w, h) / 2)))

/** The focus on a tile: an outline a little outside it. */
private fun DrawScope.ring(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float = 6.px(this)) {
    val g = 3.px(this)
    drawRoundRect(color, Offset(x - g, y - g), Size(w + g * 2, h + g * 2), CornerRadius(r + g), style = Stroke(2.px(this)))
}

/** A line of text, as a bar. */
private fun DrawScope.textLine(color: Color, x: Float, y: Float, w: Float, h: Float = 5.px(this)) =
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(h / 2))

/** Which of [n] places a focus travelling once around the loop is on. */
private fun step(t: Float, n: Int): Int = floor(t * n).toInt().coerceIn(0, n - 1)

private fun DrawScope.grid(area: Size, cols: Int, rows: Int, aspect: Float, t: Float, c: AidColors, labels: Boolean) {
    val gap = 8.px(this)
    val labelH = if (labels) 12.px(this) else 0f
    val w = (area.width - gap * (cols - 1)) / cols
    val h = minOf(w / aspect, (area.height - gap * (rows - 1)) / rows - labelH)
    val tw = h * aspect
    val focus = step(t, cols * rows)
    for (r in 0 until rows) for (col in 0 until cols) {
        val i = r * cols + col
        val x = col * (w + gap) + (w - tw) / 2
        val y = r * (h + labelH + gap)
        tile(if (i == focus) c.accent.copy(alpha = 0.55f) else c.tile, x, y, tw, h)
        if (labels) textLine(c.line, x, y + h + 4.px(this), tw * 0.7f, 4.px(this))
        if (i == focus) ring(c.focus, x, y, tw, h)
    }
}

private fun DrawScope.libraryView(layout: LibraryLayout, area: Size, t: Float, c: AidColors) {
    when (layout) {
        LibraryLayout.ICON -> grid(area, cols = 5, rows = 2, aspect = 1f, t = t, c = c, labels = true)
        LibraryLayout.COVER_GRID -> grid(area, cols = 6, rows = 2, aspect = 2f / 3f, t = t, c = c, labels = false)
        LibraryLayout.CAPSULE -> {
            // Wide capsules with the game's logo across them.
            val gap = 8.px(this)
            val cols = 3
            val rows = 3
            val w = (area.width - gap * (cols - 1)) / cols
            val h = minOf(w / 2.1f, (area.height - gap * (rows - 1)) / rows)
            val focus = step(t, cols * rows)
            for (r in 0 until rows) for (col in 0 until cols) {
                val i = r * cols + col
                val x = col * (w + gap)
                val y = r * (h + gap)
                tile(if (i == focus) c.accent.copy(alpha = 0.55f) else c.tile, x, y, w, h)
                textLine(c.line, x + w * 0.22f, y + h / 2 - 3.px(this), w * 0.56f, 6.px(this))
                if (i == focus) ring(c.focus, x, y, w, h)
            }
        }
        LibraryLayout.COMPACT_LIST -> {
            // Rows: a small picture, a title and a line under it, the highlight moving down.
            val rows = 5
            val gap = 6.px(this)
            val h = (area.height - gap * (rows - 1)) / rows
            val focus = step(t, rows)
            for (r in 0 until rows) {
                val y = r * (h + gap)
                if (r == focus) tile(c.tile, 0f, y, area.width, h)
                tile(if (r == focus) c.accent.copy(alpha = 0.6f) else c.tile, 6.px(this), y + 4.px(this), h - 8.px(this), h - 8.px(this))
                textLine(if (r == focus) c.text.copy(alpha = 0.6f) else c.line, h + 6.px(this), y + h * 0.3f, area.width * 0.45f)
                textLine(c.line, h + 6.px(this), y + h * 0.58f, area.width * 0.28f, 4.px(this))
            }
        }
    }
}

private fun DrawScope.gameArt(style: GameArtStyle, area: Size, t: Float, c: AidColors) {
    // One shelf of games, its art square or tall.
    val aspect = if (style == GameArtStyle.POSTER) 2f / 3f else 1f
    val cols = if (style == GameArtStyle.POSTER) 5 else 4
    val gap = 10.px(this)
    val w = (area.width - gap * (cols - 1)) / cols
    val h = minOf(w / aspect, area.height * 0.78f)
    val tw = h * aspect
    val focus = step(t, cols)
    val y = (area.height - h) / 2
    for (i in 0 until cols) {
        val x = i * (w + gap) + (w - tw) / 2
        tile(if (i == focus) c.accent.copy(alpha = 0.55f) else c.tile, x, y, tw, h, 8.px(this))
        // The art's own picture, suggested by a horizon line.
        textLine(c.line, x + tw * 0.15f, y + h * 0.78f, tw * 0.7f, 4.px(this))
        if (i == focus) ring(c.focus, x, y, tw, h, 8.px(this))
    }
}

private fun DrawScope.opensOn(installed: Boolean, area: Size, t: Float, c: AidColors) {
    // The two views on a pill, the chosen one lit; under it, the games it opens on.
    val pillW = area.width * 0.6f
    val pillH = 22.px(this)
    val px = (area.width - pillW) / 2
    tile(c.tile, px, 0f, pillW, pillH, pillH / 2)
    val half = pillW / 2
    tile(c.accent.copy(alpha = 0.7f), px + if (installed) half else 0f, 0f, half, pillH, pillH / 2)
    textLine(c.text.copy(alpha = 0.7f), px + half * 0.3f, pillH / 2 - 2.5f.px(this), half * 0.4f)
    textLine(c.text.copy(alpha = 0.7f), px + half * 1.2f, pillH / 2 - 2.5f.px(this), half * 0.6f)
    val cols = 5
    val rows = 2
    val gap = 8.px(this)
    val top = pillH + 14.px(this)
    val w = (area.width - gap * (cols - 1)) / cols
    val h = minOf(w, (area.height - top - gap) / rows)
    // Every third game is somewhere else: on RomM or another device. Installed shows only the
    // rest, closed up as the Library closes them up.
    val shown = (0 until rows * cols).filter { !(installed && it % 3 == 1) }
    shown.forEachIndexed { place, i ->
        val elsewhere = i % 3 == 1
        val col = place % cols
        val r = place / cols
        val x = col * (w + gap) + (w - h) / 2
        val y = top + r * (h + gap)
        if (elsewhere) {
            drawRoundRect(
                c.line, Offset(x, y), Size(h, h), CornerRadius(6.px(this)),
                style = Stroke(1.5f.px(this), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.px(this), 4.px(this)))),
            )
            // A cloud's worth: where it comes from, faintly.
            drawCircle(c.accent.copy(alpha = 0.5f), 4.px(this), Offset(x + h - 8.px(this), y + 8.px(this)))
        } else {
            tile(c.tile, x, y, h, h)
        }
    }
    // The focus wanders over what is there.
    val f = step(t, shown.size)
    ring(c.focus, (f % cols) * (w + gap) + (w - h) / 2, top + (f / cols) * (h + gap), h, h)
}

private fun DrawScope.selectGame(opensPage: Boolean, area: Size, t: Float, c: AidColors) {
    // A game focused; confirm pressed half way through the loop.
    val pressed = t > 0.35f
    val p = ((t - 0.35f) / 0.4f).coerceIn(0f, 1f)
    val eased = 1f - (1f - p) * (1f - p)
    val tw = area.width * 0.26f
    val th = tw
    val tx = (area.width - tw) / 2
    val ty = (area.height - th) / 2
    if (!opensPage) {
        // Plays it: the tile lifts, a play mark, then the screen goes to the game.
        tile(c.accent.copy(alpha = 0.55f), tx, ty, tw, th, 10.px(this))
        ring(c.focus, tx, ty, tw, th, 10.px(this))
        if (pressed) {
            val r = tw * 0.22f
            val cx = area.width / 2
            val cy = area.height / 2
            val tri = Path().apply {
                moveTo(cx - r * 0.6f, cy - r)
                lineTo(cx + r, cy)
                lineTo(cx - r * 0.6f, cy + r)
                close()
            }
            drawPath(tri, c.text.copy(alpha = 0.85f))
            // The game takes the screen.
            drawRect(c.accent.copy(alpha = 0.35f * eased), size = area)
        }
    } else {
        // Opens its page: the tile grows into a page with its art and its lines.
        val x = tx * (1f - eased)
        val y = ty * (1f - eased)
        val w = tw + (area.width - tw) * eased
        val h = th + (area.height - th) * eased
        tile(if (pressed) c.tile else c.accent.copy(alpha = 0.55f), x, y, w, h, 10.px(this))
        if (!pressed) ring(c.focus, tx, ty, tw, th, 10.px(this))
        if (eased > 0.6f) {
            val a = (eased - 0.6f) / 0.4f
            tile(c.accent.copy(alpha = 0.5f * a), x + 12.px(this), y + 12.px(this), w * 0.28f, h * 0.5f, 8.px(this))
            textLine(c.text.copy(alpha = 0.6f * a), x + w * 0.36f, y + 16.px(this), w * 0.4f, 8.px(this))
            textLine(c.line.copy(alpha = c.line.alpha * a), x + w * 0.36f, y + 32.px(this), w * 0.5f)
            textLine(c.line.copy(alpha = c.line.alpha * a), x + w * 0.36f, y + 44.px(this), w * 0.42f)
            tile(c.accent.copy(alpha = 0.8f * a), x + w * 0.36f, y + h * 0.62f, w * 0.18f, 18.px(this), 9.px(this))
        }
    }
}

private fun DrawScope.homeStyle(style: io.github.matiyaaa.fuse.model.HomeStyle, area: Size, t: Float, c: AidColors) {
    if (style == io.github.matiyaaa.fuse.model.HomeStyle.FII) {
        // Even channels in white frames, over a bar with the clock between two round buttons.
        val bar = area.height * 0.24f
        val cols = 4
        val rows = 3
        val gap = 6.px(this)
        val w = (area.width - gap * (cols - 1)) / cols
        val h = (area.height - bar - gap * rows) / rows
        val focus = step(t, cols * rows)
        for (i in 0 until cols * rows) {
            val x = (i % cols) * (w + gap)
            val y = (i / cols) * (h + gap)
            tile(if (i == focus) c.accent.copy(alpha = 0.45f) else c.tile, x, y, w, h, h * 0.3f)
            drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(x, y), Size(w, h), CornerRadius(h * 0.3f), style = Stroke(2.px(this)))
            if (i == focus) ring(c.focus, x, y, w, h, h * 0.3f)
        }
        val top = area.height - bar + gap
        drawRoundRect(c.tile, Offset(0f, top), Size(area.width, bar - gap), CornerRadius(bar * 0.4f))
        val r = (bar - gap) * 0.36f
        drawCircle(Color.White.copy(alpha = 0.9f), r, Offset(r * 1.6f, top + (bar - gap) / 2))
        drawCircle(Color.White.copy(alpha = 0.9f), r, Offset(area.width - r * 1.6f, top + (bar - gap) / 2))
        textLine(c.text.copy(alpha = 0.6f), area.width * 0.4f, top + (bar - gap) * 0.3f, area.width * 0.2f, 8.px(this))
        return
    }
    if (style == io.github.matiyaaa.fuse.model.HomeStyle.FUSED) {
        // A board of tiles of different sizes, arranged by hand.
        val u = minOf(area.width / 6f, area.height / 3.4f)
        val gap = 6.px(this)
        val tiles = listOf(
            floatArrayOf(0f, 0f, 2f, 2f), floatArrayOf(2f, 0f, 2f, 1f), floatArrayOf(4f, 0f, 2f, 1f),
            floatArrayOf(2f, 1f, 1f, 1f), floatArrayOf(3f, 1f, 3f, 1f), floatArrayOf(0f, 2f, 3f, 1f), floatArrayOf(3f, 2f, 3f, 1f),
        )
        val focus = step(t, tiles.size)
        tiles.forEachIndexed { i, (x, y, w, h) ->
            val px = x * u + gap / 2
            val py = y * u + gap / 2
            tile(if (i == focus) c.accent.copy(alpha = 0.55f) else c.tile, px, py, w * u - gap, h * u - gap, 8.px(this))
            if (i == focus) ring(c.focus, px, py, w * u - gap, h * u - gap, 8.px(this))
        }
    } else {
        // Shelves that run on: a title, and rows drifting by.
        textLine(c.text.copy(alpha = 0.6f), 0f, 0f, area.width * 0.35f, 9.px(this))
        val rows = 3
        val gap = 8.px(this)
        val top = 20.px(this)
        val h = (area.height - top - gap * (rows - 1)) / rows - 10.px(this)
        val w = h * 1.6f
        for (r in 0 until rows) {
            val y = top + r * (h + gap + 10.px(this))
            textLine(c.line, 0f, y, area.width * 0.18f, 4.px(this))
            val drift = (t * (w + gap) * (if (r % 2 == 0) 1f else 0.6f))
            var x = -drift
            var i = 0
            while (x < area.width) {
                tile(if (r == 0 && i == 1) c.accent.copy(alpha = 0.55f) else c.tile, x, y + 8.px(this), w, h, 6.px(this))
                x += w + gap
                i++
            }
        }
    }
}

private fun DrawScope.textSize(scale: Float, area: Size, c: AidColors) {
    // A heading and lines of text, at the chosen size.
    val base = 9.px(this) * scale
    var y = 4.px(this)
    textLine(c.text.copy(alpha = 0.75f), 0f, y, area.width * 0.55f * scale.coerceAtMost(1.2f), base * 1.6f)
    y += base * 1.6f + 10.px(this)
    repeat(4) { i ->
        textLine(c.line, 0f, y, area.width * (0.92f - i * 0.12f), base)
        y += base + 8.px(this) * scale
    }
}

private fun DrawScope.screenEdges(percent: Int, area: Size, c: AidColors) {
    // A screen, the part a TV may cut off shaded, and Fuse kept inside.
    drawRoundRect(c.tile, size = area, cornerRadius = CornerRadius(8.px(this)))
    val inset = Offset(area.width * percent / 100f * 2.5f, area.height * percent / 100f * 2.5f)
    val inner = Size(area.width - inset.x * 2, area.height - inset.y * 2)
    if (percent > 0) {
        drawRoundRect(
            c.accent.copy(alpha = 0.8f), inset, inner, CornerRadius(6.px(this)),
            style = Stroke(1.5f.px(this), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.px(this), 4.px(this)))),
        )
    }
    val pad = 8.px(this)
    textLine(c.text.copy(alpha = 0.6f), inset.x + pad, inset.y + pad, inner.width * 0.4f, 7.px(this))
    val cols = 4
    val gap = 6.px(this)
    val w = (inner.width - pad * 2 - gap * (cols - 1)) / cols
    val h = minOf(w, inner.height - pad * 3 - 7.px(this))
    for (i in 0 until cols) tile(c.line, inset.x + pad + i * (w + gap), inset.y + pad * 2 + 7.px(this), w, h)
}

private fun DrawScope.motion(profile: MotionProfile, area: Size, t: Float, c: AidColors) {
    // One tile going from one place to another, as each level moves it.
    val w = area.height * 0.42f
    val y = (area.height - w) / 2
    val travel = area.width - w
    // There for the first part of the loop, going for the next, then resting.
    val p = ((t - 0.15f) / 0.45f).coerceIn(0f, 1f)
    val (x, alpha) = when (profile) {
        MotionProfile.REDUCED -> (if (p < 0.5f) 0f else travel) to (1f - sin(p * PI.toFloat()) * 0.9f)
        MotionProfile.MINIMAL -> travel * (1f - (1f - p) * (1f - p)) to 1f
        MotionProfile.STANDARD -> travel * (p * p * (3f - 2f * p)) to 1f
        MotionProfile.ENHANCED -> {
            // A spring: past the mark, and back.
            val s = 1f - kotlin.math.exp(-6f * p) * kotlin.math.cos(9f * p)
            travel * s to 1f
        }
    }
    textLine(c.line, 0f, y + w + 10.px(this), area.width, 2.px(this))
    tile(c.accent.copy(alpha = 0.6f * alpha), x, y, w, w, 10.px(this))
    if (profile == MotionProfile.ENHANCED || profile == MotionProfile.STANDARD) {
        val lift = if (p in 0.01f..0.99f) 1f else 0f
        if (lift > 0f) ring(c.focus.copy(alpha = 0.5f), x, y, w, w, 10.px(this))
    }
}

private const val LOOP_MS = 2_800
