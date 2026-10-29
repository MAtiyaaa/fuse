package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.library.movein.MoveInSource
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.MoveInReport
import io.github.matiyaaa.fuse.ui.shell.store.MoveInView
import kotlinx.coroutines.launch

/** Each launcher's mark in the Move In sheets. */
internal fun moveInIcon(s: MoveInSource): ImageVector = when (s) {
    MoveInSource.ESDE -> FuseIcons.LayoutList
    MoveInSource.RETROARCH -> FuseIcons.Blocks
    MoveInSource.PEGASUS -> FuseIcons.GalleryThumbnails
    MoveInSource.STEAM -> FuseIcons.Gamepad
    MoveInSource.RETROACHIEVEMENTS -> FuseIcons.Trophy
}

/** "40 favourites, play time for 120 games, 3 collections": what a move in brings, in a line. */
internal fun moveInWords(r: MoveInReport): String = listOfNotNull(
    r.favourites.takeIf { it > 0 }?.let { if (it == 1) "1 favourite" else "$it favourites" },
    r.timed.takeIf { it > 0 }?.let { "play time for ${gamesWord(it)}" },
    r.journal.takeIf { it > 0 }?.let { "${gamesWord(it)} in your Journal" },
    r.hidden.takeIf { it > 0 }?.let { "$it hidden" },
    r.collections.takeIf { it > 0 }?.let { if (it == 1) "1 collection" else "$it collections" },
).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Nothing new for the games here" }

private fun gamesWord(n: Int) = if (n == 1) "1 game" else "$n games"

/**
 * Moving in: finds the launchers on this device (ES-DE, RetroArch, Pegasus, Steam) and the
 * person's Want to Play list on RetroAchievements, shows what each would bring to the games here,
 * and brings in the ones chosen. Steam's play time is only brought in when the person turns it on.
 */
fun AppState.moveInSheet() {
    choice = null
    toasts.show("Looking for your other launchers", icon = FuseIcons.Search)
    scope.launch {
        val found = runCatching { store.library.moveInLook() }.getOrDefault(emptyList())
        if (found.isEmpty()) {
            choice = ChoiceSpec(
                title = "Nothing to bring in",
                icon = FuseIcons.Import,
                message = "Fuse looked for ES-DE, RetroArch, Pegasus and Steam on this device, and at your Want to Play list on RetroAchievements, and none of them says anything about the games here yet.",
                options = listOf(MenuAction("done", "Done", FuseIcons.Check, onSelect = { choice = null })),
            )
            return@launch
        }
        moveInPick(found, found.map { it.source }.filter { it != MoveInSource.STEAM }.toSet())
    }
}

private fun AppState.moveInPick(found: List<MoveInView>, picked: Set<MoveInSource>) {
    val sum = found.filter { it.source in picked }.map { it.report }.fold(MoveInReport()) { a, b ->
        MoveInReport(a.games + b.games, a.favourites + b.favourites, a.hidden + b.hidden, a.timed + b.timed, a.journal + b.journal, a.collections + b.collections)
    }
    choice = ChoiceSpec(
        title = "Bring in your history",
        icon = FuseIcons.Import,
        message = "What your other launchers here say about the games Fuse has. Nothing you set in Fuse is undone, and it can all be put back.",
        options = found.map { v ->
            MenuAction(
                "src.${v.source.name}", v.source.label, moveInIcon(v.source),
                detail = moveInWords(v.report),
                trailing = Trailing.Switch(v.source in picked),
                section = "From",
                onSelect = { moveInPick(found, if (v.source in picked) picked - v.source else picked + v.source) },
            )
        } + listOf(
            MenuAction(
                "go", "Bring it in", FuseIcons.Download,
                detail = if (picked.isEmpty()) "Choose a launcher above" else moveInWords(sum),
                enabled = picked.isNotEmpty(),
                section = "",
                onSelect = { moveInApply(picked) },
            ),
            MenuAction("cancel", "Not now", FuseIcons.Close, section = "", onSelect = { choice = null }),
        ),
    )
}

private fun AppState.moveInApply(picked: Set<MoveInSource>) {
    choice = null
    toasts.show("Bringing in your history", icon = FuseIcons.Import)
    scope.launch {
        val r = runCatching { store.library.moveIn(picked) }.getOrElse {
            toasts.show("Your history couldn't be brought in. Nothing was changed")
            return@launch
        }
        choice = ChoiceSpec(
            title = if (r.isEmpty) "Nothing new" else "Moved in",
            icon = FuseIcons.CheckCheck,
            message = if (r.isEmpty) "Fuse already had everything those launchers say." else "${moveInWords(r)}. Imported play time is kept apart from time Fuse sees you play.",
            options = listOfNotNull(
                MenuAction("done", "Done", FuseIcons.Check, onSelect = { choice = null }),
                MenuAction("undo", "Put it all back", FuseIcons.Undo, detail = "Every game and collection as it was before", onSelect = { moveInUndo() }).takeIf { !r.isEmpty },
            ),
        )
    }
}

/** Puts back the last move in, and says so. */
fun AppState.moveInUndo() {
    choice = null
    scope.launch {
        toasts.show(if (store.library.undoMoveIn()) "Everything is back as it was before moving in" else "There's no move in to put back")
    }
}
