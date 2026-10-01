package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.CornerFamily
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import kotlinx.coroutines.flow.first

enum class ToastKind { INFO, SUCCESS, WARNING, ERROR }

@Immutable
data class Toast(
    val id: Long,
    val message: String,
    val kind: ToastKind = ToastKind.INFO,
    val icon: ImageVector? = null,
    val durationMs: Long = 3200,
)

/** Queue of transient messages ("3 new games", "Couldn't open DuckStation"). */
@Stable
class ToastState {
    internal val items = mutableStateListOf<Toast>()
    private var next = 1L

    fun show(message: String, kind: ToastKind = ToastKind.INFO, icon: ImageVector? = null, durationMs: Long = 3200) {
        items.removeAll { it.message == message }
        items.add(Toast(next++, message, kind, icon, durationMs))
        if (items.size > 3) items.removeAt(0)
    }

    internal fun dismiss(id: Long) {
        items.removeAll { it.id == id }
    }
}

/**
 * Shows [state]'s toasts at the bottom centre, above the hint line. A toast springs up from the
 * bottom edge, says what kind it is with an icon in its colour, and runs a thin line down to show
 * when it will go (the line waits while the pointer rests on it). Newer toasts sit nearest the edge;
 * older ones step back a little. A tap dismisses one early. Under Reduced motion they only fade.
 */
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    // What is on screen: the queue plus toasts still playing their way out.
    val shown = remember { mutableStateListOf<Toast>() }
    val visibility = remember { HashMap<Long, MutableTransitionState<Boolean>>() }
    val queue = state.items.toList()
    LaunchedEffect(queue) {
        for (t in queue) {
            if (shown.none { it.id == t.id }) {
                visibility[t.id] = MutableTransitionState(false).apply { targetState = true }
                shown.add(t)
            }
        }
        for (t in shown) if (queue.none { it.id == t.id }) visibility[t.id]?.targetState = false
    }
    Box(modifier.fillMaxSize().padding(bottom = Space.x4), contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val staying = shown.filter { visibility[it.id]?.targetState != false }
            for (toast in shown) {
                val vis = visibility[toast.id]
                if (vis != null) {
                    key(toast.id) {
                        val index = staying.indexOfFirst { it.id == toast.id }
                        ToastSlot(
                            toast,
                            vis,
                            depth = if (index >= 0) (staying.size - 1 - index).coerceIn(0, 2) else null,
                            onGone = {
                                shown.removeAll { it.id == toast.id }
                                visibility.remove(toast.id)
                            },
                            onDismiss = { state.dismiss(toast.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One toast's place in the stack: it springs in from the bottom edge, steps back as newer ones
 * arrive ([depth], null while it leaves) and calls [onGone] once it has played its way out.
 */
@Composable
private fun ToastSlot(toast: Toast, vis: MutableTransitionState<Boolean>, depth: Int?, onGone: () -> Unit, onDismiss: () -> Unit) {
    val motion = Fuse.motion
    LaunchedEffect(vis) {
        snapshotFlow { vis.isIdle && !vis.currentState && !vis.targetState }.first { it }
        onGone()
    }
    // A leaving toast keeps the place it had.
    val last = remember { IntArray(1) }
    if (depth != null) last[0] = depth
    val stepBack by animateFloatAsState(last[0].toFloat(), motion.tween(Durations.BASE), label = "toast depth")
    AnimatedVisibility(
        visibleState = vis,
        enter = if (motion.reduced) {
            fadeIn(motion.fade(Durations.FAST))
        } else {
            expandVertically(spring(dampingRatio = 1f, stiffness = 700f), expandFrom = Alignment.Top) +
                slideInVertically(spring(dampingRatio = 0.72f, stiffness = 420f)) { it * 2 } +
                scaleIn(spring(dampingRatio = 0.72f, stiffness = 420f), initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeIn(motion.tween(Durations.FAST, Easings.Fade))
        },
        exit = if (motion.reduced) {
            fadeOut(motion.fade(Durations.INSTANT))
        } else {
            fadeOut(motion.tween(Durations.FAST, Easings.Exit)) +
                scaleOut(motion.tween(Durations.FAST, Easings.Exit), targetScale = 0.94f) +
                shrinkVertically(motion.tween(Durations.BASE, Easings.Standard), shrinkTowards = Alignment.Top)
        },
    ) {
        Box(
            Modifier
                .padding(top = Space.s)
                .graphicsLayer {
                    val s = if (motion.reduced) 1f else 1f - 0.035f * stepBack
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.12f * stepBack
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
        ) {
            ToastCard(toast, onDismiss = onDismiss)
        }
    }
}

/** One toast: the kind's icon in a soft disc of its colour, the message, and the time left. */
@Composable
private fun ToastCard(toast: Toast, onDismiss: () -> Unit) {
    val c = Fuse.colors
    val (icon, tint) = when (toast.kind) {
        ToastKind.INFO -> (toast.icon ?: FuseIcons.Info) to c.text
        ToastKind.SUCCESS -> (toast.icon ?: FuseIcons.CircleCheck) to c.success
        ToastKind.WARNING -> (toast.icon ?: FuseIcons.Warning) to c.warning
        ToastKind.ERROR -> (toast.icon ?: FuseIcons.Alert) to c.danger
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The time left, 1 to 0. It waits while the pointer rests on the toast, so it can be read.
    val left = remember(toast.id) { Animatable(1f) }
    LaunchedEffect(toast.id, hovered) {
        if (hovered) return@LaunchedEffect
        val remaining = (toast.durationMs * left.value).toInt()
        left.animateTo(0f, tween(remaining, easing = LinearEasing))
        onDismiss()
    }
    val shape = RoundedCornerShape(if (Fuse.geometry.family == CornerFamily.SHARP) Fuse.geometry.panel else Radius.l)
    val line = if (toast.kind == ToastKind.INFO) c.textMuted else tint
    Panel(
        shape = shape,
        raised = true,
        modifier = Modifier
            .widthIn(min = 240.dp, max = 640.dp)
            .clickable(interaction, null, onClick = onDismiss)
            .drawWithContent {
                drawContent()
                // The time left: a hairline along the bottom edge that runs out towards the start.
                val h = 2.dp.toPx()
                val inset = 14.dp.toPx()
                val full = size.width - inset * 2
                if (full > 0f && left.value > 0f) {
                    drawRoundRect(
                        line.copy(alpha = 0.16f),
                        topLeft = Offset(inset, size.height - h - 3.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(full, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
                    )
                    drawRoundRect(
                        line.copy(alpha = 0.7f),
                        topLeft = Offset(inset, size.height - h - 3.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(full * left.value, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
                    )
                }
            },
    ) {
        Row(
            Modifier.padding(start = Space.m, end = Space.l, top = Space.m, bottom = Space.m + Space.xxs),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(TOAST_DISC).background(tint.copy(alpha = if (toast.kind == ToastKind.INFO) 0.1f else 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                FuseIcon(icon, size = Size.iconS, tint = tint)
            }
            FText(toast.message, Fuse.type.bodyStrong, maxLines = 3, modifier = Modifier.widthIn(max = 560.dp))
        }
    }
}

private val TOAST_DISC = 30.dp
