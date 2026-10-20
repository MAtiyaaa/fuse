package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.Canvas
import io.github.matiyaaa.fuse.ui.designsystem.effects.ditheredBackground
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
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
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
                    .ditheredBackground(Brush.linearGradient(listOf(c.accent, c.accent.copy(alpha = 0.72f)))),
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
                .ditheredBackground(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            FuseIcon(icon, size = 26.dp, tint = c.text)
        }
        FText(name, Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/**
 * The stage when there is a choice: the way this device's saves move, told as one card, with the
 * others under it as small pills. Fuse One leads, lit in the accent and marked as the one Fuse
 * recommends, until another way is on; then that one leads with a check. Nothing overlaps (the
 * cards once fanned out behind each other and cut each other's words off).
 */
@Composable
internal fun SyncChoiceStage(fuseSync: Boolean, syncthing: Boolean, hasFuseSync: Boolean, romm: Boolean = false, hasRomm: Boolean = false) {
    val c = Fuse.colors
    val teal = io.github.matiyaaa.fuse.ui.shell.sync.SYNCTHING_TINT
    val rommTint = Color(0xFF8B7CF6)
    val ways = buildList {
        if (hasFuseSync) add(Way(0, "Fuse One", if (fuseSync) "On" else "Recommended", c.accent, FuseIcons.RefreshCcw,
            listOf(FuseIcons.Save to "Saves, by game", FuseIcons.Clock to "Play time", FuseIcons.Users to "Saves per person"), fuseSync, filled = true))
        if (hasRomm) add(Way(1, "RomM", if (romm) "On" else "Your server", rommTint, FuseIcons.Server,
            listOf(FuseIcons.Save to "Saves, by game", FuseIcons.LibraryBig to "Games from RomM", FuseIcons.Server to "Your RomM"), romm, filled = false))
        add(Way(2, "Syncthing", if (syncthing) "On" else "Bring your own", teal, FuseIcons.FolderSync,
            listOf(FuseIcons.FolderOpen to "Save folders", FuseIcons.Users to "One save for all", FuseIcons.Link2 to "Your Syncthing"), syncthing, filled = false))
    }
    // Which leads: the one that is on, else Fuse One (Syncthing when it is the only one here).
    val leadKey = when {
        syncthing -> 2
        romm && hasRomm -> 1
        hasFuseSync -> 0
        else -> 2
    }
    LeadStage(
        ways, leadKey,
        when {
            fuseSync -> "Fuse One is on"
            romm && hasRomm -> "RomM is on"
            syncthing -> "Syncthing is on"
            else -> "One moves your saves"
        },
        label = "sync choice stage",
    )
}

/**
 * A choice told on a stage: the way that leads as one card, glowing in its colour and drifting a
 * little, the others under it as small pills, then one line. A new lead fades in over the old one;
 * nothing overlaps, so every word can be read. Reduced Motion keeps the card still.
 */
@Composable
internal fun LeadStage(ways: List<Way>, leadKey: Int, line: String, label: String) {
    val motion = Fuse.motion
    val still = motion.reduced
    val clock = rememberLoopClock(label)
    val drift by clock.animateFloat(-1f, 1f, infiniteRepeatable(tween(4600), RepeatMode.Reverse), "drift")
    val breathe by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2800), RepeatMode.Reverse), "breathe")
    val lead = ways.firstOrNull { it.key == leadKey } ?: ways.first()
    val tint by io.github.matiyaaa.fuse.ui.fuseline.fuselineColor(lead.tint, tween(motion.ms(io.github.matiyaaa.fuse.ui.fuseline.Durations.SLOW)), label = "leadTint")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(340.dp, 204.dp), contentAlignment = Alignment.Center) {
            // The glow behind the one that leads.
            Canvas(Modifier.matchParentSize()) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val r = 150.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.22f + 0.08f * breathe), Color.Transparent), centre, r), r, centre)
            }
            io.github.matiyaaa.fuse.ui.fuseline.Swap(
                targetState = lead.key,
                transitionSpec = {
                    io.github.matiyaaa.fuse.ui.fuseline.fadeIn(motion.enter(io.github.matiyaaa.fuse.ui.fuseline.Durations.BASE)) togetherWith
                        io.github.matiyaaa.fuse.ui.fuseline.fadeOut(motion.exit(io.github.matiyaaa.fuse.ui.fuseline.Durations.FAST))
                },
                contentAlignment = Alignment.Center,
                label = "lead way",
            ) { key ->
                val w = ways.firstOrNull { it.key == key } ?: lead
                ChoiceCard(
                    name = w.name, tag = w.tag, tint = w.tint, mark = w.mark, lines = w.lines, on = w.on, filled = w.filled,
                    width = 224.dp,
                    modifier = Modifier.graphicsLayer {
                        translationY = if (still) 0f else drift * 4.dp.toPx()
                        shadowElevation = 24.dp.toPx()
                        shape = RoundedCornerShape(22.dp)
                    },
                )
            }
        }
        // The others, small and side by side, so each can be read in full.
        val others = ways.filter { it.key != lead.key }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(Space.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (w in others) WayPill(w)
            }
        }
        Spacer(Modifier.height(Space.m))
        FText(line, Fuse.type.bodyStrong, maxLines = 1)
    }
}

/** A way to choose on a [LeadStage]. */
internal data class Way(
    val key: Int,
    val name: String,
    val tag: String,
    val tint: Color,
    val mark: ImageVector,
    val lines: List<Pair<ImageVector, String>>,
    val on: Boolean,
    val filled: Boolean,
)

/** One of the ways that doesn't lead: its mark in its own colour and its name, in a quiet pill. */
@Composable
internal fun WayPill(w: Way) {
    val c = Fuse.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.surfaceOverlay.copy(alpha = 0.85f))
            .padding(start = Space.xs, end = Space.m, top = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(w.tint.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) { FuseIcon(if (w.on) FuseIcons.Check else w.mark, size = 14.dp, tint = w.tint) }
        Spacer(Modifier.width(Space.s))
        FText(w.name, Fuse.type.label, maxLines = 1)
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
            .ditheredBackground(Brush.verticalGradient(listOf(tint.copy(alpha = 0.16f), Color.Transparent)))
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
