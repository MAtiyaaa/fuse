package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Fuseline 4.1: one clock that many things play from. A page of a hundred tiles coming in used to
 * be a hundred moves, each with its own coroutine and value, all waking every frame for the same
 * few hundred milliseconds. Joined to one cue, they are one frame callback and one number: each
 * reads where it is from the cue's time with its own delay, length and curve ([progress]), where it
 * draws (a graphics layer), so a frame of the entrance recomposes and lays out nothing.
 *
 * The cue runs while anything joined to it still has time to play ([join] says until when), then
 * stops asking for frames. Its time starts at the first frame after the first join, from the frame
 * clock, so it agrees with every other motion on the frame and replays exactly under a test clock.
 * Motion speed (the system's animator scale, Fuse's motion levels) is the caller's: pass lengths
 * already scaled, as [FuselineMotion.ms] gives them.
 */
@Stable
class Cue(private val scope: CoroutineScope) {
    private var origin = -1L
    private var until = 0L
    private var ticking: Job? = null

    /** Milliseconds since the cue's first frame; read from a draw or layer block to follow it. */
    var nowMillis by mutableLongStateOf(0L)
        private set

    /** True while it is asking for frames. */
    val playing: Boolean get() = ticking?.isActive == true

    /**
     * Something will play from this cue until [endsAtMillis] (on the cue's time). The cue starts if
     * it isn't running, and runs at least until then.
     */
    fun join(endsAtMillis: Long) {
        if (endsAtMillis > until) until = endsAtMillis
        if (ticking?.isActive == true) return
        ticking = scope.launch {
            while (true) {
                val now = withFrameNanos { t ->
                    if (origin < 0) origin = t
                    (t - origin) / 1_000_000
                }
                if (now != nowMillis) nowMillis = now
                if (now >= until) break
            }
        }
    }

    /**
     * Where a part joined to the cue is: 0 before [delayMillis], then along [curve] for
     * [durationMillis], then 1. A part whose cue was stopped (its page left mid-entrance) is where it
     * would end, never stuck part way.
     */
    fun progress(delayMillis: Int, durationMillis: Int, curve: Curve = Curves.Enter): Float {
        val now = nowMillis
        if (now >= delayMillis + durationMillis || (!playing && origin >= 0 && now < until)) return 1f
        if (now <= delayMillis || durationMillis <= 0) return if (durationMillis <= 0 && now >= delayMillis) 1f else 0f
        return curve.transform((now - delayMillis).toFloat() / durationMillis)
    }

    /** The cue's time now: [nowMillis] without subscribing to it (for deciding, not drawing). */
    val elapsedMillis: Long get() = if (origin < 0) 0 else nowMillis
}

/** A cue for this place in composition (a new one for each new value of [keys]). */
@Composable
fun rememberCue(vararg keys: Any?): Cue {
    val scope = rememberCoroutineScope()
    return remember(*keys) { Cue(scope) }
}
