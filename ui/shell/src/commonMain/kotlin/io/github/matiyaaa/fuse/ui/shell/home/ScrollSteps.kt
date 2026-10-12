package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Turns a wheel's or a trackpad's scrolling into steps (a card, a page). A mouse wheel moves in
 * whole notches: each notch is a step, however fast they come. A trackpad (a Mac's above all) sends
 * a stream of small, fractional movements, with momentum after the fingers lift: one swipe is one
 * step, and the stream must go quiet before another counts, so a swipe never races through a
 * carousel or the pages, and nothing moves by halves.
 */
internal class ScrollSteps {
    private var pending = 0f
    private var lastAt = Long.MIN_VALUE
    private var spent = false

    /** The step [delta] (scrolled at [atMillis]) makes: -1, 0 or 1. */
    fun feed(delta: Float, atMillis: Long): Int {
        val wheel = delta != 0f && delta == kotlin.math.round(delta)
        if (lastAt == Long.MIN_VALUE || atMillis - lastAt > QUIET_MS) {
            pending = 0f
            spent = false
        }
        lastAt = atMillis
        if (wheel) {
            spent = false
            pending += delta
            if (kotlin.math.abs(pending) < 1f) return 0
        } else {
            if (spent) return 0
            pending += delta
            if (kotlin.math.abs(pending) < TRACKPAD_STEP) return 0
            spent = true
        }
        val by = if (pending > 0f) 1 else -1
        pending = 0f
        return by
    }

    private companion object {
        /** A pause this long ends a trackpad swipe (momentum included). */
        const val QUIET_MS = 180L

        /** How far a trackpad swipe goes before it counts. */
        const val TRACKPAD_STEP = 1.5f
    }
}

/**
 * Whether something under the pointer (a carousel) took this scroll for itself, so the board's
 * pages leave it alone. Set while the event passes down, read as it comes back up.
 */
internal class ScrollClaim {
    var taken = false
}

internal val LocalScrollClaim = staticCompositionLocalOf<ScrollClaim?> { null }
