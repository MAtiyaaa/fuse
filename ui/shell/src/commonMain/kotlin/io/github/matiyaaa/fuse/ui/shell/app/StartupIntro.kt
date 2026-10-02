package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Whether the intro already ran in this process (a window made again doesn't play it twice). */
internal object StartupIntro {
    var played = false

    /** The whole intro, in milliseconds; the reduced-motion version is a short fade. */
    const val LENGTH_MS = 2_700
    const val REDUCED_MS = 1_000

    /** Where a skipped intro jumps to: the opening out. */
    const val EXIT_AT_MS = 2_100
}

/**
 * Fuse's startup: its mark lights like a fuse. A spark catches at the end of the line, runs along it
 * shedding embers, and at the top the frame ignites around it in a burst of light; "Fuse" rises
 * under the mark, and the whole thing opens out into the interface. Any button skips it. With
 * reduced motion it is the finished mark, held a moment, then faded.
 *
 * Plays once when Fuse starts (or the device starts with Fuse as its Home), when Settings, Screen
 * and sound, Startup animation is on; Settings, Developer can play it again.
 */
@Composable
internal fun StartupIntroOverlay(onDone: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val sounds = io.github.matiyaaa.fuse.ui.designsystem.sound.LocalUiSounds.current
    val reduced = motion.reduced
    val length = if (reduced) StartupIntro.REDUCED_MS else StartupIntro.LENGTH_MS
    val clock = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var burstPlayed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        clock.animateTo(length.toFloat(), tween(length, easing = LinearEasing))
        onDone()
    }
    // The spark reaches the frame: the launch sound, once.
    val t = clock.value
    LaunchedEffect(t >= BURST_AT && !reduced) {
        if (t >= BURST_AT && !reduced && !burstPlayed) {
            burstPlayed = true
            sounds.play(SoundCue.LAUNCH)
        }
    }

    // Any button skips straight to the opening out.
    InputLayer(priority = LayerPriority.DIALOG + 50, modal = true) { _ ->
        val exit = if (reduced) length * 0.6f else StartupIntro.EXIT_AT_MS.toFloat()
        if (clock.value < exit) {
            scope.launch {
                clock.snapTo(exit)
                clock.animateTo(length.toFloat(), tween((length - exit).toInt(), easing = LinearEasing))
                onDone()
            }
        }
        NavResult.CONSUMED
    }

    val embers = remember { embers() }
    val ink = c.ink
    val accent = c.accent
    val text = c.text
    val warm = lerp(accent, Color.White, 0.55f)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Fuse is starting" }
            .graphicsLayer { alpha = 1f - phase(t, if (reduced) length * 0.6f else EXIT_FADE_FROM, length.toFloat()).ease() },
        contentAlignment = Alignment.Center,
    ) {
        val markDp = (min(maxWidth.value, maxHeight.value) * 0.22f).coerceIn(96f, 220f).dp
        Canvas(Modifier.fillMaxSize()) {
            // The room: ink, warmed by the spark's light as it grows.
            drawRect(ink)
            val m = markDp.toPx()
            val lift = m * 0.32f * phase(t, WORD_FROM, WORD_TO).easeOut()
            val origin = Offset(size.width / 2 - m / 2, size.height / 2 - m / 2 - lift)
            val exit = phase(t, EXIT_FROM, length.toFloat()).easeIn()
            val scale = 1f + exit * 0.35f
            withTransform({ scale(scale, scale, pivot = origin + Offset(m / 2, m / 2)) }) {
                if (reduced) {
                    drawFinishedMark(origin, m, text, accent)
                } else {
                    drawIgnition(t, origin, m, text, accent, warm, embers)
                }
            }
        }
        // "Fuse", rising under the mark as the burst settles; letters close up as they arrive.
        val word = if (reduced) 1f else phase(t, WORD_FROM, WORD_TO).easeOut()
        val wordOut = if (reduced) 0f else phase(t, EXIT_FROM, EXIT_FROM + 300f)
        Box(
            Modifier
                .offset(y = markDp * 0.5f + markDp * 0.05f * (1f - word))
                .graphicsLayer { alpha = word * (1f - wordOut) },
        ) {
            FText(
                "Fuse",
                Fuse.type.display.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (0.04f + 0.3f * (1f - word)).em),
                color = text,
                maxLines = 1,
            )
        }
    }
}

/** The intro's beats, in milliseconds. */
private const val CATCH_TO = 320f
private const val RUN_FROM = 280f
private const val RUN_TO = 1_180f
private const val BURST_AT = 1_180f
private const val FRAME_TO = 1_620f
private const val WORD_FROM = 1_420f
private const val WORD_TO = 2_050f
private const val EXIT_FROM = 2_150f
private const val EXIT_FADE_FROM = 2_250f

/** The fuse line inside the mark, in the mark's own units (as [FuseMark] draws it). */
private fun fusePath(o: Offset, m: Float) = Path().apply {
    moveTo(o.x + m * 0.28f, o.y + m * 0.7f)
    cubicTo(o.x + m * 0.42f, o.y + m * 0.7f, o.x + m * 0.44f, o.y + m * 0.34f, o.x + m * 0.64f, o.y + m * 0.34f)
}

/** The frame, starting at its bottom left corner, so it lights from where the fuse began. */
private fun framePath(o: Offset, m: Float): Path {
    val s = m * 0.1f
    return Path().apply {
        addRoundRect(RoundRect(o.x + s / 2, o.y + s / 2, o.x + m - s / 2, o.y + m - s / 2, CornerRadius(m * 0.28f)))
    }
}

private fun DrawScope.drawIgnition(t: Float, o: Offset, m: Float, text: Color, accent: Color, warm: Color, embers: List<Ember>) {
    val stroke = m * 0.1f
    val line = fusePath(o, m)
    val measure = PathMeasure().apply { setPath(line, false) }
    val length = measure.length
    val run = phase(t, RUN_FROM, RUN_TO).easeInOut()
    val head = measure.getPosition(length * run)
    val spark = Offset(o.x + m * 0.7f, o.y + m * 0.3f)
    val burst = phase(t, BURST_AT, BURST_AT + 650f)

    // The room's glow: the spark lights everything, brightest as it bursts.
    val glow = (phase(t, 0f, RUN_TO) * 0.35f + (1f - burst) * if (t >= BURST_AT) 0.45f else 0f).coerceIn(0f, 0.6f)
    val lightAt = if (t < BURST_AT) head else spark
    drawRect(
        Brush.radialGradient(listOf(accent.copy(alpha = glow), accent.copy(alpha = glow * 0.25f), Color.Transparent), center = lightAt, radius = size.maxDimension * 0.6f),
    )

    // The burnt line behind the spark: a lit core over a soft glow.
    if (run > 0f) {
        val burnt = Path()
        measure.getSegment(0f, length * run, burnt, true)
        drawPath(burnt, accent.copy(alpha = 0.35f), style = Stroke(stroke * 2.4f, cap = StrokeCap.Round))
        drawPath(burnt, text, style = Stroke(stroke, cap = StrokeCap.Round))
    }

    // Embers shed along the way, falling and fading.
    for (e in embers) {
        val born = RUN_FROM + (RUN_TO - RUN_FROM) * e.at
        val age = (t - born) / e.life
        if (age !in 0f..1f) continue
        val from = measure.getPosition(length * phase(born, RUN_FROM, RUN_TO).easeInOut())
        val p = from + Offset(cos(e.angle) * e.speed * m * age, sin(e.angle) * e.speed * m * age + m * 0.35f * age * age)
        drawCircle(lerp(warm, accent, age), radius = m * e.size * (1f - age * 0.6f), center = p, alpha = (1f - age) * 0.9f)
    }

    // The frame ignites from where the fuse began, running all the way round.
    val frameRun = phase(t, BURST_AT - 60f, FRAME_TO).easeOut()
    if (frameRun > 0f) {
        val frame = framePath(o, m)
        val fm = PathMeasure().apply { setPath(frame, true) }
        val lit = Path()
        fm.getSegment(0f, fm.length * frameRun, lit, true)
        drawPath(lit, accent.copy(alpha = 0.3f * (1f - burst * 0.5f)), style = Stroke(stroke * 2.2f, cap = StrokeCap.Round))
        drawPath(lit, text, style = Stroke(stroke, cap = StrokeCap.Round))
    }

    // The spark: catching at the start, running, then resting at the top as the mark's own.
    val at = when {
        t < BURST_AT -> head
        else -> {
            val settle = phase(t, BURST_AT, BURST_AT + 260f).easeOut()
            head + (spark - head) * settle
        }
    }
    val catch = phase(t, 0f, CATCH_TO).easeOut()
    val flicker = 1f + 0.18f * sin(t / 38f) * (1f - phase(t, BURST_AT, BURST_AT + 400f))
    val haloR = m * (0.08f + 0.18f * catch) * flicker * (1f + (1f - burst) * if (t >= BURST_AT) 0.6f else 0f)
    drawCircle(Brush.radialGradient(listOf(warm, accent.copy(alpha = 0.6f), Color.Transparent), center = at, radius = haloR), radius = haloR, center = at)
    drawCircle(lerp(Color.White, accent, phase(t, BURST_AT, BURST_AT + 500f)), radius = m * 0.07f * catch, center = at)

    // The burst: a ring of light opening from the spark, and a flash.
    if (t >= BURST_AT && burst < 1f) {
        val r = m * (0.15f + 1.9f * burst.easeOut())
        drawCircle(accent.copy(alpha = (1f - burst) * 0.85f), radius = r, center = spark, style = Stroke(m * 0.05f * (1f - burst) + 1f))
        drawCircle(warm.copy(alpha = (1f - burst) * 0.5f), radius = r * 0.62f, center = spark, style = Stroke(m * 0.025f * (1f - burst) + 1f))
        drawRect(Color.White.copy(alpha = (1f - phase(t, BURST_AT, BURST_AT + 220f)) * 0.12f))
    }
}

/** The mark as it is when lit: the frame, the line and the spark. */
private fun DrawScope.drawFinishedMark(o: Offset, m: Float, text: Color, accent: Color) {
    val stroke = m * 0.1f
    drawPath(framePath(o, m), text, style = Stroke(stroke))
    drawPath(fusePath(o, m), text, style = Stroke(stroke, cap = StrokeCap.Round))
    val spark = Offset(o.x + m * 0.7f, o.y + m * 0.3f)
    drawCircle(Brush.radialGradient(listOf(accent, accent.copy(alpha = 0f)), center = spark, radius = m * 0.22f), radius = m * 0.22f, center = spark)
    drawCircle(accent, radius = m * 0.07f, center = spark)
}

/** One ember: when along the run it is shed (0..1), which way and how fast it flies, how big, how long it lives. */
private class Ember(val at: Float, val angle: Float, val speed: Float, val size: Float, val life: Float)

/** The same embers every time: the intro is a designed moment, not noise. */
private fun embers(): List<Ember> {
    val r = Random(0xF05E)
    return List(26) {
        Ember(
            at = it / 26f + r.nextFloat() * 0.03f,
            // Mostly upward and backward, away from where the spark is going.
            angle = (PI * (1.05 + r.nextDouble() * 0.9)).toFloat(),
            speed = 0.25f + r.nextFloat() * 0.45f,
            size = 0.012f + r.nextFloat() * 0.018f,
            life = 380f + r.nextFloat() * 380f,
        )
    }
}

/** How far [t] is between [from] and [to], 0..1. */
private fun phase(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

private fun Float.easeOut(): Float = 1f - (1f - this) * (1f - this) * (1f - this)
private fun Float.easeIn(): Float = this * this * this
private fun Float.easeInOut(): Float = if (this < 0.5f) 4f * this * this * this else 1f - (-2f * this + 2f).let { it * it * it } / 2f
private fun Float.ease(): Float = this * this * (3f - 2f * this)

private fun lerp(a: Color, b: Color, f: Float): Color = Color(
    a.red + (b.red - a.red) * f,
    a.green + (b.green - a.green) * f,
    a.blue + (b.blue - a.blue) * f,
    a.alpha + (b.alpha - a.alpha) * f,
)
