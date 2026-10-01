package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.lerp as lerpColor

@Immutable
data class Hint(val button: HintButton, val label: String)

/**
 * Lets whoever handles input flash a hint's glyph when its button is pressed, so the hint line
 * answers the hand: the glyph dips and brightens for a moment. Purely visual; it changes nothing.
 */
@Stable
class HintFlash {
    internal val pulses = mutableStateMapOf<HintButton, Int>()

    /** Flashes [button]'s glyph if the hint line shows it. */
    fun flash(button: HintButton) {
        pulses[button] = (pulses[button] ?: 0) + 1
    }
}

/** A [HintFlash] for one hint line. */
@Composable
fun rememberHintFlash(): HintFlash = remember { HintFlash() }

/**
 * The quiet line of button hints at the bottom right: a glyph and a label for each available action.
 * Hints that stay put while you move between items stay perfectly still; one whose label changes
 * crossfades in place, new ones open in and old ones fold away, so the line never jumps. With a
 * [flash], a glyph dips and brightens when its button is pressed. Under Reduced motion changes are
 * short fades.
 */
@Composable
fun HintBar(hints: List<Hint>, modifier: Modifier = Modifier, flash: HintFlash? = null) {
    // On screen: the current hints plus any still folding away, in a stable order.
    val shown = remember { mutableStateListOf<HintEntry>() }
    LaunchedEffect(hints) {
        val next = ArrayList<HintEntry>(hints.size + shown.size)
        for (h in hints) {
            val existing = shown.firstOrNull { it.button == h.button }
            if (existing != null) {
                existing.label = h.label
                existing.visible.targetState = true
                next.add(existing)
            } else {
                next.add(HintEntry(h.button, h.label, MutableTransitionState(false).apply { targetState = true }))
            }
        }
        // Leaving hints keep roughly the place they had while they fold away.
        shown.forEachIndexed { i, e ->
            if (hints.none { it.button == e.button }) {
                e.visible.targetState = false
                next.add(i.coerceAtMost(next.size), e)
            }
        }
        shown.clear()
        shown.addAll(next)
    }
    Row(
        modifier.height(Size.hintHeight),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (entry in shown) {
            key(entry.button) {
                HintItem(entry, flash = flash, onGone = { shown.remove(entry) })
            }
        }
    }
}

/** One hint on the line, with its own way in and out. */
@Stable
private class HintEntry(val button: HintButton, label: String, val visible: MutableTransitionState<Boolean>) {
    var label by mutableStateOf(label)
}

@Composable
private fun HintItem(entry: HintEntry, flash: HintFlash?, onGone: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    LaunchedEffect(entry) {
        snapshotFlow { entry.visible.isIdle && !entry.visible.currentState && !entry.visible.targetState }.first { it }
        onGone()
    }
    val pulse = remember { Animatable(0f) }
    val count = flash?.pulses?.get(entry.button) ?: 0
    LaunchedEffect(count) {
        if (count == 0) return@LaunchedEffect
        pulse.snapTo(1f)
        pulse.animateTo(0f, tween(if (motion.reduced) Durations.FAST else Durations.SLOW, easing = Easings.Standard))
    }
    AnimatedVisibility(
        visibleState = entry.visible,
        enter = if (motion.reduced) {
            fadeIn(motion.fade(Durations.FAST))
        } else {
            expandHorizontally(motion.tween(Durations.BASE, Easings.Standard), expandFrom = Alignment.End) +
                fadeIn(motion.tween(Durations.BASE, Easings.Fade))
        },
        exit = if (motion.reduced) {
            fadeOut(motion.fade(Durations.INSTANT))
        } else {
            shrinkHorizontally(motion.tween(Durations.BASE, Easings.Standard), shrinkTowards = Alignment.End) +
                fadeOut(motion.tween(Durations.FAST, Easings.Exit))
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Every hint brings its own gap, so nothing jumps when the first one folds away (the
            // line is right aligned; the first gap is empty room on its left).
            Spacer(Modifier.width(HINT_GAP))
            Box(
                Modifier.graphicsLayer {
                    val p = if (motion.reduced) 0f else pulse.value
                    val s = 1f - 0.14f * p
                    scaleX = s
                    scaleY = s
                },
                contentAlignment = Alignment.Center,
            ) {
                ButtonGlyph(
                    entry.button,
                    size = GLYPH,
                    color = c.text.copy(alpha = 0.9f + 0.1f * pulse.value),
                    emphasized = entry.button == HintButton.CONFIRM || pulse.value > 0.5f,
                )
            }
            Spacer(Modifier.width(Space.s))
            AnimatedContent(
                targetState = entry.label,
                transitionSpec = {
                    (fadeIn(motion.tween(Durations.FAST, Easings.Fade)) togetherWith fadeOut(motion.tween(Durations.INSTANT, Easings.Fade)))
                        .using(SizeTransform(clip = false) { _, _ -> motion.tween(Durations.BASE, Easings.Standard) })
                },
                contentAlignment = Alignment.CenterStart,
                label = "hint label",
            ) { label ->
                FText(label, Fuse.type.label, color = lerpColor(c.textMuted, c.text, pulse.value), maxLines = 1)
            }
        }
    }
}

/** Glyph size and the space between one hint and the next (wider than glyph to label, so pairs read as pairs). */
private val GLYPH = 22.dp
private val HINT_GAP = Space.l + Space.xs
