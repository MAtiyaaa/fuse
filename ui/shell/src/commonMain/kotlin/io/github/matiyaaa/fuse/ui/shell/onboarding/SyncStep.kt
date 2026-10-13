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
        FText(if (on) "Fuse One is on" else "Your games, in step on every device", Fuse.type.bodyStrong, maxLines = 1)
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
 * The stage when there is a choice: the ways this device's saves can move, side by side. Fuse One
 * stands in front, lit in the accent and marked as the one Fuse recommends; RomM and Syncthing sit
 * beside it, smaller, each in its own colour. All float gently. Once one is on, it comes forward
 * with a check and the others step back.
 */
@Composable
internal fun SyncChoiceStage(fuseSync: Boolean, syncthing: Boolean, hasFuseSync: Boolean, romm: Boolean = false, hasRomm: Boolean = false) {
    val c = Fuse.colors
    val still = Fuse.motion.reduced
    val clock = rememberLoopClock("sync choice stage")
    val drift by clock.animateFloat(-1f, 1f, infiniteRepeatable(tween(4600), RepeatMode.Reverse), "drift")
    val drift2 by clock.animateFloat(1f, -1f, infiniteRepeatable(tween(5300), RepeatMode.Reverse), "drift2")
    val breathe by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2800), RepeatMode.Reverse), "breathe")
    val teal = io.github.matiyaaa.fuse.ui.shell.sync.SYNCTHING_TINT
    val rommTint = Color(0xFF8B7CF6)
    // Which card leads: the one that is on, else Fuse One (Syncthing when it is the only one here).
    val leader = when {
        syncthing -> 2
        romm && hasRomm -> 1
        hasFuseSync -> 0
        else -> 2
    }
    val leadFuse by fuselineFloat(if (leader == 0) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 260f), label = "leadFuse")
    val leadRomm by fuselineFloat(if (leader == 1) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 260f), label = "leadRomm")
    val leadThing by fuselineFloat(if (leader == 2) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 260f), label = "leadThing")
    // Where each card sits: three across, or two when one isn't here.
    val three = hasFuseSync && hasRomm
    val xFuse = if (three) (-96).dp else (-76).dp
    val xRomm = if (three) 0.dp else if (hasFuseSync) 92.dp else (-76).dp
    val xThing = if (three) 96.dp else if (hasFuseSync || hasRomm) 92.dp else 0.dp
    fun Modifier.card(lead: Float, x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, wobble: Float, tilt: Float) =
        offset(x, y).zIndex(lead).graphicsLayer {
            val s = 0.82f + 0.18f * lead
            scaleX = s; scaleY = s
            alpha = 0.55f + 0.45f * lead
            translationY = if (still) 0f else wobble * 4.dp.toPx()
            rotationZ = tilt * (1f - lead)
            shadowElevation = (6f + 18f * lead).dp.toPx()
            shape = RoundedCornerShape(22.dp)
        }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(if (three) 380.dp else 340.dp, 250.dp), contentAlignment = Alignment.Center) {
            // The glow behind whichever leads.
            Canvas(Modifier.matchParentSize()) {
                val xs = listOf(xFuse, xRomm, xThing).map { size.width / 2f + it.toPx() }
                val leads = listOf(leadFuse, leadRomm, leadThing)
                val total = leads.sum().coerceAtLeast(0.001f)
                val centre = Offset(xs.indices.sumOf { (xs[it] * leads[it]).toDouble() }.toFloat() / total, size.height / 2f)
                val tint = lerp(lerp(teal, rommTint, leadRomm / (leadRomm + leadThing).coerceAtLeast(0.001f)), c.accent, leadFuse)
                drawCircle(
                    Brush.radialGradient(listOf(tint.copy(alpha = 0.22f + 0.08f * breathe), Color.Transparent), centre, 150.dp.toPx()),
                    150.dp.toPx(), centre,
                )
            }
            if (hasRomm) {
                ChoiceCard(
                    name = "RomM", tag = if (romm) "On" else "Your server", tint = rommTint, mark = FuseIcons.Server,
                    lines = listOf(FuseIcons.Save to "Saves, by game", FuseIcons.LibraryBig to "Games from RomM", FuseIcons.Server to "Your RomM"),
                    on = romm, filled = false,
                    modifier = Modifier.card(leadRomm, xRomm, (-10).dp, drift2, 0f),
                )
            }
            ChoiceCard(
                name = "Syncthing", tag = if (syncthing) "On" else "Bring your own", tint = teal, mark = FuseIcons.FolderSync,
                lines = listOf(FuseIcons.FolderOpen to "Save folders", FuseIcons.Users to "One save for all", FuseIcons.Link2 to "Your Syncthing"),
                on = syncthing, filled = false,
                modifier = Modifier.card(leadThing, xThing, 8.dp, drift2, 4f),
            )
            if (hasFuseSync) {
                ChoiceCard(
                    name = "Fuse One", tag = if (fuseSync) "On" else "Recommended", tint = c.accent, mark = FuseIcons.RefreshCcw,
                    lines = listOf(FuseIcons.Save to "Saves, by game", FuseIcons.Clock to "Play time", FuseIcons.Users to "Saves per person"),
                    on = fuseSync, filled = true,
                    modifier = Modifier.card(leadFuse, xFuse, 0.dp, drift, -3f),
                )
            }
        }
        Spacer(Modifier.height(Space.m))
        FText(
            when {
                fuseSync -> "Fuse One is on"
                romm && hasRomm -> "RomM is on"
                syncthing -> "Syncthing is on"
                else -> "One moves your saves"
            },
            Fuse.type.bodyStrong, maxLines = 1,
        )
    }
}

/** One of the ways on the stage: its mark, name and tag, then what it keeps in step, one line each. */
@Composable
internal fun ChoiceCard(
    name: String,
    tag: String,
    tint: Color,
    mark: ImageVector,
    lines: List<Pair<ImageVector, String>>,
    on: Boolean,
    filled: Boolean,
    modifier: Modifier,
    width: Dp = 176.dp,
) {
    val c = Fuse.colors
    Column(
        modifier.width(width).clip(RoundedCornerShape(22.dp)).background(c.surfaceOverlay)
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
