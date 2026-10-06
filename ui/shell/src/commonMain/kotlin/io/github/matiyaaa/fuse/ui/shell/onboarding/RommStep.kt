package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.RepeatMode
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.home.CARTRIDGE_TINT
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor

/**
 * The stage for "Do you use RomM?": three cards fanned out. Fuse RomM in the middle, lit in the
 * accent; Cartridge to one side in its own orange; "Neither" to the other, quiet. Before an answer
 * the middle card leads; after one, the chosen card comes forward with a check and the others step
 * back. Each card says in three short lines what that choice means, so the buttons below need no
 * more explaining. Reduced Motion keeps the cards still.
 */
@Composable
internal fun RommChoiceStage(chosen: String, connected: Boolean, cartridgeReady: Boolean, hasRomm: Boolean, hasCartridge: Boolean) {
    val c = Fuse.colors
    val still = Fuse.motion.reduced
    val clock = rememberLoopClock("romm choice stage")
    val drift by clock.animateFloat(-1f, 1f, infiniteRepeatable(tween(4800), RepeatMode.Reverse), "drift")
    val breathe by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2800), RepeatMode.Reverse), "breathe")
    val cartridgeTint = CARTRIDGE_TINT.toColor()
    val quiet = c.textMuted
    // Which card leads: 0 Cartridge, 1 Fuse RomM, 2 Neither. Animated so the glow and cards glide.
    val leadTarget = when (chosen) {
        "CARTRIDGE" -> 0f
        "NONE" -> 2f
        else -> if (hasRomm) 1f else if (hasCartridge) 0f else 2f
    }
    val lead by fuselineFloat(leadTarget, spring(dampingRatio = 0.8f, stiffness = 240f), label = "rommLead")
    fun weight(slot: Int) = (1f - kotlin.math.abs(lead - slot)).coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(380.dp, 240.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val tint = when {
                    lead <= 1f -> lerp(cartridgeTint, c.accent, lead)
                    else -> lerp(c.accent, quiet, lead - 1f)
                }
                val centre = Offset(size.width * (0.24f + 0.26f * lead), size.height / 2f)
                drawCircle(
                    Brush.radialGradient(listOf(tint.copy(alpha = 0.20f + 0.08f * breathe), Color.Transparent), centre, 150.dp.toPx()),
                    150.dp.toPx(), centre,
                )
            }
            val cards = buildList {
                if (hasCartridge) add(0)
                if (hasRomm) add(1)
                add(2)
            }
            for (slot in cards) {
                val w = weight(slot)
                val x = when (slot) { 0 -> if (hasRomm) -116 else -64; 1 -> 0; else -> if (hasRomm || hasCartridge) 116 else 0 }
                val tilt = when (slot) { 0 -> -4f; 1 -> 0f; else -> 4f }
                val m = Modifier.offset(x.dp, if (slot == 1) 0.dp else 10.dp).zIndex(w).graphicsLayer {
                    val s = 0.84f + 0.16f * w
                    scaleX = s; scaleY = s
                    alpha = 0.55f + 0.45f * w
                    translationY = if (still) 0f else drift * (if (slot == 1) 4f else -3f) * w.coerceAtLeast(0.4f) * density
                    rotationZ = tilt * (1f - w)
                    shadowElevation = (6f + 18f * w).dp.toPx()
                    shape = RoundedCornerShape(22.dp)
                }
                when (slot) {
                    0 -> ChoiceCard(
                        name = "Cartridge", tag = if (chosen == "CARTRIDGE") if (cartridgeReady) "Ready" else "Chosen" else "Companion app",
                        tint = cartridgeTint, mark = FuseMarks.Cartridge,
                        lines = listOf(FuseIcons.Download to "Downloads for Fuse", FuseIcons.Server to "Your RomM server", FuseIcons.Link2 to "A separate app"),
                        on = chosen == "CARTRIDGE" && cartridgeReady, filled = false, modifier = m, width = 160.dp,
                    )
                    1 -> ChoiceCard(
                        name = "Fuse RomM", tag = when { connected -> "Connected"; chosen == "FUSE" -> "Chosen"; else -> "Recommended" },
                        tint = c.accent, mark = FuseIcons.LibraryBig,
                        lines = listOf(FuseIcons.Server to "Straight to RomM", FuseIcons.Download to "Downloads in Fuse", FuseIcons.Upload to "Uploads too"),
                        on = connected, filled = true, modifier = m, width = 160.dp,
                    )
                    else -> ChoiceCard(
                        name = "Neither", tag = if (chosen == "NONE") "Chosen" else "This device only",
                        tint = quiet, mark = FuseIcons.HardDrive,
                        lines = listOf(FuseIcons.FolderOpen to "Your own folders", FuseIcons.Gamepad to "Plays as it is", FuseIcons.Clock to "RomM later, if ever"),
                        on = chosen == "NONE", filled = false, modifier = m, width = 160.dp,
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.m))
        FText(
            when (chosen) {
                "FUSE" -> if (connected) "Fuse RomM is connected" else "Fuse RomM, built in"
                "CARTRIDGE" -> if (cartridgeReady) "Cartridge is ready" else "Cartridge, alongside Fuse"
                "NONE" -> "Just this device"
                else -> "Three ways to bring in your games"
            },
            Fuse.type.bodyStrong, maxLines = 1,
        )
    }
}
