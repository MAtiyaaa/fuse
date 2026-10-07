package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Which slot generated art fills; it lays out differently for each. */
enum class ArtSlot { ICON, BOX, WIDE, HERO, SYSTEM }

/**
 * Original, deterministic placeholder art for anything without artwork, so every tile still looks
 * intentional and the same title always gets the same picture.
 *
 * The composition is lit like the rest of Fuse: a deep gradient in the platform's colour, a key
 * light falling from above (its place chosen by the title), a softer bounce light in a neighbouring
 * hue from below, one of four quiet original patterns (orbits, contour lines, a halftone of dots,
 * folded facets) and a vignette that gives it depth. Icons and system slots set two initials in the
 * display face with a soft shadow; covers, wide and hero slots set the full title over a dark
 * floor. The platform tag is a small uppercase pill.
 *
 * Cheap to draw: every brush and path is built once per size and drawn once into a picture that is
 * shown from then on, since nothing moves.
 */
@Composable
fun GeneratedArt(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    slot: ArtSlot = ArtSlot.ICON,
    label: String? = null,
    /** False when the container lays its own title over the art, so the name never appears twice. */
    showText: Boolean = true,
) {
    val seed = remember(title) { artSeed(title) }
    val floor = showText && slot != ArtSlot.ICON && slot != ArtSlot.SYSTEM
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .drawWithCache { composeArt(seed, accent, floor) },
    ) {
        val density = LocalDensity.current
        val w = maxWidth
        val h = maxHeight
        val short = if (w < h) w else h
        val type = Fuse.type
        val pad = (short * 0.075f).coerceIn(4.dp, 16.dp)
        // Small tiles (list thumbnails) drop the tag: it could not be read.
        val tagRoom = short >= 72.dp
        fun Dp.asSp() = with(density) { toSp() }
        if (showText) when (slot) {
            ArtSlot.ICON, ArtSlot.SYSTEM -> {
                val initials = remember(title) { initialsOf(title) }
                val size = h * (if (initials.length > 2) 0.24f else 0.31f)
                val tagged = label != null && tagRoom
                Box(
                    Modifier.fillMaxSize().padding(bottom = if (tagged) h * 0.06f else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        initials,
                        style = type.display.copy(
                            brush = Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.78f))),
                            fontSize = size.asSp(),
                            lineHeight = (size * 1.05f).asSp(),
                            letterSpacing = (-0.03).em,
                            shadow = Shadow(Color.Black.copy(alpha = 0.32f), Offset(0f, with(density) { (size * 0.06f).toPx() }), with(density) { (size * 0.22f).toPx() }),
                        ),
                        maxLines = 1,
                    )
                }
                if (tagged) PlatformTag(label, short, Modifier.align(Alignment.BottomStart).padding(pad))
            }
            else -> Column(
                Modifier.fillMaxSize().padding(pad),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (label != null && tagRoom) PlatformTag(label, short, Modifier) else Box(Modifier)
                // Small covers get smaller type, so words wrap whole instead of breaking apart.
                val titleStyle = if (slot == ArtSlot.HERO) type.hero else type.title
                // The longest word has to fit on one line, measured in the face itself, so a word is
                // never broken across lines on a small cover.
                val widthDp = w.value
                val measurer = rememberTextMeasurer(cacheSize = 0)
                val room = with(density) { (w - pad * 2).toPx() }
                val titleSize = if (slot == ArtSlot.BOX) {
                    val fits = remember(title, titleStyle, room) {
                        val probe = 20f
                        val longest = title.split(' ').filter { it.isNotBlank() }.maxByOrNull { it.length } ?: title
                        val measured = measurer.measure(longest, titleStyle.copy(fontSize = probe.dp.asSp(), lineHeight = TextUnit.Unspecified), softWrap = false, maxLines = 1).size.width
                        if (measured <= 0) Float.MAX_VALUE else probe * room / measured * 0.96f
                    }
                    // Fitting wins over a minimum size: a tiny cover (a collage) gets tiny type, never a broken word.
                    minOf(titleStyle.fontSize.value, widthDp / 6.5f, fits).coerceAtLeast(MIN_TITLE)
                } else {
                    titleStyle.fontSize.value
                }
                BasicText(
                    title,
                    style = titleStyle.copy(
                        color = Color.White,
                        fontSize = titleSize.dp.asSp(),
                        lineHeight = (titleSize * 1.12f).dp.asSp(),
                        shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 8f),
                    ),
                    maxLines = if (slot == ArtSlot.BOX) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The platform's short name as a small uppercase pill, sized from the slot's short side. */
@Composable
private fun PlatformTag(label: String, short: Dp, modifier: Modifier) {
    val density = LocalDensity.current
    val size = (short * 0.062f).coerceIn(8.dp, 11.dp)
    val sp = with(density) { size.toSp() }
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.34f), PillShape)
            .padding(horizontal = size * 0.7f, vertical = size * 0.32f),
    ) {
        BasicText(
            label.uppercase(),
            style = Fuse.type.overline.copy(
                color = Color.White.copy(alpha = 0.9f),
                fontSize = sp,
                lineHeight = with(density) { (size * 1.2f).toSp() },
                letterSpacing = 0.12.em,
            ),
            maxLines = 1,
        )
    }
}

/** The smallest cover title, in dp of type: only collage-sized covers ever reach it. */
private const val MIN_TITLE = 5f

/** The near-black every generated picture sinks into, with a hint of blue so it isn't flat. */
private val Night = Color(0xFF07080C)

/**
 * Builds the lit composition for [seed] and [accent] once per size. Every value that varies comes
 * from the seed, so two titles side by side differ but each always looks the same.
 */
private fun CacheDrawScope.composeArt(seed: Int, accent: Color, floor: Boolean): DrawResult {
    val w = size.width
    val h = size.height
    val short = min(w, h)
    val long = max(w, h)
    val rng = Rng(seed)
    val leanRight = rng.next() < 0.5f
    val key = vivid(accent)
    val bounce = vivid(bounceOf(accent))
    rng.next()

    // Base: deep colour falling from the lit top toward night at the bottom, leaning one way.
    val base = Brush.linearGradient(
        0f to lerp(Night, accent, 0.5f),
        0.55f to lerp(Night, accent, 0.26f),
        1f to lerp(Night, accent, 0.08f),
        start = Offset(if (leanRight) 0f else w, 0f),
        end = Offset(if (leanRight) w * 0.65f else w * 0.35f, h),
    )
    // Key light from above, placed by the title.
    val keyCenter = Offset(w * (0.16f + 0.68f * rng.next()), h * (-0.1f + 0.4f * rng.next()))
    val keyLight = Brush.radialGradient(
        0f to key.copy(alpha = 0.6f),
        0.32f to key.copy(alpha = 0.24f),
        1f to Color.Transparent,
        center = keyCenter,
        radius = long * (0.72f + 0.25f * rng.next()),
    )
    // Bounce light from below, on the far side, in a neighbouring hue.
    val bounceCenter = Offset(if (keyCenter.x > w / 2) w * 0.08f else w * 0.92f, h * 1.08f)
    val bounceLight = Brush.radialGradient(
        0f to bounce.copy(alpha = 0.28f),
        1f to Color.Transparent,
        center = bounceCenter,
        radius = long * 0.78f,
    )
    val vignette = Brush.radialGradient(
        0.5f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.42f),
        center = Offset(w / 2, h * 0.42f),
        radius = sqrt(w * w + h * h) * 0.62f,
    )
    val floorBrush = if (floor) Brush.verticalGradient(0.42f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.58f)) else null

    val line = (short / 140f).coerceIn(1f, 2.5f)
    val pattern = when (rng.pick(4)) {
        0 -> orbits(keyCenter, short, line)
        1 -> contours(w, h, short, line, rng, leanRight)
        2 -> halftone(w, h, short, keyCenter, long)
        else -> facets(w, h, rng, leanRight)
    }

    // Nothing here moves: the picture is drawn once at this size and shown as one image after that,
    // instead of six gradients and a pattern of dozens of shapes on every tile, every frame.
    val flat = io.github.matiyaaa.fuse.ui.designsystem.effects.FlatLayer()
    return onDrawBehind {
        flat.draw(this, null) {
            drawRect(base)
            drawRect(keyLight)
            drawRect(bounceLight)
            pattern(this)
            drawRect(vignette)
            floorBrush?.let { drawRect(it) }
        }
    }
}

private typealias Pattern = (androidx.compose.ui.graphics.drawscope.DrawScope) -> Unit

/** Rings spreading from the key light, fading as they go. */
private fun orbits(center: Offset, short: Float, line: Float): Pattern {
    val step = short * 0.24f
    val stroke = Stroke(line)
    return { scope ->
        for (i in 1..8) {
            scope.drawCircle(Color.White, radius = step * i, center = center, alpha = 0.085f * (1f - i / 10f), style = stroke)
        }
    }
}

/** Gently waving contour lines across the picture, like a map of a hill. */
private fun contours(w: Float, h: Float, short: Float, line: Float, rng: Rng, leanRight: Boolean): Pattern {
    val path = Path()
    val count = 9
    val amp = short * (0.05f + 0.04f * rng.next())
    val phase = rng.next() * 2f * PI.toFloat()
    val tilt = (if (leanRight) -1f else 1f) * h * 0.25f
    for (i in 0..count) {
        val y = h * (i + 0.5f) / count
        val segments = 24
        path.moveTo(-w * 0.05f, y + tilt * -0.5f)
        for (s in 1..segments) {
            val t = s / segments.toFloat()
            val x = -w * 0.05f + w * 1.1f * t
            val wave = sin(phase + t * 2.4f * PI.toFloat() + i * 0.55f) * amp
            path.lineTo(x, y + tilt * (t - 0.5f) + wave)
        }
    }
    val stroke = Stroke(line)
    return { scope -> scope.drawPath(path, Color.White, alpha = 0.055f, style = stroke) }
}

/** A halftone of dots that gathers where the key light falls and fades out away from it. */
private fun halftone(w: Float, h: Float, short: Float, light: Offset, long: Float): Pattern {
    val step = (short / 10f).coerceAtLeast(6f)
    val path = Path()
    var y = step / 2
    var row = 0
    while (y < h + step) {
        var x = if (row % 2 == 0) step / 2 else step
        while (x < w + step) {
            val d = (Offset(x, y) - light).getDistance() / long
            val r = step * 0.17f * (1f - d * 1.7f)
            if (r > step * 0.035f) path.addOval(Rect(x - r, y - r, x + r, y + r))
            x += step
        }
        y += step * 0.87f
        row++
    }
    return { scope -> scope.drawPath(path, Color.White, alpha = 0.065f) }
}

/** Large folded planes, as if the light fell across creased paper. */
private fun facets(w: Float, h: Float, rng: Rng, leanRight: Boolean): Pattern {
    val a = rng.next()
    val b = rng.next()
    val light = Path().apply {
        if (leanRight) {
            moveTo(w * (0.25f + 0.3f * a), 0f); lineTo(w, 0f); lineTo(w, h * (0.55f + 0.3f * b)); close()
        } else {
            moveTo(0f, 0f); lineTo(w * (0.45f + 0.3f * a), 0f); lineTo(0f, h * (0.55f + 0.3f * b)); close()
        }
    }
    val shade = Path().apply {
        if (leanRight) {
            moveTo(0f, h * (0.35f + 0.3f * b)); lineTo(w * (0.55f + 0.3f * a), h); lineTo(0f, h); close()
        } else {
            moveTo(w, h * (0.35f + 0.3f * b)); lineTo(w * (0.45f - 0.3f * a), h); lineTo(w, h); close()
        }
    }
    val crease = Path().apply {
        moveTo(if (leanRight) w * (0.25f + 0.3f * a) else w * (0.45f + 0.3f * a), 0f)
        lineTo(if (leanRight) w else 0f, h * (0.55f + 0.3f * b))
    }
    return { scope ->
        scope.drawPath(light, Color.White, alpha = 0.055f)
        scope.drawPath(shade, Color.Black, alpha = 0.16f)
        scope.drawPath(crease, Color.White, alpha = 0.08f, style = Stroke(1f))
    }
}

/** A brighter, slightly richer take on [c] for the lights, so muted platform colours still glow. */
private fun vivid(c: Color): Color {
    val (h, s, v) = hsv(c)
    return Color.hsv(h, (s * 1.18f + 0.06f).coerceAtMost(0.82f), max(v, 0.8f))
}

/**
 * The bounce light's colour: the accent's hue turned a little further round the wheel, away from
 * yellow and green, so warm colours bounce toward red and cool ones toward violet and nothing turns
 * muddy.
 */
private fun bounceOf(accent: Color): Color {
    val hue = hsv(accent).first
    return hueShift(accent, if (hue in 90f..300f) 28f else -28f)
}

/** [c] with its hue turned by [degrees]. */
private fun hueShift(c: Color, degrees: Float): Color {
    val (h, s, v) = hsv(c)
    return Color.hsv(((h + degrees) % 360f + 360f) % 360f, s, v)
}

private fun hsv(c: Color): Triple<Float, Float, Float> {
    val r = c.red
    val g = c.green
    val b = c.blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val d = maxC - minC
    val hue = when {
        d == 0f -> 0f
        maxC == r -> 60f * (((g - b) / d) % 6f)
        maxC == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val sat = if (maxC == 0f) 0f else d / maxC
    return Triple(hue, sat, maxC)
}

/** A stable seed from a title: the same on every device and run. */
private fun artSeed(title: String): Int {
    var h = 0x811C9DC5.toInt()
    for (ch in title.lowercase()) {
        h = h xor ch.code
        h *= 0x01000193
    }
    return h
}

/** A small deterministic generator, so art is varied but repeatable. */
private class Rng(seed: Int) {
    private var state = seed.toLong() * -7046029254386353131L + 0x632BE59BD9B4E019L

    fun next(): Float {
        state = state * 6364136223846793005L + 1442695040888963407L
        return ((state ushr 40).toInt() and 0xFFFFFF) / 16_777_216f
    }

    fun pick(n: Int): Int = (next() * n).toInt().coerceIn(0, n - 1)
}

/** "The Legend of Zelda" -> "LZ", "Tetris" -> "TE", "F-Zero X" -> "FZ". */
fun initialsOf(title: String): String {
    val stop = setOf("the", "a", "an", "of", "and", "to", "in", "on", "for")
    val words = title
        .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        .split(Regex("""[\s\-:_.]+"""))
        .filter { it.isNotBlank() && it.lowercase() !in stop }
    return when {
        words.isEmpty() -> title.take(2).uppercase()
        words.size == 1 -> words[0].take(2).uppercase()
        else -> words.take(2).joinToString("") { it.take(1) }.uppercase()
    }
}
