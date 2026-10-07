package io.github.matiyaaa.fuse.ui.fuseline.v31

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrain
import kotlinx.coroutines.launch

/** How the room a [Swap] takes changes between its contents' sizes; [clip] trims what overflows meanwhile. */
@Immutable
class SizeTransform(val clip: Boolean = true, val animationSpec: Motion = Spring(stiffness = Spring.StiffnessMediumLow, threshold = 1f))

/** One change in a [Swap]: how the new content arrives, how the old one leaves, and how the room changes. */
@Immutable
class SwapTransform(val enter: Enter, val exit: Exit, val sizeTransform: SizeTransform? = SizeTransform()) {
    infix fun using(sizeTransform: SizeTransform?): SwapTransform = SwapTransform(enter, exit, sizeTransform)
}

/** The new content's [Enter] together with the old content's [Exit]. */
infix fun Enter.togetherWith(exit: Exit): SwapTransform = SwapTransform(this, exit)

/** Which change a [Swap]'s transition is for. */
interface SwapScope<S> {
    val initialState: S
    val targetState: S
}

/** What [Swap] content runs in. */
interface SwapContentScope

private object SwapContentScopeImpl : SwapContentScope

private class Change<S>(override val initialState: S, override val targetState: S) : SwapScope<S>

/** A Swap's content arrives a beat after the old leaves, growing very slightly into place. */
private fun <S> SwapScope<S>.defaultSwap(): SwapTransform =
    (fadeIn(tween(220, delayMillis = 90)) + scaleIn(tween(220, delayMillis = 90), initialScale = 0.92f)) togetherWith fadeOut(tween(90))

@Stable
private class SwapEntry<S>(state: S, val key: Any?, val enter: Enter, exit: Exit, val visibility: AppearState) {
    var state by mutableStateOf(state)
    var exit by mutableStateOf(exit)
}

/**
 * Shows [content] for [targetState], and when the state changes (by [contentKey]) moves the old
 * content out and the new one in as [transitionSpec] says, both shown while they cross. The room it
 * takes follows the new content's size under the transform's [SizeTransform] (or jumps to it with
 * none). Newer content draws above older.
 */
@Composable
fun <S> Swap(
    targetState: S,
    modifier: Modifier = Modifier,
    transitionSpec: SwapScope<S>.() -> SwapTransform = { defaultSwap() },
    contentAlignment: Alignment = Alignment.TopStart,
    label: String = "Swap",
    contentKey: (targetState: S) -> Any? = { it },
    content: @Composable SwapContentScope.(targetState: S) -> Unit,
) {
    val entries = remember { mutableStateListOf(SwapEntry(targetState, contentKey(targetState), Enter.None, Exit.None, AppearState(true))) }
    var sizing by remember { mutableStateOf<SizeTransform?>(SizeTransform()) }
    val newest = entries.last()
    val key = contentKey(targetState)
    if (key != newest.key) {
        val change = Change(newest.state, targetState).transitionSpec()
        sizing = change.sizeTransform
        for (e in entries) {
            e.exit = change.exit
            e.visibility.targetState = false
        }
        // Content on its way out that is wanted again turns round instead of a second copy arriving.
        val leaving = entries.firstOrNull { it.key == key }
        if (leaving != null) {
            entries.remove(leaving)
            leaving.state = targetState
            leaving.visibility.targetState = true
            entries.add(leaving)
        } else {
            entries.add(SwapEntry(targetState, key, change.enter, change.exit, AppearState(false).apply { this.targetState = true }))
        }
    } else if (newest.state != targetState) {
        // The same content under a new state: it updates in place.
        newest.state = targetState
    }

    // The room follows the newest content's size.
    val room = remember { FuselineValue(IntSize.Zero, IntSizeConverter) }
    // Where the room was last sent (read and written while measuring, so not state).
    val target = remember { arrayOfNulls<IntSize>(1) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Clipped only while a change is under way (two contents crossing, or the room still moving):
    // at rest, content that reaches past the room (a lifted card's edge, a focus ring) is whole.
    val changing = entries.size > 1 || room.isRunning
    Layout(
        modifier = if (sizing?.clip == true && changing) modifier.clipToBounds() else modifier,
        content = {
            for (e in entries) key(e.key) {
                Appear(e.visibility, enter = e.enter, exit = e.exit, label = label) {
                    SwapContentScopeImpl.content(e.state)
                }
                // Gone once it has left.
                LaunchedEffect(e.visibility.isIdle, e.visibility.currentState) {
                    if (e.visibility.isIdle && !e.visibility.currentState && e !== entries.last()) entries.remove(e)
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val front = placeables.lastOrNull()
        val want = IntSize(front?.width ?: 0, front?.height ?: 0)
        val transform = sizing
        val size = when {
            transform == null -> want
            target[0] == null -> want.also { t -> target[0] = t; scope.launch { room.snapTo(t) } }
            target[0] != want -> {
                target[0] = want
                scope.launch { room.animateTo(want, transform.animationSpec) }
                room.value
            }
            room.isRunning -> room.value
            else -> want
        }
        val shown = constraints.constrain(size)
        layout(shown.width, shown.height) {
            for (p in placeables) {
                p.place(contentAlignment.align(IntSize(p.width, p.height), shown, LayoutDirection.Ltr))
            }
        }
    }
}

/** [targetState]'s content, cross-fading from the old one under [animationSpec]. */
@Composable
fun <S> Crossfade(
    targetState: S,
    modifier: Modifier = Modifier,
    animationSpec: Motion = tween(),
    label: String = "Crossfade",
    content: @Composable (S) -> Unit,
) {
    Swap(
        targetState,
        modifier,
        transitionSpec = { (fadeIn(animationSpec) togetherWith fadeOut(animationSpec)) using null },
        label = label,
    ) { content(it) }
}
