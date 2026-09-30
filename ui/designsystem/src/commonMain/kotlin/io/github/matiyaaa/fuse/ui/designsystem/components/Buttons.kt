package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

enum class ButtonKind { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * A controller- and touch-friendly button. [selected] is Fuse's selection (controller focus): the
 * button brightens, lifts slightly and gets an outline so it reads without colour.
 */
@Composable
fun FuseButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: ButtonKind = ButtonKind.SECONDARY,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val (bg, fg) = when (kind) {
        ButtonKind.PRIMARY -> c.accent to c.onAccent
        ButtonKind.SECONDARY -> (if (selected) c.text else c.surfaceRaised) to (if (selected) c.ink else c.text)
        ButtonKind.GHOST -> (if (selected) c.text.copy(alpha = 0.12f) else Color.Transparent) to c.text
        ButtonKind.DANGER -> (if (selected) c.danger else c.danger.copy(alpha = 0.16f)) to (if (selected) c.ink else c.danger)
    }
    val bgAnim by animateColorAsState(bg, motion.tween(Durations.FAST), label = "bg")
    val fgAnim by animateColorAsState(fg, motion.tween(Durations.FAST), label = "fg")
    val lift by animateFloatAsState(if (selected) 1f else 0f, motion.focusSpring(), label = "lift")
    val ring = selected && (kind == ButtonKind.PRIMARY || Fuse.look.highContrastFocus)
    Row(
        modifier
            .graphicsLayer {
                val s = 1f + 0.04f * lift * (if (motion.reduced) 0f else 1f)
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.4f
            }
            .then(if (ring) Modifier.border(2.dp, c.focus, PillShape).padding(3.dp) else Modifier)
            .clip(PillShape)
            .background(bgAnim)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .height(height)
            .defaultMinSize(minWidth = height * 2)
            .padding(horizontal = Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) FuseIcon(icon, tint = fgAnim)
        FText(label, Fuse.type.bodyStrong, color = fgAnim, maxLines = 1)
    }
}

/** Compact round icon button (touch targets stay 48dp). */
@Composable
fun IconButton(
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = Fuse.colors.text,
) {
    val c = Fuse.colors
    val bg by animateColorAsState(
        if (selected) c.text else c.text.copy(alpha = 0.08f),
        Fuse.motion.tween(Durations.FAST),
        label = "ib",
    )
    Row(
        modifier
            .clip(PillShape)
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .height(size)
            .defaultMinSize(minWidth = size),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, tint = if (selected) c.ink else tint)
    }
}
