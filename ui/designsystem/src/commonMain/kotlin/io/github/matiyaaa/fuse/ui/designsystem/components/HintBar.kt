package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyphDefaults
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.AppearState
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.SizeTransform
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.expandHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.shrinkHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.fuseline.tween
import kotlinx.coroutines.flow.first

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
        val seen = HashMap<HintButton, Int>()
        for (h in hints) {
            // The same button may appear twice; each appearance is its own hint.
            val nth = seen[h.button] ?: 0
            seen[h.button] = nth + 1
            val existing = shown.firstOrNull { it.button == h.button && it.nth == nth }
            if (existing != null) {
                existing.label = h.label
                existing.visible.targetState = true
                next.add(existing)
            } else {
                next.add(HintEntry(h.button, nth, h.label, AppearState(false).apply { targetState = true }))
            }
        }
        // Leaving hints keep roughly the place they had while they fold away.
        shown.forEachIndexed { i, e ->
            if (next.none { it === e }) {
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
            key(entry.button, entry.nth) {
                HintItem(entry, flash = flash, onGone = { shown.remove(entry) })
            }
        }
    }
}

/** One hint on the line, with its own way in and out. */
@Stable
private class HintEntry(val button: HintButton, val nth: Int, label: String, val visible: AppearState) {
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
    val pulse = remember { FuselineValue(0f) }
    val lit by remember { derivedStateOf { pulse.value > 0.5f } }
    val count = if (entry.nth == 0) flash?.pulses?.get(entry.button) ?: 0 else 0
    LaunchedEffect(count) {
        if (count == 0) return@LaunchedEffect
        pulse.snapTo(1f)
        pulse.animateTo(0f, tween(if (motion.reduced) Durations.FAST else Durations.SLOW, easing = Curves.Standard))
    }
    Appear(
        visibleState = entry.visible,
        // The room opens and closes without clipping: a clipped hint shows half a glyph while it
        // moves (a cut disc reads as a rendering fault). Unclipped, the hint is drawn whole beside
        // the room it is opening, so it only fades in once most of that room is there, and it
        // fades out at once on the way out, never sitting over its neighbour.
        enter = if (motion.reduced) {
            fadeIn(motion.fade(Durations.FAST))
        } else {
            expandHorizontally(motion.tween(Durations.BASE, Curves.Standard), expandFrom = Alignment.End, clip = false) +
                fadeIn(tween(motion.ms(Durations.FAST), delayMillis = motion.ms(ENTER_FADE_DELAY), easing = Curves.Fade))
        },
        exit = if (motion.reduced) {
            fadeOut(motion.fade(Durations.INSTANT))
        } else {
            shrinkHorizontally(motion.tween(Durations.BASE, Curves.Standard), shrinkTowards = Alignment.End, clip = false) +
                fadeOut(motion.tween(Durations.INSTANT, Curves.Standard))
        },
    ) {
        // A hint is also a button: tapping or clicking it does what pressing its button does.
        val router = io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter.current
        val action = actionOf(entry.button)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Every hint brings its own gap, so nothing jumps when the first one folds away (the
            // line is right aligned; the first gap is empty room on its left).
            Spacer(Modifier.width(HINT_GAP))
            Row(
                Modifier
                    .clip(io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape)
                    .then(
                        if (action == null) Modifier
                        else Modifier.fuseClickable(
                            shape = io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape,
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClickLabel = entry.label,
                        ) { router.dispatch(action, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.TOUCH) },
                    )
                    .padding(horizontal = Space.xs, vertical = Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Box(
                Modifier.graphicsLayer {
                    val p = if (motion.reduced) 0f else pulse.value
                    val s = 1f - 0.14f * p
                    scaleX = s
                    scaleY = s
                    // The flash brightens the glyph through its layer, so the glyph itself is not
                    // rebuilt on every frame of it.
                    alpha = 0.9f + 0.1f * pulse.value
                },
                contentAlignment = Alignment.Center,
            ) {
                ButtonGlyph(
                    entry.button,
                    size = GLYPH,
                    color = c.text,
                    emphasized = entry.button == HintButton.CONFIRM || lit,
                )
            }
            Spacer(Modifier.width(Space.s))
            Swap(
                targetState = entry.label,
                transitionSpec = {
                    (fadeIn(motion.tween(Durations.FAST, Curves.Fade)) togetherWith fadeOut(motion.tween(Durations.INSTANT, Curves.Fade)))
                        .using(SizeTransform(clip = false, animationSpec = motion.tween(Durations.BASE, Curves.Standard)))
                },
                contentAlignment = Alignment.CenterStart,
                label = "hint label",
            ) { label ->
                FText(label, Fuse.type.label, color = lerpColor(c.textMuted, c.text, pulse.value), maxLines = 1)
            }
            }
        }
    }
}

/** What pressing a hint's button does, for a tap on the hint; null for hints that only describe (the D-pad, sticks, holds). */
private fun actionOf(button: HintButton): io.github.matiyaaa.fuse.model.NavAction? = when (button) {
    HintButton.CONFIRM -> io.github.matiyaaa.fuse.model.NavAction.SELECT
    HintButton.BACK -> io.github.matiyaaa.fuse.model.NavAction.BACK
    HintButton.OPTIONS -> io.github.matiyaaa.fuse.model.NavAction.CONTEXT
    HintButton.SEARCH -> io.github.matiyaaa.fuse.model.NavAction.SEARCH
    HintButton.MENU -> io.github.matiyaaa.fuse.model.NavAction.QUICK_MENU
    HintButton.PREV -> io.github.matiyaaa.fuse.model.NavAction.PREVIOUS_SECTION
    HintButton.NEXT -> io.github.matiyaaa.fuse.model.NavAction.NEXT_SECTION
    HintButton.PAGE_PREV -> io.github.matiyaaa.fuse.model.NavAction.PAGE_UP
    HintButton.PAGE_NEXT -> io.github.matiyaaa.fuse.model.NavAction.PAGE_DOWN
    HintButton.HOLD_CONFIRM -> io.github.matiyaaa.fuse.model.NavAction.REORDER
    HintButton.HOLD_OPTIONS -> io.github.matiyaaa.fuse.model.NavAction.CONTEXT_HOLD
    else -> null
}

/** Glyph size (the one every glyph beside label text uses). */
private val GLYPH = ButtonGlyphDefaults.Size

/** The space between one hint and the next: wider than glyph to label, so pairs read as pairs. */
private val HINT_GAP = Space.l + Space.xs

/** How far into its opening a new hint starts to fade in (base ms; about a third of the way). */
private const val ENTER_FADE_DELAY = 70
