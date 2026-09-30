package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import kotlinx.coroutines.delay

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

@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val motion = Fuse.motion
    Box(modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.BottomCenter) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s), horizontalAlignment = Alignment.CenterHorizontally) {
            for (toast in state.items) {
                LaunchedEffect(toast.id) {
                    delay(toast.durationMs)
                    state.dismiss(toast.id)
                }
                AnimatedVisibility(
                    visible = true,
                    enter = slideInVertically(motion.tween(Durations.BASE, Easings.Enter)) { it / 2 } + fadeIn(motion.fade(Durations.FAST)),
                    exit = slideOutVertically(motion.tween(Durations.FAST, Easings.Exit)) { it / 2 } + fadeOut(motion.fade(Durations.FAST)),
                ) {
                    Panel(shape = PillShape, raised = true, modifier = Modifier.widthIn(max = 560.dp)) {
                        Row(
                            Modifier.padding(horizontal = Space.l, vertical = Space.m),
                            horizontalArrangement = Arrangement.spacedBy(Space.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val (icon, tint) = when (toast.kind) {
                                ToastKind.INFO -> (toast.icon ?: FuseIcons.Info) to c.text
                                ToastKind.SUCCESS -> (toast.icon ?: FuseIcons.CircleCheck) to c.success
                                ToastKind.WARNING -> (toast.icon ?: FuseIcons.Warning) to c.warning
                                ToastKind.ERROR -> (toast.icon ?: FuseIcons.Alert) to c.danger
                            }
                            FuseIcon(icon, tint = tint)
                            FText(toast.message, Fuse.type.bodyStrong, maxLines = 3)
                        }
                    }
                }
            }
        }
    }
}
