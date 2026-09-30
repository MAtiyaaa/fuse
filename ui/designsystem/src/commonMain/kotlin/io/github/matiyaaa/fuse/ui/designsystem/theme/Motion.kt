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
import io.github.matiyaaa.fuse.model.MotionProfile
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
}

/**
 * Motion scaled to the user's profile. Reduced keeps only short fades (no scale, slide or parallax);
 * Enhanced adds depth effects but keeps the same durations, so it never slows navigation down.
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

    /** Slide distance used by page transitions, as a fraction of the page. */
    val slideFraction: Float = when (profile) {
        MotionProfile.REDUCED -> 0f
        MotionProfile.MINIMAL -> 0.03f
        MotionProfile.STANDARD -> 0.06f
        MotionProfile.ENHANCED -> 0.08f
    }

    fun ms(base: Int): Int = when (profile) {
        MotionProfile.REDUCED -> minOf(base, 120).let { (it * 0.6f).roundToInt() }
        MotionProfile.MINIMAL -> (base * 0.75f).roundToInt()
        MotionProfile.STANDARD -> base
        MotionProfile.ENHANCED -> base
    }

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
}
