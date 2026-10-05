package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import kotlin.math.hypot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Someone becomes the one playing ([AppState.profileArrival]): their avatar's colours bloom out
 * from the middle of the screen as a circle, their avatar springs up inside it with "Hi, Mo" under
 * it, and while everything of theirs arrives behind it the circle gathers itself up into the top
 * right corner, where their avatar lives in the top line, and is gone. About a second and a half,
 * every part of it on Fuseline; under reduced motion a short fade. Input waits while it plays.
 */
@Composable
internal fun ProfileArrival(app: AppState) {
    val p = app.profileArrival ?: return
    val motion = Fuse.motion
    val style = remember(p.avatar) { FuseAvatars.of(p.avatar) }
    val bloom = remember(p.id) { FuselineValue(0f) }
    val face = remember(p.id) { FuselineValue(0f) }
    val words = remember(p.id) { FuselineValue(0f) }
    val gather = remember(p.id) { FuselineValue(0f) }
    LaunchedEffect(p.id) {
        app.platform.sounds.play(SoundCue.SELECT)
        if (motion.reduced) {
            bloom.snapTo(1f)
            face.snapTo(1f)
            words.snapTo(1f)
            delay(500)
            gather.animateTo(1f, Tween(motion.ms(Durations.BASE), curve = Curves.Fade))
        } else {
            coroutineScope {
                launch { bloom.animateTo(1f, Tween(560, curve = Curves.Enter)) }
                launch {
                    delay(140)
                    face.animateTo(1f, Spring(dampingRatio = 0.55f, stiffness = 380f))
                }
                launch {
                    delay(320)
                    words.animateTo(1f, Tween(420, curve = Curves.Enter))
                }
            }
            delay(420)
            gather.animateTo(1f, Tween(620, curve = Curves.Sweep))
        }
        app.profileArrival = null
    }
    // Nothing underneath is pressed while it plays.
    InputLayer(priority = LayerPriority.DIALOG + 4, modal = true) { NavResult.CONSUMED }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val reach = hypot(w, h)
        // Where the circle gathers to: the avatar at the top line's far end.
        val corner = with(androidx.compose.ui.platform.LocalDensity.current) { Offset(w - Space.gutter.toPx() - 24.dp.toPx(), 32.dp.toPx()) }
        Canvas(Modifier.fillMaxSize()) {
            val g = gather.value
            val centre = Offset(size.width / 2f + (corner.x - size.width / 2f) * g, size.height / 2f + (corner.y - size.height / 2f) * g)
            val r = reach * 0.62f * bloom.value * (1f - g * 0.985f)
            if (r <= 0.5f) return@Canvas
            drawCircle(
                Brush.linearGradient(listOf(style.from, style.to), start = centre - Offset(r, r), end = centre + Offset(r, r)),
                r, centre, alpha = 1f - g * 0.35f,
            )
            // A soft light from the top of the circle, like the avatars' own sheen.
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), centre - Offset(0f, r * 0.45f), r),
                r, centre, alpha = 1f - g,
            )
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
                    val s = 0.4f + 0.6f * face.value
                    scaleX = s
                    scaleY = s
                    alpha = face.value.coerceIn(0f, 1f)
                },
            ) {
                ProfileAvatar(p.avatar, 132.dp, ring = Color.White)
            }
            Spacer(Modifier.height(Space.l))
            Column(
                Modifier.graphicsLayer {
                    alpha = words.value
                    translationY = (1f - words.value) * 18.dp.toPx()
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FText("Hi, ${p.name}", Fuse.type.hero, color = Color.White, maxLines = 1, align = TextAlign.Center)
                FText("Your games, saves and Home", Fuse.type.body, color = Color.White.copy(alpha = 0.85f), maxLines = 1, align = TextAlign.Center)
            }
        }
    }
}
