package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.LibraryOps
import io.github.matiyaaa.fuse.ui.shell.store.ResumeInfo

private const val HOUR = 60 * 60_000L

/** What 0.4.6 adds: Continue on a game's page. */
internal fun AuditDriver.fuse046Screens() {
    scenario("continue", "a game left mid-play") {
        useLibrary()
        val base = libraryStore
        val now = System.currentTimeMillis()
        // Left two hours ago on the Steam Deck, which DuckStation here carries on from.
        val library = object : LibraryOps by base.library {
            override suspend fun resumePoint(id: GameId) = ResumeInfo("DuckStation", now - 2 * HOUR, "Steam Deck")
        }
        show(object : FuseStore by base { override val library: LibraryOps = library })
        openGame("Last Ferry Home")
        shoot("Continue leads, Start fresh beside it", 1_400)
    }
}
