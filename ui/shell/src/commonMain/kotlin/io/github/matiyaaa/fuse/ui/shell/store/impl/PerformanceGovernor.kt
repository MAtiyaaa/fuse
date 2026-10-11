package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.EnginePolicy
import io.github.matiyaaa.fuse.ui.shell.store.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Fuse Engine's Performance Governor: one place that knows what Fuse is doing (in use, idle, behind
 * another window, a game running, the device asleep or just woken) and what each of those allows
 * ([EnginePolicy]). The parts of Fuse that do optional work ask it before they do: decorative
 * motion, automatic art and details, looking for emulators. Saves are never held back by it.
 *
 * Asleep is seen two ways: when the platform says so, and on waking, by the clock having jumped
 * further than Fuse's own ticks could (a suspended device runs no code, so it can't say so itself).
 */
internal class PerformanceGovernor(
    private val scope: CoroutineScope,
    playing: StateFlow<GameId?>,
    /** The wall clock, which keeps counting while the device sleeps. */
    private val wallClock: () -> Long,
    /** Nanoseconds since the person last used a control, or null when they haven't yet. */
    private val sinceInput: () -> Long?,
) {
    private val foreground = MutableStateFlow(true)
    private val asleep = MutableStateFlow(false)
    private val waking = MutableStateFlow(false)
    private val idle = MutableStateFlow(false)

    private val playingNow = playing

    /** The state from what is known this moment (the flow below can trail it by a dispatch). */
    fun current(): EngineState = stateOf(playingNow.value, foreground.value, asleep.value, waking.value, idle.value)

    private fun stateOf(game: GameId?, front: Boolean, sleeping: Boolean, woke: Boolean, still: Boolean) = when {
        sleeping -> EngineState.SUSPENDED
        woke -> EngineState.RESUMING
        game != null -> EngineState.PLAYING
        !front -> EngineState.BACKGROUND
        still -> EngineState.IDLE
        else -> EngineState.ACTIVE
    }

    val state: StateFlow<EngineState> = combine(playing, foreground, asleep, waking, idle, ::stateOf)
        .stateIn(scope, SharingStarted.Eagerly, EngineState.ACTIVE)

    /** What may run this moment. */
    val policy: EnginePolicy get() = EnginePolicy.of(current())

    fun start() {
        scope.launch {
            var last = wallClock()
            while (true) {
                delay(TICK_MS)
                val now = wallClock()
                // The clock ran on far past one tick: the device slept in between.
                if (now - last > TICK_MS + SLEEP_GAP_MS) woke()
                last = now
                idle.value = sinceInput()?.let { it > IDLE_NANOS } ?: false
            }
        }
    }

    fun onForeground(front: Boolean) {
        foreground.value = front
        if (front) idle.value = false
    }

    /** The platform says the device is going to sleep; Fuse holds optional work until it wakes. */
    fun onSuspend() {
        asleep.value = true
    }

    /** Back from sleep: a short settling time (the network and drives come back) before optional work resumes. */
    fun woke() {
        asleep.value = false
        waking.value = true
        scope.launch {
            delay(RESUME_SETTLE_MS)
            waking.value = false
        }
    }

    /** Waits until optional work of the kind [allowed] picks out may run. */
    suspend fun await(allowed: (EnginePolicy) -> Boolean) {
        if (allowed(policy)) return
        state.first { allowed(EnginePolicy.of(it)) && allowed(policy) }
    }

    private companion object {
        const val TICK_MS = 5_000L
        const val SLEEP_GAP_MS = 25_000L
        const val RESUME_SETTLE_MS = 8_000L
        const val IDLE_NANOS = 180_000_000_000L
    }
}
