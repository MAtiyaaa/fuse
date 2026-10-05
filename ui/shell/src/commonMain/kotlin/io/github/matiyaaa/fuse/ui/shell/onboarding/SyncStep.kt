package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.RepeatMode
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.spring
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

/**
 * The stage when there is a choice: Fuse Sync and Syncthing side by side. Fuse Sync stands in front,
 * lit in the accent and marked as the one Fuse recommends, with what only it does; Syncthing sits
 * beside it, smaller, in its own teal. Both float gently. Once one is on, it comes forward with a
 * check and the other steps back.
 */
@Composable
internal fun SyncChoiceStage(fuseSync: Boolean, syncthing: Boolean, hasFuseSync: Boolean) {
    val c = Fuse.colors
    val still = Fuse.motion.reduced
    val clock = rememberLoopClock("sync choice stage")
    val drift by clock.animateFloat(-1f, 1f, infiniteRepeatable(tween(4600), RepeatMode.Reverse), "drift")
    val drift2 by clock.animateFloat(1f, -1f, infiniteRepeatable(tween(5300), RepeatMode.Reverse), "drift2")
    val breathe by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2800), RepeatMode.Reverse), "breathe")
    // Which card leads: Fuse Sync, unless Syncthing is the one on.
    val lead by fuselineFloat(if (syncthing) 0f else 1f, spring(dampingRatio = 0.8f, stiffness = 260f), label = "lead")
    val teal = io.github.matiyaaa.fuse.ui.shell.sync.SYNCTHING_TINT
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(340.dp, 250.dp), contentAlignment = Alignment.Center) {
            // The glow behind whichever leads.
            Canvas(Modifier.matchParentSize()) {
                val centre = Offset(size.width * (0.30f + 0.40f * (1f - lead)), size.height / 2f)
                val tint = lerp(teal, c.accent, lead)
                drawCircle(
                    Brush.radialGradient(listOf(tint.copy(alpha = 0.22f + 0.08f * breathe), Color.Transparent), centre, 150.dp.toPx()),
                    150.dp.toPx(), centre,
                )
            }
            if (hasFuseSync) {
                ChoiceCard(
                    name = "Fuse Sync", tag = if (fuseSync) "On" else "Recommended", tint = c.accent, mark = FuseIcons.RefreshCcw,
                    lines = listOf(FuseIcons.Save to "Saves, by game", FuseIcons.Clock to "Play time", FuseIcons.Users to "Saves per person"),
                    on = fuseSync, filled = true,
                    modifier = Modifier.offset((-76).dp, 0.dp).zIndex(lead).graphicsLayer {
                        val s = 0.86f + 0.14f * lead
                        scaleX = s; scaleY = s
                        alpha = 0.55f + 0.45f * lead
                        translationY = if (still) 0f else drift * 4.dp.toPx()
                        rotationZ = -3f * lead
                        shadowElevation = (6f + 18f * lead).dp.toPx()
                        shape = RoundedCornerShape(22.dp)
                    },
                )
            }
            ChoiceCard(
                name = "Syncthing", tag = if (syncthing) "On" else "Bring your own", tint = teal, mark = FuseIcons.FolderSync,
                lines = listOf(FuseIcons.FolderOpen to "Save folders", FuseIcons.Users to "One save for all", FuseIcons.Link2 to "Your Syncthing"),
                on = syncthing, filled = false,
                modifier = Modifier.offset(if (hasFuseSync) 92.dp else 0.dp, 8.dp).zIndex(1f - lead).graphicsLayer {
                    val s = 0.86f + 0.14f * (1f - lead)
                    scaleX = s; scaleY = s
                    alpha = 0.62f + 0.38f * (1f - lead)
                    translationY = if (still) 0f else drift2 * 4.dp.toPx()
                    rotationZ = 4f * lead
                    shadowElevation = (6f + 18f * (1f - lead)).dp.toPx()
                    shape = RoundedCornerShape(22.dp)
                },
            )
        }
        Spacer(Modifier.height(Space.m))
        FText(
            when {
                fuseSync -> "Fuse Sync is on"
                syncthing -> "Syncthing is on"
                else -> "Two ways to stay in step"
            },
            Fuse.type.bodyStrong, maxLines = 1,
        )
    }
}

/** One of the two on the stage: its mark, name and tag, then what it keeps in step, one line each. */
@Composable
private fun ChoiceCard(
    name: String,
    tag: String,
    tint: Color,
    mark: ImageVector,
    lines: List<Pair<ImageVector, String>>,
    on: Boolean,
    filled: Boolean,
    modifier: Modifier,
) {
    val c = Fuse.colors
    Column(
        modifier.width(176.dp).clip(RoundedCornerShape(22.dp)).background(c.surfaceOverlay)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.16f), Color.Transparent)))
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (filled) Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.72f))) else Brush.linearGradient(listOf(tint.copy(alpha = 0.28f), tint.copy(alpha = 0.12f)))),
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(if (on) FuseIcons.Check else mark, size = 20.dp, tint = if (filled) c.onAccent else tint)
            }
            Spacer(Modifier.width(Space.s))
            Column {
                FText(name, Fuse.type.bodyStrong, maxLines = 1)
                FText(tag, Fuse.type.caption, color = tint, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
        for ((icon, line) in lines) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(icon, size = 16.dp, tint = tint)
                Spacer(Modifier.width(Space.s))
                FText(line, Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
        }
    }
}
