package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseColors
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

enum class ButtonKind { PRIMARY, SECONDARY, GHOST, DANGER }

/** Fill and content colours for a button kind, at rest and selected. */
private fun FuseColors.buttonColors(kind: ButtonKind, selected: Boolean): Pair<Color, Color> = when (kind) {
    ButtonKind.PRIMARY -> accent to onAccent
    // Selection inverts quiet buttons: the strongest thing on the surface is the one you're on.
    ButtonKind.SECONDARY -> (if (selected) text else surfaceRaised) to (if (selected) ink else text)
    ButtonKind.GHOST -> (if (selected) text else text.copy(alpha = 0f)) to (if (selected) ink else text)
    ButtonKind.DANGER -> (if (selected) danger else danger.copy(alpha = 0.14f)) to (if (selected) ink else danger)
}

/**
 * A controller- and touch-friendly button. [selected] is Fuse's selection (controller focus): the
 * button lifts, quiet kinds (secondary, ghost) invert and coloured kinds (primary, danger) take a
 * focus ring just outside their edge, so the selected button is always the strongest thing around
 * it, in any theme and without relying on colour. Focusing never moves anything. High contrast
 * focus rings every selected button. Hover brightens it under a mouse; pressing sinks it a little.
 *
 * Buttons are pills, or keep the theme's corners in sharp themes. At the standard [height] (48 dp)
 * the label is `bodyStrong` with a 20 dp icon; below 44 dp they step down to `label` and 16 dp.
 * [trailingIcon] sits after the label (an arrow for "opens elsewhere", a chevron for "more").
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
    height: Dp = Size.touch,
    trailingIcon: ImageVector? = null,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val interaction = remember { MutableInteractionSource() }
    val press = rememberAtomPress(interaction, enabled)
    val (bg, fg) = c.buttonColors(kind, selected)
    val bgAnim by animateColorAsState(bg, motion.tween(Durations.FAST), label = "bg")
    val fgAnim by animateColorAsState(fg, motion.tween(Durations.FAST), label = "fg")
    val lift by animateFloatAsState(if (selected) 1f else 0f, motion.focusSpring(), label = "lift")
    val shape = controlShape()
    val ringShape = controlRingShape()
    val compact = height < 44.dp
    val iconSize = if (compact) Size.iconS else Size.iconM
    val pad = if (compact) Space.l else Space.xl
    val hover = c.hoverOverlay()
    val sink = c.pressOverlay()
    val focus = c.focus
    // Quiet buttons at rest lie flat; anything solid casts a soft shadow that deepens as it lifts.
    val solid = kind == ButtonKind.PRIMARY || kind == ButtonKind.SECONDARY || selected
    // Quiet kinds invert when selected, which reads without colour; the coloured kinds keep their
    // fill, so they take the ring. High contrast focus rings every selected button.
    val ringed = kind == ButtonKind.PRIMARY || kind == ButtonKind.DANGER || Fuse.look.highContrastFocus
    Row(
        modifier
            .graphicsLayer {
                val s = if (motion.reduced) 1f else 1f + 0.04f * lift - 0.03f * press.pressed
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.4f
                if (solid && enabled) {
                    shadowElevation = (1.5f + 6f * lift) * density
                    this.shape = shape
                }
            }
            .ringOutside({ if (ringed) lift else 0f }, focus, ringShape)
            .clip(shape)
            .background(bgAnim)
            .drawBehind {
                if (press.hovered > 0f) drawRect(hover, alpha = press.hovered)
                if (press.pressed > 0f) drawRect(sink, alpha = press.pressed)
            }
            .then(
                if (kind == ButtonKind.SECONDARY && !selected) {
                    Modifier.litEdge(shape, top = Color.White.copy(alpha = if (c.isDark) 0.12f else 0.6f), rest = c.hairline, reach = 16.dp)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .height(height)
            .defaultMinSize(minWidth = height * 2)
            // An icon reads as part of the label's weight, so its side sits a little tighter.
            .padding(start = if (icon != null) pad - Space.xs else pad, end = if (trailingIcon != null) pad - Space.xs else pad),
        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) FuseIcon(icon, size = iconSize, tint = fgAnim)
        FText(label, if (compact) Fuse.type.label else Fuse.type.bodyStrong, color = fgAnim, maxLines = 1)
        if (trailingIcon != null) FuseIcon(trailingIcon, size = iconSize, tint = fgAnim.copy(alpha = fgAnim.alpha * 0.8f))
    }
}

/**
 * A round icon button (touch targets stay 48 dp). Selected, it inverts and lifts like a quiet
 * [FuseButton] (with a ring under High contrast focus); hover and press behave the same.
 * [contentDescription] names it for screen readers, since there is no label.
 */
@Composable
fun IconButton(
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Size.touch,
    tint: Color = Fuse.colors.text,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val interaction = remember { MutableInteractionSource() }
    val press = rememberAtomPress(interaction, enabled)
    val bg by animateColorAsState(
        if (selected) c.text else c.quietFill(),
        motion.tween(Durations.FAST),
        label = "ib",
    )
    val fg by animateColorAsState(if (selected) c.ink else tint, motion.tween(Durations.FAST), label = "ibfg")
    val lift by animateFloatAsState(if (selected) 1f else 0f, motion.focusSpring(), label = "iblift")
    val highContrast = Fuse.look.highContrastFocus
    val shape = controlShape()
    val hover = c.hoverOverlay()
    val sink = c.pressOverlay()
    Box(
        modifier
            .graphicsLayer {
                val s = if (motion.reduced) 1f else 1f + 0.06f * lift - 0.05f * press.pressed
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.4f
            }
            .ringOutside({ if (highContrast) lift else 0f }, c.focus, controlRingShape())
            .clip(shape)
            .background(bg)
            .drawBehind {
                if (press.hovered > 0f) drawRect(hover, alpha = press.hovered)
                if (press.pressed > 0f) drawRect(sink, alpha = press.pressed)
            }
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .height(size)
            .defaultMinSize(minWidth = size),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = if (size >= 44.dp) Size.iconM else Size.iconS, tint = fg)
    }
}
