package io.github.matiyaaa.fuse.ui.fuseline.v3

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy

/**
 * Scrolls by [delta] pixels under [animationSpec], frame by frame. Returns how far it really went
 * (less at an end of the list). Another scroll, or a finger, takes over and ends this one.
 */
suspend fun ScrollableState.fuselineScrollBy(delta: Float, animationSpec: Motion = Spring()): Float {
    if (delta == 0f) return 0f
    var moved = 0f
    scroll {
        var previous = 0f
        animate(0f, delta, animationSpec = animationSpec) { value, _ ->
            val step = value - previous
            val consumed = scrollBy(step)
            previous += step
            moved += consumed
        }
    }
    return moved
}

/** Scrolls to [value] (pixels from the top) under [animationSpec]. */
suspend fun ScrollState.fuselineScrollTo(value: Int, animationSpec: Motion = Spring()) {
    fuselineScrollBy((value - this.value).toFloat(), animationSpec)
}

/** Jumps by [delta] pixels at once. */
suspend fun ScrollableState.jumpBy(delta: Float): Float = scrollBy(delta)
