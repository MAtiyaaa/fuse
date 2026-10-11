package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.sync.SyncStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fuse started by a game's entry in Steam (`--play <id>`): the game starts as soon as Fuse is ready,
 * the way it does from Fuse (its emulator, its settings, its saves and play time), and when it ends
 * and its saves are kept, Fuse closes, so the person is back in Steam. A game that can't start keeps
 * Fuse open, saying why; a Fuse not set up yet opens setup as usual.
 */
internal suspend fun AppState.playOnStart(id: Long) {
    if (!store.prefs.value.onboardingDone) return
    val card = withTimeoutOrNull(FIND_MS) {
        store.library.games(GameQuery(includeHidden = true)).first { list -> list.any { it.id.value == id } }.first { it.id.value == id }
    }
    if (card == null) {
        toasts.show("That game isn't in Fuse's library any more. Settings, Library, Steam can tidy Steam's entries", ToastKind.ERROR)
        return
    }
    play(card)
    val playing = store.library.home.map { it.playtime.currentGame?.id }
    // It started (an emulator that couldn't start leaves Fuse open, with why).
    withTimeoutOrNull(START_MS) { playing.first { it == card.id } } ?: return
    playing.first { it != card.id }
    // Its saves are kept here (and sent, where a household is reachable) before Fuse goes.
    delay(SETTLE_MS)
    store.sync.service?.let { s -> withTimeoutOrNull(SAVES_MS) { s.status.first { st -> st !is SyncStatus.Online || !st.working } } }
    platform.exit()
}

/** How long Fuse looks for the game in its library while it starts. */
private const val FIND_MS = 30_000L

/** How long the game has to start before Fuse stays open instead. */
private const val START_MS = 90_000L

/** A moment for the save the game just wrote to be taken in. */
private const val SETTLE_MS = 2_000L

/** The longest Fuse waits for saves to go before closing (they go on the next start otherwise). */
private const val SAVES_MS = 60_000L
