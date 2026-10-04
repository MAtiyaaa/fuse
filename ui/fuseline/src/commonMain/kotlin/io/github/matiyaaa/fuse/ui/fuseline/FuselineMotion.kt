package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How much movement the person wants. */
enum class MotionLevel { REDUCED, MINIMAL, STANDARD, ENHANCED }

/** Base durations in milliseconds (at [MotionLevel.STANDARD]). Controller users move fast, so these stay short. */
object Durations {
    const val INSTANT = 90
    const val FAST = 150
    const val BASE = 220
    const val SLOW = 320
    const val DELIBERATE = 420

    /** Hero art changing behind the interface. */
    const val HERO = 380

    /** The one-shot light sweep across a newly focused tile. */
    const val SWEEP = 520

    /** Between neighbouring items revealed as a screen opens (see [FuselineMotion.stagger]). */
    const val STAGGER = 25

    /** The whole interface crossfading from one theme to the next. */
    const val THEME = 300

    /** One loading shimmer: a pass of light, then a rest. */
    const val SHIMMER = 1600

    /** One leg of the hero art's slow drift (Enhanced motion only). */
    const val DRIFT = 26_000
}

/**
 * Fuse's motion at one [level]: the motions every part of the interface uses, scaled to the
 * person's choice. Reduced keeps only short fades (no scale, slide or parallax); Enhanced adds
 * depth but keeps the same durations, so it never slows navigation down.
 *
 * Every motion here can be interrupted by the next change, and the springs settle without a
 * visible wobble unless their description says otherwise.
 */
@Immutable
class FuselineMotion(val level: MotionLevel) {
    val reduced: Boolean get() = level == MotionLevel.REDUCED

    /** Tiles scale up when focused (1 = no scaling). */
    val focusScale: Float = when (level) {
        MotionLevel.REDUCED -> 1f
        MotionLevel.MINIMAL -> 1.04f
        MotionLevel.STANDARD -> 1.07f
        MotionLevel.ENHANCED -> 1.08f
    }

    /** Light sweep across a tile when it gains focus. */
    val sweep: Boolean = level == MotionLevel.STANDARD || level == MotionLevel.ENHANCED

    /** Hero drifts slower than content while scrolling. */
    val parallax: Boolean = level == MotionLevel.ENHANCED || level == MotionLevel.STANDARD

    /** Animated theme backgrounds. */
    val ambient: Boolean = level != MotionLevel.REDUCED && level != MotionLevel.MINIMAL

    /** The hero art drifts and zooms very slowly while it rests. Enhanced only. */
    val drift: Boolean = level == MotionLevel.ENHANCED

    /** Slide distance used by page transitions, as a fraction of the page. */
    val slideFraction: Float = when (level) {
        MotionLevel.REDUCED -> 0f
        MotionLevel.MINIMAL -> 0.03f
        MotionLevel.STANDARD -> 0.06f
        MotionLevel.ENHANCED -> 0.08f
    }

    /** How far content rises as it is revealed on entry (0 under Reduced, where it only fades). */
    val revealRise: Dp = when (level) {
        MotionLevel.REDUCED -> 0.dp
        MotionLevel.MINIMAL -> 6.dp
        MotionLevel.STANDARD -> 10.dp
        MotionLevel.ENHANCED -> 12.dp
    }

    /** Delay between neighbouring revealed items (none under Reduced). */
    val staggerMs: Int = when (level) {
        MotionLevel.REDUCED -> 0
        MotionLevel.MINIMAL -> 15
        MotionLevel.STANDARD -> Durations.STAGGER
        MotionLevel.ENHANCED -> 30
    }

    /** How far a pressed item shrinks (1 = not at all, under Reduced). */
    val pressScale: Float = if (level == MotionLevel.REDUCED) 1f else 0.965f

    /** The scale an overlay panel grows from as it opens (1 under Reduced, where it only fades). */
    val overlayScale: Float = if (level == MotionLevel.REDUCED) 1f else 0.96f

    fun ms(base: Int): Int = when (level) {
        MotionLevel.REDUCED -> minOf(base, 120).let { (it * 0.6f).roundToInt() }
        MotionLevel.MINIMAL -> (base * 0.75f).roundToInt()
        MotionLevel.STANDARD -> base
        MotionLevel.ENHANCED -> base
    }

    /** The reveal delay for the item at [index]: [staggerMs] per item, capped at [STAGGER_MAX] items. */
    fun stagger(index: Int): Int = staggerMs * index.coerceIn(0, STAGGER_MAX)

    fun tween(base: Int, easing: Curve = Curves.Standard): Tween = Tween(ms(base), curve = easing)

    /** Focus lift: a firm spring that settles without visible bounce. */
    fun focusSpring(): Motion = if (reduced) Snap() else Spring(dampingRatio = 0.82f, stiffness = 900f)

    /** Carousels and strips following the selection. */
    fun followSpring(): Motion =
        if (reduced) Tween(ms(Durations.FAST), curve = Curves.Standard)
        else Spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)

    /**
     * How a list glides to keep up with the selection: a critically damped spring, which restarts
     * smoothly from its current speed when the selection moves again (holding a direction glides
     * instead of stepping). Under Reduced motion a short tween, so the list still moves (the
     * selection must stay in view) without a long slide.
     */
    fun followScroll(): Motion = if (reduced) Tween(ms(Durations.FAST), curve = Curves.Standard) else FOLLOW

    /** Content fades. */
    fun fade(base: Int = Durations.BASE): Tween = Tween(ms(base), curve = Curves.Fade)

    /**
     * Something arriving: an overlay panel opening, a row appearing, a toast sliding in. Pair it
     * with [exit] for the way out, which is always quicker.
     */
    fun enter(base: Int = Durations.BASE): Tween = Tween(ms(base), curve = Curves.Enter)

    /** Something leaving: quicker than [enter] and accelerating away, so it never lingers. */
    fun exit(base: Int = Durations.FAST): Tween = Tween(ms(base), curve = Curves.Exit)

    /**
     * Indicators that glide from place to place: a tab underline, the highlight behind the
     * selected row of a list or menu, a segmented control's thumb. Quick and without bounce, so
     * holding a direction reads as one smooth run. Snaps under Reduced motion. For a two-edged
     * indicator use [rememberGlide], which pairs this with [glideTrail].
     */
    fun glide(): Motion = if (reduced) Snap() else Spring(dampingRatio = 0.9f, stiffness = 1400f)

    /** The trailing edge of a gliding indicator: softer than [glide], so the indicator stretches as it moves. */
    fun glideTrail(): Motion = if (reduced) Snap() else Spring(dampingRatio = 0.92f, stiffness = 900f)

    /**
     * Values that change in place: a toggle's knob, a slider's fill, a progress bar, a counter.
     * Interruptible, so a value that keeps changing (a download) flows instead of stepping.
     */
    fun value(): Motion =
        if (reduced) Tween(ms(Durations.FAST), curve = Curves.Standard)
        else Spring(dampingRatio = 1f, stiffness = 420f)

    /**
     * The spark bar under a focused tile growing out: a lively spring with a small overshoot, so
     * the bar pops into place a beat after the lift. Snaps under Reduced motion.
     */
    fun barSpring(): Motion = if (reduced) Snap() else Spring(dampingRatio = 0.55f, stiffness = 520f)

    /** Pressing down: quick and direct, so a tap is felt at once. */
    fun pressIn(): Motion = Tween(ms(Durations.INSTANT), curve = Curves.Standard)

    /** Letting go: springs back with a hint of overshoot, like a key. */
    fun pressOut(): Motion =
        if (reduced) Tween(ms(Durations.FAST), curve = Curves.Standard)
        else Spring(dampingRatio = 0.5f, stiffness = 600f)

    /** Mouse hover highlights: quick in, a little slower out. */
    fun hover(entering: Boolean): Motion =
        Tween(ms(if (entering) Durations.INSTANT else Durations.FAST), curve = Curves.Standard)

    companion object {
        /** Items past this index are revealed together with it, so long lists never queue up. */
        const val STAGGER_MAX = 8

        private val FOLLOW = Spring(dampingRatio = 1f, stiffness = 600f)
    }
}

/** The motion in force where this is read. */
val LocalFuselineMotion = staticCompositionLocalOf { FuselineMotion(MotionLevel.STANDARD) }
