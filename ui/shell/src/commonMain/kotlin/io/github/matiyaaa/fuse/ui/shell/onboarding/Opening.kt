package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.LocalUiSounds
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.fuseline.CubicCurve
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Timeline
import io.github.matiyaaa.fuse.ui.fuseline.rememberTimelinePlayer
import io.github.matiyaaa.fuse.ui.shell.app.BrandArt
import io.github.matiyaaa.fuse.ui.shell.app.drawWordmark
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The first thing setup shows, once: Fuse lighting itself, longer and larger than the startup
 * animation, built on a [Timeline]. In the dark a spark catches the end of a fuse line that crosses
 * the whole screen (the logo's own fuse, seen very close), shedding embers as it runs. The camera
 * follows it into the frame, which traces itself around, then pulls far back until the mark is its
 * own size; the spark reaches its place and the mark ignites in a flash and a ring of light. Tiles
 * scatter outward from it in three depths, the mark steps aside and the wordmark burns in letter by
 * letter beside it, Fuse's lockup, which then gives way to setup. Any button or touch skips to the
 * end. Under reduced motion it is the lockup fading in and out.
 */
@Composable
internal fun SetupOpening(onDone: () -> Unit) {
    val c = Fuse.colors
    val reduced = Fuse.motion.reduced
    val sounds = LocalUiSounds.current
    val done by rememberUpdatedState(onDone)
    val play = rememberTimelinePlayer(OpeningTimeline, reduced = reduced, reducedMs = 1_200, onFinished = { done() })
    // The mark catching light is the moment the sound comes.
    val lit = play.timeMs >= IGNITE_AT
    LaunchedEffect(lit) { if (lit && !reduced) sounds.play(SoundCue.LAUNCH) }
    InputLayer(priority = LayerPriority.DIALOG + 60, modal = true) { _ ->
        play.skip()
        NavResult.CONSUMED
    }
    val embers = remember { embers() }
    val tiles = remember { tiles() }
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Fuse is starting. Press any button to skip" },
    ) {
        val out = play["out"]
        drawRect(c.ink.copy(alpha = 1f - out))
        val alpha = 1f - out
        if (alpha <= 0f) return@Canvas
        // The camera: the mark seen very close at first (its fuse spanning the screen), pulled back
        // until the mark is its own size.
        val markSize = min(size.width, size.height) * 0.2f
        val zoom = play["zoom"]
        val closeScale = max(size.width, size.height) * 0.9f / BrandArt.MARK
        val farScale = markSize / BrandArt.MARK
        val scale = closeScale + (farScale - closeScale) * zoom
        // What the camera looks at: the middle of the fuse line close up, the mark's centre far.
        val focusClose = Offset(48f, 52f)
        val focusFar = Offset(BrandArt.MARK / 2, BrandArt.MARK / 2)
        val focus = focusClose + (focusFar - focusClose) * zoom
        // The mark steps left for the wordmark as it burns in.
        val word = play["word"]
        val cap = markSize / BrandArt.LOCKUP_MARK
        val lockupW = cap * BrandArt.LOCKUP_W
        val shift = -(lockupW / 2 - markSize / 2) * play["aside"]
        val centre = Offset(size.width / 2 + shift, size.height / 2)
        // Close up the line is a fine wire and the spark a point of light; they reach the logo's
        // own proportions as the camera pulls back.
        fun onScreen(close: Float, far: Float) = (close + (far - close) * zoom) / scale
        val wire = onScreen(3.dp.toPx(), BrandArt.MARK_STROKE * farScale)
        val coreR = onScreen(7.dp.toPx(), BrandArt.CORE_R * farScale)
        val glowR = onScreen(48.dp.toPx(), BrandArt.GLOW_R * farScale)
        val emberR = onScreen(2.5.dp.toPx(), 0.9f * farScale)
        fun toScreen(p: Offset) = Offset(centre.x + (p.x - focus.x) * scale, centre.y + (p.y - focus.y) * scale)

        withTransform({
            translate(centre.x - focus.x * scale, centre.y - focus.y * scale)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            val ink = c.text.copy(alpha = alpha)
            // The fuse line burns in behind the spark.
            val run = play["run"]
            val fuse = PathMeasure().apply { setPath(BrandArt.fuse, false) }
            val drawn = Path()
            fuse.getSegment(0f, fuse.length * run, drawn, true)
            drawPath(drawn, ink, style = Stroke(wire, cap = StrokeCap.Round))
            // The frame traces around.
            val trace = play["trace"]
            if (trace > 0f) {
                val frame = PathMeasure().apply { setPath(BrandArt.frame, false) }
                val f = Path()
                frame.getSegment(0f, frame.length * trace, f, true)
                drawPath(f, ink, style = Stroke(wire, cap = StrokeCap.Round))
            }
            // The spark: at the head of the burning line, then lit in its place.
            val head = if (run < 1f) fuse.getPosition(fuse.length * run) else BrandArt.SPARK
            val ignite = play["ignite"]
            val glow = 0.6f + 0.4f * ignite
            val haloR = glowR * (0.7f + 0.6f * ignite)
            drawCircle(
                Brush.radialGradient(
                    0f to c.accent,
                    0.3f to c.accent.copy(alpha = 0.55f),
                    0.6f to c.accent.copy(alpha = 0.18f),
                    1f to c.accent.copy(alpha = 0f),
                    center = head,
                    radius = haloR,
                ),
                radius = haloR,
                center = head,
                alpha = alpha * glow,
            )
            drawCircle(Color.White.copy(alpha = alpha), radius = coreR * (0.8f + 0.2f * ignite), center = head)
            if (ignite > 0f) drawCircle(BrandArt.core(c.accent), radius = BrandArt.CORE_R, center = BrandArt.SPARK, alpha = alpha * ignite)
            // Embers shed behind the head as it runs, falling and cooling.
            if (run > 0f && run < 1.2f) {
                for (e in embers) {
                    if (e.at > run) continue
                    val age = ((run - e.at) / 0.25f).coerceIn(0f, 1f)
                    if (age >= 1f) continue
                    val from = fuse.getPosition(fuse.length * e.at.coerceAtMost(1f))
                    val drift = emberR * 6f
                    val p = Offset(from.x + e.dx * age * drift, from.y + (e.dy + age * 2f) * age * drift)
                    drawCircle(lerp(Color.White, c.accent, age).copy(alpha = alpha * (1f - age)), radius = emberR * (1f - age * 0.6f), center = p)
                }
            }
        }

        // Ignition: a flash over everything and a ring of light running out from the spark.
        val ignite = play["ignite"]
        val spark = toScreen(BrandArt.SPARK)
        val flash = play["flash"]
        if (flash > 0f) {
            drawRect(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.55f * flash), Color.Transparent), center = spark, radius = max(size.width, size.height) * 0.7f), alpha = alpha)
            drawRect(Color.White.copy(alpha = 0.08f * flash * alpha))
        }
        val ring = play["ring"]
        if (ring > 0f && ring < 1f) {
            val r = max(size.width, size.height) * 0.9f * ring
            drawCircle(c.accent.copy(alpha = (1f - ring) * 0.7f * alpha), radius = r, center = spark, style = Stroke(markSize * 0.05f * (1f - ring) + 1f))
        }
        // Tiles scatter from the mark in three depths, the near ones faster and larger.
        val scatter = play["tiles"]
        if (scatter > 0f && scatter < 1f) {
            for (t in tiles) {
                val d = scatter * (0.6f + t.depth * 0.8f)
                val dist = min(size.width, size.height) * (0.15f + d * 0.9f)
                val p = Offset(centre.x + cos(t.angle) * dist, centre.y + sin(t.angle) * dist * 0.75f)
                val s = markSize * (0.25f + t.depth * 0.35f) * (0.6f + scatter * 0.6f)
                val fade = (1f - scatter) * (0.2f + 0.3f * t.depth) * alpha
                drawRoundRect(
                    if (t.lit) c.accent.copy(alpha = fade) else c.text.copy(alpha = fade * 0.6f),
                    Offset(p.x - s / 2, p.y - s * 0.65f),
                    Size(s, s * 1.3f),
                    CornerRadius(s * 0.16f),
                )
            }
        }
        // The wordmark burns in beside the mark: its letters appear left to right with a hot edge.
        if (word > 0f) {
            val left = centre.x + markSize / 2 + cap * BrandArt.LOCKUP_GAP
            val top = centre.y - cap / 2
            val wordW = cap * BrandArt.WORD_W / BrandArt.CAP
            val edge = left + wordW * word
            clipRect(left = left - 2f, top = top - cap, right = edge, bottom = top + cap * 2) {
                drawWordmark(Offset(left, top), cap, c.text.copy(alpha = alpha))
            }
            if (word < 1f) {
                val heat = ((1f - word) * 5f).coerceAtMost(1f) * alpha
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, c.accent.copy(alpha = 0.9f * heat), Color.White.copy(alpha = heat)), startX = edge - cap * 0.6f, endX = edge),
                    topLeft = Offset(edge - cap * 0.6f, top - cap * 0.05f),
                    size = Size(cap * 0.6f, cap * 1.1f),
                )
            }
        }
        drawIgnition(spark, ignite, c.accent, markSize, alpha)
    }
}

/** The light the mark gives off once it is lit: a soft halo that breathes down to the logo's glow. */
private fun DrawScope.drawIgnition(at: Offset, ignite: Float, accent: Color, markSize: Float, alpha: Float) {
    if (ignite <= 0f) return
    val r = markSize * (0.5f + 0.9f * (1f - ignite))
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f * ignite * alpha), Color.Transparent), center = at, radius = r), radius = r, center = at)
}

private fun lerp(a: Color, b: Color, f: Float) = Color(
    a.red + (b.red - a.red) * f,
    a.green + (b.green - a.green) * f,
    a.blue + (b.blue - a.blue) * f,
    a.alpha + (b.alpha - a.alpha) * f,
)

/** When the spark reaches its place and the mark lights. */
private const val IGNITE_AT = 4_100f

/** The opening's choreography, in milliseconds. */
private val OpeningTimeline = Timeline(7_400) {
    val swift = CubicCurve(0.6f, 0f, 0.2f, 1f)
    // The spark runs the fuse line, slow to catch, then quick.
    track("run") { at(500, 0f); at(2_300, 1f, CubicCurve(0.5f, 0f, 0.4f, 1f)) }
    // The frame traces around as the camera starts to pull back.
    track("trace") { at(2_000, 0f); at(3_300, 1f, Curves.Standard) }
    // The camera pulls back from the fuse to the whole mark.
    track("zoom") { at(2_200, 0f); at(4_100, 1f, swift) }
    // Ignition, its flash and its ring.
    track("ignite") { at(4_100, 0f); at(4_400, 1f, Curves.Enter) }
    track("flash") { at(4_050, 0f); at(4_150, 1f, Curves.Linear); at(4_900, 0f, Curves.Fade) }
    track("ring") { at(4_100, 0f); at(5_200, 1f, Curves.Standard) }
    track("tiles") { at(4_200, 0f); at(6_000, 1f, CubicCurve(0.2f, 0.6f, 0.3f, 1f)) }
    // The mark steps aside and the word burns in.
    track("aside") { at(5_000, 0f); at(5_700, 1f, Curves.Standard) }
    track("word") { at(5_300, 0f); at(6_400, 1f, CubicCurve(0.3f, 0f, 0.3f, 1f)) }
    // The lockup holds, then gives way to setup.
    track("out") { at(6_800, 0f); at(7_400, 1f, Curves.Fade) }
}

private class Ember(val at: Float, val dx: Float, val dy: Float)

/** Embers along the fuse, each with its own drift (fixed, so the opening is the same every time). */
private fun embers(): List<Ember> {
    val r = kotlin.random.Random(7)
    return List(70) { Ember(it / 70f, r.nextFloat() * 2f - 1f, -r.nextFloat() * 1.5f) }
}

private class ScatterTile(val angle: Float, val depth: Float, val lit: Boolean)

/** Tiles flying out from the mark, spread around it in three depths. */
private fun tiles(): List<ScatterTile> {
    val r = kotlin.random.Random(11)
    return List(18) { i ->
        val angle = (i / 18f) * 2f * PI.toFloat() + r.nextFloat() * 0.3f
        ScatterTile(angle, (i % 3) / 2f, i % 5 == 0)
    }
}
