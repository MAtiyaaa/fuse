package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.RenderQuality
import kotlin.math.roundToInt

/** Curves shared by every animation. */
object Easings {
    /** Most movement: quick start, long gentle landing. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Things arriving on screen. */
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Things leaving: accelerate away, never linger. */
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Crossfades between artworks. */
    val Fade: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /**
     * Light travelling across glass: eases in and out more strongly than [Fade], so a sweep seems to
     * pick up speed through the middle of a tile the way a reflection does when you tilt it.
     */
    val Sweep: Easing = CubicBezierEasing(0.45f, 0f, 0.25f, 1f)
}

/** Base durations in milliseconds (Standard profile). Controller users move fast, so these stay short. */
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

    /** Between neighbouring items revealed as a screen opens (see [FuseMotion.stagger]). */
    const val STAGGER = 25

    /** The whole interface crossfading from one theme to the next. */
    const val THEME = 300

    /** One loading shimmer: a pass of light, then a rest. */
    const val SHIMMER = 1600

    /** One leg of the hero art's slow drift (Enhanced motion only). */
    const val DRIFT = 26_000
}

/**
 * Motion scaled to the user's profile. Reduced keeps only short fades (no scale, slide or parallax);
 * Enhanced adds depth effects but keeps the same durations, so it never slows navigation down.
 *
 * Specs here never block input: every one of them can be interrupted by the next change, and the
 * springs are tuned to settle without a visible wobble unless the KDoc says otherwise.
 */
@Immutable
class FuseMotion(val profile: MotionProfile) {
    val reduced: Boolean get() = profile == MotionProfile.REDUCED

    /** Tiles scale up when focused (1 = no scaling). */
    val focusScale: Float = when (profile) {
        MotionProfile.REDUCED -> 1f
        MotionProfile.MINIMAL -> 1.04f
        MotionProfile.STANDARD -> 1.07f
        MotionProfile.ENHANCED -> 1.08f
    }

    /** Light sweep across a tile when it gains focus. */
    val sweep: Boolean = profile == MotionProfile.STANDARD || profile == MotionProfile.ENHANCED

    /** Hero drifts slower than content while scrolling. */
    val parallax: Boolean = profile == MotionProfile.ENHANCED || profile == MotionProfile.STANDARD

    /** Animated theme backgrounds. */
    val ambient: Boolean = profile != MotionProfile.REDUCED && profile != MotionProfile.MINIMAL

    /** The hero art drifts and zooms very slowly while it rests. Enhanced only. */
    val drift: Boolean = profile == MotionProfile.ENHANCED

    /** Slide distance used by page transitions, as a fraction of the page. */
    val slideFraction: Float = when (profile) {
        MotionProfile.REDUCED -> 0f
        MotionProfile.MINIMAL -> 0.03f
        MotionProfile.STANDARD -> 0.06f
        MotionProfile.ENHANCED -> 0.08f
    }

    /** How far content rises as it is revealed on entry (0 under Reduced, where it only fades). */
    val revealRise: Dp = when (profile) {
        MotionProfile.REDUCED -> 0.dp
        MotionProfile.MINIMAL -> 6.dp
        MotionProfile.STANDARD -> 10.dp
        MotionProfile.ENHANCED -> 12.dp
    }

    /** Delay between neighbouring revealed items (none under Reduced). */
    val staggerMs: Int = when (profile) {
        MotionProfile.REDUCED -> 0
        MotionProfile.MINIMAL -> 15
        MotionProfile.STANDARD -> Durations.STAGGER
        MotionProfile.ENHANCED -> 30
    }

    /** How far a pressed item shrinks (1 = not at all, under Reduced). */
    val pressScale: Float = if (profile == MotionProfile.REDUCED) 1f else 0.965f

    /** The scale an overlay panel grows from as it opens (1 under Reduced, where it only fades). */
    val overlayScale: Float = if (profile == MotionProfile.REDUCED) 1f else 0.96f

    fun ms(base: Int): Int = when (profile) {
        MotionProfile.REDUCED -> minOf(base, 120).let { (it * 0.6f).roundToInt() }
        MotionProfile.MINIMAL -> (base * 0.75f).roundToInt()
        MotionProfile.STANDARD -> base
        MotionProfile.ENHANCED -> base
    }

    /** The reveal delay for the item at [index]: [staggerMs] per item, capped at [STAGGER_MAX] items. */
    fun stagger(index: Int): Int = staggerMs * index.coerceIn(0, STAGGER_MAX)

    fun <T> tween(base: Int, easing: Easing = Easings.Standard): FiniteAnimationSpec<T> =
        tween(durationMillis = ms(base), easing = easing)

    /** Focus lift: a firm spring that settles without visible bounce. */
    fun <T> focusSpring(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.82f, stiffness = 900f)

    /** Carousels and strips following the selection. */
    fun <T> followSpring(): AnimationSpec<T> =
        if (reduced) tween(ms(Durations.FAST), easing = Easings.Standard)
        else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)

    /** Content fades. */
    fun <T> fade(base: Int = Durations.BASE): FiniteAnimationSpec<T> = tween(ms(base), easing = Easings.Fade)

    /**
     * Something arriving: an overlay panel opening, a row appearing, a toast sliding in. Pair it
     * with [exit] for the way out, which is always quicker.
     */
    fun <T> enter(base: Int = Durations.BASE): FiniteAnimationSpec<T> = tween(ms(base), easing = Easings.Enter)

    /** Something leaving: quicker than [enter] and accelerating away, so it never lingers. */
    fun <T> exit(base: Int = Durations.FAST): FiniteAnimationSpec<T> = tween(ms(base), easing = Easings.Exit)

    /**
     * Indicators that glide from place to place: a tab underline, the highlight behind the selected
     * row of a list or menu, a segmented control's thumb. Quick and without bounce, so holding a
     * direction reads as one smooth run. Snaps under Reduced motion. For a two-edged indicator use
     * [io.github.matiyaaa.fuse.ui.designsystem.effects.rememberGlide], which pairs this with
     * [glideTrail].
     */
    fun <T> glide(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.9f, stiffness = 1400f)

    /** The trailing edge of a gliding indicator: softer than [glide], so the indicator stretches as it moves. */
    fun <T> glideTrail(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.92f, stiffness = 900f)

    /**
     * Values that change in place: a toggle's knob, a slider's fill, a progress bar, a counter.
     * Interruptible, so a value that keeps changing (a download) flows instead of stepping.
     */
    fun <T> value(): FiniteAnimationSpec<T> =
        if (reduced) tween(ms(Durations.FAST), easing = Easings.Standard)
        else spring(dampingRatio = 1f, stiffness = 420f)

    /**
     * The spark bar under a focused tile growing out: a lively spring with a small overshoot, so the
     * bar pops into place a beat after the lift. Snaps under Reduced motion.
     */
    fun <T> barSpring(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.55f, stiffness = 520f)

    /** Pressing down: quick and direct, so a tap is felt at once. */
    fun <T> pressIn(): FiniteAnimationSpec<T> = tween(ms(Durations.INSTANT), easing = Easings.Standard)

    /** Letting go: springs back with a hint of overshoot, like a key. */
    fun <T> pressOut(): FiniteAnimationSpec<T> =
        if (reduced) tween(ms(Durations.FAST), easing = Easings.Standard)
        else spring(dampingRatio = 0.5f, stiffness = 600f)

    /** Mouse hover highlights: quick in, a little slower out. */
    fun <T> hover(entering: Boolean): FiniteAnimationSpec<T> =
        tween(ms(if (entering) Durations.INSTANT else Durations.FAST), easing = Easings.Standard)

    companion object {
        /** Items past this index are revealed together with it, so long lists never queue up. */
        const val STAGGER_MAX = 8
    }
}

/**
 * Whether decorative, continuous motion may run: the profile allows ambient movement and the
 * performance profile isn't saving power. Gate loops (drift, shimmer passes) on this.
 */
fun FuseMotion.ambientOn(quality: RenderQuality): Boolean = ambient && quality.animatedBackground

/**
 * Whether one-shot flourishes may run (reveals, the focus sweep, theme crossfades): anything but
 * Reduced motion, outside Low Power Mode.
 */
fun FuseMotion.flourishOn(quality: RenderQuality): Boolean = !reduced && quality.animatedBackground
