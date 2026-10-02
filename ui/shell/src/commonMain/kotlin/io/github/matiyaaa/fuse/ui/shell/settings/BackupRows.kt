package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.matiyaaa.fuse.data.backup.BackupFile
import io.github.matiyaaa.fuse.data.backup.BackupPart
import io.github.matiyaaa.fuse.data.backup.BackupProblem
import io.github.matiyaaa.fuse.data.backup.RestoreReport
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.showProblem
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.store.BackupOpened
import io.github.matiyaaa.fuse.ui.shell.store.BackupPreview
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import kotlinx.coroutines.launch

/** Settings, Backup and restore: make a backup file, restore one (choosing what), and put settings back after a restore. */
@Composable
fun backupRows(app: AppState): List<MenuAction> {
    val canUndo by app.store.backup.canUndo.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    fun work(id: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = id
        app.scope.launch {
            try {
                block()
            } finally {
                busy = null
            }
        }
    }
    return buildList {
        add(MenuAction(
            "make", "Back up now", FuseIcons.DatabaseBackup,
            detail = "Settings, the look and Home, what you changed on games, collections, chosen art and play time, in one file",
            trailing = if (busy == "make") Trailing.Value("Making") else Trailing.Chevron,
            onSelect = { work("make") { app.makeBackup() } },
        ))
        add(MenuAction(
            "restore", "Restore a backup", FuseIcons.ArchiveRestore,
            detail = "Choose a .fusebackup file, see what's in it, then pick what to bring back",
            trailing = if (busy == "restore") Trailing.Value("Opening") else Trailing.Chevron,
            onSelect = { work("restore") { app.pickBackup() } },
        ))
        if (canUndo) {
            add(MenuAction(
                "undo", "Put settings back", FuseIcons.Undo,
                detail = "Settings, the look and Home as they were before the last restore. Game changes it brought stay",
                trailing = Trailing.Chevron,
                onSelect = {
                    work("undo") {
                        val ok = app.store.backup.undo()
                        app.toasts.show(if (ok) "Settings are back as they were" else "Couldn't put settings back", if (ok) ToastKind.SUCCESS else ToastKind.WARNING)
                    }
                },
            ))
        }
        add(infoRow(
            "never", "What a backup never holds",
            detail = "Games, firmware, keys and passwords stay out, and so does art Fuse found by itself. Nothing is uploaded",
            icon = FuseIcons.ShieldCheck,
        ).copy(section = "Privacy"))
    }
}

private suspend fun AppState.makeBackup() {
    val made = store.backup.create()
    if (made == null) {
        showProblem(Problem(
            title = "Couldn't make a backup",
            message = "Fuse couldn't read its library to write the file. Nothing was changed; try again in a moment.",
            kind = ProblemKind.DATA,
        ))
        return
    }
    val where = platform.saveFile(made.name, BackupFile.MIME_TYPE, made.bytes) ?: return
    toasts.show("Backed up ${counted(made.games, "game")} to $where", ToastKind.SUCCESS)
}

private suspend fun AppState.pickBackup() {
    val file = platform.openFile(listOf(BackupFile.MIME_TYPE, "application/zip"), listOf(BackupFile.EXTENSION), BackupFile.MAX_TOTAL_BYTES) ?: return
    when (val opened = store.backup.open(file.bytes)) {
        is BackupOpened.Failed -> showProblem(Problem(
            title = when (opened.problem) {
                BackupProblem.NOT_A_BACKUP -> "That isn't a Fuse backup"
                BackupProblem.DAMAGED -> "That backup is damaged"
                BackupProblem.TOO_BIG -> "That file is too big to be a backup"
            },
            message = when (opened.problem) {
                BackupProblem.NOT_A_BACKUP -> "${file.name} isn't a file Fuse made with Back up now. Pick a .fusebackup file."
                BackupProblem.DAMAGED -> "${file.name} is a Fuse backup, but what's inside can't be read. It may not have finished copying."
                BackupProblem.TOO_BIG -> "Fuse backups are much smaller than ${file.name}. Pick the .fusebackup file Fuse made."
            },
            kind = ProblemKind.FILE,
            severity = Severity.ATTENTION,
        ))
        is BackupOpened.Ready -> chooseRestore(opened.preview)
    }
}

/** What restoring [preview] would do, and the parts to choose from. */
private fun AppState.chooseRestore(preview: BackupPreview) {
    val made = "Made ${agoText(preview.createdAt)} with Fuse ${preview.fuseVersion} on ${hostName(preview.host)}."
    val games = when {
        preview.games == 0 -> null
        preview.gamesHere == preview.games -> "It has changes for ${counted(preview.games, "game")}, all in this library."
        preview.gamesHere == 0 -> "None of its ${counted(preview.games, "game")} are in this library yet, so game changes would be left out."
        else -> "It has changes for ${counted(preview.games, "game")}: ${preview.gamesHere} are in this library, the rest are left out."
    }
    val newer = "It's from a newer Fuse, so what this one doesn't know yet stays out.".takeIf { preview.newer }
    val message = listOfNotNull(made, games, newer, "Nothing here is deleted.").joinToString(" ")
    fun restore(parts: Set<BackupPart>) {
        choice = null
        scope.launch {
            val report = store.backup.restore(preview, parts)
            if (report == null) {
                showProblem(Problem(
                    title = "Couldn't restore the backup",
                    message = "Something went wrong partway, so nothing was changed. Your library and settings are as they were.",
                    kind = ProblemKind.DATA,
                ))
            } else {
                toasts.show(restoredText(report), ToastKind.SUCCESS, durationMs = 5000)
            }
        }
    }
    val library = preview.games + preview.collections + preview.sessions + preview.pictures > 0
    choice = ChoiceSpec(
        title = "Restore this backup?",
        message = message,
        icon = FuseIcons.ArchiveRestore,
        options = buildList {
            add(MenuAction(
                "all", "Everything", FuseIcons.ArchiveRestore,
                detail = "Settings, the look and Home, games, collections, art and play time",
                onSelect = { restore(BackupPart.entries.toSet()) },
            ))
            if (preview.hasSettings) {
                add(MenuAction(
                    "settings", "Settings", FuseIcons.Sliders,
                    detail = "Controls, sound, library, art sources and each system's settings",
                    onSelect = { restore(setOf(BackupPart.SETTINGS)) },
                ))
                add(MenuAction(
                    "look", "Look and Home", FuseIcons.Palette,
                    detail = "The theme, added themes, Flow's rows and the widget board",
                    onSelect = { restore(setOf(BackupPart.APPEARANCE)) },
                ))
            }
            if (library) {
                val usable = preview.gamesHere > 0 || preview.collections > 0
                add(MenuAction(
                    "games", "Games and play time", FuseIcons.Gamepad,
                    detail = "Names, favourites, emulators, collections, chosen art and play time",
                    enabled = usable,
                    unavailableReason = "None of its games are in this library".takeUnless { usable },
                    onSelect = { restore(setOf(BackupPart.LIBRARY, BackupPart.PLAYTIME)) },
                ))
            }
        },
    )
}

private fun restoredText(r: RestoreReport): String {
    val parts = buildList {
        if (r.settings) add("settings")
        if (r.appearance) add("the look and Home")
        if (r.games > 0) add(counted(r.games, "game"))
        if (r.collections > 0) add(counted(r.collections, "collection"))
        if (r.sessions > 0) add(counted(r.sessions, "play session"))
    }
    return if (parts.isEmpty()) "Restored. Nothing needed changing" else "Restored ${parts.joinToString(", ")}"
}

private fun counted(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun hostName(host: String) = when (host.uppercase()) {
    "ANDROID" -> "Android"
    "LINUX" -> "Linux"
    "WINDOWS" -> "Windows"
    "MACOS" -> "macOS"
    else -> host
}
