package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.ui.fuseline.DecorationRate

/** What Fuse is doing, as Fuse Engine's Performance Governor sees it. */
enum class EngineState {
    /** In use. */
    ACTIVE,

    /** On screen, untouched for a few minutes. */
    IDLE,

    /** Behind another window, or the app in the background. */
    BACKGROUND,

    /** A game is running. */
    PLAYING,

    /** The device is asleep. */
    SUSPENDED,

    /** Just woken: a few seconds for the network and drives to come back. */
    RESUMING,
}

/**
 * What each [EngineState] allows of Fuse's optional work. Saves are never part of it: they are
 * kept and sent whatever the state.
 */
data class EnginePolicy(
    /** Decorative motion: moving backgrounds and the like (navigation and focus always move). */
    val motion: DecorationRate,
    /** Fetching art and details by itself, and the image work that comes with it. */
    val artwork: Boolean,
    /** Looking for emulators and apps, and learning about them. */
    val detection: Boolean,
    /** Background jobs that are only nice to have: finding series, suggestions, identifying games ([io.github.matiyaaa.fuse.ui.shell.store.impl.BackgroundWork]). */
    val background: Boolean = artwork,
) {
    companion object {
        fun of(state: EngineState): EnginePolicy = when (state) {
            EngineState.ACTIVE -> EnginePolicy(DecorationRate.FULL, artwork = true, detection = true)
            EngineState.IDLE -> EnginePolicy(DecorationRate.REDUCED, artwork = true, detection = true)
            EngineState.BACKGROUND -> EnginePolicy(DecorationRate.PAUSED, artwork = true, detection = true)
            // A game has the machine: nothing optional competes with it.
            EngineState.PLAYING -> EnginePolicy(DecorationRate.PAUSED, artwork = false, detection = false)
            EngineState.SUSPENDED, EngineState.RESUMING -> EnginePolicy(DecorationRate.PAUSED, artwork = false, detection = false)
        }
    }
}
