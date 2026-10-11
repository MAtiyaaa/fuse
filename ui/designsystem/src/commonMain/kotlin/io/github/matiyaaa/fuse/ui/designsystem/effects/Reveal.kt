package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import io.github.matiyaaa.fuse.ui.designsystem.theme.LocalRenderQuality
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.LocalFuselineMotion
import io.github.matiyaaa.fuse.ui.fuseline.Cue
import io.github.matiyaaa.fuse.ui.fuseline.rememberCue

/**
 * One entry into a screen (or a tab of one). Content revealed through it rises a few dp and fades
 * in, lightly staggered, but only while the entry is fresh: anything that appears later (a row
 * scrolled into view, a list that grows, an item recomposed after a focus move) is simply there.
 * So a reveal plays once per entry and never replays.
 *
 * Make one per screen with [rememberReveal] (keyed on what makes a new entry, such as the open
 * tab) and pass it to [Modifier.reveal], or provide it to a subtree with [RevealScope].
 */
@Stable
class Reveal internal constructor(internal val cue: Cue) {

    internal companion object {
        /**
         * Items that first appear later than this after the first one are shown at once: they came
         * from scrolling or data arriving, not from the screen opening.
         */
        const val WINDOW_MS = 700L
    }
}

/** A fresh [Reveal] for each new value of [keys]. */
@Composable
fun rememberReveal(vararg keys: Any?): Reveal {
    // Fuseline 4.1: every element of the entry plays from one cue, not a move of its own each.
    val cue = rememberCue(*keys)
    return remember(cue) { Reveal(cue) }
}

/** The entry [Modifier.reveal] without an explicit [Reveal] belongs to; none outside a [RevealScope]. */
val LocalReveal = staticCompositionLocalOf<Reveal?> { null }

/**
 * Provides a fresh entry to [content] (a new one whenever [keys] change), so components deep inside
 * can use [Modifier.reveal] with just an index.
 */
@Composable
fun RevealScope(vararg keys: Any?, content: @Composable () -> Unit) {
    val reveal = rememberReveal(*keys)
    CompositionLocalProvider(LocalReveal provides reveal, content = content)
}

/**
 * Reveals this element as its screen opens: it fades in while rising [io.github.matiyaaa.fuse.ui.fuseline.FuselineMotion.revealRise]
 * (about 10 dp) with [Curves.Enter], starting [io.github.matiyaaa.fuse.ui.fuseline.FuselineMotion.stagger]
 * after the first item (about 25 ms per [index], at most eight items deep).
 *
 * Use it on the few large pieces of a screen in reading order (title, then sections, then the
 * first rows of a list), not on every small element. Under Reduced motion it is a quick fade only;
 * in Low Power Mode nothing animates. It moves the element's layer only, so it never changes
 * layout, never recomposes and never blocks input.
 */
fun Modifier.reveal(reveal: Reveal, index: Int = 0): Modifier = this then RevealElement(reveal, index)

/** [reveal] in the entry of the nearest [RevealScope]; without one the element is simply shown. */
fun Modifier.reveal(index: Int = 0): Modifier = this then RevealElement(null, index)

private class RevealElement(val reveal: Reveal?, val index: Int) : ModifierNodeElement<RevealNode>() {
    override fun create() = RevealNode(reveal, index)

    override fun update(node: RevealNode) {
        // A new index or entry never restarts a reveal that already ran.
        node.reveal = reveal
        node.index = index
    }

    override fun equals(other: Any?) = other is RevealElement && other.reveal === reveal && other.index == index

    override fun hashCode() = (reveal?.hashCode() ?: 0) * 31 + index

    override fun InspectorInfo.inspectableProperties() {
        name = "reveal"
        properties["index"] = index
    }
}

private class RevealNode(var reveal: Reveal?, var index: Int) :
    Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {

    /** The entry this element plays from, while it has some of its reveal left to play. */
    private var cue: Cue? = null
    private var delayMs = 0
    private var durationMs = 0
    private var risePx = 0f
    private var fadeOnly = false

    override val shouldAutoInvalidate: Boolean get() = false

    override fun onAttach() {
        cue = null
        val entry = reveal ?: currentValueOf(LocalReveal)
        val motion = currentValueOf(LocalFuselineMotion)
        val quality = currentValueOf(LocalRenderQuality)
        if (entry == null || !quality.animatedBackground) return
        // Decided now, before the first frame: an element that appears after the entry is simply
        // there, without even one hidden frame.
        if (entry.cue.elapsedMillis > Reveal.WINDOW_MS) return
        fadeOnly = motion.reduced
        risePx = with(currentValueOf(LocalDensity)) { motion.revealRise.toPx() }
        delayMs = motion.stagger(index)
        durationMs = if (fadeOnly) motion.ms(Durations.FAST) else motion.ms(Durations.SLOW + 60)
        cue = entry.cue
        entry.cue.join((delayMs + durationMs).toLong())
    }

    override fun onDetach() {
        cue = null
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                // Read in the layer, from the entry's one clock: a frame of the entrance redraws
                // layers only. Once played, it stops reading (and stops being redrawn by others).
                val c = cue ?: return@placeWithLayer
                val p = c.progress(delayMs, durationMs, Curves.Enter)
                if (p >= 1f) {
                    cue = null
                    alpha = 1f
                    translationY = 0f
                } else {
                    // The fade finishes ahead of the rise, so text is readable while it settles.
                    alpha = (p * 1.6f).coerceAtMost(1f)
                    translationY = if (fadeOnly) 0f else (1f - p) * risePx
                }
            }
        }
    }
}
