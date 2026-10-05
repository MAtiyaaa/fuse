package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/** Where each device sits around the host on the stage, as offsets from its centre. */
private val SPOTS = listOf(Offset(-118f, -62f), Offset(118f, -62f), Offset(0f, 92f))

/**
 * The stage for Fuse Sync: the host in the middle, lit, and three devices around it (a computer, a
 * handheld, a phone) on dashed lines. A save travels out along each line in turn, so it reads as
 * one library in step everywhere; a person's avatar rides with it. Set up, the host shows a check.
 */
@Composable
internal fun SyncStage(on: Boolean) {
    val c = Fuse.colors
    val still = Fuse.motion.reduced
    val clock = rememberLoopClock("sync stage")
    val travel by clock.animateFloat(0f, 3f, infiniteRepeatable(tween(5400), RepeatMode.Restart), "travel")
    val breathe by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), "breathe")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(320.dp, 250.dp), contentAlignment = Alignment.Center) {
            val accent = c.accent
            val line = c.text.copy(alpha = 0.18f)
            Canvas(Modifier.size(320.dp, 250.dp)) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                SPOTS.forEachIndexed { i, s ->
                    val end = centre + Offset(s.x.dp.toPx(), s.y.dp.toPx())
                    drawLine(line, centre, end, strokeWidth = 2.dp.toPx(), pathEffect = dash)
                    // The save on its way: out from the host on this line, in its turn.
                    val t = if (still) 0.5f else (travel - i).coerceIn(0f, 1f)
                    if (still || (travel >= i && travel < i + 1)) {
                        val p = centre + (end - centre) * t
                        drawCircle(accent.copy(alpha = 0.22f), 11.dp.toPx(), p)
                        drawCircle(accent, 5.dp.toPx(), p)
                    }
                }
                // The host's glow, breathing.
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.30f + 0.10f * breathe), Color.Transparent), centre, 92.dp.toPx()),
                    92.dp.toPx(), centre,
                )
                drawCircle(accent.copy(alpha = 0.35f), 44.dp.toPx(), centre, style = Stroke(1.5.dp.toPx()))
            }
            // The host.
            Box(
                Modifier.size(76.dp).graphicsLayer { shadowElevation = 20.dp.toPx(); shape = RoundedCornerShape(24.dp); clip = true }
                    .background(Brush.linearGradient(listOf(c.accent, c.accent.copy(alpha = 0.72f)))),
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(if (on) FuseIcons.Check else FuseIcons.RefreshCcw, size = 34.dp, tint = c.onAccent)
            }
            Device(FuseIcons.Laptop, "PC", SPOTS[0])
            Device(FuseIcons.Gamepad, "Handheld", SPOTS[1])
            Device(FuseIcons.Smartphone, "Phone", SPOTS[2])
            // Who it follows: their avatar, riding beside the host.
            Box(Modifier.offset(44.dp, 40.dp)) { ProfileAvatar("cat", 34.dp, ring = c.surfaceOverlay) }
        }
        Spacer(Modifier.height(Space.m))
        FText(if (on) "Fuse Sync is on" else "Your games, in step on every device", Fuse.type.bodyStrong, maxLines = 1)
    }
}

/** A device on the stage: a rounded card with its icon and name, at [spot] from the centre. */
@Composable
private fun Device(icon: ImageVector, name: String, spot: Offset, size: Dp = 64.dp) {
    val c = Fuse.colors
    Column(
        Modifier.offset(spot.x.dp, spot.y.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(18.dp)).background(c.surfaceOverlay)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(icon, size = 26.dp, tint = c.text)
        }
        FText(name, Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}
