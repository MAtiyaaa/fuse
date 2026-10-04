package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.VolumeKind
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.MoveTarget
import kotlinx.coroutines.launch

/** Whether there is another drive to move games to: more than one that can be written. */
internal val AppState.canMoveGames: Boolean
    get() = store.sources.volumes.value.count { !it.readOnly } > 1

/**
 * Moves [games] (named [titles], taking [bytes]) to another drive: the drives to choose from, each
 * with its space and where the games go on it, then what happens, asked once, then the move with
 * its progress in Storage. A drive without a games folder gets one made ("ROMs") and added to the
 * library. [from] are the drives the games are on now, left out of the choice.
 */
internal fun AppState.moveGames(
    games: List<GameId>,
    titles: List<String>,
    bytes: Long,
    from: Set<String> = emptySet(),
    onDone: () -> Unit = {},
) {
    if (games.isEmpty()) return
    if (store.storage.moving.value != null) {
        toasts.show("Games are already moving. This can start once they're done", icon = FuseIcons.FolderSync)
        return
    }
    scope.launch {
        val targets = store.storage.moveTargets().filter { it.volumeId !in from }
        if (targets.isEmpty()) {
            toasts.show("Connect an SD card or another drive to move games to", icon = FuseIcons.SdCard)
            return@launch
        }
        choice = ChoiceSpec(
            title = if (games.size == 1) "Move ${titles.first()} to" else "Move ${games.size} games to",
            message = "${bytesText(bytes)} to move. Play time, favourites and art stay with ${if (games.size == 1) "it" else "them"}.",
            icon = FuseIcons.FolderSync,
            options = targets.map { t ->
                val fits = t.freeBytes <= 0 || t.freeBytes > bytes
                MenuAction(
                    "to.${t.volumeId}", t.label, t.icon(),
                    detail = t.gamesFolder?.let { "Into its ${io.github.matiyaaa.fuse.library.FsPath.name(it)} folder, each game with its system" } ?: "Fuse makes a ROMs folder on it and adds it to your library",
                    trailing = if (t.freeBytes > 0) Trailing.Value("${bytesText(t.freeBytes)} free") else Trailing.None,
                    unavailableReason = if (fits) null else "Not enough space: ${bytesText(t.freeBytes)} free, ${bytesText(bytes)} needed",
                    onSelect = {
                        choice = null
                        confirmMove(games, titles, bytes, t, onDone)
                    },
                )
            },
        )
    }
}

private fun AppState.confirmMove(games: List<GameId>, titles: List<String>, bytes: Long, target: MoveTarget, onDone: () -> Unit) {
    val names = titles.take(3).joinToString(", ") + if (titles.size > 3) " and ${titles.size - 3} more" else ""
    confirm = ConfirmSpec(
        title = if (games.size == 1) "Move ${titles.first()} to ${target.label}?" else "Move ${games.size} games to ${target.label}?",
        message = (if (games.size == 1) "" else "$names. ") +
            "${bytesText(bytes)} is copied into ${target.gamesFolder ?: "a new ROMs folder on ${target.label}"}, each game into its system's folder. " +
            "Each game leaves this drive only once its copy is whole, so nothing is lost if the move stops.",
        confirmLabel = "Move",
    ) {
        scope.launch {
            if (target.gamesFolder == null && store.storage.makeGamesFolder(target.volumeId) == null) {
                toasts.show("Fuse couldn't make a folder on ${target.label}. Check that it isn't read only", ToastKind.ERROR)
                return@launch
            }
            toasts.show(if (games.size == 1) "Moving ${titles.first()} to ${target.label}" else "Moving ${games.size} games to ${target.label}", icon = FuseIcons.FolderSync)
            val report = store.storage.move(games, target.volumeId)
            onDone()
            toasts.show(
                when {
                    report.moved == 0 -> report.reason?.let { "Nothing moved. $it" } ?: "Nothing moved. Fuse couldn't copy ${report.failed.take(2).joinToString(", ")}"
                    report.failed.isEmpty() -> "Moved ${report.moved} ${if (report.moved == 1) "game" else "games"} (${bytesText(report.bytes)}) to ${target.label}"
                    else -> "Moved ${report.moved}; ${report.failed.size} stayed where ${if (report.failed.size == 1) "it was" else "they were"}"
                },
                if (report.moved == 0) ToastKind.ERROR else ToastKind.SUCCESS,
            )
        }
    }
}

/** The icon for a kind of drive. */
internal fun MoveTarget.icon(): ImageVector = when (kind) {
    VolumeKind.SD_CARD -> FuseIcons.SdCard
    VolumeKind.USB, VolumeKind.EXTERNAL -> FuseIcons.Usb
    VolumeKind.NETWORK -> FuseIcons.Network
    else -> FuseIcons.HardDrive
}
