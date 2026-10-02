package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.LocalFuseMotion
import io.github.matiyaaa.fuse.ui.designsystem.theme.LocalRenderQuality
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.launch

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
class Reveal internal constructor() {
    /** When the first item of this entry appeared. */
    internal var start: TimeMark? = null

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
fun rememberReveal(vararg keys: Any?): Reveal = remember(*keys) { Reveal() }

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
 * Reveals this element as its screen opens: it fades in while rising [io.github.matiyaaa.fuse.ui.designsystem.theme.FuseMotion.revealRise]
 * (about 10 dp) with [Easings.Enter], starting [io.github.matiyaaa.fuse.ui.designsystem.theme.FuseMotion.stagger]
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

    /** 0 hidden and lowered, 1 in place. */
    private var progress by mutableFloatStateOf(1f)
    private var risePx = 0f
    private var fadeOnly = false

    override val shouldAutoInvalidate: Boolean get() = false

    override fun onAttach() {
        val entry = reveal ?: currentValueOf(LocalReveal)
        val motion = currentValueOf(LocalFuseMotion)
        val quality = currentValueOf(LocalRenderQuality)
        progress = 1f
        if (entry == null || !quality.animatedBackground) return
        // Decided now, before the first frame: an element that appears after the entry is simply
        // there, without even one hidden frame.
        val start = entry.start ?: TimeSource.Monotonic.markNow().also { entry.start = it }
        val elapsed = start.elapsedNow().inWholeMilliseconds
        if (elapsed > Reveal.WINDOW_MS) return
        fadeOnly = motion.reduced
        risePx = with(currentValueOf(LocalDensity)) { motion.revealRise.toPx() }
        progress = 0f
        coroutineScope.launch {
            try {
                val delay = (motion.stagger(index) - start.elapsedNow().inWholeMilliseconds).toInt().coerceAtLeast(0)
                val duration = if (fadeOnly) motion.ms(Durations.FAST) else motion.ms(Durations.SLOW + 60)
                animate(0f, 1f, animationSpec = tween(duration, delay, Easings.Enter)) { v, _ -> progress = v }
            } finally {
                // Cancelled (the element left mid-reveal) or done: never leave it hidden.
                progress = 1f
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val p = progress
                if (p < 1f) {
                    // The fade finishes ahead of the rise, so text is readable while it settles.
                    alpha = (p * 1.6f).coerceAtMost(1f)
                    translationY = if (fadeOnly) 0f else (1f - p) * risePx
                }
            }
        }
    }
}
