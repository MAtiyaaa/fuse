package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.RepeatMode
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.tween

/**
 * Setup's "Who's playing" stage. Before anyone has a profile: an empty place, a dashed ring
 * breathing where the first person will be. After: everyone here, the one playing lifted and lit,
 * and a dashed place for the next.
 */
@Composable
internal fun ProfilesStage(profiles: List<ProfileInfo>, playing: ProfileInfo?) {
    val c = Fuse.colors
    val still = Fuse.motion.reduced
    val clock = rememberLoopClock("profiles stage")
    val breathe by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), "breathe")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (profiles.isEmpty()) {
            Box(Modifier.size(260.dp, 220.dp), contentAlignment = Alignment.Center) {
                val accent = c.accent
                val ring = c.text.copy(alpha = 0.45f)
                Canvas(Modifier.size(260.dp, 220.dp)) {
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val glow = 96.dp.toPx() * (0.92f + 0.08f * if (still) 0.5f else breathe)
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent), centre, glow), glow, centre)
                    drawCircle(
                        ring, 58.dp.toPx(), centre,
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx()))),
                    )
                }
                FuseIcon(FuseIcons.UserPlus, size = 44.dp, tint = c.text)
            }
            Spacer(Modifier.height(Space.m))
            FText("Your own place in Fuse", Fuse.type.bodyStrong, maxLines = 1)
        } else {
            val shown = profiles.take(4)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Bottom) {
                for (p in shown) {
                    val here = p.id == playing?.id
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                        Box(
                            Modifier.graphicsLayer {
                                val s = if (here) 1f + 0.04f * (if (still) 0f else breathe) else 0.86f
                                scaleX = s
                                scaleY = s
                            },
                        ) { ProfileAvatar(p.avatar, 88.dp, ring = if (here) c.accent else null, dim = playing != null && !here) }
                        Spacer(Modifier.height(Space.s))
                        FText(p.name, Fuse.type.bodyStrong, color = if (here) c.text else c.textMuted, maxLines = 1, align = TextAlign.Center)
                        FText(if (here) "Playing" else " ", Fuse.type.caption, color = c.accent, maxLines = 1, align = TextAlign.Center)
                    }
                }
                if (profiles.size < 4) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                        Box(Modifier.size(88.dp).graphicsLayer { scaleX = 0.86f; scaleY = 0.86f }, contentAlignment = Alignment.Center) {
                            val ring = c.textFaint
                            Canvas(Modifier.size(88.dp)) {
                                drawCircle(
                                    ring, size.minDimension / 2f - 2.dp.toPx(),
                                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 6.dp.toPx()))),
                                )
                            }
                            FuseIcon(FuseIcons.Plus, size = 28.dp, tint = c.textMuted)
                        }
                        Spacer(Modifier.height(Space.s))
                        FText("Someone else", Fuse.type.bodyStrong, color = c.textMuted, maxLines = 1, align = TextAlign.Center)
                        FText(" ", Fuse.type.caption, maxLines = 1)
                    }
                }
            }
        }
    }
}
