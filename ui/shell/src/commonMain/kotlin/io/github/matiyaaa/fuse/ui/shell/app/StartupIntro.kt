package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    const val LENGTH_MS = 3_200
    const val REDUCED_MS = 1_000

    /** Where a skipped intro jumps to: the opening out. */
    const val EXIT_AT_MS = 2_600
}

/**
 * Fuse's startup: its mark lights like a fuse, then writes its name. A spark catches at the end of
 * the line and runs along it shedding embers; where it reaches the spark the frame ignites around it
 * in a burst of light. The lit mark glides aside and the wordmark burns in beside it, letter by
 * letter, white-hot at the edge and cooling behind, until the two stand as Fuse's lockup, the same
 * art as the website and the README. Then it opens out into the interface. Any button skips it.
 * With reduced motion it is the finished lockup, held a moment, then faded.
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
    fun skip() {
        val exit = if (reduced) length * 0.6f else StartupIntro.EXIT_AT_MS.toFloat()
        if (clock.value < exit) {
            scope.launch {
                clock.snapTo(exit)
                clock.animateTo(length.toFloat(), tween((length - exit).toInt(), easing = LinearEasing))
                onDone()
            }
        }
    }
    InputLayer(priority = LayerPriority.DIALOG + 50, modal = true) { _ ->
        skip()
        NavResult.CONSUMED
    }

    val markEmbers = remember { markEmbers() }
    val wordEmbers = remember { wordEmbers() }
    val colors = IntroColors(ink = c.ink, text = c.text, accent = c.accent)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Fuse is starting" }
            // Touches and clicks never reach the interface underneath; a tap skips, as a button does.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed && !it.previousPressed }) skip()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .graphicsLayer { alpha = 1f - phase(t, if (reduced) length * 0.6f else EXIT_FADE_FROM, length.toFloat()).ease() },
        contentAlignment = Alignment.Center,
    ) {
        // The lockup's capitals: large, but the whole lockup always fits across the screen.
        val capDp = min(min(maxWidth.value, maxHeight.value) * 0.17f, maxWidth.value * 0.78f / BrandArt.LOCKUP_W).coerceIn(28f, 150f).dp
        Canvas(Modifier.fillMaxSize()) {
            drawRect(colors.ink)
            val lockup = Lockup(center, capDp.toPx())
            val exit = phase(t, EXIT_FROM, length.toFloat()).easeIn()
            withTransform({ scale(1f + exit * 0.3f, 1f + exit * 0.3f, pivot = center) }) {
                if (reduced) {
                    drawBrandMark(lockup.markAt, lockup.mark, colors.text, colors.accent)
                    drawWordmark(lockup.wordAt, lockup.cap, colors.text)
                } else {
                    drawIntro(t, lockup, colors, markEmbers, wordEmbers)
                }
            }
        }
    }
}

/** The intro's beats, in milliseconds. */
private const val CATCH_TO = 320f
private const val RUN_FROM = 280f
private const val RUN_TO = 1_180f
private const val BURST_AT = 1_180f
private const val FRAME_TO = 1_600f
private const val GLIDE_FROM = 1_420f
private const val GLIDE_TO = 1_960f
private const val BURN_FROM = 1_720f
private const val BURN_TO = 2_480f
private const val EXIT_FROM = 2_650f
private const val EXIT_FADE_FROM = 2_750f

/** How much bigger the mark is while it lights, centred, than in the finished lockup. */
private const val SOLO = 1.4f

/** The burning edge's band, in wordmark units: white-hot at the front, cooling to the letters' colour behind. */
private const val BAND = 70f

private class IntroColors(val ink: Color, val text: Color, val accent: Color) {
    val warm = mix(accent, Color.White, 0.55f)
    val hot = mix(accent, Color.White, 0.9f)
}

/** Fuse's horizontal lockup centred on [center], with capitals [cap] tall (as the brand's lockup.py lays it out). */
private class Lockup(center: Offset, val cap: Float) {
    val mark = cap * BrandArt.LOCKUP_MARK
    private val left = center.x - cap * BrandArt.LOCKUP_W / 2
    val markAt = Offset(left, center.y - mark / 2)
    val wordAt = Offset(left + mark + cap * BrandArt.LOCKUP_GAP, center.y - cap / 2)

    /** Where the mark lights: bigger, in the middle of the screen. */
    val soloMark = mark * SOLO
    val soloAt = Offset(center.x - soloMark / 2, center.y - soloMark / 2)
}

private fun DrawScope.drawIntro(t: Float, lockup: Lockup, colors: IntroColors, markEmbers: List<Ember>, wordEmbers: List<Ember>) {
    // The mark lights in the middle, then glides aside into the lockup as the word begins.
    val glide = phase(t, GLIDE_FROM, GLIDE_TO).easeInOut()
    val markSize = lockup.soloMark + (lockup.mark - lockup.soloMark) * glide
    val markAt = lockup.soloAt + (lockup.markAt - lockup.soloAt) * glide
    val k = markSize / BrandArt.MARK

    val fuse = PathMeasure().apply { setPath(BrandArt.fuse, false) }
    val run = phase(t, RUN_FROM, RUN_TO).easeInOut()
    val head = if (t < BURST_AT) fuse.getPosition(fuse.length * run) else BrandArt.SPARK
    val burst = phase(t, BURST_AT, BURST_AT + 650f)

    // The room's glow: the spark lights everything, brightest as it bursts, settling as the word burns.
    val glow = (phase(t, 0f, RUN_TO) * 0.35f + (1f - burst) * if (t >= BURST_AT) 0.45f else 0f).coerceIn(0f, 0.6f) *
        (1f - 0.55f * phase(t, GLIDE_FROM, BURN_TO))
    val lightAt = markAt + head * k
    drawRect(
        Brush.radialGradient(
            listOf(colors.accent.copy(alpha = glow), colors.accent.copy(alpha = glow * 0.25f), Color.Transparent),
            center = lightAt,
            radius = size.maxDimension * 0.6f,
        ),
    )

    withTransform({
        translate(markAt.x, markAt.y)
        scale(k, k, pivot = Offset.Zero)
    }) {
        drawIgnitingMark(t, fuse, run, head, burst, colors, markEmbers)
    }

    // The flash as the frame ignites, over everything.
    if (t >= BURST_AT && t < BURST_AT + 220f) {
        drawRect(Color.White.copy(alpha = (1f - phase(t, BURST_AT, BURST_AT + 220f)) * 0.12f))
    }

    if (t >= BURN_FROM) {
        withTransform({
            translate(lockup.wordAt.x, lockup.wordAt.y)
            scale(lockup.cap / BrandArt.CAP, lockup.cap / BrandArt.CAP, pivot = Offset.Zero)
        }) {
            drawBurningWord(t, colors, wordEmbers)
        }
    }
}

/** The mark, in its own 100 units: the fuse burning in, the frame igniting, the spark and its burst. */
private fun DrawScope.drawIgnitingMark(t: Float, fuse: PathMeasure, run: Float, head: Offset, burst: Float, colors: IntroColors, embers: List<Ember>) {
    val stroke = BrandArt.MARK_STROKE
    val length = fuse.length
    // The lit glow round the line and frame cools away as the lockup settles, leaving the brand's clean mark.
    val cool = 1f - phase(t, GLIDE_FROM, BURN_TO)

    // The burnt line behind the spark: a lit core over a soft glow.
    if (run > 0f) {
        val burnt = Path()
        fuse.getSegment(0f, length * run, burnt, true)
        drawPath(burnt, colors.accent.copy(alpha = 0.35f * cool), style = Stroke(stroke * 2.4f, cap = StrokeCap.Round))
        drawPath(burnt, colors.text, style = Stroke(stroke, cap = StrokeCap.Round))
    }

    // Embers shed along the way, falling and fading.
    for (e in embers) {
        val born = RUN_FROM + (RUN_TO - RUN_FROM) * e.at
        val age = (t - born) / e.life
        if (age !in 0f..1f) continue
        val from = fuse.getPosition(length * phase(born, RUN_FROM, RUN_TO).easeInOut())
        val p = from + Offset(cos(e.angle) * e.speed * 100f * age, sin(e.angle) * e.speed * 100f * age + 35f * age * age)
        drawCircle(mix(colors.warm, colors.accent, age), radius = 100f * e.size * (1f - age * 0.6f), center = p, alpha = (1f - age) * 0.9f)
    }

    // The frame ignites from its top, beside the spark, running both ways round to meet at the bottom.
    val frameRun = phase(t, BURST_AT - 60f, FRAME_TO).easeOut()
    if (frameRun > 0f) {
        val fm = PathMeasure().apply { setPath(BrandArt.frame, true) }
        val half = fm.length * frameRun / 2
        val lit = Path()
        fm.getSegment(0f, half, lit, true)
        fm.getSegment(fm.length - half, fm.length, lit, true)
        drawPath(lit, colors.accent.copy(alpha = 0.3f * (1f - burst * 0.5f) * cool), style = Stroke(stroke * 2.2f, cap = StrokeCap.Round))
        drawPath(lit, colors.text, style = Stroke(stroke, cap = StrokeCap.Round))
    }

    // The spark: catching at the start, running, then resting as the mark's own.
    val catch = phase(t, 0f, CATCH_TO).easeOut()
    val flicker = 1f + 0.18f * sin(t / 38f) * (1f - phase(t, BURST_AT, BURST_AT + 400f))
    val swell = 1f + (1f - burst) * if (t >= BURST_AT) 0.6f else 0f
    val settled = phase(t, BURST_AT, BURST_AT + 500f)
    val haloR = (8f + 18f * catch) * flicker * swell
    val halo = haloR + (BrandArt.GLOW_R - haloR) * settled
    drawCircle(
        Brush.radialGradient(listOf(colors.warm, colors.accent.copy(alpha = 0.6f), Color.Transparent), center = head, radius = halo),
        radius = halo,
        center = head,
        alpha = 1f - settled,
    )
    if (settled > 0f) {
        drawCircle(BrandArt.glow(colors.accent), radius = BrandArt.GLOW_R, center = BrandArt.SPARK, alpha = settled)
    }
    drawCircle(Color.White, radius = BrandArt.CORE_R * catch, center = head, alpha = 1f - settled)
    if (settled > 0f) {
        drawCircle(BrandArt.core(colors.accent), radius = BrandArt.CORE_R, center = BrandArt.SPARK, alpha = settled)
    }

    // The burst: rings of light opening from the spark.
    if (t >= BURST_AT && burst < 1f) {
        val r = 15f + 190f * burst.easeOut()
        drawCircle(colors.accent.copy(alpha = (1f - burst) * 0.85f), radius = r, center = BrandArt.SPARK, style = Stroke(5f * (1f - burst) + 1f))
        drawCircle(colors.warm.copy(alpha = (1f - burst) * 0.5f), radius = r * 0.62f, center = BrandArt.SPARK, style = Stroke(2.5f * (1f - burst) + 1f))
    }
}

/** Where the burning edge is along the word, in wordmark units: from just before the F to past the e. */
private fun edgeAt(t: Float): Float = -10f + (BrandArt.WORD_W + BAND + 20f) * phase(t, BURN_FROM, BURN_TO).ease()

/** The wordmark burning in from the left, in its own units (capitals 100 tall). */
private fun DrawScope.drawBurningWord(t: Float, colors: IntroColors, embers: List<Ember>) {
    val edge = edgeAt(t)
    val burning = t < BURN_TO

    // The light of the burn, travelling with the edge and dying down as it finishes.
    val heat = phase(t, BURN_FROM, BURN_FROM + 120f) * (1f - phase(t, BURN_TO - 160f, BURN_TO + 220f))
    if (heat > 0f) {
        val at = Offset(edge.coerceAtMost(BrandArt.WORD_W), 52f)
        drawCircle(
            Brush.radialGradient(listOf(colors.accent.copy(alpha = 0.42f * heat), colors.accent.copy(alpha = 0.12f * heat), Color.Transparent), center = at, radius = 95f),
            radius = 95f,
            center = at,
        )
    }

    if (burning) {
        drawPath(
            BrandArt.wordmark,
            Brush.horizontalGradient(
                0f to colors.text,
                0.42f to colors.accent,
                0.8f to colors.warm,
                0.95f to colors.hot,
                1f to colors.hot.copy(alpha = 0f),
                startX = edge - BAND,
                endX = edge,
            ),
        )
    } else {
        drawPath(BrandArt.wordmark, colors.text)
    }

    // Sparks thrown off the burning edge, falling and fading.
    for (e in embers) {
        val born = BURN_FROM + (BURN_TO - BURN_FROM) * e.at
        val age = (t - born) / e.life
        if (age !in 0f..1f) continue
        val from = Offset(edgeAt(born).coerceIn(0f, BrandArt.WORD_W), e.y)
        val p = from + Offset(cos(e.angle) * e.speed * 100f * age, sin(e.angle) * e.speed * 100f * age + 60f * age * age)
        drawCircle(mix(colors.hot, colors.accent, age), radius = 100f * e.size * (1f - age * 0.6f), center = p, alpha = (1f - age) * 0.85f)
    }
}

/** One ember: when along its run it is shed (0..1), which way and how fast it flies, how big, how long it lives, and its height on a letter. */
private class Ember(val at: Float, val angle: Float, val speed: Float, val size: Float, val life: Float, val y: Float = 0f)

/** The same embers every time: the intro is a designed moment, not noise. */
private fun markEmbers(): List<Ember> {
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

/** Sparks off the word's burning edge: thrown back and up from the letters, then falling. */
private fun wordEmbers(): List<Ember> {
    val r = Random(0xF05F)
    return List(22) {
        Ember(
            at = it / 22f + r.nextFloat() * 0.04f,
            angle = (PI * (1.0 + r.nextDouble() * 0.6)).toFloat(),
            speed = 0.2f + r.nextFloat() * 0.35f,
            size = 0.01f + r.nextFloat() * 0.016f,
            life = 320f + r.nextFloat() * 340f,
            y = 15f + r.nextFloat() * 75f,
        )
    }
}

/** How far [t] is between [from] and [to], 0..1. */
private fun phase(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

private fun Float.easeOut(): Float = 1f - (1f - this) * (1f - this) * (1f - this)
private fun Float.easeIn(): Float = this * this * this
private fun Float.easeInOut(): Float = if (this < 0.5f) 4f * this * this * this else 1f - (-2f * this + 2f).let { it * it * it } / 2f
private fun Float.ease(): Float = this * this * (3f - 2f * this)

/**
 * Fuse coming back to the front after time in the background with no game played (the screen was
 * off, the device slept), and how long it was away. The interface replays the startup animation
 * after a long enough absence ([AWAY_INTRO_MS]), so waking the device feels like switching it on.
 */
internal object Away {
    private val _returns = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val returns: kotlinx.coroutines.flow.SharedFlow<Long> = _returns

    fun returned(awayMs: Long) {
        _returns.tryEmit(awayMs)
    }

    /** Away this long or longer replays the startup animation. */
    const val AWAY_INTRO_MS = 10 * 60_000L
}
