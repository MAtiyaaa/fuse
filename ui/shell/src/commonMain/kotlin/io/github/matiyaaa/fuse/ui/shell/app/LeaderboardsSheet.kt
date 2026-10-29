package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import kotlinx.coroutines.launch

/**
 * A game's RetroAchievements leaderboards (Fuse 0.4.6): each with what it asks, the best score and
 * whose it is, and the person's own score with its place. The ones they have a score on come first.
 */
fun AppState.leaderboardsSheet(card: GameCard) {
    contextMenu = null
    scope.launch {
        val boards = runCatching { store.achievements.leaderboards(card.id) }.getOrNull()
        when {
            boards == null -> toasts.show("Fuse doesn't know ${card.title}'s RetroAchievements set, or RetroAchievements isn't answering")
            boards.isEmpty() -> toasts.show("${card.title} has no leaderboards on RetroAchievements")
            else -> choice = ChoiceSpec(
                title = "Leaderboards",
                icon = FuseIcons.Medal,
                message = "${card.title}: the best of everyone, and yours where you have a score.",
                options = boards.mapIndexed { i, b ->
                    MenuAction(
                        "board.$i", b.title, if (b.myRank == 1L) FuseIcons.Crown else FuseIcons.Medal,
                        detail = listOfNotNull(
                            b.description.takeIf { it.isNotBlank() },
                            b.best?.let { best -> "Best $best" + (b.leader?.let { " by $it" } ?: "") },
                        ).joinToString("  ·  ").ifEmpty { null },
                        trailing = Trailing.Value(b.myScore?.let { score -> (b.myRank?.let { "#$it  " } ?: "") + score } ?: "No score yet"),
                        onSelect = {},
                    )
                },
            )
        }
    }
}
