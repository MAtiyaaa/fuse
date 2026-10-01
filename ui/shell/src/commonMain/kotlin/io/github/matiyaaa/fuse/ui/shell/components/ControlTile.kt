package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Toggle
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/**
 * A square-ish control: icon at the top, label and state at the bottom, used by the quick menu, the
 * bottom screen and Cartridge. It keeps its own colours while focused (lit in the accent while
 * [active]), so on or off always reads; focus adds an outline and a small lift instead of a white
 * fill. [toggle] adds a switch in the corner and makes the state "On" or "Off". The caller sizes it.
 * [compact] fits a short tile (about 80 dp tall). An [unavailable] tile is dimmed but still answers
 * a press, so it can say why.
 */
@Composable
fun ControlTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    detail: String? = null,
    toggle: Boolean = false,
    compact: Boolean = false,
    unavailable: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val bg by animateColorAsState(
        when {
            active -> c.accentSoft
            selected -> c.text.copy(alpha = 0.14f)
            else -> c.text.copy(alpha = 0.07f)
        },
        motion.tween(Durations.FAST),
        label = "tile",
    )
    val lift by animateFloatAsState(if (selected) 1f else 0f, motion.focusSpring(), label = "tile lift")
    val fg = if (active) c.accent else c.text
    val corner = Fuse.geometry.panel
    Box(
        modifier
            .graphicsLayer {
                val s = 1f + 0.04f * lift * (if (motion.reduced) 0f else 1f)
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                if (lift > 0.01f) {
                    val inset = 2.dp.toPx()
                    drawRoundRect(
                        c.focus.copy(alpha = lift),
                        topLeft = Offset(-inset, -inset),
                        size = androidx.compose.ui.geometry.Size(size.width + inset * 2, size.height + inset * 2),
                        cornerRadius = CornerRadius(corner.toPx() + inset),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
            .clip(RoundedCornerShape(corner))
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(if (compact) 10.dp else Space.m),
    ) {
        val dim = Modifier.alpha(if (unavailable) 0.45f else 1f)
        FuseIcon(icon, tint = fg, size = if (compact) 18.dp else Size.iconM, modifier = dim.align(Alignment.TopStart))
        if (toggle) {
            // A switch in the corner, the same as in Settings.
            Toggle(active, Modifier.align(Alignment.TopEnd).graphicsLayer { scaleX = 0.8f; scaleY = 0.8f; transformOrigin = TransformOrigin(1f, 0f) })
        }
        Column(dim.align(Alignment.BottomStart)) {
            FText(label, Fuse.type.label, color = fg, maxLines = 1)
            val state = if (toggle) (if (active) "On" else "Off") else detail
            state?.let { FText(it, Fuse.type.caption, color = fg.copy(alpha = 0.7f), maxLines = 1) }
        }
    }
}
