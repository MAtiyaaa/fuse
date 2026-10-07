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
 * What the device itself asks of motion, where it can tell reliably: saving power, drawing without
 * the graphics card, its refresh rate. Decoration goes first (ambient loops, light sweeps, drift,
 * parallax); interaction (focus, selection, navigation, gestures) keeps its full quality, and no
 * motion's physics ever changes with it.
 */
@Immutable
data class MotionEnvironment(
    val lowPower: Boolean = false,
    val cpuDrawing: Boolean = false,
    val refreshRate: Float = 60f,
) {
    /** Decoration costs nothing worth saving here. */
    val decorates: Boolean get() = !lowPower && !cpuDrawing

    companion object {
        val Default = MotionEnvironment()
    }
}

/**
 * Fuse's motion at one [level]: the motions every part of the interface uses, scaled to the
 * person's choice. Reduced keeps only short fades (no scale, slide or parallax); Enhanced adds
 * depth but keeps the same durations, so it never slows navigation down.
 *
 * Fuse asks for an intent ([focus], [selection], [follow], [settle], [enter], [exit], [dismiss],
 * [reveal], [navigation], [press], [hover], [sharedElement], [overscroll]) rather than tuning
 * numbers in place, and the intent adapts: under Reduced, large movement becomes a fade, overshoot
 * and parallax go and ambient loops stop, while the feedback that says something happened stays.
 * The [environment] trims decoration first and never interaction.
 *
 * Every motion here can be interrupted by the next change, and the springs settle without a
 * visible wobble unless their description says otherwise.
 */
@Immutable
class FuselineMotion(val level: MotionLevel, val environment: MotionEnvironment = MotionEnvironment.Default) {
    val reduced: Boolean get() = level == MotionLevel.REDUCED

    /** Tiles scale up when focused (1 = no scaling). */
    val focusScale: Float = when (level) {
        MotionLevel.REDUCED -> 1f
        MotionLevel.MINIMAL -> 1.04f
        MotionLevel.STANDARD -> 1.07f
        MotionLevel.ENHANCED -> 1.08f
    }

    /** Light sweep across a tile when it gains focus. */
    val sweep: Boolean = (level == MotionLevel.STANDARD || level == MotionLevel.ENHANCED) && environment.decorates

    /** Hero drifts slower than content while scrolling. */
    val parallax: Boolean = (level == MotionLevel.ENHANCED || level == MotionLevel.STANDARD) && environment.decorates

    /** Animated theme backgrounds. */
    val ambient: Boolean = level != MotionLevel.REDUCED && level != MotionLevel.MINIMAL && environment.decorates

    /** The hero art drifts and zooms very slowly while it rests. Enhanced only. */
    val drift: Boolean = level == MotionLevel.ENHANCED && environment.decorates

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

    // ------------------------------------------------------------------------------------------
    // Intents. Each says what the motion is for; the level and environment decide its shape.

    /** Overshoot is a flourish: gone under Reduced (and kept gentle under Minimal), the movement itself kept. */
    fun adapt(motion: Motion): Motion = when {
        motion is Spring && level == MotionLevel.REDUCED && motion.dampingRatio < 1f -> motion.copy(dampingRatio = 1f)
        motion is Spring && level == MotionLevel.MINIMAL && motion.dampingRatio < 0.85f -> motion.copy(dampingRatio = 0.85f)
        else -> motion
    }

    /**
     * How far something travels for a full move of [distance]: all of it normally, a little under
     * Minimal, none under Reduced, where large movement becomes a fade.
     */
    fun travel(distance: Float): Float = when (level) {
        MotionLevel.REDUCED -> 0f
        MotionLevel.MINIMAL -> distance * 0.5f
        else -> distance
    }

    /** Focus moving to an item (its lift and ring). */
    fun focus(): Motion = focusSpring()

    /** The selection moving along a row or a menu (its highlight, an indicator). */
    fun selection(): Motion = glide()

    /** Something keeping up with the selection or a finger (a list scrolling to keep it in view). */
    fun follow(): Motion = followScroll()

    /** Coming to rest where it belongs (after a gesture, a fling, a drop). */
    fun settle(): Motion = if (reduced) Tween(ms(Durations.FAST), curve = Curves.Standard) else Spring(1f, 500f)

    /** Moving between places (tabs, pages, screens): quick, and every change carries on from the last. */
    fun navigation(): Motion = if (reduced) Tween(ms(Durations.FAST), curve = Curves.Fade) else Spring(1f, 900f)

    /** Something put away by the person (a sheet swiped down, a toast flicked off): carries their speed. */
    fun dismiss(): Motion = if (reduced) Tween(ms(Durations.FAST), curve = Curves.Exit) else Spring(1f, 600f)

    /** Content revealed as a screen opens. */
    fun reveal(): Motion = enter()

    /** Press feedback, kept under every level: something happened. */
    fun press(down: Boolean): Motion = if (down) pressIn() else pressOut()

    /** An element travelling between screens: still, cross-faded, under Reduced. */
    fun sharedElement(): Motion = if (reduced) Snap() else Spring(1f, 450f)

    /** Pulling past an end and springing back. */
    fun overscroll(): Motion = if (reduced) Snap() else Spring(1f, 700f)

    companion object {
        /** Items past this index are revealed together with it, so long lists never queue up. */
        const val STAGGER_MAX = 8

        private val FOLLOW = Spring(dampingRatio = 1f, stiffness = 600f)
    }
}

/** The motion in force where this is read. */
val LocalFuselineMotion = staticCompositionLocalOf { FuselineMotion(MotionLevel.STANDARD) }
