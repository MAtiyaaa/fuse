package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.model.GameId

/**
 * Fuse Fast Start: one game started exactly as Play starts it, with no window, for a Steam entry
 * (`Fuse --run <id>`). The person's newest save is put in place first, the game runs, its session is
 * counted and its saves are kept and sent where they go, and then Fuse leaves; Steam follows Fuse's
 * process, so it sees the game for as long as it runs.
 *
 * Whatever only the person can decide (two saves to choose between, another device still playing
 * the game, a problem to explain) is never decided here: the run says so ([Outcome.NeedsYou]) and
 * Fuse opens on the game instead, as `--play` does.
 */
object FastStart {
    sealed interface Outcome {
        /** It ran, and everything after it is done. */
        data object Played : Outcome

        /** Only the emulator's app opened: which game is played there can't be followed. */
        data class OpenedApp(val app: String) : Outcome

        /** Something only the person can decide or read: Fuse opens on the game. [why] is for the log. */
        data class NeedsYou(val why: String) : Outcome
    }

    /** Starts [id] through [store] (one made with `createRunStore`) and returns once all of it is done. */
    suspend fun run(store: FuseStore, id: GameId): Outcome = when (val started = store.library.launch(id)) {
        LaunchOutcome.Started, is LaunchOutcome.Synced -> {
            store.library.awaitPlayed()
            Outcome.Played
        }
        is LaunchOutcome.OpenedAppOnly -> Outcome.OpenedApp(started.appName)
        is LaunchOutcome.Problem -> Outcome.NeedsYou(started.problem.title)
        is LaunchOutcome.SaveConflict -> Outcome.NeedsYou("this device and Fuse One both have a newer save")
        is LaunchOutcome.SyncBusy -> Outcome.NeedsYou("another device is playing it or still sending its save")
        is LaunchOutcome.SyncWaiting -> Outcome.NeedsYou("a newer save is still on its way")
        is LaunchOutcome.RommSaveConflict -> Outcome.NeedsYou("this device and RomM both have a newer save")
        is LaunchOutcome.SyncthingConflict -> Outcome.NeedsYou("Syncthing kept two versions of its save")
    }
}
