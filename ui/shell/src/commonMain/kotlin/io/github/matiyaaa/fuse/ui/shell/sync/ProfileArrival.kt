package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseAvatars
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.Spring
import io.github.matiyaaa.fuse.ui.fuseline.Tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [p] becomes the one playing, and their arrival plays. The first time someone plays on this device,
 * whether their profile was just [made] or they signed in to one from another device, it is the
 * grand welcome; after that, the usual one.
 */
internal fun arrive(app: AppState, p: io.github.matiyaaa.fuse.sync.ProfileInfo, made: Boolean = false) {
    val sync = app.store.sync
    val c = sync.config.value
    app.arrivalGrand = made || (c.welcomedSeeded && p.id !in c.welcomedProfiles)
    app.arrivalMade = made
    if (p.id !in c.welcomedProfiles) {
        app.scope.launch { sync.configure { s -> s.copy(welcomedProfiles = (s.welcomedProfiles + p.id).distinct()) } }
    }
    app.profileArrival = p
}

/**
 * A familiar profile arrives as a brief signature near its HUD home. Normal navigation remains
 * available. One remembered Fuseline value survives A -> B -> C changes, so cancellation retargets
 * from the current position and velocity rather than queuing three cinematic arrivals.
 */
@Composable
internal fun ProfileArrival(app: AppState) {
    val p = app.profileArrival ?: return
    if (app.arrivalGrand) return FirstArrival(app, p)
    val motion = Fuse.motion
    val progress = remember { FuselineValue(0f) }
    val colors = Fuse.colors
    LaunchedEffect(p.id, motion.reduced) {
        app.platform.sounds.play(SoundCue.SELECT)
        progress.animateTo(1f, if (motion.reduced) Tween(72, curve = Curves.Fade) else Spring(dampingRatio = 1f, stiffness = 1400f))
        delay(180)
        progress.animateTo(0f, Tween(if (motion.reduced) 72 else 120, curve = Curves.Fade))
        if (app.profileArrival?.id == p.id) app.profileArrival = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        androidx.compose.foundation.layout.Row(
            Modifier
                .padding(top = io.github.matiyaaa.fuse.ui.designsystem.theme.Size.hudHeight + Space.s, end = Space.gutter)
                .graphicsLayer {
                    val v = progress.value.coerceIn(0f, 1f)
                    alpha = v
                    translationY = if (motion.reduced) 0f else -Space.s.toPx() * (1f - v)
                }
                .background(colors.surface, io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape.fraction(Fuse.geometry.tileCornerFraction))
                .padding(Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            ProfileAvatar(p.avatar, io.github.matiyaaa.fuse.ui.designsystem.theme.Size.touch, ring = colors.focus)
            Column {
                FText(p.name, Fuse.type.title, color = colors.text, maxLines = 1)
                FText("Your games, saves and Home", Fuse.type.caption, color = colors.textMuted, maxLines = 1)
            }
        }
    }
}

/**
 * Someone playing here for the first time arrives grandly (a profile just made, or one signed in to
 * from another device): a lit fuse runs in from the corner of the screen
 * shedding sparks, reaches the middle and ignites; a ring of light bursts out with rays behind it,
 * their colours bloom, their avatar springs up, "Welcome, Mo" types itself in under it, and then it
 * all gathers into the top line's corner as every other arrival does. About two and a half seconds
 * on Fuseline; under reduced motion a short fade. Input waits while it plays.
 */
@Composable
private fun FirstArrival(app: AppState, p: io.github.matiyaaa.fuse.sync.ProfileInfo) {
    val motion = Fuse.motion
    val c = Fuse.colors
    val style = remember(p.avatar) { FuseAvatars.of(p.avatar) }
    val fuse = remember(p.id) { FuselineValue(0f) }
    val burst = remember(p.id) { FuselineValue(0f) }
    val bloom = remember(p.id) { FuselineValue(0f) }
    val face = remember(p.id) { FuselineValue(0f) }
    val typed = remember(p.id) { FuselineValue(0f) }
    val words = remember(p.id) { FuselineValue(0f) }
    val gather = remember(p.id) { FuselineValue(0f) }
    val greeting = "Welcome, ${p.name}"
    val made = app.arrivalMade
    LaunchedEffect(p.id) {
        app.platform.sounds.play(SoundCue.SELECT)
        if (motion.reduced) {
            fuse.snapTo(1f)
            burst.snapTo(1f)
            bloom.snapTo(1f)
            face.snapTo(1f)
            typed.snapTo(1f)
            words.snapTo(1f)
            delay(900)
            gather.animateTo(1f, Tween(motion.ms(Durations.BASE), curve = Curves.Fade))
        } else {
            // The fuse burns in, then everything at once from the moment it lights.
            fuse.animateTo(1f, Tween(640, curve = Curves.Standard))
            app.platform.sounds.play(SoundCue.ACHIEVEMENT)
            coroutineScope {
                launch { burst.animateTo(1f, Tween(760, curve = Curves.Enter)) }
                launch {
                    delay(40)
                    bloom.animateTo(1f, Tween(620, curve = Curves.Enter))
                }
                launch {
                    delay(120)
                    face.animateTo(1f, Spring(dampingRatio = 0.5f, stiffness = 340f))
                }
                launch {
                    delay(300)
                    typed.animateTo(1f, Tween((greeting.length * 45).coerceIn(320, 760), curve = Curves.Linear))
                }
                launch {
                    delay(700)
                    words.animateTo(1f, Tween(420, curve = Curves.Enter))
                }
            }
            delay(520)
            gather.animateTo(1f, Tween(680, curve = Curves.Sweep))
        }
        app.arrivalGrand = false
        app.profileArrival = null
    }
    InputLayer(priority = LayerPriority.DIALOG + 4, modal = true) { NavResult.CONSUMED }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val reach = hypot(w, h)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val corner = with(density) { Offset(w - Space.gutter.toPx() - 24.dp.toPx(), 32.dp.toPx()) }
        val accent = c.accent
        Canvas(Modifier.fillMaxSize()) {
            val g = gather.value
            val mid = Offset(size.width / 2f, size.height / 2f)
            // The fuse: in from the bottom left on a long S, to the middle.
            val p0 = Offset(size.width * 0.04f, size.height * 0.98f)
            val p1 = Offset(size.width * 0.34f, size.height * 1.04f)
            val p2 = Offset(size.width * 0.16f, size.height * 0.40f)
            fun at(t: Float): Offset {
                val u = 1f - t
                return p0 * (u * u * u) + p1 * (3f * u * u * t) + p2 * (3f * u * t * t) + mid * (t * t * t)
            }
            val f = fuse.value
            val b = burst.value
            val lineFade = (1f - b * 1.6f).coerceIn(0f, 1f)
            if (f > 0f && lineFade > 0f) {
                val steps = 56
                var last = at(0f)
                for (i in 1..steps) {
                    val t = f * i / steps
                    val next = at(t)
                    val k = i / steps.toFloat()
                    drawLine(accent.copy(alpha = 0.22f * k * lineFade), last, next, strokeWidth = (6f + 6f * k) * density.density, cap = StrokeCap.Round)
                    drawLine(Color.White.copy(alpha = (0.15f + 0.85f * k * k) * lineFade), last, next, strokeWidth = (1.5f + 2.5f * k) * density.density, cap = StrokeCap.Round)
                    last = next
                }
                // Sparks shed along the way: each flies off from where the fuse was, falls a little and dies.
                for (k in 0 until SPARKS) {
                    val born = (k + 0.5f) / SPARKS
                    val age = (f - born) * 4f
                    if (age <= 0f || age >= 1f) continue
                    val angle = k * 2.3999f
                    val from = at(born)
                    val pos = from + Offset(cos(angle), sin(angle)) * (age * 46f * density.density) + Offset(0f, age * age * 26f * density.density)
                    drawCircle((if (k % 3 == 0) Color.White else Color(0xFFFFD27A)).copy(alpha = (1f - age) * lineFade), (2.6f * (1f - age) + 0.6f) * density.density, pos)
                }
                // The burning head.
                val head = at(f)
                val flicker = 0.88f + 0.12f * sin(f * 70f)
                drawCircle(Brush.radialGradient(listOf(Color.White, accent.copy(alpha = 0.6f), Color.Transparent), head, 30f * density.density * flicker), 30f * density.density * flicker, head, alpha = lineFade)
                drawCircle(Color.White.copy(alpha = lineFade), 4.5f * density.density, head)
            }
            // Their colours bloom from the middle, then gather into the corner with everything else.
            val centre = Offset(mid.x + (corner.x - mid.x) * g, mid.y + (corner.y - mid.y) * g)
            val r = reach * 0.62f * bloom.value * (1f - g * 0.985f)
            if (r > 0.5f) {
                drawCircle(Brush.linearGradient(listOf(style.from, style.to), start = centre - Offset(r, r), end = centre + Offset(r, r)), r, centre, alpha = 1f - g * 0.35f)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), centre - Offset(0f, r * 0.45f), r), r, centre, alpha = 1f - g)
            }
            // The ignition: a ring of light bursting out, and rays behind it.
            if (b > 0f && b < 1f) {
                val fade = 1f - b
                for (k in 0 until RAYS) {
                    val a = k * (2f * PI.toFloat() / RAYS) + 0.2f
                    val dir = Offset(cos(a), sin(a))
                    val inner = (84f + 110f * b) * density.density
                    val outer = inner + (40f * density.density) + reach * 0.20f * b
                    drawLine(Color.White.copy(alpha = 0.7f * fade), mid + dir * inner, mid + dir * outer, strokeWidth = 3f * density.density, cap = StrokeCap.Round)
                }
                drawCircle(Color.White.copy(alpha = 0.9f * fade), 72f * density.density + reach * 0.34f * b, mid, style = Stroke((12f * fade + 1f) * density.density))
                val late = (b * 1.3f - 0.3f).coerceIn(0f, 1f)
                if (late > 0f) drawCircle(accent.copy(alpha = 0.6f * (1f - late)), 60f * density.density + reach * 0.24f * late, mid, style = Stroke((8f * (1f - late) + 1f) * density.density))
            }
        }
        Column(
            Modifier.fillMaxSize().graphicsLayer {
                val g = gather.value
                alpha = 1f - g
                val s = 1f - g * 0.6f
                scaleX = s
                scaleY = s
                translationX = (corner.x - w / 2f) * g
                translationY = (corner.y - h / 2f) * g
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.graphicsLayer {
                    val s = 0.3f + 0.7f * face.value
                    scaleX = s
                    scaleY = s
                    alpha = face.value.coerceIn(0f, 1f)
                },
            ) {
                ProfileAvatar(p.avatar, 156.dp, ring = Color.White)
            }
            Spacer(Modifier.height(Space.l))
            // The greeting types itself in; the rest of it holds its place, so the line never moves.
            val shown = (greeting.length * typed.value).toInt().coerceIn(0, greeting.length)
            androidx.compose.foundation.layout.Row {
                FText(greeting.take(shown), Fuse.type.hero, color = Color.White, maxLines = 1)
                FText(greeting.drop(shown), Fuse.type.hero, color = Color.Transparent, maxLines = 1)
            }
            FText(
                if (made) "Your saves, play time and theme are yours now" else "Your saves, play time and theme are here now", Fuse.type.body, color = Color.White.copy(alpha = 0.85f), maxLines = 1, align = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = words.value
                    translationY = (1f - words.value) * 14.dp.toPx()
                },
            )
        }
    }
}

/** Sparks the fuse sheds on its way, and rays the ignition throws. */
private const val SPARKS = 26
private const val RAYS = 14
